import Foundation
import Security

/// Stable per-install identifier sent as `x-device-id` — the header the
/// account API counts accounts against per device. Random UUID, stored in
/// Keychain (survives reinstalls of data, never personal).
enum DeviceId {
    static let value: String = {
        if let existing = Keychain.get("grapmail.device-id") { return existing }
        let generated = UUID().uuidString
        Keychain.set(generated, for: "grapmail.device-id")
        return generated
    }()
}

/// Keychain-backed session storage — the st1 token never touches plaintext disk.
enum SessionStore {
    private static let tokenKey = "grapmail.session.token"
    private static let addressKey = "grapmail.session.address"
    private static let nameKey = "grapmail.session.name"
    private static let expiresKey = "grapmail.session.expires"

    static func save(token: String, address: String, name: String, expiresAtRaw: Double? = nil) {
        Keychain.set(token, for: tokenKey)
        UserDefaults.standard.set(address, forKey: addressKey)
        UserDefaults.standard.set(name, forKey: nameKey)
        UserDefaults.standard.set(expiresAtRaw, forKey: expiresKey)
    }

    static var token: String? { Keychain.get(tokenKey) }
    static var address: String? { UserDefaults.standard.string(forKey: addressKey) }
    static var name: String? { UserDefaults.standard.string(forKey: nameKey) }

    /// A stored session counts as signed-in only while unexpired. 0/absent
    /// means the server supplied no expiry — keep the session. Values below
    /// the epoch-milliseconds threshold are seconds, not milliseconds
    /// (same normalization as Android's SessionStore).
    static var hasValidSession: Bool {
        guard token != nil else { return false }
        let raw = UserDefaults.standard.double(forKey: expiresKey)
        if raw <= 0 { return true }
        let millis = raw < 1e11 ? raw * 1000 : raw
        return Date().timeIntervalSince1970 * 1000 < millis
    }

    static func clear() {
        Keychain.delete(tokenKey)
        UserDefaults.standard.removeObject(forKey: addressKey)
        UserDefaults.standard.removeObject(forKey: nameKey)
        UserDefaults.standard.removeObject(forKey: expiresKey)
        // Signing out is the one moment the security sector's in-memory
        // credentials must go with the token. Clearing here rather than at each
        // call site means no future sign-out path can forget it and hand the next
        // person a live action proof.
        SecurityFlow.reset()
    }
}

enum Keychain {
    static func set(_ value: String, for key: String) {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
        var attrs = query
        attrs[kSecValueData as String] = data
        attrs[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(attrs as CFDictionary, nil)
    }

    static func get(_ key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func delete(_ key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
