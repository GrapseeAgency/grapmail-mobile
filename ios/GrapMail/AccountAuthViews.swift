import SwiftUI

/// Sign in — founder design verbatim: wordmark header, "Welcome back 👋", the
/// bordered card holding both fields, the reset link, the gradient CTA, OR, and
/// the create-account line. A challenged sign-in hands off to its own screen.
struct SignInView: View {
    @ObservedObject var model: RecoveryModel
    let onSignedIn: (String) -> Void
    var onBack: (() -> Void)? = nil
    let onSignUp: () -> Void
    let onForgotPassword: () -> Void
    let onNeedSecondFactor: () -> Void

    @State private var identifier = ""
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        ZStack(alignment: .topTrailing) {
            GM.bg.ignoresSafeArea()
            DecorativeBlobs()
                .frame(width: 520, height: 320)
                .ignoresSafeArea(edges: .top)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    AccountHeader(onBack: onBack)
                    Text("Welcome back 👋")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(GM.textPrimary)
                        .padding(.top, 6)
                    Text("Sign in to your GrapMail account and get back to what matters.")
                        .font(.system(size: 15))
                        .lineSpacing(5)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 8)

                    VStack(spacing: 16) {
                        AppTextField(
                            title: "Email address or phone number",
                            value: $identifier,
                            hint: "you@grapsee.com or +8801XXXXXXXX",
                            keyboardType: .emailAddress,
                            leadingIcon: "envelope"
                        )
                        AppTextField(
                            title: "Password",
                            value: $password,
                            hint: "Enter your password",
                            isPassword: true,
                            leadingIcon: "lock"
                        )
                    }
                    .padding(16)
                    .background(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).fill(GM.card))
                    .overlay(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).strokeBorder(GM.border))
                    .padding(.top, 26)

                    HStack {
                        Spacer()
                        Button(action: onForgotPassword) {
                            Text("Forgot password?")
                                .font(GMFont.captionMedium)
                                .foregroundColor(GM.primarySolid)
                        }
                        .buttonStyle(.plain)
                        .padding(.top, 8)
                    }

                    if let error {
                        Text(error)
                            .font(GMFont.caption)
                            .foregroundColor(GM.error)
                            .padding(.top, 10)
                    }

                    GradientButton(
                        title: "Sign In",
                        enabled: !identifier.isEmpty && !password.isEmpty,
                        busy: busy,
                        trailing: AnyView(
                            Image(systemName: "arrow.right")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(.white)
                        )
                    ) {
                        submit()
                    }
                    .padding(.top, 18)

                    OrDivider().padding(.top, 22)

                    HStack(spacing: 4) {
                        Text("Don't have an account?  ")
                            .font(GMFont.caption)
                            .foregroundColor(GM.textSecondary)
                        Button(action: onSignUp) {
                            Text("Create one")
                                .font(GMFont.captionMedium)
                                .foregroundColor(GM.primarySolid)
                        }
                        .buttonStyle(.plain)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 14)
                    .padding(.bottom, 28)
                }
                .padding(.horizontal, 24)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .centeredForm()
        .onAppear { identifier = model.prefillIdentifier ?? identifier }
        .onChange(of: model.prefillIdentifier) { newValue in
            if let newValue { identifier = newValue }
        }
    }

    private func submit() {
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                let resp = try await ApiClient.signIn(
                    identifier: identifier.trimmingCharacters(in: .whitespaces),
                    password: password
                )
                if resp.requireOtp == true, let cid = resp.challengeId {
                    model.identifier = identifier.trimmingCharacters(in: .whitespaces)
                    model.password = password
                    model.challengeId = cid
                    model.expiresAt = resp.expiresAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
                    model.prefillIdentifier = nil
                    onNeedSecondFactor()
                    return
                }
                guard let token = resp.sessionToken ?? resp.accessToken ?? resp.token else {
                    error = "Sign-in succeeded but no session was returned. Please try again."
                    return
                }
                let address = resp.account?.address ?? identifier
                SessionStore.save(
                    token: token,
                    address: address,
                    name: resp.account?.name ?? "",
                    expiresAtRaw: resp.expiresAt.map(Double.init)
                )
                onSignedIn(address)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

/// Two-step verification — the six code cells, the countdown, the CTA.
///
/// "Use a backup code" is the one addition: the artwork ships a backup-code
/// screen but draws no way in, and a factor screen with no alternative is a
/// locked door when the phone is gone.
struct TwoFactorView: View {
    @ObservedObject var model: RecoveryModel
    let onSignedIn: (String) -> Void
    let onBack: () -> Void
    let onBackToSignIn: () -> Void
    let onUseBackupCode: () -> Void

    @State private var code = ""
    @State private var error: String?
    @State private var notice: String?
    @State private var busy = false
    @State private var resending = false

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_account_2fa",
            eyebrow: "Two-step verification",
            title: "Enter your 6-digit code",
            subtitle: "We've sent a verification code to your GrapMail account. Please enter the 6-digit code to continue.",
            content: {
                CodeCells(length: 6, value: $code)
                HStack {
                    Spacer()
                    CodeExpiry(expiresAt: model.expiresAt)
                    Spacer()
                }
                .padding(.top, 14)
                if let notice {
                    Text(notice).font(GMFont.caption).foregroundColor(GM.success)
                        .padding(.top, 10)
                }
                if let error {
                    Text(error).font(GMFont.caption).foregroundColor(GM.error)
                        .padding(.top, 10)
                }
            },
            footer: {
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
                HStack {
                    Spacer()
                    AccountLink(title: resending ? "Sending…" : "Resend code") { resend() }
                    Spacer()
                }
                .padding(.top, 10)
                HStack {
                    Spacer()
                    AccountLink(title: "Use a backup code", action: onUseBackupCode)
                    Spacer()
                }
                OrDivider().padding(.top, 12)
                HStack {
                    Spacer()
                    AccountLink(title: "Back to sign in", action: onBackToSignIn)
                    Spacer()
                }
                .padding(.top, 12)
                .padding(.bottom, 6)
            }
        )
    }

    private func verify() {
        guard let challenge = model.challengeId else { return }
        busy = true; error = nil; notice = nil
        Task {
            defer { busy = false }
            do {
                let resp = try await ApiClient.twoFactorCode(challengeId: challenge, code: code)
                guard let token = resp.sessionToken ?? resp.accessToken ?? resp.token else {
                    error = "That code is not correct."
                    return
                }
                let address = resp.user?.address ?? model.identifier
                SessionStore.save(
                    token: token,
                    address: address,
                    name: resp.user?.name ?? "",
                    expiresAtRaw: resp.expiresAt.map(Double.init)
                )
                onSignedIn(address)
            } catch let apiError as ApiError {
                error = message(for: apiError, code: code)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func message(for error: ApiError, code: String) -> String {
        if case let .http(_, apiCode, message) = error {
            switch apiCode {
            case "CHALLENGE_EXPIRED": return "That code has expired. Resend it to get a new one."
            case "BAD_CODE": return "That code is not correct."
            default: return message.isEmpty ? "That code is not correct." : message
            }
        }
        return "Network error. Please try again."
    }

    /// Resending is signing in again: the server issues a fresh challenge and
    /// the old code stops working. Credentials are still in memory.
    private func resend() {
        guard !model.identifier.isEmpty, !model.password.isEmpty else { return }
        resending = true; error = nil; notice = nil
        Task {
            defer { resending = false }
            do {
                let resp = try await ApiClient.signIn(identifier: model.identifier, password: model.password)
                if resp.requireOtp == true, let cid = resp.challengeId {
                    code = ""
                    model.challengeId = cid
                    model.expiresAt = resp.expiresAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
                    notice = "A new code is on its way."
                } else {
                    error = "That sign-in no longer needs a code. Start again."
                }
            } catch let apiError as ApiError {
                error = message(for: apiError, code: "")
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

/// Backup code — the artwork's screen, with the one honest correction: GrapMail
/// issues ten-character codes ("ABCDE-FGHIJ"), so the row is two groups of five
/// and the note says ten. Six cells could never accept a real code.
struct BackupCodeView: View {
    @ObservedObject var model: RecoveryModel
    let onSignedIn: (String) -> Void
    let onBack: () -> Void
    let onTryAnotherMethod: () -> Void
    let onBackToSignIn: () -> Void

    @State private var code = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_account_backup",
            eyebrow: "Use a backup code",
            title: "Enter your backup code",
            subtitle: "If you don't have access to your 2-step verification method, you can use a backup code to get back into your account.",
            content: {
                CodeCells(length: 10, value: $code, alphanumeric: true, groupGap: true)
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "info.circle")
                        .font(.system(size: 13))
                        .foregroundColor(GM.textTertiary)
                    Text("Backup codes are 10 characters long and can only be used once.")
                        .font(.system(size: 13))
                        .lineSpacing(3)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.top, 16)
                if let error {
                    Text(error).font(GMFont.caption).foregroundColor(GM.error).padding(.top, 12)
                }
            },
            footer: {
                GradientButton(
                    title: "Continue",
                    enabled: code.count == 10,
                    busy: busy,
                    trailing: AnyView(
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                    )
                ) { submit() }
                HStack {
                    Spacer()
                    AccountLink(title: "Try another method", action: onTryAnotherMethod)
                    Spacer()
                }
                .padding(.top, 10)
                OrDivider().padding(.top, 12)
                HStack {
                    Spacer()
                    AccountLink(title: "Back to sign in", action: onBackToSignIn)
                    Spacer()
                }
                .padding(.top, 12)
                .padding(.bottom, 6)
            }
        )
    }

    private func submit() {
        guard !model.identifier.isEmpty, !model.password.isEmpty else {
            error = "Sign in again to use a backup code."
            return
        }
        busy = true; error = nil
        Task {
            defer { busy = false }
            do {
                let typed = String(code.prefix(10))
                // The dash is part of the stored code; the cells show ten
                // characters, so it is put back where GrapMail puts it.
                let normalized = typed.count == 10
                    ? "\(typed.prefix(5))-\(typed.suffix(5))"
                    : typed
                let resp = try await ApiClient.twoFactorBackup(
                    identifier: model.identifier,
                    password: model.password,
                    backupCode: normalized
                )
                guard let token = resp.sessionToken ?? resp.accessToken ?? resp.token else {
                    error = "That backup code is not valid, or it has already been used."
                    return
                }
                let address = resp.user?.address ?? model.identifier
                SessionStore.save(
                    token: token,
                    address: address,
                    name: resp.user?.name ?? "",
                    expiresAtRaw: resp.expiresAt.map(Double.init)
                )
                onSignedIn(address)
            } catch let apiError as ApiError {
                if case let .http(_, apiCode, message) = apiError {
                    switch apiCode {
                    case "BAD_BACKUP_CODE": error = "That backup code is not valid, or it has already been used."
                    case "TWO_FACTOR_NOT_ENABLED": error = "This account does not use two-step verification."
                    default: error = message.isEmpty ? "That backup code is not valid." : message
                    }
                } else {
                    error = apiError.localizedDescription
                }
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}
