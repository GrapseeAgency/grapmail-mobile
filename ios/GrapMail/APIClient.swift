import Foundation

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
    var requireOtp: Bool = false
    var challengeId: String?
    var delivered: Bool = false
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

struct ApiClient {
    static let base = "https://grapsee.com/api"
    private static let encoder = JSONEncoder()
    private static let decoder = JSONDecoder()

    private static func request<T: Decodable>(_ path: String, method: String = "GET", body: Data? = nil) async throws -> T {
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
        return try decoder.decode(T.self, from: data)
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
}
