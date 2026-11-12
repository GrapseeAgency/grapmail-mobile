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

    var body: some View {
        VStack(spacing: 0) {
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
                        VStack(alignment: .leading, spacing: 4) {
                            Text(m.subject?.nilIfBlank() ?? "(no subject)")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(GM.textPrimary)
                                .lineLimit(1)
                            Text("To " + (m.to ?? []).joined(separator: ", ").ifEmpty("—"))
                                .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                                .lineLimit(1)
                            if let when = m.scheduledFor {
                                Text("Sends " + ScheduledView.whenText(when))
                                    .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                            }
                            Button(cancellingId == m.id ? "Cancelling…" : "Cancel") {
                                cancel(m.id)
                            }
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(GM.primarySolid)
                            .disabled(cancellingId != nil)
                        }
                        .padding(.vertical, 6)
                        .listRowSeparator(.hidden)
                        .listRowBackground(GM.bg)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
            if let note {
                Text(note).font(.system(size: 13)).foregroundColor(GM.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.vertical, 8)
            }
        }
        .background(GM.bg.ignoresSafeArea())
        .task { await load() }
    }

    private func load() async {
        guard !loading else { return }
        loading = true
        error = nil
        defer { loading = false }
        do {
            let r = try await ApiClient.mailScheduledList(token ?? "")
            messages = r.messages ?? []
            loaded = true
        } catch {
            self.error = error.localizedDescription
            loaded = true
        }
    }

    private func cancel(_ id: String) {
        guard cancellingId == nil else { return }
        cancellingId = id
        Task {
            do {
                _ = try await ApiClient.mailScheduledCancel(token ?? "", id: id)
                messages.removeAll { $0.id == id }
                note = "Cancelled — it will not be sent."
            } catch {
                note = error.localizedDescription
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
