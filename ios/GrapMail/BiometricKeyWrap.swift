import CryptoKit
import Foundation
import LocalAuthentication
import Security

/// Biometric-bound copy of the unlocked E2E private halves — the iOS twin of
/// Android's `security/BiometricKeyWrap.kt` (MASVS-STORAGE-1 / AUTH-2, L2).
///
/// ── WHAT IT ADDS, AND WHAT IT NEVER TOUCHES ────────────────────────────────
/// The canonical record stays the passphrase container `MailE2e.saveIdentity`
/// writes; this file never modifies or deletes it. When app lock is on, the
/// halves opened by a passphrase unlock are also stored as ONE Keychain item:
///   - `SecAccessControl(.biometryCurrentSet)`: every read needs Face ID /
///     Touch ID, and the item becomes unreadable when the enrolled biometrics
///     change (a new face or finger),
///   - `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`: never in a backup,
///     never migrated to another device, and deleted by the OS if the passcode
///     is removed.
///
/// ── MIGRATION SAFETY ───────────────────────────────────────────────────────
/// Nothing is converted in place. The bound copy is written ("re-wrapped") at
/// the next passphrase unlock while app lock is on. If it cannot be written
/// (no biometry enrolled, no passcode, Keychain error) the passphrase path is
/// unchanged. The key can never be lost through this file.
///
/// ── INVALIDATION ───────────────────────────────────────────────────────────
/// A small non-secret marker (UserDefaults) remembers that a bound copy was
/// written. Marker present but item missing/unreadable = invalidated by a
/// biometry change or passcode removal → `.invalidated`, the marker is
/// cleared, and the UI asks for the passphrase (or the recovery key). Never a
/// crash.
enum BiometricKeyWrap {
    private static let service = "com.grapsee.grapmail.e2e.biometric-wrap"
    private static let markerPrefix = "grapmail.e2e.bound."

    enum Outcome {
        case opened(MailE2e.Unlocked)
        case invalidated
        case unavailable
    }

    static var deviceSupports: Bool {
        var error: NSError?
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
    }

    private static func account(_ accountKey: String) -> String {
        // Same digest naming as the identity file: no address in the clear.
        BoundName.of(accountKey)
    }

    static func hasBoundCopy(accountKey: String) -> Bool {
        UserDefaults.standard.bool(forKey: markerPrefix + account(accountKey))
    }

    /// Blocking Keychain write; call off the main actor.
    @discardableResult
    static func bind(accountKey: String, unlocked: MailE2e.Unlocked) -> Bool {
        guard deviceSupports else { return false }
        var error: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(
            nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, .biometryCurrentSet, &error
        ) else { return false }
        var payload: [String: String] = [
            "encryptionPrivateKey": unlocked.encryptionPrivateKey,
            "encryptionPublicKey": unlocked.encryptionPublicKey,
        ]
        if let s = unlocked.signingPrivateKey { payload["signingPrivateKey"] = s }
        if let s = unlocked.signingPublicKey { payload["signingPublicKey"] = s }
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return false }
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account(accountKey),
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecAttrAccessControl as String] = access
        add[kSecValueData as String] = data
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess else { return false }
        UserDefaults.standard.set(true, forKey: markerPrefix + account(accountKey))
        return true
    }

    /// Blocking: shows the Face ID / Touch ID sheet. Call off the main actor.
    static func open(accountKey: String, expectedPublicKey: String, reason: String) -> Outcome {
        guard hasBoundCopy(accountKey: accountKey) else { return .unavailable }
        let context = LAContext()
        context.localizedReason = reason
        context.localizedFallbackTitle = String(localized: "Use passphrase", comment: "Face ID fallback button when unlocking the encryption key")
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account(accountKey),
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseAuthenticationContext as String: context,
        ]
        var out: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &out)
        switch status {
        case errSecSuccess:
            guard let data = out as? Data,
                  let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: String],
                  let priv = obj["encryptionPrivateKey"], let pub = obj["encryptionPublicKey"]
            else { return .unavailable }
            guard pub == expectedPublicKey else {
                // A stale copy for an older key: discard, never trust it.
                forget(accountKey: accountKey)
                return .unavailable
            }
            return .opened(MailE2e.Unlocked(encryptionPrivateKey: priv, encryptionPublicKey: pub,
                                            signingPrivateKey: obj["signingPrivateKey"],
                                            signingPublicKey: obj["signingPublicKey"]))
        case errSecItemNotFound, errSecAuthFailed, errSecInvalidData:
            // .biometryCurrentSet items vanish / fail after a biometry change.
            forget(accountKey: accountKey)
            return .invalidated
        default:
            // errSecUserCanceled and friends: the passphrase path still works.
            return .unavailable
        }
    }

    /// Removes this account's bound copy. The passphrase record is untouched.
    static func forget(accountKey: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account(accountKey),
        ]
        SecItemDelete(query as CFDictionary)
        UserDefaults.standard.removeObject(forKey: markerPrefix + account(accountKey))
    }
}

private enum BoundName {
    /// SHA-256 of the account key, hex, first 32 chars: the identity file's
    /// own naming rule, so no address is stored in the clear.
    static func of(_ accountKey: String) -> String {
        String(SHA256.hash(data: Data(accountKey.utf8)).map { String(format: "%02x", $0) }.joined().prefix(32))
    }
}
