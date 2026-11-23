import AuthenticationServices
import Foundation
import UIKit

/// Passkeys — the WebAuthn platform-credential bridge.
///
/// ── WHAT THE PLATFORM OWNS ──────────────────────────────────────────────────
/// The private half of every passkey lives in the iCloud Keychain and is used
/// only through ASAuthorizationController; this file never sees key material,
/// only the base64url JSON the WebAuthn wire format asks for. Registration
/// hands the server an attestation object; sign-in hands it an assertion —
/// exactly the two shapes the gateway's passkey routes forward to
/// @simplewebauthn/server for verification.
///
/// ── ENTITLEMENT (deploy-side, not code) ─────────────────────────────────────
/// A passkey bound to the relying-party id only works inside this app when the
/// app target carries the Associated Domains entitlement
/// `webcredentials:<rpId host>` AND the domain serves an apple-app-site-
/// association file naming this app's team + bundle id under that key. Without
/// both, the code below still compiles and prompts, but the platform refuses
/// to return a credential for the RP. The association itself is
/// founder/deploy-side work; the client proceeds regardless.
///
/// ── WHY THE CHALLENGE IS DECODED, NOT PASSED THROUGH ────────────────────────
/// The server stores the challenge STRING it sent, and verification compares
/// that string with the base64url challenge the clientDataJSON carries. The
/// platform base64url-encodes whatever BYTES the request carries, so the bytes
/// must be exactly base64url-decode(challenge) — the platform's encoding then
/// reproduces the server's string byte for byte. Passing the string's UTF-8
/// bytes instead would verify against a different challenge and fail at the
/// server with no way to see why.
///
/// ── THE ORIGIN ──────────────────────────────────────────────────────────────
/// The platform writes `https://<relyingPartyIdentifier>` into clientDataJSON's
/// origin — the rpId comes from ApiClient.rpId, the host of the same base every
/// call uses, and the server's expectedOrigin is the same deployment origin.
/// That match is the contract; this file must never touch the clientDataJSON.
enum PasskeyManager {
    /// The relying party the passkeys are bound to — the deployment origin's
    /// host, derived in the one constant place so it cannot drift from the
    /// API host the calls actually go to.
    static var relyingPartyIdentifier: String { ApiClient.rpId }

    /// The platform's registration answer, already in the wire encoding the
    /// verify route consumes. `clientDataJSON` is the platform's own bytes,
    /// origin untouched.
    struct Registration {
        let credentialId: String      // base64url
        let clientDataJSON: String    // base64url
        let attestationObject: String // base64url
    }

    /// The platform's assertion answer, in the wire encoding.
    struct Assertion {
        let credentialId: String      // base64url
        let clientDataJSON: String    // base64url
        let authenticatorData: String // base64url
        let signature: String         // base64url
        let userHandle: String?       // base64url — present when the credential carries one
    }

    /// The refusals a screen can distinguish. `.cancelled` is the person
    /// closing the sheet — no error line belongs under it.
    enum PasskeyError: Error, LocalizedError {
        case cancelled
        case failed(String)

        var errorDescription: String? {
            switch self {
            case .cancelled: return "Passkey sign-in was cancelled."
            case let .failed(message): return message
            }
        }
    }

    /// What the credential list should print for a passkey minted on this
    /// phone. UIDevice's name is the person's own label — the same string the
    /// trusted-device enrollment sends — so the passkey row and the device row
    /// read as one device. The server trims to 60; the client arrives trimmed.
    static func deviceLabel() -> String {
        let name = UIDevice.current.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? "My device" : String(name.prefix(60))
    }

    // ── Registration (signed in) ─────────────────────────────────────────────

    /// Run the platform's create flow for one registration challenge. The
    /// server's options name the RP it will verify against (`rp.id`); the
    /// client honors it rather than substituting its own, because the
    /// credential is bound to THAT id the moment it is minted.
    @MainActor
    static func register(options: PasskeyRegistrationOptions) async throws -> Registration {
        guard let challengeBytes = options.challenge.dataFromBase64Url() else {
            throw PasskeyError.failed("The passkey challenge was unreadable. Try again.")
        }
        guard let userIdBytes = options.user.id.dataFromBase64Url() else {
            throw PasskeyError.failed("The passkey account reference was unreadable. Try again.")
        }
        let rpId = options.rp.id.nilIfBlank() ?? relyingPartyIdentifier
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: rpId)
        let request = provider.createCredentialRegistrationRequest(
            challenge: challengeBytes,
            name: options.user.name,
            userID: userIdBytes
        )
        let authorization = try await perform(request)
        guard let credential = authorization.credential as? ASAuthorizationPlatformPublicKeyCredentialRegistration,
              let attestationObject = credential.rawAttestationObject else {
            throw PasskeyError.failed("The passkey could not be created on this device.")
        }
        return Registration(
            credentialId: credential.credentialID.base64UrlEncodedString(),
            clientDataJSON: credential.rawClientDataJSON.base64UrlEncodedString(),
            attestationObject: attestationObject.base64UrlEncodedString()
        )
    }

    // ── Sign-in (public) ─────────────────────────────────────────────────────

    /// Run the platform's assertion flow for one authentication challenge.
    /// No allow-list: the server matches the credential by the id the
    /// assertion carries, so the platform is asked for whatever discoverable
    /// credential the person holds for this RP.
    @MainActor
    static func assertion(options: PasskeyAuthenticationOptions) async throws -> Assertion {
        guard let challengeBytes = options.challenge.dataFromBase64Url() else {
            throw PasskeyError.failed("The passkey challenge was unreadable. Try again.")
        }
        let rpId = options.rpId?.nilIfBlank() ?? relyingPartyIdentifier
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: rpId)
        let request = provider.createCredentialAssertionRequest(challenge: challengeBytes)
        let authorization = try await perform(request)
        guard let credential = authorization.credential as? ASAuthorizationPlatformPublicKeyCredentialAssertion else {
            throw PasskeyError.failed("The passkey response was not a platform credential.")
        }
        // `userID` is the credential's user handle — the same bytes the
        // registration request carried as `userID`. nil-safe: a credential
        // without a handle still signs in, the server matches by rawId.
        let handle = credential.userID.isEmpty ? nil : credential.userID.base64UrlEncodedString()
        return Assertion(
            credentialId: credential.credentialID.base64UrlEncodedString(),
            clientDataJSON: credential.rawClientDataJSON.base64UrlEncodedString(),
            authenticatorData: credential.rawAuthenticatorData.base64UrlEncodedString(),
            signature: credential.signature.base64UrlEncodedString(),
            userHandle: handle
        )
    }

    // ── The controller bridge ────────────────────────────────────────────────

    /// Present one request through ASAuthorizationController and await its
    /// single answer. The session object lives on the suspended stack frame
    /// across the continuation, so the controller's weak delegate reference
    /// stays valid for exactly as long as the sheet can answer.
    @MainActor
    private static func perform(_ request: ASAuthorizationRequest) async throws -> ASAuthorization {
        let session = ControllerSession()
        return try await withCheckedThrowingContinuation { continuation in
            session.run(request, continuation: continuation)
        }
    }
}

/// One ASAuthorizationController, from performRequests to the delegate answer.
/// The delegate-adapter shape the rest of the app uses for controller-backed
/// flows (QuickLookPresenter's Coordinator), extended with a continuation
/// because this flow is one shot: exactly one answer, success or error, then
/// the object is done.
///
/// Deliberately NOT main-actor-isolated — AppLock's rule: a class whose methods
/// the system calls back on the main thread anyway should not force actor
/// annotations onto every call site. performRequests() runs inside the
/// MainActor continuation above; the delegate answers arrive on the main
/// thread, which is where the sheet's own callbacks live.
private final class ControllerSession: NSObject, ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    private var continuation: CheckedContinuation<ASAuthorization, Error>?

    func run(_ request: ASAuthorizationRequest, continuation: CheckedContinuation<ASAuthorization, Error>) {
        self.continuation = continuation
        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        controller.performRequests()
    }

    func authorizationController(controller: ASAuthorizationController,
                                 didCompleteWithAuthorization authorization: ASAuthorization) {
        continuation?.resume(returning: authorization)
        continuation = nil
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        // The system sheet's cancel is the person backing out, not a failure —
        // the screen must not print an error line for it.
        let passkeyError: PasskeyManager.PasskeyError
        if let authError = error as? ASAuthorizationError, authError.code == .canceled {
            passkeyError = .cancelled
        } else {
            passkeyError = .failed("Passkey sign-in failed. Try again.")
        }
        continuation?.resume(throwing: passkeyError)
        continuation = nil
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        // The active scene's key window — the anchor the system sheet attaches
        // to. The fallthrough anchor is only reachable when no window exists,
        // which is also the only case where the sheet cannot present at all.
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }
}

// ── base64url — the WebAuthn wire encoding ──────────────────────────────────
//
// RFC 4648 base64url, unpadded. File-private on purpose: the mailbox sector's
// base64 helpers are a different contract, and one global alphabet-swapping
// extension would silently convert payloads that were never WebAuthn's.

private extension Data {
    func base64UrlEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

private extension String {
    /// Decode base64url, tolerating the missing padding the wire form uses.
    func dataFromBase64Url() -> Data? {
        var normalized = replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = normalized.count % 4
        if remainder > 0 {
            normalized.append(String(repeating: "=", count: 4 - remainder))
        }
        return Data(base64Encoded: normalized)
    }
}
