import SwiftUI

/// Step 05 of turning two-step on: the code goes to the verified recovery email,
/// and the factor is switched on only after it is entered.
///
/// GrapMail sends account codes by email only, so this replaces `VerifyPhoneView`
/// in the enable flow. It sends a fresh code to the address already on the
/// account (POST /grapsee/auth/account/recovery-email/start), confirms it, and
/// spends the proof that comes back together with the password proof from
/// `ConfirmPasswordView`. An account with no verified recovery email is sent to
/// add one first.
struct TwoFactorEmailView: View {
    let onBack: () -> Void
    let onAddRecoveryEmail: () -> Void
    let onVerified: () -> Void

    @State private var address: String?
    @State private var verificationToken: String?
    @State private var maskedEmail: String?
    @State private var expiresAt: Date?
    @State private var code = ""
    @State private var busy = false
    @State private var loaded = false
    @State private var error: String?
    @State private var needsEmail = false
    @State private var needsProof = false

    private func load() async {
        guard let token = SessionStore.token else { error = "Sign in again."; return }
        do {
            let profile = try await ApiClient.profile(token: token).profile
            guard let email = profile?.recoveryEmail, !email.isEmpty, profile?.recoveryEmailVerified == true else {
                needsEmail = true
                return
            }
            address = email
            await send()
        } catch {
            self.error = apiMessage(error, fallback: "We couldn't load your account. Try again.")
        }
    }

    private func send() async {
        guard let token = SessionStore.token, let to = address else { return }
        do {
            let sent = try await ApiClient.startRecoveryEmail(token: token, email: to)
            verificationToken = sent.token
            maskedEmail = sent.maskedEmail ?? to
            expiresAt = sent.expiresAt.map { Date(timeIntervalSince1970: $0 / 1000) }
            code = ""
        } catch {
            self.error = apiMessage(error, fallback: "We couldn't send the code. Try again.")
        }
    }

    private func turnOn() {
        guard let signed = verificationToken, code.count == 6, let token = SessionStore.token else { return }
        busy = true; error = nil
        Task {
            defer { busy = false }
            do {
                let confirmed = try await ApiClient.confirmRecoveryEmail(token: token, verificationToken: signed, code: code)
                _ = try await ApiClient.enableTwoFactor(
                    token: token, proof: SecurityFlow.enableProof, phoneProof: nil, emailProof: confirmed.emailProof)
                SecurityFlow.enableProof = nil
                SecurityFlow.phoneProof = nil
                onVerified()
            } catch {
                // The password proof is single use and checked first, so a refused
                // enable call has already spent it: send the person back for a
                // fresh one rather than retrying a dead token.
                let failure = apiCode(error)
                if failure == "PROOF_REQUIRED" || failure == "EMAIL_PROOF_REQUIRED" {
                    needsProof = true
                    SecurityFlow.enableProof = nil
                    verificationToken = nil
                    code = ""
                }
                if failure == "TWO_FACTOR_NEEDS_RECOVERY_EMAIL" { needsEmail = true }
                self.error = apiMessage(error, fallback: "We couldn't turn on two-step verification. Try again.")
            }
        }
    }

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_phone",
            illustrationWidth: 170,
            title: needsEmail ? "Add a Recovery Email" : "Check Your Email",
            subtitle: needsEmail
                ? "Two-step verification emails your sign-in codes to a verified recovery email. Add one first."
                : (maskedEmail.map { "We've emailed a 6-digit code to \($0)." }
                    ?? "We'll email a code to the recovery email on this account.")
        ) {
            if !needsEmail {
                InfoCard {
                    HStack(spacing: 8) {
                        Image(systemName: "envelope").font(.system(size: 14)).foregroundColor(GM.primarySolid)
                        Text("Recovery Email").font(.system(size: 13, weight: .medium)).foregroundColor(GM.textSecondary)
                        Spacer()
                        StatusChip(text: "Verified", tone: .success)
                    }
                    Text(address ?? "—")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(GM.textPrimary)
                        .padding(.top, 4)
                    Text("Verification Code")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(GM.textSecondary)
                        .padding(.top, 14)
                    CodeCells(length: 6, value: $code)
                        .padding(.top, 6)
                }
                CodeExpiry(expiresAt: expiresAt)
                    .padding(.top, 10)
                Text("Didn't get it? Check your spam folder. From now on, sign-in codes will go to this address.")
                    .font(.system(size: 12))
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
            }
            if let error {
                Text(error).font(.system(size: 12)).foregroundColor(GM.error).padding(.top, 6)
            }
            if needsProof {
                AccountLink(title: "Enter your password again", action: onBack)
            }
        } footer: {
            VStack(spacing: 4) {
                if needsEmail {
                    GradientButton(title: "Add Recovery Email", action: onAddRecoveryEmail)
                } else {
                    GradientButton(
                        title: "Turn On",
                        enabled: code.count == 6 && verificationToken != nil,
                        busy: busy || !loaded,
                        action: { turnOn() }
                    )
                    AccountLink(title: "Resend code", action: {
                        error = nil
                        Task { await send() }
                    })
                }
            }
        }
        .task {
            guard !loaded else { return }
            await load()
            loaded = true
        }
    }
}
