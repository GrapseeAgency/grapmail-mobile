import SwiftUI

/// Add a recovery email: the only way a GrapMail account gets its password back.
///
/// GrapMail recovers by email only. Forgot-password sends its reset link to the
/// account's VERIFIED recovery address, so an address is useless until it is
/// proven, and this view proves it in two steps against the backend:
///
///   1. POST /grapsee/auth/account/recovery-email/start   { email }
///      -> a 6-digit code goes to that address, and a signed token comes back
///   2. POST /grapsee/auth/account/recovery-email/confirm { token, code }
///      -> the address is saved as verified
///
/// Nothing is written to the account before step 2, so backing out halfway
/// leaves the account exactly as it was.
///
/// Meant for two places: the optional step right after sign-up (`onSkip` set),
/// and Account Security, which keeps reminding until an address is verified.
struct RecoveryEmailView: View {
    var onBack: (() -> Void)? = nil
    let onDone: () -> Void
    var onSkip: (() -> Void)? = nil
    var currentEmail: String? = nil

    @State private var email = ""
    @State private var verificationToken: String?
    @State private var maskedEmail: String?
    @State private var expiresAt: Date?
    @State private var code = ""
    @State private var confirmed = false
    @State private var error: String?
    @State private var notice: String?
    @State private var busy = false

    private var normalized: String { email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
    private var looksLikeEmail: Bool {
        normalized.range(of: "^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$", options: .regularExpression) != nil
    }

    var body: some View {
        if confirmed {
            confirmedScreen
        } else if verificationToken != nil {
            codeScreen
        } else {
            emailScreen
        }
    }

    // MARK: - Screens

    private var emailScreen: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_account_forgot",
            eyebrow: String(localized: "Recovery email", comment: "Small label above the screen title (recovery email screen)"),
            title: String(localized: "Add a recovery email", comment: "Screen title (recovery email screen)"),
            subtitle: String(localized: "If you ever forget your password, we'll send a reset link here. Use an address outside GrapMail.", comment: "Screen description under the title (recovery email screen)"),
            content: {
                AppTextField(
                    value: $email,
                    hint: "name@example.com",
                    keyboardType: .emailAddress,
                    leadingIcon: "envelope",
                    // An address outside GrapMail, not a GrapMail login.
                    contentType: .emailAddress
                )
                .onAppear { if email.isEmpty { email = currentEmail ?? "" } }
                .onChange(of: email) { _ in error = nil }
                InfoPanel(
                    title: String(localized: "Why it matters", comment: "Info panel title (recovery email screen)"),
                    body: String(localized: "GrapMail resets passwords by email only. Without a verified recovery email, a forgotten password can lock you out.", comment: "Info panel text (recovery email screen)"),
                    icon: {
                        Image(systemName: "lock.shield")
                            .gmFont(15, .medium)
                            .foregroundColor(GM.primarySolid)
                    }
                )
                .padding(.top, 16)
                Spacer().frame(height: 8)
            },
            footer: {
                GradientButton(
                    title: String(localized: "Send code", comment: "Button title (recovery email screen)"),
                    enabled: looksLikeEmail,
                    busy: busy,
                    trailing: AnyView(
                        Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                            .gmFont(15, .semibold, maxSize: 24)
                            .foregroundColor(.white)
                    )
                ) { sendCode(again: false) }
                if let error {
                    Text(error).gmFont(.caption).foregroundColor(GM.error).padding(.top, 10)
                }
                if let onSkip {
                    HStack {
                        Spacer()
                        AccountLink(title: String(localized: "Skip for now", comment: "Link-style button (recovery email screen)"), action: onSkip)
                        Spacer()
                    }
                    .padding(.top, 8)
                    FooterNote(text: String(localized: "You can add it later in Settings → Account Security.", comment: "Footer note (recovery email screen)"), divider: false)
                        .padding(.top, 8)
                }
                Spacer().frame(height: 4)
            }
        )
    }

    private var codeScreen: some View {
        AccountScreen(
            onBack: { verificationToken = nil; code = ""; error = nil; notice = nil },
            illustration: "ill_account_recovery_code",
            eyebrow: String(localized: "Verify recovery email", comment: "Small label above the screen title (recovery email screen)"),
            title: String(localized: "Enter the 6-digit code", comment: "Screen title (recovery email screen)"),
            subtitle: String(localized: "We sent a code to \(maskedEmail ?? String(localized: "your email", comment: "Used in place of the masked address when it is not known")). Enter it to confirm this address.", comment: "Screen description under the title; the argument is the masked recovery email"),
            content: {
                CodeCells(length: 6, value: $code)
                    // Round 8 — B: the emailed code is offered above the keyboard.
                    .textContentType(.oneTimeCode)
                    .onChange(of: code) { newValue in
                        let digits = String(newValue.filter(\.isNumber).prefix(6))
                        if digits != newValue { code = digits }
                        error = nil
                    }
                HStack {
                    Spacer()
                    CodeExpiry(expiresAt: expiresAt)
                    Spacer()
                }
                .padding(.top, 14)
            },
            footer: {
                GradientButton(
                    title: String(localized: "Confirm", comment: "Button title (recovery email screen)"),
                    enabled: code.count == 6,
                    busy: busy,
                    trailing: AnyView(
                        Image(systemName: "arrow.right").flipsForRightToLeftLayoutDirection(true)
                            .gmFont(15, .semibold, maxSize: 24)
                            .foregroundColor(.white)
                    )
                ) { confirm() }
                if let error {
                    Text(error).gmFont(12).foregroundColor(GM.error)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 10)
                }
                OrDivider().padding(.top, 14)
                HStack {
                    Spacer()
                    AccountLink(title: busy ? String(localized: "Sending…", comment: "Link-style button (recovery email screen)") : String(localized: "Resend code", comment: "Link-style button (recovery email screen)")) { sendCode(again: true) }
                    Spacer()
                }
                .padding(.top, 10)
                if let notice {
                    Text(notice).gmFont(12).foregroundColor(GM.success)
                        .frame(maxWidth: .infinity)
                }
                FooterNote(text: String(localized: "Didn't get it? Check your spam folder.", comment: "Footer note (recovery email screen)"), divider: false)
                    .padding(.top, 12)
                Spacer().frame(height: 4)
            }
        )
    }

    private var confirmedScreen: some View {
        AccountScreen(
            illustration: "ill_account_pending",
            content: {
                StatusChip(text: String(localized: "Recovery email confirmed", comment: "Status chip (recovery email screen)"), tone: .success)
                Text("You're covered")
                    .gmFont(28, .bold, maxSize: 44)
                    .foregroundColor(GM.textPrimary)
                    .padding(.top, 16)
                Text("If you ever forget your password, we'll send a reset link to \(normalized).")
                    .gmFont(15)
                    .lineSpacing(5)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
            },
            footer: {
                GradientButton(title: String(localized: "Continue", comment: "Button title (recovery email screen)")) { onDone() }
                Spacer().frame(height: 4)
            }
        )
    }

    // MARK: - Actions

    private func sendCode(again: Bool) {
        guard looksLikeEmail, !busy else { return }
        guard let token = SessionStore.token else { error = String(localized: "Your session has expired. Sign in again.", comment: "Error when the session has expired"); return }
        let address = normalized
        busy = true; error = nil; notice = nil
        Task {
            defer { busy = false }
            do {
                let resp = try await ApiClient.startRecoveryEmail(token: token, email: address)
                guard let signed = resp.token else {
                    error = String(localized: "We couldn't send the code. Try again.", comment: "Message (recovery email screen)")
                    return
                }
                verificationToken = signed
                maskedEmail = resp.maskedEmail ?? address
                expiresAt = resp.expiresAt.map { Date(timeIntervalSince1970: $0 / 1000) }
                code = ""
                if again { notice = String(localized: "A new code is on its way.", comment: "Notice after a verification code was resent") }
            } catch {
                self.error = apiMessage(error, fallback: String(localized: "We couldn't send the code. Try again.", comment: "Error message shown when the server gives no reason (recovery email screen)"))
            }
        }
    }

    private func confirm() {
        guard let signed = verificationToken, code.count == 6, !busy else { return }
        guard let token = SessionStore.token else { error = String(localized: "Your session has expired. Sign in again.", comment: "Error when the session has expired"); return }
        busy = true; error = nil; notice = nil
        Task {
            defer { busy = false }
            do {
                _ = try await ApiClient.confirmRecoveryEmail(token: token, verificationToken: signed, code: code)
                verificationToken = nil
                withAnimation { confirmed = true }
            } catch {
                self.error = apiMessage(error, fallback: String(localized: "That code is not correct.", comment: "Error message shown when the server gives no reason (recovery email screen)"))
                if apiCode(error) == "CODE_EXPIRED" { code = "" }
            }
        }
    }
}
