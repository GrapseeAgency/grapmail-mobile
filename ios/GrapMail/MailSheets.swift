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

/// The advanced search sheet -- six filters the server has no route for, run
/// CLIENT-SIDE over the pages this device has loaded, and saying so in its own
/// scope line the way the sort sheet does. The server's `q` is one literal term
/// over subject, body and from (`grapmail-search.ts:110-118`); there is no
/// from/to/attachment/date endpoint, so this filters what is here and never
/// claims to search the box. Android's `AdvancedSearchContent` is the same
/// sheet with the same words.
struct AdvancedSearchSheet: View {
    let state: MailboxModel.State
    let onApply: (_ from: String, _ to: String, _ subject: String, _ files: Bool,
                  _ after: String, _ before: String) -> Void
    let onClear: () -> Void
    let onDone: () -> Void

    @State private var from = ""
    @State private var to = ""
    @State private var subject = ""
    @State private var files = false
    @State private var after = ""
    @State private var before = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SheetTitle(text: "Search filters")
            VStack(alignment: .leading, spacing: 10) {
                AdvRow(label: "From", placeholder: "A name or address in the sender", text: $from)
                AdvRow(label: "To", placeholder: "A name or address in the recipients", text: $to)
                AdvRow(label: "Subject", placeholder: "A word the subject contains", text: $subject)
                Button {
                    files.toggle()
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "paperclip")
                            .font(.system(size: 15))
                            .foregroundColor(files ? GM.primarySolid : GM.textTertiary)
                        Text("Has attachments")
                            .font(.system(size: 14))
                            .foregroundColor(files ? GM.primarySolid : GM.textPrimary)
                        Spacer()
                    }
                }
                .buttonStyle(.plain)
                HStack(spacing: 12) {
                    AdvRow(label: "After", placeholder: "YYYY-MM-DD", text: $after)
                    AdvRow(label: "Before", placeholder: "YYYY-MM-DD", text: $before)
                }
                HStack(spacing: 16) {
                    Button("Apply") {
                        onApply(from, to, subject, files, after, before)
                        onDone()
                    }
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(GM.primarySolid)
                    Button("Clear") {
                        onClear()
                        from = ""; to = ""; subject = ""; files = false
                        after = ""; before = ""
                    }
                    .font(.system(size: 14))
                    .foregroundColor(GM.textSecondary)
                }
                // The scope, stated exactly as the sort sheet states its own.
                Text("These filters run on the \(state.advScope) messages loaded on " +
                     "this device — not all \(state.total) in this box. The typed " +
                     "term is the whole-box search.")
                    .font(.system(size: 12)).foregroundColor(GM.warnInk)
            }
            .padding(.horizontal, 20)
        }
        .padding(.bottom, 24)
        .accessibilityIdentifier("gm-sheets-advsearch")
    }
}

private struct AdvRow: View {
    let label: String
    let placeholder: String
    @Binding var text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(GM.textTertiary)
            TextField(placeholder, text: $text)
                .font(.system(size: 14))
                .foregroundColor(GM.textPrimary)
            Rectangle().fill(GM.border).frame(height: 1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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
    /// Whether the label LIST was read. An empty list after a refused read is not
    /// "no labels", and the sheet asserted exactly that.
    let labelsRead: AssignmentsRead
    let marks: [String: LabelMark]
    let read: AssignmentsRead
    let note: String?
    let selectedCount: Int
    let onPick: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SheetTitle(text: "Label")
            if labels.isEmpty {
                Text(labelsRead == .failed
                     ? "Couldn't read your labels."
                     : "No labels yet. Create one from the drawer.")
                    .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                    .padding(.horizontal, 20).padding(.vertical, 10)
            }
            // What the marks mean, said before a reader puts anything into a tick.
            // This line is the difference between a sheet that consulted the server
            // and one that merely looks as if it did: when the read has not
            // answered, the rows are deliberately unmarked and the sheet says so
            // rather than showing them clean.
            Text(explanation)
                .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                .padding(.horizontal, 20).padding(.vertical, 6)
            ForEach(labels) { l in
                let m = marks[l.id] ?? .unknown
                SheetOption(label: l.name, active: m == .on, mixed: m == .mixed) {
                    onPick(l.id)
                }
            }
            if let note {
                Text(note)
                    .font(.system(size: 13)).foregroundColor(GM.successInk)
                    .padding(.horizontal, 20).padding(.vertical, 8)
            }
        }
        .padding(.bottom, 24)
        .accessibilityIdentifier("gm-sheets-label")
    }

    private var explanation: String {
        switch read {
        case .reading, .notRead:
            return "Checking which labels these messages carry\u{2026}"
        case .failed:
            return "Couldn't read which labels they carry, so nothing is marked here. "
                + "Tapping adds a label; it won't remove one."
        case .read:
            return selectedCount > 1
                ? "Marks show what the \(selectedCount) selected messages carry. "
                    + "A dash means some but not all."
                : "Marks show what this message carries."
        }
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
            // Word-for-word the line Android draws in `SnoozeContent`. It used to
            // end "unsnoozing needs the message to be visible
            // first", which this build no longer does: the Undo beside the list takes
            // mail back out of snooze precisely when it is not visible, because the
            // moment of snoozing is the moment the app knows. This copy is
            // photographed in `84-mail-snooze-sheet`, so a claim this stale ships in
            // the artifact set on both platforms unless both are changed together.
            Text("Snoozed mail leaves your inbox until it is due. The server has no " +
                 "box that lists what is postponed, so Undo appears beside the list " +
                 "for the mail this session snoozed, and for no other mail.")
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

/// Deciding a sender, from the screener queue. The same three refusals Android
/// makes in `SenderDecisionContent`:
///
///  - no option is marked as the decision currently in force, because the
///    screener surface writes decisions and cannot read them back, so that fact
///    is genuinely unknown and an invented default would be a lie with a tick
///    beside it;
///  - it never claims these messages will move — a decision governs the next
///    delivery, and the queue is re-read afterwards;
///  - the reach of "the whole domain" is stated before it is chosen, because
///    approving a domain admits every mailbox at it, including ones that do not
///    exist yet.
struct SenderDecisionSheet: View {
    let senders: [String]
    let busy: Bool
    let result: String?
    let onPick: (MailSenderScope, MailDecision) -> Void

    @State private var scope: MailSenderScope = .address

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SheetTitle(text: "Decide the sender")
            Text(senders.count == 1 ? "Sender: \(senders[0])"
                                    : "\(senders.count) senders in the selected messages")
                .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                .padding(.horizontal, 20).padding(.vertical, 6)

            groupLabel("APPLIES TO")
            ForEach(MailSenderScope.allCases) { s in
                SheetOption(label: s.title, active: scope == s) { scope = s }
            }
            if scope == .domain {
                Text(MailSenderScope.domain.warning)
                    .font(.system(size: 12)).foregroundColor(GM.warnInk)
                    .padding(.horizontal, 20).padding(.vertical, 4)
            }

            groupLabel("DECISION")
            ForEach(MailDecision.allCases) { d in
                SheetOption(label: d.title, active: false) {
                    if !busy { onPick(scope, d) }
                }
                Text(d.note)
                    .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                    .padding(.horizontal, 20).padding(.vertical, 2)
            }

            if let result {
                Text(result)
                    .font(.system(size: 12)).foregroundColor(GM.textPrimary)
                    .padding(.horizontal, 20).padding(.vertical, 8)
                    .accessibilityIdentifier("mail-decision-receipt")
            }
        }
        .padding(.bottom, 24)
        .accessibilityIdentifier("gm-sheets-sender")
    }

    @ViewBuilder private func groupLabel(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 11, weight: .bold)).foregroundColor(GM.textTertiary)
            .padding(.horizontal, 20).padding(.vertical, 8)
    }
}

struct SheetOption: View {
    let label: String
    let active: Bool
    /// Half-checked: some of the selection carries it. Only the Label sheet has a
    /// state this can describe — a filing target either is where the message is or
    /// is not — so no other caller passes it. Mirrors Android's `mixed` parameter.
    var mixed: Bool = false
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 10) {
                Text(label)
                    .font(.system(size: 15))
                    .foregroundColor(GM.textPrimary)
                    .lineLimit(1)
                Spacer()
                if mixed {
                    // A dash inside the ring, not a filled tick: a filled circle is
                    // what `on` already looks like, and the two states must not read
                    // as one.
                    ZStack {
                        Circle().stroke(GM.primarySolid, lineWidth: 1.5)
                            .frame(width: 18, height: 18)
                        Rectangle().fill(GM.primarySolid)
                            .frame(width: 9, height: 2.5)
                    }
                } else if active {
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
