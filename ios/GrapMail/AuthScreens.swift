import SwiftUI

/// The app's face: "Better Email. A Brighter You." — founder design verbatim,
/// with the entrance motion the static PNG implies.
struct WelcomeView: View {
    let onCreateAccount: () -> Void
    let onSignIn: () -> Void

    @State private var illustrationRisen = false
    @State private var textShown = false

    var body: some View {
        VStack(spacing: 0) {
            Wordmark()
                .padding(.top, 28)
            Spacer(minLength: 12)

            Image("ill_welcome")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(maxWidth: .infinity)
                .offset(y: illustrationRisen ? 0 : 22)
                .opacity(illustrationRisen ? 1 : 0)
                .animation(.easeOut(duration: 0.48), value: illustrationRisen)

            Spacer(minLength: 12)

            VStack(spacing: 10) {
                Text.h1("Better Email.").foregroundColor(GM.textPrimary)
                Text.h1("A Brighter You.").foregroundColor(GM.primarySolid)
                Text("Secure · Private · Yours")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(GM.textSecondary)
            }
            .opacity(textShown ? 1 : 0)
            .animation(.easeOut(duration: 0.42).delay(0.15), value: textShown)

            Spacer(minLength: 16)

            GradientButton(title: "Create Account", action: onCreateAccount)
            OutlineActionButton(title: "Sign In", action: onSignIn)
                .padding(.top, 12)

            Text("Or")
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(GM.textTertiary)
                .padding(.top, 20)

            Text("By creating an account you agree to our Terms of Service and Privacy Policy")
                .font(.system(size: 12))
                .foregroundColor(GM.textTertiary)
                .multilineTextAlignment(.center)
                .padding(.top, 16)
                .padding(.horizontal, 8)

            HStack(spacing: 8) {
                Circle().fill(GM.primarySolid).frame(width: 7, height: 7)
                Circle().fill(GM.stepInactive).frame(width: 7, height: 7)
                Circle().fill(GM.stepInactive).frame(width: 7, height: 7)
            }
            .padding(.top, 18)
            .padding(.bottom, 24)
        }
        .padding(.horizontal, 24)
        .background(GM.bg.ignoresSafeArea())
        .onAppear {
            illustrationRisen = true
            textShown = true
        }
    }
}

/// Sign-in — founder design verbatim. Posts to the real /auth/account/signin;
/// the 2-step branch is handled inline because real accounts may have the
/// factor on and the app must never fake its way past it.
struct SignInView: View {
    let onSignedIn: (String) -> Void
    let onSignUp: () -> Void

    @State private var identifier = ""
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false
    @State private var challengeId: String?
    @State private var otpCode = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack { Spacer(); Wordmark(); Spacer() }
                    .padding(.top, 40)

                if let challenge = challengeId {
                    otpSection(challenge)
                } else {
                    passwordSection
                }
            }
        }
        .background(GM.bgTinted.ignoresSafeArea())
        .scrollDismissesKeyboard(.interactively)
    }

    private var passwordSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text.h2("Welcome back 👋").foregroundColor(GM.textPrimary)
                .padding(.top, 28)
            Text("Sign in to continue to GrapMail")
                .font(.system(size: 14))
                .foregroundColor(GM.textSecondary)
                .padding(.top, 6)

            VStack(spacing: 16) {
                AppTextField(label: "Email or phone number", value: $identifier, keyboardType: .emailAddress)
                AppTextField(label: "Password", value: $password, isPassword: true)
            }
            .padding(.top, 30)

            Button {
                // Recovery flow arrives with the account-recovery phase.
            } label: {
                Text("Forgot password?")
                    .font(GMFont.captionMedium)
                    .foregroundColor(GM.primarySolid)
            }
            .buttonStyle(.plain)
            .padding(.top, 10)

            if let error {
                Text(error).font(GMFont.caption).foregroundColor(GM.error)
                    .padding(.top, 14)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }

            GradientButton(
                title: "Sign In",
                enabled: !identifier.trimmingCharacters(in: .whitespaces).isEmpty && !password.isEmpty,
                busy: busy
            ) {
                submit()
            }
            .padding(.top, 26)

            HStack(spacing: 4) {
                Text("Don't have an account?")
                    .font(GMFont.caption)
                    .foregroundColor(GM.textSecondary)
                Button {
                    onSignUp()
                } label: {
                    Text("Create one")
                        .font(GMFont.captionMedium)
                        .foregroundColor(GM.primarySolid)
                }
                .buttonStyle(.plain)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 24)
            .padding(.bottom, 30)
        }
    }

    private func otpSection(_ challenge: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text.h2("2-step verification").foregroundColor(GM.textPrimary)
                .padding(.top, 28)
            Text("Enter the 6-digit code sent to your verified number.")
                .font(.system(size: 14))
                .foregroundColor(GM.textSecondary)
                .padding(.top, 6)

            AppTextField(label: "6-digit code", value: $otpCode, keyboardType: .numberPad)
                .padding(.top, 30)
                .onChange(of: otpCode) { newValue in
                    otpCode = String(newValue.filter(\.isNumber).prefix(6))
                }

            if let error {
                Text(error).font(GMFont.caption).foregroundColor(GM.error)
                    .padding(.top, 14)
            }

            GradientButton(title: "Verify", enabled: otpCode.count == 6, busy: busy) {
                submitOtp(challenge)
            }
            .padding(.top, 26)

            Button {
                withAnimation { self.challengeId = nil }
            } label: {
                Text("Use a different account")
                    .font(GMFont.captionMedium)
                    .foregroundColor(GM.primarySolid)
            }
            .buttonStyle(.plain)
            .frame(maxWidth: .infinity)
            .padding(.top, 20)
            .padding(.bottom, 30)
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
                if resp.requireOtp, let cid = resp.challengeId {
                    withAnimation { challengeId = cid }
                    return
                }
                guard let token = resp.sessionToken ?? resp.accessToken ?? resp.token else {
                    error = "Sign-in succeeded but no session was returned. Please try again."
                    return
                }
                let address = resp.account?.address ?? identifier
                SessionStore.save(token: token, address: address, name: resp.account?.name ?? "")
                onSignedIn(address)
            } catch {
                error = error.localizedDescription
            }
        }
    }

    private func submitOtp(_ challenge: String) {
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                let resp = try await ApiClient.twoFactorCode(challengeId: challenge, code: otpCode)
                guard let token = resp.sessionToken ?? resp.accessToken ?? resp.token else {
                    error = "That code is not correct."
                    return
                }
                let address = resp.user?.address ?? identifier
                SessionStore.save(token: token, address: address, name: resp.user?.name ?? "")
                onSignedIn(address)
            } catch {
                error = error.localizedDescription
            }
        }
    }
}
