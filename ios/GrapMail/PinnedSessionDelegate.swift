import CryptoKit
import Foundation
import OSLog
import Security

// Certificate (SPKI) pinning for the GrapMail API hosts: the iOS half of
// `security/api-pins.json`. The Android half is `apiPinner` in
// android/app/src/main/java/com/grapsee/grapmail/data/Api.kt, and both read the
// same JSON (here through the generated ApiPins.swift), so a pin that passes
// on one platform passes on the other.
//
// The rules, identical on both platforms:
//   - Only the hosts in `ApiPins.hosts` are pinned, by exact host name. Every
//     other host (sender images, the App Store, anything else) gets the
//     system's default handling.
//   - Pinning is on only when `ApiPins.enforce` is true, at least TWO
//     well-formed pins are compiled in, and the current time is before
//     `ApiPins.expiresAt`. A single pin with no backup is how an app locks every
//     user out the day the server's certificate changes, so one pin = no pinning.
//   - After `expires` pinning switches itself OFF and the connection is checked
//     by the platform's normal certificate validation only (fail-open), with a
//     logged warning. That is what Android's own Network Security Config does
//     with `<pin-set expiration>`. The alternative (fail-closed) would cut off
//     every install that was not updated in time, through the only channel that
//     could tell it to update. The weekly CI check (scripts/check-api-pins.py)
//     makes the date visible long before it arrives (OWASP MASTG-TEST-0022).
//   - Pinning only ever NARROWS trust: the system's own evaluation must pass
//     first, then one certificate in the evaluated chain must carry a pinned
//     key.
//
// Why not NSPinnedDomains in Info.plist: ATS pins have no expiry and no
// minimum-count rule, so a stale ATS pin set is a permanent lockout for old
// installs, and it could not share the JSON with Android.
//
// The hash: base64(SHA-256(DER SubjectPublicKeyInfo)), the same value OkHttp
// writes after `sha256/` and scripts/print-api-pins.sh prints. iOS hands out
// only the raw key (`SecCertificateCopyKey` + `SecKeyCopyExternalRepresentation`:
// PKCS#1 RSAPublicKey for RSA, the X9.63 point 04||X||Y for EC), so the SPKI
// wrapper is rebuilt here before hashing. Fixed headers for the common sizes,
// for reference (they are what `SPKI.der(for:)` produces):
//   RSA 2048:  30820122300d06092a864886f70d01010105000382010f00
//   RSA 4096:  30820222300d06092a864886f70d01010105000382020f00
//   EC P-256:  3059301306072a8648ce3d020106082a8648ce3d030107034200
//   EC P-384:  3076301006072a8648ce3d020106052b81040022036200
// The lengths are computed rather than hard-coded, so an RSA key of another
// size or exponent still hashes correctly. Other key types (P-521, Ed25519) are
// skipped: that certificate cannot match, and another one in the chain must.

/// Whether pinning is in force, from the generated `ApiPins`.
enum PinPolicy {
    static let log = Logger(subsystem: "com.grapsee.grapmail", category: "TLSPinning")

    /// Well-formed pins only: base64 that decodes to exactly 32 bytes.
    static let pins: Set<String> = Set(ApiPins.sha256.filter { Data(base64Encoded: $0)?.count == 32 })

    static let hosts: Set<String> = Set(ApiPins.hosts.map { $0.lowercased() })

    /// Turned on in JSON, with a backup, for a host list, and not past expiry.
    static var isConfigured: Bool {
        ApiPins.enforce && pins.count >= 2 && !hosts.isEmpty
    }

    static func isEnforcing(at now: Date = Date()) -> Bool {
        isConfigured && now.timeIntervalSince1970 < ApiPins.expiresAt
    }
}

/// The session the API calls go through.
///
/// When pinning is not in force this IS `URLSession.shared`, so an unpinned
/// build behaves exactly as before. When it is, it is a default-configuration
/// session (same shared cookie store, URL cache and credential store as
/// `.shared`) whose delegate checks the pins.
enum PinnedSession {
    static let shared: URLSession = {
        guard PinPolicy.isEnforcing() else {
            if PinPolicy.isConfigured {
                PinPolicy.log.warning("Certificate pin set expired on \(ApiPins.expires, privacy: .public): pinning is off, platform trust only. Ship an update with a fresh security/api-pins.json.")
            }
            return URLSession.shared
        }
        return URLSession(configuration: .default, delegate: PinnedSessionDelegate(), delegateQueue: nil)
    }()
}

/// Session-level server-trust handler. TLS server trust is a session-level
/// challenge, so this method receives it for every task in the session,
/// including the async `data(for:)` / `download(for:)` / `upload(for:fromFile:)`
/// calls APIClient makes.
final class PinnedSessionDelegate: NSObject, URLSessionDelegate {
    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        let space = challenge.protectionSpace
        guard space.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = space.serverTrust,
              PinPolicy.hosts.contains(space.host.lowercased())
        else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        // The pin set can expire while the process is still running. Same
        // fail-open as at launch.
        guard PinPolicy.isEnforcing() else {
            PinPolicy.log.warning("Certificate pin set expired on \(ApiPins.expires, privacy: .public): not pinning \(space.host, privacy: .public)")
            completionHandler(.performDefaultHandling, nil)
            return
        }
        // The platform's own evaluation first. If it fails, default handling
        // refuses the connection with the usual system error, as before.
        guard SecTrustEvaluateWithError(trust, nil) else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        if SPKI.chainMatches(trust, pins: PinPolicy.pins) {
            completionHandler(.performDefaultHandling, nil)
        } else {
            PinPolicy.log.error("Certificate pin mismatch for \(space.host, privacy: .public): no key in the chain is in security/api-pins.json. Refusing the connection.")
            // The task fails with NSURLErrorCancelled, which APIClient reports
            // through its normal network-failure path.
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}

/// SubjectPublicKeyInfo hashing that matches OkHttp's `sha256/` pins.
enum SPKI {
    // AlgorithmIdentifier DER for each supported key type.
    private static let rsaEncryption: [UInt8] = [   // 1.2.840.113549.1.1.1, NULL
        0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00,
    ]
    private static let ecP256: [UInt8] = [          // id-ecPublicKey, prime256v1
        0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01,
        0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07,
    ]
    private static let ecP384: [UInt8] = [          // id-ecPublicKey, secp384r1
        0x30, 0x10, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01,
        0x06, 0x05, 0x2b, 0x81, 0x04, 0x00, 0x22,
    ]

    private static let rsaType = kSecAttrKeyTypeRSA as String
    private static let ecType = kSecAttrKeyTypeECSECPrimeRandom as String

    /// True when any certificate in the evaluated chain (leaf, intermediates
    /// and the trust anchor the system chose) carries a pinned key.
    static func chainMatches(_ trust: SecTrust, pins: Set<String>) -> Bool {
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate] else { return false }
        for certificate in chain {
            if let hash = sha256Base64(of: certificate), pins.contains(hash) {
                return true
            }
        }
        return false
    }

    static func sha256Base64(of certificate: SecCertificate) -> String? {
        guard let key = SecCertificateCopyKey(certificate), let spki = der(for: key) else { return nil }
        return Data(SHA256.hash(data: spki)).base64EncodedString()
    }

    /// DER SubjectPublicKeyInfo for a public key, or nil for unsupported types.
    static func der(for key: SecKey) -> Data? {
        guard let attributes = SecKeyCopyAttributes(key) as? [String: Any],
              let type = attributes[kSecAttrKeyType as String] as? String,
              let raw = SecKeyCopyExternalRepresentation(key, nil) as Data?
        else { return nil }
        let bits = (attributes[kSecAttrKeySizeInBits as String] as? NSNumber)?.intValue ?? 0

        let algorithm: [UInt8]
        if type == rsaType {
            algorithm = rsaEncryption
        } else if type == ecType, bits == 256 {
            algorithm = ecP256
        } else if type == ecType, bits == 384 {
            algorithm = ecP384
        } else {
            return nil
        }
        // BIT STRING { 0 unused bits, key bytes }
        let bitString: [UInt8] = [0x03] + derLength(raw.count + 1) + [0x00] + [UInt8](raw)
        let body = algorithm + bitString
        return Data([0x30] + derLength(body.count) + body)
    }

    private static func derLength(_ length: Int) -> [UInt8] {
        if length < 0x80 { return [UInt8(length)] }
        var bytes: [UInt8] = []
        var value = length
        while value > 0 {
            bytes.insert(UInt8(value & 0xff), at: 0)
            value >>= 8
        }
        return [0x80 | UInt8(bytes.count)] + bytes
    }
}
