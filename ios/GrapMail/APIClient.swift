import Foundation
import UIKit

/// Lenient decoding helpers.
///
/// Swift's synthesized `Decodable` throws `keyNotFound` for a missing key even
/// when the property has a `= default` — a default is NOT consulted during
/// decoding. The GrapMail account backend (a different service than the web
/// API) omits optional-in-practice fields per endpoint, which Android's
/// kotlinx handles by applying the default but iOS turned into a hard decode
/// failure: the sign-in and every security screen's fetch reported "Unexpected
/// server response" / "couldn't load" and the 2FA toggle stayed disabled.
/// These helpers decode each field with `decodeIfPresent` + the default, so a
/// missing key yields the default instead of throwing — iOS parity with
/// Android's lenient behavior.
extension KeyedDecodingContainer {
    func lenient<T: Decodable>(_ key: K, _ def: T) -> T {
        // `try?` of `decodeIfPresent` (which returns T? and throws) flattens to
        // T?: nil covers both "key absent / null" and "threw", so one binding
        // is enough — a second `let v` fails to compile (v is already T).
        if let v = try? decodeIfPresent(T.self, forKey: key) { return v }
        return def
    }
    func lenientOpt<T: Decodable>(_ key: K) -> T? {
        (try? decodeIfPresent(T.self, forKey: key)) ?? nil
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
}

struct CheckResponse: Codable {
    var success: Bool = false
    var available: Bool = false
    var handle: String = ""
    var address: String?
    var suggestion: String?
    var reason: String?
}

struct SignInRequest: Codable {
    var identifier: String
    var password: String
}

struct SignInResponse: Codable {
    var success: Bool = false
    var sessionToken: String?
    var accessToken: String?
    var token: String?
    var expiresAt: Int?
    var account: AccountDto?
    // The live /auth/account/signin envelope is
    // { success, message, accessToken, refreshToken, expiresIn, suspicious, user }
    // — it carries neither requireOtp nor delivered. Swift's synthesized
    // Decodable throws keyNotFound for a missing NON-optional field (a `= false`
    // default is not applied during decoding), so these must be Optional or the
    // whole response fails to decode and sign-in reports 'Unexpected server
    // response'. Android's kotlinx applies the default for a missing key, which
    // is why only iOS broke. Optional + `?? false` at the call sites is parity.
    var requireOtp: Bool?
    var challengeId: String?
    var delivered: Bool?
    var backupCodesRemaining: Int?
    var message: String?
    var error: String?
    var code: String?
}

struct TwoFactorRequest: Codable {
    var challengeId: String?
    var code: String?
    var identifier: String?
    var password: String?
    var backupCode: String?
}

struct TwoFactorResponse: Codable {
    var success: Bool = false
    var sessionToken: String?
    var accessToken: String?
    var token: String?
    var expiresAt: Int?
    var user: AccountDto?
    var error: String?
    var code: String?
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
}

struct LinkPhoneRequest: Codable { var phoneToken: String }

struct LinkPhoneResponse: Codable {
    var success: Bool = false
    var message: String?
    var error: String?
    var code: String?
}

// ── Security sector (the 24-screen batch) ───────────────────────────────────

struct ProfileDto: Codable {
    var id = ""
    var name = ""
    var address = ""
    var handle = ""
    var phone: String?
    var twoFactor = "off"
    /// The server's own answer to "is the second factor on", so no client re-derives it.
    var twoFactorEnabled = false
    var twoFactorEnabledAt: String?
    var backupCodesRemaining = 0
    var passwordChangedAt: String?
    var createdAt: String?

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
        twoFactor = c.lenient(.twoFactor, "off")
        twoFactorEnabled = c.lenient(.twoFactorEnabled, false)
        twoFactorEnabledAt = c.lenientOpt(.twoFactorEnabledAt)
        backupCodesRemaining = c.lenient(.backupCodesRemaining, 0)
        passwordChangedAt = c.lenientOpt(.passwordChangedAt)
        createdAt = c.lenientOpt(.createdAt)
    }
}

struct ProfileResponse: Codable {
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
    var message: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, profile, proof, newBackupCodes, accessToken, tokenExpiresAt,
             message, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        profile = c.lenientOpt(.profile)
        proof = c.lenientOpt(.proof)
        newBackupCodes = c.lenientOpt(.newBackupCodes)
        accessToken = c.lenientOpt(.accessToken)
        tokenExpiresAt = c.lenientOpt(.tokenExpiresAt)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
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
