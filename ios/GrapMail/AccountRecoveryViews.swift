import OSLog
import SwiftUI
import UIKit

/// The error code a route refused with, when it carried one.
func apiCode(_ error: Error) -> String? {
    if case let ApiError.http(_, code, _) = error { return code }
    return nil
}

func apiMessage(_ error: Error, fallback: String) -> String {
    if let api = error as? ApiError { return api.userMessage }
    let failure = error as NSError
    Logger(subsystem: "com.grapsee.grapmail", category: "UI")
        .error("Operation failed: \(failure.domain, privacy: .private) (\(failure.code))")
    return fallback
}

/// Forgot password — one identifier field, the arrowed CTA, the support note.
/// The added "Find your GrapMail address" link is the way into the lookup
/// screen: the person who cannot remember which address they own is exactly the
/// person standing here.
struct ForgotPasswordView: View {
    @ObservedObject var model: RecoveryModel
    let onBack: () -> Void
    let onCodeSent: () -> Void
    let onNeedsManualReview: () -> Void
    let onFindAddress: () -> Void
    let onSupport: () -> Void
    /// The trusted-device path: this install holds an enrolled Ed25519 key the
    /// account can verify, so a signature can stand in for the texted code.
    /// Only offered when a key exists (DeviceKeyManager.hasKey) — offering it
    /// from a device that never enrolled would route into a refusal.
    let onTrustedDevice: () -> Void

    @State private var identifier = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_account_forgot",
            eyebrow: String(localized: "Forgot password", comment: "Small label above the screen title (forgot password screen)"),
            title: String(localized: "Reset your password", comment: "Screen title (forgot password screen)"),
            subtitle: String(localized: "Enter your GrapMail address and we'll email a reset link to the recovery email on your account.", comment: "Screen description under the title (forgot password screen)"),
            content: {
                AppTextField(
                    value: $identifier,
                    hint: String(localized: "GrapMail address", comment: "Text field placeholder (forgot password screen)"),
                    keyboardType: .emailAddress,
                    leadingIcon: "envelope",
                    contentType: .username
                )
                .onAppear { identifier = model.identifier }
                Spacer().frame(height: 22)
            },
            footer: {
                GradientButton(
                    title: String(localized: "Continue", comment: "Button title (forgot password screen)"),
                    enabled: !identifier.isEmpty,
                    busy: busy,
                    trailing: AnyView(
                        Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                            .gmFont(15, .semibold, maxSize: 24)
                            .foregroundColor(.white)
                    )
                ) { submit() }
                if let error {
                    Text(error).gmFont(.caption).foregroundColor(GM.error).padding(.top, 10)
                }
                HStack {
                    Spacer()
                    AccountLink(title: String(localized: "Find your GrapMail address", comment: "Link-style button (forgot password screen)"), action: onFindAddress)
                    Spacer()
                }
                .padding(.top, 8)
                // The device that signed in has a key the account enrolled:
                // offer the path that needs no code at all. The typed address
                // rides along in the model, so the verification screen does
                // not ask for it a second time.
                if DeviceKeyManager.hasKey() {
                    HStack {
                        Spacer()
                        AccountLink(title: String(localized: "Use a trusted device instead", comment: "Link-style button (forgot password screen)"), action: {
                            model.identifier = identifier.trimmingCharacters(in: .whitespaces)
                            onTrustedDevice()
                        })
                        Spacer()
                    }
                    .padding(.top, 4)
                }
                FooterNote(
                    text: String(localized: "Need help? Contact our support team.", comment: "Footer note (forgot password screen)"),
                    linkText: String(localized: "support team", comment: "Tappable words in the footer note; must appear verbatim in the note's translation (forgot password screen)"),
                    onLink: onSupport
                )
                .padding(.top, 14)
                Spacer().frame(height: 4)
            }
        )
    }

    private func submit() {
        guard !identifier.isEmpty else { return }
        busy = true; error = nil
        Task {
            defer { busy = false }
            do {
                model.identifier = identifier.trimmingCharacters(in: .whitespaces)
                let resp = try await ApiClient.forgotPassword(identifier: model.identifier)
                model.challengeId = resp.challengeId
                model.expiresAt = resp.expiresAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
                model.maskedPhone = resp.maskedPhone
                model.resetChannel = resp.channel
                model.code = ""
                // No challenge means no code went out. GrapMail recovers by
                // email, so this is normally the reset link going to the
                // verified recovery address (channel "email"); the pending
                // screen says which.
                if resp.challengeId != nil { onCodeSent() } else { onNeedsManualReview() }
            } catch {
                self.error = apiMessage(error, fallback: String(localized: "We could not start the reset. Try again.", comment: "Error message shown when the server gives no reason (forgot password screen)"))
            }
        }
    }
}

/// Verify recovery code — the reset arc's code screen.
struct RecoveryCodeView: View {
    @ObservedObject var model: RecoveryModel
    let onBack: () -> Void
    let onContinue: () -> Void
    let onBackToSignIn: () -> Void

    @State private var code = ""
    @State private var error: String?
    @State private var notice: String?
    @State private var resending = false

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_account_recovery_code",
            eyebrow: String(localized: "Verify recovery code", comment: "Small label above the screen title (recovery code screen)"),
            title: String(localized: "Enter the 6-digit code", comment: "Screen title (recovery code screen)"),
            subtitle: String(localized: "We've sent a 6-digit verification code to your verified phone number. Please enter the code to continue.", comment: "Screen description under the title (recovery code screen)"),
            content: {
                CodeCells(length: 6, value: $code)
                    // Round 8 — B: the recovery code is offered above the keyboard.
                    .textContentType(.oneTimeCode)
                HStack {
                    Spacer()
                    CodeExpiry(expiresAt: model.expiresAt)
                    Spacer()
                }
                .padding(.top, 14)
            },
            footer: {
                GradientButton(
                    title: String(localized: "Continue", comment: "Button title (recovery code screen)"),
                    enabled: code.count == 6,
                    trailing: AnyView(
                        Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                            .gmFont(15, .semibold, maxSize: 24)
                            .foregroundColor(.white)
                    )
                ) {
                    model.code = code
                    onContinue()
                }
                OrDivider().padding(.top, 14)
                HStack {
                    Spacer()
                    AccountLink(title: resending ? String(localized: "Sending…", comment: "Link-style button (recovery code screen)") : String(localized: "Resend code", comment: "Link-style button (recovery code screen)")) { resend() }
                    Spacer()
                }
                .padding(.top, 10)
                if let notice {
                    Text(notice).gmFont(12).foregroundColor(GM.success)
                        .frame(maxWidth: .infinity)
                }
                if let error {
                    Text(error).gmFont(12).foregroundColor(GM.error)
                        .frame(maxWidth: .infinity)
                }
                FooterNote(text: String(localized: "Didn't receive the code? Check your spam folder.", comment: "Footer note (recovery code screen)"), divider: false)
                    .padding(.top, 12)
                Spacer().frame(height: 4)
            }
        )
    }

    private func resend() {
        guard !model.identifier.isEmpty else { return }
        resending = true; error = nil; notice = nil
        Task {
            defer { resending = false }
            do {
                let resp = try await ApiClient.forgotPassword(identifier: model.identifier)
                if let cid = resp.challengeId {
                    code = ""
                    model.challengeId = cid
                    model.expiresAt = resp.expiresAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
                    notice = String(localized: "A new code is on its way.", comment: "Notice after a verification code was resent")
                } else {
                    error = String(localized: "We couldn't send another code. Contact support and we'll help.", comment: "Recovery code resend error")
                }
            } catch {
                self.error = apiMessage(error, fallback: String(localized: "Could not resend the code. Try again.", comment: "Error message shown when the server gives no reason (recovery code screen)"))
            }
        }
    }
}

private let commonPasswords: Set<String> = [
    "password", "password1", "password123", "12345678", "123456789", "qwerty123",
    "letmein1", "iloveyou", "welcome1", "admin123", "grapmail", "grapmail1",
]

/// Set a new password — two fields, the five-segment meter and the four rules.
struct NewPasswordView: View {
    @ObservedObject var model: RecoveryModel
    let onBack: () -> Void
    let onBackToCode: () -> Void
    let onReset: (String?) -> Void

    @State private var password = ""
    @State private var confirm = ""
    @State private var error: String?
    /// Set when the server refused the code itself, so the "Enter the code
    /// again" link does not depend on the wording of a translated message.
    @State private var codeRejected = false
    @State private var busy = false

    private var hasLength: Bool { password.count >= 8 }
    // ASCII classes, matching `passwordProblem` on the server exactly. Swift's
    // isLetter/isNumber are Unicode-aware, and the old gate checked a digit and a
    // symbol but no letter, so `1234567!` cleared every rule here and was then
    // refused by the reset route for having no letter.
    private var hasLetter: Bool { password.contains { ($0 >= "A" && $0 <= "Z") || ($0 >= "a" && $0 <= "z") } }
    private var hasDigit: Bool { password.contains { $0 >= "0" && $0 <= "9" } }
    // A symbol is any character the server's `[^A-Za-z0-9]` would match — space
    // included, exactly as the route counts it.
    private var hasSpecial: Bool { password.contains { !(($0 >= "A" && $0 <= "Z") || ($0 >= "a" && $0 <= "z") || ($0 >= "0" && $0 <= "9")) } }
    private var isFresh: Bool { password.count >= 8 && !commonPasswords.contains(password.lowercased()) }
    private var matches: Bool { !confirm.isEmpty && confirm == password }
    private var ready: Bool { hasLength && hasLetter && hasDigit && hasSpecial && isFresh && matches }
    private var filled: Int { [hasLength, hasLetter, hasDigit, hasSpecial].filter { $0 }.count }

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_account_new_password",
            eyebrow: String(localized: "Create new password", comment: "Small label above the screen title (new password screen)"),
            title: String(localized: "Set a new password", comment: "Screen title (new password screen)"),
            subtitle: String(localized: "Your new password must be strong and secure. Make sure it's something you haven't used before.", comment: "Screen description under the title (new password screen)"),
            content: {
                // Round 8 — B: the account the new password belongs to, so the
                // password manager updates that entry instead of a nameless one.
                if let username = (model.resetAddress ?? model.identifier).nilIfBlank() {
                    CredentialUsernameField(username: username)
                }
                AppTextField(
                    value: $password,
                    hint: String(localized: "New password", comment: "Text field placeholder (new password screen)"),
                    isPassword: true,
                    leadingIcon: "lock",
                    contentType: .newPassword
                )
                HStack(spacing: 6) {
                    ForEach(0..<5, id: \.self) { i in
                        RoundedRectangle(cornerRadius: 3, style: .continuous)
                            .fill(i < filled ? GM.primarySolid : GM.stepInactive)
                            .frame(height: 5)
                    }
                }
                .padding(.top, 12)
                Text("Password strength")
                    .gmFont(12)
                    .foregroundColor(GM.textTertiary)
                    .padding(.top, 8)
                AppTextField(
                    value: $confirm,
                    hint: String(localized: "Confirm new password", comment: "Text field placeholder (new password screen)"),
                    isPassword: true,
                    leadingIcon: "lock",
                    contentType: .newPassword
                )
                .padding(.top, 18)
                VStack(alignment: .leading, spacing: 0) {
                    RuleLine(label: String(localized: "At least 8 characters", comment: "Password requirement (new password screen)"), met: hasLength)
                    RuleLine(label: String(localized: "Include a letter", comment: "Password requirement (new password screen)"), met: hasLetter)
                    RuleLine(label: String(localized: "Include a number", comment: "Password requirement (new password screen)"), met: hasDigit)
                    RuleLine(label: String(localized: "Include a special character", comment: "Password requirement (new password screen)"), met: hasSpecial)
                    RuleLine(label: String(localized: "Not commonly used", comment: "Password requirement (new password screen)"), met: isFresh)
                }
                .padding(.top, 16)
                if !confirm.isEmpty && !matches {
                    Text("Both passwords must match.")
                        .gmFont(.caption)
                        .foregroundColor(GM.error)
                        .padding(.top, 8)
                }
            },
            footer: {
                GradientButton(
                    title: String(localized: "Reset Password", comment: "Button title (new password screen)"),
                    enabled: ready,
                    busy: busy,
                    trailing: AnyView(
                        Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                            .gmFont(15, .semibold, maxSize: 24)
                            .foregroundColor(.white)
                    )
                ) { submit() }
                if let error {
                    Text(error).gmFont(.caption).foregroundColor(GM.error).padding(.top, 10)
                    if codeRejected || error.lowercased().contains("code") {
                        HStack {
                            Spacer()
                            AccountLink(title: String(localized: "Enter the code again", comment: "Link-style button (new password screen)"), action: onBackToCode)
                            Spacer()
                        }
                    }
                }
                FooterNote(text: String(localized: "Your password will be encrypted and kept secure.", comment: "Footer note (new password screen)"), divider: false)
                    .padding(.top, 16)
                Spacer().frame(height: 4)
            }
        )
    }

    private func submit() {
        guard let challenge = model.challengeId else {
            error = String(localized: "That reset link expired. Start again.", comment: "Password reset error")
            return
        }
        busy = true; error = nil; codeRejected = false
        Task {
            defer { busy = false }
            do {
                let resp = try await ApiClient.resetPassword(challengeId: challenge, code: model.code, password: password)
                onReset(resp.address)
            } catch {
                switch apiCode(error) {
                // The server names the exact rule that failed. Showing the real
                // message beats a hardcoded "at least 8 characters", which was
                // wrong for every other rule the policy now enforces.
                case "WEAK_PASSWORD": self.error = apiMessage(error, fallback: String(localized: "Use at least 8 characters with a letter, a number and a symbol.", comment: "Error message shown when the server gives no reason (new password screen)"))
                case "VERIFY_FAILED":
                    codeRejected = true
                    self.error = String(localized: "That code is not correct, or it has expired.", comment: "Password reset error")
                default: self.error = apiMessage(error, fallback: String(localized: "We could not reset the password. Try again.", comment: "Error message shown when the server gives no reason (new password screen)"))
                }
            }
        }
    }
}

/// Password reset successful — chip, headline, the way back to sign-in.
struct ResetDoneView: View {
    let onBackToSignIn: () -> Void

    var body: some View {
        AccountScreen(
            illustration: "ill_account_reset_ok",
            content: {
                StatusChip(text: String(localized: "Password Reset Successful", comment: "Status chip (reset done screen)"), tone: .success)
                Text("Your password\nhas been reset")
                    .gmFont(28, .bold, maxSize: 44)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)
                Text("Your GrapMail account is now secure with your new password. You can now sign in and continue using all the features.")
                    .gmFont(15)
                    .lineSpacing(5)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
                Spacer().frame(height: 8)
            },
            footer: {
                GradientButton(
                    title: String(localized: "Back to Sign In", comment: "Button title (reset done screen)"),
                    trailing: AnyView(
                        Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                            .gmFont(15, .semibold, maxSize: 24)
                            .foregroundColor(.white)
                    )
                ) { onBackToSignIn() }
                FooterNote(text: String(localized: "For your security, we recommend keeping your password private and using two-step verification.", comment: "Footer note (reset done screen)"))
                    .padding(.top, 18)
                Spacer().frame(height: 4)
            }
        )
    }
}

/// Account recovery pending — the honest outcome when no code could be sent.
struct RecoveryPendingView: View {
    @ObservedObject var model: RecoveryModel
    let onBackToSignIn: () -> Void
    let onSupport: () -> Void

    @State private var notice: String?
    @State private var busy = false

    var body: some View {
        AccountScreen(
            illustration: "ill_account_pending",
            content: {
                let emailed = model.resetChannel == "email"
                StatusChip(text: emailed ? String(localized: "Reset link sent", comment: "Status chip (recovery pending screen)") : String(localized: "Account Recovery Pending", comment: "Status chip (recovery pending screen)"), tone: emailed ? .success : .warning)
                (emailed ? Text("Check your\nrecovery email") : Text("We're still working\non your recovery"))
                    .gmFont(28, .bold, maxSize: 44)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)
                (emailed
                     ? Text("We sent a password reset link to the recovery email on your account. Open it on any device to choose a new password.")
                     : Text("There might be a short delay while we verify your information. You'll get an update soon at your verified phone number or email."))
                    .gmFont(15)
                    .lineSpacing(5)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
                InfoPanel(
                    title: emailed ? String(localized: "Didn't get it?", comment: "Info panel title (recovery pending screen)") : String(localized: "What's happening?", comment: "Info panel title (recovery pending screen)"),
                    body: emailed
                        ? String(localized: "The link works for 1 hour. Check your spam folder, or tap Send again for a new one.", comment: "Info panel text (recovery pending screen)")
                        : String(localized: "We're verifying your recovery request. This usually takes a few minutes, but can take up to 24 hours in some cases.", comment: "Info panel text (recovery pending screen)"),
                    icon: {
                        Image(systemName: "clock")
                            .gmFont(15, .medium)
                            .foregroundColor(GM.primarySolid)
                    }
                )
                .padding(.top, 18)
                Spacer().frame(height: 8)
            },
            footer: {
                GradientButton(
                    title: model.resetChannel == "email" ? String(localized: "Send again", comment: "Button title (recovery pending screen)") : String(localized: "Try Again", comment: "Button title (recovery pending screen)"),
                    busy: busy,
                    trailing: AnyView(
                        Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                            .gmFont(15, .semibold, maxSize: 24)
                            .foregroundColor(.white)
                    )
                ) { retry() }
                if let notice {
                    Text(notice).gmFont(.caption).foregroundColor(GM.textSecondary)
                        .padding(.top, 10)
                }
                HStack {
                    Spacer()
                    AccountLink(title: String(localized: "Back to Sign In", comment: "Link-style button (recovery pending screen)"), action: onBackToSignIn)
                    Spacer()
                }
                .padding(.top, 8)
                OrDivider().padding(.top, 14)
                FooterNote(
                    text: String(localized: "Need help? Contact our support team.", comment: "Footer note (recovery pending screen)"),
                    linkText: String(localized: "support team", comment: "Tappable words in the footer note; must appear verbatim in the note's translation (recovery pending screen)"),
                    onLink: onSupport,
                    divider: false
                )
                .padding(.top, 14)
                Spacer().frame(height: 4)
            }
        )
    }

    private func retry() {
        guard !model.identifier.isEmpty else { return }
        busy = true; notice = nil
        Task {
            defer { busy = false }
            do {
                let resp = try await ApiClient.forgotPassword(identifier: model.identifier)
                model.resetChannel = resp.channel
                if resp.challengeId != nil {
                    notice = String(localized: "A code is on its way. Check your phone.", comment: "Account recovery status notice")
                } else if resp.channel == "email" {
                    notice = String(localized: "A new reset link is on its way. Check your recovery email.", comment: "Account recovery status notice")
                } else {
                    notice = String(localized: "Still in review. We'll message you as soon as it clears.", comment: "Account recovery status notice")
                }
            } catch {
                notice = apiMessage(error, fallback: String(localized: "We couldn't check again. Try in a minute.", comment: "Error message shown when the server gives no reason (recovery pending screen)"))
            }
        }
    }
}

/// Find your GrapMail address — one number in, a masked address back.
struct FindAddressView: View {
    @ObservedObject var model: RecoveryModel
    let onBack: () -> Void
    let onFound: () -> Void
    let onSupport: () -> Void

    @State private var phone = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_account_find_address",
            eyebrow: String(localized: "Recover your GrapMail address", comment: "Small label above the screen title (find address screen)"),
            title: String(localized: "Find your GrapMail address", comment: "Screen title (find address screen)"),
            subtitle: String(localized: "Enter the phone number linked to your GrapMail account. We'll check our records and show you the email address associated with it.", comment: "Screen description under the title (find address screen)"),
            content: {
                AppTextField(
                    value: $phone,
                    hint: String(localized: "Enter your verified phone number", comment: "Text field placeholder (find address screen)"),
                    keyboardType: .phonePad,
                    leadingIcon: "phone"
                )
                Spacer().frame(height: 8)
            },
            footer: {
                GradientButton(
                    title: String(localized: "Find My Email", comment: "Button title (find address screen)"),
                    enabled: !phone.isEmpty,
                    busy: busy,
                    trailing: AnyView(
                        Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                            .gmFont(15, .semibold, maxSize: 24)
                            .foregroundColor(.white)
                    )
                ) { submit() }
                if let error {
                    Text(error).gmFont(.caption).foregroundColor(GM.error).padding(.top, 10)
                }
                OrDivider().padding(.top, 16)
                FooterNote(
                    text: String(localized: "Need help? Contact our support team.", comment: "Footer note (find address screen)"),
                    linkText: String(localized: "support team", comment: "Tappable words in the footer note; must appear verbatim in the note's translation (find address screen)"),
                    onLink: onSupport,
                    divider: false
                )
                .padding(.top, 16)
                Spacer().frame(height: 4)
            }
        )
    }

    private func submit() {
        guard !phone.isEmpty else { return }
        busy = true; error = nil
        Task {
            defer { busy = false }
            do {
                let resp = try await ApiClient.findAddress(phone: phone.trimmingCharacters(in: .whitespaces))
                guard let masked = resp.maskedAddress, !masked.isEmpty else {
                    error = String(localized: "We couldn't find a GrapMail account with that number.", comment: "Find-address error")
                    return
                }
                model.foundAddress = masked
                model.foundAddressFull = resp.address
                onFound()
            } catch {
                switch apiCode(error) {
                case "NOT_FOUND": self.error = String(localized: "We couldn't find a GrapMail account with that number.", comment: "Find-address error")
                case "RATE_LIMITED": self.error = String(localized: "Too many lookups. Try again in a little while.", comment: "Find-address error")
                default: self.error = apiMessage(error, fallback: String(localized: "We couldn't check that number. Try again.", comment: "Error message shown when the server gives no reason (find address screen)"))
                }
            }
        }
    }
}

/// Address found — the mask on screen, the real address on the clipboard,
/// which is exactly what the note promises it can be used for.
struct AddressFoundView: View {
    @ObservedObject var model: RecoveryModel
    let onBackToSignIn: (String?) -> Void

    @State private var copied = false

    var body: some View {
        let shown = model.foundAddress ?? ""
        let copyable = model.foundAddressFull ?? shown
        AccountScreen(
            illustration: "ill_account_address_found",
            content: {
                StatusChip(text: String(localized: "Email Found", comment: "Status chip (address found screen)"), tone: .success)
                Text("Your GrapMail address\nhas been found!")
                    .gmFont(28, .bold, maxSize: 44)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)
                Text("Here is the email address associated with your account. You can copy it or use it to sign in.")
                    .gmFont(15)
                    .lineSpacing(5)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
                InfoCard {
                    GMAdaptiveStack(spacing: 12) {
                        IconTile(systemName: "envelope")
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Your GrapMail address")
                                .gmFont(12)
                                .foregroundColor(GM.textSecondary)
                            Text(shown)
                                .gmFont(15, .bold)
                                .foregroundColor(GM.textPrimary)
                        }
                        Spacer(minLength: 8)
                        CopyButton(title: copied ? String(localized: "Copied", comment: "Copy button title (address found screen)") : String(localized: "Copy", comment: "Copy button title (address found screen)")) {
                            UIPasteboard.general.string = copyable
                            copied = true
                        }
                    }
                }
                .padding(.top, 18)
                Spacer().frame(height: 8)
            },
            footer: {
                GradientButton(
                    title: String(localized: "Back to Sign In", comment: "Button title (address found screen)"),
                    trailing: AnyView(
                        Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                            .gmFont(15, .semibold, maxSize: 24)
                            .foregroundColor(.white)
                    )
                ) { onBackToSignIn(model.foundAddressFull) }
                FooterNote(
                    text: String(localized: "For your security, this address is partially hidden. You can copy it to use for sign in.", comment: "Footer note (address found screen)"),
                    divider: false
                )
                .padding(.top, 16)
                Spacer().frame(height: 4)
            }
        )
    }
}

/// Phone verification, both steps: the artwork's two states share the artwork,
/// the ribbon and the footer, and only the middle changes.
struct PhoneVerifyView: View {
    @Environment(\.dynamicTypeSize) private var textSize
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @ObservedObject var model: RecoveryModel
    let onBack: () -> Void
    let onVerified: (String) -> Void

    @State private var step = 1
    @State private var countryIndex = 0
    @State private var national = ""
    @State private var code = ""
    @State private var error: String?
    @State private var busy = false
    @State private var resending = false

    private var country: (flag: String, dial: String, name: String) { PhoneFormat.countries[countryIndex] }

    var body: some View {
        AccountScreen(
            onBack: { if step == 2 { step = 1 } else { onBack() } },
            illustration: textSize.isAccessibilitySize || verticalSizeClass == .compact ? nil : "ill_account_phone",
            eyebrow: String(localized: "Phone verification", comment: "Small label above the screen title (phone verify screen)"),
            title: step == 1 ? String(localized: "Verify your phone number", comment: "Screen title (phone verify screen)") : String(localized: "Enter the 6-digit code", comment: "Screen title (phone verify screen)"),
            subtitle: step == 1
                ? String(localized: "We’ll send you a 6-digit code by SMS.", comment: "Screen description under the title (phone verify screen)")
                : String(localized: "SMS sent to \(PhoneFormat.pretty(model.phone)). The code expires in five minutes.", comment: "Phone verification description; the argument is a phone number"),
            content: {
                TwoStepRibbon(current: step, labels: [
                    String(localized: "Phone number", comment: "Phone verification step 1 label"),
                    String(localized: "SMS code", comment: "Phone verification step 2 label"),
                ])
                Spacer().frame(height: 20)
                if step == 1 {
                    phoneField
                    Spacer().frame(height: 8)
                } else {
                    TextField("6-digit SMS code", text: $code)
                        .font(.body.monospacedDigit())
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                        .accessibilityLabel("Verification code")
                        .padding(14)
                        .frame(maxWidth: .infinity, minHeight: 54, alignment: .leading)
                        .background(GM.inputFill, in: RoundedRectangle(cornerRadius: GMShape.input))
                        .overlay {
                            RoundedRectangle(cornerRadius: GMShape.input).strokeBorder(GM.border)
                        }
                        .onChange(of: code) { entered in
                            let digits = String(entered.filter { $0.isASCII && $0.isNumber }.prefix(6))
                            if digits != code { code = digits }
                            error = nil
                        }
                    VStack(alignment: .leading, spacing: 8) {
                        CodeExpiry(expiresAt: model.phoneExpiresAt)
                        AccountLink(title: resending ? String(localized: "Sending…", comment: "Link-style button (phone verify screen)") : String(localized: "Resend code", comment: "Link-style button (phone verify screen)")) { sendCode(resend: true) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 14)
                }
            },
            footer: {
                if step == 1 {
                    GradientButton(
                        title: String(localized: "Send Verification Code", comment: "Button title (phone verify screen)"),
                        enabled: !national.isEmpty,
                        busy: busy,
                        trailing: AnyView(
                            Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                                .gmFont(15, .semibold, maxSize: 24)
                                .foregroundColor(.white)
                        )
                    ) { sendCode(resend: false) }
                    if let error {
                        Text(error).gmFont(.caption).foregroundColor(GM.error).padding(.top, 10)
                    }
                    FooterNote(
                        text: String(localized: "Your phone number is only used for security purposes and won't be shared with anyone.", comment: "Footer note (phone verify screen)"),
                        divider: false
                    )
                    .padding(.top, 16)
                } else {
                    GradientButton(
                        title: String(localized: "Verify & Continue", comment: "Button title (phone verify screen)"),
                        enabled: code.count == 6,
                        busy: busy,
                        trailing: AnyView(
                            Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                                .gmFont(15, .semibold, maxSize: 24)
                                .foregroundColor(.white)
                        )
                    ) { verify() }
                    if let error {
                        Text(error).gmFont(.caption).foregroundColor(GM.error).padding(.top, 10)
                    }
                    FooterNote(
                        text: String(localized: "If the text message hasn’t arrived, you can request another code after one minute.", comment: "Footer note (phone verify screen)"),
                        divider: false
                    )
                    .padding(.top, 14)
                }
                Spacer().frame(height: 6)
            }
        )
    }

    private var phoneField: some View {
        // Country menu beside the number; stacked at the accessibility sizes so
        // the number field keeps a usable width on an iPhone SE.
        GMAdaptiveStack(spacing: 10) {
            Menu {
                ForEach(0..<PhoneFormat.countries.count, id: \.self) { index in
                    let c = PhoneFormat.countries[index]
                    Button("\(c.flag) \(c.name) \(c.dial)") { countryIndex = index }
                }
            } label: {
                HStack(spacing: 6) {
                    Text(country.flag).gmFont(17)
                    Text(country.dial)
                        .gmFont(15)
                        .foregroundColor(GM.textPrimary)
                    Image(systemName: "chevron.down")
                        .gmFont(11, .semibold, maxSize: 24)
                        .foregroundColor(GM.textTertiary)
                }
            }
            if !textSize.isAccessibilitySize {
                Rectangle().fill(GM.border).frame(width: 1, height: 28)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("Phone number")
                    .gmFont(12)
                    .foregroundColor(GM.textTertiary)
                TextField("01XXXXXXXXX", text: $national)
                    .keyboardType(.phonePad)
                    .gmFont(15)
                    .foregroundColor(GM.textPrimary)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, textSize.isAccessibilitySize ? 10 : 0)
        .frame(minHeight: 56)
        .background(RoundedRectangle(cornerRadius: GMShape.input, style: .continuous).fill(GM.inputFill))
        .overlay(
            RoundedRectangle(cornerRadius: GMShape.input, style: .continuous).strokeBorder(GM.border)
        )
    }

    private func sendCode(resend: Bool) {
        let target = resend
            ? model.phone
            : (PhoneFormat.e164(dial: country.dial, national: national) ?? "")
        guard !target.isEmpty else {
            error = String(localized: "Enter a valid phone number.", comment: "Phone verification error")
            return
        }
        if resend { resending = true } else { busy = true }
        error = nil
        Task {
            defer { busy = false; resending = false }
            do {
                let resp = try await ApiClient.startPhoneVerification(phone: target)
                guard let cid = resp.challengeId else {
                    error = resp.message ?? String(localized: "We couldn't send a code to that number.", comment: "Phone verification error")
                    return
                }
                code = ""
                model.phone = target
                model.phoneChallengeId = cid
                model.phoneExpiresAt = resp.expiresAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
                step = 2
            } catch {
                switch apiCode(error) {
                case "PHONE_IN_USE": self.error = String(localized: "That number is already linked to another account.", comment: "Phone verification error")
                default: self.error = apiMessage(error, fallback: String(localized: "We couldn't send your code. Try again.", comment: "Error message shown when the server gives no reason (phone verify screen)"))
                }
            }
        }
    }

    private func verify() {
        guard let challenge = model.phoneChallengeId else { return }
        guard let session = SessionStore.token else {
            error = String(localized: "Sign in first so the number can be saved to your account.", comment: "Phone verification error")
            return
        }
        busy = true; error = nil
        Task {
            defer { busy = false }
            do {
                let verified = try await ApiClient.verifyPhone(challengeId: challenge, code: code)
                guard let proof = verified.phoneToken else {
                    error = String(localized: "That code is not correct.", comment: "Verification code error")
                    return
                }
                _ = try await ApiClient.linkPhone(sessionToken: session, phoneToken: proof)
                let phone = verified.phone ?? model.phone
                model.verifiedPhone = phone
                onVerified(phone)
            } catch {
                switch apiCode(error) {
                case "PHONE_IN_USE": self.error = String(localized: "That number is already linked to another account.", comment: "Phone verification error")
                case "VERIFY_FAILED", "BAD_CODE": self.error = String(localized: "That code is not correct.", comment: "Verification code error")
                default: self.error = apiMessage(error, fallback: String(localized: "We couldn't verify that code. Try again.", comment: "Error message shown when the server gives no reason (phone verify screen)"))
                }
            }
        }
    }
}

/// Phone verified — the number in a card with its green badge.
struct PhoneVerifiedView: View {
    @ObservedObject var model: RecoveryModel
    let onGoToSecurity: () -> Void
    let onBackHome: () -> Void

    var body: some View {
        let phone = model.verifiedPhone ?? model.phone
        AccountScreen(
            illustration: "ill_account_phone_ok",
            content: {
                StatusChip(text: String(localized: "Phone Verified", comment: "Status chip (phone verified screen)"), tone: .success)
                Text("Your phone number\nhas been verified!")
                    .gmFont(28, .bold, maxSize: 44)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)
                Text("Your phone number \(PhoneFormat.pretty(phone)) is now linked to your GrapMail account. You can use it for account recovery and two-step verification.")
                    .gmFont(15)
                    .lineSpacing(5)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
                InfoCard {
                    GMAdaptiveStack(spacing: 12) {
                        IconTile(systemName: "phone")
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Verified Phone Number")
                                .gmFont(12)
                                .foregroundColor(GM.textSecondary)
                            Text(PhoneFormat.pretty(phone))
                                .gmFont(15, .semibold)
                                .foregroundColor(GM.textPrimary)
                        }
                        Spacer(minLength: 8)
                        HStack(spacing: 5) {
                            Image(systemName: "checkmark.circle.fill")
                                .gmFont(12, .semibold)
                            Text("Verified").gmFont(12, .semibold)
                        }
                        .foregroundColor(GM.successInk)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Capsule().fill(GM.successChipBg))
                    }
                }
                .padding(.top, 18)
                Spacer().frame(height: 8)
            },
            footer: {
                GradientButton(
                    title: String(localized: "Go to Account Security", comment: "Button title (phone verified screen)"),
                    trailing: AnyView(
                        Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                            .gmFont(15, .semibold, maxSize: 24)
                            .foregroundColor(.white)
                    )
                ) { onGoToSecurity() }
                HStack {
                    Spacer()
                    AccountLink(title: String(localized: "Back to Home", comment: "Link-style button (phone verified screen)"), action: onBackHome)
                    Spacer()
                }
                .padding(.top, 8)
                FooterNote(
                    text: String(localized: "Your phone number is securely stored and only used for account recovery and two-step verification.", comment: "Footer note (phone verified screen)"),
                    divider: false
                )
                .padding(.top, 14)
                Spacer().frame(height: 4)
            }
        )
    }
}
