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
        /// Whether a recovery kit is stored for this account; nil until read.
        var recoveryKitExists: Bool? = nil
        var recoveryKitCreatedAt: String? = nil
        /// A recovery key just made, shown ONCE and never stored on this device.
        var newRecoveryKey: String? = nil
        var state3: String {
            guard record != nil else { return "none" }
            return unlocked ? "unlocked" : "locked"
        }
    }

    @Published var state = State()

    private var snapshotSeeded = false
    private var session: MailE2e.Unlocked?
    private var localRead: Task<Void, Never>?

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

    /// The identity file's stable name: the account's ADDRESS, not the bearer
    /// token. The token is reissued at every sign-in, and `MailE2e` names the
    /// file by a digest of whatever key it is handed — keying it by the token
    /// meant the first rotation orphaned a real key behind a stale filename:
    /// the person sees "Encryption is not set up on this device" while the
    /// protected record sits unreachable on disk, and mail sealed to the key
    /// cannot open. Android's twin names the same rule.
    private var identityKey: String? { SessionStore.address ?? token }

    /// Read the identity file, migrating the old token-keyed name if needed:
    /// v0.3.0 builds wrote under the token digest. Found under the old name
    /// only? Copy it to the stable name and drop the old file, so "Remove the
    /// key" can never leave a resurrectable copy behind.
    private nonisolated static func readIdentityFile(
        key: String, legacy: String?
    ) async -> (record: MailE2e.Identity?, legacyKey: String?) {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                if let record = MailE2e.loadIdentity(accountKey: key) {
                    continuation.resume(returning: (record, nil))
                } else if let legacy, legacy != key,
                          let record = MailE2e.loadIdentity(accountKey: legacy) {
                    continuation.resume(returning: (record, legacy))
                } else {
                    continuation.resume(returning: (nil, nil))
                }
            }
        }
    }

    func load(includeDirectory: Bool = true) {
        guard !snapshotSeeded else { return }
        state.available = MailE2e.available()
        state.unavailableReason = MailE2e.unavailableReason
        guard state.available == true else { return }
        refreshLocal()
        if includeDirectory { loadPublished() }
        if includeDirectory { loadRecoveryStatus() }
    }

    /// Whether a recovery kit is stored for this account. A failed read leaves it
    /// unknown (nil), never "no kit": that would push the person to make a new one.
    func loadRecoveryStatus() {
        guard !snapshotSeeded, let t = token else { return }
        let generation = SessionStore.generation
        Task {
            guard let status = try? await ApiClient.e2eRecoveryStatus(token: t),
                  status.success, generation == SessionStore.generation else { return }
            state.recoveryKitExists = status.exists
            state.recoveryKitCreatedAt = status.createdAt
        }
    }

    /// Make a recovery key and store the kit it locks. Needs the key unlocked: the
    /// kit is the private halves locked a second time, under the new recovery key.
    /// The recovery key itself is shown once and kept nowhere.
    func createRecoveryKey() {
        guard !snapshotSeeded, let t = token else { return }
        guard let unlocked = session else {
            note(String(localized: "Unlock your key first: the recovery kit is made from it.", comment: "End-to-end encryption status note"), bad: true)
            return
        }
        state.busy = true
        Task {
            let recoveryKey = MailE2e.generateRecoveryKey()
            // Two 600,000-round derivations: detached, like setup() and unlock().
            let made = await Task.detached {
                E2eModel.attempt { try MailE2e.buildRecoveryKit(unlocked, recoveryKey: recoveryKey) }
            }.value
            switch made {
            case .failure(let error):
                state.busy = false
                note(Self.describe(error), bad: true)
            case .success(let kit):
                do {
                    let saved = try await ApiClient.e2eRecoverySave(token: t, kit: kit)
                    state.busy = false
                    if saved.success {
                        state.recoveryKitExists = true
                        state.recoveryKitCreatedAt = saved.createdAt
                        state.newRecoveryKey = recoveryKey
                    } else {
                        note(saved.error ?? String(localized: "The recovery kit was not saved.", comment: "End-to-end encryption status note"), bad: true)
                    }
                } catch {
                    state.busy = false
                    note(Self.describe(error), bad: true)
                }
            }
        }
    }

    /// The person has written the recovery key down; it leaves memory now.
    func dismissRecoveryKey() { state.newRecoveryKey = nil }

    /// Remove the stored kit. Encrypted mail then cannot be recovered on a new device.
    func removeRecoveryKit() {
        guard !snapshotSeeded, let t = token else { return }
        state.busy = true
        Task {
            do {
                let r = try await ApiClient.e2eRecoveryRemove(token: t)
                state.busy = false
                if r.success { state.recoveryKitExists = false }
                note(r.success ? String(localized: "The recovery key was removed. Keep this device safe: it now holds the only copy of your key.", comment: "End-to-end encryption status note")
                               : (r.error ?? String(localized: "The recovery kit was not removed.", comment: "End-to-end encryption status note")), bad: !r.success)
            } catch {
                state.busy = false
                note(Self.describe(error), bad: true)
            }
        }
    }

    /// Put the key back on this device from the stored kit: the recovery key opens
    /// it, and the new passphrase protects it here. The same key comes back, so
    /// mail already encrypted to it opens again.
    func restoreWithRecoveryKey(_ recoveryKey: String, passphrase: String, confirm: String) {
        guard !snapshotSeeded, let t = token, let k = identityKey else { return }
        if passphrase.utf16.count < MailE2e.minPassphraseLength {
            note(String(localized: "Use at least \(MailE2e.minPassphraseLength) characters for the passphrase.", comment: "End-to-end encryption status note"), bad: true)
            return
        }
        if passphrase != confirm {
            note(String(localized: "The two passphrases do not match.", comment: "End-to-end encryption status note"), bad: true)
            return
        }
        guard MailE2e.normalizeRecoveryKey(recoveryKey) != nil else {
            note(String(localized: "That recovery key is not valid. It has 28 letters and numbers.", comment: "End-to-end encryption status note"), bad: true)
            return
        }
        state.busy = true
        Task {
            let status: E2eRecoveryStatus
            do {
                status = try await ApiClient.e2eRecoveryStatus(token: t, includeKit: true)
            } catch {
                state.busy = false
                note(Self.describe(error), bad: true)
                return
            }
            guard status.success, let kit = status.kit else {
                state.busy = false
                if status.success { state.recoveryKitExists = false }
                note(status.error ?? String(localized: "There is no recovery kit for this account.", comment: "End-to-end encryption status note"), bad: true)
                return
            }
            let restored = await Task.detached {
                E2eModel.attempt {
                    let identity = try MailE2e.restoreFromRecoveryKit(accountKey: k, kit: kit,
                                                                      recoveryKey: recoveryKey,
                                                                      newPassphrase: passphrase)
                    return (identity, try MailE2e.unlock(identity, passphrase: passphrase))
                }
            }.value
            state.busy = false
            switch restored {
            case .failure(let error):
                note(Self.describe(error), bad: true)
            case .success(let (identity, opened)):
                guard identityKey == k else { return }
                session = opened
                state.record = identity
                state.fingerprint = MailE2e.fingerprint(publicKeyB64: identity.encryption.publicKey)
                state.unlocked = true
                note(String(localized: "Your key is back on this device and unlocked. Encrypted mail opens here again.", comment: "End-to-end encryption status note"), bad: false)
            }
        }
    }

    func refreshLocal() {
        guard !snapshotSeeded, let key = identityKey else { return }
        localRead?.cancel()
        let legacy = token
        let generation = SessionStore.generation
        localRead = Task { [weak self] in
            let result = await Self.readIdentityFile(key: key, legacy: legacy)
            guard let self, !Task.isCancelled, !self.state.busy,
                  generation == SessionStore.generation else { return }
            if let old = result.legacyKey, let record = result.record,
               MailE2e.saveIdentity(accountKey: key, identity: record) {
                MailE2e.clearIdentity(accountKey: old)
            }
            var updated = self.state
            updated.record = result.record
            updated.fingerprint = result.record.flatMap {
                MailE2e.fingerprint(publicKeyB64: $0.encryption.publicKey)
            }
            self.state = updated
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
                    state.publishedError = r.error ?? String(localized: "The key directory did not answer.", comment: "End-to-end encryption: error reading the published key")
                }
            } catch {
                state.publishedLoaded = true
                state.publishedError = Self.describe(error)
            }
        }
    }

    func setup(passphrase: String, confirm: String, publishOnSetup: Bool) {
        guard !snapshotSeeded, let t = identityKey else { return }
        if passphrase.utf16.count < MailE2e.minPassphraseLength {
            note(String(localized: "Use at least \(MailE2e.minPassphraseLength) characters for the passphrase.", comment: "End-to-end encryption status note"), bad: true)
            return
        }
        if passphrase != confirm {
            note(String(localized: "The two passphrases do not match.", comment: "End-to-end encryption status note"), bad: true)
            return
        }
        state.busy = true
        // Both the identity generation and the first unlock sit behind
        // Task.detached, exactly as seal() and decrypt() do: setup derives up
        // to FOUR 600,000-round PBKDF2 blocks, and a plain Task inherits this
        // class's @MainActor — on the main thread that is tens of seconds of
        // freeze per press and a watchdog candidate. attempt is nonisolated
        // precisely so the work stays where detached puts it. The outer Task
        // is what makes the await legal: setup() is called from a button.
        Task {
            let made = await Task.detached {
                E2eModel.attempt {
                    let identity = try MailE2e.generateIdentity(accountKey: t, passphrase: passphrase)
                    return (identity, try MailE2e.unlock(identity, passphrase: passphrase))
                }
            }.value
            switch made {
            case .failure(let error):
                state.busy = false
                note(Self.describe(error), bad: true)
            case .success(let (identity, opened)):
                // The generation just proved the passphrase, so the same phrase
                // must open the fresh key; if that second step ever fails it is
                // said, not swallowed — "Encryption is on" with a locked key and
                // no reason would be a lie by omission.
                session = opened
                state.record = identity
                state.fingerprint = MailE2e.fingerprint(publicKeyB64: identity.encryption.publicKey)
                state.unlocked = true
                if publishOnSetup {
                    await publishNow()
                } else {
                    state.busy = false
                    note(String(localized: "Encryption is on. Your key is private to this device until you publish it.", comment: "End-to-end encryption status note"),
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
                    ? String(localized: "Encryption is on, and your key is published so others can find it.", comment: "End-to-end encryption status note")
                    : String(localized: "Your key is saved on this device and stored on the server, but it is not findable by senders yet. That happens when the mailbox is not active. Mail sent to you stays unencrypted until that is fixed.", comment: "End-to-end encryption status note"),
                    bad: !r.discoverable)
            } else {
                note(r.error ?? String(localized: "The directory refused those keys.", comment: "End-to-end encryption status note"), bad: true)
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
                    ? String(localized: "Unpublished. New mail to you will not be encrypted. Messages already encrypted stay readable on this device.", comment: "End-to-end encryption status note")
                    : r.error ?? String(localized: "The directory did not remove the key.", comment: "End-to-end encryption status note"),
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
            // Two 600k-round derivations per press: off the main actor, same
            // reason as setup().
            let opened = await Task.detached {
                E2eModel.attempt { try MailE2e.unlock(record, passphrase: passphrase) }
            }.value
            state.busy = false
            switch opened {
            case .success(let s):
                session = s
                state.unlocked = true
                note(String(localized: "Unlocked for this session. It locks again when the app is closed.", comment: "End-to-end encryption status note"), bad: false)
                bindAfterPassphraseUnlock(s)
            case .failure(let error):
                note(Self.describe(error), bad: true)
            }
        }
    }

    // ── biometric-bound unlock (BiometricKeyWrap.swift) ────────────────────
    // Additive: the passphrase record is never touched. With app lock on, a
    // passphrase unlock is followed by a re-wrap into a .biometryCurrentSet
    // Keychain item; later unlocks can use Face ID / Touch ID.

    var canBiometricUnlock: Bool {
        guard !snapshotSeeded, state.record != nil, !state.unlocked,
              AppLock.shared.enabled, let k = identityKey else { return false }
        return BiometricKeyWrap.hasBoundCopy(accountKey: k)
    }

    private func bindAfterPassphraseUnlock(_ unlocked: MailE2e.Unlocked) {
        guard AppLock.shared.enabled, let k = identityKey,
              !BiometricKeyWrap.hasBoundCopy(accountKey: k) else { return }
        // Failure is silent by design: the passphrase path keeps working.
        Task.detached { BiometricKeyWrap.bind(accountKey: k, unlocked: unlocked) }
    }

    func unlockWithBiometrics() {
        guard !snapshotSeeded, let record = state.record, let k = identityKey else { return }
        let expected = record.encryption.publicKey
        let reason = String(localized: "Unlock your encryption key", comment: "Face ID prompt reason for the end-to-end encryption key")
        Task {
            let outcome = await Task.detached {
                BiometricKeyWrap.open(accountKey: k, expectedPublicKey: expected, reason: reason)
            }.value
            switch outcome {
            case .opened(let s):
                session = s
                state.unlocked = true
                note(String(localized: "Unlocked for this session. It locks again when the app is closed.", comment: "End-to-end encryption status note"), bad: false)
            case .invalidated:
                note(String(localized: "Your Face ID or Touch ID settings changed, so biometric unlock was turned off for this key. Unlock with your passphrase. If you have forgotten it, use your recovery key.", comment: "End-to-end encryption status note"), bad: true)
            case .unavailable:
                break
            }
        }
    }

    func lock() {
        guard !snapshotSeeded else { return }
        session = nil
        state.unlocked = false
        note(String(localized: "Locked.", comment: "End-to-end encryption status note"), bad: false)
    }

    /// Sign-out and session expiry go through this. The file's own contract —
    /// "never written to disk, never logged, cleared on lock, SIGN-OUT and
    /// process death" — had the sign-out leg missing: the model is a
    /// @StateObject on the root view, so path.removeAll() leaves it alive,
    /// and the next account to sign in on the same device would find the
    /// previous person's plaintext private halves still in `session` and its
    /// record in `state` — unlocked, fingerprinted, and ready to seal mail
    /// with a stranger's key. A clear the UI forgets to call is a leak; this
    /// is called from every sign-out door and from the expiry handler.
    func clearForSignOut() {
        localRead?.cancel()
        session = nil
        state = State()
    }

    func changePassphrase(oldPhrase: String, newPhrase: String) {
        guard !snapshotSeeded, let t = identityKey, let record = state.record else { return }
        if newPhrase.utf16.count < MailE2e.minPassphraseLength {
            note(String(localized: "Use at least \(MailE2e.minPassphraseLength) characters for the new passphrase.", comment: "End-to-end encryption status note"),
                 bad: true)
            return
        }
        state.busy = true
        Task {
            // Four 600k-round derivations (unwrap old, wrap new, twice over):
            // detached for the same reason as setup() and unlock().
            let done = await Task.detached {
                E2eModel.attempt {
                    let reEnc = try MailE2e.changePassphrase(record.encryption,
                                                             oldPassphrase: oldPhrase,
                                                             newPassphrase: newPhrase)
                    let reSign = try record.signing.map {
                        try MailE2e.changePassphrase($0, oldPassphrase: oldPhrase, newPassphrase: newPhrase)
                    }
                    guard MailE2e.saveIdentity(accountKey: t,
                          identity: MailE2e.Identity(encryption: reEnc, signing: reSign,
                                                    createdAt: record.createdAt)) else {
                        throw MailE2e.E2eError(text: String(localized: "This device would not store the updated key.", comment: "End-to-end encryption error when saving the re-encrypted key fails"))
                    }
                }
            }.value
            state.busy = false
            switch done {
            case .success:
                refreshLocal()
                note(String(localized: "Passphrase changed. The key itself is unchanged, so old messages still open.", comment: "End-to-end encryption status note"),
                     bad: false)
            case .failure(let error):
                note(Self.describe(error), bad: true)
            }
        }
    }

    func removeKey() {
        guard !snapshotSeeded else { return }
        localRead?.cancel()
        state.busy = true
        // Both names: the stable one, and the token-keyed one a v0.3.0 build
        // may still have on disk. "Remove the key from this device" that left
        // a copy for the migration to resurrect would be a lie.
        if let k = identityKey { MailE2e.clearIdentity(accountKey: k); BiometricKeyWrap.forget(accountKey: k) }
        if let t = token { MailE2e.clearIdentity(accountKey: t) }
        session = nil
        state.record = nil
        state.unlocked = false
        state.fingerprint = nil
        state.busy = false
        note(String(localized: "The key has been removed from this device. Any message encrypted to it can no longer be read here.", comment: "End-to-end encryption status note"), bad: false)
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
                if !r.success { note(r.error ?? String(localized: "The directory did not answer.", comment: "End-to-end encryption status note"), bad: true) }
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
            note(String(localized: "Your key is locked — unlock it before sending encrypted mail.", comment: "End-to-end encryption status note"), bad: true)
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
            note(Self.describe(error), bad: true)
            return nil
        }
    }

    /// Open a stored envelope with this device's key. The passphrase unlocks
    /// the record for exactly this one operation; nothing is kept. A wrong
    /// phrase gets the module's own line, and a message not sealed to this key
    /// says so — ciphertext is never shown as if it were plaintext.
    func decrypt(envelopeJson: String, passphrase: String) async -> String? {
        let generation = SessionStore.generation
        await localRead?.value
        guard !Task.isCancelled, generation == SessionStore.generation else { return nil }
        guard let record = state.record else {
            note(String(localized: "This device has no key to open messages with.", comment: "End-to-end encryption status note"), bad: true)
            return nil
        }
        let result = await Task.detached {
            E2eModel.attempt {
                let priv = try MailE2e.unlockPrivateKey(record.encryption, passphrase: passphrase)
                return try MailE2e.open(envelopeJson, recipientPrivateKeyB64: priv)
            }
        }.value
        guard !Task.isCancelled, generation == SessionStore.generation else { return nil }
        switch result {
        case .success(let plain): return plain
        case .failure(let error):
            note(Self.describe(error), bad: true)
            return nil
        }
    }

    func canSealWithThisDevice() -> Bool { session != nil }

    var openpgpExportNote: String {
        String(localized: "OpenPGP export lives on the web client — this device holds the key, but the gpg-shaped file is built in the browser. Nothing here is missing from your key; it is one action the phone does not offer.", comment: "End-to-end encryption: why there is no OpenPGP export on the phone")
    }

    func clearNote() { state.note = nil }

    private func note(_ text: String, bad: Bool) {
        state.note = text
        state.noteIsBad = bad
    }

    private nonisolated static func describe(_ error: Error) -> String {
        if let api = error as? ApiError, case let .http(_, _, message) = api,
           !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return message }
        // The module's own sentences are the whole point of crafting them:
        // "That passphrase did not unlock this key." must survive to the
        // screen, where the generic line below is only for errors with no
        // message of their own.
        if let e2e = error as? MailE2e.E2eError { return e2e.text }
        return String(localized: "That did not go through.", comment: "End-to-end encryption status note")
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
