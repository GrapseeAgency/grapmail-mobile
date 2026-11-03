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
                Text(title).font(.system(size: 16, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
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
            .font(.system(size: 13)).lineSpacing(5)
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
    let label: String
    let value: String
    var mono = true
    var body: some View {
        HStack(alignment: .top) {
            Text(label).font(.system(size: 13)).foregroundColor(GM.textSecondary)
                .frame(width: 150, alignment: .leading)
            Text(value).font(mono ? .system(size: 13, design: .monospaced)
                                  : .system(size: 13))
                .foregroundColor(GM.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 7)
    }
}

private struct E2eButton: View {
    let text: String
    let enabled: Bool
    var quiet = false
    var marker: String? = nil
    let onClick: () -> Void

    var body: some View {
        Text(text)
            .font(.system(size: 14, weight: .semibold))
            .foregroundColor(quiet ? (enabled ? GM.textPrimary : GM.textTertiary)
                                   : .white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 13)
            .background(RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(quiet ? GM.card : (enabled ? GM.primarySolid : GM.buttonDisabled))
                .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .strokeBorder(quiet ? GM.border : GM.primarySolid, lineWidth: 1)))
            .contentShape(Rectangle())
            .onTapGesture { if enabled { onClick() } }
            .accessibilityIdentifier(marker ?? "gm-e2e-button")
    }
}

private struct E2eLede: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text)
            .font(.system(size: 13)).lineSpacing(5)
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

    var body: some View {
        MailScaffold(title: "Encryption", onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer().frame(height: 8)
                    switch vm.state.available {
                    case nil:
                        Text("Checking this device…")
                            .font(.system(size: 14)).foregroundColor(GM.textTertiary)
                            .padding(.vertical, 16)
                            .accessibilityIdentifier("gm-e2e-probing")
                    case false:
                        E2eCard("Encryption is not available on this device") {
                            HStack(alignment: .top, spacing: 12) {
                                Image(systemName: "exclamationmark.triangle")
                                    .font(.system(size: 16)).foregroundColor(GM.error)
                                VStack(alignment: .leading, spacing: 0) {
                                    E2eLede(vm.state.unavailableReason ?? "")
                                    E2eLede("Nothing is wrong with your account. Your mail is unaffected; "
                                            + "it simply cannot be encrypted from here.")
                                }
                            }
                        }
                    default:
                        if let note = vm.state.note {
                            E2eNote(text: note, bad: vm.state.noteIsBad)
                            Spacer().frame(height: 10)
                        }
                        // The state card: one sentence, and the sentence is true.
                        let headline: String
                        let lede: String
                        switch vm.state.state3 {
                        case "unlocked":
                            headline = "Your key is unlocked for this session"
                            lede = "It is held in memory only and is never written down. "
                                 + "Closing the app locks it."
                        case "locked":
                            headline = "Your key is locked"
                            lede = "It locks again whenever the app is closed. Unlock it to send "
                                 + "or read encrypted mail."
                        default:
                            headline = "Encryption is not set up on this device"
                            lede = "Without a key, mail to you cannot be encrypted and encrypted mail "
                                 + "cannot be read here."
                        }
                        E2eCard(headline, marker: "gm-e2e-state-\(vm.state.state3)") {
                            HStack(alignment: .top, spacing: 12) {
                                Image(systemName: vm.state.state3 == "unlocked"
                                      ? "checkmark.shield" : "lock")
                                    .font(.system(size: 16))
                                    .foregroundColor(vm.state.state3 == "unlocked"
                                                     ? GM.primarySolid : GM.textSecondary)
                                VStack(alignment: .leading, spacing: 0) { E2eLede(lede) }
                            }
                            Spacer().frame(height: 12)
                            switch vm.state.state3 {
                            case "none":
                                E2eButton(text: "Create my key", enabled: !vm.state.busy,
                                          marker: "gm-e2e-setup-open", onClick: onSetup)
                            case "locked":
                                E2eButton(text: "Unlock", enabled: !vm.state.busy,
                                          marker: "gm-e2e-unlock-open", onClick: onUnlock)
                            default:
                                E2eButton(text: "Lock now", enabled: true, quiet: true,
                                          marker: "gm-e2e-lock", onClick: { vm.lock() })
                            }
                        }
                        if vm.state.record != nil {
                            Spacer().frame(height: 14)
                            E2eRow(label: "Fingerprint", value: vm.state.fingerprint ?? "-")
                            Spacer().frame(height: 8)
                            E2eButton(text: "Key details", enabled: true, quiet: true,
                                      marker: "gm-e2e-details-open", onClick: onDetails)
                            Spacer().frame(height: 8)
                            E2eButton(text: "Who can find your key", enabled: true, quiet: true,
                                      marker: "gm-e2e-publish-open", onClick: onPublish)
                            if vm.state.unlocked {
                                Spacer().frame(height: 8)
                                E2eButton(text: "Change passphrase", enabled: true, quiet: true,
                                          marker: "gm-e2e-change-open", onClick: onChangePassphrase)
                            }
                            Spacer().frame(height: 8)
                            E2eButton(text: "Recipient keys", enabled: true, quiet: true,
                                      marker: "gm-e2e-recipients-open", onClick: onRecipients)
                            Spacer().frame(height: 8)
                            E2eButton(text: "Remove the key from this device", enabled: true,
                                      quiet: true, marker: "gm-e2e-remove-open", onClick: onRemove)
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

// ── 101 · setup ──────────────────────────────────────────────────────────────

/// Create the key. The no-recovery warning sits above the fields, in the
/// web's own words: a recovery route would mean a second copy of the key held
/// somewhere else, which is the one thing this feature exists to prevent.
struct E2eSetupScreen: View {
    @ObservedObject var vm: E2eModel
    let onBack: () -> Void
    @State private var passphrase = ""
    @State private var confirm = ""
    @State private var publishOnSetup = true

    var body: some View {
        MailScaffold(title: "Create your key", onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let note = vm.state.note {
                        E2eNote(text: note, bad: vm.state.noteIsBad)
                        Spacer().frame(height: 10)
                    }
                    E2eLede("The key is made here, on this device. The private half never leaves it: that is "
                            + "what makes this end-to-end rather than a promise. It is stored encrypted under a "
                            + "passphrase you choose.")
                    Text("There is no way to recover a forgotten passphrase.")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(GM.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 10)
                    E2eLede("Not by us, and not by you. A recovery route would mean a second copy of your "
                            + "key held somewhere else, which is the one thing this feature exists to prevent.")
                    Spacer().frame(height: 14)
                    AppTextField(title: "Passphrase", value: $passphrase, isPassword: true)
                    Spacer().frame(height: 8)
                    AppTextField(title: "Repeat passphrase", value: $confirm, isPassword: true)
                    HStack(alignment: .top, spacing: 12) {
                        ZStack {
                            Circle().strokeBorder(publishOnSetup ? GM.primarySolid : GM.border,
                                                  lineWidth: 1.5).frame(width: 20, height: 20)
                            if publishOnSetup {
                                Circle().fill(GM.primarySolid).frame(width: 20, height: 20)
                                Text("✓").font(.system(size: 12)).foregroundColor(.white)
                            }
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Publish the public half, so other people can send me encrypted mail.")
                                .font(.system(size: 13)).foregroundColor(GM.textPrimary)
                                .fixedSize(horizontal: false, vertical: true)
                            Text("This shares your public key. Your private key is not shared, and never "
                                 + "leaves this device.")
                                .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer()
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { publishOnSetup.toggle() }
                    .padding(.top, 12)
                    Spacer().frame(height: 16)
                    E2eButton(text: vm.state.busy ? "Creating…" : "Create my key",
                              enabled: !vm.state.busy, marker: "gm-e2e-setup-submit",
                              onClick: { vm.setup(passphrase: passphrase, confirm: confirm,
                                                  publishOnSetup: publishOnSetup) })
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
            }
        }
    }
}

// ── 102 · unlock ─────────────────────────────────────────────────────────────

struct E2eUnlockScreen: View {
    @ObservedObject var vm: E2eModel
    let onBack: () -> Void
    @State private var phrase = ""

    var body: some View {
        MailScaffold(title: "Unlock your key", onBack: onBack) {
            VStack(alignment: .leading, spacing: 0) {
                if let note = vm.state.note {
                    E2eNote(text: note, bad: vm.state.noteIsBad)
                    Spacer().frame(height: 10)
                }
                E2eLede("Your passphrase decrypts the key on this device. It is never sent anywhere.")
                Spacer().frame(height: 14)
                AppTextField(title: "Passphrase", value: $phrase, isPassword: true)
                Spacer().frame(height: 16)
                E2eButton(text: vm.state.busy ? "Unlocking…" : "Unlock",
                          enabled: !vm.state.busy && !phrase.isEmpty,
                          marker: "gm-e2e-unlock-submit",
                          onClick: { vm.unlock(passphrase: phrase); phrase = "" })
                Spacer()
            }
            .padding(.horizontal, 20)
        }
    }
}

// ── 103 · key details ────────────────────────────────────────────────────────

struct E2eDetailsScreen: View {
    @ObservedObject var vm: E2eModel
    let onBack: () -> Void

    var body: some View {
        MailScaffold(title: "This device's key", onBack: onBack) {
            VStack(alignment: .leading, spacing: 0) {
                E2eLede("Read the fingerprint aloud, or send it through another channel, and have the "
                        + "other person confirm it. If the two do not match, the key you have is not theirs.")
                Spacer().frame(height: 10)
                E2eRow(label: "Fingerprint", value: vm.state.fingerprint ?? "-")
                E2eRow(label: "Created", value: vm.state.record?.createdAt ?? "-")
                E2eRow(label: "Signing key",
                       value: vm.state.record?.signing != nil
                              ? "Yes: recipients can verify it was you" : "None",
                       mono: false)
                Spacer().frame(height: 12)
                Text(vm.openpgpExportNote)
                    .font(.system(size: 12)).lineSpacing(4)
                    .foregroundColor(GM.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer()
            }
            .padding(.horizontal, 20)
        }
    }
}

// ── 104 · publishing ─────────────────────────────────────────────────────────

struct E2ePublishScreen: View {
    @ObservedObject var vm: E2eModel
    let onBack: () -> Void

    var body: some View {
        MailScaffold(title: "Who can find your key", onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let note = vm.state.note {
                        E2eNote(text: note, bad: vm.state.noteIsBad)
                        Spacer().frame(height: 10)
                    }
                    if !vm.state.publishedLoaded {
                        Text("Checking…").font(.system(size: 13))
                            .foregroundColor(GM.textTertiary)
                            .padding(.vertical, 10)
                            .accessibilityIdentifier("gm-e2e-publish-checking")
                    } else if let error = vm.state.publishedError {
                        // The failed read says so. "Nothing is published" would be a
                        // claim about the server that the server never made.
                        E2eNote(text: error, bad: true)
                    } else if let p = vm.state.published {
                        E2eLede("Your public key is published, so anyone sending you mail can encrypt to "
                                + "it. Your private key is not published and never will be.")
                        Spacer().frame(height: 8)
                        E2eRow(label: "Published fingerprint", value: p.encryption?.fingerprint ?? "-")
                        E2eRow(label: "Updated", value: p.updatedAt ?? p.publishedAt ?? "-")
                        let mismatch = vm.state.fingerprint != nil
                            && p.encryption?.fingerprint != nil
                            && p.encryption?.fingerprint != vm.state.fingerprint
                        if mismatch {
                            Spacer().frame(height: 10)
                            E2eNote(text: "The key published for your account is NOT the key on this device. "
                                    + "Anyone encrypting to you right now is encrypting to a key this device "
                                    + "cannot open. If you did not expect this — for example if you set "
                                    + "encryption up on another device — publish from this one, or restore "
                                    + "that device's key.", bad: true)
                        }
                        Spacer().frame(height: 14)
                        E2eButton(text: "Unpublish", enabled: !vm.state.busy, quiet: true,
                                  marker: "gm-e2e-unpublish", onClick: { vm.unpublish() })
                    } else {
                        E2eLede("Nothing is published for this account, so senders cannot encrypt to "
                                + "you yet. Publishing shares only the public half.")
                        Spacer().frame(height: 14)
                        E2eButton(text: vm.state.busy ? "Publishing…" : "Publish my public key",
                                  enabled: !vm.state.busy && vm.state.record != nil,
                                  marker: "gm-e2e-publish-submit", onClick: { vm.publish() })
                        if vm.state.record == nil {
                            Spacer().frame(height: 8)
                            Text("A key on this device comes first — create one from the Encryption screen.")
                                .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                        }
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
            }
        }
        .task { vm.loadPublished() }
    }
}

// ── 105 · passphrase change ──────────────────────────────────────────────────

struct E2ePassphraseScreen: View {
    @ObservedObject var vm: E2eModel
    let onBack: () -> Void
    @State private var oldPhrase = ""
    @State private var newPhrase = ""

    var body: some View {
        MailScaffold(title: "Change your passphrase", onBack: onBack) {
            VStack(alignment: .leading, spacing: 0) {
                if let note = vm.state.note {
                    E2eNote(text: note, bad: vm.state.noteIsBad)
                    Spacer().frame(height: 10)
                }
                E2eLede("This re-locks the same key under a new passphrase. It does not change the key, "
                        + "so messages already encrypted to you stay readable.")
                Spacer().frame(height: 14)
                AppTextField(title: "Current passphrase", value: $oldPhrase, isPassword: true)
                Spacer().frame(height: 8)
                AppTextField(title: "New passphrase", value: $newPhrase, isPassword: true)
                Spacer().frame(height: 16)
                E2eButton(text: vm.state.busy ? "Changing…" : "Change passphrase",
                          enabled: !vm.state.busy && vm.state.unlocked,
                          marker: "gm-e2e-change-submit",
                          onClick: { vm.changePassphrase(oldPhrase: oldPhrase, newPhrase: newPhrase)
                                     oldPhrase = ""; newPhrase = "" })
                if !vm.state.unlocked {
                    Spacer().frame(height: 8)
                    Text("Unlocking the key comes first — the re-wrap needs the old passphrase to open it.")
                        .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
            }
            .padding(.horizontal, 20)
        }
    }
}

// ── 106 · removal ────────────────────────────────────────────────────────────

struct E2eRemoveScreen: View {
    @ObservedObject var vm: E2eModel
    let onBack: () -> Void
    @State private var confirmRemove = false

    var body: some View {
        MailScaffold(title: "Remove the key from this device", onBack: onBack) {
            VStack(alignment: .leading, spacing: 0) {
                if let note = vm.state.note {
                    E2eNote(text: note, bad: vm.state.noteIsBad)
                    Spacer().frame(height: 10)
                }
                // The warning is the consequence, stated before the tap, in the
                // web's own words. Not a scare line: permanent and everywhere.
                E2eLede("This deletes the private key here. Every message already encrypted to it "
                        + "becomes unreadable — here and anywhere else, permanently. Do this only if you "
                        + "have the key somewhere else, or you accept losing access to that mail.")
                Spacer().frame(height: 16)
                if !confirmRemove {
                    E2eButton(text: "Remove from this device", enabled: vm.state.record != nil,
                              quiet: true, marker: "gm-e2e-remove-start",
                              onClick: { confirmRemove = true })
                } else {
                    E2eButton(text: vm.state.busy ? "Removing…" : "Yes, delete the key from this device",
                              enabled: !vm.state.busy, marker: "gm-e2e-remove-confirm",
                              onClick: { vm.removeKey(); confirmRemove = false; onBack() })
                    Spacer().frame(height: 8)
                    E2eButton(text: "Keep it", enabled: true, quiet: true,
                              onClick: { confirmRemove = false })
                }
                Spacer()
            }
            .padding(.horizontal, 20)
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
    @ObservedObject var vm: E2eModel
    let onBack: () -> Void
    let onCompose: () -> Void
    @State private var draft = ""

    var body: some View {
        MailScaffold(title: "Recipient keys", onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    E2eLede("Check that the people you are about to write to have published keys. A found "
                            + "entry shows the fingerprint to compare; an unavailable one says why, in the "
                            + "directory's own words.")
                    Spacer().frame(height: 8)
                    HStack(spacing: 10) {
                        AppTextField(value: $draft, hint: "someone@example.com")
                            .frame(maxWidth: .infinity)
                        E2eButton(text: "Check",
                                  enabled: !vm.state.busy && !draft.trimmed.isEmpty,
                                  marker: "gm-e2e-lookup-submit",
                                  onClick: { vm.lookup(addresses: [draft.trimmed]); draft = "" })
                            .fixedSize()
                    }
                    if let note = vm.state.note {
                        Spacer().frame(height: 8)
                        E2eNote(text: note, bad: vm.state.noteIsBad)
                    }
                    if !vm.state.lookup.isEmpty {
                        Spacer().frame(height: 10)
                        ForEach(vm.state.lookup.indices) { i in
                            lookupRow(vm.state.lookup[i])
                        }
                        Spacer().frame(height: 10)
                        E2eButton(text: "Back to compose", enabled: true, quiet: true,
                                  onClick: onCompose)
                    } else if !vm.state.lookupFor.isEmpty && !vm.state.busy {
                        Text("The directory answered nothing for that check.")
                            .font(.system(size: 13)).foregroundColor(GM.textTertiary)
                            .padding(.vertical, 10)
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
            }
        }
    }

    @ViewBuilder private func lookupRow(_ e: MailE2eLookupEntry) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(e.address).font(.system(size: 14, weight: .medium))
                    .foregroundColor(GM.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(e.status == "found" ? "Found" : "Cannot encrypt")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(e.status == "found" ? GM.success : GM.warnInk)
            }
            if e.status == "found", e.signing != nil {
                Text("Signing fingerprint \(e.signing?.fingerprint ?? "-")")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundColor(GM.textSecondary)
            } else if e.status != "found" {
                Text(why(e)).font(.system(size: 12)).foregroundColor(GM.textTertiary)
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
        case .notAnAddress: return "That is not an address the directory can look up."
        case .remoteDomain: return "Not a GrapMail address — the directory only knows GrapMail keys."
        case .noSuchAccount: return "There is no GrapMail account at that address."
        case .noKeyPublished: return "This person has not published an encryption key yet."
        case .unknown: return e.reason ?? "The directory gave no reason."
        }
    }
}
