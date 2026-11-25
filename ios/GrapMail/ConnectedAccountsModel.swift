import Foundation

/// Pure logic + networking for Connected accounts (ConnectedAccountsView.swift).
/// Backend: services/shared/src/routes/grapmail-providers.ts, mounted at `/grapmail`
/// → `ApiClient.mailBase + "/providers…"`. Mirrors Android logic/ProviderPresets.kt.
/// Gmail is intentionally absent (paid annual assessment is off).

struct ProviderPreset: Hashable, Identifiable {
    let id: String
    let label: String
    let imapHost: String
    let imapPort: Int
    let imapTLS: Bool
    let smtpHost: String
    let smtpPort: Int
    /// true = implicit TLS (465); false = STARTTLS (587).
    let smtpTLS: Bool
    let appPasswordURL: String?
    var isCustom: Bool { id == ProviderPreset.customID }

    static let customID = "custom"
    static let all: [ProviderPreset] = [
        .init(id: "yahoo", label: "Yahoo", imapHost: "imap.mail.yahoo.com", imapPort: 993, imapTLS: true,
              smtpHost: "smtp.mail.yahoo.com", smtpPort: 465, smtpTLS: true, appPasswordURL: "https://login.yahoo.com/account/security"),
        .init(id: "icloud", label: "iCloud", imapHost: "imap.mail.me.com", imapPort: 993, imapTLS: true,
              smtpHost: "smtp.mail.me.com", smtpPort: 587, smtpTLS: false, appPasswordURL: "https://account.apple.com"),
        .init(id: "aol", label: "AOL", imapHost: "imap.aol.com", imapPort: 993, imapTLS: true,
              smtpHost: "smtp.aol.com", smtpPort: 465, smtpTLS: true, appPasswordURL: "https://login.aol.com/account/security"),
        .init(id: "fastmail", label: "Fastmail", imapHost: "imap.fastmail.com", imapPort: 993, imapTLS: true,
              smtpHost: "smtp.fastmail.com", smtpPort: 465, smtpTLS: true, appPasswordURL: "https://app.fastmail.com/settings/security/apppasswords"),
        .init(id: "zoho", label: "Zoho", imapHost: "imap.zoho.com", imapPort: 993, imapTLS: true,
              smtpHost: "smtp.zoho.com", smtpPort: 465, smtpTLS: true, appPasswordURL: "https://accounts.zoho.com/home#security/app_password"),
        .init(id: customID, label: "Custom", imapHost: "", imapPort: 993, imapTLS: true,
              smtpHost: "", smtpPort: 587, smtpTLS: false, appPasswordURL: nil),
    ]

    static func byId(_ id: String) -> ProviderPreset { all.first { $0.id == id } ?? all.last! }

    static func forEmail(_ email: String) -> ProviderPreset? {
        guard let at = email.lastIndex(of: "@") else { return nil }
        let d = email[email.index(after: at)...].lowercased().trimmingCharacters(in: .whitespaces)
        if d.isEmpty { return nil }
        if d.hasPrefix("yahoo.") || d == "ymail.com" { return byId("yahoo") }
        if ["icloud.com", "me.com", "mac.com"].contains(d) { return byId("icloud") }
        if d == "aol.com" { return byId("aol") }
        if d == "fastmail.com" || d == "fastmail.fm" { return byId("fastmail") }
        if d.hasPrefix("zoho.") || d == "zohomail.com" { return byId("zoho") }
        return nil
    }
}

struct ImapForm: Equatable {
    var presetId = ProviderPreset.all[0].id
    var email = ""
    var password = ""
    var imapHost = ProviderPreset.all[0].imapHost
    var imapPort = "993"
    var imapTLS = true
    var smtpHost = ProviderPreset.all[0].smtpHost
    var smtpPort = String(ProviderPreset.all[0].smtpPort)
    var smtpTLS = ProviderPreset.all[0].smtpTLS

    mutating func apply(_ p: ProviderPreset) {
        presetId = p.id; imapHost = p.imapHost; imapPort = String(p.imapPort); imapTLS = p.imapTLS
        smtpHost = p.smtpHost; smtpPort = String(p.smtpPort); smtpTLS = p.smtpTLS
    }
}

enum ImapFormError: Hashable { case email, password, imapHost, imapPort, smtpHost, smtpPort }

enum ImapFormValidator {
    static func validHost(_ raw: String) -> Bool {
        let h = raw.trimmingCharacters(in: .whitespaces)
        guard (1...253).contains(h.count), h.contains("."), !h.hasPrefix("."), !h.hasPrefix("-"),
              !h.hasSuffix("."), !h.contains("..") else { return false }
        return h.range(of: "^[A-Za-z0-9.-]+$", options: .regularExpression) != nil
    }
    static func port(_ raw: String) -> Int? {
        guard let n = Int(raw.trimmingCharacters(in: .whitespaces)), (1...65535).contains(n) else { return nil }
        return n
    }
    static func validate(_ f: ImapForm) -> Set<ImapFormError> {
        var e = Set<ImapFormError>()
        let email = f.email.trimmingCharacters(in: .whitespaces)
        if email.count > 320 || email.range(of: "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", options: .regularExpression) == nil { e.insert(.email) }
        if f.password.isEmpty || f.password.count > 1024 { e.insert(.password) }
        if !validHost(f.imapHost) { e.insert(.imapHost) }
        if port(f.imapPort) == nil { e.insert(.imapPort) }
        if !f.smtpHost.trimmingCharacters(in: .whitespaces).isEmpty && !validHost(f.smtpHost) { e.insert(.smtpHost) }
        if port(f.smtpPort) == nil { e.insert(.smtpPort) }
        return e
    }
}

enum ProviderUIStatus { case syncing, ok, needsReauth, error, disconnected
    static func of(_ status: String, lastSyncAt: String?, lastError: String?) -> ProviderUIStatus {
        switch status.lowercased() {
        case "expired": return .needsReauth
        case "error": return .error
        case "revoked": return .disconnected
        case "connected":
            if let e = lastError, !e.isEmpty { return .error }
            if lastSyncAt?.isEmpty ?? true { return .syncing }
            return .ok
        default: return .error
        }
    }
}

// MARK: - Wire

struct ProviderAccount: Decodable, Identifiable, Equatable {
    var id = ""; var provider = ""; var providerEmail = ""; var status = ""
    var lastError: String?; var lastSyncAt: String?
    private enum CodingKeys: String, CodingKey { case id, provider, providerEmail, status, lastError, lastSyncAt }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, ""); provider = c.lenient(.provider, ""); providerEmail = c.lenient(.providerEmail, "")
        status = c.lenient(.status, ""); lastError = c.lenientOpt(.lastError); lastSyncAt = c.lenientOpt(.lastSyncAt)
    }
}

struct ProviderListResponse: Decodable {
    var success = false; var accounts: [ProviderAccount] = []
    private enum CodingKeys: String, CodingKey { case success, accounts }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false); accounts = c.lenient(.accounts, [])
    }
}

struct ProviderSimpleResponse: Decodable {
    var success = false; var error: String?; var authorizationUrl: String?
    private enum CodingKeys: String, CodingKey { case success, error, authorizationUrl }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false); error = c.lenientOpt(.error); authorizationUrl = c.lenientOpt(.authorizationUrl)
    }
}

extension ApiClient {
    static func providersList(token: String) async throws -> ProviderListResponse {
        try await request("\(mailBase)/providers", bearer: token)
    }
    static func providersAuthorizeOutlook(token: String) async throws -> ProviderSimpleResponse {
        try await request("\(mailBase)/providers/microsoft/authorize", method: "POST", body: Data("{}".utf8), bearer: token)
    }
    /// `state` is the one from the authorize URL; the server consumes it once (migration 0047).
    static func providersConnectOutlook(token: String, code: String, state: String, redirectUri: String, providerEmail: String) async throws -> ProviderSimpleResponse {
        let body = try JSONSerialization.data(withJSONObject: ["code": code, "state": state, "redirectUri": redirectUri, "providerEmail": providerEmail])
        return try await request("\(mailBase)/providers/microsoft/connect", method: "POST", body: body, bearer: token)
    }
    /// The password travels once in this body and is not kept on the device.
    static func providersConnectImap(token: String, form f: ImapForm) async throws -> ProviderSimpleResponse {
        var obj: [String: Any] = [
            "email": f.email.trimmingCharacters(in: .whitespaces), "password": f.password,
            "imapHost": f.imapHost.trimmingCharacters(in: .whitespaces), "imapPort": ImapFormValidator.port(f.imapPort) ?? 993,
            "secure": f.imapTLS, "smtpPort": ImapFormValidator.port(f.smtpPort) ?? 587, "smtpSecure": f.smtpTLS,
        ]
        let smtp = f.smtpHost.trimmingCharacters(in: .whitespaces)
        if !smtp.isEmpty { obj["smtpHost"] = smtp }
        return try await request("\(mailBase)/providers/imap/connect", method: "POST", body: try JSONSerialization.data(withJSONObject: obj), bearer: token)
    }
    static func providersSync(token: String, id: String) async throws -> ProviderSimpleResponse {
        try await request("\(mailBase)/providers/\(id)/sync", method: "POST", body: Data("{}".utf8), bearer: token)
    }
    /// Server-side disconnect (status → revoked). Never deletes anything on the device.
    static func providersDisconnect(token: String, id: String) async throws -> ProviderSimpleResponse {
        try await request("\(mailBase)/providers/\(id)", method: "DELETE", bearer: token)
    }
}

// MARK: - OAuth helpers

enum ProviderOAuth {
    static let callbackScheme = "grapmail"
    struct Pending: Equatable { let state: String; let redirectUri: String; let providerEmail: String }
    enum Result: Equatable { case code(String, state: String); case denied(String) }

    static func pending(from authorizationUrl: String, providerEmail: String) -> Pending? {
        guard let c = URLComponents(string: authorizationUrl), c.scheme == "https" else { return nil }
        let q = c.queryItems ?? []
        guard let state = q.first(where: { $0.name == "state" })?.value, !state.isEmpty,
              let redirect = q.first(where: { $0.name == "redirect_uri" })?.value, !redirect.isEmpty else { return nil }
        return Pending(state: state, redirectUri: redirect, providerEmail: providerEmail.lowercased().trimmingCharacters(in: .whitespaces))
    }

    /// grapmail://providers/callback?code=…&state=…
    static func parse(_ url: URL) -> Result? {
        guard url.scheme?.lowercased() == callbackScheme, url.host?.lowercased() == "providers", url.path == "/callback",
              let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        let q = c.queryItems ?? []
        if let e = q.first(where: { $0.name == "error" })?.value {
            return .denied(q.first(where: { $0.name == "error_description" })?.value ?? e)
        }
        guard let code = q.first(where: { $0.name == "code" })?.value, !code.isEmpty, code.count <= 4096,
              let state = q.first(where: { $0.name == "state" })?.value, !state.isEmpty else { return nil }
        return .code(code, state: state)
    }
}
