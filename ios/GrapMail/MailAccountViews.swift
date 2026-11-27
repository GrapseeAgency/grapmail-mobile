import CoreTransferable
import SwiftUI
import PhotosUI
import ImageIO
import UniformTypeIdentifiers

enum AvatarThumbnail {
    static func inline(_ value: String?) -> Data? {
        guard let value, value.utf8.count <= 14 * 1024 * 1024,
              let comma = value.firstIndex(of: ","),
              ["data:image/png;base64", "data:image/jpeg;base64", "data:image/webp;base64",
               "data:image/gif;base64"].contains(String(value[..<comma])),
              let data = Data(base64Encoded: String(value[value.index(after: comma)...])),
              !data.isEmpty, data.count <= 10 * 1024 * 1024 else { return nil }
        return data
    }

    static func decode(_ data: Data, maxSize: Int = 256) async -> CGImage? {
        await decode(maxSize: maxSize) { CGImageSourceCreateWithData(data as CFData, nil) }
    }

    static func decode(_ url: URL, maxSize: Int) async -> CGImage? {
        await decode(maxSize: maxSize) { CGImageSourceCreateWithURL(url as CFURL, nil) }
    }

    private static func decode(maxSize: Int, source: @escaping @Sendable () -> CGImageSource?) async -> CGImage? {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let options: [CFString: Any] = [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: maxSize,
                    kCGImageSourceShouldCacheImmediately: true,
                ]
                let thumbnail = source().flatMap {
                    CGImageSourceCreateThumbnailAtIndex($0, 0, options as CFDictionary)
                }
                continuation.resume(returning: thumbnail)
            }
        }
    }
}

struct SelectedAvatar: Transferable {
    let png: Data

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .image) { received in
            // The avatar route (and the register route) accept photos up to
            // 10 MB, not 1 MB. Keep the full-resolution image where it fits;
            // only a PNG above the ceiling is scaled down, and only enough to
            // fit it. The result is always PNG, so the data URI the signup
            // sends and the multipart part the profile screen sends both name
            // the bytes' real format.
            for limit in [8192, 4096, 2048, 1024, 512] {
                guard let image = await AvatarThumbnail.decode(received.file, maxSize: limit),
                      let png = UIImage(cgImage: image).pngData() else {
                    throw CocoaError(.fileReadCorruptFile)
                }
                if !png.isEmpty, png.count <= 10 * 1024 * 1024 { return Self(png: png) }
            }
            throw CocoaError(.fileReadTooLarge)
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
    /// A resource: every caller passes a literal, which the String Catalog
    /// extracts; uppercased in the device locale after lookup.
    let text: LocalizedStringResource
    var body: some View {
        Text(String(localized: text).uppercased())
            .gmFont(11, .bold).kerning(1)
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
            .gmFont(13)
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
    /// A resource so each call site's literal is extracted; `AppTextField`
    /// takes the looked-up String.
    let hint: LocalizedStringResource
    @Binding var value: String
    let busy: Bool
    let marker: String
    let onAdd: () -> Void
    /// The round add button grows with the text size beside it (capped).
    @ScaledMetric(relativeTo: .body) private var addButton: CGFloat = 44

    var body: some View {
        HStack(spacing: 10) {
            AppTextField(value: $value, hint: String(localized: hint))
                .frame(maxWidth: .infinity)
            Button(action: { if !value.trimmed.isEmpty && !busy { onAdd() } }) {
                ZStack {
                    Circle()
                        .fill(!value.trimmed.isEmpty && !busy ? GM.primarySolid : GM.buttonDisabled)
                        .frame(width: min(addButton, 64), height: min(addButton, 64))
                    if busy {
                        ProgressView().controlSize(.small).tint(.white)
                    } else {
                        Image(systemName: "plus").gmFont(16, .semibold, maxSize: 24)
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
            Text(title).gmFont(15).foregroundColor(GM.textPrimary)
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
            Text(text).gmFont(15).foregroundColor(GM.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onRemove) {
                Image(systemName: "trash").gmFont(15)
                    .foregroundColor(GM.textTertiary)
            }
            .accessibilityLabel("Remove")
            .accessibilityIdentifier(marker)
        }
        .padding(.vertical, 10)
    }
}

private struct DetailLine2: View {
    @Environment(\.dynamicTypeSize) private var dts
    /// The label is a literal at every call site; the value is data.
    let label: LocalizedStringKey
    let value: String
    var body: some View {
        // Label column beside the value; at the accessibility text sizes the
        // fixed 150-point column would squeeze the value, so they stack.
        GMAdaptiveStack(spacing: dts.isAccessibilitySize ? CGFloat(2) : nil, verticalAlignment: .top) {
            Text(label).gmFont(13).foregroundColor(GM.textSecondary)
                .frame(width: dts.isAccessibilitySize ? nil : CGFloat(150), alignment: .leading)
            Text(value).gmFont(14).foregroundColor(GM.textPrimary)
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
    /// Screen states: Try again under a failed read. Optional so every
    /// existing call site keeps compiling.
    var onRetry: (() -> Void)? = nil
    var body: some View {
        if profile == nil && fatal != nil {
            Text("\(what) could not be read. \(fatal!)")
                .gmFont(14).foregroundColor(GM.textSecondary)
                .padding(.vertical, 16)
            if let onRetry {
                AccountLink(title: String(localized: "Try again"), action: onRetry)
            }
        } else if profile == nil {
            if loading {
                GMSkeletonBlock(lines: 4)
            } else {
                Text("\(what) could not be read.")
                    .gmFont(14).foregroundColor(GM.textTertiary)
                    .padding(.vertical, 16)
                if let onRetry {
                    AccountLink(title: String(localized: "Try again"), action: onRetry)
                }
            }
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
        MailScaffold(title: String(localized: "Notes"), onBack: onBack) {
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
                    Text("Notes are stored on the account, not on this phone, so they follow you to any machine you sign in from.")
                        .gmFont(12).foregroundColor(GM.textTertiary)
                        .padding(.vertical, 6)
                    if vm.state.profile == nil {
                        AccountReadState(profile: vm.state.profile, loading: vm.state.loading,
                                         fatal: vm.state.fatal, what: String(localized: "Notes"),
                                         onRetry: { vm.load(force: true) })
                    } else if let notes = vm.state.profile?.settings?.notes {
                        if notes.isEmpty {
                            Text("Nothing kept here yet.")
                                .gmFont(14).foregroundColor(GM.textTertiary)
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
                // Wide windows (iPad, Stage Manager, Mac): a centred form column.
                .gmMaxWidth(GMLayout.form)
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
        MailScaffold(title: String(localized: "Tasks"), onBack: onBack) {
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
                        .gmFont(12).foregroundColor(GM.textTertiary)
                        .padding(.vertical, 6)
                    if vm.state.profile == nil {
                        AccountReadState(profile: vm.state.profile, loading: vm.state.loading,
                                         fatal: vm.state.fatal, what: String(localized: "Tasks"),
                                         onRetry: { vm.load(force: true) })
                    } else if let tasks = vm.state.profile?.settings?.tasks {
                        if tasks.isEmpty {
                            Text("No tasks on this account.")
                                .gmFont(14).foregroundColor(GM.textTertiary)
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
                                Text(task).gmFont(15).foregroundColor(GM.textPrimary)
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
                // Wide windows (iPad, Stage Manager, Mac): a centred form column.
                .gmMaxWidth(GMLayout.form)
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
    @State private var timezone = ""
    @State private var zoneError: String?
    @State private var safeDraft = ""
    @State private var blockedDraft = ""
    @State private var safeError: String?
    @State private var blockedError: String?
    @State private var awayOn = false
    @State private var awayMessage = ""
    @State private var awayFrom = ""
    @State private var awayTo = ""
    @State private var awayError: String?
    @State private var forwardOn = false
    @State private var forwardTo = ""
    @State private var forwardError: String?
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
        MailScaffold(title: String(localized: "Mail settings"), onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let note = vm.state.note {
                        NoteLine(text: note, bad: vm.state.noteIsBad)
                    }
                    if s == nil {
                        AccountReadState(profile: vm.state.profile, loading: vm.state.loading,
                                         fatal: vm.state.fatal, what: String(localized: "These settings"),
                                         onRetry: { vm.load(force: true) })
                    } else if fieldsReady {
                        prefsBody
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
                // Wide windows (iPad, Stage Manager, Mac): a centred form column.
                .gmMaxWidth(GMLayout.form)
            }
        }
        .task { vm.load() }
        .task(id: s != nil) {
            guard !fieldsReady, let settings = s else { return }
            signature = settings.signature
            timezone = settings.locale.timezone
            awayOn = settings.vacation.enabled
            awayMessage = settings.vacation.message
            awayFrom = settings.vacation.from
            awayTo = settings.vacation.to
            forwardOn = settings.forwarding.enabled
            forwardTo = settings.forwarding.address
            overrides = settings.signatures
            fieldsReady = true
        }
    }

    private var prefsBody: some View {
        VStack(alignment: .leading, spacing: 0) {
            AccountSectionLabel(text: "Signature")
            Text("Appended to what you send, on every address you write from unless an override says otherwise.")
                .gmFont(12).foregroundColor(GM.textTertiary)
            TextEditor(text: $signature)
                .gmFont(15)
                .frame(minHeight: 80)
                .padding(8)
                .overlay(RoundedRectangle(cornerRadius: GMShape.input)
                    .strokeBorder(GM.border))
                .accessibilityIdentifier("gm-signature-field")
            Button {
                vm.saveSettings(ProfileSettingsPatch(signature: signature))
            } label: {
                Text("Save signature")
                    .gmFont(14, .semibold)
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
            Text("When you send as an alias, this is what gets added, so an alias does not sign like your personal address. Leave one empty and that address uses the main signature above.")
                .gmFont(12).foregroundColor(GM.textTertiary)
            ForEach(signatureAddresses, id: \.self) { address in
                Text(address)
                    .gmFont(12, .medium).foregroundColor(GM.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 10)
                TextEditor(text: Binding(
                    get: { overrides[address] ?? "" },
                    set: { overrides[address] = $0 }
                ))
                .gmFont(14)
                .frame(minHeight: 60)
                .padding(6)
                .overlay(alignment: .topLeading) {
                    if (overrides[address] ?? "").isEmpty {
                        Text("Uses the main signature")
                            .gmFont(14).foregroundColor(GM.textTertiary)
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
                    .gmFont(14, .semibold)
                    .foregroundColor(overridesDirty ? GM.primarySolid : GM.textTertiary)
                    .padding(.vertical, 8)
            }
            .disabled(!overridesDirty || vm.state.busy)
            .buttonStyle(.plain)
            .accessibilityIdentifier("gm-save-signatures")

            MailNotificationPreviewSetting()

            MailFeatureSettingsSection()

            AccountSectionLabel(text: "List density")
            AccountRadioRow(title: String(localized: "Comfortable — the roomier list"),
                            selected: s?.density != "compact") {
                if s?.density != "comfortable" {
                    vm.saveSettings(ProfileSettingsPatch(density: "comfortable"))
                }
            }
            AccountRadioRow(title: String(localized: "Compact — more messages per screen"),
                            selected: s?.density == "compact") {
                if s?.density != "compact" {
                    vm.saveSettings(ProfileSettingsPatch(density: "compact"))
                }
            }

            // The app's own language is iOS's per-app setting (Settings → GrapMail
            // → Language), so it is not repeated here as a free-text field that
            // nothing on this device read.
            AccountSectionLabel(text: "Time zone and dates")
            Text("How times and dates appear in your mailbox list.")
                .gmFont(12).foregroundColor(GM.textTertiary)
            AccountRadioRow(title: String(localized: "This device's time zone (\(TimeZone.current.identifier))"),
                            selected: (s?.locale.timezone ?? "").isEmpty) {
                zoneError = nil
                if let loc = s?.locale, !loc.timezone.isEmpty {
                    vm.saveSettings(ProfileSettingsPatch(locale: LocaleInfo(
                        language: loc.language, timezone: "", dateFormat: loc.dateFormat)))
                }
            }
            AppTextField(title: String(localized: "Another time zone"), value: $timezone, error: zoneError,
                         hint: String(localized: "e.g. Europe/London"))
                .padding(.top, 4)
                .onChange(of: timezone) { _ in zoneError = nil }
            Button {
                guard let loc = s?.locale else { return }
                guard let zone = MailDisplay.zone(timezone.trimmed) else {
                    zoneError = String(localized: "That isn't a time zone. Use a name like Europe/London or America/New_York.")
                    return
                }
                vm.saveSettings(ProfileSettingsPatch(locale: LocaleInfo(
                    language: loc.language, timezone: zone.identifier, dateFormat: loc.dateFormat)))
            } label: {
                Text("Use this time zone")
                    .gmFont(14, .semibold)
                    .foregroundColor(zoneDirty ? GM.primarySolid : GM.textTertiary).padding(.vertical, 8)
            }
            .disabled(vm.state.busy || !zoneDirty)
            .buttonStyle(.plain)
            Text("Dates in the mailbox list:")
                .gmFont(13).foregroundColor(GM.textSecondary)
            ForEach([("", String(localized: "Automatic — follows this device")), ("dmy", String(localized: "day first (25/10/26)")),
                     ("mdy", String(localized: "month first (10/25/26)")), ("ymd", String(localized: "year first (2026-10-25)"))], id: \.0) { code, label in
                AccountRadioRow(title: label, selected: s?.locale.dateFormat == code) {
                    if let loc = s?.locale, loc.dateFormat != code {
                        vm.saveSettings(ProfileSettingsPatch(
                            locale: LocaleInfo(language: loc.language, timezone: loc.timezone,
                                               dateFormat: code)))
                    }
                }
            }

            autoReplySection
            forwardingSection

            // These two lists are enforced on delivery: the server turns each entry
            // into a Screener decision (safe = approved, blocked = blocked), and a
            // block applies whether or not the Screener is on. A sender can be on
            // one list only, so adding to one takes it off the other.
            AccountSectionLabel(text: "Senders you always accept")
            Text("Mail from these senders always lands in your inbox, even when the Screener is on. Use an address (name@example.com) or a whole domain (example.com).")
                .gmFont(12).foregroundColor(GM.textTertiary)
            AccountAddRow(hint: "name@example.com or example.com", value: $safeDraft,
                          busy: vm.state.busy, marker: "gm-add-safe") {
                guard let sender = Self.normalizedSender(safeDraft) else {
                    safeError = String(localized: "Enter an address like name@example.com, or a domain like example.com.")
                    return
                }
                vm.saveSettings(ProfileSettingsPatch(
                    blockedSenders: (s?.blockedSenders ?? []).filter { $0 != sender },
                    safeSenders: ((s?.safeSenders ?? []) + [sender]).uniq()))
                safeDraft = ""
            }
            .onChange(of: safeDraft) { _ in safeError = nil }
            if let safeError {
                Text(safeError).gmFont(12).foregroundColor(GM.error)
            }
            ForEach(s?.safeSenders ?? [], id: \.self) { e in
                AccountListRow(text: e, marker: "gm-safe-row") {
                    vm.saveSettings(ProfileSettingsPatch(
                        safeSenders: (s?.safeSenders ?? []).filter { $0 != e }))
                }
            }

            AccountSectionLabel(text: "Senders you block")
            Text("Mail from blocked senders goes straight to Trash. Use an address (name@example.com) or a whole domain (example.com).")
                .gmFont(12).foregroundColor(GM.textTertiary)
            AccountAddRow(hint: "name@example.com or example.com", value: $blockedDraft,
                          busy: vm.state.busy, marker: "gm-add-blocked") {
                guard let sender = Self.normalizedSender(blockedDraft) else {
                    blockedError = String(localized: "Enter an address like name@example.com, or a domain like example.com.")
                    return
                }
                vm.saveSettings(ProfileSettingsPatch(
                    blockedSenders: ((s?.blockedSenders ?? []) + [sender]).uniq(),
                    safeSenders: (s?.safeSenders ?? []).filter { $0 != sender }))
                blockedDraft = ""
            }
            .onChange(of: blockedDraft) { _ in blockedError = nil }
            if let blockedError {
                Text(blockedError).gmFont(12).foregroundColor(GM.error)
            }
            ForEach(s?.blockedSenders ?? [], id: \.self) { e in
                AccountListRow(text: e, marker: "gm-blocked-row") {
                    vm.saveSettings(ProfileSettingsPatch(
                        blockedSenders: (s?.blockedSenders ?? []).filter { $0 != e }))
                }
            }
        }
    }

    private var zoneDirty: Bool {
        !timezone.trimmed.isEmpty && timezone.trimmed != s?.locale.timezone
    }

    // MARK: auto-reply and forwarding
    //
    // Both are enforced on delivery by the backend (lib/mail-automation.ts), and
    // used to be editable only on the website.

    private var autoReplySection: some View {
        VStack(alignment: .leading, spacing: 0) {
            AccountSectionLabel(text: "Auto-reply")
            Text("While it's on, people who write to you get this message once every 4 days. Mailing lists and no-reply senders never get one.")
                .gmFont(12).foregroundColor(GM.textTertiary)
            Toggle("Send auto-replies", isOn: $awayOn)
                .gmFont(15).padding(.vertical, 6)
                .onChange(of: awayOn) { _ in awayError = nil }
            TextEditor(text: $awayMessage)
                .gmFont(15)
                .frame(minHeight: 80)
                .padding(8)
                .overlay(alignment: .topLeading) {
                    if awayMessage.isEmpty {
                        Text("I'm away until Monday and will reply when I'm back.")
                            .gmFont(15).foregroundColor(GM.textTertiary)
                            .padding(.leading, 13).padding(.top, 16)
                            .allowsHitTesting(false)
                    }
                }
                .overlay(RoundedRectangle(cornerRadius: GMShape.input)
                    .strokeBorder(awayError == nil ? GM.border : GM.error))
                .accessibilityLabel("Auto-reply message")
            GMAdaptiveStack(spacing: 10) {
                AppTextField(title: String(localized: "First day"), value: $awayFrom, hint: String(localized: "YYYY-MM-DD (optional)"))
                AppTextField(title: String(localized: "Last day"), value: $awayTo, hint: String(localized: "YYYY-MM-DD (optional)"))
            }
            .padding(.top, 8)
            if let awayError {
                Text(awayError).gmFont(12).foregroundColor(GM.error).padding(.top, 4)
            }
            Button {
                awayError = Self.vacationProblem(on: awayOn, message: awayMessage,
                                                 from: awayFrom.trimmed, to: awayTo.trimmed)
                guard awayError == nil else { return }
                vm.saveSettings(ProfileSettingsPatch(vacation: VacationInfo(
                    enabled: awayOn, from: awayFrom.trimmed, to: awayTo.trimmed, message: awayMessage)))
            } label: {
                Text("Save auto-reply")
                    .gmFont(14, .semibold)
                    .foregroundColor(awayDirty ? GM.primarySolid : GM.textTertiary)
                    .padding(.vertical, 8)
            }
            .disabled(!awayDirty || vm.state.busy)
            .buttonStyle(.plain)
        }
    }

    private var awayDirty: Bool {
        guard let v = s?.vacation else { return false }
        return v.enabled != awayOn || v.message != awayMessage ||
            v.from != awayFrom.trimmed || v.to != awayTo.trimmed
    }

    private var forwardingSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            AccountSectionLabel(text: "Forwarding")
            Text("Send a copy of every message you receive to another address. The original always stays in GrapMail, and the copy is plain text. Turning it on also puts a security notice in your inbox, so you'd notice if someone else did.")
                .gmFont(12).foregroundColor(GM.textTertiary)
            Toggle("Forward my mail", isOn: $forwardOn)
                .gmFont(15).padding(.vertical, 6)
                .onChange(of: forwardOn) { _ in forwardError = nil }
            AppTextField(title: String(localized: "Forward to"), value: $forwardTo, error: forwardError,
                         hint: "name@example.com", keyboardType: .emailAddress)
                .onChange(of: forwardTo) { _ in forwardError = nil }
            Button {
                let address = forwardTo.trimmed.lowercased()
                let own = Set(signatureAddresses.map { $0.lowercased() })
                if forwardOn && address.isEmpty {
                    forwardError = String(localized: "Add the address to forward to.")
                } else if !address.isEmpty && Self.normalizedSender(address)?.contains("@") != true {
                    forwardError = String(localized: "Forward to a full email address.")
                } else if own.contains(address) {
                    forwardError = String(localized: "Forwarding to your own address would loop.")
                } else {
                    vm.saveSettings(ProfileSettingsPatch(forwarding: ForwardingInfo(
                        enabled: forwardOn, address: address)))
                }
            } label: {
                Text("Save forwarding")
                    .gmFont(14, .semibold)
                    .foregroundColor(forwardDirty ? GM.primarySolid : GM.textTertiary)
                    .padding(.vertical, 8)
            }
            .disabled(!forwardDirty || vm.state.busy)
            .buttonStyle(.plain)
        }
    }

    private var forwardDirty: Bool {
        guard let f = s?.forwarding else { return false }
        return f.enabled != forwardOn || f.address != forwardTo.trimmed.lowercased()
    }

    /// A sender the safe and blocked lists accept: an address, or a bare domain.
    static func normalizedSender(_ raw: String) -> String? {
        var value = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if value.hasPrefix("@") { value.removeFirst() }
        let address = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$"
        let domain = "^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$"
        let ok = value.range(of: address, options: .regularExpression) != nil ||
            value.range(of: domain, options: .regularExpression) != nil
        return ok ? value : nil
    }

    /// The rules the server and the delivery path apply to an auto-reply.
    static func vacationProblem(on: Bool, message: String, from: String, to: String) -> String? {
        let start = from.isEmpty ? nil : AccountDates.parse(from)
        let end = to.isEmpty ? nil : AccountDates.parse(to)
        if !from.isEmpty && start == nil { return String(localized: "Write the first day as YYYY-MM-DD.") }
        if !to.isEmpty && end == nil { return String(localized: "Write the last day as YYYY-MM-DD.") }
        if let start, let end, end < start { return String(localized: "The last day is before the first day.") }
        if on && message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return String(localized: "Write the auto-reply before turning it on.")
        }
        return nil
    }
}

/// `YYYY-MM-DD` dates, parsed strictly (no "2026-02-31" rolling into March).
enum AccountDates {
    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd"
        f.isLenient = false
        return f
    }()

    static func parse(_ value: String) -> Date? {
        guard let date = formatter.date(from: value), formatter.string(from: date) == value else { return nil }
        return date
    }

    /// The sign-up rule: a real date, not in the future, 1900 or later, age 13+.
    static func birthdayProblem(_ value: String) -> String? {
        let text = value.trimmingCharacters(in: .whitespaces)
        if text.isEmpty { return nil }
        guard let date = parse(text) else { return String(localized: "Enter your birthday as YYYY-MM-DD.") }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        if date > Date() || cal.component(.year, from: date) < 1900 { return String(localized: "Enter a real birthday.") }
        let age = cal.dateComponents([.year], from: date, to: Date()).year ?? 0
        return age < 13 ? String(localized: "A GrapMail account needs you to be at least 13.") : nil
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
        MailScaffold(title: String(localized: "Addresses"), onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let note = vm.state.note {
                        NoteLine(text: note, bad: vm.state.noteIsBad)
                    }
                    AccountSectionLabel(text: "Your GrapMail address")
                    Text(vm.state.profile?.address
                         ?? (vm.state.fatal != nil ? String(localized: "Could not be read") : String(localized: "Loading…")))
                        .gmFont(16, .medium)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14).padding(.vertical, 12)
                        .background(RoundedRectangle(cornerRadius: 10).fill(GM.bgTinted))
                        .accessibilityIdentifier("gm-primary-address")
                    AccountSectionLabel(text: "Email aliases")
                    Text("An alias delivers into the same mailbox under a second address, and you can choose it as the sender.")
                        .gmFont(12).foregroundColor(GM.textTertiary)
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
                            Text("Stop receiving at \(address)? Mail already delivered stays in the mailbox; nothing new arrives at this address.")
                                .gmFont(13).foregroundColor(GM.textPrimary)
                            GMAdaptiveStack(spacing: 24) {
                                Button("Cancel") { pendingDelete = nil }
                                    .foregroundColor(GM.textSecondary)
                                Button("Remove address") {
                                    pendingDelete = nil
                                    vm.aliasWrite(value: address, remove: true)
                                }
                                .foregroundColor(GM.error)
                                .fontWeight(.semibold)
                            }
                            .gmFont(14)
                        }
                        .padding(16)
                        .background(RoundedRectangle(cornerRadius: 12).fill(GM.warnChipBg))
                        .accessibilityIdentifier("gm-alias-confirm")
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
                // Wide windows (iPad, Stage Manager, Mac): a centred form column.
                .gmMaxWidth(GMLayout.form)
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
                    .gmFont(14).foregroundColor(GM.textTertiary)
                    .padding(.vertical, 12)
            }
            ForEach(aliases) { a in
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(a.label.trimmed.isEmpty ? a.address : a.label)
                            .gmFont(15, .medium)
                            .foregroundColor(GM.textPrimary)
                        Text(a.address).gmFont(13).foregroundColor(GM.textSecondary)
                        if !a.isActive {
                            Text("Paused — nothing new arrives here.")
                                .gmFont(12).foregroundColor(GM.warnInk)
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
                .gmFont(14).foregroundColor(GM.textSecondary)
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
    /// The monogram tile grows with the text size beside it (capped).
    @ScaledMetric(relativeTo: .body) private var tile: CGFloat = 38

    private var connected: [ProfileConnection] { vm.state.profile?.connections ?? [] }

    private var filters: [(String, String)] {
        [("all", String(localized: "All")), ("mailbox", String(localized: "With mail")),
         ("products", String(localized: "Grapsee apps"))]
    }

    private func filterChip(_ key: String, _ label: String) -> some View {
        let count = connected.count { c in
            switch key {
            case "mailbox": return c.scopes.contains("mailbox")
            case "products": return !(c.host?.contains(".") ?? false)
            default: return true
            }
        }
        return Button {
            viewFilter = key
        } label: {
            Text("\(label) (\(count))")
                .gmFont(13, .medium)
                .foregroundColor(viewFilter == key ? GM.primarySolid
                                                   : GM.textSecondary)
                .padding(.horizontal, 10).padding(.vertical, 6)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("gm-view-\(key)")
    }

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
        MailScaffold(title: String(localized: "Connected accounts"), onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let note = vm.state.note {
                        NoteLine(text: note, bad: vm.state.noteIsBad)
                    }
                    Text("When you use this account to sign in somewhere, that app appears here with what it can reach, and you can remove it at any time.")
                        .gmFont(13).foregroundColor(GM.textSecondary)
                        .padding(.vertical, 8)
                    AppTextField(value: $query, hint: String(localized: "Search connected apps by name"))
                        .accessibilityIdentifier("gm-connections-search")
                    // One row of filters while it fits; a column when the
                    // text size (or a narrow window) leaves no room for three.
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 8) {
                            ForEach(filters, id: \.0) { key, label in filterChip(key, label) }
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            ForEach(filters, id: \.0) { key, label in filterChip(key, label) }
                        }
                    }
                    .padding(.vertical, 10)
                    if vm.state.profile == nil {
                        // Not read yet (or refused): never claim "no app has access".
                        AccountReadState(profile: nil, loading: vm.state.loading,
                                         fatal: vm.state.fatal, what: String(localized: "Connected accounts"),
                                         onRetry: { vm.load(force: true) })
                    } else if shown.isEmpty {
                        Text(connected.isEmpty ? "No app has access to this account."
                                               : "Nothing matches that.")
                            .gmFont(14).foregroundColor(GM.textTertiary)
                            .padding(.vertical, 16)
                    }
                    ForEach(shown) { c in
                        GMAdaptiveStack(spacing: 12) {
                            ZStack {
                                RoundedRectangle(cornerRadius: 10).fill(GM.softFill)
                                    .frame(width: min(tile, 60), height: min(tile, 60))
                                Text(monogram(c.product))
                                    .gmFont(14, .semibold, maxSize: 22)
                                    .foregroundColor(GM.primarySolid)
                            }
                            VStack(alignment: .leading, spacing: 2) {
                                Text(c.product).gmFont(15, .medium)
                                    .foregroundColor(GM.textPrimary)
                                let scopes = c.scopes.map(scopeWord).joined(separator: ", ")
                                Text(scopes.isEmpty ? "Signed in here" : "Sees: \(scopes)")
                                    .gmFont(12).foregroundColor(GM.textSecondary)
                            }
                            Spacer()
                            Button {
                                pendingRemove = c
                            } label: {
                                Text("Remove").gmFont(13, .semibold)
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
                            Text("Cut \(c.product) off? It loses access to what is listed beside it, and has to sign in again to get back in.")
                                .gmFont(13).foregroundColor(GM.textPrimary)
                            GMAdaptiveStack(spacing: 24) {
                                Button("Cancel") { pendingRemove = nil }
                                    .foregroundColor(GM.textSecondary)
                                Button("Remove access") {
                                    pendingRemove = nil
                                    vm.saveConnections(keep: connected.filter { $0.product != c.product })
                                }
                                .foregroundColor(GM.error).fontWeight(.semibold)
                            }
                            .gmFont(14)
                        }
                        .padding(16)
                        .background(RoundedRectangle(cornerRadius: 12).fill(GM.warnChipBg))
                        .accessibilityIdentifier("gm-connection-confirm")
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
                // Wide windows (iPad, Stage Manager, Mac): a centred form column.
                .gmMaxWidth(GMLayout.form)
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
    case "profile": return String(localized: "name, address and photo")
    case "mailbox": return String(localized: "your mailbox")
    case "projects": return String(localized: "your projects")
    case "contacts": return String(localized: "your contacts")
    default: return scope
    }
}

/// The web's FULL sharing sentences (`LinkedAppDetail.tsx` SHARED map), for
/// the per-app page where the person decides what cutting access costs. The
/// list row uses the short words; the detail uses these, exactly as the web
/// splits the two. Android's `sharedWord` is the twin.
func sharedWord(_ scope: String) -> String {
    switch scope {
    case "profile": return String(localized: "Your name, GrapMail address and profile photo")
    case "mailbox": return String(localized: "Read and send mail in your GrapMail mailbox")
    case "projects": return String(localized: "Projects and client records you own on Grapsee products")
    case "contacts": return String(localized: "The people you have exchanged mail with")
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
    @ScaledMetric(relativeTo: .body) private var headerTile: CGFloat = 48

    private var connected: [ProfileConnection] { vm.state.profile?.connections ?? [] }
    private var entry: ProfileConnection? {
        connected.first { $0.product.lowercased() == product.lowercased() }
    }
    private var scopes: [String] { entry?.scopes ?? [] }

    var body: some View {
        // The product's own name, as the server recorded it: data, not copy.
        MailScaffold(title: product, onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if vm.state.profile == nil {
                        Text(vm.state.loading ? "Loading…"
                             : "The account could not be read, so this screen cannot say what the app can reach.")
                            .gmFont(14).foregroundColor(GM.textTertiary)
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
                            Text("This app does not have access to this account. Nothing is being shared with it, and there is nothing to remove.")
                                .gmFont(14).lineSpacing(5)
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
                // Wide windows (iPad, Stage Manager, Mac): a centred form column.
                .gmMaxWidth(GMLayout.form)
            }
        }
        .task { vm.load() }
    }

    private var header: some View {
        GMAdaptiveStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 12).fill(GM.softFill)
                    .frame(width: min(headerTile, 72), height: min(headerTile, 72))
                Text(monogram(product)).gmFont(18, .semibold, maxSize: 28)
                    .foregroundColor(GM.primarySolid)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(product).gmFont(20).foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("gm-connection-detail-name")
                Text(entry.map(Self.connectedLine) ?? String(localized: "Not connected to this account"))
                    .gmFont(13).foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
        }
        .padding(.top, 8)
    }

    /// "host · connected 2026-03-14" as one localizable sentence, so a
    /// translation can reorder the host and the date.
    private static func connectedLine(_ c: ProfileConnection) -> String {
        let host = c.host ?? String(localized: "Grapsee product")
        let day = String((c.connectedAt ?? "").prefix(10))
        return String(localized: "\(host) · connected \(day)", comment: "Linked app subtitle: host or 'Grapsee product', then the date it was connected")
    }

    private var scopesCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("How GrapMail helps you sign in to \(product)")
                .gmFont(15).foregroundColor(GM.textPrimary)
            Text("When you use your Grapsee account to sign in to \(product), this account proves who you are and hands over only what is listed below. Your password is never shared with \(product).")
                .gmFont(13).lineSpacing(5)
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)
            Text("WHAT \(product.uppercased()) CAN REACH")
                .gmFont(12, .medium)
                .foregroundColor(GM.textSecondary)
                .padding(.top, 14)
            let scopes = self.scopes
            if scopes.isEmpty {
                Text("No access was recorded when this was connected.")
                    .gmFont(13).foregroundColor(GM.textSecondary)
                    .padding(.top, 6)
            }
            ForEach(scopes, id: \.self) { s in
                HStack(alignment: .top, spacing: 10) {
                    Circle().fill(GM.primarySolid).frame(width: 6, height: 6)
                        .padding(.top, 7)
                    Text(sharedWord(s)).gmFont(13)
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
                .gmFont(15, .medium).foregroundColor(GM.error)
            Text("Remove all links and stop sharing this account's data with \(product).")
                .gmFont(12).lineSpacing(4)
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
            Text("Cut \(product) off? It loses access to everything listed above and has to sign in again to get back in.")
                .gmFont(13).foregroundColor(GM.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            GMAdaptiveStack(spacing: 24) {
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
            .gmFont(14)
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
                .gmFont(12).foregroundColor(GM.textSecondary)
            Button("Report this app", action: onSupport)
                .gmFont(13, .medium)
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
    @State private var birthday = ""
    @State private var formError: String?
    @State private var showRecoveryEmail = false
    @State private var gender = ""
    @State private var photoError: String?
    @State private var photoItem: PhotosPickerItem?
    @State private var avatarPreview: UIImage?

    private var p: ProfileDto? { vm.state.profile }

    var body: some View {
        MailScaffold(title: String(localized: "Personal info"), onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let note = vm.state.note {
                        NoteLine(text: note, bad: vm.state.noteIsBad)
                    }
                    if let photoError { NoteLine(text: photoError, bad: true) }
                    if p == nil {
                        AccountReadState(profile: vm.state.profile, loading: vm.state.loading,
                                         fatal: vm.state.fatal, what: String(localized: "Your account record"),
                                         onRetry: { vm.load(force: true) })
                    } else if fieldsReady {
                        avatarRow
                        fields
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
                // Wide windows (iPad, Stage Manager, Mac): a centred form column.
                .gmMaxWidth(GMLayout.form)
            }
        }
        .task { vm.load() }
        .task(id: p != nil) {
            guard !fieldsReady, let profile = p else { return }
            first = profile.firstName ?? ""
            last = profile.lastName ?? ""
            display = profile.displayName ?? ""
            birthday = profile.birthday ?? ""
            // Held as the code; a profile still holding the legacy English
            // word ("Female") is read as its code so the right row is selected.
            gender = GenderOption.normalize(profile.gender) ?? ""
            fieldsReady = true
        }
        .task(id: vm.state.busy ? nil : p?.avatarUrl) {
            if !vm.state.busy { vm.loadAvatar() }
        }
        // The recovery email is proven with a code before it counts, so it is
        // changed in that flow, then the record is re-read for the new state.
        .sheet(isPresented: $showRecoveryEmail, onDismiss: { vm.load(force: true) }) {
            RecoveryEmailView(
                onBack: { showRecoveryEmail = false },
                onDone: { showRecoveryEmail = false },
                currentEmail: p?.recoveryEmail
            )
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
            guard let selected = try? await item.loadTransferable(type: SelectedAvatar.self) else {
                if !Task.isCancelled { photoError = String(localized: "Couldn’t open that photo. Choose another image.") }
                return
            }
            guard !Task.isCancelled else { return }
            photoError = nil
            vm.uploadAvatar(bytes: selected.png, filename: "profile.png", mime: "image/png")
        }
    }

    private var avatarRow: some View {
        GMAdaptiveStack(spacing: 14) {
            ZStack {
                Circle().fill(GM.softFill).frame(width: 64, height: 64)
                if let img = avatarPreview {
                    Image(uiImage: img).resizable()
                        .scaledToFill().frame(width: 64, height: 64).clipped()
                        .clipShape(Circle())
                } else {
                    Text(String((p?.name ?? "?").prefix(1)).uppercased())
                        .gmFont(22, .semibold, maxSize: 32)
                        .foregroundColor(GM.primarySolid)
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                PhotosPicker(selection: $photoItem, matching: .images) {
                    Text((p?.avatarUrl?.isEmpty == false) ? "Change photo" : "Add a photo")
                        .gmFont(14, .semibold)
                        .foregroundColor(GM.primarySolid)
                }
                .buttonStyle(.plain)
                if p?.avatarUrl?.isEmpty == false {
                    Button {
                        vm.removeAvatar()
                    } label: {
                        Text("Remove").gmFont(13).foregroundColor(GM.error)
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
            AppTextField(title: String(localized: "First name"), value: $first)
            AppTextField(title: String(localized: "Last name"), value: $last).padding(.top, 8)
            AccountSectionLabel(text: "Birthday")
            Text("A GrapMail address can receive mail from anyone, so the account requires an age of at least 13 — the same rule signup enforces.")
                .gmFont(12).foregroundColor(GM.textTertiary)
            AppTextField(title: String(localized: "Birthday"), value: $birthday, hint: String(localized: "YYYY-MM-DD"))
                .padding(.top, 6)
            AccountSectionLabel(text: "Gender")
            // The account stores the code (GenderOption, ProfileOptions.swift);
            // only the row label is translated.
            ForEach(GenderOption.all, id: \.self) { g in
                AccountRadioRow(title: GenderOption.label(g), selected: gender == g) {
                    gender = (gender == g) ? "" : g
                }
            }
            AccountSectionLabel(text: "How you appear")
            AppTextField(title: String(localized: "Display name"), value: $display, hint: String(localized: "What recipients see"))
            // A plain save here used to store the recovery email unverified, and
            // reset links are only sent to a verified one. The recovery phone is
            // gone: GrapMail recovers by email only, and nothing read it.
            AccountSectionLabel(text: "Recovery")
            Text(recoveryLine)
                .gmFont(14).foregroundColor(GM.textSecondary)
                .padding(.top, 4)
            Button {
                showRecoveryEmail = true
            } label: {
                Text((p?.recoveryEmail ?? "").isEmpty ? "Add recovery email" : "Change recovery email")
                    .gmFont(14, .semibold)
                    .foregroundColor(GM.primarySolid).padding(.vertical, 8)
            }
            .buttonStyle(.plain)
            if let formError {
                Text(formError).gmFont(13).foregroundColor(GM.error).padding(.vertical, 6)
            }
            Button {
                // The same rules sign-up applies, checked before the request so
                // the person sees which field is wrong.
                if first.trimmed.count < 2 {
                    formError = String(localized: "Enter your first name (2+ characters).")
                } else if last.trimmed.count < 2 {
                    formError = String(localized: "Enter your last name (2+ characters).")
                } else if display.trimmed.count > 60 {
                    formError = String(localized: "Keep the display name to 60 characters.")
                } else {
                    formError = AccountDates.birthdayProblem(birthday)
                }
                guard formError == nil else { return }
                vm.save(label: String(localized: "Saved."), personal: [
                    "firstName": first.trimmed, "lastName": last.trimmed, "birthday": birthday.trimmed,
                    "gender": gender, "displayName": display.trimmed,
                ])
            } label: {
                Text(vm.state.busy ? "Saving…" : "Save")
                    .gmFont(15, .semibold)
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

    private var recoveryLine: String {
        guard let email = p?.recoveryEmail, !email.isEmpty else {
            return String(localized: "No recovery email yet. Without one, a forgotten password can't be reset.")
        }
        return p?.recoveryEmailVerified == true ? String(localized: "\(email) · verified") : String(localized: "\(email) · not verified yet")
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
        MailScaffold(title: String(localized: "Data & privacy"), onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    AccountSectionLabel(text: "What is kept")
                    ForEach([
                        String(localized: "GrapMail address and aliases: the identity of the account and any extra addresses that deliver into it."),
                        String(localized: "Your messages: stored for your account only. Nobody at Grapsee reads message contents to build profiles, and addresses are not sold."),
                        String(localized: "When you agreed to the terms: a timestamp, so the record can answer “did they agree, and when?”"),
                    ], id: \.self) { line in
                        Text("• \(line)")
                            .gmFont(13).foregroundColor(GM.textSecondary)
                            .padding(.vertical, 4)
                    }
                    AccountSectionLabel(text: "Your controls")
                    AccountControlRow(title: String(localized: "Edit personal info"), value: String(localized: "Name, birthday, photo"),
                                      marker: "gm-privacy-profile", onClick: onProfile)
                    AccountControlRow(title: String(localized: "Change phone number"), value: String(localized: "Proven with a code"),
                                      marker: "gm-privacy-phone", onClick: onPhone)
                    AccountControlRow(title: String(localized: "Manage addresses"), value: String(localized: "Primary and aliases"),
                                      marker: "gm-privacy-addresses", onClick: onAddresses)
                    AccountControlRow(title: String(localized: "Mail settings"), value: String(localized: "Signature, density, locale"),
                                      marker: "gm-privacy-prefs", onClick: onPrefs)
                    AccountSectionLabel(text: "This account")
                    DetailLine2(label: "Address", value: p?.address
                        ?? (vm.state.fatal != nil ? String(localized: "Could not be read") : String(localized: "Loading…")))
                    DetailLine2(label: "Created", value: p.flatMap { String($0.createdAt?.prefix(10) ?? "") }.flatMap { $0.isEmpty ? nil : $0 }
                        ?? (p == nil ? String(localized: "Could not be read") : String(localized: "Not reported")))
                    DetailLine2(label: "Terms accepted", value: p.flatMap { String($0.termsAcceptedAt?.prefix(10) ?? "") }.flatMap { $0.isEmpty ? nil : $0 }
                        ?? (p == nil ? String(localized: "Could not be read") : String(localized: "not recorded")))
                    DetailLine2(label: "2-step verification", value: {
                        switch p?.twoFactorState {
                        case .some(true): return String(localized: "On")
                        case .some(false): return String(localized: "Off")
                        case .none: return p == nil ? String(localized: "Could not be read") : String(localized: "Not reported")
                        }
                    }())
                    AccountSectionLabel(text: "Deleting the account")
                    Text("Deletion removes the mailbox, every message in it, all aliases, and the GrapMail address itself. It is irreversible, so it asks for the account password and a final confirmation.")
                        .gmFont(13).foregroundColor(GM.textSecondary)
                        .padding(.vertical, 6)
                    Button("Delete account", role: .destructive) { showDeletion = true }
                        .padding(.vertical, 8)
                        .accessibilityIdentifier("gm-privacy-delete")
                    Button {
                        onSupport()
                    } label: {
                        Text("Go to help & support")
                            .gmFont(14, .semibold)
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
                            .gmFont(14).foregroundColor(GM.primarySolid)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.plain)
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
                // Wide windows (iPad, Stage Manager, Mac): a centred form column.
                .gmMaxWidth(GMLayout.form)
            }
        }
        .task { vm.load() }
        .sheet(isPresented: $showDeletion) {
            DeleteAccountView {
                showDeletion = false
                onDeleted()
            }
            .grapMailSheetSurface()
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
            error = String(localized: "Sign in again before requesting deletion.")
            return
        }
        busy = true; error = nil
        Task { @MainActor in
            defer { busy = false; password = "" }
            do {
                _ = try await ApiClient.deleteAccount(token: token, password: password)
                // Round 8 — B: a deleted account is forgotten on this device too
                // (spec §2.4), read before clear() removes the stored address.
                if let address = SessionStore.address {
                    RememberedAccounts.prepareSignOut(address: address, removeFromDevice: true)
                }
                SessionStore.clear()
                URLCache.shared.removeAllCachedResponses()
                onDeleted()
            } catch {
                // One sentence with the server's reason interpolated, not a
                // translated head glued to another translated string.
                let reason = apiMessage(error, fallback: String(localized: "Try again in a moment."))
                self.error = String(localized: "Deletion was not confirmed. Check account status before retrying. \(reason)")
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
                    Text(title).gmFont(15).foregroundColor(GM.textPrimary)
                    Text(value).gmFont(12).foregroundColor(GM.textSecondary)
                }
                Spacer()
                Image(systemName: "chevron.forward").gmFont(13, maxSize: 24)
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

    private struct SupportLink: Identifiable {
        let label: String
        let url: String
        var id: String { url }
    }

    private let supportLinks = [
        SupportLink(label: String(localized: "Browse GrapMail help"), url: "https://grapsee.com/help/products/grapmail"),
        SupportLink(label: String(localized: "Open a support case"), url: "https://grapsee.com/help/request?product=grapmail"),
        SupportLink(label: String(localized: "Track a support case"), url: "https://grapsee.com/help/cases"),
    ]

    private let faq: [(String, String)] = [
        (String(localized: "I forgot my password. What now?"),
         String(localized: "Tap Forgot password on the sign-in screen. GrapMail emails a reset link to the recovery email on your account, so add and verify one in Personal info before you need it.")),
        (String(localized: "Can I sign in with my phone number?"),
         String(localized: "Yes. The sign-in page accepts either the GrapMail address or the phone number you verified, but the number is never the identity of the account, and mail is never addressed to it.")),
        (String(localized: "I changed my number. Will I lose the account?"),
         String(localized: "No, if you change it before losing access. The new number is proven with a code before it replaces the old one.")),
        (String(localized: "Why can I not create an account for someone under 13?"),
         String(localized: "GrapMail holds an address that can receive mail from anyone, so the account requires an age of at least 13. The birthday you enter is checked against that rule.")),
        (String(localized: "What is an alias for?"),
         String(localized: "An alias delivers into the same mailbox under a second address, and can be chosen as the sender. Useful when you want to write as “business” without a second account.")),
        (String(localized: "Mail I was expecting never arrived."),
         String(localized: "In order: check the sending address was spelled exactly, look in Spam and Trash under “Others”, then check that the sender is not on the suppression list because they unsubscribed earlier. If the mailbox is connected and the message is still missing, the mail server has to be involved. That is a support matter.")),
    ]

    var body: some View {
        MailScaffold(title: String(localized: "Help & support"), onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Answers to the questions this product actually raises, and how to reach a person.")
                        .gmFont(13).foregroundColor(GM.textTertiary)
                        .padding(.vertical, 8)
                    ForEach(faq, id: \.0) { q, a in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack(spacing: 8) {
                                Text(open == q ? "−" : "+")
                                    .gmFont(15, .bold)
                                    .foregroundColor(GM.primarySolid)
                                Text(q).gmFont(14, .medium)
                                    .foregroundColor(GM.textPrimary)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            if open == q {
                                Text(a).gmFont(13).foregroundColor(GM.textSecondary)
                                    .lineSpacing(4)
                            }
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { open = (open == q) ? nil : q }
                        .padding(.vertical, 12)
                        .accessibilityIdentifier("gm-faq-item")
                    }
                    AccountSectionLabel(text: String(localized: "Get help from GrapMail"))
                    ForEach(supportLinks) { link in
                        Button { openExternal(link.url) } label: {
                            Text(link.label)
                                .gmFont(14, .semibold)
                                .foregroundColor(GM.primarySolid)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.vertical, 14)
                        }
                        .buttonStyle(.plain)
                    }
                    Text("Recovery screens, in the order that fixes things:")
                        .gmFont(13).foregroundColor(GM.textSecondary)
                        .padding(.top, 6)
                    ForEach([("Forgot password", onForgot), ("Change my phone", onPhone),
                             ("Manage addresses", onAddresses)], id: \.0) { label, go in
                        Button(action: go) {
                            // `label` stays the English key so the identifier below does
                            // not change with the language; the row draws its translation.
                            Text(LocalizedStringKey(label)).gmFont(14).foregroundColor(GM.primarySolid)
                                .padding(.vertical, 6)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("gm-support-\(label)")
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
                // Wide windows (iPad, Stage Manager, Mac): a centred form column.
                .gmMaxWidth(GMLayout.form)
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


private struct MailNotificationPreviewSetting: View {
    @AppStorage("mailGenericPreviews") private var hidePreviews = false

    var body: some View {
        Toggle("Hide mail notification previews", isOn: $hidePreviews)
            .onChange(of: hidePreviews) { _ in
                NotificationRegistration.shared.refreshPreferences()
            }
    }
}
