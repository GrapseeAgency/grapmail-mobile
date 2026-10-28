import SwiftUI
import UIKit

/// 01 / 02 — the hub the whole sector hangs from.
///
/// One view for both references: 02 is the same page with everything switched
/// on, so the copy follows the state instead of the layout being forked.
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

    private func sectionLabel(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12, weight: .semibold))
            .foregroundColor(GM.textTertiary)
            .padding(.top, 18)
            .padding(.bottom, 2)
    }

    private var twoFactorOn: Bool { profile?.twoFactorEnabled ?? false }
    private var secure: Bool { twoFactorOn && !(profile?.phone ?? "").isEmpty }

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: secure ? "ill_sec_hub_secure" : "ill_sec_hub",
            illustrationWidth: 120,
            title: "Account Security",
            subtitle: "Manage your security settings to keep your account safe and secure."
        ) {
            // The reference puts the account's verdict above the feature list: it
            // answers "am I safe?" before the six rows that explain why.
            InfoCard {
                if loading {
                    Text("Checking your account…")
                        .font(.system(size: 13))
                        .foregroundColor(GM.textSecondary)
                } else if failed {
                    Text("We couldn't load your security settings.")
                        .font(.system(size: 13))
                        .foregroundColor(GM.textSecondary)
                    AccountLink(title: "Try again", action: { reload += 1 })
                } else {
                    StatusChip(
                        text: secure ? "Your account is secure" : "One thing left to do",
                        tone: secure ? .success : .warning
                    )
                    Text(secure
                         ? "All essential security features are active and your account is protected."
                         : "Turn on two-step verification to protect your account even if your password is ever exposed.")
                        .font(.system(size: 12))
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 10)
                    // Two-step on with no codes is one lost phone away from a
                    // lock-out, and the badge above would still call it secure.
                    // Only say so when the server actually answered with a
                    // count: an absent field is unknown, not zero, and a false
                    // "you have no codes" is its own kind of wrong warning.
                    if twoFactorOn, let remaining = profile?.backupCodesRemaining, remaining == 0 {
                        Text("Add backup codes so losing your phone does not lock you out.")
                            .font(.system(size: 12))
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
                title: "Two-Step Verification",
                subtitle: "Add an extra layer of security to your account with a verification code.",
                chip: (loading || failed) ? nil : (twoFactorOn ? "Enabled" : "Off"),
                chipTone: twoFactorOn ? .success : .warning,
                isOn: twoFactorOn && !loading && !failed,
                enabled: !loading && !failed,
                onToggle: { _ in twoFactorOn ? onDisableTwoFactor() : onTwoFactor() }
            )
            SecurityDivider()
            SecurityRowView(
                icon: "iphone",
                title: "Phone Number",
                subtitle: (profile?.phone ?? "").isEmpty
                    ? (loading ? nil : "Used for account recovery and two-step verification.")
                    : profile?.phone,
                chip: (loading || failed) ? nil : ((profile?.phone ?? "").isEmpty ? "Add" : "Verified"),
                chipTone: (profile?.phone ?? "").isEmpty ? .warning : .success,
                action: onPhone
            )

            sectionLabel("Account Management")
            SecurityRowView(
                icon: "key",
                title: "Change Password",
                subtitle: "Update your account password for better security.",
                action: onPassword
            )
            SecurityDivider()
            SecurityRowView(
                icon: "at",
                title: "Backup Codes",
                subtitle: (profile?.backupCodesRemaining ?? 0) > 0
                    ? "\(profile?.backupCodesRemaining ?? 0) codes unused."
                    : "Generate and manage backup codes for 2FA.",
                action: onBackupCodes
            )
            SecurityDivider()
            SecurityRowView(
                icon: "rectangle.portrait.and.arrow.right",
                title: "Sign Out All Devices",
                subtitle: "Log out from all devices except this one.",
                action: onSignOutAll
            )
            // Two rows the reference hub does not draw but the sector needs a
            // reachable entry for; kept here rather than orphaning those screens.
            SecurityDivider()
            SecurityRowView(
                icon: "laptopcomputer.and.iphone",
                title: "Active Sessions",
                subtitle: "Devices signed in to this account.",
                action: onSessions
            )
            SecurityDivider()
            SecurityRowView(
                icon: "checkmark.shield",
                title: "Recent Security Activity",
                subtitle: "Sign-ins and changes to this account.",
                action: onActivity
            )
            SecurityDivider()
            SecurityRowView(
                icon: "headphones",
                title: "Help & Support",
                subtitle: "Visit our support center for security assistance.",
                action: onSupport
            )
        } footer: {
            FooterNote(text: "Your security matters. We're here to help.")
        }
        .task(id: reload) {
            loading = true; failed = false
            guard let token = SessionStore.token else {
                loading = false; failed = true; return
            }
            do {
                profile = try await ApiClient.profile(token: token).profile
                // A profile that never answered the security verdict is not a
                // loaded profile. Left alone, `twoFactorEnabled` would default
                // to false and the hub would claim "2FA off" for an account that
                // may have it on — and enable a toggle that routes into the
                // ENABLE flow. Show the honest "couldn't load" instead, which
                // also leaves the toggle disabled rather than mis-informed.
                if profile?.twoFactorEnabled == nil { profile = nil; failed = true }
            } catch {
                failed = true
            }
            loading = false
        }
        .onAppear {
            if didAppear { reload += 1 } else { didAppear = true }
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
            title: "Enable Two-Step Verification",
            subtitle: "Add an extra layer of security to your account."
        ) {
            InfoCard {
                NeedRow(icon: "checkmark.shield", title: "More Protection", body: "Prevents unauthorized access, even if your password is compromised.")
                SecurityDivider()
                NeedRow(icon: "iphone", title: "Use Your Phone", body: "We'll send a 6-digit code to your verified phone number.")
                SecurityDivider()
                NeedRow(icon: "lock", title: "Easy to Manage", body: "You can turn it off anytime from account security settings.")
            }
            HStack(alignment: .top, spacing: 9) {
                Image(systemName: "info.circle").font(.system(size: 14)).foregroundColor(GM.primarySolid)
                Text("You'll need a verified phone number to enable two-step verification.")
                    .font(.system(size: 12))
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.softFill))
            .padding(.top, 12)
        } footer: {
            VStack(spacing: 4) {
                GradientButton(title: "Continue", action: onContinue)
                AccountLink(title: "Cancel", action: onBack)
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
                .font(.system(size: 14))
                .foregroundColor(GM.primarySolid)
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.system(size: 13, weight: .semibold)).foregroundColor(GM.textPrimary)
                Text(detail).font(.system(size: 12)).foregroundColor(GM.textSecondary)
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
            title: "Confirm Your Password",
            subtitle: "Enter your password to continue."
        ) {
            AppTextField(
                title: "Password",
                value: $password,
                error: error,
                hint: "Enter your password",
                isPassword: true
            )
        } footer: {
            VStack(spacing: 4) {
                GradientButton(
                    title: "Continue",
                    enabled: !password.isEmpty,
                    busy: busy,
                    action: {
                        guard let token = SessionStore.token else { error = "Sign in again."; return }
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
                                self.error = error.localizedDescription
                            }
                        }
                    }
                )
                AccountLink(title: "Back", action: onBack)
            }
        }
    }
}

/// 05 — the code goes to the number already on the account; the factor is only
/// switched on after the code is redeemed.
struct VerifyPhoneView: View {
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
                error = "Add a phone number first."
                return
            }
            let sent = try await ApiClient.startPhoneVerification(phone: number)
            challengeId = sent.challengeId
            if let millis = sent.expiresAt {
                expiresAt = Date(timeIntervalSince1970: Double(millis) / 1000)
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_phone",
            illustrationWidth: 170,
            title: "Verify Your Phone",
            subtitle: (profile?.phone).map { "We'll text a code to \($0)." }
                ?? "We'll text a code to the number on this account."
        ) {
            InfoCard {
                HStack(spacing: 8) {
                    Image(systemName: "iphone").font(.system(size: 14)).foregroundColor(GM.primarySolid)
                    Text("Phone Number").font(.system(size: 13, weight: .medium)).foregroundColor(GM.textSecondary)
                    Spacer()
                    StatusChip(text: "Verified", tone: .success)
                }
                Text(profile?.phone ?? "—")
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
            HStack(alignment: .top, spacing: 9) {
                Image(systemName: "info.circle").font(.system(size: 14)).foregroundColor(GM.primarySolid)
                Text("Didn't receive the code? Check your spam folder or make sure your phone is on and has signal.")
                    .font(.system(size: 12))
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.softFill))
            .padding(.top, 10)
            if let error {
                Text(error).font(.system(size: 12)).foregroundColor(GM.error).padding(.top, 6)
            }
            if needsPhone {
                AccountLink(title: "Add a phone number", action: onAddPhone)
            }
            if needsProof {
                AccountLink(title: "Enter your password again", action: onBack)
            }
            if needsPhoneProof {
                AccountLink(title: "Send me a new code", action: {
                    needsPhoneProof = false
                    error = nil
                    Task { await send() }
                })
            }
        } footer: {
            VStack(spacing: 4) {
                GradientButton(
                    title: "Continue",
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
                                self.error = error.localizedDescription
                            }
                        }
                    }
                )
                AccountLink(title: "Resend code", action: {
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
            title: "Two-Step Verification Enabled",
            subtitle: "Your account is now protected with an extra layer of security."
        ) {
            StatusChip(text: "2-Step Verification Enabled", tone: .success)
            InfoCard {
                Text("What's Next?")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                NeedRow(icon: "checkmark.shield", title: "Keep Backup Codes Safe", body: "Use them if you ever lose access to your phone.")
                NeedRow(icon: "iphone", title: "Update Your Phone Number", body: "Keep it current so codes always reach you.")
            }
            .padding(.top, 16)
            Text("You'll be asked for a code each time you sign in on a new device.")
                .font(.system(size: 12))
                .foregroundColor(GM.textSecondary)
                .padding(.top, 12)
        } footer: {
            VStack(spacing: 4) {
                GradientButton(title: "Continue", action: onContinue)
                AccountLink(title: "Back to Security Settings", action: onBackHome)
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
            title: "Disable Two-Step Verification?",
            subtitle: "This will remove the extra layer of security from your account."
        ) {
            AppTextField(
                title: "Password",
                value: $password,
                error: error,
                hint: "Enter your password",
                isPassword: true
            )
            HStack(alignment: .top, spacing: 9) {
                Image(systemName: "checkmark.shield").font(.system(size: 14)).foregroundColor(GM.primarySolid)
                Text("Disabling two-step verification will make your account less secure. You can enable it again anytime from Account Security settings.")
                    .font(.system(size: 12))
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
                    title: "Continue",
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
                                self.error = error.localizedDescription
                            }
                        }
                    }
                )
                AccountLink(title: "Back", action: onBack)
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
            title: "Two-Step Verification Disabled",
            subtitle: "Two-step verification has been disabled. Your account is now protected by your password only."
        ) {
            InfoCard {
                Text("What happens now?")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                NeedRow(icon: "key", title: "Password Only", body: "Signing in no longer asks for a code.")
                NeedRow(icon: "message", title: "Backup Codes Retired", body: "The codes you saved no longer apply.")
            }
            Text("You can turn two-step verification back on at any time from Account Security.")
                .font(.system(size: 12))
                .foregroundColor(GM.textSecondary)
                .padding(.top, 12)
        } footer: {
            VStack(spacing: 4) {
                GradientButton(title: "Continue", action: onDone)
                AccountLink(title: "Turn it back on", action: onSecureNow)
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
            title: "Generate & Save Backup Codes",
            subtitle: "Create one-time backup codes to access your account if you lose your phone."
        ) {
            InfoCard {
                Text("What's Included")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                NeedRow(icon: "number", title: "10 One-Time Codes", body: "Each code can be used once.")
                NeedRow(icon: "lock", title: "Use When Needed", body: "Sign in when your phone is unavailable.")
                NeedRow(icon: "checkmark.shield", title: "Each Code is Unique", body: "Generating a new set retires the old one.")
            }
            Text("Store the codes somewhere only you can reach. Anyone with a code can pass your second factor.")
                .font(.system(size: 12))
                .foregroundColor(GM.textSecondary)
                .padding(.top, 12)
            if let error {
                Text(error).font(.system(size: 12)).foregroundColor(GM.error).padding(.top, 6)
            }
        } footer: {
            VStack(spacing: 4) {
                GradientButton(
                    title: "Generate Codes",
                    busy: busy,
                    action: {
                        guard let token = SessionStore.token else { error = "Sign in again."; return }
                        busy = true; error = nil
                        Task {
                            do {
                                let resp = try await ApiClient.generateBackupCodes(token: token)
                                busy = false
                                onGenerated(resp.newBackupCodes ?? [])
                            } catch {
                                busy = false
                                self.error = error.localizedDescription
                            }
                        }
                    }
                )
                AccountLink(title: "Cancel", action: onBack)
            }
        }
    }
}

/// 08 — the only time the codes exist in readable form. The server keeps
/// hashes, so there is no "view them again" path anywhere in the product.
struct BackupCodesSavedView: View {
    let codes: [String]
    let onDone: () -> Void

    @State private var copied = false
    @State private var copiedCode: String?
    // Codes are shown once. Leaving without saving them is a real loss, so it
    // takes an explicit confirmation rather than a tap on the nearest button.
    @State private var confirmed = false
    @State private var showLeave = false

    private var grid: [[String]] { stride(from: 0, to: codes.count, by: 2).map { Array(codes[$0..<min($0 + 2, codes.count)]) } }

    var body: some View {
        AccountScreen(
            onBack: { if confirmed { onDone() } else { showLeave = true } },
            illustration: "ill_sec_backup_saved",
            illustrationWidth: 150,
            title: "Backup Codes Saved",
            subtitle: "Store these codes somewhere safe. They won't be shown again."
        ) {
            InfoCard {
                HStack {
                    Text("Your Backup Codes")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(GM.textPrimary)
                    Spacer()
                    Button(action: {
                        UIPasteboard.general.string = codes.joined(separator: "\n")
                        copied = true
                    }) {
                        HStack(spacing: 5) {
                            Image(systemName: copied ? "checkmark.circle" : "doc.on.doc")
                                .font(.system(size: 12, weight: .semibold))
                            Text(copied ? "Copied" : "Copy all")
                                .font(.system(size: 12, weight: .semibold))
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
                                            .font(.system(size: 13, weight: .semibold, design: .monospaced))
                                            .foregroundColor(GM.textPrimary)
                                        Image(systemName: copiedCode == code ? "checkmark.circle" : "doc.on.doc")
                                            .font(.system(size: 11))
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
                            if pair.count == 1 { Spacer().frame(maxWidth: .infinity) }
                        }
                    }
                }
            }
            InfoCard {
                NeedRow(icon: "lock", title: "Store Safely", body: "Use a password manager or print them out.")
                SecurityDivider()
                NeedRow(icon: "nosign", title: "Don't Share", body: "Backup codes are for your eyes only.")
                SecurityDivider()
                NeedRow(icon: "info.circle", title: "One Time Use", body: "Each code can only be used once.")
            }
            .padding(.top, 12)
            Text("Copy them into your password manager, or write them down.")
                .font(.system(size: 12))
                .foregroundColor(GM.textSecondary)
                .padding(.top, 12)
            Button {
                confirmed.toggle()
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: confirmed ? "checkmark.square.fill" : "square")
                        .font(.system(size: 18))
                        .foregroundColor(confirmed ? GM.primarySolid : GM.textTertiary)
                    Text("I have saved these codes somewhere safe.")
                        .font(.system(size: 13))
                        .foregroundColor(GM.textPrimary)
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
                    }
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 54)
                    .background(RoundedRectangle(cornerRadius: GMShape.button, style: .continuous).fill(GM.primarySolid))
                }
                GradientButton(title: "Done", enabled: confirmed, action: onDone)
                FooterNote(text: "Each code can be used once. Generating new codes retires every code on this list.")
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
            eyebrow: afterSuspicious ? "After Suspicious Activity" : nil,
            title: afterSuspicious ? "Change Your Password" : "Change Password",
            subtitle: afterSuspicious
                ? "Your account was accessed from an unfamiliar device. To keep your GrapMail account secure, please update your password now."
                : "Choose a strong password you have not used here before."
        ) {
            if afterSuspicious {
                HStack(alignment: .top, spacing: 9) {
                    Image(systemName: "exclamationmark.triangle").font(.system(size: 14)).foregroundColor(GM.error)
                    Text("Unrecognized activity was found on your account.")
                        .font(.system(size: 12))
                        .foregroundColor(GM.error)
                    Spacer(minLength: 0)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.error.opacity(0.08)))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(GM.error.opacity(0.35)))
                .padding(.bottom, 14)
            }
            AppTextField(title: "Current Password", value: $current, hint: "Enter your current password", isPassword: true, leadingIcon: "lock")
            AppTextField(title: "New Password", value: $next, hint: "Enter your new password", isPassword: true, leadingIcon: "lock")
                .padding(.top, 12)
            AppTextField(
                title: "Confirm New Password",
                value: $confirm,
                error: (!confirm.isEmpty && !matches) ? "Passwords do not match." : nil,
                hint: "Re-enter your new password",
                isPassword: true,
                leadingIcon: "lock"
            )
            .padding(.top, 12)
            InfoCard {
                Text("Password Requirements")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                RuleLine(label: "At least 8 characters", met: longEnough)
                RuleLine(label: "Contains a letter", met: hasLetter)
                RuleLine(label: "Contains a number", met: hasNumber)
                RuleLine(label: "Contains a symbol", met: hasSymbol)
            }
            .padding(.top, 14)
            if let error {
                Text(error).font(.system(size: 12)).foregroundColor(GM.error).padding(.top, 6)
            }
        } footer: {
            VStack(spacing: 4) {
                GradientButton(
                    title: afterSuspicious ? "Update Password" : "Change Password",
                    enabled: !current.isEmpty && rulesMet && matches,
                    busy: busy,
                    action: {
                        guard let token = SessionStore.token else { error = "Sign in again."; return }
                        busy = true; error = nil
                        Task {
                            do {
                                let resp = try await ApiClient.changePassword(token: token, current: current, next: next)
                                // Every other device is cut off by this change,
                                // this one included: keep the replacement token.
                                if let fresh = resp.accessToken {
                                    SessionStore.save(
                                        token: fresh,
                                        address: SessionStore.address ?? "",
                                        name: SessionStore.name ?? "",
                                        // Without this the expiry key is removed, which
                                        // the store reads as "never expires" — the app then
                                        // cold-boots into a home screen whose every call 401s.
                                        expiresAtRaw: resp.tokenExpiresAtMillis
                                    )
                                }
                                busy = false
                                onChanged()
                            } catch {
                                busy = false
                                self.error = error.localizedDescription
                            }
                        }
                    }
                )
                AccountLink(title: "Back", action: onBack)
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
            title: updated ? "Password Updated Successfully" : "Password Changed Successfully",
            subtitle: "Your password has been updated successfully. Other devices were signed out; this one stays signed in."
        ) {
            InfoCard {
                Text("What's Next?")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                NeedRow(icon: "lock", title: "This device stays signed in", body: "You can carry on where you are.")
                NeedRow(
                    icon: "laptopcomputer.and.iphone",
                    title: "Other devices are signed out",
                    body: "You'll need to sign in again on them, including mobile apps, desktop, and other browsers."
                )
            }
            HStack(alignment: .top, spacing: 9) {
                Image(systemName: "info.circle").font(.system(size: 14)).foregroundColor(GM.primarySolid)
                Text("This helps keep your account secure and prevents unauthorized access.")
                    .font(.system(size: 12))
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.softFill))
            .padding(.top, 12)
        } footer: {
            VStack(spacing: 6) {
                GradientButton(title: "Continue", action: onDone)
                FooterNote(
                    text: "If you did not make this change, contact the support team immediately.",
                    linkText: "support team",
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
            title: "Sign Out All Devices",
            subtitle: "This will sign you out of all devices except this one."
        ) {
            InfoCard {
                Text("You'll stay signed in here")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                Text("Every other phone, tablet and browser is signed out immediately. This device keeps working, so you can carry on.")
                    .font(.system(size: 13))
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 6)
            }
            Text("Use this when a device is lost, borrowed or sold — not instead of changing a leaked password.")
                .font(.system(size: 12))
                .foregroundColor(GM.textSecondary)
                .padding(.top, 12)
            if let error {
                Text(error).font(.system(size: 12)).foregroundColor(GM.error).padding(.top, 6)
            }
        } footer: {
            VStack(spacing: 4) {
                GradientButton(
                    title: "Sign Out All Devices",
                    busy: busy,
                    action: {
                        guard let token = SessionStore.token else { return }
                        busy = true; error = nil
                        Task {
                            do {
                                let resp = try await ApiClient.signOutEverywhere(token: token)
                                // The epoch moved, so the token this request used is
                                // dead. Keep the replacement, or the next screen
                                // asks for a sign-in right after promising this
                                // device would stay signed in.
                                if let fresh = resp.accessToken {
                                    SessionStore.save(
                                        token: fresh,
                                        address: SessionStore.address ?? "",
                                        name: SessionStore.name ?? "",
                                        // Without this the expiry key is removed, which
                                        // the store reads as "never expires" — the app then
                                        // cold-boots into a home screen whose every call 401s.
                                        expiresAtRaw: resp.tokenExpiresAtMillis
                                    )
                                }
                                busy = false
                                onDone()
                            } catch {
                                busy = false
                                self.error = error.localizedDescription
                            }
                        }
                    }
                )
                AccountLink(title: "Cancel", action: onBack)
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
            title: "Signed Out of All Devices",
            subtitle: "You have been signed out of all devices except this one."
        ) {
            InfoCard {
                Text("What happens next?")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                Text("This device stays signed in. Every other device has to sign in again, and anyone who had a session can no longer read this mailbox.")
                    .font(.system(size: 13))
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 6)
            }
            StatusChip(text: "Only this device is signed in", tone: .success)
                .padding(.top, 12)
        } footer: {
            VStack(spacing: 6) {
                GradientButton(title: "Continue", action: onDone)
                FooterNote(text: "Anyone using this account on another device now needs your password again.")
            }
        }
    }
}

/// 15 — the real device list, current one marked.
struct ManageSessionsView: View {
    let onBack: () -> Void
    let onRevoke: (SessionDto) -> Void
    let onRevokeAlert: (SessionDto) -> Void
    let onSignOutAll: () -> Void

    @State private var sessions: [SessionDto] = []
    @State private var activeCount = 0
    @State private var loading = true
    @State private var error: String?
    /// The device behind an open alert gets the stronger confirmation (21)
    /// rather than the ordinary one (16): it is the device the person came here
    /// to deal with, and that copy names the activity.
    @State private var flaggedDeviceId = ""

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_sessions",
            illustrationWidth: 170,
            title: "Manage Active Sessions",
            subtitle: "Devices currently signed in to this account."
        ) {
            if loading {
                Text("Loading sessions…").font(.system(size: 13)).foregroundColor(GM.textSecondary)
            } else if let error {
                Text(error).font(.system(size: 13)).foregroundColor(GM.error)
            } else if sessions.isEmpty {
                Text("No sessions are recorded yet.").font(.system(size: 13)).foregroundColor(GM.textSecondary)
            } else {
                VStack(spacing: 10) {
                    // "N Active Sessions" + Revoke All, as the reference header. The
                    // count is the server's true total; Revoke All routes to the
                    // confirm screen, since signing out everywhere is a lock-out.
                    HStack(spacing: 8) {
                        Circle().fill(GM.success).frame(width: 8, height: 8)
                        Text("\(activeCount) Active Sessions")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(GM.textPrimary)
                        Spacer(minLength: 0)
                        Button(action: onSignOutAll) {
                            Text("Revoke All").font(.system(size: 13, weight: .semibold)).foregroundColor(GM.primarySolid)
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
                    Image(systemName: "info.circle").font(.system(size: 14)).foregroundColor(GM.primarySolid)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Keep Your Account Secure").font(.system(size: 13, weight: .semibold)).foregroundColor(GM.textPrimary)
                        Text("If you see a device you don't recognize, revoke the session immediately. This will prevent unauthorized access.")
                            .font(.system(size: 12))
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
            AccountLink(title: "Sign out all devices", action: onSignOutAll)
        }
        .task {
            guard let token = SessionStore.token else { loading = false; return }
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
                self.error = error.localizedDescription
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
    let onBack: () -> Void
    let onRevoked: (String) -> Void

    @State private var label = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_session_revoke",
            illustrationWidth: 170,
            title: "Revoke Session Access?",
            subtitle: "This device will be signed out of your GrapMail account and will need to sign in again."
        ) {
            InfoCard {
                HStack(spacing: 10) {
                    Image(systemName: "laptopcomputer.and.iphone").font(.system(size: 15)).foregroundColor(GM.primarySolid)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Device").font(.system(size: 12)).foregroundColor(GM.textSecondary)
                        Text(label.isEmpty ? "That device" : label)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(GM.textPrimary)
                    }
                    Spacer(minLength: 0)
                }
            }
            CannotBeUndoneNote()
                .padding(.top, 12)
            if let error {
                Text(error).font(.system(size: 12)).foregroundColor(GM.error).padding(.top, 6)
            }
        } footer: {
            VStack(spacing: 6) {
                GradientButton(
                    title: "Revoke Session",
                    busy: busy,
                    action: { revoke() }
                )
                AccountLink(title: "Cancel", action: onBack)
            }
        }
        .task {
            guard let token = SessionStore.token else { return }
            label = (try? await ApiClient.sessions(token: token))?.sessions.first { $0.id == sessionId }?.device ?? ""
        }
    }

    private func revoke() {
        guard let token = SessionStore.token else { return }
        busy = true; error = nil
        Task {
            do {
                _ = try await ApiClient.revokeSession(token: token, id: sessionId)
                busy = false
                onRevoked(label)
            } catch {
                busy = false
                self.error = error.localizedDescription
            }
        }
    }
}

/// 21 — the same revoke, entered from the alert, so the danger is named.
struct ConfirmSessionRevocationView: View {
    let sessionId: String
    let onBack: () -> Void
    let onRevoked: (String) -> Void

    @State private var label = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_revoke_confirm",
            illustrationWidth: 170,
            eyebrow: "Suspicious Access Detected",
            title: "Confirm Session Revocation",
            subtitle: "This will immediately sign this session out and no longer have access to your GrapMail account."
        ) {
            InfoCard {
                HStack(alignment: .top, spacing: 9) {
                    Image(systemName: "exclamationmark.triangle").font(.system(size: 14)).foregroundColor(GM.error)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Unverified activity")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(GM.textPrimary)
                        Text((label.isEmpty ? "" : label + " · ") + "This session showed activity that did not match your usual sign-in pattern.")
                            .font(.system(size: 12))
                            .foregroundColor(GM.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                }
            }
            CannotBeUndoneNote()
                .padding(.top, 12)
            if let error {
                Text(error).font(.system(size: 12)).foregroundColor(GM.error).padding(.top, 6)
            }
        } footer: {
            VStack(spacing: 6) {
                GradientButton(
                    title: "Revoke Session",
                    busy: busy,
                    action: {
                        guard let token = SessionStore.token else { return }
                        busy = true; error = nil
                        Task {
                            do {
                                _ = try await ApiClient.revokeSession(token: token, id: sessionId)
                                busy = false
                                onRevoked(label)
                            } catch {
                                busy = false
                                self.error = error.localizedDescription
                            }
                        }
                    }
                )
                AccountLink(title: "Cancel", action: onBack)
            }
        }
        .task {
            guard let token = SessionStore.token else { return }
            label = (try? await ApiClient.sessions(token: token))?.sessions.first { $0.id == sessionId }?.device ?? ""
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
            title: "Session Revoked Successfully",
            subtitle: "The session has been revoked and this device can no longer access your account."
        ) {
            InfoCard {
                Text("Revoked Session")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                Text(device.isEmpty ? "That device" : device)
                    .font(.system(size: 14))
                    .foregroundColor(GM.textSecondary)
                    .padding(.top, 6)
            }
        } footer: {
            VStack(spacing: 6) {
                GradientButton(title: "Done", action: onDone)
                FooterNote(text: "The device will need your password to sign in again.")
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
            title: "Session Revoked Successfully",
            subtitle: "The suspicious session has been revoked and can no longer access your GrapMail account."
        ) {
            InfoCard {
                Text("Revoked Session Details")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.shield").font(.system(size: 14)).foregroundColor(GM.success)
                    Text("This device is signed out")
                        .font(.system(size: 13))
                        .foregroundColor(GM.textSecondary)
                }
                .padding(.top, 8)
                Text(device.isEmpty ? "Unknown device" : device)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                    .padding(.top, 4)
            }
        } footer: {
            VStack(spacing: 6) {
                GradientButton(title: "Done", action: onDone)
                FooterNote(text: "Change your password too — a revoked session does not change a leaked one.")
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
            title: "Recent Security Activity",
            subtitle: "Sign-ins and changes to your account, newest first."
        ) {
            if suspicious {
                HStack(alignment: .top, spacing: 9) {
                    Image(systemName: "exclamationmark.triangle").font(.system(size: 14)).foregroundColor(GM.error)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Unrecognized activity")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(GM.error)
                        Text("A device signed in that this account had not used before.")
                            .font(.system(size: 12))
                            .foregroundColor(GM.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        AccountLink(title: "Review it now", action: onOpenAlert)
                    }
                    Spacer(minLength: 0)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.error.opacity(0.08)))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(GM.error.opacity(0.35)))
                .padding(.bottom, 14)
            }
            if loading {
                Text("Loading activity…").font(.system(size: 13)).foregroundColor(GM.textSecondary)
            } else if let error {
                Text(error).font(.system(size: 13)).foregroundColor(GM.error)
            } else if events.isEmpty {
                Text("Nothing has happened on this account yet.").font(.system(size: 13)).foregroundColor(GM.textSecondary)
            } else {
                InfoCard {
                    ForEach(Array(events.enumerated()), id: \.element.id) { index, event in
                        ActivityRowView(event: event)
                        if index < events.count - 1 { SecurityDivider() }
                    }
                }
            }
        } footer: {
            AccountLink(title: "Manage active sessions", action: onOpenSessions)
        }
        .task(id: reload) {
            loading = true
            guard let token = SessionStore.token else { loading = false; return }
            do {
                let resp = try await ApiClient.activity(token: token)
                events = resp.events
                suspicious = resp.suspicious
            } catch {
                self.error = error.localizedDescription
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
            eyebrow: "Security Alert",
            title: "Suspicious Sign-In Alert",
            subtitle: "We noticed a sign-in to your account from a device we do not recognise."
        ) {
            InfoCard {
                Text("Sign-in details")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                if !loaded {
                    Text("Checking your account\u{2026}").font(.system(size: 13)).foregroundColor(GM.textSecondary)
                } else {
                    // Never a guess: with no event the honest answer is "not
                    // recorded" / "not available", not "Just now", which could push
                    // someone to revoke their own current device.
                    detailRow("Device", event?.device.isEmpty == false ? event!.device : "Not recorded")
                    detailRow("When", event.map { securityRelative($0.at) } ?? "Not available")
                    detailRow("Address", event?.ip.isEmpty == false ? event!.ip : "Not recorded")
                    if event != nil {
                        StatusChip(text: "Not recognised", tone: .warning)
                            .padding(.top, 8)
                    }
                }
            }
            CannotBeUndoneNote(text: "This wasn't you? Someone may have gained access to your account. Take action now to keep your data safe.")
                .padding(.top, 12)
        } footer: {
            VStack(spacing: 6) {
                GradientButton(title: "Secure My Account", action: onSecure)
                AccountLink(title: "This was me", action: {
                    // Dismissing is a write, not a navigation. The old handler popped
                    // back whether the write landed or not, so a failed acknowledgement
                    // looked like success and the warning stayed lit on the next load.
                    guard let token = SessionStore.token else { ackError = "Sign in again to record that."; return }
                    Task {
                        do {
                            _ = try await ApiClient.acknowledgeActivity(token: token)
                            onThisWasMe()
                        } catch {
                            ackError = error.localizedDescription
                        }
                    }
                })
                if let ackError {
                    Text(ackError).font(.system(size: 12)).foregroundColor(GM.error)
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

    private func detailRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.system(size: 12)).foregroundColor(GM.textSecondary)
            Spacer()
            Text(value).font(.system(size: 13, weight: .semibold)).foregroundColor(GM.textPrimary)
        }
        .padding(.vertical, 4)
    }
}

/// 20 — Secure My Account, per the reference: the suspicious sign-in spelled out
/// (Location / Device / Time), a red "this looks suspicious" warning, and the two
/// actions that end the break-in — Revoke This Session and Change Password — with
/// a way back to the activity trail. Revoke is on-screen so the intruder is cut
/// off from here, not behind the device list.
struct SecureMyAccountView: View {
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
            sessionsLoaded = true
        }
        if let activity = try? await ApiClient.activity(token: token) {
            // The server names the open alert; re-filtering would flag a dismissed
            // or stale sign-in as a break-in.
            let alert = activity.suspiciousEvent
            alertAt = alert?.at
            alertDevice = alert?.device ?? ""
            alertIp = alert?.ip ?? ""
            flaggedDeviceId = alert?.deviceId ?? ""
            alertKnown = true
        }
    }

    private func revokeFlagged() {
        guard let token = SessionStore.token, let session = flaggedSession else { return }
        revokeBusy = true; revokeError = nil
        Task {
            do {
                _ = try await ApiClient.revokeSession(token: token, id: session.id)
                await load()
            } catch {
                revokeError = error.localizedDescription
            }
            revokeBusy = false
        }
    }

    var body: some View {
        AccountScreen(
            onBack: onBackToActivity,
            illustration: "ill_sec_secure",
            illustrationWidth: 170,
            title: "Secure My Account",
            subtitle: "We found a suspicious sign-in to your account. Review the details, revoke the access if it wasn't you, and change your password."
        ) {
            if checking {
                Text("Checking your account\u{2026}").font(.system(size: 13)).foregroundColor(GM.textSecondary)
            } else {
                InfoCard {
                    Text("Suspicious Sign-In Details")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(GM.textPrimary)
                    // Location is the masked IP only — no GeoIP source, so no city is
                    // invented; the caption says so plainly.
                    detailRowSecure("Location", alertIp.isEmpty ? "Not recorded" : alertIp, "Approximate location based on IP")
                    detailRowSecure("Device", alertDevice.isEmpty ? "Not recorded" : alertDevice)
                    detailRowSecure("Time", alertAt.map { securityAbsolute($0) } ?? "Not available", "Your local time")
                }
                CannotBeUndoneNote(text: "This looks suspicious. If this wasn't you, we recommend revoking the access and changing your password immediately.")
                    .padding(.top, 12)
            }
        } footer: {
            VStack(spacing: 6) {
                if flaggedSession != nil {
                    GradientButton(title: "Revoke This Session", busy: revokeBusy, action: revokeFlagged)
                    if let revokeError {
                        Text(revokeError).font(.system(size: 12)).foregroundColor(GM.error)
                    }
                    OutlineActionButton(title: "Change Password", action: onChangePassword)
                } else if !checking {
                    Text("That device is already signed out. Change your password to end any other session.")
                        .font(.system(size: 12))
                        .foregroundColor(GM.textSecondary)
                        .multilineTextAlignment(.center)
                    GradientButton(title: "Change Password", action: onChangePassword)
                }
                AccountLink(title: "Back to Security Activity", action: onBackToActivity)
            }
        }
        .task {
            await load()
        }
    }

    private func detailRowSecure(_ label: String, _ value: String, _ caption: String? = nil) -> some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 1) {
                Text(label).font(.system(size: 12)).foregroundColor(GM.textSecondary)
                if let caption {
                    Text(caption).font(.system(size: 11)).foregroundColor(GM.textTertiary)
                }
            }
            Spacer()
            Text(value).font(.system(size: 13, weight: .semibold)).foregroundColor(GM.textPrimary)
                .multilineTextAlignment(.trailing)
        }
        .padding(.vertical, 4)
    }
}
