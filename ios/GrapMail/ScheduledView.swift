import SwiftUI

/// The account's future sends, soonest first, each with its cancel. The data
/// is the server's own `GET /grapmail/scheduled` answer; cancelling clears
/// `scheduled_at` on the server, which is why the note says "it will not be
/// sent" rather than "deleted". Gate-legal: no client scheduling field —
/// this screen only reads and cancels mail the server already holds.
///
/// Text follows Dynamic Type (ScaledFont.swift). At the accessibility sizes a
/// row's subject and recipients wrap instead of truncating, and on wide windows
/// (iPad, Stage Manager, Mac) the list keeps a readable width.
struct ScheduledView: View {
    /// The session's bearer, passed by the wiring site: this view never
    /// reaches into the model's private session state.
    let apiToken: String
    let onBack: () -> Void

    @Environment(\.dynamicTypeSize) private var dts
    @State private var loading = false
    @State private var loaded = false
    @State private var error: String?
    @State private var messages: [MailScheduledMessage] = []
    @State private var cancellingId: String?
    @State private var note: String?

    // The three pieces are computed properties, not one nested expression: the
    // single-body version of this screen exceeded the compiler's type-check
    // budget ("unable to type-check this expression in reasonable time" at
    // `var body`), which is a compile error, not a warning.
    var body: some View {
        VStack(spacing: 0) {
            header
            GMOfflineBanner()
            content
                .gmMaxWidth(GMLayout.readable)
            if let note {
                Text(note).gmFont(13).foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.vertical, 8)
                    .gmMaxWidth(GMLayout.readable)
            }
        }
        .background(GM.bg.ignoresSafeArea())
        .task { await load() }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button { onBack() } label: {
                Image(systemName: "chevron.left").gmFont(17, maxSize: 28)
                    .foregroundColor(GM.textPrimary)
                    .flipsForRightToLeftLayoutDirection(true)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Back")
            Text("Scheduled")
                .gmFont(17, .semibold)
                .foregroundColor(GM.textPrimary)
                .accessibilityAddTraits(.isHeader)
            Spacer()
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
    }

    @ViewBuilder private var content: some View {
        // Screen states: skeleton on the first read only, error + Retry when
        // nothing could be read, empty state only after the server answered.
        if (loading || !loaded) && messages.isEmpty {
            GMSkeletonList(rows: 4, avatar: false)
        } else if let error, messages.isEmpty {
            GMErrorState(title: "Couldn't load scheduled mail", message: error,
                         onRetry: { Task { await load() } })
        } else if messages.isEmpty {
            GMEmptyState(icon: "clock", title: "Nothing scheduled.",
                         message: "Mail you schedule to send later waits here until it goes out.")
        } else {
            List {
                ForEach(messages) { m in
                    row(m)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    /// One line at the design's sizes; at the accessibility sizes the line
    /// wraps (up to `wrapped` lines) rather than cutting the subject or the
    /// recipient list short.
    private func lines(_ wrapped: Int) -> Int { dts.isAccessibilitySize ? wrapped : 1 }

    private func row(_ message: MailScheduledMessage) -> some View {
        // The server's recipient list, or an em dash when it is empty — the
        // `ifEmpty` helper this line used to call does not exist.
        let recipients = (message.to ?? []).joined(separator: ", ")

        return VStack(alignment: .leading, spacing: 4) {
            Text(message.subject?.nilIfBlank() ?? String(localized: "(no subject)"))
                .gmFont(15, .semibold)
                .foregroundColor(GM.textPrimary)
                .lineLimit(lines(4))
            // One sentence with the list interpolated, not "To " glued on.
            Text("To \(recipients.isEmpty ? "—" : recipients)")
                .gmFont(13).foregroundColor(GM.textSecondary)
                .lineLimit(lines(4))
            if let when = message.scheduledFor {
                Text("Sends \(ScheduledView.whenText(when))")
                    .gmFont(12).foregroundColor(GM.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button(cancellingId == message.id ? "Cancelling…" : "Cancel") {
                cancel(message.id)
            }
            .gmFont(13, .semibold)
            .foregroundColor(GM.primarySolid)
            .disabled(cancellingId != nil)
        }
        .padding(.vertical, 6)
        .listRowSeparator(.hidden)
        .listRowBackground(GM.bg)
    }

    private func load() async {
        guard !loading else { return }
        // What this device already knows — a send scheduled a moment ago from
        // the composer included — is on screen before the read answers.
        if messages.isEmpty { messages = ScheduledStore.shared.current }
        loading = true
        error = nil
        defer { loading = false }
        do {
            let r = try await ApiClient.mailScheduledList(apiToken)
            messages = r.messages ?? []
            ScheduledStore.shared.replace(messages)
            loaded = true
            // The Lock Screen countdown for the soonest send rides the same
            // read the list below renders (SendLaterActivityManager).
            if #available(iOS 16.2, *) {
                SendLaterActivityManager.sync(messages: messages)
            }
        } catch {
            self.error = apiMessage(error, fallback: String(localized: "Could not update scheduled mail. Try again."))
            loaded = true
        }
    }

    private func cancel(_ id: String) {
        guard cancellingId == nil else { return }
        cancellingId = id
        Task {
            do {
                _ = try await ApiClient.mailScheduledCancel(apiToken, id: id)
                messages.removeAll { $0.id == id }
                ScheduledStore.shared.remove(id: id)
                note = String(localized: "Cancelled — it will not be sent.")
                if #available(iOS 16.2, *) {
                    // The cancelled send leaves the list and the Lock Screen in
                    // the same beat; the next soonest send, if any, takes over.
                    SendLaterActivityManager.sync(messages: messages)
                }
            } catch {
                note = apiMessage(error, fallback: String(localized: "Could not update scheduled mail. Try again."))
            }
            cancellingId = nil
        }
    }

    // One parser, one formatter, built once. The old version constructed a
    // DateFormatter (an ICU setup) and an ISO8601DateFormatter per row per
    // render of the scheduled list.
    // The parser is SendLaterSchedule's: the server's instants carry
    // milliseconds, which a default ISO8601DateFormatter refuses (every row
    // used to print the raw string).
    private static let whenFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .short
        return f
    }()

    static func whenText(_ iso: String) -> String {
        SendLaterSchedule.parse(iso).map(Self.whenFormatter.string(from:)) ?? iso
    }
}
