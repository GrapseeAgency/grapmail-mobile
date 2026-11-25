import SwiftUI
import UserNotifications

/// The shared shell for the eight screens that hang off the mailbox: labels,
/// label create, contacts, contact detail, files, attachment preview, settings,
/// screener.
///
/// Text size: every screen in this file follows Dynamic Type (ScaledFont.swift).
/// At the design's sizes nothing moves; above them rows wrap instead of cutting
/// their text, label/value pairs stack at the accessibility sizes, and screens
/// whose content can outgrow the window scroll. On wide windows (iPad, Split
/// View, Stage Manager, Mac, Vision Pro) each screen's content keeps a
/// readable or form width (`GMLayout`), decided by the space it is given —
/// never by the device.
struct MailScaffold<Content: View>: View {
    let title: String
    let onBack: () -> Void
    @ViewBuilder var content: Content

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button(action: onBack) {
                    Image(systemName: "chevron.backward")
                        .gmFont(17, .semibold, maxSize: 28)
                        .foregroundColor(GM.textPrimary)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Back")
                Text(title)
                    .font(.headline)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
            }
            .padding(.horizontal, 12).padding(.vertical, 4)
            // Screen states: every screen on this shell keeps its content when
            // the network goes, and says so here (ScreenStates.swift).
            GMOfflineBanner()
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(GM.bg)
        .accessibilityIdentifier("gm-mail-\(title)")
    }
}

private struct SectionLabel: View {
    /// A resource: every caller passes a literal, which the String Catalog
    /// extracts; uppercased in the device locale after lookup.
    let text: LocalizedStringResource
    var body: some View {
        Text(String(localized: text).uppercased())
            .gmFont(11, .bold).kerning(1)
            .foregroundColor(GM.textTertiary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20).padding(.vertical, 10)
            .accessibilityAddTraits(.isHeader)
    }
}

private struct NoteBar: View {
    /// A key: every caller passes one literal sentence.
    let text: LocalizedStringKey
    var body: some View {
        Text(text)
            .gmFont(13).foregroundColor(GM.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(GM.infoFill))
            .padding(.horizontal, 16).padding(.top, 12)
    }
}

private struct DetailLine: View {
    /// The label is a literal at every call site; the value is data.
    let label: LocalizedStringKey
    let value: String
    @Environment(\.dynamicTypeSize) private var dts
    /// The label column grows with its text, so a translated "Messages seen"
    /// is not cut off at large sizes.
    @ScaledMetric(relativeTo: .footnote) private var labelColumn: CGFloat = 92

    var body: some View {
        Group {
            if dts.isAccessibilitySize {
                // A fixed label column cannot hold accessibility-size text
                // beside its value on a phone: the label sits above instead.
                VStack(alignment: .leading, spacing: 2) {
                    labelText.frame(maxWidth: .infinity, alignment: .leading)
                    valueText
                }
            } else {
                HStack(alignment: .top, spacing: 10) {
                    labelText.frame(width: labelColumn, alignment: .leading)
                    valueText
                }
            }
        }
        .padding(.vertical, 3)
    }

    private var labelText: some View {
        Text(label).gmFont(13).foregroundColor(GM.textTertiary)
    }

    private var valueText: some View {
        Text(value).gmFont(14).foregroundColor(GM.textPrimary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A row of label + value (+ control) laid out exactly as designed at the
/// default size; above it the row stays on one line while it fits and stacks
/// when it does not, and at the accessibility sizes it always stacks.
private struct SecondaryFlowRow<Content: View>: View {
    @Environment(\.dynamicTypeSize) private var dts
    var spacing: CGFloat? = nil
    @ViewBuilder var content: () -> Content

    var body: some View {
        if dts <= .large {
            HStack(spacing: spacing, content: content)
        } else if dts.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 6, content: content)
        } else {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: spacing, content: content)
                VStack(alignment: .leading, spacing: 6, content: content)
            }
        }
    }
}

/// Content exactly where it was while it fits; the same content scrolling once
/// larger text or a short window makes it taller than the space it has.
private struct SecondaryFitOrScroll<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        ViewThatFits(in: .vertical) {
            content()
            ScrollView(.vertical) { content() }
        }
    }
}

private extension View {
    /// The column a secondary screen's content keeps on a wide window: filled
    /// leading-aligned on a phone (as before), capped and centred past `max`.
    func secondaryColumn(_ max: CGFloat) -> some View {
        frame(maxWidth: .infinity, alignment: .leading).gmMaxWidth(max)
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
    @Environment(\.dynamicTypeSize) private var dts
    @ScaledMetric(relativeTo: .body) private var swatchDot: CGFloat = 12

    var body: some View {
        MailScaffold(title: String(localized: "Labels"), onBack: onBack) {
            VStack(alignment: .leading, spacing: 0) {
                NoteBar(text: "Labels are the real filter: the reference's Personal and Work chips have no backing in the API, so these are what the chip row is built from.")
                // No Spacer here: at the accessibility sizes this row stacks,
                // and a Spacer in a column would take the list's height.
                GMAdaptiveStack(spacing: dts.isAccessibilitySize ? 6 : nil) {
                    Text(vm.labelState.labels.count == 1 ? LocalizedStringKey("1 label") : LocalizedStringKey("\(vm.labelState.labels.count) labels"))
                        .gmFont(13).foregroundColor(GM.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button("New label", action: onCreate)
                        .gmFont(14, .medium)
                        .foregroundColor(GM.primarySolid)
                }
                .padding(.horizontal, 16).padding(.vertical, 10)

                if let notice {
                    Text(notice).gmFont(12).foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 16).padding(.bottom, 4)
                }

                if vm.labelState.loading && vm.labelState.labels.isEmpty {
                    GMSkeletonList(rows: 5, avatar: false)
                } else if let error = vm.labelState.error, vm.labelState.labels.isEmpty {
                    // A refused read is not "no labels": say it failed, offer Retry.
                    GMErrorState(title: "Couldn't load your labels", message: error,
                                 onRetry: { vm.refreshLabels(force: true) })
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
                                    .frame(width: min(swatchDot, 22), height: min(swatchDot, 22))
                                Text(label.name)
                                    .gmFont(15).foregroundColor(GM.textPrimary)
                                    // A label's name is the row: it wraps at the
                                    // accessibility sizes rather than losing its end.
                                    .lineLimit(dts.isAccessibilitySize ? nil : 1)
                                Spacer()
                                Button {
                                    vm.deleteLabel(id: label.id) { notice = $0 }
                                } label: {
                                    Image(systemName: "trash")
                                        .gmFont(15, maxSize: 28).foregroundColor(GM.textTertiary)
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
            .secondaryColumn(GMLayout.readable)
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
    /// Swatches grow with the text, capped so a row of four still fits an
    /// iPhone SE at the largest accessibility size (4 × 44 + 3 × 12 < 280).
    @ScaledMetric(relativeTo: .body) private var swatchSide: CGFloat = 28

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

    @State private var saving = false

    var body: some View {
        MailScaffold(title: editing == nil ? String(localized: "New label") : String(localized: "Edit label"), onBack: onDone) {
            // Scrolls once large text or a landscape phone with the keyboard up
            // makes the form taller than the window; content stays at the top,
            // where the trailing Spacer used to hold it.
            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Name").gmFont(12, .bold)
                            .foregroundColor(GM.textTertiary)
                        TextField("Work", text: $name)
                            .gmFont(15).foregroundColor(GM.textPrimary)
                            .padding(12)
                            .background(RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(GM.inputFill))
                            .accessibilityIdentifier("label-name")
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Colour").gmFont(12, .bold)
                            .foregroundColor(GM.textTertiary)
                        // Two rows of four: eight swatches on one line does not fit a
                        // small phone once the screen's own padding is taken off, and a
                        // clipped last swatch is an option that cannot be chosen.
                        ForEach(Array(rampRows.enumerated()), id: \.offset) { _, row in
                            HStack(spacing: 12) {
                                ForEach(row) { swatch in
                                    Circle().fill(labelSwatch(swatch.key))
                                        .frame(width: min(swatchSide, 44), height: min(swatchSide, 44))
                                        .overlay(Circle().stroke(
                                            colour == swatch.key ? GM.textPrimary : Color.clear,
                                            lineWidth: 2))
                                        .onTapGesture { colour = swatch.key }
                                        // The palette key is a server word ("slate"); VoiceOver
                                        // reads its translation, looked up by that key. The
                                        // eight keys are listed in the String Catalog inventory.
                                        .accessibilityLabel(Text(LocalizedStringKey(swatch.key)))
                                        .accessibilityIdentifier("label-colour-\(swatch.key)")
                                }
                            }
                        }
                    }
                    if let notice {
                        Text(notice).gmFont(13).foregroundColor(GM.error)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    GradientButton(title: editing == nil ? String(localized: "Create label") : String(localized: "Save changes"),
                                   enabled: !saving && !name.trimmingCharacters(in: .whitespaces).isEmpty) {
                        let trimmed = name.trimmingCharacters(in: .whitespaces)
                        guard !trimmed.isEmpty else { notice = String(localized: "A label needs a name."); return }
                        // The key is sent as it stands.
                        //
                        // The server's own words either way: a create that hit the label
                        // cap and a rename the route refused both say why, and that text
                        // is what the person sees. A create is only dismissed when the
                        // route accepted it — this used to call `onDone()` unconditionally
                        // after setting `notice`, so a rejected label (duplicate name, or
                        // the 200 cap) navigated away with the reason never readable and
                        // the user left believing the label existed.
                        saving = true
                        let complete: (Bool, String) -> Void = { saved, result in
                            saving = false
                            if saved { onDone() } else { notice = result }
                        }
                        if let label = editing {
                            vm.renameLabel(id: label.id, name: trimmed, colour: colour, then: complete)
                        } else {
                            vm.createLabel(name: trimmed, colour: colour, then: complete)
                        }
                    }
                    Spacer()
                }
                .padding(20)
                .secondaryColumn(GMLayout.form)
            }
            .scrollDismissesKeyboard(.interactively)
        }
    }
}

// ── 77 contacts / 85 contact detail ──────────────────────────────────────────

struct ContactsView: View {
    let vm: MailboxModel
    let onBack: () -> Void
    let onOpen: (String) -> Void
    @State private var people: [MailboxModel.Person] = []
    @State private var query = ""
    @State private var matches: [MailboxModel.Person]?
    @State private var sourceVersion = 0
    /// False until the first read of the cache answers, so the empty state is
    /// never drawn before anything was asked.
    @State private var peopleLoaded = false
    @Environment(\.dynamicTypeSize) private var dts
    /// The avatar grows with the name beside it, capped so the text keeps the row.
    @ScaledMetric(relativeTo: .body) private var avatarSide: CGFloat = 34

    private var needle: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    private struct SearchKey: Equatable {
        let term: String
        let sourceVersion: Int
    }

    var body: some View {
        MailScaffold(title: String(localized: "Contacts"), onBack: onBack) {
            VStack(alignment: .leading, spacing: 0) {
                NoteBar(text: "People are read out of the mail you have received and sent. GrapMail has no contacts endpoint, so this list grows with your mailbox rather than being edited by hand.")
                TextField("Search people", text: $query)
                    .gmFont(14)
                    .padding(10)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(GM.inputFill))
                    .padding(.horizontal, 16)
                    .accessibilityIdentifier("contacts-search")

                let shown = needle.isEmpty ? people : (matches ?? [])
                if !peopleLoaded {
                    GMSkeletonList(rows: 6)
                } else if people.isEmpty {
                    CenteredState(title: "No people yet",
                                  detail: "Contacts appear here as mail arrives from or to them.")
                } else if !needle.isEmpty && matches == nil {
                    CenteredState(title: "Searching…")
                } else if shown.isEmpty {
                    CenteredState(title: "No matching contacts", detail: "Try another name or email address.")
                } else {
                    List {
                        ForEach(shown, id: \.address) { p in
                            Button { onOpen(p.address) } label: {
                                HStack(spacing: 12) {
                                    SenderAvatar(display: p.name ?? "", address: p.address,
                                                 size: min(avatarSide, 56))
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(p.name ?? p.address)
                                            .gmFont(15).foregroundColor(GM.textPrimary)
                                            .lineLimit(dts.isAccessibilitySize ? 3 : 1)
                                        if p.name != nil {
                                            Text(p.address)
                                                .gmFont(12).foregroundColor(GM.textTertiary)
                                                .lineLimit(dts.isAccessibilitySize ? 3 : 1)
                                        }
                                    }
                                    Spacer()
                                    if p.seen > 0 {
                                        Text("\(p.seen)")
                                            .gmFont(12).foregroundColor(GM.textTertiary)
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
            .secondaryColumn(GMLayout.readable)
        }
        .task {
            let loaded = await vm.people()
            guard !Task.isCancelled else { return }
            people = loaded
            peopleLoaded = true
            sourceVersion += 1
        }
        .task(id: SearchKey(term: needle, sourceVersion: sourceVersion)) {
            guard !needle.isEmpty else { matches = nil; return }
            let term = needle
            do { try await Task.sleep(nanoseconds: 120_000_000) }
            catch { return }
            let filtered = await Self.matching(people, term: term)
            guard !Task.isCancelled else { return }
            matches = filtered
        }
    }

    private nonisolated static func matching(_ people: [MailboxModel.Person], term: String) async -> [MailboxModel.Person] {
        people.filter {
            $0.address.localizedCaseInsensitiveContains(term)
                || $0.name?.localizedCaseInsensitiveContains(term) == true
        }
    }
}

struct ContactDetailView: View {
    let vm: MailboxModel
    let address: String
    let onBack: () -> Void
    let onWrite: () -> Void
    @State private var person: MailboxModel.Person?
    @Environment(\.dynamicTypeSize) private var dts
    @ScaledMetric(relativeTo: .title3) private var avatarSide: CGFloat = 52

    var body: some View {
        MailScaffold(title: String(localized: "Contact"), onBack: onBack) {
            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 14) {
                    // Avatar beside the name; at the accessibility sizes the
                    // name and address take the full width under the avatar.
                    GMAdaptiveStack(spacing: 12) {
                        SenderAvatar(display: person?.name ?? "", address: address,
                                     size: min(avatarSide, 80))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(person?.name ?? address)
                                .gmFont(18, .semibold)
                                .foregroundColor(GM.textPrimary)
                                .fixedSize(horizontal: false, vertical: true)
                            if person?.name != nil {
                                Text(address).gmFont(13)
                                    .foregroundColor(GM.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    DetailLine(label: "Messages seen",
                               value: person.map { "\($0.seen)" } ?? String(localized: "Not loaded"))
                    Text("Derived from loaded mail")
                        .gmFont(12).foregroundColor(GM.textTertiary)
                    GradientButton(title: String(localized: "Write", comment: "Button: write a message to this contact"), action: onWrite)
                    Spacer()
                }
                .padding(20)
                .secondaryColumn(GMLayout.form)
            }
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
    /// False until the first read answers (skeleton, not "No files yet").
    @State private var filesLoaded = false
    @Environment(\.dynamicTypeSize) private var dts

    var body: some View {
        MailScaffold(title: String(localized: "Files"), onBack: onBack) {
            VStack(alignment: .leading, spacing: 0) {
                NoteBar(text: "GrapMail has no endpoint that lists every file in a mailbox, so this is the attachments inside messages you have opened — a file inside mail you have not read is not shown here.")
                if !filesLoaded {
                    GMSkeletonList(rows: 5)
                } else if entries.isEmpty {
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
                                        Text(entry.attachment.name ?? String(localized: "Attachment"))
                                            .gmFont(15).foregroundColor(GM.textPrimary)
                                            .lineLimit(dts.isAccessibilitySize ? 3 : 1)
                                        if let from = entry.fromMessage {
                                            Text(from).gmFont(12)
                                                .foregroundColor(GM.textTertiary)
                                                .lineLimit(dts.isAccessibilitySize ? 3 : 1)
                                        }
                                        // At the accessibility sizes the size moves
                                        // under the name, so the name keeps the width.
                                        if dts.isAccessibilitySize, let n = entry.attachment.sizeBytes {
                                            Text(humanBytes(n)).gmFont(12)
                                                .foregroundColor(GM.textTertiary)
                                        }
                                    }
                                    Spacer()
                                    if !dts.isAccessibilitySize, let n = entry.attachment.sizeBytes {
                                        Text(humanBytes(n)).gmFont(12)
                                            .foregroundColor(GM.textTertiary)
                                    }
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            // Long-press to drag the file out, once this device
                            // holds it (AttachmentDrag.swift); the tap still opens it.
                            .attachmentDrag(vm.localAttachmentFile(entry.attachment.id),
                                            name: entry.attachment.name, mimeType: entry.attachment.mimeType)
                            .listRowInsets(EdgeInsets(top: 8, leading: 20, bottom: 8, trailing: 16))
                        }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
            .secondaryColumn(GMLayout.readable)
        }
        .task(id: vm.filesRevision) {
            let loaded = await vm.files()
            if !Task.isCancelled { entries = loaded; filesLoaded = true }
        }
    }
}

struct AttachmentPreviewView: View {
    let vm: MailboxModel
    let attachmentId: String
    let onBack: () -> Void
    @State private var entry: MailboxModel.FileEntry?
    @State private var loaded = false
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
    @Environment(\.dynamicTypeSize) private var dts
    /// The file tile grows with the text, capped so the card's buttons stay on
    /// screen without scrolling as long as possible.
    @ScaledMetric(relativeTo: .title2) private var tileSide: CGFloat = 84

    var body: some View {
        MailScaffold(title: String(localized: "Attachment"), onBack: onBack) {
            VStack(alignment: .leading, spacing: 0) {
                if let a = entry?.attachment, !deleted {
                    // Drawn where it always was while it fits; scrolls once large
                    // text or a landscape phone makes the card taller than the
                    // screen (the card has no text field, so the swap is safe).
                    SecondaryFitOrScroll {
                        card(a)
                    }
                } else if !loaded {
                    GMSkeletonBlock(lines: 4)
                        .padding(.horizontal, 20)
                } else {
                    CenteredState(
                        title: deleted
                            ? "This attachment has been removed from the message. The message itself is unchanged."
                            : "This attachment is no longer listed.",
                        detail: deleted ? nil : "Open its message again.")
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .task(id: attachmentId) {
            let result = await vm.file(attachmentId)
            guard !Task.isCancelled else { return }
            entry = result
            loaded = true
        }
        // Presented on the URL itself, not a Bool: QuickLook is only ever
        // opened with a file that actually arrived, and dismissing clears it
        // so a second tap re-fetches rather than replaying a stale copy.
        .sheet(item: $previewURL) { item in
            QuickLookPresenter(url: item.url)
                .ignoresSafeArea()
        }
    }

    private func card(_ a: MailAttachment) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Spacer().frame(height: 16)
            ZStack {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(GM.softFill).frame(width: min(tileSide, 124), height: min(tileSide, 124))
                Image(systemName: "paperclip")
                    .gmFont(32, maxSize: 48).foregroundColor(GM.primarySolid)
            }
            // The tile drags the file out once it is on this device
            // (AttachmentDrag.swift). Only the tile: QuickLook stays
            // on its own button below, so a long press here never
            // competes with that tap.
            .attachmentDrag(vm.localAttachmentFile(a.id), name: a.name, mimeType: a.mimeType)
            Text(a.name ?? String(localized: "Attachment"))
                .gmFont(19, .bold)
                .foregroundColor(GM.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            // Each field says "Not recorded" when the server did not
            // name it. A guessed mime type would tell the user a file
            // is an image when nothing said so.
            DetailLine(label: "Type",
                       value: a.mimeType?.nilIfBlank() ?? String(localized: "Not recorded"))
            DetailLine(label: "Size",
                       value: a.sizeBytes.map { humanBytes($0) } ?? String(localized: "Not recorded"))
            if let from = entry?.fromMessage {
                DetailLine(label: "From message", value: from)
            }
            if let outcome {
                Text(outcome).gmFont(12)
                    .foregroundColor(GM.textSecondary)
                    .lineLimit(dts.isAccessibilitySize ? nil : 3)
            }
            GradientButton(title: saving ? String(localized: "Saving…") : String(localized: "Save to this device"),
                           enabled: !saving && !opening && !deleting) {
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
                    else { outcome = error ?? String(localized: "This attachment could not be opened.") }
                }
            } label: {
                Text(opening ? "Opening…" : "Open in another app")
                    .gmFont(15, .medium)
                    .foregroundColor(GM.primarySolid)
                    .multilineTextAlignment(.center)
                    .padding(.vertical, 14).padding(.horizontal, 18)
                    .frame(maxWidth: .infinity)
                    .background(Capsule().stroke(GM.border, lineWidth: 1.5))
            }
            .disabled(opening || saving || deleting)
            .accessibilityIdentifier("mail-attachment-open")
            .padding(.top, 10)
            if !confirmDelete {
                Button {
                    confirmDelete = true
                } label: {
                    Text("Remove from this message")
                        .gmFont(14, .semibold)
                        .foregroundColor(GM.error)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 12)
                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                        .overlay(RoundedRectangle(cornerRadius: 24)
                            .stroke(GM.error, lineWidth: 1))
                }
                .disabled(deleting)
                .accessibilityIdentifier("mail-attachment-delete")
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    // Two whole sentences (named / unnamed file) rather than a
                    // translated "this file" spliced into a translated sentence.
                    Text(a.name.map {
                        String(localized: "Remove \($0) from the message? The message keeps everything else; this file is gone from it for good.")
                    } ?? String(localized: "Remove this file from the message? The message keeps everything else; this file is gone from it for good."))
                        .gmFont(13).foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    GMAdaptiveStack(spacing: dts.isAccessibilitySize ? 12 : 24) {
                        Button("Cancel") { confirmDelete = false }
                            .foregroundColor(GM.textSecondary)
                        Button(deleting ? "Removing…" : "Remove") {
                            deleting = true
                            vm.deleteAttachment(a.id) { removed, message in
                                deleting = false; confirmDelete = false
                                deleted = removed; outcome = message
                            }
                        }
                        .foregroundColor(GM.error)
                        .fontWeight(.semibold)
                        .disabled(deleting)
                    }
                    .gmFont(14)
                }
                .padding(16)
                .background(RoundedRectangle(cornerRadius: 12).fill(GM.warnChipBg))
                .accessibilityIdentifier("mail-attachment-confirm")
            }
        }
        .padding(.horizontal, 20)
        .secondaryColumn(GMLayout.form)
    }
}

// ── 80 settings ──────────────────────────────────────────────────────────────

struct MailSettingsView: View {
    @ObservedObject var vm: MailboxModel
    let onBack: () -> Void
    let onSecurity: () -> Void
    let onScreener: () -> Void
    let onScheduled: () -> Void
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
    // Optional with a default so the snapshot host's bare `secondary(...)`
    // call keeps compiling unchanged; the row simply renders without a
    // chevron when no push was wired.
    var onNotificationPrefs: (() -> Void)? = nil
    // Same contract as the row above: the per-account mute screen, reached
    // only where the route was wired.
    var onPerAccountNotifications: (() -> Void)? = nil
    // Connected mailboxes (Outlook / IMAP). Optional for the same reason:
    // the snapshot host does not wire it.
    var onProviders: (() -> Void)? = nil

    /// The system's own answer about alerts for this app, read when the screen
    /// appears — never assumed, never remembered from a previous visit.
    @State private var notificationStatus: UNAuthorizationStatus? = nil
    @State private var showPlan = false
    @State private var showNotificationFilters = false
    @State private var crashReports = CrashReporter.enabled
    @State private var showImport = false

    /// The row's words, straight from that reading. `nil` means the read has
    /// not landed, and "Checking…" is the only honest thing to print for it.
    private var notificationValue: String {
        switch notificationStatus {
        case nil: return String(localized: "Checking this device's setting")
        case .authorized, .provisional:
            return String(localized: "On — GrapMail checks for new mail when iOS wakes it")
        case .denied:
            return String(localized: "Off — turn it on in Settings › GrapMail › Notifications")
        case .notDetermined:
            return String(localized: "Not asked yet — tap to allow new-mail alerts")
        default:
            return String(localized: "Not allowed to alert from this device")
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
                Task { @MainActor in await NotificationRegistration.shared.reconcile() }
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
        MailScaffold(title: String(localized: "Settings"), onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    SectionLabel(text: "Account")
                    // Every unanswered field says so. "Not reported" is the
                    // truth; a blank or a default would read as a fact about
                    // the account.
                    SettingRow(icon: "envelope", title: String(localized: "GrapMail address"),
                               value: vm.state.accountAddress ?? String(localized: "Not loaded"))
                    SettingRow(icon: "star", title: String(localized: "Mailbox status"),
                               value: vm.state.accountStatus ?? String(localized: "Not reported"))
                    SettingRow(icon: "lock", title: String(localized: "Member since"),
                               value: vm.state.memberSince ?? String(localized: "Not reported"))

                    SectionLabel(text: "Sending as")
                    // Three answers, not two: while the read is outstanding the
                    // screen must not claim the mailbox has no aliases.
                    switch vm.aliasesState.read {
                    case .notRead, .reading:
                        Text("Checking…")
                            .gmFont(13).foregroundColor(GM.textTertiary)
                            .padding(.horizontal, 20).padding(.vertical, 8)
                    case .failed where vm.aliasesState.list.isEmpty:
                        Text(vm.aliasesState.error ?? String(localized: "The alias list did not load."))
                            .gmFont(13).foregroundColor(GM.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 20).padding(.vertical, 8)
                    default:
                        if vm.aliasesState.list.isEmpty {
                            Text("No aliases on this mailbox. An alias is a second address that delivers into the same inbox.")
                                .gmFont(13).foregroundColor(GM.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
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
                    SettingRow(icon: "checkmark.shield", title: String(localized: "Account security"),
                               value: String(localized: "Password, two-step, sessions"), onClick: onSecurity)
                    SettingRow(icon: "lock", title: String(localized: "Screener"),
                               value: String(localized: "Approval for unknown senders"), onClick: onScreener)
                    SettingRow(icon: "clock", title: String(localized: "Scheduled"),
                               value: String(localized: "Sends queued on the server"), onClick: onScheduled)
                    SettingRow(icon: "tag", title: String(localized: "Labels"),
                               value: String(localized: "Filters for the inbox"), onClick: onLabels)
                    SettingRow(icon: "envelope", title: String(localized: "Mail settings"),
                               value: String(localized: "Signature, auto-reply, forwarding, dates, senders"), onClick: onPrefs)
                    SettingRow(icon: "star", title: String(localized: "Your plan"),
                               value: String(localized: "Monthly sending allowance"), onClick: { showPlan = true })
                        .sheet(isPresented: $showPlan) { PlanView(onClose: { showPlan = false }) }
                    SettingRow(icon: "person.badge.plus", title: String(localized: "Addresses"),
                               value: String(localized: "Primary and aliases"), onClick: onAddresses)
                    SettingRow(icon: "person", title: String(localized: "Connected accounts"),
                               value: String(localized: "Apps that can use this sign-in"), onClick: onConnections)
                    SettingRow(icon: "tray.2", title: String(localized: "Connected mailboxes"),
                               value: String(localized: "Outlook, Yahoo, iCloud and other IMAP"), onClick: onProviders)
                    SettingRow(icon: "envelope", title: String(localized: "Subscriptions"),
                               value: String(localized: "Senders that mail you most"), onClick: onSubscriptions)
                    SettingRow(icon: "tray.and.arrow.down", title: String(localized: "Import mail"),
                               value: String(localized: "From Gmail, Yahoo, iCloud or another IMAP account"),
                               onClick: { showImport = true })
                        .accessibilityIdentifier("mail-import-row")
                    SettingRow(icon: "note.text", title: String(localized: "Notes"),
                               value: String(localized: "Kept on the account"), onClick: onNotes)
                    SettingRow(icon: "checkmark", title: String(localized: "Tasks"),
                               value: String(localized: "The list beside the mailbox"), onClick: onTasks)

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
                    SettingRow(icon: "bell", title: String(localized: "New mail notifications"),
                               value: notificationValue,
                               onClick: { askNotifications() })
                        .accessibilityIdentifier("mail-notifications-row")
                    // The account's per-scope alert switches (mail / security /
                    // account) and the mail quiet-hours window. The system
                    // authorization row above answers "may the app alert at
                    // all"; this one answers "which alerts".
                    SettingRow(icon: "bell.badge", title: String(localized: "Notification preferences"),
                               value: String(localized: "Mail, security and account alerts"),
                               onClick: onNotificationPrefs)
                        .accessibilityIdentifier("mail-notification-prefs-row")
                    // The per-account mute: which signed-in account may alert
                    // at all on this device. The row above shapes the alerts
                    // one account sends; this one is the off switch per
                    // account.
                    SettingRow(icon: "bell.slash", title: String(localized: "Per-account notifications"),
                               value: String(localized: "Silence one signed-in account on this device"),
                               onClick: onPerAccountNotifications)
                        .accessibilityIdentifier("mail-per-account-notifications-row")
                    // Client-side filtering (priority only, VIP senders,
                    // lock-screen previews) — a different layer from the
                    // server's per-scope alerts above.
                    SettingRow(icon: "bell.badge", title: String(localized: "Notification filters"),
                               value: String(localized: "Priority only, VIP senders, quiet hours, lock-screen previews"),
                               onClick: { showNotificationFilters = true })
                        .accessibilityIdentifier("mail-notification-filters-row")
                    // App lock — Face ID in front of the whole mailbox (AppLock.swift).
                    AppLockRow()
                    // Crash and hang reports to GrapMail's own server (RemoteConfig.swift).
                    SettingRow(icon: "ladybug", title: String(localized: "Send crash reports"),
                               value: crashReports ? String(localized: "On — app version, device and the error, never your mail")
                                                   : String(localized: "Off — crashes are not reported"),
                               onClick: { crashReports.toggle(); CrashReporter.enabled = crashReports })
                        .accessibilityIdentifier("mail-crash-reports-row")
                    SettingRow(icon: "person", title: String(localized: "Personal info"),
                               value: String(localized: "Name, birthday, photo"), onClick: onProfile)
                    SettingRow(icon: "checkmark.shield", title: String(localized: "Data & privacy"),
                               value: String(localized: "What is kept, what you control"), onClick: onPrivacy)
                    SettingRow(icon: "lock", title: String(localized: "Encryption"),
                               value: String(localized: "Your end-to-end key, on this device"), onClick: onEncryption)

                    SectionLabel(text: "Support")
                    SettingRow(icon: "questionmark.circle", title: String(localized: "Help & Support"),
                               value: String(localized: "Questions this product actually raises"), onClick: onHelp)

                    Button(action: onSignOut) {
                        Text("Sign out")
                            .gmFont(15, .semibold)
                            .foregroundColor(GM.error)
                            .padding(.horizontal, 20).padding(.vertical, 14)
                    }
                    .accessibilityIdentifier("mail-sign-out")
                }
                .padding(.bottom, 30)
                .secondaryColumn(GMLayout.form)
            }
        }
        .sheet(isPresented: $showNotificationFilters) { NotificationFiltersView() }
        .sheet(isPresented: $showImport) { ImportMailView() }
        .task {
            vm.loadAliases()
            // The notification row reports the system's answer, so it reads it
            // when the screen appears rather than carrying a remembered value
            // the person may have changed in Settings since.
            readNotificationStatus()
        }
    }
}

// ── 80a per-account notifications ────────────────────────────────────────────

/// One mute per account, on this device. The register route mints a device
/// row per signed-in account and answers its id; the PATCH on that row is
/// what silences the account — and it must travel with the muted account's
/// own bearer, which is why the toggle re-derives that account's bearer
/// instead of reusing the mailbox's active one. The switch is optimistic and
/// reverts with the reason on a refused write, the pattern the security
/// prefs' toggles follow. The device-wide previews toggle in the settings
/// stays untouched: this screen changes who hears, not what the preview
/// shows.
struct PerAccountNotificationsView: View {
    let onBack: () -> Void

    struct Row: Identifiable {
        let address: String
        var deviceRowId: String?
        var muted: Bool
        var preview: String
        var id: String { address.lowercased() }
    }

    @State private var rows: [Row] = []
    @State private var loaded = false
    @State private var saving: String?
    @State private var error: String?
    @Environment(\.dynamicTypeSize) private var dts

    var body: some View {
        MailScaffold(title: String(localized: "Per-account notifications"), onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if !loaded {
                        Text("Checking…")
                            .gmFont(13).foregroundColor(GM.textTertiary)
                    } else if rows.isEmpty {
                        Text("No accounts are signed in on this device.")
                            .gmFont(13).foregroundColor(GM.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    } else {
                        ForEach(rows) { row in
                            rowView(row)
                        }
                    }
                    if let error {
                        Text(error).gmFont(12).foregroundColor(GM.error)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Text("Silencing one account stops its mail alerts on this device. Mandatory security alerts still arrive — the server keeps those on for every account.")
                        .gmFont(12).foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(20)
                .secondaryColumn(GMLayout.form)
            }
        }
        .task { load() }
    }

    @ViewBuilder
    private func rowView(_ row: Row) -> some View {
        if dts.isAccessibilitySize {
            // The switch under the address: beside it, accessibility-size text
            // would leave the address a few characters wide.
            VStack(alignment: .leading, spacing: 8) {
                rowText(row)
                rowControl(row)
            }
            .padding(.vertical, 6)
        } else {
            HStack(spacing: 12) {
                rowText(row)
                Spacer(minLength: 8)
                rowControl(row)
            }
            .padding(.vertical, 6)
        }
    }

    private func rowText(_ row: Row) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(row.address)
                .gmFont(15).foregroundColor(GM.textPrimary)
                // The address is what tells one account from another: at the
                // accessibility sizes it wraps rather than losing its middle.
                .lineLimit(dts.isAccessibilitySize ? nil : 1).truncationMode(.middle)
            Text(row.deviceRowId == nil
                 ? "Not registered for push on this device"
                 : row.muted ? "Muted — this account's alerts are silenced"
                 : "Alerts arrive normally")
                .gmFont(12).foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    @ViewBuilder
    private func rowControl(_ row: Row) -> some View {
        if saving == row.address {
            ProgressView()
        } else {
            Toggle("Muted", isOn: Binding(
                get: { rows.first { $0.address == row.address }?.muted ?? row.muted },
                set: { setMuted(row, muted: $0) }
            ))
            .labelsHidden()
            .tint(GM.primarySolid)
            .disabled(row.deviceRowId == nil)
            .accessibilityIdentifier("per-account-mute-\(row.id)")
        }
    }

    // ── reads and writes ─────────────────────────────────────────────────────

    private func load() {
        // A local read, not a fetch: the sessions this device holds and the
        // mute record stored beside them are the whole state, and the server
        // is asked only when the person moves a switch.
        rows = SessionStore.accounts().map { account in
            let state = AccountPushPrefs.read(mailbox: account.address)
            let previews = UserDefaults.standard.object(forKey: "mailGenericPreviews") as? Bool
            return Row(
                address: account.address,
                deviceRowId: state?.deviceRowId,
                muted: state?.muted ?? false,
                preview: state?.preview ?? (previews == true ? "generic" : "full"),
            )
        }
        loaded = true
    }

    private func setMuted(_ row: Row, muted: Bool) {
        guard let deviceRowId = row.deviceRowId else { return }
        let previous = rows
        // Optimistic: the switch answers at once and reverts with the reason
        // if the server refuses the write.
        rows = rows.map {
            $0.address == row.address
                ? Row(address: $0.address, deviceRowId: $0.deviceRowId, muted: muted, preview: $0.preview)
                : $0
        }
        saving = row.address
        error = nil
        Task {
            do {
                // The mute must be authenticated AS the muted account, so the
                // bearer is that account's own — not the mailbox's active one.
                guard let bearer = await ApiClient.pushBearer(for: row.address) else {
                    throw ApiError.http(status: 401, code: nil, message: String(localized: "Sign in again."))
                }
                let answer = try await ApiClient.setAccountPushPreferences(
                    token: bearer, deviceRowId: deviceRowId,
                    enabled: !muted, preview: row.preview)
                let confirmed = !answer.enabled
                _ = AccountPushPrefs.setMuted(confirmed, mailbox: row.address)
                rows = rows.map {
                    $0.address == row.address
                        ? Row(address: $0.address, deviceRowId: $0.deviceRowId, muted: confirmed, preview: $0.preview)
                        : $0
                }
            } catch {
                rows = previous
                self.error = apiMessage(error, fallback: String(localized: "The change did not save. Try again."))
            }
            saving = nil
        }
    }
}

private struct SettingRow: View {
    let icon: String
    let title: String
    let value: String
    var trailing: String? = nil
    var onClick: (() -> Void)? = nil

    /// The icon column grows with the icon, which grows with the title.
    @ScaledMetric(relativeTo: .body) private var iconColumn: CGFloat = 22

    var body: some View {
        if let onClick {
            Button(action: onClick) { content }
                .buttonStyle(.plain)
        } else {
            content
        }
    }

    private var content: some View {
        HStack(spacing: 14) {
            Image(systemName: icon).gmFont(17, maxSize: 30)
                .foregroundColor(GM.textSecondary).frame(width: min(iconColumn, 34))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body.weight(.medium)).foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(value).font(.footnote).foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let trailing {
                    Text(trailing).font(.caption).foregroundColor(GM.warnInk)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if onClick != nil {
                Image(systemName: "chevron.forward")
                    .font(.caption.weight(.semibold))
                    .foregroundColor(GM.textSecondary)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
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
        MailScaffold(title: String(localized: "Screener"), onBack: onBack) {
            // Scrolls once large text makes the screen taller than the window;
            // content stays at the top, where the trailing Spacer held it.
            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Mail from senders you have not approved waits in a queue instead of reaching the inbox. The queue is a real folder, so a mail in it is not lost — it is held.")
                        .gmFont(14).foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)

                    // On one line as designed; stacked once the switch and its
                    // status no longer fit beside the title.
                    SecondaryFlowRow {
                        Text("Screener").gmFont(15).foregroundColor(GM.textPrimary)
                        Spacer()
                        if !vm.screener.loaded {
                            Text("Checking…").gmFont(13).foregroundColor(GM.textTertiary)
                        } else if let enabled = vm.screener.enabled {
                            Toggle("", isOn: Binding(
                                get: { enabled },
                                set: { vm.setScreener(enabled: $0) }))
                                .labelsHidden()
                                .disabled(vm.screener.busy)
                                .accessibilityLabel(Text("Screener"))
                            Text(enabled ? "On — unknown senders wait" : "Off — all senders reach the inbox")
                                .gmFont(12).foregroundColor(GM.textTertiary)
                                .fixedSize(horizontal: false, vertical: true)
                        } else {
                            Text("Not reported").gmFont(13).foregroundColor(GM.warnInk)
                        }
                    }

                    SecondaryFlowRow {
                        // Same words as the Android row: two platforms naming one
                        // thing differently is how a screen stops being the same
                        // screen, and the walk marker has to match both.
                        Text("Waiting in the queue")
                            .gmFont(15).foregroundColor(GM.textPrimary)
                        Spacer()
                        Text(vm.screener.pending.map { "\($0)" } ?? String(localized: "Not reported"))
                            .gmFont(15, .semibold)
                            .foregroundColor(GM.primarySolid)
                    }

                    if let error = vm.screener.error {
                        Text(error).gmFont(13).foregroundColor(GM.error)
                            .fixedSize(horizontal: false, vertical: true)
                        if !vm.screener.busy { Button("Retry") { vm.loadScreener() } }
                    }

                    GradientButton(title: String(localized: "Open the queue"), action: onOpenQueue)
                    Spacer()
                }
                .padding(20)
                .secondaryColumn(GMLayout.form)
            }
        }
        .task { vm.loadScreener() }
    }
}
