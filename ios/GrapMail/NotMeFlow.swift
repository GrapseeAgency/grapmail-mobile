import SwiftUI

// MARK: - Round 8 — "This wasn't me" (docs/design/round8-spec.md §3.4)
//
// Confirm sheet → POST /grapsee/auth/security/not-me (§6.3) → step-up password
// sheet on 403 STEP_UP_REQUIRED → "Account secured" → change password in
// required mode → "Review your recovery options" → back to the Center.

/// The last not-me answer, in memory only, for the "Account secured" screen
/// the route pushes (the route itself carries only the device label).
@MainActor
enum NotMeFlowState {
    static var lastResult: NotMeResult?

    static func reset() { lastResult = nil }
}

/// One "This wasn't me" press: what it targets and the label to name.
struct NotMeRequest: Identifiable {
    let id = UUID()
    let target: NotMeTarget
    /// nil for an event with no device: the first bullet then reads "Sign out
    /// whoever did this".
    let label: String?
}

@MainActor
struct NotMeConfirmSheet: View {
    let request: NotMeRequest
    /// 200: the host closes the sheet, then pushes "Account secured".
    let onSecured: (String) -> Void
    /// 404: the target is gone; the host refetches.
    let onGone: () -> Void
    let onCancel: () -> Void

    private enum Stage { case confirm, password }

    @Environment(\.dynamicTypeSize) private var dts
    @State private var stage: Stage = .confirm
    @State private var busy = false
    @State private var error: String?
    @State private var canRetry = false
    @State private var password = ""
    @State private var proof: String?

    private var label: String { request.label?.nilIfBlank() ?? secL("Unknown device") }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                switch stage {
                case .confirm: confirm
                case .password: passwordStage
                }
            }
            .padding(24)
            .gmMaxWidth(GMLayout.form)
        }
        .presentationDetents([.large])
        .interactiveDismissDisabled(busy)
        .accessibilityIdentifier("sec-not-me-sheet")
    }

    // MARK: stages

    @ViewBuilder private var confirm: some View {
        HStack {
            Spacer()
            Art.view("ill_sec_alert", width: 240)
            Spacer()
        }
        Text(secL("Secure your account?"))
            .gmFont(.h2)
            .foregroundColor(GM.textPrimary)
            .accessibilityAddTraits(.isHeader)
        Text(bodyText)
            .gmFont(.body)
            .foregroundColor(GM.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
        VStack(alignment: .leading, spacing: 10) {
            bullet(request.label?.nilIfBlank() == nil
                   ? secL("Sign out whoever did this")
                   : secL("Sign out \(label) and remove its trust"))
            bullet(secL("Sign out all other sessions"))
            bullet(secL("Ask you to set a new password"))
        }
        if let error { errorBlock(error) }
        DestructiveActionButton(title: busy ? secL("Securing…") : secL("Secure my account"),
                                enabled: !busy, action: secure)
            .accessibilityIdentifier("sec-not-me-secure")
        Button(action: onCancel) {
            Text(secL("Cancel"))
                .gmFont(.bodyMedium)
                .foregroundColor(SecPalette.primaryText)
                .frame(maxWidth: .infinity, minHeight: 48)
        }
        .buttonStyle(.plain)
        .disabled(busy)
    }

    @ViewBuilder private var passwordStage: some View {
        Text(secL("Confirm it's you"))
            .gmFont(.h2)
            .foregroundColor(GM.textPrimary)
            .accessibilityAddTraits(.isHeader)
        // The signed-in address, so iCloud Keychain / a password manager can
        // offer this account's saved password for the step-up.
        if let username = SessionStore.address?.nilIfBlank() {
            CredentialUsernameField(username: username)
        }
        AppTextField(title: secL("Password"), value: $password, error: error,
                     isPassword: true, leadingIcon: "lock",
                     contentType: .password, focusOnAppear: true)
            .submitLabel(.continue)
            .onSubmit {
                if !password.isEmpty && !busy { confirmPassword() }
            }
        GradientButton(title: busy ? secL("Securing…") : secL("Continue"),
                       enabled: !password.isEmpty && !busy, busy: false, action: confirmPassword)
        Button(action: onCancel) {
            Text(secL("Cancel"))
                .gmFont(.bodyMedium)
                .foregroundColor(SecPalette.primaryText)
                .frame(maxWidth: .infinity, minHeight: 48)
        }
        .buttonStyle(.plain)
        .disabled(busy)
    }

    /// "We'll sign out **{device label}** right away, …" — the label is bold
    /// inside the translated sentence, whatever its position there.
    private var bodyText: AttributedString {
        let sentence = request.label?.nilIfBlank() == nil
            ? secL("We'll sign out whoever did this right away, then sign out every other device and browser except this one. You'll choose a new password next.")
            : secL("We'll sign out \(label) right away, then sign out every other device and browser except this one. You'll choose a new password next.")
        var text = AttributedString(sentence)
        if request.label?.nilIfBlank() != nil, let range = text.range(of: label) {
            text[range].inlinePresentationIntent = .stronglyEmphasized
        }
        return text
    }

    private func bullet(_ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: "checkmark")
                .gmFont(13, .semibold, maxSize: 22)
                .foregroundColor(GM.textSecondary)
                .accessibilityHidden(true)
            Text(text)
                .gmFont(.body)
                .foregroundColor(GM.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func errorBlock(_ text: String) -> some View {
        GMAdaptiveStack(spacing: 8) {
            Text(text)
                .gmFont(.caption)
                .foregroundColor(SecPalette.error)
                .fixedSize(horizontal: false, vertical: true)
            if !dts.isAccessibilitySize { Spacer(minLength: 4) }
            if canRetry {
                Button(action: secure) {
                    Text(secL("Try again"))
                        .gmFont(.captionMedium)
                        .foregroundColor(SecPalette.primaryText)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: GMShape.input, style: .continuous).fill(SecPalette.errorContainer))
        .accessibilityElement(children: .combine)
    }

    // MARK: calls

    private func secure() {
        guard !busy else { return }
        guard let token = SessionStore.token else {
            fail(secL("Couldn't reach GrapMail. Nothing was changed."), retry: false)
            return
        }
        busy = true
        error = nil
        canRetry = false
        Task { @MainActor in
            defer { busy = false }
            do {
                let response = try await ApiClient.reportNotMe(token: token, target: request.target, proof: proof)
                guard response.success else {
                    fail(secL("Couldn't reach GrapMail. Nothing was changed."), retry: true)
                    return
                }
                GMHaptics.success()
                NotMeFlowState.lastResult = response.result
                SecurityOverviewStore.shared.notePasswordChangeRequired(response.result.passwordChangeRequired)
                onSecured(label)
            } catch {
                handle(error)
            }
        }
    }

    private func handle(_ failure: Error) {
        switch failure {
        case let ApiError.http(status, code, _):
            if code == "STEP_UP_REQUIRED" || (status == 403 && code == nil && proof == nil) {
                // Not an error: the server asks for the password first (§3.4 step 3).
                proof = nil
                password = ""
                error = nil
                stage = .password
                return
            }
            if code == "NOT_FOUND" || status == 404 {
                fail(secL("That device is already gone."), retry: false)
                onGone()
            } else if code == "CANNOT_TARGET_CURRENT" || status == 409 {
                fail(secL("That's the device you're using. Use Sign out instead."), retry: false)
            } else if code == "RATE_LIMITED" || status == 429 {
                fail(secL("Too many attempts. Try again in a few minutes."), retry: false)
            } else {
                fail(apiMessage(failure, fallback: secL("Couldn't reach GrapMail. Nothing was changed.")), retry: true)
            }
        case ApiError.network:
            fail(secL("Couldn't reach GrapMail. Nothing was changed."), retry: true)
        default:
            fail(secL("Couldn't reach GrapMail. Nothing was changed."), retry: true)
        }
    }

    private func fail(_ message: String, retry: Bool) {
        GMHaptics.failure()
        stage = .confirm
        error = message
        canRetry = retry
        GMAnnounce.say(message)
    }

    /// verify-password {password, purpose: "secure-account"} → proof → retry.
    private func confirmPassword() {
        guard !busy, !password.isEmpty, let token = SessionStore.token else { return }
        busy = true
        error = nil
        Task { @MainActor in
            do {
                let response = try await ApiClient.verifyPasswordProof(token: token, password: password)
                guard let minted = response.proof?.nilIfBlank() else {
                    busy = false
                    GMHaptics.failure()
                    error = apiMessage(ApiError.http(status: response.success ? 200 : 403, code: response.code, message: response.error ?? ""),
                                       fallback: secL("Couldn't reach GrapMail. Nothing was changed."))
                    return
                }
                proof = minted
                password = ""
                busy = false
                stage = .confirm
                secure()
            } catch {
                busy = false
                GMHaptics.failure()
                if case ApiError.network = error {
                    self.error = secL("Couldn't reach GrapMail. Nothing was changed.")
                } else {
                    self.error = apiMessage(error, fallback: secL("Couldn't reach GrapMail. Nothing was changed."))
                }
            }
        }
    }
}

// MARK: - "Account secured" (route .secNotMeDone(device:))

/// Reuses the `SessionRevokedSuspiciousView` layout (AccountScreen, art
/// `ill_sec_revoked_suspicious`).
@MainActor
struct AccountSecuredView: View {
    let device: String
    let onChangePassword: () -> Void
    let onDone: () -> Void
    /// Snapshot host only.
    var previewResult: NotMeResult? = nil

    private var result: NotMeResult? { previewResult ?? NotMeFlowState.lastResult }

    private var message: String {
        if result?.alreadyReported == true {
            return secL("You already secured your account from this report. Set a new password if you haven't yet.")
        }
        let label = device.nilIfBlank() ?? secL("Unknown device")
        let others = max(0, result?.signedOutSessions ?? 0)
        return secL("\(label) and \(others) other sessions were signed out. Set a new password now to finish.")
    }

    var body: some View {
        AccountScreen(
            onBack: onDone,
            illustration: "ill_sec_revoked_suspicious",
            illustrationWidth: 170,
            title: secL("Account secured"),
            subtitle: message
        ) {
            InfoCard {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "checkmark.shield")
                        .gmFont(14)
                        .foregroundColor(SecPalette.success)
                        .accessibilityHidden(true)
                    Text(secL("Every other device and browser now needs your password to sign in."))
                        .gmFont(.caption)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        } footer: {
            GradientButton(title: secL("Change password"), action: onChangePassword)
                .accessibilityIdentifier("sec-secured-change-password")
        }
        .accessibilityIdentifier("sec-account-secured")
    }
}

// MARK: - Change password, required mode (route .secPasswordRequired)

/// The existing change-password screen; its title stays "Change password".
/// Back asks before leaving the account at risk (§3.4 step 4).
@MainActor
struct RequiredChangePasswordView: View {
    let onChanged: () -> Void
    let onLeave: () -> Void
    let onForgot: () -> Void

    @State private var confirmLeave = false

    var body: some View {
        ChangePasswordView(
            afterSuspicious: false,
            onBack: { confirmLeave = true },
            onForgot: onForgot,
            onChanged: {
                SecurityOverviewStore.shared.notePasswordChangeRequired(false)
                Task { _ = try? await SecurityOverviewStore.shared.fetch() }
                onChanged()
            }
        )
        .alert(secL("Leave without changing your password?"), isPresented: $confirmLeave) {
            Button(secL("Stay"), role: .cancel) {}
            Button(secL("Leave"), role: .destructive) { onLeave() }
        } message: {
            Text(secL("Your account stays at risk until you do."))
        }
    }
}

// MARK: - "Review your recovery options" (route .secRecoveryReview)

@MainActor
struct RecoveryReviewView: View {
    let onDone: () -> Void
    /// The same destinations as the §4.2 checklist actions.
    let onOpen: (SecChecklistID) -> Void
    var preview: SecurityOverviewResponse? = nil

    @ObservedObject private var store = SecurityOverviewStore.shared
    @State private var didAppear = false

    private var overview: SecurityOverviewResponse? { preview ?? store.overview }

    private struct Row {
        let id: SecChecklistID
        let icon: String
        let title: String
    }

    private var rows: [Row] {
        [
            Row(id: .recoveryEmail, icon: "envelope.badge.shield.half.filled", title: secL("Recovery email")),
            Row(id: .recoveryPhone, icon: "phone", title: secL("Phone")),
            Row(id: .recoveryCodes, icon: "key.fill", title: secL("Recovery codes")),
            Row(id: .passkey, icon: "key.viewfinder", title: secL("Passkeys")),
        ]
    }

    var body: some View {
        AccountScreen(
            onBack: onDone,
            illustration: "ill_sec_hub_secure",
            illustrationWidth: 150,
            title: secL("Review your recovery options"),
            subtitle: secL("Make sure you can always get back in, even without this device.")
        ) {
            VStack(spacing: 0) {
                ForEach(rows, id: \.id) { row in
                    let item = overview?.checklist.first { $0.id == row.id }
                    if item?.state != .notApplicable {
                        SecurityRowView(
                            icon: row.icon,
                            title: row.title,
                            chip: chip(item),
                            chipTone: item?.isDone == true ? .success : .warning,
                            action: { onOpen(row.id) }
                        )
                        if row.id != rows.last?.id { SecurityDivider() }
                    }
                }
            }
        } footer: {
            GradientButton(title: secL("Done"), action: onDone)
                .accessibilityIdentifier("sec-recovery-review-done")
        }
        .task {
            guard preview == nil else { return }
            _ = try? await store.fetch()
        }
        .onAppear {
            guard preview == nil else { return }
            if didAppear { Task { _ = try? await store.fetch() } } else { didAppear = true }
        }
        .accessibilityIdentifier("sec-recovery-review")
    }

    private func chip(_ item: SecChecklistItem?) -> String? {
        guard let item else { return nil }
        return item.isDone ? secL("Done") : secL("Not set")
    }
}
