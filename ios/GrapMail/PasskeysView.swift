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
            title: "Passkeys",
            subtitle: "Sign in with your face, fingerprint or device passcode — no password to type, leak or reuse."
        ) {
            if loading {
                Text("Loading passkeys…").font(.system(size: 13)).foregroundColor(GM.textSecondary)
            } else if let error {
                Text(error).font(.system(size: 13)).foregroundColor(GM.error)
                AccountLink(title: "Try again", action: { Task { await load() } })
            } else {
                if passkeys.isEmpty {
                    Text("No passkeys are registered for this account yet. Add one below and this device can sign you in without your password.")
                        .font(.system(size: 13))
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
                    Image(systemName: "info.circle").font(.system(size: 14)).foregroundColor(GM.primarySolid)
                    Text("A passkey works only where your iCloud Keychain is — revoking a device does not remove its passkey, and deleting a passkey does not revoke the device that made it.")
                        .font(.system(size: 12))
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.softFill))
                .padding(.top, 12)
                if let addError {
                    Text(addError).font(.system(size: 12)).foregroundColor(GM.error).padding(.top, 6)
                }
                if let deleteError {
                    Text(deleteError).font(.system(size: 12)).foregroundColor(GM.error).padding(.top, 6)
                }
            }
        } footer: {
            VStack(spacing: 6) {
                GradientButton(title: "Add Passkey", busy: adding, action: addPasskey)
                FooterNote(text: "Registering a passkey signs a challenge with this device's credential manager. The private key never leaves it.")
            }
        }
        .task { await load() }
        .alert(
            "Delete \(pendingDelete?.name.isEmpty == false ? pendingDelete!.name : "this passkey")?",
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
        guard let token = SessionStore.token else { loading = false; return }
        loading = true
        error = nil
        do {
            let resp = try await ApiClient.listPasskeys(token: token)
            passkeys = resp.passkeys
        } catch {
            self.error = apiMessage(error, fallback: "Could not load the passkey list. Try again.")
        }
        loading = false
    }

    /// Options → platform create sheet → verify. The name is this device's own
    /// label, the same string trusted-device enrollment sends, so the
    /// credential row reads like the device row next to it.
    private func addPasskey() {
        guard let token = SessionStore.token else {
            addError = "Sign in again."
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
                    addError = "Passkey registration is not available right now. Try again."
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
                    addError = verified.error?.nilIfBlank() ?? "The passkey was not verified. Try again."
                    return
                }
                await load()
            } catch PasskeyManager.PasskeyError.cancelled {
                // The person closed the sheet — not a failure to report.
            } catch {
                addError = apiMessage(error, fallback: "The passkey could not be added. Try again.")
            }
        }
    }

    private func delete(_ passkey: PasskeyDto) {
        guard let token = SessionStore.token else { return }
        deleting = true
        deleteError = nil
        Task {
            defer { deleting = false }
            do {
                _ = try await ApiClient.deletePasskey(token: token, id: passkey.id)
                pendingDelete = nil
                await load()
            } catch {
                deleteError = apiMessage(error, fallback: "The passkey could not be deleted. Try again.")
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
            HStack(spacing: 8) {
                Image(systemName: "key.viewfinder")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(GM.primarySolid)
                Text(passkey.name.isEmpty ? "Passkey" : passkey.name)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            Text(lastUsedLine)
                .font(.system(size: 11))
                .foregroundColor(GM.textTertiary)
            Button(action: onDelete) {
                Text("Delete passkey")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(GM.error)
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
            return "Last used " + securityAbsolute(lastUsed)
        }
        if let created = passkey.createdAt?.nilIfBlank() {
            return "Registered " + securityAbsolute(created) + " · never used"
        }
        return "Never used"
    }
}
