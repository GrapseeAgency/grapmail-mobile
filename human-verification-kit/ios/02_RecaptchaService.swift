import Foundation
import RecaptchaEnterprise

/// OPTIONAL (P2): reCAPTCHA Enterprise native client for iOS — a frictionless,
/// score-based layer on top of the REQUIRED visible challenge (the /verify/human
/// checkpoint WebView in HumanVerifyView.swift). The native SDK never shows UI;
/// it returns a token you can attach to GrapMail API calls as an extra signal.
///
/// Dependency: the reCAPTCHA Enterprise mobile SDK
///   SPM: github.com/GoogleCloudPlatform/recaptcha-enterprise-mobile-sdk
///   (v18.10.0-beta01 at the time of writing; CocoaPods support has ended — SPM only)
/// Requirements: iOS 15+, App Attest capability on production builds.
///
/// Signatures below follow docs.cloud.google.com/recaptcha/docs/instrument-ios-apps —
/// re-check them against the SDK version you adopt.

/// Errors surfaced by `RecaptchaService`.
enum RecaptchaServiceError: Error {
    case clientNotReady
}

@MainActor
final class RecaptchaService {
    static let shared = RecaptchaService()

    // TODO(deploy): the Enterprise site key for this app (reCAPTCHA admin console).
    private let siteKey = "YOUR_ENTERPRISE_SITE_KEY"

    private var client: RecaptchaClient?

    /// Call early in the app lifecycle (e.g. .task on the root view).
    /// Initialization can take seconds — never block UI on it.
    func warmup() async {
        guard client == nil else { return }
        do {
            client = try await Recaptcha.fetchClient(withSiteKey: siteKey)
        } catch {
            // Silent by design; the visible checkpoint remains the primary gate.
        }
    }

    /// Per-action token with a timeout. Tokens are single-use, ~2 minutes TTL —
    /// fetch right before the call that consumes them.
    func token(for action: RecaptchaAction = RecaptchaAction.login,
               timeout: TimeInterval = 10) async throws -> String {
        if client == nil { await warmup() }
        guard let client else { throw RecaptchaServiceError.clientNotReady }
        return try await client.execute(withAction: action, withTimeout: timeout)
    }
}
