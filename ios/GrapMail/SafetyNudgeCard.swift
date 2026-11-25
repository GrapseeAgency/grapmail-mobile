import SwiftUI

// MARK: - Round 8 — mailbox security slot (docs/design/round8-spec.md §3.6, §4.3 B)
//
// MailboxView carries ONE line for this file: `SafetyMailboxSlot(...)` inside
// its list. The slot draws, in order of precedence:
//   1. the non-dismissible "Finish securing your account" banner while
//      `account.passwordChangeRequired` is true (§3.6), or
//   2. the safety nudge card (§4.3 B) when every rule below holds, or
//   3. nothing.
// Navigation goes through `Notification.Name.gmOpenSecurityCenter`, which
// RootView answers (it owns the stack), so MailboxView needs no new callback.

extension Notification.Name {
    /// Object: "center" (the Security Center) or "password" (change password,
    /// required mode).
    static let gmOpenSecurityCenter = Notification.Name("grapmail.open.securityCenter")
}

enum SecurityCenterLink {
    static func openCenter() {
        NotificationCenter.default.post(name: .gmOpenSecurityCenter, object: "center")
    }

    static func openRequiredPassword() {
        NotificationCenter.default.post(name: .gmOpenSecurityCenter, object: "password")
    }
}

/// §4.3 B rule 5 + the session rules: shown at most once a week per account
/// (`gm.safetyCard.lastShownAt.<fnv1a(address)>`, set the moment it shows,
/// dismissed or not); a dismissal hides it for the rest of the session.
@MainActor
enum SafetyNudgePolicy {
    private static var shownThisSession: Set<String> = []
    private static var dismissedThisSession: Set<String> = []

    private static func key(_ address: String) -> String {
        "gm.safetyCard.lastShownAt.\(SecHash.accountKey(address))"
    }

    static func allowed(_ address: String, now: Date = Date()) -> Bool {
        let id = SecHash.accountKey(address)
        if dismissedThisSession.contains(id) { return false }
        if shownThisSession.contains(id) { return true }
        let last = UserDefaults.standard.double(forKey: key(address))
        guard last > 0 else { return true }
        return now.timeIntervalSince1970 - last >= 7 * 24 * 3600
    }

    static func noteShown(_ address: String) {
        let id = SecHash.accountKey(address)
        guard !shownThisSession.contains(id) else { return }
        shownThisSession.insert(id)
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: key(address))
    }

    static func dismiss(_ address: String) {
        dismissedThisSession.insert(SecHash.accountKey(address))
    }
}

/// The one view MailboxView inserts. It applies its own list-row modifiers.
@MainActor
struct SafetyMailboxSlot: View {
    /// The nudge shows on the Inbox only; the §3.6 banner shows in every box.
    let isInbox: Bool
    /// Multi-select is active (rule 7).
    let selecting: Bool
    /// Search / filters narrow the list (rule 7).
    let searching: Bool
    /// The device is offline (rule 7).
    let offline: Bool

    @ObservedObject private var store = SecurityOverviewStore.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dismissed = false

    private var address: String { store.address ?? SessionStore.address?.lowercased() ?? "" }

    private enum Kind { case banner, nudge(SecurityOverviewResponse) }

    private var kind: Kind? {
        guard SecFeature.enabled, !selecting, let overview = store.overview, !address.isEmpty else { return nil }
        if overview.account.passwordChangeRequired { return .banner }
        guard isInbox, !searching, !offline, !dismissed,
              overview.score.value < 60,                  // rule 1
              store.freshForNudge,                        // rule 2
              accountOlderThanADay(overview),             // rule 6
              SafetyNudgePolicy.allowed(address)          // rule 5 (+ session)
        else { return nil }
        // Rule 4 (an unreviewed security-alert banner wins) has nothing to
        // yield to on iOS today: the mailbox draws no such banner.
        return .nudge(overview)
    }

    var body: some View {
        if let kind {
            switch kind {
            case .banner:
                SecPasswordRequiredBanner(onChangePassword: SecurityCenterLink.openRequiredPassword)
                    .modifier(SafetySlotRow())
            case let .nudge(overview):
                SafetyNudgeCard(
                    value: overview.score.value,
                    level: overview.score.level,
                    nextTitle: SafetyCopy.topTodo(overview.checklist).map(SafetyCopy.title),
                    onImprove: SecurityCenterLink.openCenter,
                    onDismiss: {
                        SafetyNudgePolicy.dismiss(address)
                        withAnimation(GMMotion.gated(GMMotion.standard(), reduced: reduceMotion)) { dismissed = true }
                    }
                )
                .onAppear { SafetyNudgePolicy.noteShown(address) }
                .transition(reduceMotion ? .identity : .opacity)
                .modifier(SafetySlotRow())
            }
        }
    }

    /// §4.3 B rule 6. The overview carries no creation date (§6 gap), so the
    /// account counts as new only while its `account_created` event is under
    /// 24 h old; no such event in the 30-day window means older than that.
    private func accountOlderThanADay(_ overview: SecurityOverviewResponse) -> Bool {
        guard let created = overview.events.first(where: { $0.type == .accountCreated }),
              let at = SecTime.date(created.occurredAt) else { return true }
        return Date().timeIntervalSince(at) > 24 * 3600
    }
}

private struct SafetySlotRow: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
            .gmMaxWidth(GMLayout.readable)
            .listRowInsets(EdgeInsets())
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }
}

/// §3.6 — full width, `errorContainer`, radius 16, padding 16; cannot be
/// dismissed. Shared by the Security Center and the mailbox.
struct SecPasswordRequiredBanner: View {
    let onChangePassword: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Image(systemName: "exclamationmark.shield")
                    .gmFont(15, .semibold, maxSize: 24)
                    .foregroundColor(SecPalette.error)
                    .accessibilityHidden(true)
                Text(secL("Finish securing your account"))
                    .gmFont(.bodyMedium)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(secL("Set a new password to finish."))
                .gmFont(.caption)
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Button(action: onChangePassword) {
                Text(secL("Change password"))
                    .gmFont(.captionMedium)
                    .foregroundColor(SecPalette.primaryText)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("sec-banner-change-password")
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: GMShape.button, style: .continuous).fill(SecPalette.errorContainer))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sec-password-required-banner")
    }
}

/// §4.3 B card: `primaryContainer`, radius 20, padding 16, mini ring 40
/// (stroke 5), "Improve" and a 44 pt Dismiss.
struct SafetyNudgeCard: View {
    let value: Int
    let level: SecScoreLevel
    let nextTitle: String?
    let onImprove: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            SafetyRing(value: value, level: level, size: 40, stroke: 5, showsCenter: false)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(secL("Account safety")))
                .accessibilityValue(Text(SafetyCopy.ringLabelValue(SecScore.display(value: value, level: level))))
            VStack(alignment: .leading, spacing: 4) {
                Text(secL("Make your account harder to break into"))
                    .gmFont(.bodyMedium)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(nextTitle.map { secL("Your safety score is \(value). Next: \($0) — it takes about a minute.") }
                     ?? secL("Your safety score is \(value)."))
                    .gmFont(.caption)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Button(action: onImprove) {
                    Text(secL("Improve"))
                        .gmFont(.captionMedium)
                        .foregroundColor(SecPalette.primaryText)
                        .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("sec-nudge-improve")
            }
            Spacer(minLength: 0)
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .gmFont(13, .semibold, maxSize: 22)
                    .foregroundColor(GM.textSecondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(secL("Dismiss")))
            .accessibilityIdentifier("sec-nudge-dismiss")
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).fill(SecPalette.primaryContainer))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sec-safety-nudge")
    }
}

extension SecScore {
    /// A score value for display-only helpers (the nudge's VoiceOver value).
    static func display(value: Int, level: SecScoreLevel) -> SecScore {
        var score = SecScore()
        score.value = value
        score.level = level
        return score
    }
}
