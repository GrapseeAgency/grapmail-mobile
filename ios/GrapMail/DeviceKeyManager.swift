import CryptoKit
import Foundation

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

    /// Sign raw bytes (the recovery nonce) with Ed25519 — no pre-hash. The
    /// 64-byte signature comes back base64-encoded, the wire form the verify
    /// route base64-decodes.
    static func sign(_ data: Data) -> Data? {
        guard let key = privateKey() else { return nil }
        // `isValidSignature` on the server side reconstructs the message from
        // the nonce string it issued; `signature(for:)` is the direct Ed25519
        // over exactly these bytes.
        return try? key.signature(for: data)
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
