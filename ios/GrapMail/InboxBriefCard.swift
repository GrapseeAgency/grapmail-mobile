import SwiftUI
import UIKit

// MARK: - Good morning card (top of the primary Inbox)
//
// A greeting by the phone's local time, then one line built from what the
// app already detects (MailboxInsights.swift). Each non-zero part is a
// button that filters the inbox to those messages; the active filter shows
// as a clearable chip under the category row. The X hides the card until the
// next time-of-day period. It eases in once per account per time-of-day
// period (never under Reduce Motion), and VoiceOver reads it as ONE element
// whose parts and Dismiss are custom actions.

/// The card's entrance record: the period id (`InboxDayPart.periodID`) it
/// last played in, per account, in UserDefaults -- the same rule Android
/// keeps. No mutable static, so nothing for Swift 6 strict concurrency to
/// flag, and a relaunch inside the same morning does not replay it.
private enum InboxBriefEntrance {
    private static func key(_ account: String?) -> String {
        let id = (account ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return "gm.inboxBrief.entrancePeriod.\(id.isEmpty ? "default" : id)"
    }

    static func played(account: String?, period: String, defaults: UserDefaults = .standard) -> Bool {
        defaults.string(forKey: key(account)) == period
    }

    static func markPlayed(account: String?, period: String, defaults: UserDefaults = .standard) {
        defaults.set(period, forKey: key(account))
    }
}

struct InboxBriefCard: View {
    let greeting: String
    let parts: [InboxInsightPart]
    let active: InboxInsightKind?
    /// Whose inbox it is (the entrance plays once per account per period).
    let account: String?
    let onPick: (InboxInsightKind) -> Void
    let onDismiss: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dts
    @State private var shown: Bool

    init(greeting: String,
         parts: [InboxInsightPart],
         active: InboxInsightKind?,
         account: String? = nil,
         onPick: @escaping (InboxInsightKind) -> Void,
         onDismiss: @escaping () -> Void) {
        self.greeting = greeting
        self.parts = parts
        self.active = active
        self.account = account
        self.onPick = onPick
        self.onDismiss = onDismiss
        // Already played in this period for this account: drawn in place, no
        // first frame at opacity 0.
        _shown = State(initialValue: InboxBriefEntrance.played(
            account: account, period: InboxDayPart.periodID(Date())))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .center, spacing: 4) {
                Text(greeting)
                    .gmFont(17, .semibold)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .gmFont(12, .semibold, maxSize: 20)
                        .foregroundColor(GM.textTertiary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("mail-brief-dismiss")
            }
            if parts.isEmpty {
                Text(MailboxInsights.summaryLine([]))
                    .gmFont(14)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, 10)
            } else {
                // One line when it fits; the parts stack when the text size
                // or a narrow column leaves no room for all of them.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 2) { partButtons(separated: true) }
                    VStack(alignment: .leading, spacing: 0) { partButtons(separated: false) }
                }
                .padding(.bottom, 4)
            }
        }
        .padding(.leading, 14)
        .padding(.trailing, 4)
        .padding(.top, 2)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: GMShape.card, style: .continuous).fill(GM.infoFill))
        .padding(.horizontal, 12)
        .padding(.top, 10)
        .padding(.bottom, 6)
        .opacity(shown ? 1 : 0)
        .offset(y: shown ? 0 : -10)
        .onAppear {
            guard !shown else { return }
            InboxBriefEntrance.markPlayed(account: account, period: InboxDayPart.periodID(Date()))
            if reduceMotion {
                shown = true
            } else {
                withAnimation(GMMotion.emphasizedDecelerate(GMMotion.long)) { shown = true }
            }
        }
        // One element: the greeting and the whole summary in one sentence,
        // the parts and Dismiss as its actions (rotor → Actions).
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(accessibilitySentence))
        .accessibilityActions {
            ForEach(parts) { part in
                Button(String(localized: "Show \(part.text)", comment: "VoiceOver action on the inbox greeting card; e.g. 'Show 3 need a reply'")) {
                    onPick(part.kind)
                }
            }
            Button(String(localized: "Dismiss", comment: "VoiceOver action: hide the inbox greeting card")) {
                onDismiss()
            }
        }
        .accessibilityIdentifier("mail-brief")
    }

    private var accessibilitySentence: String {
        "\(greeting). \(MailboxInsights.summaryLine(parts))"
    }

    @ViewBuilder private func partButtons(separated: Bool) -> some View {
        ForEach(Array(parts.enumerated()), id: \.element.id) { index, part in
            if separated && index > 0 {
                Text(verbatim: "·")
                    .gmFont(14)
                    .foregroundColor(GM.textTertiary)
                    .accessibilityHidden(true)
            }
            partButton(part)
        }
    }

    private func partButton(_ part: InboxInsightPart) -> some View {
        let on = active == part.kind
        return Button {
            onPick(part.kind)
        } label: {
            Text(part.text)
                .gmFont(14, .medium)
                .foregroundColor(on ? .white : GM.primarySolid)
                .lineLimit(dts.isAccessibilitySize ? nil : 1)
                .fixedSize(horizontal: !dts.isAccessibilitySize, vertical: true)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Capsule().fill(on ? GM.primarySolid : Color.clear))
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("mail-brief-\(part.kind.rawValue)")
    }
}

// MARK: - The active insight filter

/// "Needs a reply ⓧ" under the category row while the card's filter is on.
/// The whole chip is the clear control.
struct InboxInsightFilterChip: View {
    let kind: InboxInsightKind
    let onClear: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onClear) {
                HStack(spacing: 6) {
                    Image(systemName: kind.symbol)
                        .gmFont(12, .semibold, maxSize: 20)
                        .accessibilityHidden(true)
                    Text(kind.filterTitle)
                        .gmFont(13, .semibold)
                        .fixedSize(horizontal: false, vertical: true)
                    Image(systemName: "xmark.circle.fill")
                        .gmFont(13, maxSize: 22)
                        .accessibilityHidden(true)
                }
                .foregroundColor(.white)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(Capsule().fill(GM.primarySolid))
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(String(localized: "Filter: \(kind.filterTitle)", comment: "VoiceOver label of the active inbox filter chip")))
            .accessibilityHint(Text("Double-tap to clear the filter"))
            .accessibilityIdentifier("mail-insight-filter")
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
    }
}

// MARK: - "Code copied"

/// The short confirmation after a code chip copies. It never repeats the
/// code: the pasteboard holds it, the screen does not need to say it twice.
struct CodeCopiedToast: View {
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "checkmark.circle.fill")
                .gmFont(14, .semibold, maxSize: 24)
                .accessibilityHidden(true)
            Text("Code copied", comment: "Confirmation after copying a sign-in code; never includes the code")
                .gmFont(14, .semibold)
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundColor(GM.bg)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Capsule().fill(GM.textPrimary))
        .padding(.horizontal, 24)
        .allowsHitTesting(false)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("mail-code-copied")
    }
}

/// VoiceOver hears what a toast shows.
enum GMAnnounce {
    static func say(_ text: String) {
        UIAccessibility.post(notification: .announcement, argument: text)
    }
}
