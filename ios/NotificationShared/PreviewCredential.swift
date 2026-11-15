import Foundation
import Security

enum PreviewCredential {
    static func secret(from registration: String) -> String {
        registration.split(separator: ".").last.map(String.init) ?? registration
    }

    private static func query(_ mailbox: String) -> [String: Any]? {
        guard let team = Bundle.main.object(forInfoDictionaryKey: "PreviewTeamIdentifier") as? String,
              !team.isEmpty else { return nil }
        return [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.grapsee.grapmail.push-preview",
            kSecAttrAccount as String: mailbox.lowercased(),
            kSecAttrAccessGroup as String: "\(team).com.grapsee.grapmail.notifications",
        ]
    }

    static func read(mailbox: String) -> String? {
        guard var request = query(mailbox) else { return nil }
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    static func store(_ secret: String, mailbox: String) -> Bool {
        guard var request = query(mailbox) else { return false }
        let data = Data(secret.utf8)
        let status = SecItemUpdate(request as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
        request[kSecValueData as String] = data
        request[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(request as CFDictionary, nil) == errSecSuccess
    }

    static func clear(mailbox: String) {
        if let query = query(mailbox) { SecItemDelete(query as CFDictionary) }
    }
}
