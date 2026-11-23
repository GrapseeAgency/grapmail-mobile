import CryptoKit
import Foundation
import Security

/// This device's session key, for DPoP proofs (RFC 9449; backend lib/dpop.ts).
///
/// A P-256 key in the Secure Enclave where the device has one (every iPhone with
/// Face ID or Touch ID), a software P-256 key otherwise. Either way it is kept in the
/// Keychain as this-device-only, so it never leaves the phone. Every token request
/// carries a short proof signed with it, and the server binds the session to its
/// public key, so a refresh token copied off this phone cannot be used elsewhere.
enum DeviceKey {
    private static let account = "grapmail.dpop.v1"

    private enum Key {
        case enclave(SecureEnclave.P256.Signing.PrivateKey)
        case software(P256.Signing.PrivateKey)

        var publicKey: P256.Signing.PublicKey {
            switch self {
            case .enclave(let k): return k.publicKey
            case .software(let k): return k.publicKey
            }
        }

        func sign(_ data: Data) throws -> Data {
            switch self {
            case .enclave(let k): return try k.signature(for: data).rawRepresentation
            case .software(let k): return try k.signature(for: data).rawRepresentation
            }
        }
    }

    private static let lock = NSLock()
    private static var cached: Key?

    private static func stored() -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    private static func store(_ data: Data) {
        let attrs: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: account] as CFDictionary)
        SecItemAdd(attrs as CFDictionary, nil)
    }

    private static func key() throws -> Key {
        lock.lock(); defer { lock.unlock() }
        if let cached { return cached }
        // One leading byte says which kind is stored: 1 = Secure Enclave blob, 2 = raw software key.
        if let data = stored(), let kind = data.first {
            let body = data.dropFirst()
            if kind == 1, let k = try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: body) {
                cached = .enclave(k); return cached!
            }
            if kind == 2, let k = try? P256.Signing.PrivateKey(rawRepresentation: body) {
                cached = .software(k); return cached!
            }
        }
        let made: Key
        if SecureEnclave.isAvailable, let k = try? SecureEnclave.P256.Signing.PrivateKey() {
            made = .enclave(k)
            store(Data([1]) + k.dataRepresentation)
        } else {
            let k = P256.Signing.PrivateKey()
            made = .software(k)
            store(Data([2]) + k.rawRepresentation)
        }
        cached = made
        return made
    }

    private static func b64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// A `DPoP` header value for one request, or nil if this device cannot make one
    /// (the request then goes without, and the session stays unbound as before).
    static func proof(method: String, url: URL) -> String? {
        guard let key = try? key() else { return nil }
        let point = key.publicKey.x963Representation   // 0x04 || X(32) || Y(32)
        guard point.count == 65 else { return nil }
        let x = b64url(point.subdata(in: 1..<33)), y = b64url(point.subdata(in: 33..<65))
        var parts = URLComponents(url: url, resolvingAgainstBaseURL: false)
        parts?.query = nil
        parts?.fragment = nil
        let htu = (parts?.url ?? url).absoluteString.replacingOccurrences(of: "\"", with: "")
        let header = "{\"alg\":\"ES256\",\"typ\":\"dpop+jwt\",\"jwk\":{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"\(x)\",\"y\":\"\(y)\"}}"
        let payload = "{\"htm\":\"\(method)\",\"htu\":\"\(htu)\",\"iat\":\(Int(Date().timeIntervalSince1970)),\"jti\":\"\(UUID().uuidString)\"}"
        let input = b64url(Data(header.utf8)) + "." + b64url(Data(payload.utf8))
        guard let signature = try? key.sign(Data(input.utf8)) else { return nil }
        return input + "." + b64url(signature)
    }

    /// The token routes that bind or check the session (backend auth-service/index.ts).
    private static let tokenPaths = ["/auth/login", "/auth/verify-otp", "/auth/resend-otp", "/auth/refresh",
                                     "/auth/social", "/auth/google", "/auth/account/twofactor"]

    static func wantsProof(method: String, url: URL) -> Bool {
        method == "POST" && tokenPaths.contains { url.path.hasSuffix($0) }
    }

    /// Add the proof to a request that needs one.
    static func attach(to request: inout URLRequest) {
        guard let url = request.url, let method = request.httpMethod, wantsProof(method: method, url: url),
              let proof = proof(method: method, url: url) else { return }
        request.setValue(proof, forHTTPHeaderField: "DPoP")
    }
}
