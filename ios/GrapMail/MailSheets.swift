import SwiftUI

/// The five sheets the selection bar and the search row open.
///
/// Each one states the scope of what it does. That is not padding: the server
/// orders by UID descending and nothing else, so a chosen sort reordered only
/// the pages already fetched; there is no arbitrary "move to folder" endpoint,
/// only the filing actions that exist; and snoozing has no box to read back
/// from. A sheet that hides any of that is a control whose label promises more
/// than the system delivers.
struct SortFilterSheet: View {
    let sort: MailSort
    let filter: MailFilter
    /// How many rows the chosen sort actually re-ordered.
    let sortedCount: Int
    let total: Int
    let onPick: (MailSort, MailFilter) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SheetTitle(text: "Sort & filter")
            groupLabel("Sort by")
            ForEach(MailSort.allCases) { s in
                SheetOption(label: s.title, active: s == sort) { onPick(s, filter) }
            }
            groupLabel("Filter by")
            ForEach(MailFilter.allCases) { f in
                SheetOption(label: f.title, active: f == filter) { onPick(sort, f) }
            }
            if !sort.isServerSide && sortedCount < total {
                Text("Sorted across the \(sortedCount) messages loaded so far, " +
                     "not all \(total) in this box.")
                    .font(.system(size: 12)).foregroundColor(GM.warnInk)
                    .padding(.horizontal, 20).padding(.vertical, 8)
            }
        }
        .padding(.bottom, 24)
        .accessibilityIdentifier("gm-sheets-sort")
    }

    @ViewBuilder private func groupLabel(_ t: String) -> some View {
        Text(t)
            .font(.system(size: 12, weight: .bold))
            .foregroundColor(GM.textTertiary)
            .padding(.horizontal, 20).padding(.vertical, 6)
    }
}

/// The filing targets that actually exist. `FILING_ACTIONS` is
/// archive/unarchive/spam/unspam (`grapmail-archive.ts:146`) plus
/// trash/restore (`grapmail-boxes.ts:265`). Nothing else is offered, so no
/// option can silently do something other than its label.
struct MoveSheet: View {
    let onPick: (MailAction) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SheetTitle(text: "Move to")
            SheetOption(label: "Archive", active: false) { onPick(.archive) }
            SheetOption(label: "Trash", active: false) { onPick(.trash) }
            SheetOption(label: "Spam", active: false) { onPick(.spam) }
            SheetOption(label: "Back to Inbox", active: false) { onPick(.restore) }
            Text("Moving out of Spam or Trash returns the message to the inbox.")
                .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                .padding(.horizontal, 20).padding(.vertical, 6)
        }
        .padding(.bottom, 24)
        .accessibilityIdentifier("gm-sheets-move")
    }
}

struct AssignLabelSheet: View {
    let labels: [MailLabel]
    let onPick: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SheetTitle(text: "Label")
            if labels.isEmpty {
                Text("No labels yet. Create one from the drawer.")
                    .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                    .padding(.horizontal, 20).padding(.vertical, 10)
            }
            ForEach(labels) { l in
                SheetOption(label: l.name, active: false) { onPick(l.id) }
            }
        }
        .padding(.bottom, 24)
        .accessibilityIdentifier("gm-sheets-label")
    }
}

/// Snooze. The body key the server reads is `until`
/// (`grapmail-archive.ts:171`). The warning is load-bearing: there is no
/// `snoozed` box to list these back from, so a snoozed message leaves the
/// visible set until it comes round, and a sheet that omitted that would let
/// the user believe the mail was lost.
struct SnoozeSheet: View {
    let onPick: (String) -> Void

    private struct Slot: Identifiable {
        let label: String
        let hours: Int
        var id: String { label }
    }

    private let options = [
        Slot(label: "This evening", hours: 4),
        Slot(label: "Tomorrow morning", hours: 16),
        Slot(label: "Next weekend", hours: 96),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SheetTitle(text: "Snooze until")
            ForEach(options) { slot in
                SheetOption(label: slot.label, active: false) {
                    onPick(Self.untilString(hours: slot.hours))
                }
            }
            Text("Snoozed mail leaves your inbox until it is due. This build has no " +
                 "way to list messages that are snoozed, because the API has no box " +
                 "for them — unsnoozing needs the message to be visible first.")
                .font(.system(size: 12)).foregroundColor(GM.warnInk)
                .padding(.horizontal, 20).padding(.vertical, 8)
        }
        .padding(.bottom, 24)
        .accessibilityIdentifier("gm-sheets-snooze")
    }

    /// A real UTC instant with the `Z` the column expects. An epoch integer or a
    /// local-time string would be read as a different moment, or not at all.
    static func untilString(hours: Int) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.string(from: Date().addingTimeInterval(TimeInterval(hours * 3600)))
    }
}

/// The second, explicit step. The selection bar's Delete trashes; this destroys,
/// and only ever from Trash.
struct PermanentDeleteSheet: View {
    let count: Int
    let cancel: () -> Void
    let confirm: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SheetTitle(text: count == 1
                ? "Delete this message permanently?"
                : "Delete \(count) messages permanently?")
            Text("This cannot be undone. Messages in Trash can be restored instead — " +
                 "this removes them for good, and only works from Trash.")
                .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                .padding(.horizontal, 20).padding(.vertical, 6)
            HStack(spacing: 12) {
                Button(action: cancel) {
                    Text("Cancel")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundColor(GM.textSecondary)
                        .frame(maxWidth: .infinity).padding(.vertical, 14)
                }
                .buttonStyle(.plain)
                GradientButton(title: "Delete permanently", action: confirm)
            }
            .padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 24)
        }
        .accessibilityIdentifier("gm-sheets-permanent")
    }
}

struct SheetTitle: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.system(size: 17, weight: .semibold))
            .foregroundColor(GM.textPrimary)
            .padding(.horizontal, 20).padding(.vertical, 12)
    }
}

struct SheetOption: View {
    let label: String
    let active: Bool
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 10) {
                Text(label)
                    .font(.system(size: 15))
                    .foregroundColor(GM.textPrimary)
                    .lineLimit(1)
                Spacer()
                if active {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 18))
                        .foregroundColor(GM.primarySolid)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("gm-sheet-\(label)")
    }
}
