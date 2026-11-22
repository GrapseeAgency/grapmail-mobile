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
    var onNeedEmailVerification: () -> Void = {}

    @State private var identifier = ""
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false
    // The passkey path keeps its own busy/error pair: a cancelled system sheet
    // or a refused credential must never leave the password form's state
    // dirty, and the Sign In button's enablement must not wait on the sheet.
    @State private var passkeyBusy = false
    @State private var passkeyError: String?

    var body: some View {
        ZStack(alignment: .topTrailing) {
            // The decorative field is an overlay of the BACKGROUND, not a
            // ZStack sibling. As a fixed 520-point sibling it decided the
            // ZStack's width, so on a 402-point screen the entire form was laid
            // out 59 points wider than the screen on each side: the title, the
            // fields and the Sign In button all ran off both edges (photographed
            // by the walk as "elcome back" and clipped fields). A view attached
            // to the background cannot change its parent's layout size.
            GM.bg
                .ignoresSafeArea()
                .overlay(alignment: .topTrailing) {
                    DecorativeBlobs()
                        .frame(width: 520, height: 320)
                }
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
                    if let passkeyError {
                        Text(passkeyError)
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

                    // The password-free door: a discoverable credential for
                    // this account signs in without either field being typed.
                    // Usernameless by contract — the identifier above does not
                    // take part, so the link is live with the form empty.
                    HStack {
                        Spacer()
                        AccountLink(title: passkeyBusy ? "Waiting for the passkey…" : "Use a passkey",
                                    action: passkeySignIn)
                        Spacer()
                    }
                    .padding(.top, 10)

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
        .onAppear {
            identifier = model.prefillIdentifier ?? identifier
            model.clearSignInChallenge()
        }
        .disabled(busy || passkeyBusy)
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
                // A challenge in the response means a code is required — the
                // presence is the gate, not the `requireOtp` flag, which this
                // backend omits on ordinary sign-ins. Reading an absent flag as
                // "no code needed" would sign a 2FA account in on the password.
                if resp.secondFactorRequired {
                    model.identifier = identifier.trimmingCharacters(in: .whitespaces)
                    model.password = password
                    password = ""
                    model.challengeId = resp.challengeId
                    // The CHALLENGE's expiry, for the code countdown — deliberately
                    // `expiresAt` and not `expiresAtDate`: the latter falls back to
                    // the session's `expiresIn`, and a countdown that claims the
                    // code lives as long as the session would be a lie.
                    model.expiresAt = resp.expiresAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
                    model.prefillIdentifier = nil
                    onNeedSecondFactor()
                    return
                }
                guard !resp.secondFactorRequired, resp.success,
                      let token = resp.sessionToken ?? resp.accessToken ?? resp.token, !token.isEmpty else {
                    error = "Sign-in did not return a complete session. Please try again."
                    return
                }
                let address = resp.account?.address ?? identifier
                SessionStore.save(
                    token: token,
                    refreshToken: resp.refreshToken,
                    address: address,
                    name: resp.account?.name ?? "",
                    expiresAtRaw: resp.expiresAtMillis
                )
                password = ""
                model.clearSignInChallenge()
                onSignedIn(address)
            } catch ApiError.emailVerificationRequired(let email) {
                password = ""
                model.clearSignInChallenge()
                model.verificationEmail = email
                onNeedEmailVerification()
            } catch {
                self.error = apiMessage(error, fallback: "Could not complete the account request. Try again.")
            }
        }
    }

    /// Passkey sign-in: options → platform assertion → verify → the same
    /// session store the password path fills, so everything downstream (the
    /// mailbox, trusted-device enrollment, push re-registration) runs the
    /// identical post-sign-in sequence. The challenge is single-use and
    /// single-shot: one platform sheet per options response, and a refusal
    /// means starting from a fresh options call.
    private func passkeySignIn() {
        passkeyBusy = true
        passkeyError = nil
        Task {
            defer { passkeyBusy = false }
            do {
                let optionsResponse = try await ApiClient.passkeyAuthOptions()
                guard optionsResponse.success, let challengeId = optionsResponse.challengeId.nilIfBlank(),
                      let challenge = optionsResponse.challengeBase64Url,
                      let options = optionsResponse.options else {
                    passkeyError = "Passkey sign-in is not available right now. Use your password instead."
                    return
                }
                let assertion = try await PasskeyManager.assertion(options: options)
                let verified = try await ApiClient.passkeyAuthVerify(
                    challengeId: challengeId,
                    response: PasskeyAuthenticationResponseJson(
                        id: assertion.credentialId,
                        clientDataJSON: assertion.clientDataJSON,
                        authenticatorData: assertion.authenticatorData,
                        signature: assertion.signature,
                        userHandle: assertion.userHandle))
                guard verified.success, let token = verified.accessToken?.nilIfBlank() else {
                    passkeyError = verified.error?.nilIfBlank() ?? "The passkey was not accepted. Use your password instead."
                    return
                }
                // The passkey envelope names the account under `user` with
                // `email`; the typed field is the fallback, and a sign-in that
                // names neither has not returned a session worth storing.
                let address = verified.user?.email.nilIfBlank() ?? identifier.trimmingCharacters(in: .whitespaces)
                guard !address.isEmpty else {
                    passkeyError = "Sign-in did not return a complete session. Please try again."
                    return
                }
                // The route answers no expiry stamp (unlike the password
                // login's `expiresIn`), so the session is stored without one —
                // SessionStore keeps it until a 401 renewal says otherwise.
                SessionStore.save(
                    token: token,
                    refreshToken: verified.refreshToken,
                    address: address,
                    name: verified.user?.name ?? "",
                    expiresAtRaw: nil)
                password = ""
                model.clearSignInChallenge()
                onSignedIn(address)
            } catch PasskeyManager.PasskeyError.cancelled {
                // The person closed the sheet — not a failure to report.
            } catch {
                passkeyError = apiMessage(error, fallback: "Could not sign in with the passkey. Try again.")
            }
        }
    }
}

/// Email verification is not phone two-factor: no challengeId or backup codes.
struct EmailVerificationView: View {
    @ObservedObject var model: RecoveryModel
    let onSignedIn: (String) -> Void
    let onBack: () -> Void
    @State private var otp = ""
    @State private var busy = false
    @State private var error: String?
    @State private var notice: String?

    var body: some View {
        AccountScreen(onBack: { model.clearSignInChallenge(); onBack() },
                      eyebrow: "Email verification", title: "Enter the email code",
                      subtitle: model.verificationEmail,
                      content: {
            CodeCells(length: 6, value: $otp)
            if let error { Text(error).font(GMFont.caption).foregroundColor(GM.error) }
            if let notice { Text(notice).font(GMFont.caption).foregroundColor(GM.textSecondary) }
        }, footer: {
            GradientButton(title: "Verify & Continue", enabled: otp.count == 6, busy: busy) {
                verify()
            }
            AccountLink(title: "Resend code") { resend() }
            Text("A pending code stays valid. A resend request does not confirm a new delivery.")
                .font(GMFont.caption).foregroundColor(GM.textSecondary)
        })
        .disabled(busy)
        .onDisappear { otp = "" }
    }

    private func verify() {
        guard !busy, let email = model.verificationEmail else { return }
        busy = true; error = nil; notice = nil
        Task { @MainActor in
            defer { busy = false }
            do {
                let resp = try await ApiClient.verifyEmailOTP(email: email, otp: otp)
                guard resp.success, !resp.secondFactorRequired,
                      let token = resp.accessToken ?? resp.sessionToken ?? resp.token, !token.isEmpty else {
                    error = "Verification did not return a complete session. Sign in again."
                    return
                }
                let address = resp.account?.address.isEmpty == false ? resp.account!.address : email
                SessionStore.save(token: token, refreshToken: resp.refreshToken, address: address,
                                  name: resp.account?.name ?? "", expiresAtRaw: resp.expiresAtMillis)
                otp = ""
                model.clearSignInChallenge()
                onSignedIn(address)
            } catch { self.error = apiMessage(error, fallback: "Could not complete the account request. Try again.") }
        }
    }

    private func resend() {
        guard !busy, let email = model.verificationEmail else { return }
        busy = true; error = nil; notice = nil
        Task { @MainActor in
            defer { busy = false }
            do {
                let resp = try await ApiClient.resendEmailOTP(email: email)
                guard resp.success else { error = "The request was not accepted."; return }
                // The endpoint deliberately returns the same answer on cooldown,
                // existing codes, unknown accounts and provider failure. No countdown.
                notice = "Request accepted. Check for an existing code; a new delivery is not confirmed."
            } catch { self.error = apiMessage(error, fallback: "Could not complete the account request. Try again.") }
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
            title: model.challengeId == nil ? "Use a backup code" : "Enter your 6-digit code",
            subtitle: model.challengeId == nil
                ? "A verification text is unavailable. Use one of your saved backup codes to continue."
                : "Enter the six-digit code we sent to your phone.",
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
                    refreshToken: resp.refreshToken,
                    address: address,
                    name: resp.user?.name ?? "",
                    expiresAtRaw: resp.expiresAtMillis
                )
                onSignedIn(address)
            } catch let apiError as ApiError {
                error = message(for: apiError, code: code)
            } catch {
                self.error = apiMessage(error, fallback: "Could not complete the account request. Try again.")
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
                // A challenge in the response means a code is required — the
                // presence is the gate, not the `requireOtp` flag, which this
                // backend omits on ordinary sign-ins. Reading an absent flag as
                // "no code needed" would sign a 2FA account in on the password.
                if resp.secondFactorRequired, let cid = resp.challengeId {
                    code = ""
                    model.challengeId = cid
                    // The CHALLENGE's expiry, for the code countdown — deliberately
                    // `expiresAt` and not `expiresAtDate`: the latter falls back to
                    // the session's `expiresIn`, and a countdown that claims the
                    // code lives as long as the session would be a lie.
                    model.expiresAt = resp.expiresAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
                    notice = "A new code is on its way."
                } else {
                    error = "That sign-in no longer needs a code. Start again."
                }
            } catch let apiError as ApiError {
                error = message(for: apiError, code: "")
            } catch {
                self.error = apiMessage(error, fallback: "Could not complete the account request. Try again.")
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
                    refreshToken: resp.refreshToken,
                    address: address,
                    name: resp.user?.name ?? "",
                    expiresAtRaw: resp.expiresAtMillis
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
                self.error = apiMessage(error, fallback: "Could not complete the account request. Try again.")
            }
        }
    }
}
