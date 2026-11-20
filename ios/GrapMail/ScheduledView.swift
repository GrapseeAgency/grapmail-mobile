import SwiftUI

/// The account's future sends, soonest first, each with its cancel. The data
/// is the server's own `GET /grapmail/scheduled` answer; cancelling clears
/// `scheduled_at` on the server, which is why the note says "it will not be
/// sent" rather than "deleted". Gate-legal: no client scheduling field —
/// this screen only reads and cancels mail the server already holds.
struct ScheduledView: View {
    /// The session's bearer, passed by the wiring site: this view never
    /// reaches into the model's private session state.
    let apiToken: String
    let onBack: () -> Void

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
            content
            if let note {
                Text(note).font(.system(size: 13)).foregroundColor(GM.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.vertical, 8)
            }
        }
        .background(GM.bg.ignoresSafeArea())
        .task { await load() }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button { onBack() } label: {
                Image(systemName: "chevron.left").font(.system(size: 17))
                    .foregroundColor(GM.textPrimary)
            }
            .accessibilityLabel("Back")
            Text("Scheduled")
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(GM.textPrimary)
            Spacer()
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
    }

    @ViewBuilder private var content: some View {
        if loading {
            Spacer()
            ProgressView()
            Spacer()
        } else if let error {
            Text(error).font(.system(size: 14)).foregroundColor(GM.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
        } else if messages.isEmpty && loaded {
            Text("Nothing scheduled.")
                .font(.system(size: 14)).foregroundColor(GM.textTertiary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
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

    private func row(_ message: MailScheduledMessage) -> some View {
        // The server's recipient list, or an em dash when it is empty — the
        // `ifEmpty` helper this line used to call does not exist.
        let recipients = (message.to ?? []).joined(separator: ", ")

        return VStack(alignment: .leading, spacing: 4) {
            Text(message.subject?.nilIfBlank() ?? "(no subject)")
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(GM.textPrimary)
                .lineLimit(1)
            Text("To " + (recipients.isEmpty ? "—" : recipients))
                .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                .lineLimit(1)
            if let when = message.scheduledFor {
                Text("Sends " + ScheduledView.whenText(when))
                    .font(.system(size: 12)).foregroundColor(GM.textTertiary)
            }
            Button(cancellingId == message.id ? "Cancelling…" : "Cancel") {
                cancel(message.id)
            }
            .font(.system(size: 13, weight: .semibold))
            .foregroundColor(GM.primarySolid)
            .disabled(cancellingId != nil)
        }
        .padding(.vertical, 6)
        .listRowSeparator(.hidden)
        .listRowBackground(GM.bg)
    }

    private func load() async {
        guard !loading else { return }
        loading = true
        error = nil
        defer { loading = false }
        do {
            let r = try await ApiClient.mailScheduledList(apiToken)
            messages = r.messages ?? []
            loaded = true
            // The Lock Screen countdown for the soonest send rides the same
            // read the list below renders (SendLaterActivityManager).
            if #available(iOS 16.2, *) {
                SendLaterActivityManager.sync(messages: messages)
            }
        } catch {
            self.error = apiMessage(error, fallback: "Could not update scheduled mail. Try again.")
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
                note = "Cancelled — it will not be sent."
                if #available(iOS 16.2, *) {
                    // The cancelled send leaves the list and the Lock Screen in
                    // the same beat; the next soonest send, if any, takes over.
                    SendLaterActivityManager.sync(messages: messages)
                }
            } catch {
                note = apiMessage(error, fallback: "Could not update scheduled mail. Try again.")
            }
            cancellingId = nil
        }
    }

    static func whenText(_ iso: String) -> String {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .short
        return ISO8601DateFormatter().date(from: iso).map(f.string(from:)) ?? iso
    }
}
