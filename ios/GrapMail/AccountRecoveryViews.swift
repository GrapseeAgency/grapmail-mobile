import SwiftUI
import UIKit

/// The error code a route refused with, when it carried one.
func apiCode(_ error: Error) -> String? {
    if case let ApiError.http(_, code, _) = error { return code }
    return nil
}

func apiMessage(_ error: Error, fallback: String) -> String {
    let text = error.localizedDescription
    return text.isEmpty ? fallback : text
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

    @State private var identifier = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_account_forgot",
            eyebrow: "Forgot password",
            title: "Reset your password",
            subtitle: "Enter your GrapMail address or phone number and we'll send you instructions to reset your password securely.",
            content: {
                AppTextField(
                    value: $identifier,
                    hint: "GrapMail address or phone number",
                    keyboardType: .emailAddress,
                    leadingIcon: "envelope"
                )
                .onAppear { identifier = model.identifier }
                Spacer().frame(height: 22)
            },
            footer: {
                GradientButton(
                    title: "Continue",
                    enabled: !identifier.isEmpty,
                    busy: busy,
                    trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                    )
                ) { submit() }
                if let error {
                    Text(error).font(GMFont.caption).foregroundColor(GM.error).padding(.top, 10)
                }
                HStack {
                    Spacer()
                    AccountLink(title: "Find your GrapMail address", action: onFindAddress)
                    Spacer()
                }
                .padding(.top, 8)
                FooterNote(
                    text: "Need help? Contact our support team.",
                    linkText: "support team",
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
                model.code = ""
                // No challenge means no code went out: the account has no
                // number to text. That is the manual-review path, not an error.
                if resp.challengeId != nil { onCodeSent() } else { onNeedsManualReview() }
            } catch {
                self.error = apiMessage(error, fallback: "We could not start the reset. Try again.")
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
            eyebrow: "Verify recovery code",
            title: "Enter the 6-digit code",
            subtitle: "We've sent a 6-digit verification code to your verified phone number. Please enter the code to continue.",
            content: {
                CodeCells(length: 6, value: $code)
                HStack {
                    Spacer()
                    CodeExpiry(expiresAt: model.expiresAt)
                    Spacer()
                }
                .padding(.top, 14)
            },
            footer: {
                GradientButton(
                    title: "Continue",
                    enabled: code.count == 6,
                    trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                    )
                ) {
                    model.code = code
                    onContinue()
                }
                OrDivider().padding(.top, 14)
                HStack {
                    Spacer()
                    AccountLink(title: resending ? "Sending…" : "Resend code") { resend() }
                    Spacer()
                }
                .padding(.top, 10)
                if let notice {
                    Text(notice).font(.system(size: 12)).foregroundColor(GM.success)
                        .frame(maxWidth: .infinity)
                }
                if let error {
                    Text(error).font(.system(size: 12)).foregroundColor(GM.error)
                        .frame(maxWidth: .infinity)
                }
                FooterNote(text: "Didn't receive the code? Check your spam folder.", divider: false)
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
                    notice = "A new code is on its way."
                } else {
                    error = "We couldn't send another code. Contact support and we'll help."
                }
            } catch {
                self.error = apiMessage(error, fallback: "Could not resend the code. Try again.")
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
            eyebrow: "Create new password",
            title: "Set a new password",
            subtitle: "Your new password must be strong and secure. Make sure it's something you haven't used before.",
            content: {
                AppTextField(
                    value: $password,
                    hint: "New password",
                    isPassword: true,
                    leadingIcon: "lock"
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
                    .font(.system(size: 12))
                    .foregroundColor(GM.textTertiary)
                    .padding(.top, 8)
                AppTextField(
                    value: $confirm,
                    hint: "Confirm new password",
                    isPassword: true,
                    leadingIcon: "lock"
                )
                .padding(.top, 18)
                VStack(alignment: .leading, spacing: 0) {
                    RuleLine(label: "At least 8 characters", met: hasLength)
                    RuleLine(label: "Include a letter", met: hasLetter)
                    RuleLine(label: "Include a number", met: hasDigit)
                    RuleLine(label: "Include a special character", met: hasSpecial)
                    RuleLine(label: "Not commonly used", met: isFresh)
                }
                .padding(.top, 16)
                if !confirm.isEmpty && !matches {
                    Text("Both passwords must match.")
                        .font(GMFont.caption)
                        .foregroundColor(GM.error)
                        .padding(.top, 8)
                }
            },
            footer: {
                GradientButton(
                    title: "Reset Password",
                    enabled: ready,
                    busy: busy,
                    trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                    )
                ) { submit() }
                if let error {
                    Text(error).font(GMFont.caption).foregroundColor(GM.error).padding(.top, 10)
                    if error.lowercased().contains("code") {
                        HStack {
                            Spacer()
                            AccountLink(title: "Enter the code again", action: onBackToCode)
                            Spacer()
                        }
                    }
                }
                FooterNote(text: "Your password will be encrypted and kept secure.", divider: false)
                    .padding(.top, 16)
                Spacer().frame(height: 4)
            }
        )
    }

    private func submit() {
        guard let challenge = model.challengeId else {
            error = "That reset link expired. Start again."
            return
        }
        busy = true; error = nil
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
                case "WEAK_PASSWORD": self.error = apiMessage(error, fallback: "Use at least 8 characters with a letter, a number and a symbol.")
                case "VERIFY_FAILED": self.error = "That code is not correct, or it has expired."
                default: self.error = apiMessage(error, fallback: "We could not reset the password. Try again.")
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
                StatusChip(text: "Password Reset Successful", tone: .success)
                Text("Your password\nhas been reset")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)
                Text("Your GrapMail account is now secure with your new password. You can now sign in and continue using all the features.")
                    .font(.system(size: 15))
                    .lineSpacing(5)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
                Spacer().frame(height: 8)
            },
            footer: {
                GradientButton(
                    title: "Back to Sign In",
                    trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                    )
                ) { onBackToSignIn() }
                FooterNote(text: "For your security, we recommend keeping your password private and using two-step verification.")
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
                StatusChip(text: "Account Recovery Pending", tone: .warning)
                Text("We're still working\non your recovery")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)
                Text("There might be a short delay while we verify your information. You'll get an update soon at your verified phone number or email.")
                    .font(.system(size: 15))
                    .lineSpacing(5)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
                InfoPanel(
                    title: "What's happening?",
                    body: "We're verifying your recovery request. This usually takes a few minutes, but can take up to 24 hours in some cases.",
                    icon: {
                        Image(systemName: "clock")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundColor(GM.primarySolid)
                    }
                )
                .padding(.top, 18)
                Spacer().frame(height: 8)
            },
            footer: {
                GradientButton(
                    title: "Try Again",
                    busy: busy,
                    trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                    )
                ) { retry() }
                if let notice {
                    Text(notice).font(GMFont.caption).foregroundColor(GM.textSecondary)
                        .padding(.top, 10)
                }
                HStack {
                    Spacer()
                    AccountLink(title: "Back to Sign In", action: onBackToSignIn)
                    Spacer()
                }
                .padding(.top, 8)
                OrDivider().padding(.top, 14)
                FooterNote(
                    text: "Need help? Contact our support team.",
                    linkText: "support team",
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
                notice = resp.challengeId != nil
                    ? "A code is on its way. Check your phone."
                    : "Still in review. We'll message you as soon as it clears."
            } catch {
                notice = apiMessage(error, fallback: "We couldn't check again. Try in a minute.")
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
            eyebrow: "Recover your GrapMail address",
            title: "Find your GrapMail address",
            subtitle: "Enter the phone number linked to your GrapMail account. We'll check our records and show you the email address associated with it.",
            content: {
                AppTextField(
                    value: $phone,
                    hint: "Enter your verified phone number",
                    keyboardType: .phonePad,
                    leadingIcon: "phone"
                )
                Spacer().frame(height: 8)
            },
            footer: {
                GradientButton(
                    title: "Find My Email",
                    enabled: !phone.isEmpty,
                    busy: busy,
                    trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                    )
                ) { submit() }
                if let error {
                    Text(error).font(GMFont.caption).foregroundColor(GM.error).padding(.top, 10)
                }
                OrDivider().padding(.top, 16)
                FooterNote(
                    text: "Need help? Contact our support team.",
                    linkText: "support team",
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
                    error = "We couldn't find a GrapMail account with that number."
                    return
                }
                model.foundAddress = masked
                model.foundAddressFull = resp.address
                onFound()
            } catch {
                switch apiCode(error) {
                case "NOT_FOUND": self.error = "We couldn't find a GrapMail account with that number."
                case "RATE_LIMITED": self.error = "Too many lookups. Try again in a little while."
                default: self.error = apiMessage(error, fallback: "We couldn't check that number. Try again.")
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
                StatusChip(text: "Email Found", tone: .success)
                Text("Your GrapMail address\nhas been found!")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)
                Text("Here is the email address associated with your account. You can copy it or use it to sign in.")
                    .font(.system(size: 15))
                    .lineSpacing(5)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
                InfoCard {
                    HStack(spacing: 12) {
                        IconTile(systemName: "envelope")
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Your GrapMail address")
                                .font(.system(size: 12))
                                .foregroundColor(GM.textSecondary)
                            Text(shown)
                                .font(.system(size: 15, weight: .bold))
                                .foregroundColor(GM.textPrimary)
                        }
                        Spacer(minLength: 8)
                        CopyButton(title: copied ? "Copied" : "Copy") {
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
                    title: "Back to Sign In",
                    trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                    )
                ) { onBackToSignIn(model.foundAddressFull) }
                FooterNote(
                    text: "For your security, this address is partially hidden. You can copy it to use for sign in.",
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
            illustration: "ill_account_phone",
            eyebrow: "Phone verification",
            title: step == 1 ? "Add or verify your phone number" : "Enter the 6-digit code",
            subtitle: step == 1
                ? "We'll send a 6-digit verification code to your phone number. This helps you recover your account and enable two-step verification."
                : "We've sent a verification code to your phone number \(PhoneFormat.pretty(model.phone)). The code will expire in 5 minutes.",
            content: {
                TwoStepRibbon(current: step, labels: ["Enter Phone Number", "Verify Code"])
                Spacer().frame(height: 20)
                if step == 1 {
                    phoneField
                    Spacer().frame(height: 8)
                } else {
                    CodeCells(length: 6, value: $code)
                    HStack {
                        CodeExpiry(expiresAt: model.phoneExpiresAt)
                        Spacer()
                        AccountLink(title: resending ? "Sending…" : "Resend Code") { sendCode(resend: true) }
                    }
                    .padding(.top, 14)
                }
            },
            footer: {
                if step == 1 {
                    GradientButton(
                        title: "Send Verification Code",
                        enabled: !national.isEmpty,
                        busy: busy,
                        trailing: AnyView(
                            Image(systemName: "arrow.right")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(.white)
                        )
                    ) { sendCode(resend: false) }
                    if let error {
                        Text(error).font(GMFont.caption).foregroundColor(GM.error).padding(.top, 10)
                    }
                    FooterNote(
                        text: "Your phone number is only used for security purposes and won't be shared with anyone.",
                        divider: false
                    )
                    .padding(.top, 16)
                } else {
                    GradientButton(
                        title: "Verify & Continue",
                        enabled: code.count == 6,
                        busy: busy,
                        trailing: AnyView(
                            Image(systemName: "arrow.right")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(.white)
                        )
                    ) { verify() }
                    if let error {
                        Text(error).font(GMFont.caption).foregroundColor(GM.error).padding(.top, 10)
                    }
                    FooterNote(
                        text: "Didn't receive the code? Check your spam folder or try resending after 1 minute.",
                        divider: false
                    )
                    .padding(.top, 14)
                }
                Spacer().frame(height: 6)
            }
        )
    }

    private var phoneField: some View {
        HStack(spacing: 10) {
            Menu {
                ForEach(0..<PhoneFormat.countries.count, id: \.self) { index in
                    let c = PhoneFormat.countries[index]
                    Button("\(c.flag) \(c.name) \(c.dial)") { countryIndex = index }
                }
            } label: {
                HStack(spacing: 6) {
                    Text(country.flag).font(.system(size: 17))
                    Text(country.dial)
                        .font(.system(size: 15))
                        .foregroundColor(GM.textPrimary)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(GM.textTertiary)
                }
            }
            Rectangle().fill(GM.border).frame(width: 1, height: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text("Phone number")
                    .font(.system(size: 12))
                    .foregroundColor(GM.textTertiary)
                TextField("01XXXXXXXXX", text: $national)
                    .keyboardType(.phonePad)
                    .font(.system(size: 15))
                    .foregroundColor(GM.textPrimary)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
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
            error = "Enter a valid phone number."
            return
        }
        if resend { resending = true } else { busy = true }
        error = nil
        Task {
            defer { busy = false; resending = false }
            do {
                let resp = try await ApiClient.startPhoneVerification(phone: target)
                guard let cid = resp.challengeId else {
                    error = resp.message ?? "We couldn't send a code to that number."
                    return
                }
                code = ""
                model.phone = target
                model.phoneChallengeId = cid
                model.phoneExpiresAt = resp.expiresAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
                step = 2
            } catch {
                switch apiCode(error) {
                case "PHONE_IN_USE": self.error = "That number is already linked to another account."
                default: self.error = apiMessage(error, fallback: "We couldn't send your code. Try again.")
                }
            }
        }
    }

    private func verify() {
        guard let challenge = model.phoneChallengeId else { return }
        guard let session = SessionStore.token else {
            error = "Sign in first so the number can be saved to your account."
            return
        }
        busy = true; error = nil
        Task {
            defer { busy = false }
            do {
                let verified = try await ApiClient.verifyPhone(challengeId: challenge, code: code)
                guard let proof = verified.phoneToken else {
                    error = "That code is not correct."
                    return
                }
                _ = try await ApiClient.linkPhone(sessionToken: session, phoneToken: proof)
                let phone = verified.phone ?? model.phone
                model.verifiedPhone = phone
                onVerified(phone)
            } catch {
                switch apiCode(error) {
                case "PHONE_IN_USE": self.error = "That number is already linked to another account."
                case "VERIFY_FAILED", "BAD_CODE": self.error = "That code is not correct."
                default: self.error = apiMessage(error, fallback: "We couldn't verify that code. Try again.")
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
                StatusChip(text: "Phone Verified", tone: .success)
                Text("Your phone number\nhas been verified!")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)
                Text("Your phone number \(PhoneFormat.pretty(phone)) is now linked to your GrapMail account. You can use it for account recovery and two-step verification.")
                    .font(.system(size: 15))
                    .lineSpacing(5)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
                InfoCard {
                    HStack(spacing: 12) {
                        IconTile(systemName: "phone")
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Verified Phone Number")
                                .font(.system(size: 12))
                                .foregroundColor(GM.textSecondary)
                            Text(PhoneFormat.pretty(phone))
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(GM.textPrimary)
                        }
                        Spacer(minLength: 8)
                        HStack(spacing: 5) {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 12, weight: .semibold))
                            Text("Verified").font(.system(size: 12, weight: .semibold))
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
                    title: "Go to Account Security",
                    trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                    )
                ) { onGoToSecurity() }
                HStack {
                    Spacer()
                    AccountLink(title: "Back to Home", action: onBackHome)
                    Spacer()
                }
                .padding(.top, 8)
                FooterNote(
                    text: "Your phone number is securely stored and only used for account recovery and two-step verification.",
                    divider: false
                )
                .padding(.top, 14)
                Spacer().frame(height: 4)
            }
        )
    }
}
