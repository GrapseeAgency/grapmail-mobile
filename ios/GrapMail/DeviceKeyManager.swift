import CryptoKit
import Foundation
import LocalAuthentication

/// The Ed25519 signing key that makes THIS install a trusted device.
///
/// ── WHAT IT IS ──────────────────────────────────────────────────────────────
/// One Curve25519 (Ed25519) keypair per install. The private half never leaves
/// the Keychain; the server holds only the public half, enrolled over an
/// authenticated session (TrustedDeviceManager). Later, when the password is
/// lost, the account asks this device to sign a fresh nonce — possession of the
/// private half is the recovery proof (`/grapsee/auth/recovery/device/verify`).
///
/// ── THE TWO CONTRACT POINTS ────────────────────────────────────────────────
/// 1. Enroll sends `publicKey` as base64 **SPKI DER**. The server parses it
///    with node:crypto `createPublicKey({ format: 'der', type: 'spki' })`, so
///    the raw 32-byte Curve25519 public key is wrapped in the fixed 12-byte
///    Ed25519 SPKI prefix (`302a300506032b6570032100`) before encoding — 44
///    DER bytes total. Any other wrapping is a key the server refuses.
/// 2. `sign(_:)` signs the RAW nonce bytes. Curve25519.Signing hashes nothing
///    itself and the server verifies Ed25519 over the nonce verbatim
///    (`verify(null, …)` — null is only legal for the Ed curves); a pre-hash
///    here would produce a signature that verifies against the wrong message.
///    (Android's es256 path is the opposite: ECDSA needs SHA-256 named.)
///
/// ── THE BIOMETRIC GATE ─────────────────────────────────────────────────────
/// Signing goes through LocalAuthentication: one LAContext prompt
/// (`deviceOwnerAuthenticationWithBiometrics`, falling back to the full
/// `.deviceOwnerAuthentication` ladder) per 60-second window, cached so a
/// recovery flow that signs once is not re-prompted for every retry. Every
/// user-facing signature — the recovery challenge above included — MUST go
/// through `sign(_:)`, which is where the gate lives; there is no ungated
/// signing entry point for a screen to reach around it.
///
/// ── THE HONESTY RULE ───────────────────────────────────────────────────────
/// A device with no passcode and no biometrics cannot be prompted, and a hard
/// refusal there would lock recovery out of the one device class that most
/// needs it. Such a device signs anyway, but the state is REPORTED — the
/// outcome carries `.unlockedWithoutLock` and `keyGuardLabel` says
/// "Device key (no biometric lock)" — so the UI records what this key is
/// actually protected by instead of implying a lock that does not exist (the
/// same honesty rule the Android side applies to its device-key states).
///
/// ── STORAGE ────────────────────────────────────────────────────────────────
/// The raw 32-byte private representation, base64-encoded as a string, in the
/// same generic-password Keychain the session token uses (`Keychain` in
/// SessionStore.swift, service attributes `grapmail.device-key`,
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` — available for the
/// recovery flow after a reboot, but never migrated off the phone by backups).
/// Not in Secure Enclave on purpose: CryptoKit's SecureEnclave.Signing is
/// P-256 only, and the server contract for iOS is Ed25519.
enum DeviceKeyManager {
    /// The Keychain item's account attribute. Namespaced like the other
    /// `grapmail.*` keys so one Keychain never collides with itself.
    private static let keychainKey = "grapmail.device-key"

    // ── the gate ─────────────────────────────────────────────────────────────
    private static let unlockWindowSeconds: TimeInterval = 60
    private static let gateLock = NSLock()
    private static var unlockedUntil: Date = .distantPast
    private static var unlockedWithoutLock = false

    /// How one gated signing call turned out.
    enum KeyGuardOutcome: Equatable {
        /// The prompt succeeded; signing may proceed.
        case unlocked
        /// The device has no passcode or biometrics to prompt with. Signing
        /// still proceeds — and the UI must say so (`keyGuardLabel`).
        case unlockedWithoutLock
        /// The person cancelled or the prompt failed. Signing is refused.
        case refused
    }

    /// The outcome of one gated signing call: the signature when the key
    /// produced one, plus the gate's own refusal sentence — present exactly
    /// when the GATE (not the key) refused, so a screen can say "the prompt
    /// was cancelled" rather than "the key is broken".
    struct GatedSignature {
        let signature: Data?
        let guardMessage: String?
    }

    /// One LAContext round-trip, cached for `unlockWindowSeconds` so a flow
    /// that signs more than once in a minute is prompted once. The prompt is
    /// the biometrics-first policy — the same ladder AppLock climbs, with the
    /// full `deviceOwnerAuthentication` fallback (passcode) for a device whose
    /// biometrics are broken rather than absent.
    static func ensureUnlocked() async -> KeyGuardOutcome {
        gateLock.lock()
        let cachedUntil = unlockedUntil
        let cachedNoLock = unlockedWithoutLock
        gateLock.unlock()
        if Date() < cachedUntil {
            return cachedNoLock ? .unlockedWithoutLock : .unlocked
        }
        let context = LAContext()
        context.localizedCancelTitle = "Cancel"
        var policyError: NSError?
        let policy: LAPolicy
        if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &policyError) {
            policy = .deviceOwnerAuthenticationWithBiometrics
        } else if context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &policyError) {
            policy = .deviceOwnerAuthentication
        } else {
            // No lock exists to prompt with. Sign anyway, but mark it —
            // `keyGuardLabel` is how the screen keeps this honest.
            markUnlockWindow(withoutLock: true)
            return .unlockedWithoutLock
        }
        do {
            guard try await context.evaluatePolicy(policy, localizedReason: "Sign with this device's key") else {
                return .refused
            }
        } catch {
            return .refused
        }
        markUnlockWindow(withoutLock: false)
        return .unlocked
    }

    /// The honest state of THIS install's key guard, for the UI to print.
    /// Evaluated fresh each read on purpose: `canEvaluatePolicy` prompts
    /// nobody, and a cached answer could call a device "locked" after the
    /// owner turned the passcode off.
    static var keyGuardLabel: String {
        let context = LAContext()
        var error: NSError?
        if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) {
            return "Device key (biometric lock)"
        }
        if context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) {
            return "Device key (passcode lock)"
        }
        return "Device key (no biometric lock)"
    }

    /// Sign-out drops the cached unlock with the session it belonged to — the
    /// next person on this phone must not inherit a gate the previous session
    /// opened, the same rule that clears SecurityFlow's in-memory proof.
    static func resetGate() {
        gateLock.lock()
        unlockedUntil = .distantPast
        unlockedWithoutLock = false
        gateLock.unlock()
    }

    private static func markUnlockWindow(withoutLock: Bool) {
        gateLock.lock()
        unlockedUntil = Date().addingTimeInterval(unlockWindowSeconds)
        unlockedWithoutLock = withoutLock
        gateLock.unlock()
    }

    // ── the key ───────────────────────────────────────────────────────────────

    /// Whether a key exists. The recovery entry point reads this to decide
    /// whether "Use a trusted device instead" is offered at all: a device that
    /// never enrolled cannot prove anything, and offering the button would
    /// only route people into a refusal the server writes for them.
    static func hasKey() -> Bool {
        Keychain.get(keychainKey) != nil
    }

    /// The private key, created on first use.
    ///
    /// Generation is lazy on purpose: a person who signs in and never loses
    /// their password has paid nothing but one Keychain write. A stored item
    /// that cannot be parsed back into a key (a truncated write, a restore
    /// from an older format) is REPLACED rather than left in place — a key
    /// this device cannot load is a key it can never sign with, and keeping
    /// it would fail every future enroll silently.
    static func privateKey() -> Curve25519.Signing.PrivateKey? {
        if let stored = Keychain.get(keychainKey) {
            if let data = Data(base64Encoded: stored),
               let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: data) {
                return key
            }
            Keychain.delete(keychainKey)
        }
        let fresh = Curve25519.Signing.PrivateKey()
        guard Keychain.set(fresh.rawRepresentation.base64EncodedString(), for: keychainKey) else {
            return nil
        }
        return fresh
    }

    /// The public half, base64 SPKI DER — exactly the bytes `POST
    /// /grapsee/auth/devices/enroll` reads into its Ed25519 verifier.
    /// nil only when no key can exist, in which case there is nothing to enroll.
    static func publicKeySPKIBase64() -> String? {
        guard let key = privateKey() else { return nil }
        // The public point, NOT the private representation: `key.rawRepresentation`
        // is the 32 private bytes and must never cross the wire.
        var der = Data([0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00])
        der.append(key.publicKey.rawRepresentation)  // the raw 32-byte public point
        return der.base64EncodedString()
    }

    /// Sign raw bytes (the recovery nonce) with Ed25519 — no pre-hash — after
    /// the biometric gate. The 64-byte signature comes back base64-encoded at
    /// the call site, the wire form the verify route base64-decodes.
    ///
    /// This is the ONLY signing entry point: recovery-challenge signing and
    /// every future user-facing signature go through the gate here, and the
    /// gate's refusal is reported as `guardMessage` so the screen can say why
    /// rather than blaming the key.
    static func sign(_ data: Data) async -> GatedSignature {
        switch await ensureUnlocked() {
        case .refused:
            return GatedSignature(signature: nil, guardMessage: "The device key stayed locked.")
        case .unlocked, .unlockedWithoutLock:
            guard let key = privateKey() else {
                return GatedSignature(signature: nil, guardMessage: nil)
            }
            // `isValidSignature` on the server side reconstructs the message from
            // the nonce string it issued; `signature(for:)` is the direct Ed25519
            // over exactly these bytes.
            return GatedSignature(signature: try? key.signature(for: data), guardMessage: nil)
        }
    }

    /// Replace the keypair. Used only on the re-enroll path: the server
    /// answered a conflict (409/400) for this installation id, meaning its
    /// stored public half no longer matches what this device can sign, so the
    /// private half is worthless and a fresh enrollment is the only repair.
    /// Any signature the old key could still have produced dies with it.
    @discardableResult
    static func rotate() -> Bool {
        Keychain.delete(keychainKey)
        return privateKey() != nil
    }
}
