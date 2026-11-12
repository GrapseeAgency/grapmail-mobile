import Foundation
import Combine

/// The account record and its writers — the eight screens the web client
/// splits across `/mail/account/*`, `/mail/settings`, `/mail/notes` and
/// `/mail/tasks` all read and change this one document
/// (`auth-account-profile.ts`). Swift mirror of Android's `ProfileViewModel`;
/// the two hold the same record with the same rules.
///
/// ── WHY ONE MODEL FOR EIGHT SCREENS ─────────────────────────────────────────
/// The PATCH response carries the full updated profile (`:675-679`), so a save
/// refreshes every screen from the same answer rather than firing a GET after
/// it. Eight screens each fetching their own copy would disagree about the
/// record during the window between the write and the read — the notes screen
/// showing a list the tasks screen just overwrote is exactly the drift the
/// shared record exists to prevent.
///
/// ── WHY THE NOTE IS PART OF THE STATE ───────────────────────────────────────
/// A save must be able to say what actually happened: "Saved." only when the
/// server accepted, the server's own refusal text otherwise (the route answers
/// 422 with a human message and a stable code). A screen that shows a success
/// banner over a refused write is the defect class this whole sector was built
/// against.
@MainActor
final class ProfileModel: ObservableObject {

    struct State {
        /// Nil until the first read answers. The screens draw "Loading…" or
        /// "could not be read" from this — never empty strings as if the
        /// account had been asked and had nothing to say.
        var profile: ProfileDto?
        var loading = false
        /// A failed load with nothing on screen.
        var fatal: String?
        /// A failed load that kept the previous record on screen.
        var staleError: String?
        /// The last save's outcome, shown by the screen that made it.
        var note: String?
        var noteIsBad = false
        var busy = false
        /// The photo bytes, loaded lazily. nil means never asked — the profile
        /// carries only the URL, and the URL is same-origin-with-bearer, so
        /// the bytes come from a second call that runs only when a screen
        /// actually draws the avatar.
        var avatar: Data?
        var avatarTried = false
    }

    @Published var state = State()

    /// Test-only seam for the snapshot host, same rule as the mailbox's: the
    /// seed is the frame's content and the boot read must not race it — a live
    /// profile arriving after the seed would photograph the CI account's real
    /// (empty) notes list and call the populated branch unseen.
    private var snapshotSeeded = false
    func seedForSnapshot(_ profile: ProfileDto, avatarBytes: Data? = nil) {
        snapshotSeeded = true
        state.profile = profile
        state.loading = false
        state.avatar = avatarBytes
        state.avatarTried = true
    }

    private var token: String? { SessionStore.token }

    private var profileLoad: Task<Void, Never>?
    private var avatarLoad: Task<Void, Never>?
    private var profileLoadedAt: TimeInterval?

    func load(force: Bool = false) {
        guard !snapshotSeeded, profileLoad == nil, !state.busy, let token else { return }
        if !force, state.staleError == nil, let profileLoadedAt, ProcessInfo.processInfo.systemUptime - profileLoadedAt < 30 { return }
        if state.profile == nil { state.loading = true }
        let generation = SessionStore.generation
        profileLoad = Task { [weak self] in
            guard let self, !Task.isCancelled else { return }
            defer { if !Task.isCancelled { self.profileLoad = nil } }
            do {
                let resp = try await ApiClient.profile(token: token)
                guard !Task.isCancelled, generation == SessionStore.generation else { return }
                var updated = self.state
                if let fresh = resp.profile {
                    if fresh.avatarUrl != updated.profile?.avatarUrl {
                        updated.avatar = nil
                        updated.avatarTried = false
                    }
                    updated.profile = fresh
                    self.profileLoadedAt = ProcessInfo.processInfo.systemUptime
                }
                updated.loading = false
                updated.fatal = updated.profile == nil ? "The account record is unavailable." : nil
                updated.staleError = nil
                self.state = updated
            } catch {
                guard !Task.isCancelled, generation == SessionStore.generation else { return }
                let message = self.reason(error)
                var updated = self.state
                updated.loading = false
                updated.fatal = updated.profile == nil ? message : nil
                updated.staleError = updated.profile != nil ? message : nil
                self.state = updated
            }
        }
    }

    func loadAvatar() {
        guard !snapshotSeeded, !state.avatarTried, !state.busy, let token,
              let url = state.profile?.avatarUrl?.nilIfBlank() else { return }
        avatarLoad?.cancel()
        let generation = SessionStore.generation
        avatarLoad = Task { [weak self] in
            guard let self else { return }
            defer { if !Task.isCancelled { self.avatarLoad = nil } }
            do {
                let bytes = try await ApiClient.avatarBytes(token: token)
                guard !Task.isCancelled, generation == SessionStore.generation,
                      self.state.profile?.avatarUrl == url else { return }
                var updated = self.state
                updated.avatar = bytes
                updated.avatarTried = true
                self.state = updated
            } catch let error as ApiError {
                guard !Task.isCancelled, generation == SessionStore.generation,
                      self.state.profile?.avatarUrl == url else { return }
                if case let .http(status, _, _) = error, status == 404 {
                    var updated = self.state
                    updated.avatar = nil
                    updated.avatarTried = true
                    self.state = updated
                }
            } catch {}
        }
    }

    private func beginWrite() -> String? {
        guard !state.busy, let token else { return nil }
        profileLoad?.cancel()
        profileLoad = nil
        avatarLoad?.cancel()
        avatarLoad = nil
        var updated = state
        updated.busy = true
        updated.loading = false
        updated.note = nil
        state = updated
        return token
    }

    /// Apply one partial write and settle the note from the server's answer.
    ///
    /// The PATCH response IS the new record, so there is no follow-up GET — a
    /// second read would open a window where two screens show two versions of
    /// the same document.
    func save(label: String, personal: [String: String?] = [:],
              settings: [String: Any?]? = nil,
              connections: [[String: Any?]]? = nil) {
        guard let token = beginWrite() else { return }
        let generation = SessionStore.generation
        Task {
            do {
                let resp = try await ApiClient.patchProfile(
                    token: token, personal: personal, settings: settings,
                    connections: connections)
                guard generation == SessionStore.generation else { return }
                state.busy = false
                state.profile = resp.profile ?? state.profile
                if resp.profile != nil { profileLoadedAt = ProcessInfo.processInfo.systemUptime }
                let text = resp.message?.trimmingCharacters(in: .whitespacesAndNewlines)
                state.note = (text?.isEmpty == false) ? text! : label
                state.noteIsBad = false
            } catch {
                guard generation == SessionStore.generation else { return }
                state.busy = false
                state.note = reason(error)
                state.noteIsBad = true
            }
        }
    }

    // ── the settings writers, one per editable key-group ─────────────────────

    func saveSettings(_ patch: ProfileSettingsPatch) {
        var settings: [String: Any?] = [:]
        if let v = patch.signature { settings["signature"] = v }
        if let v = patch.density { settings["density"] = v }
        if let v = patch.signatures { settings["signatures"] = v }
        if let v = patch.blockedSenders { settings["blockedSenders"] = v }
        if let v = patch.safeSenders { settings["safeSenders"] = v }
        if let v = patch.notes { settings["notes"] = v }
        if let v = patch.tasks { settings["tasks"] = v }
        if let v = patch.locale, let data = try? ApiClient.encoder.encode(v),
           let obj = try? JSONSerialization.jsonObject(with: data) {
            // A JSON object, not the encoded Data: JSONSerialization refuses
            // raw bytes as a value, and `locale` is a whole nested object on
            // the wire — the caller sends the copy it holds with the one field
            // changed, exactly as Android does.
            settings["locale"] = obj
        }
        // vacation and forwarding are DELIBERATELY absent from the patch type:
        // the sieve interpreter supports the actions but no code turns these
        // settings into rules, so the switches do not appear and nothing here
        // writes a value no pipeline reads.
        save(label: "Saved.", settings: settings)
    }

    /// The keep-list after one disconnect. `removeConnection` is what the
    /// screen's Remove acts on; the write is the whole remaining list, because
    /// that is the shape the route replaces.
    func saveConnections(keep: [ProfileConnection]) {
        let rows = keep.map { c -> [String: Any?] in
            ["product": c.product, "host": c.host, "scopes": c.scopes,
             "connectedAt": c.connectedAt]
        }
        save(label: "Removed.", connections: rows)
    }

    /// Mint or retire an alias — the two routes the mailbox API already
    /// declares, reached from the Addresses screen.
    ///
    /// After either, the profile is re-read rather than patched locally: the
    /// server mints the address itself, so a locally-invented row would be a
    /// claim about a value nobody has seen, and the alias list on the profile
    /// is the same table the settings rows quote — both must move together or
    /// one is lying.
    func aliasWrite(value: String, remove: Bool) {
        guard let token = beginWrite() else { return }
        let generation = SessionStore.generation
        Task {
            do {
                var msg = ""
                if remove {
                    let r = try await ApiClient.mailDeleteAlias(token, address: value)
                    guard generation == SessionStore.generation else { return }
                    guard r.success else {
                        state.busy = false
                        state.note = r.error ?? "That alias could not be removed."
                        state.noteIsBad = true
                        return
                    }
                    msg = r.message ?? "Alias removed."
                } else {
                    let r = try await ApiClient.mailCreateAlias(token, label: value)
                    guard generation == SessionStore.generation else { return }
                    guard r.success else {
                        state.busy = false
                        state.note = r.error ?? r.message ?? "The alias could not be created."
                        state.noteIsBad = true
                        return
                    }
                    msg = r.message ?? "Alias created."
                }
                state.busy = false
                state.noteIsBad = false
                state.note = msg
                snapshotSeeded = false  // a live write re-owns the record
                load(force: true)
            } catch {
                guard generation == SessionStore.generation else { return }
                state.busy = false
                state.note = reason(error)
                state.noteIsBad = true
            }
        }
    }

    /// Replace or clear the photo. On success the server's nonce-bearing URL
    /// replaces the stale one immediately; on failure the old bytes stay — a
    /// refused upload must not blank the photo that is still on the record.
    func uploadAvatar(bytes: Data, filename: String, mime: String) {
        guard let token = beginWrite() else { return }
        let generation = SessionStore.generation
        Task {
            do {
                let resp = try await ApiClient.uploadAvatar(
                    token: token, bytes: bytes, filename: filename, mime: mime)
                guard generation == SessionStore.generation else { return }
                state.busy = false
                state.avatar = bytes
                state.avatarTried = true
                if let url = resp.avatarUrl { state.profile?.avatarUrl = url }
                let text = resp.message?.trimmingCharacters(in: .whitespacesAndNewlines)
                state.note = (text?.isEmpty == false) ? text! : "Profile photo updated."
                state.noteIsBad = false
            } catch {
                guard generation == SessionStore.generation else { return }
                state.busy = false
                state.note = reason(error)
                state.noteIsBad = true
            }
        }
    }

    func removeAvatar() {
        guard let token = beginWrite() else { return }
        let generation = SessionStore.generation
        Task {
            do {
                let resp = try await ApiClient.deleteAvatar(token: token)
                guard generation == SessionStore.generation else { return }
                state.busy = false
                state.avatar = nil
                state.avatarTried = true
                state.profile?.avatarUrl = nil
                let text = resp.message?.trimmingCharacters(in: .whitespacesAndNewlines)
                state.note = (text?.isEmpty == false) ? text! : "Profile photo removed."
                state.noteIsBad = false
            } catch {
                guard generation == SessionStore.generation else { return }
                state.busy = false
                state.note = reason(error)
                state.noteIsBad = true
            }
        }
    }

    func clearNote() {
        state.note = nil
        state.noteIsBad = false
    }

    /// The server's refusal in the person's words, never a status number.
    private func reason(_ error: Error) -> String {
        if let api = error as? ApiError, case let .http(_, _, message) = api,
           !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return message
        }
        return "That change did not go through."
    }
}
