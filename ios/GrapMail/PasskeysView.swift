import SwiftUI

/// The passkey screens: the credential list the account holds, and the
/// registration flow that adds one. They hang off the account-security hub the
/// way TrustedDevicesView does, so the shells, chips and buttons here are the
/// security sector's own components — not redrawn ones.
///
/// ── WHAT A PASSKEY IS HERE ──────────────────────────────────────────────────
/// A credential whose private half lives in the iCloud Keychain (PasskeyManager
/// never sees it). A row here signs this account in without a password on any
/// device that shares that Keychain — which is exactly why deleting one is the
/// same one-way act revoking a session is, and takes the same confirmation.

// ── Passkeys ─────────────────────────────────────────────────────────────────

@MainActor
struct PasskeysView: View {
    let onBack: () -> Void

    @State private var passkeys: [PasskeyDto] = []
    @State private var loading = true
    @State private var error: String?
    /// The passkey behind an open confirmation. Held as the row itself so the
    /// alert's title names the right credential even if the list re-renders.
    @State private var pendingDelete: PasskeyDto?
    @State private var deleting = false
    @State private var deleteError: String?
    @State private var adding = false
    @State private var addError: String?

    var body: some View {
        AccountScreen(
            onBack: onBack,
            illustration: "ill_sec_hub",
            illustrationWidth: 150,
            title: String(localized: "Passkeys", comment: "Screen title (passkeys screen)"),
            subtitle: String(localized: "Sign in with your face, fingerprint or device passcode — no password to type, leak or reuse.", comment: "Screen description under the title (passkeys screen)")
        ) {
            if loading {
                GMSkeletonList(rows: 3, avatar: false)
            } else if let error {
                Text(error).gmFont(13).foregroundColor(GM.error)
                    .fixedSize(horizontal: false, vertical: true)
                AccountLink(title: String(localized: "Try again", comment: "Link-style button (passkeys screen)"), action: { Task { await load() } })
            } else {
                if passkeys.isEmpty {
                    Text("No passkeys are registered for this account yet. Add one below and this device can sign you in without your password.")
                        .gmFont(13)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    VStack(spacing: 10) {
                        ForEach(passkeys) { passkey in
                            PasskeyCardView(passkey: passkey, onDelete: { pendingDelete = passkey })
                        }
                    }
                }
                HStack(alignment: .top, spacing: 9) {
                    Image(systemName: "info.circle").gmFont(14).foregroundColor(GM.primaryText).accessibilityHidden(true)
                    Text("A passkey works only where your iCloud Keychain is — revoking a device does not remove its passkey, and deleting a passkey does not revoke the device that made it.")
                        .gmFont(12)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.softFill))
                .padding(.top, 12)
                if let addError {
                    Text(addError).gmFont(12).foregroundColor(GM.error).padding(.top, 6)
                }
                if let deleteError {
                    Text(deleteError).gmFont(12).foregroundColor(GM.error).padding(.top, 6)
                }
            }
        } footer: {
            VStack(spacing: 6) {
                GradientButton(title: String(localized: "Add Passkey", comment: "Button title (passkeys screen)"), busy: adding, action: addPasskey)
                FooterNote(text: String(localized: "Registering a passkey signs a challenge with this device's credential manager. The private key never leaves it.", comment: "Footer note (passkeys screen)"))
            }
        }
        .task { await load() }
        .alert(
            pendingDelete.map { $0.name.isEmpty ? Text("Delete this passkey?") : Text("Delete \($0.name)?") } ?? Text("Delete this passkey?"),
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })
        ) {
            Button("Cancel", role: .cancel) { pendingDelete = nil }
            Button("Delete Passkey", role: .destructive) { if let passkey = pendingDelete { delete(passkey) } }
        } message: {
            Text("This passkey can no longer sign you in. This cannot be undone from here.")
        }
    }

    // ── reads and writes ─────────────────────────────────────────────────────

    private func load() async {
        // No session is a failure to read, not "no passkeys".
        guard let token = SessionStore.token else { error = String(localized: "Sign in again."); loading = false; return }
        loading = true
        error = nil
        do {
            let resp = try await ApiClient.listPasskeys(token: token)
            passkeys = resp.passkeys
            // Round 8 — B: the welcome-back hint (spec §2.4) follows every change
            // to the list. A hint only: it never signs anyone in or skips a step.
            if resp.success, let address = SessionStore.address {
                RememberedAccounts.setHasPasskey(!resp.passkeys.isEmpty, for: address)
            }
        } catch {
            self.error = apiMessage(error, fallback: String(localized: "Could not load the passkey list. Try again.", comment: "Error message shown when the server gives no reason (passkeys screen)"))
        }
        loading = false
    }

    /// Options → platform create sheet → verify. The name is this device's own
    /// label, the same string trusted-device enrollment sends, so the
    /// credential row reads like the device row next to it.
    private func addPasskey() {
        guard let token = SessionStore.token else {
            addError = String(localized: "Sign in again.", comment: "Error when the session has expired")
            return
        }
        adding = true
        addError = nil
        Task {
            defer { adding = false }
            do {
                let optionsResponse = try await ApiClient.passkeyRegisterOptions(token: token)
                guard optionsResponse.success, let challengeId = optionsResponse.challengeId.nilIfBlank(),
                      let challenge = optionsResponse.challengeBase64Url,
                      let options = optionsResponse.options else {
                    addError = String(localized: "Passkey registration is not available right now. Try again.", comment: "Error when passkey registration cannot start")
                    return
                }
                let registration = try await PasskeyManager.register(options: options)
                let verified = try await ApiClient.passkeyRegisterVerify(
                    token: token,
                    challengeId: challengeId,
                    name: PasskeyManager.deviceLabel(),
                    response: PasskeyRegistrationResponseJson(
                        id: registration.credentialId,
                        clientDataJSON: registration.clientDataJSON,
                        attestationObject: registration.attestationObject))
                guard verified.success else {
                    addError = verified.error?.nilIfBlank() ?? String(localized: "The passkey was not verified. Try again.", comment: "Error when the server did not verify a new passkey")
                    return
                }
                await load()
            } catch PasskeyManager.PasskeyError.cancelled {
                // The person closed the sheet — not a failure to report.
            } catch {
                addError = apiMessage(error, fallback: String(localized: "The passkey could not be added. Try again.", comment: "Error message shown when the server gives no reason (passkeys screen)"))
            }
        }
    }

    private func delete(_ passkey: PasskeyDto) {
        guard let token = SessionStore.token else { return }
        guard !deleting else { return }
        deleting = true
        deleteError = nil
        Task {
            defer { deleting = false }
            do {
                _ = try await ApiClient.deletePasskey(token: token, id: passkey.id)
                pendingDelete = nil
                await load()
            } catch {
                deleteError = apiMessage(error, fallback: String(localized: "The passkey could not be deleted. Try again.", comment: "Error message shown when the server gives no reason (passkeys screen)"))
                pendingDelete = nil
            }
        }
    }
}

/// One passkey credential, as the trusted-device screen prints a device —
/// name, the absolute times the server recorded.
private struct PasskeyCardView: View {
    let passkey: PasskeyDto
    var onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Image(systemName: "key.viewfinder")
                    .gmFont(15, .medium)
                    .foregroundColor(GM.primaryText)
                    .accessibilityHidden(true)
                Text(passkey.name.isEmpty ? String(localized: "Passkey", comment: "Passkey card title when the passkey has no name") : passkey.name)
                    .gmFont(15, .semibold)
                    .foregroundColor(GM.textPrimary)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            Text(lastUsedLine)
                .gmFont(11)
                .foregroundColor(GM.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            Button(action: onDelete) {
                Text("Delete passkey")
                    .gmFont(13, .semibold)
                    .foregroundColor(GM.error)
                    .frame(minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .padding(.top, 2)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: GMShape.button, style: .continuous).fill(GM.card))
        .overlay(RoundedRectangle(cornerRadius: GMShape.button, style: .continuous).strokeBorder(GM.border))
    }

    private var lastUsedLine: String {
        if let lastUsed = passkey.lastUsedAt?.nilIfBlank() {
            return String(localized: "Last used \(securityAbsolute(lastUsed))", comment: "Passkey card: when it was last used; the argument is a date and time")
        }
        if let created = passkey.createdAt?.nilIfBlank() {
            return String(localized: "Registered \(securityAbsolute(created)) · never used", comment: "Passkey card: when it was registered; the argument is a date and time")
        }
        return String(localized: "Never used", comment: "Passkey card: the passkey has never been used")
    }
}
