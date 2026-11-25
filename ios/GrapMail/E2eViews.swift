import SwiftUI

/// The encryption sector — the mobile form of the web's
/// `/mail/account/encryption`, split into the screens a phone needs: status,
/// setup, unlock, key details, publishing, passphrase change, removal, and the
/// recipient-key lookup compose asks for. Swift mirror of Android's
/// `E2eScreens.kt`; the wording is deliberately identical on both platforms,
/// because two platforms naming one thing differently is how a screen stops
/// being the same screen — and the walk markers match both.
///
/// The wording is the web page's, nearly line for line, including the parts it
/// REFUSES to promise: there is no passphrase recovery and the page says so
/// before you set one, not after you have lost it; removing the key makes every
/// message sealed to it unreadable, permanently and from everywhere, and the
/// screen says that before the destructive tap. A phone screen that softened
/// either line would be a phone screen that lies under pressure.
///
/// Layout: every screen here keeps a form-width column (`GMLayout.form`),
/// centred on wide windows, including its loading and error states; the
/// offline banner is MailScaffold's own, never a second copy.

// ── shared pieces ────────────────────────────────────────────────────────────

struct E2eCard<Content: View>: View {
    let title: String?
    let marker: String?
    @ViewBuilder var content: Content

    init(_ title: String? = nil, marker: String? = nil,
         @ViewBuilder content: () -> Content) {
        self.title = title
        self.marker = marker
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let title {
                Text(title).gmFont(16, .semibold)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, 4)
            }
            content
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous)
            .fill(GM.card).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(GM.border, lineWidth: 1)))
        .modifier(E2eMarker(id: marker))
    }
}

/// testTag has no SwiftUI equivalent, so the identifiers ride on
/// `accessibilityIdentifier` — the same mechanism every mail marker in this
/// app already uses (`gm-sheets-*`, `gm-signed-body`'s Android twin).
private struct E2eMarker: ViewModifier {
    let id: String?
    func body(content: Content) -> some View {
        if let id { content.accessibilityIdentifier(id) } else { content }
    }
}

private struct E2eNote: View {
    let text: String
    let bad: Bool
    var body: some View {
        Text(text)
            .gmFont(13).lineSpacing(5)
            .foregroundColor(bad ? GM.error : GM.textPrimary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16).padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(bad ? GM.warnChipBg : GM.softFill))
            .accessibilityIdentifier("gm-e2e-note")
    }
}

private struct E2eRow: View {
    @Environment(\.dynamicTypeSize) private var dts
    let label: String
    let value: String
    var mono = true
    /// The label column grows with its text (150 pt at the default size), so
    /// a translated label is not squeezed at the larger non-accessibility
    /// sizes; when label + value no longer fit, the row stacks (below).
    @ScaledMetric(relativeTo: .footnote) private var labelColumn: CGFloat = 150
    var body: some View {
        Group {
            if dts.isAccessibilitySize {
                stacked
            } else if mono {
                // A fingerprint is pre-broken into whole lines of byte pairs;
                // when a line no longer fits beside the label (large text,
                // narrow Split View), the value goes under the label instead
                // of wrapping mid-pair.
                ViewThatFits(in: .horizontal) {
                    sideBySide(fixValue: true)
                    stacked
                }
            } else {
                sideBySide(fixValue: false)
            }
        }
        .padding(.vertical, 7)
    }

    private var labelText: some View {
        Text(label).gmFont(13).foregroundColor(GM.textSecondary)
    }

    private var valueText: some View {
        Text(value).gmFont(13, design: mono ? .monospaced : .default)
            .foregroundColor(GM.textPrimary)
    }

    private func sideBySide(fixValue: Bool) -> some View {
        HStack(alignment: .top) {
            labelText
                .fixedSize(horizontal: false, vertical: true)
                .frame(width: labelColumn, alignment: .leading)
            Group {
                if fixValue {
                    valueText.fixedSize()
                } else {
                    valueText.fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var stacked: some View {
        VStack(alignment: .leading, spacing: 4) {
            labelText.fixedSize(horizontal: false, vertical: true)
            if mono {
                // Keeps each line of byte pairs whole at the largest sizes;
                // the row scrolls sideways rather than breaking a pair.
                ScrollView(.horizontal, showsIndicators: false) {
                    valueText.fixedSize()
                }
            } else {
                valueText.fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct E2eButton: View {
    let text: String
    let enabled: Bool
    var quiet = false
    /// false for buttons that sit in a Row beside a weighted field: a
    /// maxWidth .infinity child claims the whole row before the field is
    /// measured, and the recipients screen's address field was being
    /// squeezed to zero width by its own Check button.
    var fill = true
    /// Red for the irreversible (Material/HIG): deleting the private key is
    /// not the same kind of press as "Lock now" and must not wear its face.
    var destructive = false
    var marker: String? = nil
    let onClick: () -> Void

    var body: some View {
        Text(text)
            .gmFont(14, .semibold)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .foregroundColor(destructive ? (enabled ? .white : GM.textTertiary)
                           : quiet ? (enabled ? GM.textPrimary : GM.textTertiary)
                           : .white)
            .frame(maxWidth: fill ? .infinity : nil)
            .padding(.vertical, 13)
            .padding(.horizontal, fill ? 0 : 22)
            .background(RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(destructive ? (enabled ? GM.error : GM.buttonDisabled)
                                  : quiet ? GM.card : (enabled ? GM.primarySolid : GM.buttonDisabled))
                .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .strokeBorder(destructive ? GM.error : quiet ? GM.border : GM.primarySolid,
                                  lineWidth: 1)))
            .contentShape(Rectangle())
            .onTapGesture { if enabled { onClick() } }
            // No shared fallback identifier: two buttons answering to one name
            // is how a walk marker stops meaning anything. Every caller names
            // its own; Android's `tag =` on the same buttons is the twin.
            .modifier(E2eMarker(id: marker))
    }
}

/// The fingerprint eight byte-groups to a line, so a wrap never lands
/// mid-pair ("...C:05:" over "AA:..." read as a rendering fault on three
/// screens). Android's `fingerprintBlock` is the twin.
func e2eFingerprintBlock(_ fp: String) -> String {
    let groups = fp.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
    guard groups.count > 8 else { return fp }
    return stride(from: 0, to: groups.count, by: 8).map {
        groups[$0..<min($0 + 8, groups.count)].joined(separator: ":")
    }.joined(separator: "\n")
}

private struct E2eLede: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text)
            .gmFont(13).lineSpacing(5)
            .foregroundColor(GM.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 6)
    }
}

// ── 100 · status ─────────────────────────────────────────────────────────────

/// The hub. The three states the web keeps apart are stated here in one
/// sentence each, and every card below is gated on the state it needs — a
/// screen that offered an action against a locked key would offer a failure.
struct E2eStatusScreen: View {
    @ObservedObject var vm: E2eModel
    let onBack: () -> Void
    let onSetup: () -> Void
    let onUnlock: () -> Void
    let onDetails: () -> Void
    let onPublish: () -> Void
    let onChangePassphrase: () -> Void
    let onRemove: () -> Void
    let onRecipients: () -> Void
    @State private var showRestore = false
    @State private var confirmRemoveKit = false

    var body: some View {
        MailScaffold(title: String(localized: "Encryption", comment: "Screen title (end-to-end encryption status screen)"), onBack: onBack) {
            GeometryReader { g in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer(minLength: 0)
                    switch vm.state.available {
                    case nil:
                        Text("Checking this device…")
                            .gmFont(14).foregroundColor(GM.textTertiary)
                            .padding(.vertical, 16)
                            .accessibilityIdentifier("gm-e2e-probing")
                    case false:
                        E2eCard(String(localized: "Encryption is not available on this device", comment: "Card title (end-to-end encryption status screen)")) {
                            HStack(alignment: .top, spacing: 12) {
                                Image(systemName: "exclamationmark.triangle")
                                    .gmFont(16).foregroundColor(GM.error)
                                VStack(alignment: .leading, spacing: 0) {
                                    E2eLede(vm.state.unavailableReason ?? "")
                                    E2eLede(String(localized: "Nothing is wrong with your account. Your mail is unaffected; it simply cannot be encrypted from here.", comment: "Explanatory paragraph (end-to-end encryption status screen)"))
                                }
                            }
                        }
                    default:
                        if let note = vm.state.note {
                            E2eNote(text: note, bad: vm.state.noteIsBad)
                            Spacer().frame(height: 10)
                        }
                        // The state card: one sentence, and the sentence is true.
                        // The sentences come from functions, not local `let`s: a
                        // ViewBuilder body takes expressions, and variable
                        // declarations are not statements it accepts (CI).
                        E2eCard(Self.headline(for: vm.state.state3),
                                marker: "gm-e2e-state-\(vm.state.state3)") {
                            HStack(alignment: .top, spacing: 12) {
                                Image(systemName: vm.state.state3 == "unlocked"
                                      ? "checkmark.shield" : "lock")
                                    .gmFont(16)
                                    .foregroundColor(vm.state.state3 == "unlocked"
                                                     ? GM.primarySolid : GM.textSecondary)
                                VStack(alignment: .leading, spacing: 0) { E2eLede(Self.lede(for: vm.state.state3)) }
                            }
                            Spacer().frame(height: 12)
                            switch vm.state.state3 {
                            case "none":
                                E2eButton(text: String(localized: "Create my key", comment: "Button title (end-to-end encryption status screen)"), enabled: !vm.state.busy,
                                          marker: "gm-e2e-setup-open", onClick: onSetup)
                            case "locked":
                                E2eButton(text: String(localized: "Unlock", comment: "Button title (end-to-end encryption status screen)"), enabled: !vm.state.busy,
                                          marker: "gm-e2e-unlock-open", onClick: onUnlock)
                            default:
                                E2eButton(text: String(localized: "Lock now", comment: "Button title (end-to-end encryption status screen)"), enabled: true, quiet: true,
                                          marker: "gm-e2e-lock", onClick: { vm.lock() })
                            }
                        }
                        if vm.state.record == nil, vm.state.recoveryKitExists == true {
                            Spacer().frame(height: 14)
                            E2eCard(String(localized: "Restore your key from your recovery key", comment: "Card title (end-to-end encryption status screen)"), marker: "gm-e2e-restore-card") {
                                E2eLede(String(localized: "A recovery kit for this account is stored, locked with your recovery key. Enter it to put your key back on this device and read your encrypted mail again.", comment: "Explanatory paragraph (end-to-end encryption status screen)"))
                                Spacer().frame(height: 12)
                                E2eButton(text: String(localized: "Use my recovery key", comment: "Button title (end-to-end encryption status screen)"), enabled: !vm.state.busy,
                                          marker: "gm-e2e-restore-open", onClick: { showRestore = true })
                            }
                        }
                        if vm.state.record != nil {
                            Spacer().frame(height: 14)
                            E2eCard(String(localized: "Recovery key", comment: "Card title (end-to-end encryption status screen)"), marker: "gm-e2e-recovery-card") {
                                E2eLede(Self.recoveryLine(exists: vm.state.recoveryKitExists,
                                                          createdAt: vm.state.recoveryKitCreatedAt))
                                Spacer().frame(height: 12)
                                if vm.state.recoveryKitExists != nil {
                                    if vm.state.unlocked {
                                        E2eButton(text: vm.state.recoveryKitExists == true ? String(localized: "Replace the recovery key", comment: "Button title (end-to-end encryption status screen)")
                                                                                          : String(localized: "Create a recovery key", comment: "Button title (end-to-end encryption status screen)"),
                                                  enabled: !vm.state.busy, quiet: vm.state.recoveryKitExists == true,
                                                  marker: "gm-e2e-recovery-create", onClick: { vm.createRecoveryKey() })
                                    } else {
                                        E2eLede(vm.state.recoveryKitExists == true
                                                ? String(localized: "Unlock your key to replace the recovery key.", comment: "End-to-end encryption recovery key card")
                                                : String(localized: "Unlock your key to create the recovery key.", comment: "End-to-end encryption recovery key card"))
                                    }
                                }
                                if vm.state.recoveryKitExists == true {
                                    Spacer().frame(height: 8)
                                    E2eButton(text: String(localized: "Remove the recovery key", comment: "Button title (end-to-end encryption status screen)"), enabled: !vm.state.busy, quiet: true,
                                              marker: "gm-e2e-recovery-remove", onClick: { confirmRemoveKit = true })
                                }
                            }
                            Spacer().frame(height: 14)
                            E2eRow(label: String(localized: "Fingerprint", comment: "Key detail row label (end-to-end encryption status screen)"), value: e2eFingerprintBlock(vm.state.fingerprint ?? "-"))
                            Spacer().frame(height: 8)
                            E2eButton(text: String(localized: "Key details", comment: "Button title (end-to-end encryption status screen)"), enabled: true, quiet: true,
                                      marker: "gm-e2e-details-open", onClick: onDetails)
                            Spacer().frame(height: 8)
                            E2eButton(text: String(localized: "Who can find your key", comment: "Button title (end-to-end encryption status screen)"), enabled: true, quiet: true,
                                      marker: "gm-e2e-publish-open", onClick: onPublish)
                            if vm.state.unlocked {
                                Spacer().frame(height: 8)
                                E2eButton(text: String(localized: "Change passphrase", comment: "Button title (end-to-end encryption status screen)"), enabled: true, quiet: true,
                                          marker: "gm-e2e-change-open", onClick: onChangePassphrase)
                            }
                            Spacer().frame(height: 8)
                            E2eButton(text: String(localized: "Recipient keys", comment: "Button title (end-to-end encryption status screen)"), enabled: true, quiet: true,
                                      marker: "gm-e2e-recipients-open", onClick: onRecipients)
                            Spacer().frame(height: 8)
                            E2eButton(text: String(localized: "Remove the key from this device", comment: "Button title (end-to-end encryption status screen)"), enabled: true,
                                      quiet: true, marker: "gm-e2e-remove-open", onClick: onRemove)
                        }
                    }
                    Spacer().frame(height: 24)
                    Spacer(minLength: 0)
                }
                .frame(minHeight: g.size.height)
                .padding(.horizontal, 20)
                // Wide windows (iPad, Mac, Stage Manager): a column, not a
                // banner. Wider than any phone, so phones are unchanged.
                .gmMaxWidth(GMLayout.form)
            }
            }
        }
        .task { vm.load() }
        .sheet(isPresented: $showRestore) { E2eRestoreSheet(vm: vm) }
        .alert("Your recovery key", isPresented: Binding(
            get: { vm.state.newRecoveryKey != nil },
            set: { if !$0 { vm.dismissRecoveryKey() } })) {
            Button("Copy") {
                UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: vm.state.newRecoveryKey ?? ""]],
                                              options: [.expirationDate: Date().addingTimeInterval(120)])
                vm.dismissRecoveryKey()
            }
            Button("I have saved it", role: .cancel) { vm.dismissRecoveryKey() }
        } message: {
            Text("\(vm.state.newRecoveryKey ?? "")\n\nWrite it down or keep it in a password manager. GrapMail cannot show it again, and without it a lost device means lost encrypted mail. A copied key leaves the clipboard after two minutes.")
        }
        .alert("Remove the recovery key?", isPresented: $confirmRemoveKit) {
            Button("Remove", role: .destructive) { vm.removeRecoveryKit() }
            Button("Keep it", role: .cancel) {}
        } message: {
            Text("If you then lose this device, your encrypted mail cannot be recovered anywhere.")
        }
    }

    static func recoveryLine(exists: Bool?, createdAt: String?) -> String {
        switch exists {
        case .none: return String(localized: "Checking whether a recovery key is set up…", comment: "End-to-end encryption recovery key status")
        case .some(true):
            // Two whole sentences rather than " on <date>" spliced into one, so
            // a translation can place the date where its grammar needs it.
            if let date = createdAt.flatMap({ ServerDate.parse($0) }) {
                let when = date.formatted(date: .abbreviated, time: .shortened)
                return String(localized: "Set up on \(when). If you lose this device, it restores your key on a new one.", comment: "End-to-end encryption recovery key status; the argument is a date and time")
            }
            return String(localized: "Set up. If you lose this device, it restores your key on a new one.", comment: "End-to-end encryption recovery key status when the date is unknown")
        case .some(false): return String(localized: "Not set up. If you lose this device, your encrypted mail cannot be recovered.", comment: "End-to-end encryption recovery key status")
        }
    }

    /// The state's one true sentence, and its one true lede. Functions for
    /// the same reason the card takes them: ViewBuilder has no room for local
    /// declarations, and the sentences must not drift from Android's.
    static func headline(for state3: String) -> String {
        switch state3 {
        case "unlocked": return String(localized: "Your key is unlocked for this session", comment: "End-to-end encryption status headline")
        case "locked": return String(localized: "Your key is locked", comment: "End-to-end encryption status headline")
        default: return String(localized: "Encryption is not set up on this device", comment: "End-to-end encryption status headline")
        }
    }

    static func lede(for state3: String) -> String {
        switch state3 {
        case "unlocked":
            return String(localized: "It is held in memory only and is never written down. Closing the app locks it.", comment: "End-to-end encryption status explanation")
        case "locked":
            return String(localized: "It locks again whenever the app is closed. Unlock it to send or read encrypted mail.", comment: "End-to-end encryption status explanation")
        default:
            return String(localized: "Without a key, mail to you cannot be encrypted and encrypted mail cannot be read here.", comment: "End-to-end encryption status explanation")
        }
    }
}

// ── 101 · setup ──────────────────────────────────────────────────────────────

/// Create the key. The no-recovery warning sits above the fields: a forgotten
/// passphrase is never recoverable. The key itself can be, with a recovery key
/// the person makes after setup (the server's kit cannot be opened without it).
struct E2eSetupScreen: View {
    /// The check disc grows with the sentence beside it (20 pt at the default
    /// size), capped so it never crowds that sentence at the largest sizes.
    @ScaledMetric(relativeTo: .body) private var check: CGFloat = 20
    @ObservedObject var vm: E2eModel
    let onBack: () -> Void
    @State private var passphrase = ""
    @State private var confirm = ""
    @State private var publishOnSetup = true

    private var checkSide: CGFloat { min(check, 36) }

    var body: some View {
        MailScaffold(title: String(localized: "Create your key", comment: "Screen title (end-to-end encryption setup screen)"), onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let note = vm.state.note {
                        E2eNote(text: note, bad: vm.state.noteIsBad)
                        Spacer().frame(height: 10)
                    }
                    E2eLede(String(localized: "The key is made here, on this device. The private half never leaves it: that is what makes this end-to-end rather than a promise. It is stored encrypted under a passphrase you choose.", comment: "Explanatory paragraph (end-to-end encryption setup screen)"))
                    Text("There is no way to recover a forgotten passphrase.")
                        .gmFont(13, .medium)
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 10)
                    E2eLede(String(localized: "Not by us, and not by you. After setup you can create a recovery key: it restores your key on a new device with a new passphrase. Without the passphrase or the recovery key, nobody can open your encrypted mail, us included.", comment: "Explanatory paragraph (end-to-end encryption setup screen)"))
                    Spacer().frame(height: 14)
                    AppTextField(title: String(localized: "Passphrase", comment: "Text field label (end-to-end encryption setup screen)"), value: $passphrase, isPassword: true)
                    Spacer().frame(height: 8)
                    AppTextField(title: String(localized: "Repeat passphrase", comment: "Text field label (end-to-end encryption setup screen)"), value: $confirm, isPassword: true)
                    HStack(alignment: .top, spacing: 12) {
                        ZStack {
                            Circle().strokeBorder(publishOnSetup ? GM.primarySolid : GM.border,
                                                  lineWidth: 1.5).frame(width: checkSide, height: checkSide)
                            if publishOnSetup {
                                Circle().fill(GM.primarySolid).frame(width: checkSide, height: checkSide)
                                Text("✓").gmFont(12, maxSize: 22).foregroundColor(.white)
                            }
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Publish the public half, so other people can send me encrypted mail.")
                                .gmFont(13).foregroundColor(GM.textPrimary)
                                .fixedSize(horizontal: false, vertical: true)
                            Text("This shares your public key. Your private key is not shared, and never leaves this device.")
                                .gmFont(12).foregroundColor(GM.textTertiary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer()
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { publishOnSetup.toggle() }
                    .padding(.top, 12)
                    Spacer().frame(height: 16)
                    E2eButton(text: vm.state.busy ? String(localized: "Creating…", comment: "Button title (end-to-end encryption setup screen)") : String(localized: "Create my key", comment: "Button title (end-to-end encryption setup screen)"),
                              enabled: !vm.state.busy, marker: "gm-e2e-setup-submit",
                              onClick: { vm.setup(passphrase: passphrase, confirm: confirm,
                                                  publishOnSetup: publishOnSetup) })
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
                .gmMaxWidth(GMLayout.form)
            }
            .scrollDismissesKeyboard(.interactively)
        }
    }
}

// ── 102 · unlock ─────────────────────────────────────────────────────────────

struct E2eUnlockScreen: View {
    @ObservedObject var vm: E2eModel
    let onBack: () -> Void
    @State private var phrase = ""

    var body: some View {
        MailScaffold(title: String(localized: "Unlock your key", comment: "Screen title (end-to-end encryption unlock screen)"), onBack: onBack) {
            // Scrolls in a short window (landscape, keyboard up, large text)
            // so the Unlock button is always reachable; top-anchored as before.
            ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if let note = vm.state.note {
                    E2eNote(text: note, bad: vm.state.noteIsBad)
                    Spacer().frame(height: 10)
                }
                E2eLede(String(localized: "Your passphrase decrypts the key on this device. It is never sent anywhere.", comment: "Explanatory paragraph (end-to-end encryption unlock screen)"))
                Spacer().frame(height: 14)
                AppTextField(title: String(localized: "Passphrase", comment: "Text field label (end-to-end encryption unlock screen)"), value: $phrase, isPassword: true)
                Spacer().frame(height: 16)
                E2eButton(text: vm.state.busy ? String(localized: "Unlocking…", comment: "Button title (end-to-end encryption unlock screen)") : String(localized: "Unlock", comment: "Button title (end-to-end encryption unlock screen)"),
                          enabled: !vm.state.busy && !phrase.isEmpty,
                          marker: "gm-e2e-unlock-submit",
                          onClick: { vm.unlock(passphrase: phrase); phrase = "" })
                if vm.canBiometricUnlock {
                    Spacer().frame(height: 10)
                    E2eButton(text: String(localized: "Unlock with Face ID or Touch ID", comment: "Button title (end-to-end encryption unlock screen)"),
                              enabled: !vm.state.busy,
                              marker: "gm-e2e-unlock-biometric",
                              onClick: { vm.unlockWithBiometrics() })
                }
                Spacer().frame(height: 24)
            }
            .padding(.horizontal, 20)
            .gmMaxWidth(GMLayout.form)
            }
            .scrollDismissesKeyboard(.interactively)
        }
    }
}

// ── 103 · key details ────────────────────────────────────────────────────────

struct E2eDetailsScreen: View {
    @ObservedObject var vm: E2eModel
    let onBack: () -> Void

    var body: some View {
        MailScaffold(title: String(localized: "This device's key", comment: "Screen title (end-to-end encryption details screen)"), onBack: onBack) {
            // Top-anchored like every other detail screen; the flexible
            // Spacers this drops centered the record and left a dead band
            // under the toolbar -- frame 104, the mirror of Android's fix.
            // In a short window the record scrolls instead of being clipped.
            GeometryReader { g in
            ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                E2eLede(String(localized: "Read the fingerprint aloud, or send it through another channel, and have the other person confirm it. If the two do not match, the key you have is not theirs.", comment: "Explanatory paragraph (end-to-end encryption details screen)"))
                Spacer().frame(height: 10)
                E2eRow(label: String(localized: "Fingerprint", comment: "Key detail row label (end-to-end encryption details screen)"), value: e2eFingerprintBlock(vm.state.fingerprint ?? "-"))
                E2eRow(label: String(localized: "Created", comment: "Key detail row label (end-to-end encryption details screen)"), value: MailTime.full(vm.state.record?.createdAt) ?? "-")
                E2eRow(label: String(localized: "Signing key", comment: "Key detail row label (end-to-end encryption details screen)"),
                       value: vm.state.record?.signing != nil
                              ? String(localized: "Yes: recipients can verify it was you", comment: "Key detail row value (end-to-end encryption details screen)") : String(localized: "None", comment: "Key detail row value (end-to-end encryption details screen)"),
                       mono: false)
                Spacer().frame(height: 12)
                Text(vm.openpgpExportNote)
                    .gmFont(12).lineSpacing(4)
                    .foregroundColor(GM.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 20)
            .gmMaxWidth(GMLayout.form)
            .frame(minHeight: g.size.height)
            }
            }
        }
    }
}

// ── 104 · publishing ─────────────────────────────────────────────────────────

struct E2ePublishScreen: View {
    @ObservedObject var vm: E2eModel
    let onBack: () -> Void

    var body: some View {
        MailScaffold(title: String(localized: "Who can find your key", comment: "Screen title (end-to-end encryption publish screen)"), onBack: onBack) {
            // No GMOfflineBanner here: MailScaffold already draws it above
            // every screen it hosts, and a second one stacked two banners.
            //
            // First read and a failed read with nothing held use the shared
            // states; the failure keeps the server's sentence and adds Retry.
            // Both keep the screen's form column on a wide window.
            if !vm.state.publishedLoaded {
                GMSkeletonBlock(lines: 4).padding(.horizontal, 20)
                    .gmMaxWidth(GMLayout.form)
                    .accessibilityIdentifier("gm-e2e-publish-checking")
                Spacer(minLength: 0)
            } else if let error = vm.state.publishedError, vm.state.published == nil {
                GMErrorState(message: error) { vm.loadPublished() }
                    .gmMaxWidth(GMLayout.form)
            } else {
            GeometryReader { g in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer(minLength: 0)
                    if let note = vm.state.note {
                        E2eNote(text: note, bad: vm.state.noteIsBad)
                        Spacer().frame(height: 10)
                    }
                    if !vm.state.publishedLoaded {
                        Text("Checking…").gmFont(13)
                            .foregroundColor(GM.textTertiary)
                            .padding(.vertical, 10)
                            .accessibilityIdentifier("gm-e2e-publish-checking")
                    } else if let error = vm.state.publishedError {
                        // The failed read says so. "Nothing is published" would be a
                        // claim about the server that the server never made.
                        E2eNote(text: error, bad: true)
                    } else if let p = vm.state.published {
                        E2eLede(String(localized: "Your public key is published, so anyone sending you mail can encrypt to it. Your private key is not published and never will be.", comment: "Explanatory paragraph (end-to-end encryption publish screen)"))
                        Spacer().frame(height: 8)
                        E2eRow(label: String(localized: "Published fingerprint", comment: "Key detail row label (end-to-end encryption publish screen)"), value: e2eFingerprintBlock(p.encryption?.fingerprint ?? "-"))
                        E2eRow(label: String(localized: "Updated", comment: "Key detail row label (end-to-end encryption publish screen)"), value: MailTime.full(p.updatedAt ?? p.publishedAt) ?? "-")
                        // The condition is a function call, not a local `let`:
                        // a ViewBuilder branch takes expressions, and the
                        // status screen already learned that from CI. The
                        // mismatch itself is the web's warning — two
                        // different keys for one account is a fact the person
                        // must see before trusting either.
                        if Self.isMismatch(local: vm.state.fingerprint, published: p.encryption?.fingerprint) {
                            Spacer().frame(height: 10)
                            E2eNote(text: String(localized: "The key published for your account is NOT the key on this device. Anyone encrypting to you right now is encrypting to a key this device cannot open. If you did not expect this — for example if you set encryption up on another device — publish from this one, or restore that device's key.", comment: "Warning note (end-to-end encryption publish screen)"), bad: true)
                        }
                        Spacer().frame(height: 14)
                        E2eButton(text: String(localized: "Unpublish", comment: "Button title (end-to-end encryption publish screen)"), enabled: !vm.state.busy, quiet: true,
                                  marker: "gm-e2e-unpublish", onClick: { vm.unpublish() })
                    } else {
                        E2eLede(String(localized: "Nothing is published for this account, so senders cannot encrypt to you yet. Publishing shares only the public half.", comment: "Explanatory paragraph (end-to-end encryption publish screen)"))
                        Spacer().frame(height: 14)
                        E2eButton(text: vm.state.busy ? String(localized: "Publishing…", comment: "Button title (end-to-end encryption publish screen)") : String(localized: "Publish my public key", comment: "Button title (end-to-end encryption publish screen)"),
                                  enabled: !vm.state.busy && vm.state.record != nil,
                                  marker: "gm-e2e-publish-submit", onClick: { vm.publish() })
                        if vm.state.record == nil {
                            Spacer().frame(height: 8)
                            Text("A key on this device comes first — create one from the Encryption screen.")
                                .gmFont(12).foregroundColor(GM.textTertiary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    Spacer().frame(height: 24)
                    Spacer(minLength: 0)
                }
                .frame(minHeight: g.size.height)
                .padding(.horizontal, 20)
                .gmMaxWidth(GMLayout.form)
            }
            }
            }
        }
        .task { vm.loadPublished() }
    }

    /// Two known fingerprints that differ is the mismatch; a missing one on
    /// either side is an unanswered question, not a difference — the warning
    /// must not fire on an absent read.
    static func isMismatch(local: String?, published: String?) -> Bool {
        guard let local, let published else { return false }
        return local != published
    }
}

// ── 105 · passphrase change ──────────────────────────────────────────────────

struct E2ePassphraseScreen: View {
    @ObservedObject var vm: E2eModel
    let onBack: () -> Void
    @State private var oldPhrase = ""
    @State private var newPhrase = ""

    var body: some View {
        MailScaffold(title: String(localized: "Change your passphrase", comment: "Screen title (end-to-end encryption passphrase screen)"), onBack: onBack) {
            // Scrolls so the button stays reachable with the keyboard up and in
            // landscape; top-anchored as before.
            ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if let note = vm.state.note {
                    E2eNote(text: note, bad: vm.state.noteIsBad)
                    Spacer().frame(height: 10)
                }
                E2eLede(String(localized: "This re-locks the same key under a new passphrase. It does not change the key, so messages already encrypted to you stay readable.", comment: "Explanatory paragraph (end-to-end encryption passphrase screen)"))
                Spacer().frame(height: 14)
                AppTextField(title: String(localized: "Current passphrase", comment: "Text field label (end-to-end encryption passphrase screen)"), value: $oldPhrase, isPassword: true)
                Spacer().frame(height: 8)
                AppTextField(title: String(localized: "New passphrase", comment: "Text field label (end-to-end encryption passphrase screen)"), value: $newPhrase, isPassword: true)
                Spacer().frame(height: 16)
                E2eButton(text: vm.state.busy ? String(localized: "Changing…", comment: "Button title (end-to-end encryption passphrase screen)") : String(localized: "Change passphrase", comment: "Button title (end-to-end encryption passphrase screen)"),
                          enabled: !vm.state.busy && vm.state.unlocked,
                          marker: "gm-e2e-change-submit",
                          onClick: { vm.changePassphrase(oldPhrase: oldPhrase, newPhrase: newPhrase)
                                     oldPhrase = ""; newPhrase = "" })
                if !vm.state.unlocked {
                    Spacer().frame(height: 8)
                    Text("Unlocking the key comes first — the re-wrap needs the old passphrase to open it.")
                        .gmFont(12).foregroundColor(GM.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer().frame(height: 24)
            }
            .padding(.horizontal, 20)
            .gmMaxWidth(GMLayout.form)
            }
            .scrollDismissesKeyboard(.interactively)
        }
    }
}

// ── 106 · removal ────────────────────────────────────────────────────────────

struct E2eRemoveScreen: View {
    @ObservedObject var vm: E2eModel
    let onBack: () -> Void
    @State private var confirmRemove = false

    var body: some View {
        MailScaffold(title: String(localized: "Remove the key from this device", comment: "Screen title (end-to-end encryption remove screen)"), onBack: onBack) {
            // Centred when it fits, as before; scrolls in a short window.
            GeometryReader { g in
            ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if let note = vm.state.note {
                    E2eNote(text: note, bad: vm.state.noteIsBad)
                    Spacer().frame(height: 10)
                }
                // The warning is the consequence, stated before the tap, in the
                // web's own words. Not a scare line: permanent and everywhere.
                E2eLede(String(localized: "This deletes the private key here. Every message already encrypted to it becomes unreadable — here and anywhere else, permanently. Do this only if you have the key somewhere else, or you accept losing access to that mail.", comment: "Explanatory paragraph (end-to-end encryption remove screen)"))
                Spacer().frame(height: 16)
                if !confirmRemove {
                    E2eButton(text: String(localized: "Remove from this device", comment: "Button title (end-to-end encryption remove screen)"), enabled: vm.state.record != nil,
                              quiet: true, marker: "gm-e2e-remove-start",
                              onClick: { confirmRemove = true })
                } else {
                    E2eButton(text: vm.state.busy ? String(localized: "Removing…", comment: "Button title (end-to-end encryption remove screen)") : String(localized: "Yes, delete the key from this device", comment: "Button title (end-to-end encryption remove screen)"),
                              enabled: !vm.state.busy, destructive: true, marker: "gm-e2e-remove-confirm",
                              onClick: { vm.removeKey(); confirmRemove = false; onBack() })
                    Spacer().frame(height: 8)
                    E2eButton(text: String(localized: "Keep it", comment: "Button title (end-to-end encryption remove screen)"), enabled: true, quiet: true,
                              marker: "gm-e2e-remove-keep",
                              onClick: { confirmRemove = false })
                }
                // No trailing flexible Spacer: it sank the buttons to the
                // screen bottom, far from the warning that justifies them.
                Spacer().frame(height: 24)
            }
            .padding(.horizontal, 20)
            .gmMaxWidth(GMLayout.form)
            .frame(minHeight: g.size.height)
            }
            }
        }
    }
}

// ── 107 · recipient keys ─────────────────────────────────────────────────────

/// The compose-time verification: what the directory publishes for these
/// addresses. Each entry's reason is the server's own word — "this person has
/// not set up encryption" and "this is not a GrapMail address" lead to
/// different actions, and the list says which is which instead of a blanket
/// "unavailable".
struct E2eRecipientsScreen: View {
    @Environment(\.dynamicTypeSize) private var dts
    @ObservedObject var vm: E2eModel
    let onBack: () -> Void
    let onCompose: () -> Void
    @State private var draft = ""

    var body: some View {
        MailScaffold(title: String(localized: "Recipient keys", comment: "Screen title (end-to-end encryption recipients screen)"), onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    E2eLede(String(localized: "Check that the people you are about to write to have published keys. A found entry shows the fingerprint to compare; an unavailable one says why, in the directory's own words.", comment: "Explanatory paragraph (end-to-end encryption recipients screen)"))
                    Spacer().frame(height: 8)
                    GMAdaptiveStack(spacing: 10) {
                        AppTextField(value: $draft, hint: "someone@example.com")
                            .frame(maxWidth: .infinity)
                        E2eButton(text: String(localized: "Check", comment: "Button title (end-to-end encryption recipients screen)"),
                                  enabled: !vm.state.busy && !draft.trimmed.isEmpty,
                                  fill: false, marker: "gm-e2e-lookup-submit",
                                  onClick: { vm.lookup(addresses: [draft.trimmed]); draft = "" })
                            .fixedSize()
                    }
                    if let note = vm.state.note {
                        Spacer().frame(height: 8)
                        E2eNote(text: note, bad: vm.state.noteIsBad)
                    }
                    if !vm.state.lookup.isEmpty {
                        Spacer().frame(height: 10)
                        ForEach(vm.state.lookup, id: \.address) { entry in
                            lookupRow(entry)
                        }
                        Spacer().frame(height: 10)
                        E2eButton(text: String(localized: "Back to compose", comment: "Button title (end-to-end encryption recipients screen)"), enabled: true, quiet: true,
                                  marker: "gm-e2e-lookup-compose", onClick: onCompose)
                    } else if !vm.state.lookupFor.isEmpty && !vm.state.busy {
                        Text("The directory answered nothing for that check.")
                            .gmFont(13).foregroundColor(GM.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.vertical, 10)
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
                .gmMaxWidth(GMLayout.form)
            }
            .scrollDismissesKeyboard(.interactively)
        }
    }

    @ViewBuilder private func lookupRow(_ e: MailE2eLookupEntry) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            GMAdaptiveStack(spacing: dts.isAccessibilitySize ? 4 : nil) {
                Text(e.address).gmFont(14, .medium)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                (e.status == "found" ? Text("Found") : Text("Cannot encrypt"))
                    .gmFont(12, .semibold)
                    .foregroundColor(e.status == "found" ? GM.success : GM.warnInk)
            }
            if e.status == "found", e.signing != nil {
                Text("Signing fingerprint \(e2eFingerprintBlock(e.signing?.fingerprint ?? "-"))")
                    .gmFont(12, design: .monospaced)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            } else if e.status != "found" {
                Text(why(e)).gmFont(12).foregroundColor(GM.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 10)
        .accessibilityIdentifier("gm-e2e-lookup-row")
    }

    /// The sentence per refusal, in the directory's own vocabulary. Two
    /// different reasons must not collapse into one line: what the person
    /// does next differs.
    private func why(_ e: MailE2eLookupEntry) -> String {
        switch e.why {
        case .notAnAddress: return String(localized: "That is not an address the directory can look up.", comment: "Why a recipient's encryption key could not be found")
        case .remoteDomain: return String(localized: "Not a GrapMail address — the directory only knows GrapMail keys.", comment: "Why a recipient's encryption key could not be found")
        case .noSuchAccount: return String(localized: "There is no GrapMail account at that address.", comment: "Why a recipient's encryption key could not be found")
        case .noKeyPublished: return String(localized: "This person has not published an encryption key yet.", comment: "Why a recipient's encryption key could not be found")
        case .unknown: return e.reason ?? String(localized: "The directory gave no reason.", comment: "Why a recipient's encryption key could not be found")
        }
    }
}

// ── recovery key: restore ────────────────────────────────────────────────────

/// Restore the key from the stored kit: the recovery key, then a new passphrase for this device.
///
/// Three fields and a button: always the full-height sheet (a half sheet would
/// put the button under the keyboard), scrolling, with the form column kept on
/// a wide sheet (Mac, Vision Pro, large Stage Manager windows).
struct E2eRestoreSheet: View {
    @ObservedObject var vm: E2eModel
    @Environment(\.dismiss) private var dismiss
    @State private var recoveryKey = ""
    @State private var passphrase = ""
    @State private var confirm = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let note = vm.state.note {
                        E2eNote(text: note, bad: vm.state.noteIsBad)
                        Spacer().frame(height: 10)
                    }
                    E2eLede(String(localized: "Enter the recovery key you saved when you set it up. It has 28 letters and numbers; dashes and spaces do not matter.", comment: "Explanatory paragraph (end-to-end encryption restore sheet)"))
                    Spacer().frame(height: 12)
                    AppTextField(title: String(localized: "Recovery key", comment: "Text field label (end-to-end encryption restore sheet)"), value: $recoveryKey)
                    Spacer().frame(height: 14)
                    E2eLede(String(localized: "Then choose a passphrase for this device. It protects the key here, like the one you set the first time.", comment: "Explanatory paragraph (end-to-end encryption restore sheet)"))
                    Spacer().frame(height: 8)
                    AppTextField(title: String(localized: "New passphrase", comment: "Text field label (end-to-end encryption restore sheet)"), value: $passphrase, isPassword: true)
                    Spacer().frame(height: 8)
                    AppTextField(title: String(localized: "Repeat passphrase", comment: "Text field label (end-to-end encryption restore sheet)"), value: $confirm, isPassword: true)
                    Spacer().frame(height: 16)
                    E2eButton(text: vm.state.busy ? String(localized: "Restoring…", comment: "Button title (end-to-end encryption restore sheet)") : String(localized: "Restore my key", comment: "Button title (end-to-end encryption restore sheet)"),
                              enabled: !vm.state.busy && !recoveryKey.trimmingCharacters(in: .whitespaces).isEmpty
                                  && !passphrase.isEmpty,
                              marker: "gm-e2e-restore-submit",
                              onClick: { vm.restoreWithRecoveryKey(recoveryKey, passphrase: passphrase, confirm: confirm) })
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
                .gmMaxWidth(GMLayout.form)
            }
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("Restore your key")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .presentationDetents([.large])
        .onChange(of: vm.state.record != nil) { restored in if restored { dismiss() } }
    }
}
