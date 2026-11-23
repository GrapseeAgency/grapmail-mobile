import Foundation

/// `GET/PUT/DELETE /grapmail/e2e-recovery` (backend routes/grapmail-e2e-recovery.ts):
/// the encrypted-mail recovery kit. The kit is ciphertext only; the recovery key
/// that opens it never leaves the device.
struct E2eRecoveryStatus {
    var success = false
    var exists = false
    var createdAt: String?
    /// Only with `includeKit`: the locked kit, opened on the device.
    var kit: [String: Any]?
    var error: String?

    init(_ obj: [String: Any]) {
        success = obj["success"] as? Bool ?? false
        exists = obj["exists"] as? Bool ?? false
        createdAt = obj["createdAt"] as? String
        kit = obj["kit"] as? [String: Any]
        error = obj["error"] as? String
    }
}

extension ApiClient {
    /// The kit is free-form JSON the crypto layer reads, so this route is read
    /// with JSONSerialization rather than a Decodable type.
    private static func e2eRecoveryCall(_ method: String, query: String = "", body: Data? = nil,
                                        token: String) async throws -> E2eRecoveryStatus {
        guard let url = URL(string: "\(base)\(mailBase)/e2e-recovery\(query)") else {
            throw ApiError.http(status: -1, code: nil, message: "Bad URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = 20
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(DeviceId.value, forHTTPHeaderField: "x-device-id")
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.httpBody = body
        let (data, http) = try await authenticatedResponse(for: req, bearer: token)
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard http.statusCode < 400 else {
            throw ApiError.http(status: http.statusCode, code: obj["code"] as? String,
                                message: obj["error"] as? String ?? "")
        }
        return E2eRecoveryStatus(obj)
    }

    static func e2eRecoveryStatus(token: String, includeKit: Bool = false) async throws -> E2eRecoveryStatus {
        try await e2eRecoveryCall("GET", query: includeKit ? "?include=kit" : "", token: token)
    }

    static func e2eRecoverySave(token: String, kit: [String: Any]) async throws -> E2eRecoveryStatus {
        let body = try JSONSerialization.data(withJSONObject: ["kit": kit])
        return try await e2eRecoveryCall("PUT", body: body, token: token)
    }

    static func e2eRecoveryRemove(token: String) async throws -> E2eRecoveryStatus {
        try await e2eRecoveryCall("DELETE", token: token)
    }
}
