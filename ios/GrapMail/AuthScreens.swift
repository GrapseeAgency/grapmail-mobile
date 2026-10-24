import SwiftUI

/// Welcome — founder design verbatim: logo + "Your world. Your inbox.",
/// "Welcome to / GrapMail", the 3D-envelope hero, arrowed CTAs, legal line.
struct WelcomeView: View {
    let onCreateAccount: () -> Void
    let onSignIn: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 40)
            Wordmark()
            Text("Your world. Your inbox.")
                .font(.system(size: 13))
                .foregroundColor(GM.textSecondary)
                .padding(.top, 6)

            Spacer(minLength: 10)

            VStack(spacing: 0) {
                Text("Welcome to")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundColor(GM.textPrimary)
                Text("GrapMail")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundColor(GM.primarySolid)
            }
            Text("A faster, safer and more private way\nto stay connected.")
                .font(.system(size: 14))
                .foregroundColor(GM.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.top, 12)

            Image("ill_hero")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(maxWidth: .infinity)
                .padding(.top, 18)

            Spacer(minLength: 10)

            GradientButton(title: "Create Account", trailing: AnyView(
                Image(systemName: "arrow.right")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.white)
            ), action: onCreateAccount)
            OutlineActionButton(title: "Sign In", trailing: AnyView(
                Image(systemName: "arrow.right")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
            ), action: onSignIn)
            .padding(.top, 12)

            (Text("By continuing, you agree to our ")
                .foregroundColor(GM.textTertiary)
             + Text("Terms of Service").foregroundColor(GM.primarySolid)
             + Text("\nand ").foregroundColor(GM.textTertiary)
             + Text("Privacy Policy").foregroundColor(GM.primarySolid)
             + Text(".").foregroundColor(GM.textTertiary))
                .font(.system(size: 12))
                .multilineTextAlignment(.center)
                .padding(.top, 20)
                .padding(.bottom, 24)
        }
        .padding(.horizontal, 24)
        .background(GM.bg.ignoresSafeArea())
    }
}

/// Sign-in — the earlier design ("Welcome back 👋"), with the real 2-step branch.
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
            Text("Welcome back 👋")
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(GM.textPrimary)
                .padding(.top, 28)
            Text("Sign in to continue to GrapMail")
                .font(.system(size: 14))
                .foregroundColor(GM.textSecondary)
                .padding(.top, 6)

            VStack(spacing: 16) {
                AppTextField(title: "Email or phone number", value: $identifier, keyboardType: .emailAddress)
                AppTextField(title: "Password", value: $password, isPassword: true)
            }
            .padding(.top, 30)

            Text("Forgot password?")
                .font(GMFont.captionMedium)
                .foregroundColor(GM.primarySolid)
                .padding(.top, 10)

            if let error {
                Text(error).font(GMFont.caption).foregroundColor(GM.error)
                    .padding(.top, 14)
            }

            GradientButton(title: "Sign In", enabled: !identifier.isEmpty && !password.isEmpty, busy: busy) {
                submit()
            }
            .padding(.top, 26)

            HStack(spacing: 4) {
                Text("Don't have an account?")
                    .font(GMFont.caption)
                    .foregroundColor(GM.textSecondary)
                Text("Create one")
                    .font(GMFont.captionMedium)
                    .foregroundColor(GM.primarySolid)
                    .onTapGesture { onSignUp() }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 24)
            .padding(.bottom, 30)
        }
    }

    private func otpSection(_ challenge: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("2-step verification")
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(GM.textPrimary)
                .padding(.top, 28)
            Text("Enter the 6-digit code sent to your verified number.")
                .font(.system(size: 14))
                .foregroundColor(GM.textSecondary)
                .padding(.top, 6)

            AppTextField(title: "6-digit code", value: $otpCode, keyboardType: .numberPad)
                .padding(.top, 30)
                .onChange(of: otpCode) { otpCode = String($0.filter(\.isNumber).prefix(6)) }

            if let error {
                Text(error).font(GMFont.caption).foregroundColor(GM.error).padding(.top, 14)
            }

            GradientButton(title: "Verify", enabled: otpCode.count == 6, busy: busy) {
                submitOtp(challenge)
            }
            .padding(.top, 26)

            Text("Use a different account")
                .font(GMFont.captionMedium)
                .foregroundColor(GM.primarySolid)
                .frame(maxWidth: .infinity)
                .padding(.top, 20)
                .padding(.bottom, 30)
                .onTapGesture { withAnimation { self.challengeId = nil } }
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
