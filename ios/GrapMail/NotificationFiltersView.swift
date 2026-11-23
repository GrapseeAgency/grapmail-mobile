import SwiftUI

/// Notification filters: which new mail may interrupt, and when.
///
/// Every control changes a rule the server enforces before it sends a push
/// (backend lib/notification-policy.ts), so it holds on every device signed in to
/// the account. Each change is saved as it is made, and the screen shows what the
/// server stored.
struct NotificationFiltersView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var settings: NotificationSettingsInfo?
    @State private var loadError: String?
    @State private var saving = false
    @State private var note: String?
    @State private var vipInput = ""
    @State private var attempt = 0

    private static let modes: [(value: String, title: String, detail: String)] = [
        ("all", "All new mail", "Every message that reaches the inbox."),
        ("priority", "Priority only", "VIP senders and conversations you follow."),
        ("none", "Nothing", "Only conversations you follow, and security alerts."),
    ]

    var body: some View {
        NavigationStack {
            Group {
                if let s = settings {
                    form(s)
                } else {
                    VStack(spacing: 12) {
                        Text(loadError ?? "Checking your settings…")
                            .foregroundColor(loadError == nil ? GM.textSecondary : GM.error)
                            .multilineTextAlignment(.center)
                        if loadError != nil { Button("Try again") { attempt += 1 } }
                    }
                    .padding(24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .navigationTitle("Notification filters")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .task(id: attempt) { await load() }
    }

    private func load() async {
        loadError = nil
        guard let token = SessionStore.token else { loadError = "Sign in again."; return }
        do {
            if let read = try await ApiClient.profile(token: token).profile?.settings?.notifications {
                NotificationPolicy.store(read, account: SessionStore.address)
                settings = read
            } else {
                loadError = "Your notification settings could not be read."
            }
        } catch {
            loadError = (error as? ApiError)?.userMessage ?? "Your notification settings could not be read."
        }
    }

    private func change(_ done: String? = nil, _ edit: @escaping (inout NotificationSettingsInfo) -> Void) {
        guard !saving else { return }
        saving = true
        note = nil
        Task {
            let error = await NotificationPolicy.update(edit)
            saving = false
            if let error { note = error } else {
                settings = NotificationPolicy.cached(account: SessionStore.address)
                note = done
            }
        }
    }

    private static func date(_ clock: String) -> Date {
        let parts = clock.split(separator: ":").compactMap { Int($0) }
        var c = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        c.hour = parts.first ?? 22
        c.minute = parts.count > 1 ? parts[1] : 0
        return Calendar.current.date(from: c) ?? Date()
    }

    private static func clock(_ date: Date) -> String {
        let c = Calendar.current.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
    }

    private static func validVip(_ v: String) -> Bool {
        v.range(of: "^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$", options: .regularExpression) != nil ||
            v.range(of: "^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$", options: .regularExpression) != nil
    }

    @ViewBuilder private func form(_ s: NotificationSettingsInfo) -> some View {
        let zone = TimeZone.current.identifier
        Form {
            Section("Alert me about") {
                ForEach(Self.modes, id: \.value) { mode in
                    Button {
                        if s.mode != mode.value { change { $0.mode = mode.value } }
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(mode.title).foregroundColor(GM.textPrimary)
                                Text(mode.detail).font(.footnote).foregroundColor(GM.textSecondary)
                            }
                            Spacer()
                            if s.mode == mode.value {
                                Image(systemName: "checkmark").foregroundColor(GM.primarySolid)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(saving)
                    .accessibilityAddTraits(s.mode == mode.value ? .isSelected : [])
                    .accessibilityIdentifier("notify-mode-\(mode.value)")
                }
            }

            Section {
                Toggle("Show sender and subject", isOn: Binding(
                    get: { s.showPreview },
                    set: { on in change { $0.showPreview = on } }))
                    .disabled(saving)
                    .accessibilityIdentifier("notify-preview")
            } header: {
                Text("On the lock screen")
            } footer: {
                Text(s.showPreview ? "Alerts name who wrote and what about." : "Alerts say only \"New mail\".")
            }

            Section("Quiet hours") {
                Toggle("Pause alerts every day", isOn: Binding(
                    get: { s.quietHours.enabled },
                    set: { on in change { $0.quietHours.enabled = on; $0.quietHours.timeZone = zone } }))
                    .disabled(saving)
                    .accessibilityIdentifier("notify-quiet")
                if s.quietHours.enabled {
                    DatePicker("From", selection: Binding(
                        get: { Self.date(s.quietHours.start) },
                        set: { d in
                            let t = Self.clock(d)
                            if t == s.quietHours.end { note = "Quiet hours need to start and end at different times." }
                            else if t != s.quietHours.start { change { $0.quietHours.start = t; $0.quietHours.timeZone = zone } }
                        }), displayedComponents: .hourAndMinute)
                        .disabled(saving)
                    DatePicker("To", selection: Binding(
                        get: { Self.date(s.quietHours.end) },
                        set: { d in
                            let t = Self.clock(d)
                            if t == s.quietHours.start { note = "Quiet hours need to start and end at different times." }
                            else if t != s.quietHours.end { change { $0.quietHours.end = t; $0.quietHours.timeZone = zone } }
                        }), displayedComponents: .hourAndMinute)
                        .disabled(saving)
                    Toggle("Let VIPs through", isOn: Binding(
                        get: { s.quietHours.allowVip },
                        set: { on in change { $0.quietHours.allowVip = on } }))
                        .disabled(saving)
                    if !s.quietHours.timeZone.isEmpty {
                        Text("In \(s.quietHours.timeZone)").font(.footnote).foregroundColor(GM.textSecondary)
                    }
                }
            }

            Section {
                HStack {
                    TextField("Add a VIP", text: $vipInput)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.emailAddress)
                        .accessibilityIdentifier("notify-vip-input")
                    Button("Add") {
                        let v = vipInput.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                            .replacingOccurrences(of: "^@", with: "", options: .regularExpression)
                        guard Self.validVip(v) else { note = "\"\(v)\" is not an address or a domain."; return }
                        vipInput = ""
                        change("Added \(v).") { st in st.vipSenders.removeAll { $0 == v }; st.vipSenders.append(v) }
                    }
                    .disabled(saving || vipInput.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                if s.vipSenders.isEmpty {
                    Text("No VIPs yet. You can also add one from a message's menu.")
                        .font(.footnote).foregroundColor(GM.textTertiary)
                }
                ForEach(s.vipSenders, id: \.self) { v in
                    HStack {
                        Text(v)
                        Spacer()
                        Button("Remove", role: .destructive) {
                            change("Removed \(v).") { st in st.vipSenders.removeAll { $0 == v } }
                        }
                        .buttonStyle(.borderless)
                        .disabled(saving)
                    }
                }
            } header: {
                Text("VIP senders")
            } footer: {
                Text("An address (name@example.com) or a whole domain (example.com).")
            }

            Section {
                HStack {
                    Text(s.mutedThreads.isEmpty ? "No muted conversations"
                         : s.mutedThreads.count == 1 ? "1 muted conversation" : "\(s.mutedThreads.count) muted conversations")
                    Spacer()
                    if !s.mutedThreads.isEmpty {
                        Button("Unmute all") { change("Every conversation can alert again.") { $0.mutedThreads = [] } }
                            .buttonStyle(.borderless).disabled(saving)
                    }
                }
                HStack {
                    Text(s.watchedThreads.isEmpty ? "No followed conversations"
                         : s.watchedThreads.count == 1 ? "1 followed conversation" : "\(s.watchedThreads.count) followed conversations")
                    Spacer()
                    if !s.watchedThreads.isEmpty {
                        Button("Clear") { change("Stopped following every conversation.") { $0.watchedThreads = [] } }
                            .buttonStyle(.borderless).disabled(saving)
                    }
                }
            } header: {
                Text("Conversations")
            } footer: {
                Text("Mute or follow a conversation from a message's menu. Security alerts (new sign-ins, password and two-step changes) always come through.")
            }

            if saving || note != nil {
                Section {
                    Text(saving ? "Saving…" : (note ?? ""))
                        .font(.footnote)
                        .foregroundColor(GM.textSecondary)
                        .accessibilityIdentifier("notify-note")
                }
            }
        }
    }
}
