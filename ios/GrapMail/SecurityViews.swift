import SwiftUI
import UIKit

/// 01 / 02 — the hub the whole sector hangs from.
///
/// One view for both references: 02 is the same page with everything switched
/// on, so the copy follows the state instead of the layout being forked.
@MainActor
struct AccountSecurityView: View {
    let onBack: () -> Void
    let onTwoFactor: () -> Void
    let onDisableTwoFactor: () -> Void
    let onPhone: () -> Void
    let onSessions: () -> Void
    let onActivity: () -> Void
    let onBackupCodes: () -> Void
    let onPassword: () -> Void
    let onSignOutAll: () -> Void
    let onSupport: () -> Void
    // The device-security band: the one-time recovery codes the
    // all-devices-lost path spends, the trusted-device list the enrolled
    // keys live on, and the passkey credentials the platform signs with.
    var onRecoveryCodes: (() -> Void)? = nil
    var onTrustedDevices: (() -> Void)? = nil
    var onPasskeys: (() -> Void)? = nil
    /// Recovery Email row. GrapMail recovers by email only, so this replaced the
    /// phone row; `onPhone` stays for the legacy phone screens' callers.
    var onRecoveryEmail: () -> Void = {}

    private static var cachedVerdict: (generation: UUID, profile: ProfileDto)?

    static func clearCachedVerdict() { cachedVerdict = nil }

    @State private var profile: ProfileDto?
    // Without these the hub printed a confident "Off" and "No number on this
    // account yet" for the moment before the profile arrived, and for ever if
    // the request failed. A security screen may never show a guess as a fact.
    @State private var loading = true
    @State private var failed = false
    @State private var reload = 0
    // A NavigationStack destination stays in the hierarchy while a sub-flow is
    // pushed over it, so `.task(id:)` does NOT re-run when a revoke or a 2FA
    // toggle pops back to the hub: the screen would keep showing the state from
    // before the change. `.onAppear` is the one signal that fires on every
    // re-entry, so it drives a refetch after the first appearance. The first
    // appearance is skipped because `.task(id: reload)` already fetches it, and
    // firing both would fetch twice on entry.
    @State private var didAppear = false
    /// A password reset someone asked for by email, still pending (backend lib/reset-hold.ts).
    @State private var resetPending: PasswordResetStatus?
    @State private var resetNote: String?
    @State private var cancellingReset = false

    private func sectionLabel(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .gmFont(12, .semibold)
            .foregroundColor(GM.textTertiary)
            .padding(.top, 18)
            .padding(.bottom, 2)
    }

    private var twoFactorOn: Bool { profile?.twoFactorState ?? false }
    private var hasPhone: Bool { profile?.phone?.nilIfBlank() != nil }
    private var phoneVerified: Bool { hasPhone && profile?.phoneVerified == true }
    private var recoveryEmail: String? { profile?.recoveryEmail?.nilIfBlank() }
    private var recoveryVerified: Bool { recoveryEmail != nil && profile?.recoveryEmailVerified == true }
    // Secure means: a verified way back in (the recovery email) and a second factor.
    // It used to require a verified phone, which GrapMail no longer uses.
    private var secure: Bool { twoFactorOn && recoveryVerified }

    private var recoveryChip: String? {
        guard !loading, !failed else { return nil }
        if recoveryEmail == nil { return String(localized: "Add", comment: "Security hub row chip: nothing set up yet") }
        return recoveryVerified
            ? String(localized: "Verified", comment: "Security hub row chip")
            : String(localized: "Unverified", comment: "Security hub row chip")
    }

    private var phoneChip: String? {
        guard !loading, !failed else { return nil }
        if !hasPhone { return String(localized: "Add", comment: "Security hub row chip: nothing set up yet") }
        if phoneVerified { return String(localized: "Verified", comment: "Security hub row chip") }
        return profile?.phoneVerified == false ? String(localized: "Unverified", comment: "Security hub row chip") : nil
    }

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: secure ? "ill_sec_hub_secure" : "ill_sec_hub",
            illustrationWidth: 120,
            title: String(localized: "Account Security", comment: "Screen title (account security screen)"),
            subtitle: String(localized: "Manage your security settings to keep your account safe and secure.", comment: "Screen description under the title (account security screen)")
        ) {
            if let pending = resetPending {
                InfoCard {
                    StatusChip(text: String(localized: "Password reset requested", comment: "Status chip (account security screen)"), tone: .warning)
                    Text(Self.resetSentence(pending))
                        .gmFont(12)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 10)
                    AccountLink(title: cancellingReset ? String(localized: "Cancelling…", comment: "Link-style button (account security screen)") : String(localized: "Cancel the reset", comment: "Link-style button (account security screen)"), action: cancelReset)
                        .accessibilityIdentifier("sec-cancel-reset")
                }
                .padding(.bottom, 12)
            }
            if let resetNote {
                Text(resetNote)
                    .gmFont(12)
                    .foregroundColor(GM.textSecondary)
                    .padding(.bottom, 12)
            }
            // Jailbreak heuristics (DeviceIntegrityCheck.swift): warn, never block.
            JailbreakWarningCard()
            // The reference puts the account's verdict above the feature list: it
            // answers "am I safe?" before the six rows that explain why.
            InfoCard {
                if loading {
                    GMSkeletonList(rows: 2, avatar: false)
                } else if failed {
                    Text("We couldn't load your security settings.")
                        .gmFont(13)
                        .foregroundColor(GM.textSecondary)
                    AccountLink(title: String(localized: "Try again", comment: "Link-style button (account security screen)"), action: { reload += 1 })
                } else {
                    StatusChip(
                        text: secure ? String(localized: "Your account is secure", comment: "Status chip (account security screen)") : String(localized: "One thing left to do", comment: "Status chip (account security screen)"),
                        tone: secure ? .success : .warning
                    )
                    Text(secure
                         ? String(localized: "All essential security features are active and your account is protected.", comment: "Message (account security screen)")
                         : !recoveryVerified
                         ? String(localized: "Add a recovery email. It is the only way back into your account if you forget your password.", comment: "Message (account security screen)")
                         : String(localized: "Turn on two-step verification to protect your account even if your password is ever exposed.", comment: "Message (account security screen)"))
                        .gmFont(12)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 10)
                    // Two-step on with no codes is one lost phone away from a
                    // lock-out, and the badge above would still call it secure.
                    // Only say so when the server actually answered with a
                    // count: an absent field is unknown, not zero, and a false
                    // "you have no codes" is its own kind of wrong warning.
                    if twoFactorOn, let remaining = profile?.backupCodesRemaining, remaining == 0 {
                        Text("Add backup codes so losing access to your recovery email does not lock you out.")
                            .gmFont(12)
                            .foregroundColor(GM.warnInk)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 8)
                    }
                }
            }
            .padding(.bottom, 16)

            sectionLabel("Security Features")

            // The reference shows a real on/off switch on the 2FA row, not just a
            // pill. Toggling routes into the flow that proves the change (password,
            // then code) rather than flipping a flag blind.
            SecurityToggleRowView(
                icon: "shield.lefthalf.filled",
                title: String(localized: "Two-Step Verification", comment: "Settings switch row title (account security screen)"),
                subtitle: String(localized: "Add an extra layer of security to your account with a verification code.", comment: "Settings switch row description (account security screen)"),
                chip: (loading || failed) ? nil : (twoFactorOn ? String(localized: "Enabled", comment: "Settings row status chip (account security screen)") : String(localized: "Off", comment: "Settings row status chip (account security screen)")),
                chipTone: twoFactorOn ? .success : .warning,
                isOn: twoFactorOn && !loading && !failed,
                enabled: !loading && !failed,
                onToggle: { _ in twoFactorOn ? onDisableTwoFactor() : onTwoFactor() }
            )
            SecurityDivider()
            SecurityRowView(
                icon: "envelope.badge.shield.half.filled",
                title: String(localized: "Recovery Email", comment: "Settings row title (account security screen)"),
                subtitle: recoveryEmail
                    ?? (loading ? nil : String(localized: "Where your password reset link and sign-in codes are sent.", comment: "Settings row description (account security screen)")),
                chip: recoveryChip,
                chipTone: recoveryVerified ? .success : .warning,
                action: onRecoveryEmail
            )

            sectionLabel("Account Management")
            SecurityRowView(
                icon: "key",
                title: String(localized: "Change Password", comment: "Settings row title (account security screen)"),
                subtitle: String(localized: "Update your account password for better security.", comment: "Settings row description (account security screen)"),
                action: onPassword
            )
            SecurityDivider()
            SecurityRowView(
                icon: "at",
                title: String(localized: "Backup Codes", comment: "Settings row title (account security screen)"),
                subtitle: (profile?.backupCodesRemaining ?? 0) > 0
                    ? String(localized: "\(profile?.backupCodesRemaining ?? 0) codes unused.", comment: "Settings row description (account security screen)")
                    : String(localized: "Generate and manage backup codes for 2FA.", comment: "Settings row description (account security screen)"),
                action: onBackupCodes
            )
            SecurityDivider()
            // The 2FA backup codes above answer "lost phone"; these answer
            // "lost everything" — ten one-time codes that sign you back in
            // without a password at all.
            SecurityRowView(
                icon: "key.fill",
                title: String(localized: "Account Recovery Codes", comment: "Settings row title (account security screen)"),
                subtitle: String(localized: "One-time codes for signing in if you lose your password.", comment: "Settings row description (account security screen)"),
                action: onRecoveryCodes
            )
            SecurityDivider()
            SecurityRowView(
                icon: "checkmark.seal",
                title: String(localized: "Trusted Devices", comment: "Settings row title (account security screen)"),
                subtitle: String(localized: "Devices that hold a key this account trusts.", comment: "Settings row description (account security screen)"),
                action: onTrustedDevices
            )
            SecurityDivider()
            SecurityRowView(
                icon: "key.viewfinder",
                title: String(localized: "Passkeys", comment: "Settings row title (account security screen)"),
                subtitle: String(localized: "Sign in with your face, fingerprint or passcode instead of a password.", comment: "Settings row description (account security screen)"),
                action: onPasskeys
            )
            SecurityDivider()
            SecurityRowView(
                icon: "rectangle.portrait.and.arrow.right",
                title: String(localized: "Sign Out All Devices", comment: "Settings row title (account security screen)"),
                subtitle: String(localized: "Log out from all devices except this one.", comment: "Settings row description (account security screen)"),
                action: onSignOutAll
            )
            // Two rows the reference hub does not draw but the sector needs a
            // reachable entry for; kept here rather than orphaning those screens.
            SecurityDivider()
            SecurityRowView(
                icon: "laptopcomputer.and.iphone",
                title: String(localized: "Active Sessions", comment: "Settings row title (account security screen)"),
                subtitle: String(localized: "Devices signed in to this account.", comment: "Settings row description (account security screen)"),
                action: onSessions
            )
            SecurityDivider()
            SecurityRowView(
                icon: "checkmark.shield",
                title: String(localized: "Recent Security Activity", comment: "Settings row title (account security screen)"),
                subtitle: String(localized: "Sign-ins and changes to this account.", comment: "Settings row description (account security screen)"),
                action: onActivity
            )
            SecurityDivider()
            SecurityRowView(
                icon: "headphones",
                title: String(localized: "Help & Support", comment: "Settings row title (account security screen)"),
                subtitle: String(localized: "Visit our support center for security assistance.", comment: "Settings row description (account security screen)"),
                action: onSupport
            )
        } footer: {
            FooterNote(text: String(localized: "Your security matters. We're here to help.", comment: "Footer note (account security screen)"))
        }
        .task(id: reload) {
            let generation = SessionStore.generation
            let cached = Self.cachedVerdict
            let held = cached?.generation == generation ? cached?.profile : nil
            profile = held
            failed = false
            loading = held == nil
            guard let token = SessionStore.token else {
                loading = false
                failed = true
                return
            }
            do {
                let fresh = try await ApiClient.profile(token: token).profile
                guard !Task.isCancelled, SessionStore.generation == generation else { return }
                if let fresh, fresh.twoFactorState != nil {
                    Self.cachedVerdict = (generation, fresh)
                    profile = fresh
                } else {
                    failed = held == nil
                }
            } catch {
                guard !Task.isCancelled, SessionStore.generation == generation else { return }
                failed = held == nil
            }
            loading = false
        }
        .onAppear {
            if didAppear { reload += 1 } else { didAppear = true }
        }
        .task(id: reload) {
            guard let token = SessionStore.token,
                  let status = try? await ApiClient.passwordResetStatus(token: token) else { return }
            resetPending = status.success && status.pending ? status : nil
        }
        .task(id: reload) {
            // Round 8 — C: keep the safety header current (first entry and
            // every return from a sub-screen, like the profile read above).
            guard onSecurityCenter != nil else { return }
            _ = try? await safetyStore.fetch()
        }
    }

    private static func resetSentence(_ s: PasswordResetStatus) -> String {
        // Whole sentences, joined with a space, so no translated fragment has to
        // carry another's grammar.
        var parts = [String(localized: "A link to reset your password was sent to your recovery email.", comment: "Security hub: pending password reset notice")]
        if let at = s.availableAt, let date = ServerDate.parse(at) {
            let when = date.formatted(date: .abbreviated, time: .shortened)
            parts.append(String(localized: "It works from \(when).", comment: "Security hub: pending password reset notice; the argument is a date and time"))
        }
        parts.append(String(localized: "If this was not you, cancel it now and change your recovery email.", comment: "Security hub: pending password reset notice"))
        return parts.joined(separator: " ")
    }

    private func cancelReset() {
        guard !cancellingReset, let token = SessionStore.token else { return }
        cancellingReset = true
        Task {
            defer { cancellingReset = false }
            do {
                let result = try await ApiClient.cancelPasswordReset(token: token)
                if result.success {
                    resetPending = nil
                    resetNote = result.message ?? String(localized: "The password reset was cancelled.", comment: "Message (account security screen)")
                } else {
                    resetNote = result.error ?? String(localized: "The reset could not be cancelled. Try again.", comment: "Message (account security screen)")
                }
            } catch {
                resetNote = (error as? ApiError)?.userMessage ?? String(localized: "The reset could not be cancelled. Try again.", comment: "Message (account security screen)")
            }
        }
    }
}

/// 03 — what turning the factor on will ask for, before it asks for it.
struct TwoFactorEnableView: View {
    let onBack: () -> Void
    let onContinue: () -> Void

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_2fa",
            illustrationWidth: 180,
            title: String(localized: "Enable Two-Step Verification", comment: "Screen title (two factor enable screen)"),
            subtitle: String(localized: "Add an extra layer of security to your account.", comment: "Screen description under the title (two factor enable screen)")
        ) {
            InfoCard {
                NeedRow(icon: "checkmark.shield", title: String(localized: "More Protection", comment: "Feature row title (two factor enable screen)"), body: String(localized: "Prevents unauthorized access, even if your password is compromised.", comment: "Feature row description (two factor enable screen)"))
                SecurityDivider()
                NeedRow(icon: "iphone", title: String(localized: "Use Your Phone", comment: "Feature row title (two factor enable screen)"), body: String(localized: "We'll send a 6-digit code to your verified phone number.", comment: "Feature row description (two factor enable screen)"))
                SecurityDivider()
                NeedRow(icon: "lock", title: String(localized: "Easy to Manage", comment: "Feature row title (two factor enable screen)"), body: String(localized: "You can turn it off anytime from account security settings.", comment: "Feature row description (two factor enable screen)"))
            }
            HStack(alignment: .top, spacing: 9) {
                Image(systemName: "info.circle").gmFont(14).foregroundColor(GM.primarySolid)
                Text("You'll need a verified phone number to enable two-step verification.")
                    .gmFont(12)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.softFill))
            .padding(.top, 12)
        } footer: {
            VStack(spacing: 4) {
                GradientButton(title: String(localized: "Continue", comment: "Button title (two factor enable screen)"), action: onContinue)
                AccountLink(title: String(localized: "Cancel", comment: "Link-style button (two factor enable screen)"), action: onBack)
            }
        }
    }
}

private struct NeedRow: View {
    let icon: String
    let title: String
    let detail: String
    // The stored line is named `detail`, not `body`: a `body` property would
    // collide with SwiftUI's required `var body`. The initializer keeps the
    // external `body:` label so every call site is unchanged.
    init(icon: String, title: String, body: String) {
        self.icon = icon
        self.title = title
        self.detail = body
    }

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .gmFont(14)
                .foregroundColor(GM.primarySolid)
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).gmFont(13, .semibold).foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(detail).gmFont(12).foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 6)
    }
}

/// 04 — the password is proved by the server, not compared in the client.
struct ConfirmPasswordView: View {
    let onBack: () -> Void
    let onForgot: () -> Void
    let onVerified: () -> Void

    @State private var password = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_password_confirm",
            illustrationWidth: 150,
            title: String(localized: "Confirm Your Password", comment: "Screen title (confirm password screen)"),
            subtitle: String(localized: "Enter your password to continue.", comment: "Screen description under the title (confirm password screen)")
        ) {
            AppTextField(
                title: String(localized: "Password", comment: "Text field label (confirm password screen)"),
                value: $password,
                error: error,
                hint: String(localized: "Enter your password", comment: "Text field placeholder (confirm password screen)"),
                isPassword: true,
                contentType: .password
            )
        } footer: {
            VStack(spacing: 4) {
                GradientButton(
                    title: String(localized: "Continue", comment: "Button title (confirm password screen)"),
                    enabled: !password.isEmpty,
                    busy: busy,
                    action: {
                        guard let token = SessionStore.token else { error = String(localized: "Sign in again.", comment: "Message (confirm password screen)"); return }
                        busy = true; error = nil
                        Task {
                            do {
                                let resp = try await ApiClient.verifyPassword(token: token, password: password)
                                // The server hands back a short-lived proof; the
                                // enable call spends it. Without it this screen is
                                // decoration.
                                SecurityFlow.enableProof = resp.proof
                                busy = false
                                onVerified()
                            } catch {
                                busy = false
                                self.error = apiMessage(error, fallback: String(localized: "Could not complete the security request. Try again.", comment: "Error message shown when the server gives no reason (confirm password screen)"))
                            }
                        }
                    }
                )
                AccountLink(title: String(localized: "Back", comment: "Link-style button (confirm password screen)"), action: onBack)
            }
        }
    }
}

/// 05 — the code goes to the number already on the account; the factor is only
/// switched on after the code is redeemed.
struct VerifyPhoneView: View {
    @Environment(\.dynamicTypeSize) private var dts
    let onBack: () -> Void
    let onAddPhone: () -> Void
    let onVerified: () -> Void

    @State private var profile: ProfileDto?
    @State private var challengeId: String?
    @State private var expiresAt: Date?
    @State private var code = ""
    @State private var busy = false
    @State private var error: String?
    // Two dead ends the first build left open: no number to send to, and a
    // password proof that expired while the person typed.
    @State private var needsPhone = false
    @State private var needsProof = false
    @State private var needsPhoneProof = false

    private func send() async {
        guard let token = SessionStore.token else { return }
        do {
            let loaded = try await ApiClient.profile(token: token).profile
            profile = loaded
            guard let number = loaded?.phone, !number.isEmpty else {
                needsPhone = true
                error = String(localized: "Add a phone number first.", comment: "Message (verify phone screen)")
                return
            }
            let sent = try await ApiClient.startPhoneVerification(phone: number)
            challengeId = sent.challengeId
            if let millis = sent.expiresAt {
                expiresAt = Date(timeIntervalSince1970: Double(millis) / 1000)
            }
        } catch {
            self.error = apiMessage(error, fallback: String(localized: "Could not complete the security request. Try again.", comment: "Error message shown when the server gives no reason (verify phone screen)"))
        }
    }

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_phone",
            illustrationWidth: 170,
            title: String(localized: "Verify Your Phone", comment: "Screen title (verify phone screen)"),
            subtitle: (profile?.phone).map { String(localized: "We'll text a code to \($0).", comment: "Screen description under the title (verify phone screen)") }
                ?? String(localized: "We'll text a code to the number on this account.", comment: "Screen description under the title (verify phone screen)")
        ) {
            InfoCard {
                GMAdaptiveStack(spacing: 8) {
                    HStack(spacing: 8) {
                        Image(systemName: "iphone").gmFont(14).foregroundColor(GM.primarySolid)
                        Text("Phone Number").gmFont(13, .medium).foregroundColor(GM.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if !dts.isAccessibilitySize { Spacer() }
                    StatusChip(text: String(localized: "Verified", comment: "Status chip (verify phone screen)"), tone: .success)
                }
                Text(profile?.phone ?? "—")
                    .gmFont(16, .semibold)
                    .foregroundColor(GM.textPrimary)
                    .padding(.top, 4)
                Text("Verification Code")
                    .gmFont(13, .medium)
                    .foregroundColor(GM.textSecondary)
                    .padding(.top, 14)
                CodeCells(length: 6, value: $code)
                    // Round 8 — B: the code is offered above the keyboard.
                    .textContentType(.oneTimeCode)
                    .padding(.top, 6)
            }
            CodeExpiry(expiresAt: expiresAt)
                .padding(.top, 10)
            HStack(alignment: .top, spacing: 9) {
                Image(systemName: "info.circle").gmFont(14).foregroundColor(GM.primarySolid)
                Text("Didn't receive the code? Check your spam folder or make sure your phone is on and has signal.")
                    .gmFont(12)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.softFill))
            .padding(.top, 10)
            if let error {
                Text(error).gmFont(12).foregroundColor(GM.error).padding(.top, 6)
            }
            if needsPhone {
                AccountLink(title: String(localized: "Add a phone number", comment: "Link-style button (verify phone screen)"), action: onAddPhone)
            }
            if needsProof {
                AccountLink(title: String(localized: "Enter your password again", comment: "Link-style button (verify phone screen)"), action: onBack)
            }
            if needsPhoneProof {
                AccountLink(title: String(localized: "Send me a new code", comment: "Link-style button (verify phone screen)"), action: {
                    needsPhoneProof = false
                    error = nil
                    Task { await send() }
                })
            }
        } footer: {
            VStack(spacing: 4) {
                GradientButton(
                    title: String(localized: "Continue", comment: "Button title (verify phone screen)"),
                    enabled: code.count == 6 && challengeId != nil,
                    busy: busy,
                    action: {
                        guard let id = challengeId, let token = SessionStore.token else { return }
                        busy = true; error = nil
                        Task {
                            do {
                                let verified = try await ApiClient.verifyPhone(challengeId: id, code: code, token: token)
                                // The code's own proof, spent by the enable call.
                                // Without it the server refuses the change, so the
                                // phone step is part of the deal rather than part of
                                // the choreography.
                                SecurityFlow.phoneProof = verified.phoneProof
                                _ = try await ApiClient.enableTwoFactor(token: token, proof: SecurityFlow.enableProof, phoneProof: SecurityFlow.phoneProof)
                                SecurityFlow.enableProof = nil
                                SecurityFlow.phoneProof = nil
                                busy = false
                                onVerified()
                            } catch let apiError as ApiError {
                                // Both proofs are single-use and the server checks the
                                // password proof first, so any failure has already spent
                                // it. Clearing only the proof the branch named left a
                                // dead enable-2fa token that the next retry re-sent and
                                // failed identically. Clear both and route back to the
                                // step that mints a fresh one. The error-code binding is
                                // named `errCode`, not `code`, so it does not shadow the
                                // view's `code` state that the retry below clears.
                                if case let .http(_, errCode, _) = apiError, errCode == "PROOF_REQUIRED" {
                                    needsProof = true
                                    SecurityFlow.enableProof = nil
                                    SecurityFlow.phoneProof = nil
                                }
                                if case let .http(_, errCode, _) = apiError, errCode == "PHONE_PROOF_REQUIRED" {
                                    needsProof = true
                                    challengeId = nil
                                    code = ""
                                    SecurityFlow.enableProof = nil
                                    SecurityFlow.phoneProof = nil
                                }
                                busy = false
                                self.error = apiError.localizedDescription
                            } catch {
                                busy = false
                                self.error = apiMessage(error, fallback: String(localized: "Could not complete the security request. Try again.", comment: "Error message shown when the server gives no reason (verify phone screen)"))
                            }
                        }
                    }
                )
                AccountLink(title: String(localized: "Resend code", comment: "Link-style button (verify phone screen)"), action: {
                    error = nil
                    Task { await send() }
                })
            }
        }
        .task { await send() }
    }
}

/// 06 — the state the person just earned, with what to do next.
struct TwoFactorEnabledView: View {
    let onContinue: () -> Void
    let onBackHome: () -> Void

    var body: some View {
        AccountScreen(
            onBack: onContinue,
            illustration: "ill_sec_2fa_on",
            illustrationWidth: 160,
            title: String(localized: "Two-Step Verification Enabled", comment: "Screen title (two factor enabled screen)"),
            subtitle: String(localized: "Your account is now protected with an extra layer of security.", comment: "Screen description under the title (two factor enabled screen)")
        ) {
            StatusChip(text: String(localized: "2-Step Verification Enabled", comment: "Status chip (two factor enabled screen)"), tone: .success)
            InfoCard {
                Text("What's Next?")
                    .gmFont(14, .semibold)
                    .foregroundColor(GM.textPrimary)
                NeedRow(icon: "checkmark.shield", title: String(localized: "Keep Backup Codes Safe", comment: "Feature row title (two factor enabled screen)"), body: String(localized: "Use them if you ever lose access to your phone.", comment: "Feature row description (two factor enabled screen)"))
                NeedRow(icon: "iphone", title: String(localized: "Update Your Phone Number", comment: "Feature row title (two factor enabled screen)"), body: String(localized: "Keep it current so codes always reach you.", comment: "Feature row description (two factor enabled screen)"))
            }
            .padding(.top, 16)
            Text("You'll be asked for a code each time you sign in on a new device.")
                .gmFont(12)
                .foregroundColor(GM.textSecondary)
                .padding(.top, 12)
        } footer: {
            VStack(spacing: 4) {
                GradientButton(title: String(localized: "Continue", comment: "Button title (two factor enabled screen)"), action: onContinue)
                AccountLink(title: String(localized: "Back to Security Settings", comment: "Link-style button (two factor enabled screen)"), action: onBackHome)
            }
        }
    }
}

/// 09 — weakening the account needs the same proof as strengthening it.
struct DisableTwoFactorView: View {
    let onBack: () -> Void
    let onDisabled: () -> Void

    @State private var password = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_2fa_off_confirm",
            illustrationWidth: 150,
            title: String(localized: "Disable Two-Step Verification?", comment: "Screen title (disable two factor screen)"),
            subtitle: String(localized: "This will remove the extra layer of security from your account.", comment: "Screen description under the title (disable two factor screen)")
        ) {
            AppTextField(
                title: String(localized: "Password", comment: "Text field label (disable two factor screen)"),
                value: $password,
                error: error,
                hint: String(localized: "Enter your password", comment: "Text field placeholder (disable two factor screen)"),
                isPassword: true,
                contentType: .password
            )
            HStack(alignment: .top, spacing: 9) {
                Image(systemName: "checkmark.shield").gmFont(14).foregroundColor(GM.primarySolid)
                Text("Disabling two-step verification will make your account less secure. You can enable it again anytime from Account Security settings.")
                    .gmFont(12)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.softFill))
            .padding(.top, 12)
        } footer: {
            VStack(spacing: 6) {
                GradientButton(
                    title: String(localized: "Continue", comment: "Button title (disable two factor screen)"),
                    enabled: !password.isEmpty,
                    busy: busy,
                    action: {
                        guard let token = SessionStore.token else { return }
                        busy = true; error = nil
                        Task {
                            do {
                                _ = try await ApiClient.disableTwoFactor(token: token, password: password)
                                busy = false
                                onDisabled()
                            } catch {
                                busy = false
                                self.error = apiMessage(error, fallback: String(localized: "Could not complete the security request. Try again.", comment: "Error message shown when the server gives no reason (disable two factor screen)"))
                            }
                        }
                    }
                )
                AccountLink(title: String(localized: "Back", comment: "Link-style button (disable two factor screen)"), action: onBack)
            }
        }
    }
}

/// 10 — the confirmation, with an honest explanation of the new posture.
struct TwoFactorDisabledView: View {
    let onDone: () -> Void
    let onSecureNow: () -> Void

    var body: some View {
        AccountScreen(
            onBack: onDone,
            illustration: "ill_sec_2fa_off",
            illustrationWidth: 160,
            title: String(localized: "Two-Step Verification Disabled", comment: "Screen title (two factor disabled screen)"),
            subtitle: String(localized: "Two-step verification has been disabled. Your account is now protected by your password only.", comment: "Screen description under the title (two factor disabled screen)")
        ) {
            InfoCard {
                Text("What happens now?")
                    .gmFont(14, .semibold)
                    .foregroundColor(GM.textPrimary)
                NeedRow(icon: "key", title: String(localized: "Password Only", comment: "Feature row title (two factor disabled screen)"), body: String(localized: "Signing in no longer asks for a code.", comment: "Feature row description (two factor disabled screen)"))
                NeedRow(icon: "message", title: String(localized: "Backup Codes Retired", comment: "Feature row title (two factor disabled screen)"), body: String(localized: "The codes you saved no longer apply.", comment: "Feature row description (two factor disabled screen)"))
            }
            Text("You can turn two-step verification back on at any time from Account Security.")
                .gmFont(12)
                .foregroundColor(GM.textSecondary)
                .padding(.top, 12)
        } footer: {
            VStack(spacing: 4) {
                GradientButton(title: String(localized: "Continue", comment: "Button title (two factor disabled screen)"), action: onDone)
                AccountLink(title: String(localized: "Turn it back on", comment: "Link-style button (two factor disabled screen)"), action: onSecureNow)
            }
        }
    }
}

/// 07 — generate the ten one-time codes. Generation is the moment the old set
/// dies, so it is a deliberate press with the consequence written above it.
struct GenerateBackupCodesView: View {
    let onBack: () -> Void
    let onGenerated: ([String]) -> Void

    @State private var busy = false
    @State private var error: String?

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_backup_generate",
            illustrationWidth: 180,
            title: String(localized: "Generate & Save Backup Codes", comment: "Screen title (generate backup codes screen)"),
            subtitle: String(localized: "Create one-time backup codes to access your account if you lose your phone.", comment: "Screen description under the title (generate backup codes screen)")
        ) {
            InfoCard {
                Text("What's Included")
                    .gmFont(14, .semibold)
                    .foregroundColor(GM.textPrimary)
                NeedRow(icon: "number", title: String(localized: "10 One-Time Codes", comment: "Feature row title (generate backup codes screen)"), body: String(localized: "Each code can be used once.", comment: "Feature row description (generate backup codes screen)"))
                NeedRow(icon: "lock", title: String(localized: "Use When Needed", comment: "Feature row title (generate backup codes screen)"), body: String(localized: "Sign in when your phone is unavailable.", comment: "Feature row description (generate backup codes screen)"))
                NeedRow(icon: "checkmark.shield", title: String(localized: "Each Code is Unique", comment: "Feature row title (generate backup codes screen)"), body: String(localized: "Generating a new set retires the old one.", comment: "Feature row description (generate backup codes screen)"))
            }
            Text("Store the codes somewhere only you can reach. Anyone with a code can pass your second factor.")
                .gmFont(12)
                .foregroundColor(GM.textSecondary)
                .padding(.top, 12)
            if let error {
                Text(error).gmFont(12).foregroundColor(GM.error).padding(.top, 6)
            }
        } footer: {
            VStack(spacing: 4) {
                GradientButton(
                    title: String(localized: "Generate Codes", comment: "Button title (generate backup codes screen)"),
                    busy: busy,
                    action: {
                        guard let token = SessionStore.token else { error = String(localized: "Sign in again.", comment: "Message (generate backup codes screen)"); return }
                        busy = true; error = nil
                        Task {
                            do {
                                let resp = try await ApiClient.generateBackupCodes(token: token)
                                busy = false
                                onGenerated(resp.newBackupCodes ?? [])
                            } catch {
                                busy = false
                                self.error = apiMessage(error, fallback: String(localized: "Could not complete the security request. Try again.", comment: "Error message shown when the server gives no reason (generate backup codes screen)"))
                            }
                        }
                    }
                )
                AccountLink(title: String(localized: "Cancel", comment: "Link-style button (generate backup codes screen)"), action: onBack)
            }
        }
    }
}

/// 08 — the only time the codes exist in readable form. The server keeps
/// hashes, so there is no "view them again" path anywhere in the product.
struct BackupCodesSavedView: View {
    @Environment(\.dynamicTypeSize) private var dts
    @ScaledMetric(relativeTo: .body) private var buttonHeight: CGFloat = 54
    let codes: [String]
    let onDone: () -> Void

    @State private var copied = false
    @State private var copiedCode: String?
    // Codes are shown once. Leaving without saving them is a real loss, so it
    // takes an explicit confirmation rather than a tap on the nearest button.
    @State private var confirmed = false
    @State private var showLeave = false

    /// Two codes a row; one at the accessibility sizes, where two monospaced
    /// codes no longer fit side by side.
    private var perRow: Int { dts.isAccessibilitySize ? 1 : 2 }
    private var grid: [[String]] { stride(from: 0, to: codes.count, by: perRow).map { Array(codes[$0..<min($0 + perRow, codes.count)]) } }

    var body: some View {
        AccountScreen(
            onBack: { if confirmed { onDone() } else { showLeave = true } },
            illustration: "ill_sec_backup_saved",
            illustrationWidth: 150,
            title: String(localized: "Backup Codes Saved", comment: "Screen title (backup codes saved screen)"),
            subtitle: String(localized: "Store these codes somewhere safe. They won't be shown again.", comment: "Screen description under the title (backup codes saved screen)")
        ) {
            InfoCard {
                GMAdaptiveStack(spacing: dts.isAccessibilitySize ? 8 : nil) {
                    Text("Your Backup Codes")
                        .gmFont(14, .semibold)
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    if !dts.isAccessibilitySize { Spacer() }
                    Button(action: {
                        UIPasteboard.general.string = codes.joined(separator: "\n")
                        copied = true
                    }) {
                        HStack(spacing: 5) {
                            Image(systemName: copied ? "checkmark.circle" : "doc.on.doc")
                                .gmFont(12, .semibold)
                            (copied ? Text("Copied") : Text("Copy all"))
                                .gmFont(12, .semibold)
                        }
                        .foregroundColor(GM.primarySolid)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.bottom, 12)
                VStack(spacing: 10) {
                    ForEach(Array(grid.enumerated()), id: \.offset) { _, pair in
                        HStack(spacing: 12) {
                            ForEach(pair, id: \.self) { code in
                                // Per-code copy: grabbing one code to use now should
                                // not require copying all ten.
                                Button { UIPasteboard.general.string = code; copiedCode = code } label: {
                                    HStack(spacing: 6) {
                                        Text(code)
                                            .gmFont(13, .semibold, design: .monospaced)
                                            .foregroundColor(GM.textPrimary)
                                            .lineLimit(1)
                                            .minimumScaleFactor(0.7)
                                        Image(systemName: copiedCode == code ? "checkmark.circle" : "doc.on.doc")
                                            .gmFont(11)
                                            .foregroundColor(GM.textTertiary)
                                        Spacer(minLength: 0)
                                    }
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 9)
                                    .padding(.horizontal, 8)
                                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(GM.softFill))
                                }
                                .buttonStyle(.plain)
                            }
                            if pair.count == 1 && perRow == 2 { Spacer().frame(maxWidth: .infinity) }
                        }
                    }
                }
            }
            InfoCard {
                NeedRow(icon: "lock", title: String(localized: "Store Safely", comment: "Feature row title (backup codes saved screen)"), body: String(localized: "Use a password manager or print them out.", comment: "Feature row description (backup codes saved screen)"))
                SecurityDivider()
                NeedRow(icon: "nosign", title: String(localized: "Don't Share", comment: "Feature row title (backup codes saved screen)"), body: String(localized: "Backup codes are for your eyes only.", comment: "Feature row description (backup codes saved screen)"))
                SecurityDivider()
                NeedRow(icon: "info.circle", title: String(localized: "One Time Use", comment: "Feature row title (backup codes saved screen)"), body: String(localized: "Each code can only be used once.", comment: "Feature row description (backup codes saved screen)"))
            }
            .padding(.top, 12)
            Text("Copy them into your password manager, or write them down.")
                .gmFont(12)
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 12)
            Button {
                confirmed.toggle()
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: confirmed ? "checkmark.square.fill" : "square")
                        .gmFont(18)
                        .foregroundColor(confirmed ? GM.primarySolid : GM.textTertiary)
                    Text("I have saved these codes somewhere safe.")
                        .gmFont(13)
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }
            }
            .buttonStyle(.plain)
            .padding(.top, 14)
        } footer: {
            VStack(spacing: 6) {
                // A real export: the codes exist in readable form only here, so a
                // "Download" that did nothing left backgrounding the app as an
                // unrecoverable loss. ShareLink writes the list to Files, Notes, or a
                // password manager.
                ShareLink(item: codes.joined(separator: "\n")) {
                    HStack(spacing: 6) {
                        Image(systemName: "square.and.arrow.down")
                        Text("Download Backup Codes")
                            .multilineTextAlignment(.center)
                    }
                    .gmFont(16, .semibold)
                    .foregroundColor(.white)
                    .padding(.horizontal, 12)
                    .frame(maxWidth: .infinity)
                    // Capped, not floored: a bare floor grows into any
                    // leftover a parent stack hands out (frame 76's Send
                    // block, same class). 54 is the design's height at the
                    // default text size; it scales with the label.
                    .frame(minHeight: buttonHeight, maxHeight: buttonHeight)
                    .background(RoundedRectangle(cornerRadius: GMShape.button, style: .continuous).fill(GM.primarySolid))
                }
                GradientButton(title: String(localized: "Done", comment: "Button title (backup codes saved screen)"), enabled: confirmed, action: onDone)
                FooterNote(text: String(localized: "Each code can be used once. Generating new codes retires every code on this list.", comment: "Footer note (backup codes saved screen)"))
            }
        }
        .alert("These codes will not be shown again", isPresented: $showLeave) {
            Button("Leave anyway", role: .destructive) { onDone() }
            Button("Stay", role: .cancel) { }
        } message: {
            Text("Leave now and the list is gone. You can generate a new set later, but the old codes stop working.")
        }
        // The header chevron is guarded, but the interactive pop gesture is not the
        // navigation bar: without this a right-edge swipe left the screen with no
        // confirmation, making the guard decorative. Android closed the same hole
        // with a BackHandler.
        .interactiveDismissDisabled(!confirmed)
    }
}

/// 11 / 23 — change the password. One view, two entry points: the ordinary
/// change and the one the alert demands. Only the framing differs.
struct ChangePasswordView: View {
    let afterSuspicious: Bool
    let onBack: () -> Void
    let onForgot: () -> Void
    let onChanged: () -> Void

    @State private var current = ""
    @State private var next = ""
    @State private var confirm = ""
    @State private var busy = false
    @State private var error: String?

    private var longEnough: Bool { next.count >= 8 }
    // ASCII classes, matching `passwordProblem` on the server exactly. Swift's
    // isLetter/isNumber are Unicode-aware, so a password of `π²²²²²²²²` ticked
    // every box here and was then refused by the route.
    private var hasLetter: Bool { next.contains { ($0 >= "A" && $0 <= "Z") || ($0 >= "a" && $0 <= "z") } }
    private var hasNumber: Bool { next.contains { $0 >= "0" && $0 <= "9" } }
    private var hasSymbol: Bool { next.contains { !($0 >= "A" && $0 <= "Z") && !($0 >= "a" && $0 <= "z") && !($0 >= "0" && $0 <= "9") } }
    private var rulesMet: Bool { longEnough && hasLetter && hasNumber && hasSymbol }
    private var matches: Bool { !next.isEmpty && next == confirm }

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: afterSuspicious ? "ill_sec_password_after" : "ill_sec_password",
            illustrationWidth: afterSuspicious ? 180 : 150,
            eyebrow: afterSuspicious ? String(localized: "After Suspicious Activity", comment: "Small label above the screen title (change password screen)") : nil,
            title: afterSuspicious ? String(localized: "Change Your Password", comment: "Screen title (change password screen)") : String(localized: "Change Password", comment: "Screen title (change password screen)"),
            subtitle: afterSuspicious
                ? String(localized: "Your account was accessed from an unfamiliar device. To keep your GrapMail account secure, please update your password now.", comment: "Screen description under the title (change password screen)")
                : String(localized: "Choose a strong password you have not used here before.", comment: "Screen description under the title (change password screen)")
        ) {
            if afterSuspicious {
                HStack(alignment: .top, spacing: 9) {
                    Image(systemName: "exclamationmark.triangle").gmFont(14).foregroundColor(GM.error)
                    Text("Unrecognized activity was found on your account.")
                        .gmFont(12)
                        .foregroundColor(GM.error)
                    Spacer(minLength: 0)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.error.opacity(0.08)))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(GM.error.opacity(0.35)))
                .padding(.bottom, 14)
            }
            // Round 8 — B: the signed-in address, so the password manager
            // updates this account's saved password.
            if let username = SessionStore.address?.nilIfBlank() {
                CredentialUsernameField(username: username)
            }
            AppTextField(title: String(localized: "Current Password", comment: "Text field label (change password screen)"), value: $current, isPassword: true, leadingIcon: "lock", contentType: .password)
            AppTextField(title: String(localized: "New Password", comment: "Text field label (change password screen)"), value: $next, isPassword: true, leadingIcon: "lock", contentType: .newPassword)
                .padding(.top, 12)
            AppTextField(
                title: String(localized: "Confirm New Password", comment: "Text field label (change password screen)"),
                value: $confirm,
                error: (!confirm.isEmpty && !matches) ? String(localized: "Passwords do not match.", comment: "Text field error (change password screen)") : nil,
                isPassword: true,
                leadingIcon: "lock",
                contentType: .newPassword
            )
            .padding(.top, 12)
            InfoCard {
                Text("Password Requirements")
                    .gmFont(13, .semibold)
                    .foregroundColor(GM.textPrimary)
                RuleLine(label: String(localized: "At least 8 characters", comment: "Password requirement (change password screen)"), met: longEnough)
                RuleLine(label: String(localized: "Contains a letter", comment: "Password requirement (change password screen)"), met: hasLetter)
                RuleLine(label: String(localized: "Contains a number", comment: "Password requirement (change password screen)"), met: hasNumber)
                RuleLine(label: String(localized: "Contains a symbol", comment: "Password requirement (change password screen)"), met: hasSymbol)
            }
            .padding(.top, 14)
            if let error {
                Text(error).gmFont(12).foregroundColor(GM.error).padding(.top, 6)
            }
        } footer: {
            VStack(spacing: 4) {
                GradientButton(
                    title: afterSuspicious ? String(localized: "Update Password", comment: "Button title (change password screen)") : String(localized: "Change Password", comment: "Button title (change password screen)"),
                    enabled: !current.isEmpty && rulesMet && matches,
                    busy: busy,
                    action: {
                        guard let token = SessionStore.token else { error = String(localized: "Sign in again.", comment: "Message (change password screen)"); return }
                        busy = true; error = nil
                        Task {
                            do {
                                let generation = SessionStore.generation
                                let resp = try await ApiClient.changePassword(token: token, current: current, next: next)
                                guard generation == SessionStore.generation else {
                                    busy = false
                                    return
                                }
                                if let fresh = resp.accessToken {
                                    SessionStore.updateTokens(
                                        token: fresh,
                                        refreshToken: resp.refreshToken,
                                        expiresAtRaw: resp.tokenExpiresAtMillis
                                    )
                                }
                                busy = false
                                onChanged()
                            } catch {
                                busy = false
                                self.error = apiMessage(error, fallback: String(localized: "Could not complete the security request. Try again.", comment: "Error message shown when the server gives no reason (change password screen)"))
                            }
                        }
                    }
                )
                AccountLink(title: String(localized: "Back", comment: "Link-style button (change password screen)"), action: onBack)
            }
        }
    }
}

/// 12 / 24 — the confirmation. It says every other device is signed out, so
/// the person is not left to discover that device by device.
struct PasswordChangedView: View {
    let updated: Bool
    let onDone: () -> Void
    let onSupport: () -> Void

    var body: some View {
        AccountScreen(
            onBack: onDone,
            illustration: updated ? "ill_sec_password_updated" : "ill_sec_password_ok",
            illustrationWidth: 170,
            title: updated ? String(localized: "Password Updated Successfully", comment: "Screen title (password changed screen)") : String(localized: "Password Changed Successfully", comment: "Screen title (password changed screen)"),
            subtitle: String(localized: "Your password has been updated successfully. Other devices were signed out; this one stays signed in.", comment: "Screen description under the title (password changed screen)")
        ) {
            InfoCard {
                Text("What's Next?")
                    .gmFont(14, .semibold)
                    .foregroundColor(GM.textPrimary)
                NeedRow(icon: "lock", title: String(localized: "This device stays signed in", comment: "Feature row title (password changed screen)"), body: String(localized: "You can carry on where you are.", comment: "Feature row description (password changed screen)"))
                NeedRow(
                    icon: "laptopcomputer.and.iphone",
                    title: String(localized: "Other devices are signed out", comment: "Feature row title (password changed screen)"),
                    body: String(localized: "You'll need to sign in again on them, including mobile apps, desktop, and other browsers.", comment: "Feature row description (password changed screen)")
                )
            }
            HStack(alignment: .top, spacing: 9) {
                Image(systemName: "info.circle").gmFont(14).foregroundColor(GM.primarySolid)
                Text("This helps keep your account secure and prevents unauthorized access.")
                    .gmFont(12)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.softFill))
            .padding(.top, 12)
        } footer: {
            VStack(spacing: 6) {
                GradientButton(title: String(localized: "Continue", comment: "Button title (password changed screen)"), action: onDone)
                FooterNote(
                    text: String(localized: "If you did not make this change, contact the support team immediately.", comment: "Footer note (password changed screen)"),
                    linkText: String(localized: "support team", comment: "Tappable words in the footer note; must appear verbatim in the note's translation (password changed screen)"),
                    onLink: onSupport
                )
            }
        }
    }
}

/// 13 — sign out everywhere else. Deliberately not instant: it is a lock-out.
struct SignOutAllDevicesView: View {
    let onBack: () -> Void
    let onDone: () -> Void

    @State private var busy = false
    @State private var error: String?

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_signout_all",
            illustrationWidth: 180,
            title: String(localized: "Sign Out All Devices", comment: "Screen title (sign out all devices screen)"),
            subtitle: String(localized: "This will sign you out of all devices except this one.", comment: "Screen description under the title (sign out all devices screen)")
        ) {
            InfoCard {
                Text("You'll stay signed in here")
                    .gmFont(14, .semibold)
                    .foregroundColor(GM.textPrimary)
                Text("Every other phone, tablet and browser is signed out immediately. This device keeps working, so you can carry on.")
                    .gmFont(13)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 6)
            }
            Text("Use this when a device is lost, borrowed or sold — not instead of changing a leaked password.")
                .gmFont(12)
                .foregroundColor(GM.textSecondary)
                .padding(.top, 12)
            if let error {
                Text(error).gmFont(12).foregroundColor(GM.error).padding(.top, 6)
            }
        } footer: {
            VStack(spacing: 4) {
                GradientButton(
                    title: String(localized: "Sign Out All Devices", comment: "Button title (sign out all devices screen)"),
                    busy: busy,
                    action: {
                        guard let token = SessionStore.token else { return }
                        busy = true; error = nil
                        Task {
                            do {
                                let generation = SessionStore.generation
                                let resp = try await ApiClient.signOutEverywhere(token: token)
                                guard generation == SessionStore.generation else {
                                    busy = false
                                    return
                                }
                                if let fresh = resp.accessToken {
                                    SessionStore.updateTokens(
                                        token: fresh,
                                        refreshToken: resp.refreshToken,
                                        expiresAtRaw: resp.tokenExpiresAtMillis
                                    )
                                }
                                busy = false
                                onDone()
                            } catch {
                                busy = false
                                self.error = apiMessage(error, fallback: String(localized: "Could not complete the security request. Try again.", comment: "Error message shown when the server gives no reason (sign out all devices screen)"))
                            }
                        }
                    }
                )
                AccountLink(title: String(localized: "Cancel", comment: "Link-style button (sign out all devices screen)"), action: onBack)
            }
        }
    }
}

/// 14 — done.
struct SignedOutAllDevicesView: View {
    let onDone: () -> Void

    var body: some View {
        AccountScreen(
            onBack: onDone,
            illustration: "ill_sec_signout_all_ok",
            illustrationWidth: 170,
            title: String(localized: "Signed Out of All Devices", comment: "Screen title (signed out all devices screen)"),
            subtitle: String(localized: "You have been signed out of all devices except this one.", comment: "Screen description under the title (signed out all devices screen)")
        ) {
            InfoCard {
                Text("What happens next?")
                    .gmFont(14, .semibold)
                    .foregroundColor(GM.textPrimary)
                Text("This device stays signed in. Every other device has to sign in again, and anyone who had a session can no longer read this mailbox.")
                    .gmFont(13)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 6)
            }
            StatusChip(text: String(localized: "Only this device is signed in", comment: "Status chip (signed out all devices screen)"), tone: .success)
                .padding(.top, 12)
        } footer: {
            VStack(spacing: 6) {
                GradientButton(title: String(localized: "Continue", comment: "Button title (signed out all devices screen)"), action: onDone)
                FooterNote(text: String(localized: "Anyone using this account on another device now needs your password again.", comment: "Footer note (signed out all devices screen)"))
            }
        }
    }
}

/// 15 — the real device list, current one marked.
struct ManageSessionsView: View {
    @Environment(\.dynamicTypeSize) private var dts
    let onBack: () -> Void
    let onRevoke: (SessionDto) -> Void
    let onRevokeAlert: (SessionDto) -> Void
    let onSignOutAll: () -> Void

    @State private var sessions: [SessionDto] = []
    @State private var activeCount = 0
    @State private var loading = true
    @State private var error: String?
    /// Bumped by Try again; drives the `.task(id:)` read below.
    @State private var sessionsReload = 0
    /// The device behind an open alert gets the stronger confirmation (21)
    /// rather than the ordinary one (16): it is the device the person came here
    /// to deal with, and that copy names the activity.
    @State private var flaggedDeviceId = ""

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_sessions",
            illustrationWidth: 170,
            title: String(localized: "Manage Active Sessions", comment: "Screen title (manage sessions screen)"),
            subtitle: String(localized: "Devices currently signed in to this account.", comment: "Screen description under the title (manage sessions screen)")
        ) {
            if loading {
                GMSkeletonList(rows: 3)
            } else if let error {
                Text(error).gmFont(13).foregroundColor(GM.error)
                AccountLink(title: String(localized: "Try again"), action: { sessionsReload += 1 })
            } else if sessions.isEmpty {
                Text("No sessions are recorded yet.").gmFont(13).foregroundColor(GM.textSecondary)
            } else {
                VStack(spacing: 10) {
                    // "N Active Sessions" + Revoke All, as the reference header. The
                    // count is the server's true total; Revoke All routes to the
                    // confirm screen, since signing out everywhere is a lock-out.
                    GMAdaptiveStack(spacing: 8) {
                        HStack(spacing: 8) {
                            Circle().fill(GM.success).frame(width: 8, height: 8)
                            Text("\(activeCount) Active Sessions")
                                .gmFont(14, .semibold)
                                .foregroundColor(GM.textPrimary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        if !dts.isAccessibilitySize { Spacer(minLength: 0) }
                        Button(action: onSignOutAll) {
                            Text("Revoke All").gmFont(13, .semibold).foregroundColor(GM.primarySolid)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.bottom, 2)
                    ForEach(sessions) { session in
                        SessionCardView(
                            session: session,
                            onRevoke: revokeAction(for: session)
                        )
                    }
                }
                HStack(alignment: .top, spacing: 9) {
                    Image(systemName: "info.circle").gmFont(14).foregroundColor(GM.primarySolid)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Keep Your Account Secure").gmFont(13, .semibold).foregroundColor(GM.textPrimary)
                        Text("If you see a device you don't recognize, revoke the session immediately. This will prevent unauthorized access.")
                            .gmFont(12)
                            .foregroundColor(GM.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.softFill))
                .padding(.top, 12)
            }
        } footer: {
            AccountLink(title: String(localized: "Sign out all devices", comment: "Link-style button (manage sessions screen)"), action: onSignOutAll)
        }
        .task(id: sessionsReload) {
            loading = true
            error = nil
            // No session is a failed read, not an empty list.
            guard let token = SessionStore.token else { self.error = String(localized: "Sign in again."); loading = false; return }
            do {
                let resp = try await ApiClient.sessions(token: token)
                sessions = resp.sessions
                activeCount = resp.activeCount > 0 ? resp.activeCount : resp.sessions.count
                if let activity = try? await ApiClient.activity(token: token), activity.suspicious {
                    // The server names the open alert; re-filtering the whole trail
                    // would flag a dismissed or stale sign-in as the break-in device.
                    flaggedDeviceId = activity.suspiciousEvent?.deviceId ?? ""
                }
            } catch {
                self.error = apiMessage(error, fallback: String(localized: "Could not complete the security request. Try again.", comment: "Error message shown when the server gives no reason (manage sessions screen)"))
            }
            loading = false
        }
    }

    /// The current device has no revoke affordance: revoking it would sign the
    /// person out mid-screen with no explanation. Returning nil is how the card
    /// knows to hide the action.
    private func revokeAction(for session: SessionDto) -> (() -> Void)? {
        guard !session.current else { return nil }
        if !flaggedDeviceId.isEmpty && session.deviceId == flaggedDeviceId {
            return { onRevokeAlert(session) }
        }
        return { onRevoke(session) }
    }
}

/// 16 — a plain confirmation for a session the person picked themselves.
struct RevokeSessionView: View {
    let sessionId: String
    /// The device label the caller already holds: it rendered the row this was
    /// opened from. The screen used to refetch the entire session list to look
    /// this one string up, so a slow or failed list call left the card saying
    /// "That device" about a device whose name was on screen a moment before —
    /// and made the confirmation impossible to render without the network.
    let device: String
    let onBack: () -> Void
    let onRevoked: (String) -> Void

    @State private var busy = false
    @State private var error: String?

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_session_revoke",
            illustrationWidth: 170,
            title: String(localized: "Revoke Session Access?", comment: "Screen title (revoke session screen)"),
            subtitle: String(localized: "This device will be signed out of your GrapMail account and will need to sign in again.", comment: "Screen description under the title (revoke session screen)")
        ) {
            InfoCard {
                HStack(spacing: 10) {
                    Image(systemName: "laptopcomputer.and.iphone").gmFont(15).foregroundColor(GM.primarySolid)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Device").gmFont(12).foregroundColor(GM.textSecondary)
                        Text(device.isEmpty ? String(localized: "That device", comment: "Session revocation: stands in for an unnamed device") : device)
                            .gmFont(14, .semibold)
                            .foregroundColor(GM.textPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                }
            }
            CannotBeUndoneNote()
                .padding(.top, 12)
            if let error {
                Text(error).gmFont(12).foregroundColor(GM.error).padding(.top, 6)
            }
        } footer: {
            VStack(spacing: 6) {
                GradientButton(
                    title: String(localized: "Revoke Session", comment: "Button title (revoke session screen)"),
                    busy: busy,
                    action: { revoke() }
                )
                AccountLink(title: String(localized: "Cancel", comment: "Link-style button (revoke session screen)"), action: onBack)
            }
        }
    }

    private func revoke() {
        guard let token = SessionStore.token else { return }
        busy = true; error = nil
        Task {
            do {
                _ = try await ApiClient.revokeSession(token: token, id: sessionId)
                busy = false
                onRevoked(device)
            } catch {
                busy = false
                self.error = apiMessage(error, fallback: String(localized: "Could not complete the security request. Try again.", comment: "Error message shown when the server gives no reason (revoke session screen)"))
            }
        }
    }
}

/// 21 — the same revoke, entered from the alert, so the danger is named.
struct ConfirmSessionRevocationView: View {
    let sessionId: String
    /// See `RevokeSessionView.device`: the caller already has the label, so the
    /// screen must not refetch the session list to print one line of it.
    let device: String
    let onBack: () -> Void
    let onRevoked: (String) -> Void

    @State private var busy = false
    @State private var error: String?

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_revoke_confirm",
            illustrationWidth: 170,
            eyebrow: String(localized: "Suspicious Access Detected", comment: "Small label above the screen title (confirm session revocation screen)"),
            title: String(localized: "Confirm Session Revocation", comment: "Screen title (confirm session revocation screen)"),
            subtitle: String(localized: "This will immediately sign this session out and no longer have access to your GrapMail account.", comment: "Screen description under the title (confirm session revocation screen)")
        ) {
            InfoCard {
                HStack(alignment: .top, spacing: 9) {
                    Image(systemName: "exclamationmark.triangle").gmFont(14).foregroundColor(GM.error)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Unverified activity")
                            .gmFont(13, .semibold)
                            .foregroundColor(GM.textPrimary)
                        Text(device.isEmpty
                             ? String(localized: "This session showed activity that did not match your usual sign-in pattern.", comment: "Session revocation: why the session is flagged")
                             : String(localized: "\(device) · This session showed activity that did not match your usual sign-in pattern.", comment: "Session revocation: why the session is flagged; the argument is the device name"))
                            .gmFont(12)
                            .foregroundColor(GM.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                }
            }
            CannotBeUndoneNote()
                .padding(.top, 12)
            if let error {
                Text(error).gmFont(12).foregroundColor(GM.error).padding(.top, 6)
            }
        } footer: {
            VStack(spacing: 6) {
                GradientButton(
                    title: String(localized: "Revoke Session", comment: "Button title (confirm session revocation screen)"),
                    busy: busy,
                    action: {
                        guard let token = SessionStore.token else { return }
                        busy = true; error = nil
                        Task {
                            do {
                                _ = try await ApiClient.revokeSession(token: token, id: sessionId)
                                busy = false
                                onRevoked(device)
                            } catch {
                                busy = false
                                self.error = apiMessage(error, fallback: String(localized: "Could not complete the security request. Try again.", comment: "Error message shown when the server gives no reason (confirm session revocation screen)"))
                            }
                        }
                    }
                )
                AccountLink(title: String(localized: "Cancel", comment: "Link-style button (confirm session revocation screen)"), action: onBack)
            }
        }
    }
}

/// 17 — revoked, ordinary copy.
struct SessionRevokedView: View {
    let device: String
    let onDone: () -> Void

    var body: some View {
        AccountScreen(
            onBack: onDone,
            illustration: "ill_sec_session_revoked",
            illustrationWidth: 170,
            title: String(localized: "Session Revoked Successfully", comment: "Screen title (session revoked screen)"),
            subtitle: String(localized: "The session has been revoked and this device can no longer access your account.", comment: "Screen description under the title (session revoked screen)")
        ) {
            InfoCard {
                Text("Revoked Session")
                    .gmFont(14, .semibold)
                    .foregroundColor(GM.textPrimary)
                Text(device.isEmpty ? String(localized: "That device", comment: "Session revocation: stands in for an unnamed device") : device)
                    .gmFont(14)
                    .foregroundColor(GM.textSecondary)
                    .padding(.top, 6)
            }
        } footer: {
            VStack(spacing: 6) {
                GradientButton(title: String(localized: "Done", comment: "Button title (session revoked screen)"), action: onDone)
                FooterNote(text: String(localized: "The device will need your password to sign in again.", comment: "Footer note (session revoked screen)"))
            }
        }
    }
}

/// 22 — revoked, entered from the alert.
struct SessionRevokedSuspiciousView: View {
    let device: String
    let onDone: () -> Void

    var body: some View {
        AccountScreen(
            onBack: onDone,
            illustration: "ill_sec_revoked_suspicious",
            illustrationWidth: 170,
            title: String(localized: "Session Revoked Successfully", comment: "Screen title (session revoked suspicious screen)"),
            subtitle: String(localized: "The suspicious session has been revoked and can no longer access your GrapMail account.", comment: "Screen description under the title (session revoked suspicious screen)")
        ) {
            InfoCard {
                Text("Revoked Session Details")
                    .gmFont(14, .semibold)
                    .foregroundColor(GM.textPrimary)
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.shield").gmFont(14).foregroundColor(GM.success)
                    Text("This device is signed out")
                        .gmFont(13)
                        .foregroundColor(GM.textSecondary)
                }
                .padding(.top, 8)
                Text(device.isEmpty ? String(localized: "Unknown device", comment: "Session card title when the device name is not known") : device)
                    .gmFont(13, .semibold)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 4)
            }
        } footer: {
            VStack(spacing: 6) {
                GradientButton(title: String(localized: "Done", comment: "Button title (session revoked suspicious screen)"), action: onDone)
                FooterNote(text: String(localized: "Change your password too — a revoked session does not change a leaked one.", comment: "Footer note (session revoked suspicious screen)"))
            }
        }
    }
}

/// 18 — the account's own history. Rows come from events written when they
/// happened, so an empty list is a new account, not a failed query.
struct SecurityActivityView: View {
    let onBack: () -> Void
    let onOpenAlert: () -> Void
    let onOpenSessions: () -> Void

    @State private var events: [ActivityDto] = []
    @State private var suspicious = false
    @State private var loading = true
    @State private var error: String?
    // "This was me" acknoledges on the alert screen and pops straight back here,
    // where the destination is retained in the stack. A bare `.task` would not
    // re-run on that re-entry, so the "Unrecognized activity" banner would stay
    // lit after the sign-in had been confirmed as the person's own. `.onAppear`
    // drives a refetch on every return; the first appearance is left to `.task`.
    @State private var reload = 0
    @State private var didAppear = false

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_activity",
            illustrationWidth: 150,
            title: String(localized: "Recent Security Activity", comment: "Screen title (security activity screen)"),
            subtitle: String(localized: "Sign-ins and changes to your account, newest first.", comment: "Screen description under the title (security activity screen)")
        ) {
            if suspicious {
                HStack(alignment: .top, spacing: 9) {
                    Image(systemName: "exclamationmark.triangle").gmFont(14).foregroundColor(GM.error)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Unrecognized activity")
                            .gmFont(13, .semibold)
                            .foregroundColor(GM.error)
                        Text("A device signed in that this account had not used before.")
                            .gmFont(12)
                            .foregroundColor(GM.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        AccountLink(title: String(localized: "Review it now", comment: "Link-style button (security activity screen)"), action: onOpenAlert)
                    }
                    Spacer(minLength: 0)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.error.opacity(0.08)))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(GM.error.opacity(0.35)))
                .padding(.bottom, 14)
            }
            if loading {
                GMSkeletonList(rows: 4, avatar: false)
            } else if let error {
                Text(error).gmFont(13).foregroundColor(GM.error)
                AccountLink(title: String(localized: "Try again"), action: { reload += 1 })
            } else if events.isEmpty {
                Text("Nothing has happened on this account yet.").gmFont(13).foregroundColor(GM.textSecondary)
            } else {
                InfoCard {
                    ForEach(Array(events.enumerated()), id: \.element.id) { index, event in
                        ActivityRowView(event: event)
                        if index < events.count - 1 { SecurityDivider() }
                    }
                }
            }
        } footer: {
            AccountLink(title: String(localized: "Manage active sessions", comment: "Link-style button (security activity screen)"), action: onOpenSessions)
        }
        .task(id: reload) {
            loading = true
            error = nil
            // No session is a failed read, not an empty list.
            guard let token = SessionStore.token else { self.error = String(localized: "Sign in again."); loading = false; return }
            do {
                let resp = try await ApiClient.activity(token: token)
                events = resp.events
                suspicious = resp.suspicious
            } catch {
                self.error = apiMessage(error, fallback: String(localized: "Could not complete the security request. Try again.", comment: "Error message shown when the server gives no reason (security activity screen)"))
            }
            loading = false
        }
        .onAppear {
            if didAppear { reload += 1 } else { didAppear = true }
        }
    }
}

/// 19 — the alert itself: what happened, on which device, and two honest ways
/// out.
struct SuspiciousSignInAlertView: View {
    @Environment(\.dynamicTypeSize) private var dts
    let onBack: () -> Void
    let onSecure: () -> Void
    let onThisWasMe: () -> Void

    @State private var event: ActivityDto?
    @State private var loaded = false
    @State private var ackError: String?

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_alert",
            illustrationWidth: 170,
            eyebrow: String(localized: "Security Alert", comment: "Small label above the screen title (suspicious sign in alert screen)"),
            title: String(localized: "Suspicious Sign-In Alert", comment: "Screen title (suspicious sign in alert screen)"),
            subtitle: String(localized: "We noticed a sign-in to your account from a device we do not recognise.", comment: "Screen description under the title (suspicious sign in alert screen)")
        ) {
            InfoCard {
                Text("Sign-in details")
                    .gmFont(14, .semibold)
                    .foregroundColor(GM.textPrimary)
                if !loaded {
                    Text("Checking your account\u{2026}").gmFont(13).foregroundColor(GM.textSecondary)
                } else {
                    // Never a guess: with no event the honest answer is "not
                    // recorded" / "not available", not "Just now", which could push
                    // someone to revoke their own current device.
                    detailRow("Device", event?.device.isEmpty == false ? event!.device : String(localized: "Not recorded", comment: "Security alert detail value when the server did not record it"))
                    detailRow("When", event.map { securityRelative($0.at) } ?? String(localized: "Not available", comment: "Security alert detail value when it is unknown"))
                    detailRow("Address", event?.ip.isEmpty == false ? event!.ip : String(localized: "Not recorded", comment: "Security alert detail value when the server did not record it"))
                    if event != nil {
                        StatusChip(text: String(localized: "Not recognised", comment: "Status chip (suspicious sign in alert screen)"), tone: .warning)
                            .padding(.top, 8)
                    }
                }
            }
            CannotBeUndoneNote(text: String(localized: "This wasn't you? Someone may have gained access to your account. Take action now to keep your data safe.", comment: "Irreversible-action warning (suspicious sign in alert screen)"))
                .padding(.top, 12)
        } footer: {
            VStack(spacing: 6) {
                GradientButton(title: String(localized: "Secure My Account", comment: "Button title (suspicious sign in alert screen)"), action: onSecure)
                AccountLink(title: String(localized: "This was me", comment: "Link-style button (suspicious sign in alert screen)"), action: {
                    // Dismissing is a write, not a navigation. The old handler popped
                    // back whether the write landed or not, so a failed acknowledgement
                    // looked like success and the warning stayed lit on the next load.
                    guard let token = SessionStore.token else { ackError = String(localized: "Sign in again to record that.", comment: "Message (suspicious sign in alert screen)"); return }
                    Task {
                        do {
                            _ = try await ApiClient.acknowledgeActivity(token: token)
                            onThisWasMe()
                        } catch {
                            ackError = apiMessage(error, fallback: String(localized: "Could not complete the security request. Try again.", comment: "Error message shown when the server gives no reason (suspicious sign in alert screen)"))
                        }
                    }
                })
                if let ackError {
                    Text(ackError).gmFont(12).foregroundColor(GM.error)
                }
            }
        }
        .task {
            guard let token = SessionStore.token else { loaded = true; return }
            // The server already picked the suspicious sign-in; read that, not a
            // re-filter of the whole trail.
            event = (try? await ApiClient.activity(token: token))?.suspiciousEvent
            loaded = true
        }
    }

    private func detailRow(_ label: LocalizedStringKey, _ value: String) -> some View {
        // Label left, value right; at the accessibility sizes the value goes
        // under its label so neither is squeezed to a word per line.
        GMAdaptiveStack(spacing: dts.isAccessibilitySize ? 2 : nil) {
            Text(label).gmFont(12).foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if !dts.isAccessibilitySize { Spacer() }
            Text(value).gmFont(13, .semibold).foregroundColor(GM.textPrimary)
                .multilineTextAlignment(dts.isAccessibilitySize ? .leading : .trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 4)
    }
}

/// 20 — Secure My Account, per the reference: the suspicious sign-in spelled out
/// (Location / Device / Time), a red "this looks suspicious" warning, and the two
/// actions that end the break-in — Revoke This Session and Change Password — with
/// a way back to the activity trail. Revoke is on-screen so the intruder is cut
/// off from here, not behind the device list.
struct SecureMyAccountView: View {
    @Environment(\.dynamicTypeSize) private var dts
    let onBack: () -> Void
    let onChangePassword: () -> Void
    let onBackToActivity: () -> Void

    @State private var sessions: [SessionDto] = []
    @State private var alertAt: String?
    @State private var alertDevice = ""
    @State private var alertIp = ""
    @State private var flaggedDeviceId = ""
    @State private var revokeBusy = false
    @State private var revokeError: String?
    @State private var sessionsLoaded = false
    @State private var alertKnown = false

    private var checking: Bool { !sessionsLoaded || !alertKnown }

    // The live session on the flagged device, if any: what makes "Revoke This
    // Session" cut the intruder off now rather than being a second route to the list.
    private var flaggedSession: SessionDto? {
        guard !flaggedDeviceId.isEmpty else { return nil }
        return sessions.first { !$0.current && $0.deviceId == flaggedDeviceId }
    }

    private func load() async {
        guard let token = SessionStore.token else { return }
        if let list = try? await ApiClient.sessions(token: token) {
            sessions = list.sessions
        }
        // Settled either way. Inside the `if let`, a failed call left the flag
        // false forever and the screen sat on "Checking your account…" with no
        // retry and no error — the rows below already answer a missing value
        // honestly ("Not recorded" / "Not available"), so a failed fetch must
        // land there, not on a spinner that never ends.
        sessionsLoaded = true
        if let activity = try? await ApiClient.activity(token: token) {
            // The server names the open alert; re-filtering would flag a dismissed
            // or stale sign-in as a break-in.
            let alert = activity.suspiciousEvent
            alertAt = alert?.at
            alertDevice = alert?.device ?? ""
            alertIp = alert?.ip ?? ""
            flaggedDeviceId = alert?.deviceId ?? ""
        }
        alertKnown = true
    }

    private func revokeFlagged() {
        guard let token = SessionStore.token, let session = flaggedSession else { return }
        revokeBusy = true; revokeError = nil
        Task {
            do {
                _ = try await ApiClient.revokeSession(token: token, id: session.id)
                await load()
            } catch {
                revokeError = apiMessage(error, fallback: String(localized: "Could not complete the security request. Try again.", comment: "Error message shown when the server gives no reason (secure my account screen)"))
            }
            revokeBusy = false
        }
    }

    var body: some View {
        AccountScreen(
            onBack: onBackToActivity,
            illustration: "ill_sec_secure",
            illustrationWidth: 170,
            title: String(localized: "Secure My Account", comment: "Screen title (secure my account screen)"),
            subtitle: String(localized: "We found a suspicious sign-in to your account. Review the details, revoke the access if it wasn't you, and change your password.", comment: "Screen description under the title (secure my account screen)")
        ) {
            if checking {
                Text("Checking your account\u{2026}").gmFont(13).foregroundColor(GM.textSecondary)
            } else {
                InfoCard {
                    Text("Suspicious Sign-In Details")
                        .gmFont(14, .semibold)
                        .foregroundColor(GM.textPrimary)
                    // Location is the masked IP only — no GeoIP source, so no city is
                    // invented; the caption says so plainly.
                    detailRowSecure("Location", alertIp.isEmpty ? String(localized: "Not recorded", comment: "Security alert detail value when the server did not record it") : alertIp, String(localized: "Approximate location based on IP", comment: "Security alert detail caption"))
                    detailRowSecure("Device", alertDevice.isEmpty ? String(localized: "Not recorded", comment: "Security alert detail value when the server did not record it") : alertDevice)
                    detailRowSecure("Time", alertAt.map { securityAbsolute($0) } ?? String(localized: "Not available", comment: "Security alert detail value when it is unknown"), String(localized: "Your local time", comment: "Security alert detail caption"))
                }
                CannotBeUndoneNote(text: String(localized: "This looks suspicious. If this wasn't you, we recommend revoking the access and changing your password immediately.", comment: "Irreversible-action warning (secure my account screen)"))
                    .padding(.top, 12)
            }
        } footer: {
            VStack(spacing: 6) {
                if flaggedSession != nil {
                    GradientButton(title: String(localized: "Revoke This Session", comment: "Button title (secure my account screen)"), busy: revokeBusy, action: revokeFlagged)
                    if let revokeError {
                        Text(revokeError).gmFont(12).foregroundColor(GM.error)
                    }
                    OutlineActionButton(title: String(localized: "Change Password", comment: "Button title (secure my account screen)"), action: onChangePassword)
                } else if !checking {
                    Text("That device is already signed out. Change your password to end any other session.")
                        .gmFont(12)
                        .foregroundColor(GM.textSecondary)
                        .multilineTextAlignment(.center)
                    GradientButton(title: String(localized: "Change Password", comment: "Button title (secure my account screen)"), action: onChangePassword)
                }
                AccountLink(title: String(localized: "Back to Security Activity", comment: "Link-style button (secure my account screen)"), action: onBackToActivity)
            }
        }
        .task {
            await load()
        }
    }

    private func detailRowSecure(_ label: LocalizedStringKey, _ value: String, _ caption: String? = nil) -> some View {
        // At the accessibility sizes the value goes under its label.
        GMAdaptiveStack(spacing: dts.isAccessibilitySize ? 2 : nil, verticalAlignment: .top) {
            VStack(alignment: .leading, spacing: 1) {
                Text(label).gmFont(12).foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let caption {
                    Text(caption).gmFont(11).foregroundColor(GM.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if !dts.isAccessibilitySize { Spacer() }
            Text(value).gmFont(13, .semibold).foregroundColor(GM.textPrimary)
                .multilineTextAlignment(dts.isAccessibilitySize ? .leading : .trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 4)
    }
}
