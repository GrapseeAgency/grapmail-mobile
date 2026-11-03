import Foundation
import Combine

/// The encryption sector's state — the mirror of Android's `E2eViewModel`.
/// The device's identity, the in-memory unlock session, and the directory's
/// published answer, with the three states kept apart exactly as the web
/// page keeps them: no key, key-but-locked, unlocked. The plaintext private
/// halves live in `session` and nowhere else: never written to disk, never
/// logged, cleared on lock, sign-out and process death.
@MainActor
final class E2eModel: ObservableObject {

    struct State {
        var available: Bool? = nil
        var unavailableReason: String? = nil
        var record: MailE2e.Identity? = nil
        var unlocked = false
        var fingerprint: String? = nil
        var published: MailE2eKeys? = nil
        var publishedLoaded = false
        var publishedError: String? = nil
        var lookup: [MailE2eLookupEntry] = []
        var lookupFor: [String] = []
        var busy = false
        var note: String? = nil
        var noteIsBad = false
        var state3: String {
            guard record != nil else { return "none" }
            return unlocked ? "unlocked" : "locked"
        }
    }

    @Published var state = State()

    private var snapshotSeeded = false
    private var session: MailE2e.Unlocked?

    func seedForSnapshot(record: MailE2e.Identity?, published: MailE2eKeys?,
                         fingerprint: String?, unlocked: Bool = false,
                         lookup: [MailE2eLookupEntry] = [], lookupFor: [String] = []) {
        snapshotSeeded = true
        state.available = true
        state.record = record
        state.fingerprint = fingerprint
        state.unlocked = unlocked
        state.published = published
        state.publishedLoaded = published != nil
        state.lookup = lookup
        state.lookupFor = lookupFor
    }

    private var token: String? { SessionStore.token }

    func load() {
        guard !snapshotSeeded else { return }
        state.available = MailE2e.available()
        state.unavailableReason = MailE2e.unavailableReason
        guard state.available == true else { return }
        refreshLocal()
        loadPublished()
    }

    func refreshLocal() {
        guard !snapshotSeeded, let t = token else { return }
        let record = MailE2e.loadIdentity(accountKey: t)
        state.record = record
        state.fingerprint = record.flatMap {
            MailE2e.fingerprint(publicKeyB64: $0.encryption.publicKey)
        }
    }

    /// The server's published state. A failed read is NOT "nothing is
    /// published": showing the absence would send the person to publish twice,
    /// or to believe their key is gone.
    func loadPublished() {
        guard !snapshotSeeded, let t = token else { return }
        Task {
            do {
                let r = try await ApiClient.mailE2eKeys(t)
                state.publishedLoaded = true
                if r.success {
                    state.published = r.published ? r.keys : nil
                    state.publishedError = nil
                } else {
                    state.publishedError = r.error ?? "The key directory did not answer."
                }
            } catch {
                state.publishedLoaded = true
                state.publishedError = Self.describe(error)
            }
        }
    }

    func setup(passphrase: String, confirm: String, publishOnSetup: Bool) {
        guard !snapshotSeeded, let t = token else { return }
        if passphrase.count < MailE2e.minPassphraseLength {
            note("Use at least \(MailE2e.minPassphraseLength) characters for the passphrase.", bad: true)
            return
        }
        if passphrase != confirm {
            note("The two passphrases do not match.", bad: true)
            return
        }
        state.busy = true
        Task {
            let made = E2eModel.attempt { try MailE2e.generateIdentity(accountKey: t, passphrase: passphrase) }
            switch made {
            case .failure(let error):
                state.busy = false
                note(error.localizedDescription, bad: true)
                return
            case .success(let identity):
                session = try? MailE2e.unlock(identity, passphrase: passphrase)
                state.record = identity
                state.fingerprint = MailE2e.fingerprint(publicKeyB64: identity.encryption.publicKey)
                state.unlocked = session != nil
                if publishOnSetup {
                    await publishNow()
                } else {
                    state.busy = false
                    note("Encryption is on. Your key is private to this device until you publish it.",
                         bad: false)
                }
            }
        }
    }

    func publish() {
        guard !snapshotSeeded else { return }
        Task { await publishNow() }
    }

    private func publishNow() async {
        guard let t = token, let record = state.record else { return }
        state.busy = true
        do {
            let r = try await ApiClient.mailE2ePublish(
                t, encryptionPublicKey: record.encryption.publicKey,
                signingPublicKey: record.signing?.publicKey)
            state.busy = false
            if r.success {
                loadPublished()
                note(r.discoverable
                    ? "Encryption is on, and your key is published so others can find it."
                    : "Your key is saved on this device and stored on the server, but it is not "
                      + "findable by senders yet. That happens when the mailbox is not active. "
                      + "Mail sent to you stays unencrypted until that is fixed.",
                    bad: !r.discoverable)
            } else {
                note(r.error ?? "The directory refused those keys.", bad: true)
            }
        } catch {
            state.busy = false
            note(Self.describe(error), bad: true)
        }
    }

    func unpublish() {
        guard !snapshotSeeded, let t = token else { return }
        state.busy = true
        Task {
            do {
                let r = try await ApiClient.mailE2eUnpublish(t)
                state.busy = false
                loadPublished()
                note(r.success
                    ? "Unpublished. New mail to you will not be encrypted. Messages already "
                      + "encrypted stay readable on this device."
                    : r.error ?? "The directory did not remove the key.",
                    bad: !r.success)
            } catch {
                state.busy = false
                note(Self.describe(error), bad: true)
            }
        }
    }

    func unlock(passphrase: String) {
        guard !snapshotSeeded, let record = state.record else { return }
        state.busy = true
        Task {
            let opened = E2eModel.attempt { try MailE2e.unlock(record, passphrase: passphrase) }
            state.busy = false
            switch opened {
            case .success(let s):
                session = s
                state.unlocked = true
                note("Unlocked for this session. It locks again when the app is closed.", bad: false)
            case .failure(let error):
                note(error.localizedDescription, bad: true)
            }
        }
    }

    func lock() {
        guard !snapshotSeeded else { return }
        session = nil
        state.unlocked = false
        note("Locked.", bad: false)
    }

    func changePassphrase(oldPhrase: String, newPhrase: String) {
        guard !snapshotSeeded, let t = token, let record = state.record else { return }
        if newPhrase.count < MailE2e.minPassphraseLength {
            note("Use at least \(MailE2e.minPassphraseLength) characters for the new passphrase.",
                 bad: true)
            return
        }
        state.busy = true
        Task {
            let done = E2eModel.attempt {
                let reEnc = try MailE2e.changePassphrase(record.encryption,
                                                         oldPassphrase: oldPhrase,
                                                         newPassphrase: newPhrase)
                let reSign = try record.signing.map {
                    try MailE2e.changePassphrase($0, oldPassphrase: oldPhrase, newPassphrase: newPhrase)
                }
                guard MailE2e.saveIdentity(accountKey: t,
                      identity: MailE2e.Identity(encryption: reEnc, signing: reSign,
                                                createdAt: record.createdAt)) else {
                    throw MailE2e.E2eError(text: "This device would not store the updated key.")
                }
            }
            state.busy = false
            switch done {
            case .success:
                refreshLocal()
                note("Passphrase changed. The key itself is unchanged, so old messages still open.",
                     bad: false)
            case .failure(let error):
                note(error.localizedDescription, bad: true)
            }
        }
    }

    func removeKey() {
        guard !snapshotSeeded, let t = token else { return }
        state.busy = true
        MailE2e.clearIdentity(accountKey: t)
        session = nil
        state.record = nil
        state.unlocked = false
        state.fingerprint = nil
        state.busy = false
        note("The key has been removed from this device. Any message encrypted to it can "
             + "no longer be read here.", bad: false)
    }

    func lookup(addresses: [String]) {
        guard !snapshotSeeded, let t = token else { return }
        state.busy = true
        Task {
            do {
                let r = try await ApiClient.mailE2eLookup(t, addresses: addresses)
                state.busy = false
                state.lookup = r.success ? r.results : []
                state.lookupFor = addresses
                if !r.success { note(r.error ?? "The directory did not answer.", bad: true) }
            } catch {
                state.busy = false
                note(Self.describe(error), bad: true)
            }
        }
    }

    /// The sealed send: the plan decided, this device encrypts, the server only
    /// ever sees ciphertext. Returns the envelope JSON to send, or nil with a
    /// reason in the note — a locked key says so instead of failing silently.
    func seal(plaintext: String, recipientKeys: [String]) async -> String? {
        guard let s = session else {
            note("Your key is locked — unlock it before sending encrypted mail.", bad: true)
            return nil
        }
        let signing: MailE2e.KeyPairB64? = s.signingPrivateKey.map {
            MailE2e.KeyPairB64(publicKey: s.signingPublicKey ?? "", privateKey: $0)
        }
        let result = await Task.detached {
            E2eModel.attempt {
                try MailE2e.sealToRecipients(plaintext: plaintext,
                                             recipientPublicKeys: recipientKeys,
                                             signing: signing)
            }
        }.value
        switch result {
        case .success(let envelope):
            return MailE2e.envelopeJson(envelope)
        case .failure(let error):
            note(error.localizedDescription, bad: true)
            return nil
        }
    }

    /// Open a stored envelope with this device's key. The passphrase unlocks
    /// the record for exactly this one operation; nothing is kept. A wrong
    /// phrase gets the module's own line, and a message not sealed to this key
    /// says so — ciphertext is never shown as if it were plaintext.
    func decrypt(envelopeJson: String, passphrase: String) async -> String? {
        guard let record = state.record else {
            note("This device has no key to open messages with.", bad: true)
            return nil
        }
        let result = await Task.detached {
            E2eModel.attempt {
                let priv = try MailE2e.unlockPrivateKey(record.encryption, passphrase: passphrase)
                return try MailE2e.open(envelopeJson, recipientPrivateKeyB64: priv)
            }
        }.value
        switch result {
        case .success(let plain): return plain
        case .failure(let error):
            note(error.localizedDescription, bad: true)
            return nil
        }
    }

    func canSealWithThisDevice() -> Bool { session != nil }

    var openpgpExportNote: String {
        "OpenPGP export lives on the web client — this device holds the key, but "
        + "the gpg-shaped file is built in the browser. Nothing here is missing "
        + "from your key; it is one action the phone does not offer."
    }

    func clearNote() { state.note = nil }

    private func note(_ text: String, bad: Bool) {
        state.note = text
        state.noteIsBad = bad
    }

    private nonisolated static func describe(_ error: Error) -> String {
        if let api = error as? ApiError, case let .http(_, _, message) = api,
           !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return message }
        return "That did not go through."
    }
}

/// Run a throwing crypto call and hand back the failure as an `Error` whose
/// localizedDescription is the module's own sentence — the only surface the
/// screens ever see. A raw stack trace reaching the UI would leak more about
/// the key material than the person needs and more than this app ever shows.
extension E2eModel {
    // `nonisolated`: a detached crypto child must be able to call this
    // WITHOUT hopping back to the main actor -- otherwise the 600,000-round
    // derivation this wraps runs on the main thread, the exact thing the
    // Task.detached around it exists to prevent. The helper is pure.
    nonisolated static func attempt<T>(_ body: () throws -> T) -> Result<T, Error> {
        do { return .success(try body()) }
        catch { return .failure(error) }
    }
}
