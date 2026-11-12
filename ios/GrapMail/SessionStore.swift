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

/// Keychain-backed session storage — the gateway's JWT pair never touches
/// plaintext disk.
enum SessionStore {
    private static let tokenKey = "grapmail.session.token"
    private static let refreshKey = "grapmail.session.refresh"
    private static let addressKey = "grapmail.session.address"
    private static let nameKey = "grapmail.session.name"
    private static let expiresKey = "grapmail.session.expires"
    private static let credentialsLock = NSLock()
    private static var credentials: [String: String] = [:]
    private static var sessionGeneration = UUID()
    private static var previousAccessToken: String?
    static var generation: UUID { credentialsLock.withLock { sessionGeneration } }

    private static func readCredential(_ key: String) -> String? {
        credentialsLock.withLock {
            if let cached = credentials[key] { return cached }
            let stored = Keychain.get(key)
            credentials[key] = stored
            return stored
        }
    }

    // Called with credentialsLock held. Keep the disk result authoritative.
    private static func writeCredential(_ value: String, for key: String) {
        credentials[key] = Keychain.set(value, for: key) ? value : nil
    }

    static func save(token: String, refreshToken: String? = nil, address: String, name: String, expiresAtRaw: Double? = nil) {
        MailPoll.clear()
        credentialsLock.withLock {
            sessionGeneration = UUID()
            previousAccessToken = nil
            Keychain.delete(refreshKey)
            credentials.removeValue(forKey: refreshKey)
            writeCredential(token, for: tokenKey)
            if let refreshToken { writeCredential(refreshToken, for: refreshKey) }
            UserDefaults.standard.set(address, forKey: addressKey)
            UserDefaults.standard.set(name, forKey: nameKey)
            UserDefaults.standard.set(expiresAtRaw, forKey: expiresKey)
        }
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: .gmSessionChanged, object: nil)
        }
    }

    /// The pair after a rotation, keeping address and name.
    ///
    /// The gateway invalidates the presented refresh token on every refresh, so
    /// writing only the access token would strand the session at the next one.
    static func updateTokens(token: String, refreshToken: String?, expiresAtRaw: Double?) {
        credentialsLock.withLock {
            let oldToken = credentials[tokenKey] ?? Keychain.get(tokenKey)
            writeCredential(token, for: tokenKey)
            previousAccessToken = credentials[tokenKey] == nil || oldToken == token ? nil : oldToken
            if let refreshToken { writeCredential(refreshToken, for: refreshKey) }
            UserDefaults.standard.set(expiresAtRaw, forKey: expiresKey)
        }
    }

    static func replacementToken(for rejected: String, generation: UUID) -> String? {
        credentialsLock.withLock {
            guard sessionGeneration == generation, previousAccessToken == rejected else { return nil }
            return credentials[tokenKey]
        }
    }

    static var token: String? { readCredential(tokenKey) }
    static var refreshToken: String? { readCredential(refreshKey) }
    static var address: String? { UserDefaults.standard.string(forKey: addressKey) }
    static var name: String? { UserDefaults.standard.string(forKey: nameKey) }

    /// A stored session counts as signed-in only while unexpired. 0/absent
    /// means the server supplied no expiry — keep the session. Values below
    /// the epoch-milliseconds threshold are seconds, not milliseconds
    /// (same normalization as Android's SessionStore).
    static var hasValidSession: Bool {
        guard token != nil else { return false }
        return isUnexpired
    }

    private static var isUnexpired: Bool {
        let raw = UserDefaults.standard.double(forKey: expiresKey)
        if raw <= 0 { return true }
        let millis = raw < 1e11 ? raw * 1000 : raw
        return Date().timeIntervalSince1970 * 1000 < millis
    }

    static func launchSession() async -> (stored: Bool, unexpired: Bool) {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let stored = token != nil
                continuation.resume(returning: (stored, stored && isUnexpired))
            }
        }
    }

    @MainActor
    static func clear() {
        credentialsLock.withLock { sessionGeneration = UUID() }
        MailPoll.clear()
        // The push deregistration is authenticated with THIS session's bearer,
        // so it must run before the Keychain deletes below remove it. Its own
        // teardown stays deferred inside there: it must not unregister a
        // registration that belongs to a sign-in completed in the meantime.
        NotificationRegistration.shared.sessionEnding()
        credentialsLock.withLock {
            Keychain.delete(tokenKey)
            Keychain.delete(refreshKey)
            credentials.removeAll()
            previousAccessToken = nil
            UserDefaults.standard.removeObject(forKey: addressKey)
            UserDefaults.standard.removeObject(forKey: nameKey)
            UserDefaults.standard.removeObject(forKey: expiresKey)
        }
        // Signing out is the one moment the security sector's in-memory
        // credentials must go with the token. Clearing here rather than at each
        // call site means no future sign-out path can forget it and hand the next
        // person a live action proof.
        SecurityFlow.reset()
        AccountSecurityView.clearCachedVerdict()
        // The mailbox's cached pages, bodies, labels and stats belong to the
        // account that just signed out. Leaving them behind would make the first
        // thing the next person on this device sees somebody else's mail — the
        // same leak SecurityProfileCache closes for the security verdicts.
        MailStore.shared.clearNow()
    }
}

enum Keychain {
    @discardableResult
    static func set(_ value: String, for key: String) -> Bool {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
        var attrs = query
        attrs[kSecValueData as String] = data
        attrs[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(attrs as CFDictionary, nil) == errSecSuccess
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
