import SwiftUI

/// The shared shell for the eight screens that hang off the mailbox: labels,
/// label create, contacts, contact detail, files, attachment preview, settings,
/// screener.
struct MailScaffold<Content: View>: View {
    let title: String
    let onBack: () -> Void
    @ViewBuilder var content: Content

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button(action: onBack) {
                    Image(systemName: "chevron.backward")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(GM.textPrimary)
                }
                .accessibilityLabel("Back")
                Text(title)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                Spacer()
            }
            .padding(.horizontal, 12).padding(.vertical, 10)
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(GM.bg)
        .accessibilityIdentifier("gm-mail-\(title)")
    }
}

private struct SectionLabel: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 11, weight: .bold)).kerning(1)
            .foregroundColor(GM.textTertiary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20).padding(.vertical, 10)
    }
}

private struct NoteBar: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.system(size: 13)).foregroundColor(GM.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(GM.infoFill))
            .padding(.horizontal, 16).padding(.top, 12)
    }
}

private struct DetailLine: View {
    let label: String
    let value: String
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text(label).font(.system(size: 13)).foregroundColor(GM.textTertiary)
                .frame(width: 92, alignment: .leading)
            Text(value).font(.system(size: 14)).foregroundColor(GM.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 3)
    }
}

// ── 72 labels / 73 label create ──────────────────────────────────────────────

struct LabelsView: View {
    @ObservedObject var vm: MailboxModel
    let onBack: () -> Void
    let onCreate: () -> Void
    @State private var notice: String?

    var body: some View {
        MailScaffold(title: "Labels", onBack: onBack) {
            VStack(alignment: .leading, spacing: 0) {
                NoteBar(text: "Labels are the real filter: the reference's Personal " +
                            "and Work chips have no backing in the API, so these are " +
                            "what the chip row is built from.")
                HStack {
                    Text("\(vm.labelState.labels.count) label" +
                         (vm.labelState.labels.count == 1 ? "" : "s"))
                        .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                    Spacer()
                    Button("New label", action: onCreate)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(GM.primarySolid)
                }
                .padding(.horizontal, 16).padding(.vertical, 10)

                if let notice {
                    Text(notice).font(.system(size: 12)).foregroundColor(GM.textSecondary)
                        .padding(.horizontal, 16).padding(.bottom, 4)
                }

                if vm.labelState.loading && vm.labelState.labels.isEmpty {
                    Spacer(); ProgressView().padding(); Spacer()
                } else if vm.labelState.labels.isEmpty {
                    CenteredState(title: "No labels yet",
                                  detail: "Create one and it becomes a filter on the inbox.")
                } else {
                    List {
                        ForEach(vm.labelState.labels) { label in
                            HStack(spacing: 12) {
                                Circle()
                                    .fill(label.color?.hexColor ?? GM.textTertiary)
                                    .frame(width: 12, height: 12)
                                Text(label.name)
                                    .font(.system(size: 15)).foregroundColor(GM.textPrimary)
                                    .lineLimit(1)
                                Spacer()
                                Button {
                                    vm.deleteLabel(id: label.id) { notice = $0 }
                                } label: {
                                    Image(systemName: "trash")
                                        .font(.system(size: 15)).foregroundColor(GM.textTertiary)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Remove label \(label.name)")
                            }
                            .padding(.vertical, 4)
                            .listRowInsets(EdgeInsets(top: 6, leading: 20, bottom: 6, trailing: 16))
                        }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
        }
        .task { vm.refreshLabels() }
    }
}

struct LabelCreateView: View {
    @ObservedObject var vm: MailboxModel
    let onDone: () -> Void
    @State private var name = ""
    @State private var colour = GM.primarySolid
    @State private var notice: String?

    private struct Swatch {
        let name: String
        let color: Color
        var id: String { name }
    }

    private var ramp: [Swatch] {
        [
            Swatch(name: "Blue", color: GM.primarySolid),
            Swatch(name: "Green", color: GM.success),
            Swatch(name: "Amber", color: GM.warning),
            Swatch(name: "Violet", color: GM.violet),
            Swatch(name: "Red", color: GM.error),
        ]
    }

    var body: some View {
        MailScaffold(title: "New label", onBack: onDone) {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Name").font(.system(size: 12, weight: .bold))
                        .foregroundColor(GM.textTertiary)
                    TextField("Work", text: $name)
                        .font(.system(size: 15)).foregroundColor(GM.textPrimary)
                        .padding(12)
                        .background(RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(GM.inputFill))
                        .accessibilityIdentifier("label-name")
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("Colour").font(.system(size: 12, weight: .bold))
                        .foregroundColor(GM.textTertiary)
                    HStack(spacing: 12) {
                        ForEach(ramp) { swatch in
                            Circle().fill(swatch.color).frame(width: 28, height: 28)
                                .overlay(Circle().stroke(
                                    colour == swatch.color ? GM.textPrimary : Color.clear,
                                    lineWidth: 2))
                                .onTapGesture { colour = swatch.color }
                                .accessibilityLabel(swatch.name)
                        }
                    }
                }
                if let notice {
                    Text(notice).font(.system(size: 13)).foregroundColor(GM.error)
                }
                GradientButton(title: "Create label",
                               enabled: !name.trimmingCharacters(in: .whitespaces).isEmpty) {
                    let trimmed = name.trimmingCharacters(in: .whitespaces)
                    guard !trimmed.isEmpty else { notice = "A label needs a name."; return }
                    vm.createLabel(name: trimmed, colour: Self.hex(colour)) { result in
                        notice = result
                        vm.refreshLabels()
                        onDone()
                    }
                }
                Spacer()
            }
            .padding(20)
        }
    }

    /// `#RRGGBB`, the shape the route stores. The palette holds SwiftUI colours,
    /// so the value written to the server is read back off the colour itself
    /// rather than assumed from the swatch's name.
    static func hex(_ color: Color) -> String {
        let ui = UIColor(color)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        guard ui.getRed(&r, green: &g, blue: &b, alpha: &a) else { return "#4C7DF0" }
        return String(format: "#%02X%02X%02X",
                      Int(round(r * 255)), Int(round(g * 255)), Int(round(b * 255)))
    }
}

// ── 77 contacts / 85 contact detail ──────────────────────────────────────────

struct ContactsView: View {
    @ObservedObject var vm: MailboxModel
    let onBack: () -> Void
    let onOpen: (String) -> Void
    @State private var people: [MailboxModel.Person] = []
    @State private var query = ""

    var body: some View {
        MailScaffold(title: "Contacts", onBack: onBack) {
            VStack(alignment: .leading, spacing: 0) {
                NoteBar(text: "People are read out of the mail you have received and " +
                            "sent. GrapMail has no contacts endpoint, so this list " +
                            "grows with your mailbox rather than being edited by hand.")
                TextField("Search people", text: $query)
                    .font(.system(size: 14))
                    .padding(10)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(GM.inputFill))
                    .padding(.horizontal, 16)
                    .accessibilityIdentifier("contacts-search")

                let shown = filtered
                if people.isEmpty {
                    CenteredState(title: "No people yet",
                                  detail: "Contacts appear here as mail arrives from or to them.")
                } else {
                    List {
                        ForEach(shown, id: \.address) { p in
                            Button { onOpen(p.address) } label: {
                                HStack(spacing: 12) {
                                    SenderAvatar(display: p.name ?? "", address: p.address, size: 34)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(p.name ?? p.address)
                                            .font(.system(size: 15)).foregroundColor(GM.textPrimary)
                                            .lineLimit(1)
                                        if p.name != nil {
                                            Text(p.address)
                                                .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                                                .lineLimit(1)
                                        }
                                    }
                                    Spacer()
                                    if p.seen > 0 {
                                        Text("\(p.seen)")
                                            .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                                    }
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .listRowInsets(EdgeInsets(top: 6, leading: 20, bottom: 6, trailing: 16))
                        }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
        }
        .task { people = await vm.people() }
    }

    private var filtered: [MailboxModel.Person] {
        let needle = query.trimmingCharacters(in: .whitespaces)
        guard !needle.isEmpty else { return people }
        return people.filter {
            $0.address.localizedCaseInsensitiveContains(needle)
            || $0.name?.localizedCaseInsensitiveContains(needle) == true
        }
    }
}

struct ContactDetailView: View {
    @ObservedObject var vm: MailboxModel
    let address: String
    let onBack: () -> Void
    let onWrite: () -> Void
    @State private var person: MailboxModel.Person?

    var body: some View {
        MailScaffold(title: "Contact", onBack: onBack) {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    SenderAvatar(display: person?.name ?? "", address: address, size: 52)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(person?.name ?? address)
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(GM.textPrimary)
                        if person?.name != nil {
                            Text(address).font(.system(size: 13))
                                .foregroundColor(GM.textSecondary)
                        }
                    }
                    Spacer()
                }
                DetailLine(label: "Messages seen",
                           value: person.map { "\($0.seen)" } ?? "Not loaded")
                Text("Derived from loaded mail")
                    .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                GradientButton(title: "Write", action: onWrite)
                Spacer()
            }
            .padding(20)
        }
        .task { person = await vm.people().first { $0.address == address } }
    }
}

// ── 78 files / 79 attachment preview ─────────────────────────────────────────

struct FilesView: View {
    @ObservedObject var vm: MailboxModel
    let onBack: () -> Void
    let onOpen: (String) -> Void
    @State private var entries: [MailboxModel.FileEntry] = []

    var body: some View {
        MailScaffold(title: "Files", onBack: onBack) {
            VStack(alignment: .leading, spacing: 0) {
                NoteBar(text: "GrapMail has no endpoint that lists every file in a " +
                            "mailbox, so this is the attachments inside messages you " +
                            "have opened — a file inside mail you have not read is " +
                            "not shown here.")
                if entries.isEmpty {
                    CenteredState(title: "No files yet",
                                  detail: "Open a message with an attachment and it appears here.")
                } else {
                    List {
                        ForEach(entries, id: \.attachment.id) { entry in
                            Button { onOpen(entry.attachment.id) } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: "paperclip")
                                        .foregroundColor(GM.primarySolid)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(entry.attachment.name ?? "Attachment")
                                            .font(.system(size: 15)).foregroundColor(GM.textPrimary)
                                            .lineLimit(1)
                                        if let from = entry.fromMessage {
                                            Text(from).font(.system(size: 12))
                                                .foregroundColor(GM.textTertiary).lineLimit(1)
                                        }
                                    }
                                    Spacer()
                                    if let n = entry.attachment.sizeBytes {
                                        Text(humanBytes(n)).font(.system(size: 12))
                                            .foregroundColor(GM.textTertiary)
                                    }
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .listRowInsets(EdgeInsets(top: 8, leading: 20, bottom: 8, trailing: 16))
                        }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
        }
        .task { entries = await vm.files() }
    }
}

struct AttachmentPreviewView: View {
    @ObservedObject var vm: MailboxModel
    let attachmentId: String
    let onBack: () -> Void
    @State private var entry: MailboxModel.FileEntry?
    @State private var outcome: String?
    @State private var saving = false

    var body: some View {
        MailScaffold(title: "Attachment", onBack: onBack) {
            VStack(alignment: .leading, spacing: 0) {
                if let a = entry?.attachment {
                    VStack(alignment: .leading, spacing: 12) {
                        Spacer().frame(height: 16)
                        ZStack {
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(GM.softFill).frame(width: 84, height: 84)
                            Image(systemName: "paperclip")
                                .font(.system(size: 32)).foregroundColor(GM.primarySolid)
                        }
                        Text(a.name ?? "Attachment")
                            .font(.system(size: 19, weight: .bold))
                            .foregroundColor(GM.textPrimary)
                        // Each field says "Not recorded" when the server did not
                        // name it. A guessed mime type would tell the user a file
                        // is an image when nothing said so.
                        DetailLine(label: "Type",
                                   value: a.mimeType?.nilIfBlank() ?? "Not recorded")
                        DetailLine(label: "Size",
                                   value: a.sizeBytes.map { humanBytes($0) } ?? "Not recorded")
                        if let from = entry?.fromMessage {
                            DetailLine(label: "From message", value: from)
                        }
                        if let outcome {
                            Text(outcome).font(.system(size: 12))
                                .foregroundColor(GM.textSecondary).lineLimit(3)
                        }
                        GradientButton(title: saving ? "Saving…" : "Save to this device",
                                       enabled: !saving) {
                            saving = true
                            vm.downloadAttachment(a) { r in
                                saving = false
                                outcome = r
                            }
                        }
                        .padding(.top, 10)
                    }
                    .padding(.horizontal, 20)
                } else {
                    CenteredState(
                        title: "This attachment is no longer listed.",
                        detail: "Open its message again.")
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .task {
            entry = await vm.files().first { $0.attachment.id == attachmentId }
        }
    }
}

// ── 80 settings ──────────────────────────────────────────────────────────────

struct MailSettingsView: View {
    @ObservedObject var vm: MailboxModel
    let onBack: () -> Void
    let onSecurity: () -> Void
    let onScreener: () -> Void
    let onLabels: () -> Void
    let onHelp: () -> Void
    let onSignOut: () -> Void
    @State private var aliases: [MailAlias] = []

    var body: some View {
        MailScaffold(title: "Settings", onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    SectionLabel(text: "Account")
                    // Every unanswered field says so. "Not reported" is the
                    // truth; a blank or a default would read as a fact about
                    // the account.
                    SettingRow(icon: "envelope", title: "GrapMail address",
                               value: vm.state.accountAddress ?? "Not loaded")
                    SettingRow(icon: "star", title: "Mailbox status",
                               value: vm.state.accountStatus ?? "Not reported")
                    SettingRow(icon: "lock", title: "Member since",
                               value: vm.state.memberSince ?? "Not reported")

                    SectionLabel(text: "Sending as")
                    if aliases.isEmpty {
                        Text("No aliases on this mailbox. An alias is a second address " +
                             "that delivers into the same inbox.")
                            .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                            .padding(.horizontal, 20).padding(.vertical, 8)
                    } else {
                        ForEach(aliases) { a in
                            SettingRow(icon: "person.badge.plus", title: a.label,
                                       value: a.address,
                                       trailing: a.isActive ? nil : "paused")
                        }
                    }

                    SectionLabel(text: "Mailbox")
                    SettingRow(icon: "checkmark.shield", title: "Account security",
                               value: "Password, two-step, sessions", onClick: onSecurity)
                    SettingRow(icon: "lock", title: "Screener",
                               value: "Approval for unknown senders", onClick: onScreener)
                    SettingRow(icon: "tag", title: "Labels",
                               value: "Filters for the inbox", onClick: onLabels)

                    SectionLabel(text: "Support")
                    SettingRow(icon: "questionmark.circle", title: "Help & Support",
                               value: "grapsee.com/contact", onClick: onHelp)

                    Button(action: onSignOut) {
                        Text("Sign out")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(GM.error)
                            .padding(.horizontal, 20).padding(.vertical, 14)
                    }
                    .accessibilityIdentifier("mail-sign-out")
                }
                .padding(.bottom, 30)
            }
        }
        .task { aliases = await vm.aliases() }
    }
}

private struct SettingRow: View {
    let icon: String
    let title: String
    let value: String
    var trailing: String? = nil
    var onClick: (() -> Void)? = nil

    var body: some View {
        content
            .padding(.horizontal, 20).padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { onClick?() }
    }

    private var content: some View {
        HStack(spacing: 14) {
            Image(systemName: icon).font(.system(size: 17))
                .foregroundColor(GM.textSecondary).frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15)).foregroundColor(GM.textPrimary)
                Text(value).font(.system(size: 12)).foregroundColor(GM.textSecondary)
                    .lineLimit(1)
            }
            Spacer()
            if let trailing {
                Text(trailing).font(.system(size: 12)).foregroundColor(GM.warnInk)
            }
        }
    }
}

// ── 81 screener ──────────────────────────────────────────────────────────────

/// The sender-approval queue's settings.
///
/// `enabled` reads "Checking…" until the server answers. A switch drawn as off
/// after a failed read would tell the person their inbound mail is unfiltered
/// when nothing was said either way — the same rule the security hub learned
/// when an unanswered 2FA verdict started rendering as "Off".
struct ScreenerView: View {
    @ObservedObject var vm: MailboxModel
    let onBack: () -> Void
    let onOpenQueue: () -> Void

    var body: some View {
        MailScaffold(title: "Screener", onBack: onBack) {
            VStack(alignment: .leading, spacing: 14) {
                Text("Mail from senders you have not approved waits in a queue instead " +
                     "of reaching the inbox. The queue is a real folder, so a mail in it " +
                     "is not lost — it is held.")
                    .font(.system(size: 14)).foregroundColor(GM.textSecondary)

                HStack {
                    Text("Screener").font(.system(size: 15)).foregroundColor(GM.textPrimary)
                    Spacer()
                    if !vm.screener.loaded {
                        Text("Checking…").font(.system(size: 13)).foregroundColor(GM.textTertiary)
                    } else if let enabled = vm.screener.enabled {
                        Toggle("", isOn: Binding(
                            get: { enabled },
                            set: { vm.setScreener(enabled: $0) }))
                            .labelsHidden()
                            .disabled(vm.screener.busy)
                        Text(enabled ? "On — unknown senders wait" : "Off — all senders reach the inbox")
                            .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                    } else {
                        Text("Not reported").font(.system(size: 13)).foregroundColor(GM.warnInk)
                    }
                }

                HStack {
                    Text("Waiting now").font(.system(size: 15)).foregroundColor(GM.textPrimary)
                    Spacer()
                    Text(vm.screener.pending.map { "\($0)" } ?? "Not reported")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(GM.primarySolid)
                }

                if let error = vm.screener.error {
                    Text(error).font(.system(size: 13)).foregroundColor(GM.error)
                }

                GradientButton(title: "Open the queue", action: onOpenQueue)
                Spacer()
            }
            .padding(20)
        }
        .task { vm.loadScreener() }
    }
}
