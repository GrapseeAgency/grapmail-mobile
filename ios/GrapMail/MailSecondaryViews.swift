import SwiftUI
import UserNotifications

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
    /// Tapping a label's row edits it. The row already looked tappable and did
    /// nothing, which is the one thing this sector was told not to ship.
    let onEdit: (MailLabel) -> Void
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
                    // Not “becomes a filter on the inbox”, which is what this said
                    // on both platforms: the listing returns no label field per row
                    // and takes no label query, so nothing filters by label today.
                    // A promise the app cannot keep is the same defect as a control
                    // that goes nowhere, and a parity diff cannot see it because the
                    // two sides agree on the wrong words.
                    CenteredState(title: "No labels yet",
                                  detail: "Create one and it becomes available in the Label sheet.")
                } else {
                    List {
                        ForEach(vm.labelState.labels) { label in
                            HStack(spacing: 12) {
                                Circle()
                                    .fill(labelSwatch(label.color))
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
                            // The row itself is the edit handle, and the delete
                            // button keeps its own tap because it sits inside this
                            // shape rather than being wrapped in a second Button —
                            // a Button inside a Button swallows one of them.
                            .contentShape(Rectangle())
                            .onTapGesture { onEdit(label) }
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
    /// nil means "create". One screen for both, because the fields, the ramp and
    /// the name rule are the same control, and a second copy of them is a second
    /// place for the two to disagree.
    var editing: MailLabel? = nil
    @State private var name: String
    /// The server's palette KEY, not a colour. The route stores one of eight words
    /// and `normaliseColour` replaces anything else with `slate`, so this screen
    /// used to send a hex string — succeed with 201 — and the label came back slate
    /// whatever the user had picked. Holding the key makes the round trip verifiable.
    @State private var colour: String
    @State private var notice: String?

    init(vm: MailboxModel, onDone: @escaping () -> Void, editing: MailLabel? = nil) {
        self.vm = vm
        self.onDone = onDone
        self.editing = editing
        _name = State(initialValue: editing?.name ?? "")
        let stored = editing?.color
        _colour = State(initialValue: stored.flatMap { labelColourKeys.contains($0) ? $0 : nil }
            ?? "slate")
    }

    private struct Swatch: Identifiable {
        let key: String
        var id: String { key }
    }

    /// The eight keys the route accepts, in its order — not five colours chosen
    /// locally. A picker that offers a subset of the server's vocabulary is a form
    /// whose options are defined by whoever last edited the file rather than by the
    /// contract, and the two lists have already drifted apart once here.
    private var ramp: [Swatch] { labelColourKeys.map(Swatch.init(key:)) }

    /// The ramp in rows of four. Written as a loop rather than `chunked(4)`: that
    /// is Kotlin's API on Android (`MailSecondaryScreens.kt` uses it), and Swift's
    /// standard library has no such method — the shape of the expression is
    /// identical, which is precisely why it passes a quick read.
    private var rampRows: [[Swatch]] {
        var rows: [[Swatch]] = []
        let all = ramp
        var i = 0
        while i < all.count {
            rows.append(Array(all[i..<min(i + 4, all.count)]))
            i += 4
        }
        return rows
    }

    var body: some View {
        MailScaffold(title: editing == nil ? "New label" : "Edit label", onBack: onDone) {
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
                    // Two rows of four: eight swatches on one line does not fit a
                    // small phone once the screen's own padding is taken off, and a
                    // clipped last swatch is an option that cannot be chosen.
                    ForEach(Array(rampRows.enumerated()), id: \.offset) { _, row in
                        HStack(spacing: 12) {
                            ForEach(row) { swatch in
                                Circle().fill(labelSwatch(swatch.key)).frame(width: 28, height: 28)
                                    .overlay(Circle().stroke(
                                        colour == swatch.key ? GM.textPrimary : Color.clear,
                                        lineWidth: 2))
                                    .onTapGesture { colour = swatch.key }
                                    .accessibilityLabel(swatch.key)
                                    .accessibilityIdentifier("label-colour-\(swatch.key)")
                            }
                        }
                    }
                }
                if let notice {
                    Text(notice).font(.system(size: 13)).foregroundColor(GM.error)
                }
                GradientButton(title: editing == nil ? "Create label" : "Save changes",
                               enabled: !name.trimmingCharacters(in: .whitespaces).isEmpty) {
                    let trimmed = name.trimmingCharacters(in: .whitespaces)
                    guard !trimmed.isEmpty else { notice = "A label needs a name."; return }
                    // The key is sent as it stands.
                    //
                    // The server's own words either way: a create that hit the label
                    // cap and a rename the route refused both say why, and that text
                    // is what the person sees. A create is only dismissed when the
                    // route accepted it — this used to call `onDone()` unconditionally
                    // after setting `notice`, so a rejected label (duplicate name, or
                    // the 200 cap) navigated away with the reason never readable and
                    // the user left believing the label existed.
                    if let label = editing {
                        vm.renameLabel(id: label.id, name: trimmed, colour: colour) { result in
                            notice = result
                            vm.refreshLabels()
                            if result == "Saved." { onDone() }
                        }
                    } else {
                        vm.createLabel(name: trimmed, colour: colour) { result in
                            notice = result
                            vm.refreshLabels()
                            if result.hasPrefix("Created") { onDone() }
                        }
                    }
                }
                Spacer()
            }
            .padding(20)
        }
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
    @State private var opening = false
    /// Set once the file is fetched into the cache; presenting on this rather
    /// than a Bool means QuickLook can never open with nothing to show.
    @State private var previewURL: IdentifiableURL?
    // The delete is a two-step: the button asks, the panel confirms, and only
    // then does the route see it. `DELETE /attachments/:id` takes the file out
    // of the message for good; the body cache is dropped on success, so the
    // screen goes to the honest "removed" state rather than keeping a card for
    // bytes the server has thrown away.
    @State private var confirmDelete = false
    @State private var deleting = false
    @State private var deleted = false

    var body: some View {
        MailScaffold(title: "Attachment", onBack: onBack) {
            VStack(alignment: .leading, spacing: 0) {
                if let a = entry?.attachment, !deleted {
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
                        // The other half of the affordance. Saving to a path
                        // the person cannot reach from inside the app is only
                        // useful if they can then open the file — reading it
                        // is the point of an attachment. QuickLook's own
                        // toolbar carries the share button, so handing the
                        // file to another app arrives with the preview rather
                        // than as a second half-wired path.
                        Button {
                            opening = true
                            vm.prepareAttachment(a) { url, error in
                                opening = false
                                if let url { previewURL = IdentifiableURL(url: url) }
                                else { outcome = error ?? "This attachment could not be opened." }
                            }
                        } label: {
                            Text(opening ? "Opening…" : "Open in another app")
                                .font(.system(size: 15, weight: .medium))
                                .foregroundColor(GM.primarySolid)
                                .padding(.vertical, 14).padding(.horizontal, 18)
                                .frame(maxWidth: .infinity)
                                .background(Capsule().stroke(GM.border, lineWidth: 1.5))
                        }
                        .disabled(opening || saving)
                        .accessibilityIdentifier("mail-attachment-open")
                        .padding(.top, 10)
                        if !confirmDelete {
                            Button {
                                confirmDelete = true
                            } label: {
                                Text("Remove from this message")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundColor(GM.error)
                                    .frame(maxWidth: .infinity).padding(.vertical, 12)
                                    .overlay(RoundedRectangle(cornerRadius: 24)
                                        .stroke(GM.error, lineWidth: 1))
                            }
                            .disabled(deleting)
                            .accessibilityIdentifier("mail-attachment-delete")
                        } else {
                            VStack(alignment: .leading, spacing: 12) {
                                Text("Remove \(a.name ?? "this file") from the message? " +
                                     "The message keeps everything else; this file is " +
                                     "gone from it for good.")
                                    .font(.system(size: 13)).foregroundColor(GM.textPrimary)
                                HStack(spacing: 24) {
                                    Button("Cancel") { confirmDelete = false }
                                        .foregroundColor(GM.textSecondary)
                                    Button(deleting ? "Removing…" : "Remove") {
                                        deleting = true
                                        vm.deleteAttachment(a.id) { r in
                                            deleting = false; confirmDelete = false
                                            deleted = true; outcome = r
                                        }
                                    }
                                    .foregroundColor(GM.error)
                                    .fontWeight(.semibold)
                                    .disabled(deleting)
                                }
                                .font(.system(size: 14))
                            }
                            .padding(16)
                            .background(RoundedRectangle(cornerRadius: 12).fill(GM.warnChipBg))
                            .accessibilityIdentifier("mail-attachment-confirm")
                        }
                    }
                    .padding(.horizontal, 20)
                } else {
                    CenteredState(
                        title: deleted
                            ? "This attachment has been removed from the message. "
                              + "The message itself is unchanged."
                            : "This attachment is no longer listed.",
                        detail: deleted ? nil : "Open its message again.")
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .task {
            entry = await vm.files().first { $0.attachment.id == attachmentId }
        }
        // Presented on the URL itself, not a Bool: QuickLook is only ever
        // opened with a file that actually arrived, and dismissing clears it
        // so a second tap re-fetches rather than replaying a stale copy.
        .sheet(item: $previewURL) { item in
            QuickLookPresenter(url: item.url)
                .ignoresSafeArea()
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
    // The account-record band — the same rows Android's MailSettingsScreen
    // gained; each one opens a screen over the one profile document.
    let onNotes: () -> Void
    let onTasks: () -> Void
    let onPrefs: () -> Void
    let onAddresses: () -> Void
    let onConnections: () -> Void
    let onSubscriptions: () -> Void
    let onProfile: () -> Void
    let onPrivacy: () -> Void
    let onEncryption: () -> Void
    let onHelp: () -> Void
    let onSignOut: () -> Void

    /// The system's own answer about alerts for this app, read when the screen
    /// appears — never assumed, never remembered from a previous visit.
    @State private var notificationStatus: UNAuthorizationStatus? = nil

    /// The row's words, straight from that reading. `nil` means the read has
    /// not landed, and "Checking…" is the only honest thing to print for it.
    private var notificationValue: String {
        switch notificationStatus {
        case nil: return "Checking this device's setting"
        case .authorized, .provisional:
            return "On — GrapMail checks for new mail when iOS wakes it"
        case .denied:
            return "Off — turn it on in Settings › GrapMail › Notifications"
        case .notDetermined:
            return "Not asked yet — tap to allow new-mail alerts"
        default:
            return "Not allowed to alert from this device"
        }
    }

    private func readNotificationStatus() {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let status = settings.authorizationStatus
            Task { @MainActor in notificationStatus = status }
        }
    }

    private func askNotifications() {
        switch notificationStatus {
        case .notDetermined:
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { _, _ in
                // A just-granted permission is the last thing push registration
                // was waiting for; reconcile re-checks the real state and only
                // then asks the system for a token.
                Task { @MainActor in NotificationRegistration.shared.reconcile() }
                readNotificationStatus()
            }
        case .denied, .authorized, .provisional, .none:
            // The system will not show a second prompt after an answer, so the
            // row opens the app's Settings pane instead of pretending.
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        default:
            break
        }
    }

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
                    // Three answers, not two: while the read is outstanding the
                    // screen must not claim the mailbox has no aliases.
                    switch vm.aliasesState.read {
                    case .notRead, .reading:
                        Text("Checking…")
                            .font(.system(size: 13)).foregroundColor(GM.textTertiary)
                            .padding(.horizontal, 20).padding(.vertical, 8)
                    case .failed where vm.aliasesState.list.isEmpty:
                        Text(vm.aliasesState.error ?? "The alias list did not load.")
                            .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                            .padding(.horizontal, 20).padding(.vertical, 8)
                    default:
                        if vm.aliasesState.list.isEmpty {
                            Text("No aliases on this mailbox. An alias is a second address " +
                                 "that delivers into the same inbox.")
                                .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                                .padding(.horizontal, 20).padding(.vertical, 8)
                        } else {
                            ForEach(vm.aliasesState.list) { a in
                                // No "paused" chip: `GET /aliases` answers three
                                // columns and none of them is a state, so this
                                // list can only say what the mailbox receives
                                // as. The Addresses screen shows paused — the
                                // profile's alias rows DO answer `isActive`.
                                SettingRow(icon: "person.badge.plus", title: a.label,
                                           value: a.address)
                            }
                        }
                    }

                    SectionLabel(text: "Mailbox")
                    SettingRow(icon: "checkmark.shield", title: "Account security",
                               value: "Password, two-step, sessions", onClick: onSecurity)
                    SettingRow(icon: "lock", title: "Screener",
                               value: "Approval for unknown senders", onClick: onScreener)
                    SettingRow(icon: "tag", title: "Labels",
                               value: "Filters for the inbox", onClick: onLabels)
                    SettingRow(icon: "envelope", title: "Mail settings",
                               value: "Signature, density, language, senders", onClick: onPrefs)
                    SettingRow(icon: "person.badge.plus", title: "Addresses",
                               value: "Primary and aliases", onClick: onAddresses)
                    SettingRow(icon: "person", title: "Connected accounts",
                               value: "Apps that can use this sign-in", onClick: onConnections)
                    SettingRow(icon: "envelope", title: "Subscriptions",
                               value: "Senders that mail you most", onClick: onSubscriptions)
                    SettingRow(icon: "note.text", title: "Notes",
                               value: "Kept on the account", onClick: onNotes)
                    SettingRow(icon: "checkmark", title: "Tasks",
                               value: "The list beside the mailbox", onClick: onTasks)

                    SectionLabel(text: "This account")
                    // New-mail alerts are local polling, not push: there is no
                    // push service in this backend, so the honest description of
                    // this row is what the app actually does — iOS wakes it, it
                    // asks `GET /stats`, and it says what arrived since the last
                    // read. The value line reports the system's real
                    // authorization state, read when this screen appears, and a
                    // tap asks once. Denied is said as denied with the way out,
                    // because a row that reads "On" after a refusal is a control
                    // wired to nothing — and the ask is here rather than at
                    // launch, so nobody is interrupted by an app they have not
                    // used yet.
                    SettingRow(icon: "bell", title: "New mail notifications",
                               value: notificationValue,
                               onClick: { askNotifications() })
                        .accessibilityIdentifier("mail-notifications-row")
                    SettingRow(icon: "person", title: "Personal info",
                               value: "Name, birthday, photo", onClick: onProfile)
                    SettingRow(icon: "checkmark.shield", title: "Data & privacy",
                               value: "What is kept, what you control", onClick: onPrivacy)
                    SettingRow(icon: "lock", title: "Encryption",
                               value: "Your end-to-end key, on this device", onClick: onEncryption)

                    SectionLabel(text: "Support")
                    SettingRow(icon: "questionmark.circle", title: "Help & Support",
                               value: "Questions this product actually raises", onClick: onHelp)

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
        .task {
            vm.loadAliases()
            // The notification row reports the system's answer, so it reads it
            // when the screen appears rather than carrying a remembered value
            // the person may have changed in Settings since.
            readNotificationStatus()
        }
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
                    // Same words as the Android row: two platforms naming one
                    // thing differently is how a screen stops being the same
                    // screen, and the walk marker has to match both.
                    Text("Waiting in the queue")
                        .font(.system(size: 15)).foregroundColor(GM.textPrimary)
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
