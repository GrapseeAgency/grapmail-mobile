import Foundation
import UIKit

/// Posted when a call that carried our bearer token comes back 401: the
/// stored session is dead on the server, so the app must stop pretending to
/// be signed in instead of showing a home screen whose every request fails.
extension Notification.Name {
    static let gmSessionExpired = Notification.Name("grapmail.session.expired")
}

/// Lenient decoding helpers.
///
/// Swift's synthesized `Decodable` throws `keyNotFound` for a missing key even
/// when the property has a `= default` — a default is NOT consulted during
/// decoding. The GrapMail account backend (a different service than the web
/// API) omits optional-in-practice fields per endpoint, which Android's
/// kotlinx handles by applying the default but iOS turned into a hard decode
/// failure: the sign-in and every security screen's fetch reported "Unexpected
/// server response" / "couldn't load" and the 2FA toggle stayed disabled.
/// Every response DTO therefore decodes field by field with these helpers, so
/// a missing key yields the declared default instead of throwing — iOS parity
/// with Android's lenient behavior.
extension KeyedDecodingContainer {
    /// Read a required field, falling back to `def` when the server did not
    /// send it. Absent/null is normal for this backend; a key that is PRESENT
    /// but not the expected type is contract drift, so it is logged rather
    /// than silently defaulted — a renamed field must not become invisible.
    func lenient<T: Decodable>(_ key: K, _ def: T) -> T {
        do {
            if let v = try decodeIfPresent(T.self, forKey: key) { return v }
        } catch {
            #if DEBUG
            print("Api decode drift: \(T.self) '\(key.stringValue)' → \(error)")
            #endif
            return def
        }
        return def
    }
    /// Read an optional field, tolerating both a missing key and a wrong type.
    func lenientOpt<T: Decodable>(_ key: K) -> T? {
        do {
            return try decodeIfPresent(T.self, forKey: key)
        } catch {
            #if DEBUG
            print("Api decode drift: \(T.self) '\(key.stringValue)' → \(error)")
            #endif
            return nil
        }
    }
}

/// The real GrapMail account API — the same contract the website uses,
/// served at https://grapsee.com/api. Verified live before this client was
/// written. No mocks anywhere in this file.
enum ApiError: Error, LocalizedError {
    case http(status: Int, code: String?, message: String)
    case network(Error)

    var errorDescription: String? {
        switch self {
        case let .http(status, code, message):
            if code == "TOO_MANY_ATTEMPTS" {
                return "Too many failed attempts. Try again in about 15 minutes."
            }
            return message.isEmpty ? "Request failed (\(status))." : message
        case .network:
            return "Network error. Please try again."
        }
    }
}

/// Top-level shape of the API's error envelope, decoded on non-2xx responses.
struct ErrorCarrier: Codable {
    var code: String?
    var message: String?
    var error: String?
}

struct AccountDto: Codable {
    var id: String = ""
    var name: String = ""
    var firstName: String?
    var lastName: String?
    var address: String = ""
    var handle: String = ""
    var phone: String?
    var accountType: String = "USER"

    private enum CodingKeys: String, CodingKey {
        case id, name, firstName, lastName, address, handle, phone, accountType
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        name = c.lenient(.name, "")
        firstName = c.lenientOpt(.firstName)
        lastName = c.lenientOpt(.lastName)
        address = c.lenient(.address, "")
        handle = c.lenient(.handle, "")
        phone = c.lenientOpt(.phone)
        accountType = c.lenient(.accountType, "USER")
    }
}

struct CreateRequest: Codable {
    var name: String
    var firstName: String?
    var lastName: String?
    var birthday: String?
    var gender: String?
    var grapmailHandle: String?
    var password: String
    var acceptedTerms: Bool?
}

struct CreateResponse: Codable {
    var success: Bool = false
    var account: AccountDto?
    var mailboxDomain: String?
    var message: String?
    var phoneVerified: Bool = false
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, account, mailboxDomain, message, phoneVerified, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        account = c.lenientOpt(.account)
        mailboxDomain = c.lenientOpt(.mailboxDomain)
        message = c.lenientOpt(.message)
        phoneVerified = c.lenient(.phoneVerified, false)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

struct CheckResponse: Codable {
    var success: Bool = false
    var available: Bool = false
    var handle: String = ""
    var address: String?
    var suggestion: String?
    var reason: String?

    private enum CodingKeys: String, CodingKey {
        case success, available, handle, address, suggestion, reason
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        available = c.lenient(.available, false)
        handle = c.lenient(.handle, "")
        address = c.lenientOpt(.address)
        suggestion = c.lenientOpt(.suggestion)
        reason = c.lenientOpt(.reason)
    }
}

struct SignInRequest: Codable {
    var identifier: String
    var password: String
}

/// Decode-only. A response type never gets encoded, and it must not be
/// `Codable` here: the synthesized `encode(to:)` walks every CodingKeys case
/// and needs a stored property for each, so the alias keys below (`user`,
/// `expiresIn`) break Encodable conformance for a type that only ever reads.
struct SignInResponse: Decodable {
    var success: Bool = false
    var sessionToken: String?
    var accessToken: String?
    var token: String?
    var refreshToken: String?
    var expiresAt: Int?
    /// The live /auth/account/signin envelope answers with a RELATIVE lifetime
    /// (`expiresIn`, seconds) rather than an absolute `expiresAt`. Only the
    /// absolute one was ever read, so every stored session had no expiry and
    /// `hasValidSession` treated it as valid forever — a token revoked on the
    /// server kept the app on a home screen whose calls all 401.
    var expiresIn: Int?
    var account: AccountDto?
    // The live envelope is { success, message, accessToken, refreshToken,
    // expiresIn, suspicious, user } — the account object arrives under `user`,
    // and it carries neither requireOtp nor delivered. Swift's synthesized
    // Decodable throws keyNotFound for a missing NON-optional field (a `= false`
    // default is not applied during decoding), so every field decodes leniently
    // or the whole response fails and sign-in reports 'Unexpected server
    // response'. Android's kotlinx applies the default for a missing key, which
    // is why only iOS broke.
    var requireOtp: Bool?
    var challengeId: String?
    var suspicious: Bool?
    var backupCodesRemaining: Int?
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, sessionToken, accessToken, token, refreshToken, expiresAt,
             expiresIn, account, user, requireOtp, challengeId, suspicious,
             backupCodesRemaining, message, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        sessionToken = c.lenientOpt(.sessionToken)
        accessToken = c.lenientOpt(.accessToken)
        token = c.lenientOpt(.token)
        refreshToken = c.lenientOpt(.refreshToken)
        expiresAt = c.lenientOpt(.expiresAt)
        expiresIn = c.lenientOpt(.expiresIn)
        account = c.lenientOpt(.account) ?? c.lenientOpt(.user)
        requireOtp = c.lenientOpt(.requireOtp)
        challengeId = c.lenientOpt(.challengeId)
        suspicious = c.lenientOpt(.suspicious)
        backupCodesRemaining = c.lenientOpt(.backupCodesRemaining)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }

    /// Absolute expiry in epoch-milliseconds, from either shape the server
    /// uses, or nil when it sent neither (SessionStore then keeps the session).
    /// Note this is the SESSION lifetime; the code countdown still reads
    /// `expiresAt`, because a challenge does not live as long as a session.
    var expiresAtMillis: Double? {
        if let at = expiresAt { return at < 100_000_000_000 ? Double(at) * 1000 : Double(at) }
        guard let seconds = expiresIn else { return nil }
        return Date().timeIntervalSince1970 * 1000 + Double(seconds) * 1000
    }
    /// A challenge was issued ⇒ a second factor is required. Presence is the
    /// gate, not `requireOtp`: this backend omits that flag on ordinary
    /// responses, and treating an absent flag as "no code needed" would sign a
    /// 2FA account in on the password alone whenever a challenge is sent.
    var secondFactorRequired: Bool { challengeId != nil || requireOtp == true }
}

struct TwoFactorRequest: Codable {
    var challengeId: String?
    var code: String?
    var identifier: String?
    var password: String?
    var backupCode: String?
}

struct TwoFactorResponse: Decodable {
    var success: Bool = false
    var sessionToken: String?
    var accessToken: String?
    var token: String?
    var refreshToken: String?
    var expiresAt: Int?
    var expiresIn: Int?
    var user: AccountDto?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, sessionToken, accessToken, token, refreshToken, expiresAt,
             expiresIn, user, account, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        sessionToken = c.lenientOpt(.sessionToken)
        accessToken = c.lenientOpt(.accessToken)
        token = c.lenientOpt(.token)
        refreshToken = c.lenientOpt(.refreshToken)
        expiresAt = c.lenientOpt(.expiresAt)
        expiresIn = c.lenientOpt(.expiresIn)
        user = c.lenientOpt(.user) ?? c.lenientOpt(.account)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }

    /// Same both-shapes rule as SignInResponse: redeeming a code opens the
    /// session, and a session with no recorded expiry looks valid forever.
    var expiresAtMillis: Double? {
        if let at = expiresAt { return at < 100_000_000_000 ? Double(at) * 1000 : Double(at) }
        guard let seconds = expiresIn else { return nil }
        return Date().timeIntervalSince1970 * 1000 + Double(seconds) * 1000
    }
}

// ── Recovery & security ─────────────────────────────────────────────────────

struct ForgotPasswordRequest: Codable { var identifier: String }

struct ForgotPasswordResponse: Codable {
    var success: Bool = false
    var sent: Bool = false
    var challengeId: String?
    var expiresAt: Int?
    var maskedPhone: String?
    var provider: String?
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, sent, challengeId, expiresAt, maskedPhone, provider,
             message, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        // `sent` is the server's own word for "a code went out". Absent does
        // not mean false: the challengeId check below is what routes the flow,
        // so keep the flag optional rather than inventing a negative answer.
        sent = c.lenientOpt(.sent) ?? (c.lenientOpt(.challengeId) != nil)
        challengeId = c.lenientOpt(.challengeId)
        expiresAt = c.lenientOpt(.expiresAt)
        maskedPhone = c.lenientOpt(.maskedPhone)
        provider = c.lenientOpt(.provider)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

struct ResetPasswordRequest: Codable {
    var challengeId: String
    var code: String
    var password: String
}

struct ResetPasswordResponse: Codable {
    var success: Bool = false
    var address: String?
    var message: String?
    var error: String?
    var code: String?
    var reason: String?
    var attemptsLeft: Int?

    private enum CodingKeys: String, CodingKey {
        case success, address, message, error, code, reason, attemptsLeft
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        address = c.lenientOpt(.address)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
        reason = c.lenientOpt(.reason)
        attemptsLeft = c.lenientOpt(.attemptsLeft)
    }
}

struct FindAddressRequest: Codable { var phone: String }

struct FindAddressResponse: Codable {
    var success: Bool = false
    /// Shown on screen: first character, stars, domain.
    var maskedAddress: String?
    /// The whole address, only for the design's Copy button.
    var address: String?
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, maskedAddress, address, message, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        maskedAddress = c.lenientOpt(.maskedAddress)
        address = c.lenientOpt(.address)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

struct PhoneStartRequest: Codable { var phone: String }

struct PhoneStartResponse: Codable {
    var success: Bool = false
    var challengeId: String?
    var expiresAt: Int?
    var channel: String?
    var delivered: Bool = false
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, challengeId, expiresAt, channel, delivered, message, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        challengeId = c.lenientOpt(.challengeId)
        expiresAt = c.lenientOpt(.expiresAt)
        channel = c.lenientOpt(.channel)
        delivered = c.lenient(.delivered, false)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

struct PhoneVerifyRequest: Codable {
    var challengeId: String
    var code: String
}

struct PhoneVerifyResponse: Codable {
    var success: Bool = false
    var phone: String?
    var phoneToken: String?
    /// Proves this code was redeemed by a signed-in owner of that number, so the
    /// 2FA enable call cannot be completed by a client that skipped the step.
    var phoneProof: String?
    var expiresAt: Int?
    var message: String?
    var error: String?
    var code: String?
    var reason: String?
    var attemptsLeft: Int?

    private enum CodingKeys: String, CodingKey {
        case success, phone, phoneToken, phoneProof, expiresAt, message, error,
             code, reason, attemptsLeft
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        phone = c.lenientOpt(.phone)
        phoneToken = c.lenientOpt(.phoneToken)
        phoneProof = c.lenientOpt(.phoneProof)
        expiresAt = c.lenientOpt(.expiresAt)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
        reason = c.lenientOpt(.reason)
        attemptsLeft = c.lenientOpt(.attemptsLeft)
    }
}

struct LinkPhoneRequest: Codable { var phoneToken: String }

struct LinkPhoneResponse: Codable {
    var success: Bool = false
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, message, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

// ── Security sector (the 24-screen batch) ───────────────────────────────────

struct ProfileDto: Codable {
    var id = ""
    var name = ""
    var address = ""
    var handle = ""
    var phone: String?
    /// The legacy form of the same answer, kept because it is what some
    /// endpoints actually send. Optional: an absent string is no answer, and a
    /// defaulted "off" would claim two-step is off when nothing was said.
    var twoFactor: String?
    /// The server's own answer to "is the second factor on", so no client
    /// re-derives it. Optional on purpose: nil means the server did not answer,
    /// which the hub must show as "couldn't load" rather than as "Off" — a
    /// wrong Off invites the person to re-enable a factor that is already on
    /// and hides a real lock-out. Same reason `backupCodesRemaining` is nil and
    /// not 0: 0 would mean "generate codes or you'll be locked out".
    var twoFactorEnabled: Bool?
    var twoFactorEnabledAt: String?
    var backupCodesRemaining: Int?
    var passwordChangedAt: String?
    var createdAt: String?

    /// The one verdict the hub acts on: the server's own flag when it sends
    /// one, otherwise the legacy string read as a flag, otherwise nil — and
    /// nil is the only case the hub is allowed to call unknown.
    var twoFactorState: Bool? {
        if let enabled = twoFactorEnabled { return enabled }
        switch twoFactor?.lowercased() {
        case "on", "true", "1", "enabled": return true
        case "off", "false", "0", "disabled": return false
        default: return nil
        }
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, address, handle, phone, twoFactor, twoFactorEnabled,
             twoFactorEnabledAt, backupCodesRemaining, passwordChangedAt, createdAt
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        name = c.lenient(.name, "")
        address = c.lenient(.address, "")
        handle = c.lenient(.handle, "")
        phone = c.lenientOpt(.phone)
        twoFactor = c.lenientOpt(.twoFactor)
        twoFactorEnabled = c.lenientOpt(.twoFactorEnabled)
        twoFactorEnabledAt = c.lenientOpt(.twoFactorEnabledAt)
        backupCodesRemaining = c.lenientOpt(.backupCodesRemaining)
        passwordChangedAt = c.lenientOpt(.passwordChangedAt)
        createdAt = c.lenientOpt(.createdAt)
    }
}

struct ProfileResponse: Decodable {
    var success = false
    var profile: ProfileDto?
    /// Short-lived proof minted by verify-password for one dangerous action.
    var proof: String?
    /// Present only on the response that created them; never readable again.
    var newBackupCodes: [String]?
    /// Present only when the change revoked this device's own token too.
    var accessToken: String?
    /// When that replacement expires. Stored, or the app believes it is signed in forever.
    var tokenExpiresAt: Double?
    /// The relative form the server may send instead.
    var tokenExpiresIn: Int?
    var refreshToken: String?
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, profile, proof, newBackupCodes, accessToken, tokenExpiresAt,
             tokenExpiresIn, expiresIn, refreshToken, message, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        profile = c.lenientOpt(.profile)
        proof = c.lenientOpt(.proof)
        newBackupCodes = c.lenientOpt(.newBackupCodes)
        accessToken = c.lenientOpt(.accessToken)
        tokenExpiresAt = c.lenientOpt(.tokenExpiresAt)
        tokenExpiresIn = c.lenientOpt(.tokenExpiresIn) ?? c.lenientOpt(.expiresIn)
        refreshToken = c.lenientOpt(.refreshToken)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }

    /// Absolute expiry in epoch-milliseconds for a token this response
    /// reissued, from either shape. nil only when the server sent neither —
    /// a replacement token written without an expiry is a permanent session.
    var tokenExpiresAtMillis: Double? {
        if let at = tokenExpiresAt { return at < 100_000_000_000 ? at * 1000 : at }
        guard let seconds = tokenExpiresIn else { return nil }
        return Date().timeIntervalSince1970 * 1000 + Double(seconds) * 1000
    }
}

struct SessionDto: Codable, Identifiable {
    var id = ""
    var device = ""
    /// The browser/app half of the label, shown beside the location on the row.
    var browser = ""
    /// The stable handle for "is this the device the alert is about?". Labels collide.
    var deviceId = ""
    var ip = ""
    var createdAt = ""
    var lastSeenAt = ""
    var current = false

    private enum CodingKeys: String, CodingKey {
        case id, device, browser, deviceId, ip, createdAt, lastSeenAt, current
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        device = c.lenient(.device, "")
        browser = c.lenient(.browser, "")
        deviceId = c.lenient(.deviceId, "")
        ip = c.lenient(.ip, "")
        createdAt = c.lenient(.createdAt, "")
        lastSeenAt = c.lenient(.lastSeenAt, "")
        current = c.lenient(.current, false)
    }
}

struct SessionsResponse: Codable {
    var success = false
    var sessions: [SessionDto] = []
    var count = 0
    /// True total active sessions, correct even when the list is one page.
    var activeCount = 0
    var error: String?

    private enum CodingKeys: String, CodingKey {
        case success, sessions, count, activeCount, error
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        sessions = c.lenient(.sessions, [])
        count = c.lenient(.count, 0)
        activeCount = c.lenient(.activeCount, 0)
        error = c.lenientOpt(.error)
    }
}

struct ActivityDto: Codable, Identifiable {
    var id = ""
    var type = ""
    var at = ""
    var device = ""
    var browser = ""
    var deviceId = ""
    var ip = ""
    var severity = "info"
    var detail = ""

    private enum CodingKeys: String, CodingKey {
        case id, type, at, device, browser, deviceId, ip, severity, detail
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        type = c.lenient(.type, "")
        at = c.lenient(.at, "")
        device = c.lenient(.device, "")
        browser = c.lenient(.browser, "")
        deviceId = c.lenient(.deviceId, "")
        ip = c.lenient(.ip, "")
        severity = c.lenient(.severity, "info")
        detail = c.lenient(.detail, "")
    }
}

struct ActivityResponse: Codable {
    var success = false
    var events: [ActivityDto] = []
    var suspicious = false
    /// The exact sign-in the server flagged, so the alert screens read it rather than re-pick one.
    var suspiciousEvent: ActivityDto?
    var error: String?

    private enum CodingKeys: String, CodingKey {
        case success, events, suspicious, suspiciousEvent, error
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        events = c.lenient(.events, [])
        suspicious = c.lenient(.suspicious, false)
        suspiciousEvent = c.lenientOpt(.suspiciousEvent)
        error = c.lenientOpt(.error)
    }
}

private struct TwoFactorEnableRequest: Codable { var twoFactor: String; var proof: String; var phoneProof: String }
private struct TwoFactorDisableRequest: Codable { var twoFactor: String; var currentPassword: String }
private struct PasswordChangeRequest: Codable { var currentPassword: String; var newPassword: String }
private struct BackupCodesRequest: Codable { var generateBackupCodes: Bool }
private struct SignOutEverywhereRequest: Codable { var signOutEverywhere: Bool }
private struct VerifyPasswordRequest: Codable { var password: String }

struct ApiClient {
    static let base = "https://grapsee.com/api"
    private static let encoder = JSONEncoder()
    private static let decoder = JSONDecoder()

    /// What the security screen shows as the device name.
    ///
    /// URLSession's default User-Agent is a CFNetwork string that matches no
    /// platform rule, so every iPhone signed in as "Unknown device" and the
    /// device list was unreadable. Naming the app and the OS is what makes the
    /// row recognisable to the person looking at it.
    static let userAgent: String = {
        let name = UIDevice.current.systemName
        let version = UIDevice.current.systemVersion
        return "GrapMail/1.0 (iPhone; \(name) \(version))"
    }()

    private static func request<T: Decodable>(_ path: String, method: String = "GET", body: Data? = nil, bearer: String? = nil) async throws -> T {
        guard var components = URLComponents(string: "\(base)\(path)") else {
            throw ApiError.http(status: -1, code: nil, message: "Bad URL")
        }
        if path.contains("check?") == false { components.query = components.query } // path carries its own query when needed
        guard let url = components.url else {
            throw ApiError.http(status: -1, code: nil, message: "Bad URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = 20
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(DeviceId.value, forHTTPHeaderField: "x-device-id")
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        if let bearer { req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        req.httpBody = body

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: req)
        } catch {
            throw ApiError.network(error)
        }
        guard let http = response as? HTTPURLResponse else {
            throw ApiError.http(status: -1, code: nil, message: "No response")
        }
        if http.statusCode >= 400 {
            // A 401 on a call that carried our bearer means the server no
            // longer recognises the session — the token may have expired or
            // been revoked from another device. Clearing it here (rather than
            // only on an explicit sign-out) is the difference between landing
            // on sign-in and sitting on a home screen whose every request 401s.
            // Anonymous calls (sign-in, create, reset) 401 for wrong
            // credentials, so only an authenticated rejection kills the session.
            if http.statusCode == 401, bearer != nil {
                DispatchQueue.main.async {
                    SessionStore.clear()
                    NotificationCenter.default.post(name: .gmSessionExpired, object: nil)
                }
            }
            let carrier = try? decoder.decode(ErrorCarrier.self, from: data)
            throw ApiError.http(
                status: http.statusCode,
                code: carrier?.code,
                message: carrier?.message ?? carrier?.error ?? ""
            )
        }
        // A 2xx with an unexpected shape (HTML error page, gateway stub)
        // must surface as a clean ApiError, not raw DecodingError text —
        // callers show localizedDescription directly to the user.
        do {
            return try decoder.decode(T.self, from: data)
        } catch let e as DecodingError {
            print("Api decode error: \(e)")
            throw ApiError.http(status: http.statusCode, code: nil, message: "Unexpected server response. Please try again.")
        }
    }

    // ── Endpoints ───────────────────────────────────────────────────────────

    static func checkHandle(_ handle: String) async throws -> CheckResponse {
        let encoded = handle.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? handle
        return try await request("/auth/grapmail/check?handle=\(encoded)")
    }

    static func createAccount(_ payload: CreateRequest) async throws -> CreateResponse {
        let body = try encoder.encode(payload)
        return try await request("/auth/account/create", method: "POST", body: body)
    }

    static func signIn(identifier: String, password: String) async throws -> SignInResponse {
        let body = try encoder.encode(SignInRequest(identifier: identifier, password: password))
        return try await request("/auth/account/signin", method: "POST", body: body)
    }

    static func twoFactorCode(challengeId: String, code: String) async throws -> TwoFactorResponse {
        let body = try encoder.encode(TwoFactorRequest(challengeId: challengeId, code: code))
        return try await request("/auth/account/twofactor", method: "POST", body: body)
    }

    static func twoFactorBackup(identifier: String, password: String, backupCode: String) async throws -> TwoFactorResponse {
        let body = try encoder.encode(TwoFactorRequest(identifier: identifier, password: password, backupCode: backupCode))
        return try await request("/auth/account/twofactor", method: "POST", body: body)
    }

    /// Step 1 of a reset. `challengeId` is present only when a code went out;
    /// absent means the account takes the slower manual route.
    static func forgotPassword(identifier: String) async throws -> ForgotPasswordResponse {
        let body = try encoder.encode(ForgotPasswordRequest(identifier: identifier))
        return try await request("/auth/account/reset/start", method: "POST", body: body)
    }

    /// Step 2: the code and the new password are checked together, by design.
    static func resetPassword(challengeId: String, code: String, password: String) async throws -> ResetPasswordResponse {
        let body = try encoder.encode(ResetPasswordRequest(challengeId: challengeId, code: code, password: password))
        return try await request("/auth/account/reset/confirm", method: "POST", body: body)
    }

    /// Phone -> partially hidden address. A full address only ever reaches the
    /// clipboard, never the screen.
    static func findAddress(phone: String) async throws -> FindAddressResponse {
        let body = try encoder.encode(FindAddressRequest(phone: phone))
        return try await request("/auth/account/find", method: "POST", body: body)
    }

    static func startPhoneVerification(phone: String) async throws -> PhoneStartResponse {
        let body = try encoder.encode(PhoneStartRequest(phone: phone))
        return try await request("/auth/phone/start", method: "POST", body: body)
    }

    static func verifyPhone(challengeId: String, code: String, token: String? = nil) async throws -> PhoneVerifyResponse {
        let body = try encoder.encode(PhoneVerifyRequest(challengeId: challengeId, code: code))
        // The bearer is optional: the sign-up wizard verifies with no session yet,
        // while the 2FA flow verifies the account's own number from a signed-in
        // device. Only the latter can mint the phone proof the enable call spends.
        return try await request("/auth/phone/verify", method: "POST", body: body, bearer: token)
    }

    /// Saves the verified number to the signed-in account: the phone token is
    /// the proof of possession, the bearer token is who it belongs to.
    static func linkPhone(sessionToken: String, phoneToken: String) async throws -> LinkPhoneResponse {
        let body = try encoder.encode(LinkPhoneRequest(phoneToken: phoneToken))
        return try await request("/auth/account/profile", method: "PATCH", body: body, bearer: sessionToken)
    }

    // ── Security sector ─────────────────────────────────────────────────────

    static func profile(token: String) async throws -> ProfileResponse {
        try await request("/auth/account/profile", bearer: token)
    }

    static func enableTwoFactor(token: String, proof: String?, phoneProof: String?) async throws -> ProfileResponse {
        let body = try encoder.encode(TwoFactorEnableRequest(twoFactor: "on", proof: proof ?? "", phoneProof: phoneProof ?? ""))
        return try await request("/auth/account/profile", method: "PATCH", body: body, bearer: token)
    }

    static func disableTwoFactor(token: String, password: String) async throws -> ProfileResponse {
        let body = try encoder.encode(TwoFactorDisableRequest(twoFactor: "off", currentPassword: password))
        return try await request("/auth/account/profile", method: "PATCH", body: body, bearer: token)
    }

    static func changePassword(token: String, current: String, next: String) async throws -> ProfileResponse {
        let body = try encoder.encode(PasswordChangeRequest(currentPassword: current, newPassword: next))
        return try await request("/auth/account/profile", method: "PATCH", body: body, bearer: token)
    }

    static func generateBackupCodes(token: String) async throws -> ProfileResponse {
        let body = try encoder.encode(BackupCodesRequest(generateBackupCodes: true))
        return try await request("/auth/account/profile", method: "PATCH", body: body, bearer: token)
    }

    static func signOutEverywhere(token: String) async throws -> ProfileResponse {
        let body = try encoder.encode(SignOutEverywhereRequest(signOutEverywhere: true))
        return try await request("/auth/account/profile", method: "PATCH", body: body, bearer: token)
    }

    static func verifyPassword(token: String, password: String) async throws -> ProfileResponse {
        let body = try encoder.encode(VerifyPasswordRequest(password: password))
        return try await request("/auth/account/verify-password", method: "POST", body: body, bearer: token)
    }

    static func sessions(token: String) async throws -> SessionsResponse {
        try await request("/auth/account/sessions", bearer: token)
    }

    static func revokeSession(token: String, id: String) async throws -> ProfileResponse {
        let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? id
        return try await request("/auth/account/sessions?id=\(encoded)", method: "DELETE", bearer: token)
    }

    static func activity(token: String) async throws -> ActivityResponse {
        try await request("/auth/account/activity", bearer: token)
    }

    /// "This sign-in was me": recorded, not deleted, so the alert can clear
    /// honestly and the original row stays in the history.
    static func acknowledgeActivity(token: String) async throws -> ProfileResponse {
        try await request("/auth/account/activity", method: "POST", body: Data("{}".utf8), bearer: token)
    }
}
