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
        guard let token = SessionStore.token else { error = String(localized: "Sign in again.", comment: "Error when the session has expired"); return }
        do {
            let profile = try await ApiClient.profile(token: token).profile
            guard let email = profile?.recoveryEmail, !email.isEmpty, profile?.recoveryEmailVerified == true else {
                needsEmail = true
                return
            }
            address = email
            await send()
        } catch {
            self.error = apiMessage(error, fallback: String(localized: "We couldn't load your account. Try again.", comment: "Error message shown when the server gives no reason (two factor email screen)"))
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
            self.error = apiMessage(error, fallback: String(localized: "We couldn't send the code. Try again.", comment: "Error message shown when the server gives no reason (two factor email screen)"))
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
                self.error = apiMessage(error, fallback: String(localized: "We couldn't turn on two-step verification. Try again.", comment: "Error message shown when the server gives no reason (two factor email screen)"))
            }
        }
    }

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_phone",
            illustrationWidth: 170,
            title: needsEmail ? String(localized: "Add a Recovery Email", comment: "Screen title (two factor email screen)") : String(localized: "Check Your Email", comment: "Screen title (two factor email screen)"),
            subtitle: needsEmail
                ? String(localized: "Two-step verification emails your sign-in codes to a verified recovery email. Add one first.", comment: "Screen description under the title (two factor email screen)")
                : (maskedEmail.map { String(localized: "We've emailed a 6-digit code to \($0).", comment: "Screen description under the title (two factor email screen)") }
                    ?? String(localized: "We'll email a code to the recovery email on this account.", comment: "Screen description under the title (two factor email screen)"))
        ) {
            if !needsEmail {
                InfoCard {
                    GMAdaptiveStack(spacing: 8) {
                        Image(systemName: "envelope").gmFont(14).foregroundColor(GM.primarySolid)
                        Text("Recovery Email").gmFont(13, .medium).foregroundColor(GM.textSecondary)
                        Spacer()
                        StatusChip(text: String(localized: "Verified", comment: "Status chip (two factor email screen)"), tone: .success)
                    }
                    Text(address ?? "—")
                        .gmFont(16, .semibold)
                        .foregroundColor(GM.textPrimary)
                        .padding(.top, 4)
                    Text("Verification Code")
                        .gmFont(13, .medium)
                        .foregroundColor(GM.textSecondary)
                        .padding(.top, 14)
                    CodeCells(length: 6, value: $code)
                        // Round 8 — B: the emailed code is offered above the keyboard.
                        .textContentType(.oneTimeCode)
                        .padding(.top, 6)
                }
                CodeExpiry(expiresAt: expiresAt)
                    .padding(.top, 10)
                Text("Didn't get it? Check your spam folder. From now on, sign-in codes will go to this address.")
                    .gmFont(12)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
            }
            if let error {
                Text(error).gmFont(12).foregroundColor(GM.error).padding(.top, 6)
            }
            if needsProof {
                AccountLink(title: String(localized: "Enter your password again", comment: "Link-style button (two factor email screen)"), action: onBack)
            }
        } footer: {
            VStack(spacing: 4) {
                if needsEmail {
                    GradientButton(title: String(localized: "Add Recovery Email", comment: "Button title (two factor email screen)"), action: onAddRecoveryEmail)
                } else {
                    GradientButton(
                        title: String(localized: "Turn On", comment: "Button title (two factor email screen)"),
                        enabled: code.count == 6 && verificationToken != nil,
                        busy: busy || !loaded,
                        action: { turnOn() }
                    )
                    AccountLink(title: String(localized: "Resend code", comment: "Link-style button (two factor email screen)"), action: {
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
