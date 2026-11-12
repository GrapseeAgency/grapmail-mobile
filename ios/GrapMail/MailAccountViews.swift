import SwiftUI
import PhotosUI
import ImageIO
import UniformTypeIdentifiers

enum AvatarThumbnail {
    static func decode(_ data: Data) async -> CGImage? {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let source = CGImageSourceCreateWithData(data as CFData, nil)
                let options: [CFString: Any] = [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: 256,
                    kCGImageSourceShouldCacheImmediately: true,
                ]
                let thumbnail = source.flatMap {
                    CGImageSourceCreateThumbnailAtIndex($0, 0, options as CFDictionary)
                }
                continuation.resume(returning: thumbnail)
            }
        }
    }
}

/// The account-record screens — the mobile form of the web client's
/// `/mail/notes`, `/mail/tasks`, `/mail/settings`, `/mail/account/addresses`,
/// `/mail/account/connections`, `/mail/account/profile`,
/// `/mail/account/privacy` and `/mail/support`. Swift mirror of Android's
/// `MailAccountScreens.kt`; the wording is deliberately the same on both
/// platforms — two platforms naming one thing differently is how a screen
/// stops being the same screen, and the walk markers match both.
///
/// Every value on every screen comes from the one profile document
/// (`ProfileModel`); nothing here has a local store, a default that stands in
/// for an unread value, or a control the server cannot honour. The web pages
/// are the reference for wording and behaviour, including the parts where the
/// web REFUSES to offer a control (no grant button on Connections, no delete
/// button on Privacy) because offering one would be a button that lies.

// ── shared little pieces ─────────────────────────────────────────────────────

private struct AccountSectionLabel: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 11, weight: .bold)).kerning(1)
            .foregroundColor(GM.textTertiary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20).padding(.top, 16).padding(.bottom, 6)
    }
}

/// The save outcome. Bad in error ink, good in success ink, never silent.
private struct NoteLine: View {
    let text: String
    let bad: Bool
    var body: some View {
        Text(text)
            .font(.system(size: 13))
            .foregroundColor(bad ? GM.error : GM.success)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20).padding(.vertical, 6)
    }
}

/// The add-a-row editor every list screen shares: field, button, empty guard.
/// `onAdd` is declared LAST so the call sites' trailing closure binds to it --
/// Swift's memberwise initialiser keeps declaration order, and CI named the
/// call sites that tried to pass it out of position.
private struct AccountAddRow: View {
    let hint: String
    @Binding var value: String
    let busy: Bool
    let marker: String
    let onAdd: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            AppTextField(value: $value, hint: hint)
                .frame(maxWidth: .infinity)
            Button(action: { if !value.trimmed.isEmpty && !busy { onAdd() } }) {
                ZStack {
                    Circle()
                        .fill(!value.trimmed.isEmpty && !busy ? GM.primarySolid : GM.buttonDisabled)
                        .frame(width: 44, height: 44)
                    if busy {
                        ProgressView().controlSize(.small).tint(.white)
                    } else {
                        Image(systemName: "plus").font(.system(size: 16, weight: .semibold))
                            .foregroundColor(.white)
                    }
                }
            }
            .disabled(value.trimmed.isEmpty || busy)
            // The plus is the button's only visible content, and a system-name
            // image labels nobody: without this VoiceOver announces the control
            // with no word at all. Android's twin passes "Add" as the icon's
            // contentDescription (MailAccountScreens.kt); this is the same word.
            .accessibilityLabel("Add")
            .accessibilityIdentifier(marker)
        }
        .padding(.vertical, 8)
    }
}

/// One labelled radio row: one option of a fixed set, written the moment it is picked.
private struct AccountRadioRow: View {
    let title: String
    let selected: Bool
    let onClick: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle().strokeBorder(selected ? GM.primarySolid : GM.border, lineWidth: 1.5)
                    .frame(width: 20, height: 20)
                Circle().fill(GM.primarySolid).frame(width: 12, height: 12)
                    .opacity(selected ? 1 : 0)
            }
            Text(title).font(.system(size: 15)).foregroundColor(GM.textPrimary)
            Spacer()
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onClick)
        .padding(.vertical, 12)
    }
}

private struct AccountListRow: View {
    let text: String
    let marker: String
    let onRemove: () -> Void

    var body: some View {
        HStack(alignment: .top) {
            Text(text).font(.system(size: 15)).foregroundColor(GM.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onRemove) {
                Image(systemName: "trash").font(.system(size: 15))
                    .foregroundColor(GM.textTertiary)
            }
            .accessibilityLabel("Remove")
            .accessibilityIdentifier(marker)
        }
        .padding(.vertical, 10)
    }
}

private struct DetailLine2: View {
    let label: String
    let value: String
    var body: some View {
        HStack(alignment: .top) {
            Text(label).font(.system(size: 13)).foregroundColor(GM.textSecondary)
                .frame(width: 150, alignment: .leading)
            Text(value).font(.system(size: 14)).foregroundColor(GM.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 6)
    }
}

/// The three-way read sentence every account screen shares: nothing on screen
/// while loading, the server's refusal when the read died, the value only when
/// an answer actually landed. "Loading…" and "could not be read" are states;
/// an empty string would be a claim about the account nobody made.
private struct AccountReadState: View {
    let profile: ProfileDto?
    let loading: Bool
    let fatal: String?
    let what: String
    var body: some View {
        if profile == nil && fatal != nil {
            Text("\(what) could not be read. \(fatal!)")
                .font(.system(size: 14)).foregroundColor(GM.textSecondary)
                .padding(.vertical, 16)
        } else if profile == nil {
            Text(loading ? "Loading…" : "\(what) could not be read.")
                .font(.system(size: 14)).foregroundColor(GM.textTertiary)
                .padding(.vertical, 16)
        }
    }
}

// ── 92 · notes ───────────────────────────────────────────────────────────────

/// Short notes kept on the account, beside the mailbox they belong to — the
/// web's `/mail/notes` (`notes/page.tsx`), stored in `settings.notes`.
struct NotesView: View {
    @ObservedObject var vm: ProfileModel
    let onBack: () -> Void
    @State private var draft = ""

    var body: some View {
        MailScaffold(title: "Notes", onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let note = vm.state.note {
                        NoteLine(text: note, bad: vm.state.noteIsBad)
                    }
                    AccountAddRow(hint: "Write a note", value: $draft, busy: vm.state.busy,
                                  marker: "gm-add-note") {
                        guard let list = vm.state.profile?.settings?.notes else { return }
                        vm.saveSettings(ProfileSettingsPatch(notes: [draft.trimmed] + list))
                        draft = ""
                    }
                    Text("Notes are stored on the account, not on this phone, so they follow " +
                         "you to any machine you sign in from.")
                        .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                        .padding(.vertical, 6)
                    if vm.state.profile == nil {
                        AccountReadState(profile: vm.state.profile, loading: vm.state.loading,
                                         fatal: vm.state.fatal, what: "Notes")
                    } else if let notes = vm.state.profile?.settings?.notes {
                        if notes.isEmpty {
                            Text("Nothing kept here yet.")
                                .font(.system(size: 14)).foregroundColor(GM.textTertiary)
                                .padding(.vertical, 16)
                        }
                        ForEach(notes, id: \.self) { note in
                            AccountListRow(text: note, marker: "gm-note-row") {
                                vm.saveSettings(ProfileSettingsPatch(
                                    notes: notes.filter { $0 != note }))
                            }
                        }
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
            }
        }
        .task { vm.load() }
    }
}

// ── 93 · tasks ───────────────────────────────────────────────────────────────

/// The short list beside the mailbox — the web's `/mail/tasks`. Ticking a task
/// removes it, and the screen says so: there is no archive, and a checkmark
/// that implied one would promise a place the product does not have.
struct TasksView: View {
    @ObservedObject var vm: ProfileModel
    let onBack: () -> Void
    @State private var draft = ""

    var body: some View {
        MailScaffold(title: "Tasks", onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let note = vm.state.note {
                        NoteLine(text: note, bad: vm.state.noteIsBad)
                    }
                    AccountAddRow(hint: "Add a task", value: $draft, busy: vm.state.busy,
                                  marker: "gm-add-task") {
                        guard let list = vm.state.profile?.settings?.tasks else { return }
                        vm.saveSettings(ProfileSettingsPatch(tasks: list + [draft.trimmed]))
                        draft = ""
                    }
                    Text("Ticking a task removes it. This list has no archive.")
                        .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                        .padding(.vertical, 6)
                    if vm.state.profile == nil {
                        AccountReadState(profile: vm.state.profile, loading: vm.state.loading,
                                         fatal: vm.state.fatal, what: "Tasks")
                    } else if let tasks = vm.state.profile?.settings?.tasks {
                        if tasks.isEmpty {
                            Text("No tasks on this account.")
                                .font(.system(size: 14)).foregroundColor(GM.textTertiary)
                                .padding(.vertical, 16)
                        }
                        ForEach(tasks, id: \.self) { task in
                            // A tappable ROW, not a Button: SwiftUI folds a
                            // Button's whole label into one accessibility
                            // element, and the walk's static-text wait could
                            // never see the task text -- Android's clickable
                            // Row exposes each Text on its own, and the frame
                            // that failed here proved the two must agree in
                            // structure, not just in words.
                            HStack(spacing: 12) {
                                RoundedRectangle(cornerRadius: 6)
                                    .strokeBorder(GM.border, lineWidth: 1.5)
                                    .frame(width: 22, height: 22)
                                Text(task).font(.system(size: 15)).foregroundColor(GM.textPrimary)
                                Spacer()
                            }
                            .contentShape(Rectangle())
                            .onTapGesture {
                                vm.saveSettings(ProfileSettingsPatch(
                                    tasks: tasks.filter { $0 != task }))
                            }
                            .padding(.vertical, 12)
                            .accessibilityIdentifier("gm-task-row")
                        }
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
            }
        }
        .task { vm.load() }
    }
}

// ── 95 · mail settings ───────────────────────────────────────────────────────

/// How the mailbox behaves for this account — the web's `/mail/settings`. Only
/// settings that do something are here: the signature the app appends when it
/// sends, the density the list draws at, the locale every timestamp is
/// rendered in, and the sender lists the screener reads.
///
/// Vacation and forwarding are deliberately absent. The profile stores both
/// and the sieve interpreter supports the actions, but no code turns these
/// settings into sieve rules — so a switch here would change a stored value
/// with no effect on any message. The web page's own promise ("only settings
/// that do something") is kept by not shipping the switch until the pipeline
/// reads it.
struct MailPrefsView: View {
    @ObservedObject var vm: ProfileModel
    let onBack: () -> Void
    @State private var fieldsReady = false
    @State private var signature = ""
    @State private var language = ""
    @State private var timezone = ""
    @State private var safeDraft = ""
    @State private var blockedDraft = ""
    /// The per-address overrides being edited, seeded from the profile. The
    /// web's `Signature per address` section is the reference: one box per
    /// address the account can send as, empty means "use the main one".
    @State private var overrides: [String: String] = [:]

    private var s: ProfileSettings? { vm.state.profile?.settings }

    /// Every address compose can send as: the primary plus the aliases the
    /// profile carries — the same list the Addresses screen renders, because
    /// eight views of one document must not disagree about its contents.
    private var signatureAddresses: [String] {
        ([vm.state.profile?.address ?? ""] +
         (vm.state.profile?.aliases ?? []).map { $0.address }).filter { !$0.isEmpty }
    }

    /// The map a Save would write: blanks left out, because an empty entry
    /// stored is not the same as no entry — compose treats both the same, but
    /// the profile should not accumulate keys for "nothing".
    private var overridesToSave: [String: String] {
        overrides.filter { !$0.value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    private var overridesDirty: Bool { overridesToSave != (s?.signatures ?? [:]) }

    var body: some View {
        MailScaffold(title: "Mail settings", onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let note = vm.state.note {
                        NoteLine(text: note, bad: vm.state.noteIsBad)
                    }
                    if s == nil {
                        AccountReadState(profile: vm.state.profile, loading: vm.state.loading,
                                         fatal: vm.state.fatal, what: "These settings")
                    } else if fieldsReady {
                        prefsBody
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
            }
        }
        .task { vm.load() }
        .task(id: s != nil) {
            guard !fieldsReady, let settings = s else { return }
            signature = settings.signature
            language = settings.locale.language
            timezone = settings.locale.timezone
            overrides = settings.signatures
            fieldsReady = true
        }
    }

    private var prefsBody: some View {
        VStack(alignment: .leading, spacing: 0) {
            AccountSectionLabel(text: "Signature")
            Text("Appended to what you send, on every address you write from unless " +
                 "an override says otherwise.")
                .font(.system(size: 12)).foregroundColor(GM.textTertiary)
            TextEditor(text: $signature)
                .font(.system(size: 15))
                .frame(minHeight: 80)
                .padding(8)
                .overlay(RoundedRectangle(cornerRadius: GMShape.input)
                    .strokeBorder(GM.border))
                .accessibilityIdentifier("gm-signature-field")
            Button {
                vm.saveSettings(ProfileSettingsPatch(signature: signature))
            } label: {
                Text("Save signature")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(signature != s?.signature ? GM.primarySolid : GM.textTertiary)
                    .padding(.vertical, 8)
            }
            .disabled(signature == s?.signature || vm.state.busy)
            .buttonStyle(.plain)
            .accessibilityIdentifier("gm-save-signature")

            // The web's "Signature per address" section, same promise in the
            // lead line: compose reads `signatures[address]` for every send,
            // so a phone that only edited the default would offer an override
            // it never let anyone set — the sentence here and the compose
            // behaviour used to disagree until this section landed.
            AccountSectionLabel(text: "Signature per address")
            Text("When you send as an alias, this is what gets added, so an alias " +
                 "does not sign like your personal address. Leave one empty and " +
                 "that address uses the main signature above.")
                .font(.system(size: 12)).foregroundColor(GM.textTertiary)
            ForEach(signatureAddresses, id: \.self) { address in
                Text(address)
                    .font(.system(size: 12, weight: .medium)).foregroundColor(GM.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 10)
                TextEditor(text: Binding(
                    get: { overrides[address] ?? "" },
                    set: { overrides[address] = $0 }
                ))
                .font(.system(size: 14))
                .frame(minHeight: 60)
                .padding(6)
                .overlay(alignment: .topLeading) {
                    if (overrides[address] ?? "").isEmpty {
                        Text("Uses the main signature")
                            .font(.system(size: 14)).foregroundColor(GM.textTertiary)
                            .padding(.leading, 12).padding(.top, 12)
                            .allowsHitTesting(false)
                    }
                }
                .overlay(RoundedRectangle(cornerRadius: GMShape.input)
                    .strokeBorder(GM.border))
                .accessibilityLabel("Signature for \(address)")
                .accessibilityIdentifier("gm-sig-override-\(address)")
            }
            Button {
                vm.saveSettings(ProfileSettingsPatch(signatures: overridesToSave))
            } label: {
                Text("Save per-address signatures")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(overridesDirty ? GM.primarySolid : GM.textTertiary)
                    .padding(.vertical, 8)
            }
            .disabled(!overridesDirty || vm.state.busy)
            .buttonStyle(.plain)
            .accessibilityIdentifier("gm-save-signatures")

            AccountSectionLabel(text: "List density")
            AccountRadioRow(title: "Comfortable — the roomier list",
                            selected: s?.density != "compact") {
                if s?.density != "comfortable" {
                    vm.saveSettings(ProfileSettingsPatch(density: "comfortable"))
                }
            }
            AccountRadioRow(title: "Compact — more messages per screen",
                            selected: s?.density == "compact") {
                if s?.density != "compact" {
                    vm.saveSettings(ProfileSettingsPatch(density: "compact"))
                }
            }

            AccountSectionLabel(text: "Language and dates")
            AppTextField(title: "Language", value: $language, hint: "en-US")
            AppTextField(title: "Time zone", value: $timezone, hint: "Asia/Dhaka")
                .padding(.top, 8)
            Button {
                guard let loc = s?.locale else { return }
                vm.saveSettings(ProfileSettingsPatch(locale: LocaleInfo(
                    language: language.trimmed.isEmpty ? loc.language : language.trimmed,
                    timezone: timezone.trimmed.isEmpty ? loc.timezone : timezone.trimmed,
                    dateFormat: loc.dateFormat)))
            } label: {
                Text("Save language and time zone")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(GM.primarySolid).padding(.vertical, 8)
            }
            .disabled(vm.state.busy ||
                      (language.trimmed == s?.locale.language &&
                       timezone.trimmed == s?.locale.timezone))
            .buttonStyle(.plain)
            Text("Dates in messages and the list:")
                .font(.system(size: 13)).foregroundColor(GM.textSecondary)
            ForEach([("dmy", "day first (25/10/26)"), ("mdy", "month first (10/25/26)"),
                     ("ymd", "year first (2026-10-25)")], id: \.0) { code, label in
                AccountRadioRow(title: label, selected: s?.locale.dateFormat == code) {
                    if let loc = s?.locale, loc.dateFormat != code {
                        vm.saveSettings(ProfileSettingsPatch(
                            locale: LocaleInfo(language: loc.language, timezone: loc.timezone,
                                               dateFormat: code)))
                    }
                }
            }

            AccountSectionLabel(text: "Senders you always accept")
            Text("A safe sender skips the approval queue even when two-step checking is on.")
                .font(.system(size: 12)).foregroundColor(GM.textTertiary)
            AccountAddRow(hint: "address@example.com", value: $safeDraft,
                          busy: vm.state.busy, marker: "gm-add-safe") {
                let next = ((s?.safeSenders ?? []) + [safeDraft.trimmed.lowercased()]).uniq()
                vm.saveSettings(ProfileSettingsPatch(safeSenders: next))
                safeDraft = ""
            }
            ForEach(s?.safeSenders ?? [], id: \.self) { e in
                AccountListRow(text: e, marker: "gm-safe-row") {
                    vm.saveSettings(ProfileSettingsPatch(
                        safeSenders: (s?.safeSenders ?? []).filter { $0 != e }))
                }
            }

            AccountSectionLabel(text: "Senders you block")
            Text("Blocked senders are refused at the door: nothing arrives and nothing " +
                 "queues for approval.")
                .font(.system(size: 12)).foregroundColor(GM.textTertiary)
            AccountAddRow(hint: "address@example.com", value: $blockedDraft,
                          busy: vm.state.busy, marker: "gm-add-blocked") {
                let next = ((s?.blockedSenders ?? []) + [blockedDraft.trimmed.lowercased()]).uniq()
                vm.saveSettings(ProfileSettingsPatch(blockedSenders: next))
                blockedDraft = ""
            }
            ForEach(s?.blockedSenders ?? [], id: \.self) { e in
                AccountListRow(text: e, marker: "gm-blocked-row") {
                    vm.saveSettings(ProfileSettingsPatch(
                        blockedSenders: (s?.blockedSenders ?? []).filter { $0 != e }))
                }
            }
        }
    }
}

// ── 96 · addresses ───────────────────────────────────────────────────────────

/// The GrapMail address that IS the account, plus the aliases — the web's
/// `/mail/account/addresses`. Create and delete hit the alias routes the
/// mailbox API already declares; the list itself rides the profile document so
/// this screen and the settings rows can never disagree about how many
/// addresses an account has.
struct AddressesView: View {
    @ObservedObject var vm: ProfileModel
    let onBack: () -> Void
    @State private var label = ""
    @State private var pendingDelete: String?

    var body: some View {
        MailScaffold(title: "Addresses", onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let note = vm.state.note {
                        NoteLine(text: note, bad: vm.state.noteIsBad)
                    }
                    AccountSectionLabel(text: "Your GrapMail address")
                    Text(vm.state.profile?.address
                         ?? (vm.state.fatal != nil ? "Could not be read" : "Loading…"))
                        .font(.system(size: 16, weight: .medium))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14).padding(.vertical, 12)
                        .background(RoundedRectangle(cornerRadius: 10).fill(GM.bgTinted))
                        .accessibilityIdentifier("gm-primary-address")
                    AccountSectionLabel(text: "Email aliases")
                    Text("An alias delivers into the same mailbox under a second address, " +
                         "and you can choose it as the sender.")
                        .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                    AccountAddRow(hint: "A label — business, family…", value: $label,
                                  busy: vm.state.busy, marker: "gm-add-alias") {
                        // `POST /aliases` mints the address itself; the label is what
                        // the person calls it by. The screen reloads from the profile after.
                        vm.aliasWrite(value: label.trimmed, remove: false)
                        label = ""
                    }
                    aliasesBody
                    if let address = pendingDelete {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Stop receiving at \(address)? Mail already delivered stays " +
                                 "in the mailbox; nothing new arrives at this address.")
                                .font(.system(size: 13)).foregroundColor(GM.textPrimary)
                            HStack(spacing: 24) {
                                Button("Cancel") { pendingDelete = nil }
                                    .foregroundColor(GM.textSecondary)
                                Button("Remove address") {
                                    pendingDelete = nil
                                    vm.aliasWrite(value: address, remove: true)
                                }
                                .foregroundColor(GM.error)
                                .fontWeight(.semibold)
                            }
                            .font(.system(size: 14))
                        }
                        .padding(16)
                        .background(RoundedRectangle(cornerRadius: 12).fill(GM.warnChipBg))
                        .accessibilityIdentifier("gm-alias-confirm")
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
            }
        }
        .task { vm.load() }
    }

    @ViewBuilder private var aliasesBody: some View {
        let aliases = vm.state.profile?.aliases
        if aliases == nil && vm.state.profile != nil {
            EmptyView()  // the field exists and says none arrived: it is nil-safe
        } else if let aliases {
            if aliases.isEmpty {
                Text("No aliases yet.")
                    .font(.system(size: 14)).foregroundColor(GM.textTertiary)
                    .padding(.vertical, 12)
            }
            ForEach(aliases) { a in
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(a.label.trimmed.isEmpty ? a.address : a.label)
                            .font(.system(size: 15, weight: .medium))
                            .foregroundColor(GM.textPrimary)
                        Text(a.address).font(.system(size: 13)).foregroundColor(GM.textSecondary)
                        if !a.isActive {
                            Text("Paused — nothing new arrives here.")
                                .font(.system(size: 12)).foregroundColor(GM.warnInk)
                        }
                    }
                    Spacer()
                    Button {
                        pendingDelete = a.address
                    } label: {
                        Image(systemName: "trash").foregroundColor(GM.error)
                    }
                    .accessibilityLabel("Remove \(a.address)")
                    .accessibilityIdentifier("gm-alias-row")
                }
                .padding(.vertical, 12)
            }
        } else if vm.state.fatal != nil {
            Text("Aliases could not be read.")
                .font(.system(size: 14)).foregroundColor(GM.textSecondary)
                .padding(.vertical, 12)
        }
    }
}

// ── 94 · connections ─────────────────────────────────────────────────────────

/// Connected accounts — the web's `/mail/account/connections`, modelled on the
/// page's own rule: access is never granted from this screen, so there is no
/// grant control. It reports what already happened and lets the person cut it
/// off. A product we run has no domain, so it gets a monogram of its initials;
/// a third-party app shows its real mark.
struct ConnectionsView: View {
    @ObservedObject var vm: ProfileModel
    let onBack: () -> Void
    /// The web links the per-app detail from the same list; the row opens it.
    /// Remove stays on the row — it is the action taken without wanting detail.
    let onOpen: (String) -> Void
    @State private var query = ""
    @State private var viewFilter = "all"
    @State private var pendingRemove: ProfileConnection?

    private var connected: [ProfileConnection] { vm.state.profile?.connections ?? [] }

    private var shown: [ProfileConnection] {
        connected.filter { c in
            let inView = viewFilter == "all"
                || (viewFilter == "mailbox" && c.scopes.contains("mailbox"))
                || (viewFilter == "products" && !(c.host?.contains(".") ?? false))
            let q = query.trimmed.lowercased()
            return inView && (q.isEmpty
                || c.product.lowercased().contains(q)
                || (c.host ?? "").contains(q))
        }
    }

    var body: some View {
        MailScaffold(title: "Connected accounts", onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let note = vm.state.note {
                        NoteLine(text: note, bad: vm.state.noteIsBad)
                    }
                    Text("When you use this account to sign in somewhere, that app appears " +
                         "here with what it can reach, and you can remove it at any time.")
                        .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                        .padding(.vertical, 8)
                    AppTextField(value: $query, hint: "Search connected apps by name")
                        .accessibilityIdentifier("gm-connections-search")
                    HStack(spacing: 8) {
                        ForEach([("all", "All"), ("mailbox", "With mail"),
                                 ("products", "Grapsee apps")], id: \.0) { key, label in
                            let count = connected.count { c in
                                switch key {
                                case "mailbox": return c.scopes.contains("mailbox")
                                case "products": return !(c.host?.contains(".") ?? false)
                                default: return true
                                }
                            }
                            Button {
                                viewFilter = key
                            } label: {
                                Text("\(label) (\(count))")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundColor(viewFilter == key ? GM.primarySolid
                                                                       : GM.textSecondary)
                                    .padding(.horizontal, 10).padding(.vertical, 6)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("gm-view-\(key)")
                        }
                    }
                    .padding(.vertical, 10)
                    if shown.isEmpty {
                        Text(connected.isEmpty ? "No app has access to this account."
                                               : "Nothing matches that.")
                            .font(.system(size: 14)).foregroundColor(GM.textTertiary)
                            .padding(.vertical, 16)
                    }
                    ForEach(shown) { c in
                        HStack(spacing: 12) {
                            ZStack {
                                RoundedRectangle(cornerRadius: 10).fill(GM.softFill)
                                    .frame(width: 38, height: 38)
                                Text(monogram(c.product))
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundColor(GM.primarySolid)
                            }
                            VStack(alignment: .leading, spacing: 2) {
                                Text(c.product).font(.system(size: 15, weight: .medium))
                                    .foregroundColor(GM.textPrimary)
                                let scopes = c.scopes.map(scopeWord).joined(separator: ", ")
                                Text(scopes.isEmpty ? "Signed in here" : "Sees: \(scopes)")
                                    .font(.system(size: 12)).foregroundColor(GM.textSecondary)
                            }
                            Spacer()
                            Button {
                                pendingRemove = c
                            } label: {
                                Text("Remove").font(.system(size: 13, weight: .semibold))
                                    .foregroundColor(GM.error)
                            }
                            .accessibilityIdentifier("gm-connection-row")
                        }
                        .padding(.vertical, 12)
                        .contentShape(Rectangle())
                        .onTapGesture { onOpen(c.product) }
                    }
                    if let c = pendingRemove {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Cut \(c.product) off? It loses access to what is listed " +
                                 "beside it, and has to sign in again to get back in.")
                                .font(.system(size: 13)).foregroundColor(GM.textPrimary)
                            HStack(spacing: 24) {
                                Button("Cancel") { pendingRemove = nil }
                                    .foregroundColor(GM.textSecondary)
                                Button("Remove access") {
                                    pendingRemove = nil
                                    vm.saveConnections(keep: connected.filter { $0.product != c.product })
                                }
                                .foregroundColor(GM.error).fontWeight(.semibold)
                            }
                            .font(.system(size: 14))
                        }
                        .padding(16)
                        .background(RoundedRectangle(cornerRadius: 12).fill(GM.warnChipBg))
                        .accessibilityIdentifier("gm-connection-confirm")
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
            }
        }
        .task { vm.load() }
    }
}

/// Initials for a product with no domain, the web's rule copied: strip to
/// letters and digits, take the first character of each word, cap at two.
/// File-scope because the list row and the per-app detail draw the same mark.
private func monogram(_ product: String) -> String {
    let cleaned = product.unicodeScalars.filter {
        CharacterSet.alphanumerics.contains($0) || $0 == " "
    }
    let words = String(String.UnicodeScalarView(cleaned))
        .split(separator: " ").filter { !$0.isEmpty }
    let initials = words.prefix(2).map { String($0.first!) }.joined().uppercased()
    return initials.isEmpty ? "?" : initials
}

/// The web's own scope words (`connections/page.tsx` SHARED map), copied rather
/// than re-invented so the two clients describe one scope with one phrase.
private func scopeWord(_ scope: String) -> String {
    switch scope {
    case "profile": return "name, address and photo"
    case "mailbox": return "your mailbox"
    case "projects": return "your projects"
    case "contacts": return "your contacts"
    default: return scope
    }
}

/// The web's FULL sharing sentences (`LinkedAppDetail.tsx` SHARED map), for
/// the per-app page where the person decides what cutting access costs. The
/// list row uses the short words; the detail uses these, exactly as the web
/// splits the two. Android's `sharedWord` is the twin.
func sharedWord(_ scope: String) -> String {
    switch scope {
    case "profile": return "Your name, GrapMail address and profile photo"
    case "mailbox": return "Read and send mail in your GrapMail mailbox"
    case "projects": return "Projects and client records you own on Grapsee products"
    case "contacts": return "The people you have exchanged mail with"
    default: return scope
    }
}

// ── 94b · one linked app, in full ────────────────────────────────────────────

/// The web's `/mail/account/connections/<product>`: what this app is, what it
/// received at the moment access was granted, and the one honest way to cut
/// it off. The scopes shown are the recorded ones, not a generic catalogue; a
/// product with no entry says so — nothing shared, nothing to remove. An
/// unread profile is never drawn as "not connected": that is the invention
/// this screen exists to avoid.
struct ConnectionDetailView: View {
    @ObservedObject var vm: ProfileModel
    let product: String
    let onBack: () -> Void
    let onSupport: () -> Void
    @State private var confirmRemove = false

    private var connected: [ProfileConnection] { vm.state.profile?.connections ?? [] }
    private var entry: ProfileConnection? {
        connected.first { $0.product.lowercased() == product.lowercased() }
    }
    private var scopes: [String] { entry?.scopes ?? [] }

    var body: some View {
        MailScaffold(title: product, onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if vm.state.profile == nil {
                        Text(vm.state.loading ? "Loading…"
                             : "The account could not be read, so this screen " +
                               "cannot say what the app can reach.")
                            .font(.system(size: 14)).foregroundColor(GM.textTertiary)
                            .padding(.vertical, 16)
                            .accessibilityIdentifier("gm-connection-detail-read")
                    } else {
                        if let note = vm.state.note {
                            NoteLine(text: note, bad: vm.state.noteIsBad)
                        }
                        Spacer().frame(height: 8)
                        header
                        if entry == nil {
                            // The web's exact stance: a link to an app that is
                            // not linked is answered, not redirected.
                            Text("This app does not have access to this account. "
                                 + "Nothing is being shared with it, and there is "
                                 + "nothing to remove.")
                                .font(.system(size: 14)).lineSpacing(5)
                                .foregroundColor(GM.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                                .padding(14)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(RoundedRectangle(cornerRadius: 12).fill(GM.softFill))
                                .padding(.vertical, 20)
                                .accessibilityIdentifier("gm-connection-not-linked")
                        } else {
                            scopesCard
                            removeRow
                            if confirmRemove { confirmBlock }
                            supportLink
                        }
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
            }
        }
        .task { vm.load() }
    }

    private var header: some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 12).fill(GM.softFill)
                    .frame(width: 48, height: 48)
                Text(monogram(product)).font(.system(size: 18, weight: .semibold))
                    .foregroundColor(GM.primarySolid)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(product).font(.system(size: 20)).foregroundColor(GM.textPrimary)
                    .lineLimit(1)
                    .accessibilityIdentifier("gm-connection-detail-name")
                Text(entry.map { c in
                    (c.host ?? "Grapsee product") + " · connected "
                        + String((c.connectedAt ?? "").prefix(10))
                } ?? "Not connected to this account")
                    .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .lineLimit(2)
            }
            Spacer()
        }
        .padding(.top, 8)
    }

    private var scopesCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("How GrapMail helps you sign in to \(product)")
                .font(.system(size: 15)).foregroundColor(GM.textPrimary)
            Text("When you use your Grapsee account to sign in to \(product), this "
                 + "account proves who you are and hands over only what is listed "
                 + "below. Your password is never shared with \(product).")
                .font(.system(size: 13)).lineSpacing(5)
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)
            Text("WHAT \(product.uppercased()) CAN REACH")
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(GM.textSecondary)
                .padding(.top, 14)
            let scopes = self.scopes
            if scopes.isEmpty {
                Text("No access was recorded when this was connected.")
                    .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                    .padding(.top, 6)
            }
            ForEach(scopes, id: \.self) { s in
                HStack(alignment: .top, spacing: 10) {
                    Circle().fill(GM.primarySolid).frame(width: 6, height: 6)
                        .padding(.top, 7)
                    Text(sharedWord(s)).font(.system(size: 13))
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer()
                }
                .padding(.top, 8)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous)
            .fill(GM.card).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(GM.border, lineWidth: 1)))
        .padding(.top, 18)
    }

    private var removeRow: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(vm.state.busy ? "Removing…" : "Remove access")
                .font(.system(size: 15, weight: .medium)).foregroundColor(GM.error)
            Text("Remove all links and stop sharing this account's data with \(product).")
                .font(.system(size: 12)).lineSpacing(4)
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 18).padding(.vertical, 16)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous)
            .fill(GM.card).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(GM.border, lineWidth: 1)))
        .padding(.top, 18)
        .contentShape(Rectangle())
        .onTapGesture { if !vm.state.busy { confirmRemove = true } }
    }

    private var confirmBlock: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Cut \(product) off? It loses access to everything listed above "
                 + "and has to sign in again to get back in.")
                .font(.system(size: 13)).foregroundColor(GM.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 24) {
                Button("Cancel") { confirmRemove = false }
                    .foregroundColor(GM.textSecondary)
                Button("Remove access") {
                    confirmRemove = false
                    vm.saveConnections(keep: connected.filter {
                        $0.product.lowercased() != product.lowercased()
                    })
                }
                .foregroundColor(GM.error).fontWeight(.semibold)
            }
            .font(.system(size: 14))
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(GM.warnChipBg))
        .padding(.top, 10)
        .accessibilityIdentifier("gm-connection-confirm")
    }

    private var supportLink: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Something wrong with this app?")
                .font(.system(size: 12)).foregroundColor(GM.textSecondary)
            Button("Report this app", action: onSupport)
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(GM.primarySolid)
        }
        .padding(.top, 16)
    }
}

// ── 90 · personal info ───────────────────────────────────────────────────────

/// Personal info — the web's `/mail/account/profile`: the name on the account,
/// the birthday (with the same 13+ rule signup enforces), gender, the photo,
/// and how the person appears to recipients.
struct ProfileEditView: View {
    @ObservedObject var vm: ProfileModel
    let onBack: () -> Void
    @State private var fieldsReady = false
    @State private var first = ""
    @State private var last = ""
    @State private var display = ""
    @State private var recoveryEmail = ""
    @State private var recoveryPhone = ""
    @State private var birthday = ""
    @State private var gender = ""
    @State private var photoError: String?
    @State private var photoItem: PhotosPickerItem?
    @State private var avatarPreview: UIImage?

    private var p: ProfileDto? { vm.state.profile }

    var body: some View {
        MailScaffold(title: "Personal info", onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let note = vm.state.note {
                        NoteLine(text: note, bad: vm.state.noteIsBad)
                    }
                    if let photoError { NoteLine(text: photoError, bad: true) }
                    if p == nil {
                        AccountReadState(profile: vm.state.profile, loading: vm.state.loading,
                                         fatal: vm.state.fatal, what: "Your account record")
                    } else if fieldsReady {
                        avatarRow
                        fields
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
            }
        }
        .task { vm.load() }
        .task(id: p != nil) {
            guard !fieldsReady, let profile = p else { return }
            first = profile.firstName ?? ""
            last = profile.lastName ?? ""
            display = profile.displayName ?? ""
            recoveryEmail = profile.recoveryEmail ?? ""
            recoveryPhone = profile.recoveryPhone ?? ""
            birthday = profile.birthday ?? ""
            gender = profile.gender ?? ""
            fieldsReady = true
        }
        .task(id: vm.state.busy ? nil : p?.avatarUrl) {
            if !vm.state.busy { vm.loadAvatar() }
        }
        .task(id: vm.state.avatar) {
            guard let data = vm.state.avatar else { avatarPreview = nil; return }
            let thumbnail = await AvatarThumbnail.decode(data)
            guard !Task.isCancelled else { return }
            avatarPreview = thumbnail.map { UIImage(cgImage: $0) }
        }
        .task(id: photoItem) {
            guard let item = photoItem else { return }
            defer { if photoItem == item { photoItem = nil } }
            guard let bytes = try? await item.loadTransferable(type: Data.self), !bytes.isEmpty else {
                if !Task.isCancelled { photoError = "That image could not be read." }
                return
            }
            guard !Task.isCancelled else { return }
            if bytes.count > 256 * 1024 {
                photoError = "That photo is too large. A profile photo can be up to 256 KB."
                return
            }
            guard let image = await Self.avatarType(bytes) else {
                if !Task.isCancelled { photoError = "Choose a PNG, JPEG, WebP or GIF image." }
                return
            }
            guard !Task.isCancelled else { return }
            photoError = nil
            vm.uploadAvatar(bytes: bytes, filename: "profile.\(image.ext)", mime: image.mime)
        }
    }

    private nonisolated static func avatarType(_ bytes: Data) async -> (ext: String, mime: String)? {
        guard let source = CGImageSourceCreateWithData(bytes as CFData, nil),
              let identifier = CGImageSourceGetType(source),
              let type = UTType(identifier as String), let mime = type.preferredMIMEType else { return nil }
        switch mime {
        case "image/jpeg": return ("jpg", mime)
        case "image/png": return ("png", mime)
        case "image/webp": return ("webp", mime)
        case "image/gif": return ("gif", mime)
        default: return nil
        }
    }

    private var avatarRow: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(GM.softFill).frame(width: 64, height: 64)
                if let img = avatarPreview {
                    Image(uiImage: img).resizable()
                        .scaledToFill().frame(width: 64, height: 64).clipped()
                        .clipShape(Circle())
                } else {
                    Text(String((p?.name ?? "?").prefix(1)).uppercased())
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundColor(GM.primarySolid)
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                PhotosPicker(selection: $photoItem, matching: .images) {
                    Text((p?.avatarUrl?.isEmpty == false) ? "Change photo" : "Add a photo")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(GM.primarySolid)
                }
                .buttonStyle(.plain)
                if p?.avatarUrl?.isEmpty == false {
                    Button {
                        vm.removeAvatar()
                    } label: {
                        Text("Remove").font(.system(size: 13)).foregroundColor(GM.error)
                    }
                    .buttonStyle(.plain)
                }
            }
            Spacer()
        }
        .padding(.vertical, 10)
    }

    private var fields: some View {
        VStack(alignment: .leading, spacing: 0) {
            AccountSectionLabel(text: "Your name")
            AppTextField(title: "First name", value: $first)
            AppTextField(title: "Last name", value: $last).padding(.top, 8)
            AccountSectionLabel(text: "Birthday")
            Text("A GrapMail address can receive mail from anyone, so the account requires " +
                 "an age of at least 13 — the same rule signup enforces.")
                .font(.system(size: 12)).foregroundColor(GM.textTertiary)
            AppTextField(title: "Birthday", value: $birthday, hint: "YYYY-MM-DD")
                .padding(.top, 6)
            AccountSectionLabel(text: "Gender")
            ForEach(["Female", "Male", "Rather not say", "Custom"], id: \.self) { g in
                AccountRadioRow(title: g, selected: gender == g) {
                    gender = (gender == g) ? "" : g
                }
            }
            AccountSectionLabel(text: "How you appear")
            AppTextField(title: "Display name", value: $display, hint: "What recipients see")
            AccountSectionLabel(text: "Recovery")
            AppTextField(title: "Recovery email", value: $recoveryEmail,
                         hint: "A mailbox you can still reach", keyboardType: .emailAddress)
            AppTextField(title: "Recovery phone", value: $recoveryPhone,
                         hint: "+8801XXXXXXXXX", keyboardType: .phonePad)
                .padding(.top, 8)
            Button {
                vm.save(label: "Saved.", personal: [
                    "firstName": first, "lastName": last, "birthday": birthday,
                    "gender": gender, "displayName": display,
                    "recoveryEmail": recoveryEmail, "recoveryPhone": recoveryPhone,
                ])
            } label: {
                Text(vm.state.busy ? "Saving…" : "Save")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity).padding(.vertical, 14)
                    .background(RoundedRectangle(cornerRadius: 24)
                        .fill(vm.state.busy ? GM.buttonDisabled : GM.primarySolid))
            }
            .disabled(vm.state.busy)
            .buttonStyle(.plain)
            .padding(.vertical, 10)
            .accessibilityIdentifier("gm-profile-save")
        }
    }
}

// ── 91 · data & privacy ──────────────────────────────────────────────────────

/// Account controls, including password-confirmed deletion. The client clears
/// local state only after the authenticated endpoint explicitly confirms success.
struct PrivacyView: View {
    @ObservedObject var vm: ProfileModel
    let onBack: () -> Void
    let onProfile: () -> Void
    let onPhone: () -> Void
    let onAddresses: () -> Void
    let onPrefs: () -> Void
    let onSupport: () -> Void

    var onDeleted: () -> Void = {}
    @State private var showDeletion = false

    private var p: ProfileDto? { vm.state.profile }

    var body: some View {
        MailScaffold(title: "Data & privacy", onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    AccountSectionLabel(text: "What is kept")
                    ForEach([
                        "GrapMail address and aliases: the identity of the account and any " +
                            "extra addresses that deliver into it.",
                        "Your messages: stored for your account only. Nobody at Grapsee " +
                            "reads message contents to build profiles, and addresses are not sold.",
                        "When you agreed to the terms: a timestamp, so the record can answer " +
                            "“did they agree, and when?”",
                    ], id: \.self) { line in
                        Text("• \(line)")
                            .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                            .padding(.vertical, 4)
                    }
                    AccountSectionLabel(text: "Your controls")
                    AccountControlRow(title: "Edit personal info", value: "Name, birthday, photo",
                                      marker: "gm-privacy-profile", onClick: onProfile)
                    AccountControlRow(title: "Change phone number", value: "Proven with a code",
                                      marker: "gm-privacy-phone", onClick: onPhone)
                    AccountControlRow(title: "Manage addresses", value: "Primary and aliases",
                                      marker: "gm-privacy-addresses", onClick: onAddresses)
                    AccountControlRow(title: "Mail settings", value: "Signature, density, locale",
                                      marker: "gm-privacy-prefs", onClick: onPrefs)
                    AccountSectionLabel(text: "This account")
                    DetailLine2(label: "Address", value: p?.address
                        ?? (vm.state.fatal != nil ? "Could not be read" : "Loading…"))
                    DetailLine2(label: "Created", value: p.flatMap { String($0.createdAt?.prefix(10) ?? "") }.flatMap { $0.isEmpty ? nil : $0 }
                        ?? (p == nil ? "Could not be read" : "Not reported"))
                    DetailLine2(label: "Terms accepted", value: p.flatMap { String($0.termsAcceptedAt?.prefix(10) ?? "") }.flatMap { $0.isEmpty ? nil : $0 }
                        ?? (p == nil ? "Could not be read" : "not recorded"))
                    DetailLine2(label: "2-step verification", value: {
                        switch p?.twoFactorState {
                        case .some(true): return "On"
                        case .some(false): return "Off"
                        case .none: return p == nil ? "Could not be read" : "Not reported"
                        }
                    }())
                    AccountSectionLabel(text: "Deleting the account")
                    Text("Deletion removes the mailbox, every message in it, all aliases, and the GrapMail address itself. It is irreversible, so it asks for the account password and a final confirmation.")
                        .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                        .padding(.vertical, 6)
                    Button("Delete account", role: .destructive) { showDeletion = true }
                        .padding(.vertical, 8)
                        .accessibilityIdentifier("gm-privacy-delete")
                    Button {
                        onSupport()
                    } label: {
                        Text("Go to help & support")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(GM.primarySolid)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("gm-privacy-support")
                    AccountSectionLabel(text: "The documents")
                    Button {
                        openExternal("https://grapsee.com/legal/privacy-policy")
                    } label: {
                        Text("Privacy Policy · Terms of Service")
                            .font(.system(size: 14)).foregroundColor(GM.primarySolid)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.plain)
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
            }
        }
        .task { vm.load() }
        .sheet(isPresented: $showDeletion) {
            DeleteAccountView {
                showDeletion = false
                onDeleted()
            }
        }
    }
}

private struct DeleteAccountView: View {
    @Environment(\.dismiss) private var dismiss
    let onDeleted: () -> Void
    @State private var password = ""
    @State private var confirm = false
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Deleting the account removes the mailbox, every message in it, all aliases, and the GrapMail address. This cannot be undone — save anything needed before continuing.")
                    SecureField("Account password", text: $password)
                        .textContentType(.password)
                        .accessibilityIdentifier("gm-delete-password")
                }
                if let error { Text(error).foregroundColor(GM.error) }
                Button("Delete account permanently", role: .destructive) { confirm = true }
                    .disabled(password.isEmpty || password.count > 1024 || busy)
                    .accessibilityIdentifier("gm-delete-confirm")
                if busy { ProgressView("Requesting deletion…") }
            }
            .navigationTitle("Delete account")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { password = ""; dismiss() }.disabled(busy)
                }
            }
            .disabled(busy)
            .confirmationDialog("Permanently delete this account?", isPresented: $confirm,
                                titleVisibility: .visible) {
                Button("Delete permanently", role: .destructive) { delete() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This action cannot be undone.")
            }
        }
        .interactiveDismissDisabled(busy)
        .onDisappear { password = "" }
    }

    private func delete() {
        guard !busy, !password.isEmpty, let token = SessionStore.token else {
            error = "Sign in again before requesting deletion."
            return
        }
        busy = true; error = nil
        Task { @MainActor in
            defer { busy = false; password = "" }
            do {
                _ = try await ApiClient.deleteAccount(token: token, password: password)
                SessionStore.clear()
                URLCache.shared.removeAllCachedResponses()
                onDeleted()
            } catch {
                self.error = "Deletion was not confirmed. Check account status before retrying. " + error.localizedDescription
            }
        }
    }
}

private struct AccountControlRow: View {
    let title: String
    let value: String
    let marker: String
    let onClick: () -> Void
    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 15)).foregroundColor(GM.textPrimary)
                    Text(value).font(.system(size: 12)).foregroundColor(GM.textSecondary)
                }
                Spacer()
                Image(systemName: "chevron.forward").font(.system(size: 13))
                    .foregroundColor(GM.textTertiary)
            }
            .contentShape(Rectangle())
            .padding(.vertical, 12)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(marker)
    }
}

// ── 97 · help & support ──────────────────────────────────────────────────────

/// The questions this product actually generates — the web's `/mail/support`,
/// whose answers are written against behaviours the code really has (recovery
/// by phone code, the 13+ rule, what an alias is). The routes at the bottom go
/// to the screens that can fix the problem, not to a contact form that
/// vanishes into an inbox.
struct SupportView: View {
    let onBack: () -> Void
    let onForgot: () -> Void
    let onPhone: () -> Void
    let onAddresses: () -> Void
    @State private var open: String?

    private let faq: [(String, String)] = [
        ("I forgot my password. What now?",
         "A GrapMail account has no second inbox, so recovery proves the account with "
         + "the phone number on file: a 6-digit code goes to that number and the new "
         + "password is set only after the code is presented."),
        ("Can I sign in with my phone number?",
         "Yes. The sign-in page accepts either the GrapMail address or the phone "
         + "number you verified, but the number is never the identity of the account, "
         + "and mail is never addressed to it."),
        ("I changed my number. Will I lose the account?",
         "No, if you change it before losing access. The new number is proven with a "
         + "code before it replaces the old one."),
        ("Why can I not create an account for someone under 13?",
         "GrapMail holds an address that can receive mail from anyone, so the account "
         + "requires an age of at least 13. The birthday you enter is checked against "
         + "that rule."),
        ("What is an alias for?",
         "An alias delivers into the same mailbox under a second address, and can be "
         + "chosen as the sender. Useful when you want to write as “business” "
         + "without a second account."),
        ("Mail I was expecting never arrived.",
         "In order: check the sending address was spelled exactly, look in Spam and "
         + "Trash under “Others”, then check that the sender is not on the "
         + "suppression list because they unsubscribed earlier. If the mailbox is "
         + "connected and the message is still missing, the mail server has to be "
         + "involved. That is a support matter."),
    ]

    var body: some View {
        MailScaffold(title: "Help & support", onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Answers to the questions this product actually raises, and how " +
                         "to reach a person.")
                        .font(.system(size: 13)).foregroundColor(GM.textTertiary)
                        .padding(.vertical, 8)
                    ForEach(faq, id: \.0) { q, a in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack(spacing: 8) {
                                Text(open == q ? "−" : "+")
                                    .font(.system(size: 15, weight: .bold))
                                    .foregroundColor(GM.primarySolid)
                                Text(q).font(.system(size: 14, weight: .medium))
                                    .foregroundColor(GM.textPrimary)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            if open == q {
                                Text(a).font(.system(size: 13)).foregroundColor(GM.textSecondary)
                                    .lineSpacing(4)
                            }
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { open = (open == q) ? nil : q }
                        .padding(.vertical, 12)
                        .accessibilityIdentifier("gm-faq-item")
                    }
                    AccountSectionLabel(text: "Where a person answers")
                    Button {
                        openExternal("https://grapsee.com/contact")
                    } label: {
                        Text("grapsee.com/contact")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(GM.primarySolid).padding(.vertical, 6)
                    }
                    .buttonStyle(.plain)
                    Text("Recovery screens, in the order that fixes things:")
                        .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                        .padding(.top, 6)
                    ForEach([("Forgot password", onForgot), ("Change my phone", onPhone),
                             ("Manage addresses", onAddresses)], id: \.0) { label, go in
                        Button(action: go) {
                            Text(label).font(.system(size: 14)).foregroundColor(GM.primarySolid)
                                .padding(.vertical, 6)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("gm-support-\(label)")
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
            }
        }
    }
}

/// Hand a URL to the device, the same rule the unsubscribe sheet applies.
private func openExternal(_ url: String) {
    if let u = URL(string: url) { UIApplication.shared.open(u) }
}

// ── small string helpers used only by these screens ─────────────────────────

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

extension Array where Element == String {
    /// Order-preserving distinct, the Swift shape of Android's `.distinct()`.
    func uniq() -> [String] {
        var seen = Set<String>(); var out: [String] = []
        for e in self where seen.insert(e).inserted { out.append(e) }
        return out
    }
}
