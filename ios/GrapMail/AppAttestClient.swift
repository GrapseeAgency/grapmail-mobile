import CryptoKit
import DeviceCheck
import Foundation

/// App Attest — the DeviceCheck proof that THIS binary, running on THIS
/// hardware, is the one Apple's servers validated.
///
/// ── THE TWO-STAGE CONTRACT ──────────────────────────────────────────────────
/// The client cannot invent the challenge: DCAppAttestService hashes whatever
/// bytes the caller hands it, and a proof over a client-chosen value proves
/// nothing. The server therefore owns a one-time challenge per stage, stored
/// hashed server-side:
///   1. POST /grapsee/auth/devices/:id/attest  {attestationLevel: "app_attest",
///      keyId, stage: "challenge"}  →  {challenge: <base64>}
///   2. clientHash = SHA256(challenge bytes);
///      DCAppAttestService.attestKey(keyId, clientHash) → attestation object
///   3. POST {attestationLevel: "app_attest", keyId, attestation,
///      stage: "attest"}
///   4. later, per sensitive operation: the same challenge step, then
///      DCAppAttestService.generateAssertion(keyId, clientHash) → POST
///      {attestationLevel: "app_attest", keyId, assertion, prevAuthData}
/// where `prevAuthData` is the challenge bytes the PREVIOUS proof hashed, so
/// the server can chain the authenticator counter across proofs.
///
/// ── SERVER GAP (owned by the integrator, not this file) ─────────────────────
/// The deployed attest route today reads only {attestationLevel} and records
/// the level without verifying anything — no `stage` handling, no challenge
/// store, no App Attest verification. Two-stage challenge endpoint required:
/// {stage: "challenge" | "attest" | "assert"}. The client implements the full
/// contract anyway and degrades honestly: when the challenge answer carries no
/// `challenge` field (or refuses), the outcome is `.recorded` — the level was
/// recorded, but nothing was verified. A device is never made less trusted
/// because its proof could not run.
///
/// ── STORAGE ─────────────────────────────────────────────────────────────────
/// The key id lives in the same generic-password Keychain the session token
/// uses (`Keychain` in SessionStore.swift, `grapmail.app-attest.*` accounts,
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`). The key ITSELF never
/// leaves the Secure Enclave; the key id is an opaque handle, not a secret.
enum AppAttestClient {
    private static let keychainKeyId = "grapmail.app-attest.key-id"
    /// The challenge bytes the last successful proof hashed — the chaining
    /// half of `prevAuthData`, kept local so the client never has to ask the
    /// server what it sent last time.
    private static let keychainPrevChallenge = "grapmail.app-attest.prev-challenge"

    enum Outcome: Equatable {
        /// The server verified the attestation object.
        case attested
        /// The level was recorded but no proof was verified — the route has
        /// not deployed the two-stage challenge contract yet.
        case recorded
        /// This device cannot produce an App Attest proof at all
        /// (simulator, unsupported hardware).
        case unsupported
        /// A real failure worth a log line at the call site, never a
        /// silent swallow.
        case failed(String)
    }

    /// Whether THIS device can run App Attest at all.
    static var isSupported: Bool { DCAppAttestService.shared.isSupported }

    /// The key id, created on first use and kept in the Keychain. A stored id
    /// Apple no longer knows (a restore, a wiped provisioning record) would
    /// fail every later proof, so a failed proof deletes the local handle and
    /// lets the next attempt generate a fresh key — the same replace-don't-
    /// keep repair DeviceKeyManager applies to an unparseable key.
    static func keyId() async -> String? {
        if let existing = Keychain.get(keychainKeyId) { return existing }
        do {
            let fresh = try await DCAppAttestService.shared.generateKey()
            guard Keychain.set(fresh, for: keychainKeyId) else { return nil }
            return fresh
        } catch {
            return nil
        }
    }

    /// The full two-stage attestation for one enrolled device row. Called
    /// right after a successful enrollment; every outcome is safe to ignore —
    /// attestation strengthens a trusted device, it never gates one.
    @discardableResult
    static func attestForDevice(token: String, deviceId: String) async -> Outcome {
        guard isSupported else { return .unsupported }
        guard let id = await keyId() else { return .failed("The App Attest key could not be created.") }
        do {
            // Stage 1 — the one-time challenge. A route that answers without
            // one has not deployed the two-stage contract; the level-only
            // record it did write is exactly what `.recorded` reports.
            let challengeResponse = try await ApiClient.attestDevice(
                token: token, id: deviceId, level: "app_attest",
                stage: "challenge", keyId: id)
            guard challengeResponse.success, let challenge = challengeResponse.challenge?.nilIfBlank(),
                  let challengeBytes = challenge.wireBytes() else {
                return .recorded
            }
            // Stage 2 — Apple attests this device+binary over the challenge
            // hash, and the attestation object comes back to the app, which
            // hands it to the server for verification.
            let clientHash = Data(SHA256.hash(data: challengeBytes))
            let attestation = try await DCAppAttestService.shared.attestKey(id, clientDataHash: clientHash)
            let submitted = try await ApiClient.attestDevice(
                token: token, id: deviceId, level: "app_attest",
                stage: "attest", keyId: id,
                attestation: attestation.base64EncodedString())
            guard submitted.success else {
                return .failed(submitted.error?.nilIfBlank() ?? "The attestation was not accepted.")
            }
            remember(challengeBytes)
            return .attested
        } catch let apiError as ApiError {
            return .failed(apiError.userMessage)
        } catch {
            // A DeviceCheck-side failure is this device's problem, not the
            // account's — degrade, and drop the key only when Apple says the
            // key itself is gone (a network failure must not churn keys the
            // next attempt could still use).
            if Self.isDeadKey(error) { Keychain.delete(keychainKeyId) }
            return .failed(error.localizedDescription)
        }
    }

    /// The assertion stage, for the sensitive operations the integrator wires
    /// later. Same shape as the attestation: the server's challenge is what
    /// gets hashed (never client-chosen data), the assertion comes back from
    /// the Secure Enclave, and the body chains `prevAuthData` — the challenge
    /// bytes the previous proof hashed — so the server can verify the counter
    /// moved forward. Untyped on purpose: which operations need an assertion,
    /// and what their canonical clientData is, is the server contract's call.
    @discardableResult
    static func assertForDevice(token: String, deviceId: String) async -> Outcome {
        guard isSupported else { return .unsupported }
        guard let id = await keyId() else { return .failed("The App Attest key could not be created.") }
        do {
            let challengeResponse = try await ApiClient.attestDevice(
                token: token, id: deviceId, level: "app_attest",
                stage: "challenge", keyId: id)
            guard challengeResponse.success, let challenge = challengeResponse.challenge?.nilIfBlank(),
                  let challengeBytes = challenge.wireBytes() else {
                return .recorded
            }
            let clientHash = Data(SHA256.hash(data: challengeBytes))
            let assertion = try await DCAppAttestService.shared.generateAssertion(id, clientDataHash: clientHash)
            let submitted = try await ApiClient.attestDevice(
                token: token, id: deviceId, level: "app_attest",
                stage: "assert", keyId: id,
                assertion: assertion.base64EncodedString(),
                prevAuthData: previousChallenge()?.base64EncodedString())
            guard submitted.success else {
                return .failed(submitted.error?.nilIfBlank() ?? "The assertion was not accepted.")
            }
            remember(challengeBytes)
            return .attested
        } catch let apiError as ApiError {
            return .failed(apiError.userMessage)
        } catch {
            if Self.isDeadKey(error) { Keychain.delete(keychainKeyId) }
            return .failed(error.localizedDescription)
        }
    }

    // ── the chaining bookkeeping ─────────────────────────────────────────────

    /// The statuses that name a key Apple no longer honors rather than a bad
    /// network — the same replace-don't-keep rule DeviceKeyManager encodes for
    /// its own conflict statuses. `DCError.invalidKey` covers both a key the
    /// service cannot find and one it refused (already attested, server-
    /// rejected assertion); its documented recovery is exactly what the
    /// caller does here: drop the handle and generate fresh.
    private static func isDeadKey(_ error: Error) -> Bool {
        guard let dcError = error as? DCError else { return false }
        return dcError.code == .invalidKey
    }

    private static func remember(_ challengeBytes: Data) {
        _ = Keychain.set(challengeBytes.base64EncodedString(), for: keychainPrevChallenge)
    }

    private static func previousChallenge() -> Data? {
        Keychain.get(keychainPrevChallenge).flatMap { Data(base64Encoded: $0) }
    }
}

private extension String {
    /// The challenge on the attest wire is base64 by contract; the base64url
    /// alphabet is accepted alongside it so a route variant cannot strand the
    /// client on an encoding detail neither side intended as a contract.
    func wireBytes() -> Data? {
        Data(base64Encoded: self) ?? Data(base64Encoded: Self.base64UrlToBase64(self))
    }

    private static func base64UrlToBase64(_ value: String) -> String {
        var normalized = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = normalized.count % 4
        if remainder > 0 {
            normalized.append(String(repeating: "=", count: 4 - remainder))
        }
        return normalized
    }
}
