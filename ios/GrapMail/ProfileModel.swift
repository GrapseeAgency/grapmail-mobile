import Foundation
import Combine
import CryptoKit

enum AccountPhotoCache {
    private static let queue = DispatchQueue(label: "com.grapsee.grapmail.account-photo", qos: .userInitiated)
    private static let maxBytes = 10 * 1024 * 1024

    private static func location(token: String) -> URL? {
        let parts = token.split(separator: ".")
        guard parts.count == 3, parts[1].count <= 16_384 else { return nil }
        var payload = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        payload += String(repeating: "=", count: (4 - payload.count % 4) % 4)
        guard let bytes = Data(base64Encoded: payload),
              let claims = (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any],
              let account = claims["sub"] as? String, let session = claims["jti"] as? String,
              let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
        // The locally stored token namespaces the cache. Authorization stays server-side.
        let key = SHA256.hash(data: Data("\(account):\(session)".utf8))
            .map { String(format: "%02x", $0) }.joined()
        return root.appendingPathComponent("grapmail-account-photos", isDirectory: true).appendingPathComponent(key)
    }

    static func read(token: String) async -> Data? {
        guard let file = location(token: token) else { return nil }
        return await withCheckedContinuation { continuation in
            queue.async {
                guard let size = try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                      (1...maxBytes).contains(size) else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: try? Data(contentsOf: file))
            }
        }
    }

    static func store(_ bytes: Data?, token: String) {
        guard let file = location(token: token) else { return }
        queue.async {
            guard let bytes else { return }
            guard !bytes.isEmpty, bytes.count <= maxBytes else { return }
            try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? bytes.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    /// Drop the saved photo for an account. Only explicit removal calls this.
    static func forget(token: String) {
        guard let file = location(token: token) else { return }
        queue.async { try? FileManager.default.removeItem(at: file) }
    }
}

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

    func showSignupAvatar(_ bytes: Data) {
        state.avatar = bytes
        state.avatarTried = true
        if let token { AccountPhotoCache.store(bytes, token: token) }
    }

    private var token: String? { SessionStore.token }

    private var profileLoad: Task<Void, Never>?
    private var avatarLoad: Task<Void, Never>?
    private var profileLoadedAt: TimeInterval?
    private var avatarWriteGeneration = 0

    func watchAvatar() async {
        guard !snapshotSeeded else { return }
        let generation = SessionStore.generation
        // The watch holds one server-side long-poll open until the avatar
        // revision moves. The old loop re-downloaded and re-decoded the full
        // photo every four seconds — nine hundred times an hour for a field
        // that changes roughly never — and fell through to a full profile GET
        // on every poll when the account had no photo at all.
        var revision = ""
        while !Task.isCancelled, generation == SessionStore.generation, let token {
            let writeGeneration = avatarWriteGeneration
            while state.busy || profileLoad != nil {
                // the sleep's only failure is cancellation, and the guard
                // below answers that — the wait itself must never throw
                try? await Task.sleep(nanoseconds: 200_000_000)
                guard !Task.isCancelled, generation == SessionStore.generation else { return }
            }
            guard writeGeneration == avatarWriteGeneration else { continue }
            do {
                let latest = try await ApiClient.watchAvatar(token: token, revision: revision)
                guard !Task.isCancelled, generation == SessionStore.generation else { return }
                guard writeGeneration == avatarWriteGeneration else { continue }
                if latest == revision { continue }
                revision = latest
                let photo = try await ApiClient.avatarBytes(token: token, revision: latest)
                guard !Task.isCancelled, generation == SessionStore.generation else { return }
                guard writeGeneration == avatarWriteGeneration, !state.busy, profileLoad == nil else { continue }
                if let photo {
                    if state.avatar != photo {
                        var updated = state
                        updated.avatar = photo
                        updated.avatarTried = true
                        state = updated
                        AccountPhotoCache.store(photo, token: token)
                    } else if !state.avatarTried {
                        state.avatarTried = true
                    }
                }
            } catch let error as ApiError {
                guard !Task.isCancelled, generation == SessionStore.generation else { return }
                if case let .http(_, code, _) = error, code == "NO_AVATAR" {
                    // no photo on the account: asking again sooner than a
                    // minute is pure churn
                    try? await Task.sleep(nanoseconds: 60_000_000_000)
                    continue
                }
                try? await Task.sleep(nanoseconds: 5_000_000_000)
            } catch {
                if Task.isCancelled { return }
                try? await Task.sleep(nanoseconds: 5_000_000_000)
            }
        }
    }

    func load(force: Bool = false) {
        guard !snapshotSeeded, profileLoad == nil, !state.busy, let token else { return }
        if !force, state.staleError == nil, let profileLoadedAt, ProcessInfo.processInfo.systemUptime - profileLoadedAt < 30 { return }
        if state.profile == nil { state.loading = true }
        let generation = SessionStore.generation
        profileLoad = Task { [weak self] in
            guard let self, !Task.isCancelled else { return }
            defer { if !Task.isCancelled { self.profileLoad = nil } }
            do {
                if self.state.profile == nil,
                   let cached = await MailStore.shared.cachedProfile(generation: generation) {
                    guard !Task.isCancelled, generation == SessionStore.generation else { return }
                    self.state.profile = cached
                    self.state.loading = false
                    self.state.fatal = nil
                    MailDisplay.shared.apply(cached.settings)
                }
                if self.state.avatar == nil, self.state.avatar == nil,
                   let cached = await AccountPhotoCache.read(token: token),
                   await AvatarThumbnail.decode(cached) != nil {
                    guard !Task.isCancelled, generation == SessionStore.generation else { return }
                    if self.state.avatar == nil, !self.state.busy {
                        self.state.avatar = cached
                    }
                }
                guard !Task.isCancelled, generation == SessionStore.generation else { return }
                let resp = try await ApiClient.profile(token: token)
                guard !Task.isCancelled, generation == SessionStore.generation else { return }
                var updated = self.state
                if let fresh = resp.profile {
                    if fresh.avatarUrl != updated.profile?.avatarUrl {
                        self.avatarLoad?.cancel()
                        self.avatarLoad = nil
                        updated.avatarTried = false
                    }
                    updated.profile = fresh
                    self.profileLoadedAt = ProcessInfo.processInfo.systemUptime
                    MailDisplay.shared.apply(fresh.settings)
                }
                updated.loading = false
                updated.fatal = updated.profile == nil ? String(localized: "The account record is unavailable.", comment: "Account settings error when the profile could not be read") : nil
                updated.staleError = nil
                self.state = updated
                if let fresh = resp.profile { await MailStore.shared.putProfile(fresh, generation: generation) }
                if self.avatarLoad == nil { self.loadAvatar() }
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
        guard !snapshotSeeded, !state.avatarTried, !state.busy, avatarLoad == nil, let token,
              let url = state.profile?.avatarUrl?.nilIfBlank() else { return }
        let generation = SessionStore.generation
        avatarLoad = Task { [weak self] in
            guard let self else { return }
            defer { if !Task.isCancelled { self.avatarLoad = nil } }
            do {
                let inline = AvatarThumbnail.inline(url)
                let bytes: Data?
                if let inline, await AvatarThumbnail.decode(inline) != nil { bytes = inline }
                else { bytes = try await ApiClient.avatarBytes(token: token) }
                guard !Task.isCancelled, generation == SessionStore.generation,
                      self.state.profile?.avatarUrl == url else { return }
                var updated = self.state
                if let bytes {
                    updated.avatar = bytes
                    AccountPhotoCache.store(bytes, token: token)
                }
                updated.avatarTried = true
                self.state = updated
            } catch let error as ApiError {
                guard !Task.isCancelled, generation == SessionStore.generation,
                      self.state.profile?.avatarUrl == url else { return }
                if case let .http(_, code, _) = error, code == "NO_AVATAR" {
                    var updated = self.state
                    if updated.avatar == nil, let cached = await AccountPhotoCache.read(token: token) {
                        updated.avatar = cached
                    }
                    updated.avatarTried = true
                    self.state = updated
                }
            } catch {}
        }
    }

    private func beginWrite() -> String? {
        guard !state.busy, let token else { return nil }
        avatarWriteGeneration += 1
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
                MailDisplay.shared.apply(resp.profile?.settings)
                let text = resp.message?.trimmingCharacters(in: .whitespacesAndNewlines)
                state.note = (text?.isEmpty == false) ? text! : label
                state.noteIsBad = false
                if let fresh = resp.profile { await MailStore.shared.putProfile(fresh, generation: generation) }
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
        // Auto-reply and forwarding are enforced on delivery by the backend
        // (lib/mail-automation.ts reads users.settings for every inbound message).
        for (key, value) in [("vacation", patch.vacation.flatMap { try? ApiClient.encoder.encode($0) }),
                             ("forwarding", patch.forwarding.flatMap { try? ApiClient.encoder.encode($0) })] {
            if let data = value, let obj = try? JSONSerialization.jsonObject(with: data) {
                settings[key] = obj
            }
        }
        // Sent whole, every key present (the server keeps any key not sent) —
        // the same `settings.notifications` object NotificationPolicy writes.
        if let v = patch.notifications { settings["notifications"] = v.dictionary }
        save(label: String(localized: "Saved.", comment: "Confirmation after saving account settings"), settings: settings)
    }

    /// The keep-list after one disconnect. `removeConnection` is what the
    /// screen's Remove acts on; the write is the whole remaining list, because
    /// that is the shape the route replaces.
    func saveConnections(keep: [ProfileConnection]) {
        let rows = keep.map { c -> [String: Any?] in
            ["product": c.product, "host": c.host, "scopes": c.scopes,
             "connectedAt": c.connectedAt]
        }
        save(label: String(localized: "Removed.", comment: "Confirmation after removing a connected app"), connections: rows)
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
                        state.note = r.error ?? String(localized: "That alias could not be removed.", comment: "Alias error")
                        state.noteIsBad = true
                        return
                    }
                    msg = r.message ?? String(localized: "Alias removed.", comment: "Alias confirmation")
                } else {
                    let r = try await ApiClient.mailCreateAlias(token, label: value)
                    guard generation == SessionStore.generation else { return }
                    guard r.success else {
                        state.busy = false
                        state.note = r.error ?? r.message ?? String(localized: "The alias could not be created.", comment: "Alias error")
                        state.noteIsBad = true
                        return
                    }
                    msg = r.message ?? String(localized: "Alias created.", comment: "Alias confirmation")
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

    /// Replace or clear the photo. The picked bytes are shown and cached the
    /// moment the upload starts, so the header and profile update instantly;
    /// the server's nonce-bearing URL then supersedes them. A refused upload
    /// keeps what the user picked rather than blanking the shot.
    func uploadAvatar(bytes: Data, filename: String, mime: String, onSaved: @escaping () -> Void = {}) {
        guard let token = beginWrite() else { return }
        let generation = SessionStore.generation
        var optimistic = state
        optimistic.avatar = bytes
        optimistic.avatarTried = true
        state = optimistic
        AccountPhotoCache.store(bytes, token: token)
        Task {
            do {
                let (resp, saved) = try await ApiClient.saveAvatar(
                    token: token, bytes: bytes, filename: filename, mime: mime)
                guard generation == SessionStore.generation else { return }
                state.busy = false
                state.avatar = saved
                AccountPhotoCache.store(saved, token: token)
                state.avatarTried = true
                if let url = resp.avatarUrl { state.profile?.avatarUrl = url }
                let text = resp.message?.trimmingCharacters(in: .whitespacesAndNewlines)
                state.note = (text?.isEmpty == false) ? text! : String(localized: "Profile photo updated.", comment: "Profile photo confirmation")
                state.noteIsBad = false
                onSaved()
                load(force: true)
            } catch {
                guard generation == SessionStore.generation else { return }
                state.busy = false
                state.note = String(localized: "Couldn’t confirm your profile photo. Try again.", comment: "Profile photo error")
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
                guard resp.success else { throw CocoaError(.fileWriteUnknown) }
                state.busy = false
                state.avatar = nil
                AccountPhotoCache.forget(token: token)
                state.avatarTried = true
                state.profile?.avatarUrl = nil
                let text = resp.message?.trimmingCharacters(in: .whitespacesAndNewlines)
                state.note = (text?.isEmpty == false) ? text! : String(localized: "Profile photo removed.", comment: "Profile photo confirmation")
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
        return String(localized: "That change did not go through.", comment: "Account settings error when the server gives no reason")
    }
}
