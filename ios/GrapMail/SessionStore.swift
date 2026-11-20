import Foundation
import Security
import UserNotifications

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
    private static let notificationSessionKey = "grapmail.notification-session"
    private static let addressKey = "grapmail.session.address"
    private static let nameKey = "grapmail.session.name"
    private static let expiresKey = "grapmail.session.expires"
    private static let credentialsLock = NSLock()
    private static var credentials: [String: String] = [:]
    private static var sessionGeneration = UUID()
    private static var previousAccessToken: String?
    static var generation: UUID { credentialsLock.withLock { sessionGeneration } }

    struct Account: Codable, Identifiable {
        let address: String
        let name: String
        let token: String
        let refreshToken: String?
        let expiresAt: Double
        var id: String { address.lowercased() }
    }

    private static let accountsKey = "grapmail.inactive-accounts"

    private static func savedAccounts() -> [Account] {
        guard let data = Keychain.get(accountsKey)?.data(using: .utf8),
              let accounts = try? JSONDecoder().decode([Account].self, from: data) else { return [] }
        return accounts
    }

    private static func writeAccounts(_ accounts: [Account]) -> Bool {
        guard let data = try? JSONEncoder().encode(accounts),
              let value = String(data: data, encoding: .utf8) else { return false }
        return Keychain.set(value, for: accountsKey)
    }

    private static func activeAccount() -> Account? {
        guard let address, let token = credentials[tokenKey] ?? Keychain.get(tokenKey) else { return nil }
        return Account(address: address, name: name ?? "", token: token,
                       refreshToken: credentials[refreshKey] ?? Keychain.get(refreshKey),
                       expiresAt: UserDefaults.standard.double(forKey: expiresKey))
    }

    static func accounts() -> [Account] {
        credentialsLock.withLock {
            let active = activeAccount()
            return [active].compactMap { $0 } + savedAccounts().filter { $0.id != active?.id }
        }
    }

    static func account(_ address: String) -> Account? {
        accounts().first { $0.id == address.lowercased() }
    }

    static func recordRotation(account: Account, token: String, refresh: String?, expiry: Double?) -> String? {
        credentialsLock.withLock {
            if let active = activeAccount(), active.id == account.id {
                if active.token == token { return token }
                guard active.token == account.token, Keychain.set(token, for: tokenKey) else { return nil }
                credentials[tokenKey] = token
                previousAccessToken = account.token
                if let refresh { writeCredential(refresh, for: refreshKey) }
                UserDefaults.standard.set(expiry, forKey: expiresKey)
                return token
            }
            var parked = savedAccounts()
            guard let index = parked.firstIndex(where: { $0.id == account.id }) else { return nil }
            if parked[index].token == token { return token }
            guard parked[index].token == account.token else { return nil }
            parked[index] = Account(address: account.address, name: account.name, token: token,
                                    refreshToken: refresh ?? account.refreshToken, expiresAt: expiry ?? 0)
            return writeAccounts(parked) ? token : nil
        }
    }

    static func unreadToken(for address: String) -> String? {
        credentialsLock.withLock {
            savedAccounts().first { $0.id == address.lowercased() && $0.id != activeAccount()?.id }?.token
        }
    }

    @MainActor
    static func switchTo(_ address: String) -> Bool {
        let changed = credentialsLock.withLock { () -> Bool in
            let old = activeAccount()
            let existing = savedAccounts()
            let id = address.lowercased()
            guard old?.id != id, let next = existing.first(where: { $0.id == id }) else { return false }
            let parked = existing.filter { $0.id != id && $0.id != old?.id } + [old].compactMap { $0 }
            guard writeAccounts(parked) else { return false }
            guard install(next) else {
                _ = writeAccounts(existing)
                return false
            }
            return true
        }
        if changed {
            MailPoll.clear()
            WidgetBridge.clear()
            if #available(iOS 16.2, *) { SendLaterActivityManager.endAll() }
            SecurityFlow.reset()
            AccountSecurityView.clearCachedVerdict()
            MailStore.shared.clearNow()
            NotificationCenter.default.post(name: .gmSessionChanged, object: nil)
        }
        return changed
    }

    @discardableResult
    private static func install(_ account: Account) -> Bool {
        guard Keychain.set(account.token, for: tokenKey) else { return false }
        credentials[tokenKey] = account.token
        sessionGeneration = UUID()
        previousAccessToken = nil
        Keychain.delete(refreshKey)
        credentials.removeValue(forKey: refreshKey)
        if let refreshToken = account.refreshToken { writeCredential(refreshToken, for: refreshKey) }
        UserDefaults.standard.set(UUID().uuidString, forKey: notificationSessionKey)
        UserDefaults.standard.set(account.address, forKey: addressKey)
        UserDefaults.standard.set(account.name, forKey: nameKey)
        UserDefaults.standard.set(account.expiresAt, forKey: expiresKey)
        return true
    }

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
        WidgetBridge.clear()
        if #available(iOS 16.2, *) { SendLaterActivityManager.endAll() }
        credentialsLock.withLock {
            let next = Account(address: address, name: name, token: token,
                               refreshToken: refreshToken, expiresAt: expiresAtRaw ?? 0)
            let old = activeAccount()
            let parked = savedAccounts().filter { $0.id != next.id && $0.id != old?.id }
                + [old].compactMap { $0 }.filter { $0.id != next.id }
            if old?.id != next.id || !parked.isEmpty { _ = writeAccounts(parked) }
            install(next)
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
    static var notificationSession: String? {
        UserDefaults.standard.string(forKey: notificationSessionKey) ?? address
    }
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
        WidgetBridge.clear()
        // The Lock Screen card and the widget are the app's own surfaces and
        // must not outlive the session that filled them.
        if #available(iOS 16.2, *) { SendLaterActivityManager.endAll() }
        // The push deregistration is authenticated with THIS session's bearer,
        // so it must run before the Keychain deletes below remove it. Its own
        // teardown stays deferred inside there: it must not unregister a
        // registration that belongs to a sign-in completed in the meantime.
        NotificationRegistration.shared.sessionEnding()
        credentialsLock.withLock {
            if let id = activeAccount()?.id {
                let accounts = savedAccounts()
                if accounts.contains(where: { $0.id == id }) { _ = writeAccounts(accounts.filter { $0.id != id }) }
            }
            Keychain.delete(tokenKey)
            Keychain.delete(refreshKey)
            credentials.removeAll()
            previousAccessToken = nil
            UserDefaults.standard.removeObject(forKey: notificationSessionKey)
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
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
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
