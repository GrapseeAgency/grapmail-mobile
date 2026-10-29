import SwiftUI

/// Test-only entry into the screens a read-only CI walk can never reach.
///
/// Every view listed here is a confirmation that exists only AFTER a write:
/// two-step switched on or off, codes generated, a password changed, a session
/// revoked. Performing those writes in CI would destroy the account the rest of
/// the walk depends on — signing out everywhere ends the session the restore leg
/// proves, and a password change breaks the sign-in leg — so the walk cannot
/// reach them by navigation. The UI test therefore names one screen at launch
/// and the app renders it directly: no fetch, no submit, closures that do
/// nothing, exactly like a navigation stub.
///
/// Nothing in the real flow ever passes the argument, and no screen here is
/// reachable without it. The screens that DO fetch on appear (the
/// suspicious-sign-in alert and the "secure my account" flow behind it) are
/// deliberately absent: they need a server-seeded event, and a half-populated
/// screen photographed as if it were the real thing is worse than no screenshot
/// at all. The per-session revoke confirmations used to be excluded for the
/// same reason — they refetched the whole session list to print one device
/// label — until that fetch moved to the caller that already had the answer.
enum SecuritySnapshot {
    /// The launch argument the UI tests pass: `-gmSnapshot <name>`.
    static func requested() -> String? {
        let args = ProcessInfo.processInfo.arguments
        guard let at = args.firstIndex(of: "-gmSnapshot"), at + 1 < args.count else { return nil }
        return args[at + 1]
    }
}

struct SecuritySnapshotView: View {
    let name: String

    /// Codes in the format GrapMail actually issues — ten characters shown as
    /// two groups of five.
    private static let sampleCodes = [
        "A1B2C-D3E4F", "G5H6I-J7K8L", "M9N0P-Q1R2S", "T3U4V-W5X6Y", "Z7A8B-C9D0E",
        "F1G2H-J3K4L", "M5N6P-Q7R8S", "T9U0V-W1X2Y", "Z3A4B-C5D6E", "F7G8H-J9K0L",
    ]

    var body: some View {
        Group {
            switch name {
            case "two-factor-on":
                TwoFactorEnabledView(onContinue: {}, onBackHome: {})
            case "two-factor-off-form":
                DisableTwoFactorView(onBack: {}, onDisabled: {})
            case "two-factor-off-done":
                TwoFactorDisabledView(onDone: {}, onSecureNow: {})
            case "backup-codes-form":
                GenerateBackupCodesView(onBack: {}, onGenerated: { _ in })
            case "backup-codes-saved":
                BackupCodesSavedView(codes: Self.sampleCodes, onDone: {})
            case "change-password":
                ChangePasswordView(afterSuspicious: false, onBack: {}, onForgot: {}, onChanged: {})
            case "password-changed":
                PasswordChangedView(updated: false, onDone: {}, onSupport: {})
            case "sign-out-all-form":
                SignOutAllDevicesView(onBack: {}, onDone: {})
            case "sign-out-all-done":
                SignedOutAllDevicesView(onDone: {})
            case "session-revoked":
                SessionRevokedView(device: "Chrome on Windows · Dhaka, Bangladesh", onDone: {})
            case "session-revoked-suspicious":
                SessionRevokedSuspiciousView(device: "Firefox on Linux · Lagos, Nigeria", onDone: {})
            case "revoke-session-form":
                RevokeSessionView(sessionId: "snapshot-session", device: "Chrome on Windows · Dhaka, Bangladesh",
                                  onBack: {}, onRevoked: { _ in })
            case "revoke-session-confirm":
                ConfirmSessionRevocationView(sessionId: "snapshot-session", device: "Firefox on Linux · Lagos, Nigeria",
                                             onBack: {}, onRevoked: { _ in })
            default:
                Text("Unknown snapshot: \(name)")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(GM.bg.ignoresSafeArea())
    }
}
