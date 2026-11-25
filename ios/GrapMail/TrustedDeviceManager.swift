import Foundation
import UIKit

/// Enroll-once: make this install a trusted device for every account that
/// signs in on it.
///
/// ── THE FLOW ────────────────────────────────────────────────────────────────
/// Sign-in posts `.gmSessionChanged` (SessionStore.save). This manager listens
/// for it, and for each affected account that has not been enrolled from this
/// install yet, calls `POST /grapsee/auth/devices/enroll` with the installation
/// id (the same `x-device-id` UUID every API call carries), platform "ios",
/// the Ed25519 public key (DeviceKeyManager) and the person's own device name.
/// Enrollment authenticates with the fresh session's bearer, so a device can
/// never volunteer itself for an account it cannot prove.
///
/// The "enrolled" ledger lives in UserDefaults, keyed by account email —
/// it records WHICH installation id was enrolled, not a secret. The private
/// key stays in the Keychain; the ledger entry is a fact about the server's
/// row, and re-deriving it from the server would cost a list call at every
/// sign-in for information this device already holds.
///
/// ── WHY NOT JUST ENROLL EVERY TIME ─────────────────────────────────────────
/// The enroll route upserts, so a repeat is harmless but not free: it rewrites
/// the row, bumps `lastSeenAt` and writes a `device_enrolled` security event —
/// the person's activity trail would fill with an entry per sign-in. One
/// enrollment per (account, installation) is the contract's intent; the row's
/// `lastSeenAt` is the server's business from there.
///
/// ── THE ROTATION REPAIR ────────────────────────────────────────────────────
/// If enroll answers 409/400 — the server holds a conflicting enrollment for
/// this installation id whose stored public half no longer matches what this
/// device can sign (a Keychain restore from another phone, a wiped key) — the
/// local key is rotated (DeviceKeyManager.rotate) and the enroll is retried
/// once with the new public half. Retrying without rotating would loop the
/// same refusal forever; rotating on ANY failure would discard a healthy key
/// over a network error.
@MainActor
final class TrustedDeviceManager {
    static let shared = TrustedDeviceManager()

    /// Ledger: account email (lowercased) → what was enrolled from this install.
    private struct Enrollment: Codable {
        let deviceId: String
        let installationId: String
    }

    private static let ledgerKey = "grapmail.trusted-devices.enrolled"
    /// Session-change observer, installed once from the app delegate.
    private var observer: NSObjectProtocol?

    func observeSessions() {
        guard observer == nil else { return }
        observer = NotificationCenter.default.addObserver(
            forName: .gmSessionChanged, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                await self?.enrollSignedInAccounts()
            }
        }
        // A restored session at cold launch posts nothing, so the first
        // launch after install enrolls here too. The guard inside makes a
        // no-op out of it whenever there is nothing to enroll.
        Task { await enrollSignedInAccounts() }
    }

    /// Enroll every account this device holds a session for that is not in the
    /// ledger. The active account enrolls first; parked accounts follow, so a
    /// device is a trusted recovery path for each of them, not only the last
    /// one to sign in.
    func enrollSignedInAccounts() async {
        for account in SessionStore.accounts() where !account.address.isEmpty {
            await enroll(account: account.address.lowercased())
        }
    }

    /// One account's enrollment. Not single-flight: two passes racing (the
    /// launch task and a sign-in notification) would both POST, which the
    /// route's upsert tolerates — the loser rewrites the same row, and the
    /// ledger write after whichever lands makes every later pass a no-op.
    func enroll(account email: String) async {
        guard !email.isEmpty else { return }
        if let done = Self.readLedger()?[email], done.installationId == DeviceId.value {
            return  // already enrolled from this install
        }
        // A ledger row from a previous install (Keychain/UserDefaults carried
        // across a device migration) does not match the installation id the
        // server saw — enroll again under the current one.
        await performEnroll(account: email)
    }

    /// Drop one account's ledger row. Nothing calls it today; a future
    /// account-removal path needs the same removal of the enrollment FACT, and
    /// silently forgetting how to forget would leave a row that blocks the
    /// re-enroll a fresh sign-in on the same account should perform.
    func forget(account email: String) {
        var ledger = Self.readLedger() ?? [:]
        ledger.removeValue(forKey: email.lowercased())
        writeLedger(ledger)
    }

    /// Which device id this install enrolled for an account, or nil when it
    /// never did. The trusted-device screen uses it to recognize this phone's
    /// own row: revoking it must also drop the ledger row, or the next sign-in
    /// would skip enrollment while the server no longer trusts the device.
    func enrolledDeviceId(for email: String) -> String? {
        Self.readLedger()?[email.lowercased()]?.deviceId
    }

    private func performEnroll(account email: String) async {
        // No key, no enrollment. Creating it here (rather than at first launch)
        // keeps the Keychain write on a signed-in path, where a failure can be
        // retried at the next sign-in instead of being invisible.
        guard let publicKey = DeviceKeyManager.publicKeySPKIBase64() else { return }
        guard let bearer = await ApiClient.pushBearer(for: email) else { return }
        do {
            let response = try await ApiClient.enrollDevice(
                token: bearer,
                installationId: DeviceId.value,
                publicKey: publicKey,
                displayName: Self.deviceName()
            )
            guard response.success, let device = response.device else { return }
            recordInLedger(email: email, device: device)
            attestAfterEnrollment(token: bearer, deviceId: device.id)
        } catch let error as ApiError {
            // A conflict for this installation id means the server's stored
            // public half cannot verify this device's signatures: rotate the
            // key and offer the new public half once. Any other status (rate
            // limit, upstream trouble) is left for the next sign-in — a
            // network failure is not evidence the key is wrong.
            guard Self.isKeyConflict(error) else { return }
            guard DeviceKeyManager.rotate(), let fresh = DeviceKeyManager.publicKeySPKIBase64() else { return }
            do {
                let retried = try await ApiClient.enrollDevice(
                    token: bearer,
                    installationId: DeviceId.value,
                    publicKey: fresh,
                    displayName: Self.deviceName()
                )
                guard retried.success, let device = retried.device else { return }
                recordInLedger(email: email, device: device)
                attestAfterEnrollment(token: bearer, deviceId: device.id)
            } catch {
                // Still refused after rotation: leave it to the next sign-in.
            }
        } catch {
            // Network failure: the next sign-in notification retries.
        }
    }

    /// App Attest rides enrollment: the device row exists, the bearer is live,
    /// and the route can now be told what this install can prove. Fire-and-
    /// forget, silent on every outcome — attestation strengthens the row, it
    /// never gates it. `.recorded` (the two-stage challenge contract is not
    /// deployed server-side yet), `.unsupported` (no App Attest hardware) and
    /// `.failed` (Apple or the route refused) all leave the device exactly as
    /// trusted as before this call.
    private func attestAfterEnrollment(token: String, deviceId: String) {
        guard AppAttestClient.isSupported else { return }
        Task {
            _ = await AppAttestClient.attestForDevice(token: token, deviceId: deviceId)
        }
    }

    private func recordInLedger(email: String, device: TrustedDeviceDto) {
        var ledger = Self.readLedger() ?? [:]
        ledger[email.lowercased()] = Enrollment(deviceId: device.id, installationId: DeviceId.value)
        writeLedger(ledger)
    }

    /// The statuses that name a bad key rather than a bad network. The enroll
    /// route answers 400 for a malformed public key and 409 for a conflicting
    /// enrollment; both are repaired by rotation, and nothing else is.
    private static func isKeyConflict(_ error: ApiError) -> Bool {
        if case let .http(status, _, _) = error { return status == 400 || status == 409 }
        return false
    }

    /// What the device list should print for this phone. UIDevice's name is
    /// the person's own label for their device ("Arafat's iPhone") — the same
    /// string Android sends via Build.MODEL's user-visible cousin.
    private static func deviceName() -> String {
        let name = UIDevice.current.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? "My device" : String(name.prefix(80))
    }

    private static func readLedger() -> [String: Enrollment]? {
        guard let raw = UserDefaults.standard.string(forKey: ledgerKey),
              let data = raw.data(using: .utf8) else { return [:] }
        return try? JSONDecoder().decode([String: Enrollment].self, from: data)
    }

    private func writeLedger(_ ledger: [String: Enrollment]) {
        guard let data = try? JSONEncoder().encode(ledger),
              let raw = String(data: data, encoding: .utf8) else { return }
        UserDefaults.standard.set(raw, forKey: Self.ledgerKey)
    }
}
