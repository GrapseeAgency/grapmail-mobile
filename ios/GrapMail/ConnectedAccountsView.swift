import SwiftUI
import AuthenticationServices

/// Connected accounts — Outlook (Microsoft Graph) and IMAP/SMTP mailboxes the backend
/// syncs into the GrapMail mailbox (routes/grapmail-providers.ts). Synced mail lands in
/// the same grapmail_messages table as native mail, so it already shows in the Inbox.
/// Gmail is not offered. Android twin: ui/screens/mail/ConnectedAccountsScreen.kt.
@MainActor
final class ConnectedAccountsModel: ObservableObject {
    enum Problem: Equatable { case offline, signedOut, server(String?), outlookUnavailable, oauthState, oauthDenied(String) }

    @Published var accounts: [ProviderAccount]?
    @Published var loading = false
    @Published var problem: Problem?
    @Published var busyId: String?
    @Published var form = ImapForm()
    @Published var formErrors: Set<ImapFormError> = []
    @Published var submitting = false
    @Published var outlookBusy = false

    var visible: [ProviderAccount] { (accounts ?? []).filter { $0.status != "revoked" } }

    private func fail(_ e: Error) {
        if let api = e as? ApiError {
            switch api {
            case .network: problem = .offline
            case let .http(status, _, message): problem = status == 401 ? .signedOut : .server(message.isEmpty ? nil : message)
            default: problem = .server(nil)
            }
        } else if (e as? URLError) != nil { problem = .offline } else { problem = .server(nil) }
    }

    func load() async {
        guard let token = SessionStore.token else { problem = .signedOut; return }
        loading = true; problem = nil
        defer { loading = false }
        do { accounts = try await ApiClient.providersList(token: token).accounts } catch { fail(error) }
    }

    func sync(_ id: String) async { await act(id) { try await ApiClient.providersSync(token: $0, id: id) } }
    func disconnect(_ id: String) async { await act(id) { try await ApiClient.providersDisconnect(token: $0, id: id) } }

    private func act(_ id: String, _ block: (String) async throws -> ProviderSimpleResponse) async {
        guard let token = SessionStore.token else { problem = .signedOut; return }
        busyId = id; problem = nil
        do { _ = try await block(token) } catch { fail(error) }
        busyId = nil
        await load()
    }

    /// Returns true on success. The password is blanked whatever the outcome.
    func submitImap() async -> Bool {
        formErrors = ImapFormValidator.validate(form)
        guard formErrors.isEmpty else { return false }
        guard let token = SessionStore.token else { problem = .signedOut; return false }
        submitting = true; problem = nil
        defer { submitting = false; form.password = "" }
        do {
            _ = try await ApiClient.providersConnectImap(token: token, form: form)
            form = ImapForm()
            await load()
            return true
        } catch { fail(error); return false }
    }

    func connectOutlook(email: String, anchor: ASPresentationAnchor) async {
        guard let token = SessionStore.token else { problem = .signedOut; return }
        outlookBusy = true; problem = nil
        defer { outlookBusy = false }
        let urlString: String
        do {
            let r = try await ApiClient.providersAuthorizeOutlook(token: token)
            guard let u = r.authorizationUrl else { problem = .outlookUnavailable; return }
            urlString = u
        } catch ApiError.http(501, _, _) { problem = .outlookUnavailable; return }
        catch { fail(error); return }
        guard let pending = ProviderOAuth.pending(from: urlString, providerEmail: email), let url = URL(string: urlString) else {
            problem = .outlookUnavailable; return
        }
        // ASWebAuthenticationSession captures the grapmail:// redirect itself, so no
        // app-wide deep-link handler is needed on iOS.
        let callback: URL? = await withCheckedContinuation { cont in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: ProviderOAuth.callbackScheme) { url, _ in
                cont.resume(returning: url)
            }
            session.presentationContextProvider = AnchorProvider.shared(anchor)
            session.prefersEphemeralWebBrowserSession = true
            if !session.start() { cont.resume(returning: nil) }
        }
        guard let callback, let result = ProviderOAuth.parse(callback) else { return } // cancelled
        switch result {
        case let .denied(reason): problem = .oauthDenied(reason)
        case let .code(code, state):
            guard state == pending.state else { problem = .oauthState; return }
            do {
                _ = try await ApiClient.providersConnectOutlook(token: token, code: code, state: state,
                                                               redirectUri: pending.redirectUri, providerEmail: pending.providerEmail)
                await load()
            } catch { fail(error) }
        }
    }
}

private final class AnchorProvider: NSObject, ASWebAuthenticationPresentationContextProviding {
    private static var current: AnchorProvider?
    let anchor: ASPresentationAnchor
    init(_ a: ASPresentationAnchor) { anchor = a }
    static func shared(_ a: ASPresentationAnchor) -> AnchorProvider { let p = AnchorProvider(a); current = p; return p }
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor { anchor }
}

/// Layout: the list keeps a form-width column (`GMLayout.form`), centred, on
/// wide windows (iPad, Split View, Stage Manager, Mac, Vision Pro) — decided by
/// the space it is given, never by the device. On a phone the cap is wider than
/// the screen, so nothing moves. The offline banner is MailScaffold's own; this
/// screen used to draw a second one under it.
struct ConnectedAccountsView: View {
    let onBack: () -> Void
    @StateObject private var vm = ConnectedAccountsModel()
    @Environment(\.openURL) private var openURL
    @Environment(\.dynamicTypeSize) private var dts
    @State private var showImap = false
    @State private var confirmRemove: ProviderAccount?
    @State private var askOutlook = false
    @State private var outlookEmail = ""

    var body: some View {
        MailScaffold(title: String(localized: "Connected mailboxes"), onBack: onBack) {
            List {
                if let p = vm.problem { problemSection(p) }
                Section {
                    if vm.accounts == nil && vm.loading {
                        GMSkeletonList(rows: 3)
                            .listRowInsets(EdgeInsets())
                    } else if vm.accounts != nil && vm.visible.isEmpty {
                        Text("No mailboxes connected yet.").foregroundColor(GM.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("providers-empty")
                    } else {
                        ForEach(vm.visible) { row($0) }
                    }
                } footer: {
                    Text("Bring mail from Outlook or another IMAP account into GrapMail. New mail from a connected account arrives in your Inbox.")
                }
                Section("Add an account") {
                    Button { outlookEmail = ""; askOutlook = true } label: {
                        HStack {
                            Text("Connect Outlook")
                                .fixedSize(horizontal: false, vertical: true)
                            if vm.outlookBusy { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(vm.outlookBusy)
                    .accessibilityIdentifier("providers-add-outlook")
                    Button {
                        showImap = true
                    } label: {
                        // Long label: wraps at large text sizes instead of
                        // losing its end ("…another IMAP account").
                        Text("Connect Yahoo, iCloud, AOL or another IMAP account")
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityIdentifier("providers-add-imap")
                }
            }
            .refreshable { await vm.load() }
            .gmMaxWidth(GMLayout.form)
        }
        .task { await vm.load() }
        .sheet(isPresented: $showImap) { ImapConnectSheet(vm: vm) }
        .alert("Connect Outlook", isPresented: $askOutlook) {
            TextField("Email address", text: $outlookEmail)
                .textInputAutocapitalization(.never).keyboardType(.emailAddress)
            Button("Continue") { startOutlook() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Enter the Outlook or Microsoft 365 address you will sign in with. Microsoft's sign-in page opens next.")
        }
        .confirmationDialog(String(localized: "Disconnect \(confirmRemove?.providerEmail ?? "")?"),
                            isPresented: Binding(get: { confirmRemove != nil }, set: { if !$0 { confirmRemove = nil } }),
                            titleVisibility: .visible) {
            Button("Disconnect", role: .destructive) {
                if let a = confirmRemove { Task { await vm.disconnect(a.id) } }
                confirmRemove = nil
            }
        } message: {
            Text("GrapMail stops syncing this account. Mail already in GrapMail stays, and nothing changes in the other account.")
        }
    }

    private func startOutlook() {
        let email = outlookEmail.trimmingCharacters(in: .whitespaces)
        guard email.contains("@") else { return }
        let anchor = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first ?? ASPresentationAnchor()
        Task { await vm.connectOutlook(email: email, anchor: anchor) }
    }

    @ViewBuilder private func problemSection(_ p: ConnectedAccountsModel.Problem) -> some View {
        Section {
            Group {
                switch p {
                case .offline: Text("You are offline. Connect to the internet and try again.")
                case .signedOut: Text("Sign in again.")
                case let .server(msg): Text(msg ?? String(localized: "Connected accounts could not be loaded."))
                case .outlookUnavailable: Text("Outlook can't be connected on this server right now.")
                case .oauthState: Text("That sign-in did not match the one this app started. Start again.")
                case .oauthDenied: Text("Microsoft did not grant access. Nothing was connected.")
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            if p == .offline || { if case .server = p { return true }; return false }() {
                Button("Try again") { Task { await vm.load() } }
            }
        }
        .foregroundColor(GM.error)
        .accessibilityIdentifier("providers-problem")
    }

    @ViewBuilder private func row(_ a: ProviderAccount) -> some View {
        let status = ProviderUIStatus.of(a.status, lastSyncAt: a.lastSyncAt, lastError: a.lastError)
        VStack(alignment: .leading, spacing: 4) {
            Text(a.providerEmail).font(.headline)
                .fixedSize(horizontal: false, vertical: true)
            Text(a.provider == "microsoft" ? "Outlook" : "IMAP").font(.caption).foregroundColor(GM.textTertiary)
            Group {
                switch status {
                case .syncing: Text("Syncing for the first time…")
                case .ok: Text("Connected")
                case .needsReauth: Text("Needs you to sign in again").foregroundColor(GM.error)
                case .error: Text("Sync problem").foregroundColor(GM.error)
                case .disconnected: Text("Disconnected")
                }
            }
            .font(.subheadline)
            .fixedSize(horizontal: false, vertical: true)
            if status == .error, let e = a.lastError {
                Text(e).font(.caption).foregroundColor(GM.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(Self.lastSync(a.lastSyncAt)).font(.caption).foregroundColor(GM.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            // Two buttons side by side; stacked at the accessibility text sizes,
            // where "Sync now" and "Disconnect" no longer fit across a phone.
            GMAdaptiveStack {
                if vm.busyId == a.id { ProgressView() }
                if status == .needsReauth {
                    Button("Reconnect") {
                        if a.provider == "microsoft" { outlookEmail = a.providerEmail; askOutlook = true }
                        else { vm.form.email = a.providerEmail; showImap = true }
                    }
                } else {
                    Button("Sync now") { Task { await vm.sync(a.id) } }
                        .accessibilityIdentifier("provider-sync-\(a.id)")
                }
                if !dts.isAccessibilitySize { Spacer() }
                Button("Disconnect", role: .destructive) { confirmRemove = a }
                    .accessibilityIdentifier("provider-remove-\(a.id)")
            }
            .buttonStyle(.borderless)
            .disabled(vm.busyId == a.id)
            .padding(.top, dts.isAccessibilitySize ? 4 : 0)
        }
        .padding(.vertical, 4)
        .accessibilityIdentifier("provider-row-\(a.id)")
    }

    static func lastSync(_ iso: String?) -> String {
        guard let iso else { return String(localized: "Not synced yet") }
        let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = f.date(from: iso) ?? ISO8601DateFormatter().date(from: iso)
        guard let date else { return String(localized: "Last synced \(iso)") }
        let rel = RelativeDateTimeFormatter().localizedString(for: date, relativeTo: Date())
        return String(localized: "Last synced \(rel)")
    }
}

/// The IMAP form sheet. Scrolls (it is a Form), opens full height so every
/// field is reachable with the keyboard up, and keeps a form-width column when
/// the sheet is wider than a phone (Mac, Vision Pro, large Stage Manager
/// windows). The keyboard can be pulled down by scrolling.
struct ImapConnectSheet: View {
    @ObservedObject var vm: ConnectedAccountsModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    private var preset: ProviderPreset { ProviderPreset.byId(vm.form.presetId) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Provider", selection: Binding(get: { vm.form.presetId }, set: { vm.form.apply(ProviderPreset.byId($0)); vm.formErrors = [] })) {
                        ForEach(ProviderPreset.all) { p in
                            Text(p.isCustom ? String(localized: "Custom", comment: "IMAP preset: enter servers by hand") : p.label).tag(p.id)
                        }
                    }
                    .accessibilityIdentifier("providers-preset")
                    Text(preset.isCustom
                         ? String(localized: "Enter the IMAP and SMTP servers from your provider's help pages.")
                         : String(localized: "Create an app password in your provider's security settings and paste it here. Your normal password usually will not work."))
                        .font(.footnote).foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let url = preset.appPasswordURL.flatMap(URL.init(string:)) {
                        Button("Create an app password") { openURL(url) }
                    }
                }
                Section {
                    TextField("Email address", text: Binding(get: { vm.form.email }, set: { v in
                        let e = v.trimmingCharacters(in: .whitespaces)
                        if let g = ProviderPreset.forEmail(e), g.id != vm.form.presetId, !preset.isCustom { vm.form.apply(g) }
                        vm.form.email = e; vm.formErrors = []
                    }))
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.emailAddress)
                    .textContentType(.username).accessibilityIdentifier("providers-email")
                    if vm.formErrors.contains(.email) { Text("Enter a full email address.").font(.caption).foregroundColor(GM.error) }
                    SecureField("App password", text: $vm.form.password)
                        .textContentType(.password).accessibilityIdentifier("providers-password")
                    if vm.formErrors.contains(.password) { Text("Enter the app password.").font(.caption).foregroundColor(GM.error) }
                }
                if preset.isCustom {
                    server(String(localized: "Incoming (IMAP)"), host: $vm.form.imapHost, port: $vm.form.imapPort, tls: $vm.form.imapTLS,
                           hostBad: vm.formErrors.contains(.imapHost), portBad: vm.formErrors.contains(.imapPort), tag: "imap")
                    server(String(localized: "Outgoing (SMTP)"), host: $vm.form.smtpHost, port: $vm.form.smtpPort, tls: $vm.form.smtpTLS,
                           hostBad: vm.formErrors.contains(.smtpHost), portBad: vm.formErrors.contains(.smtpPort), tag: "smtp")
                } else {
                    Section {
                        // Two host:port pairs; wraps rather than truncating a
                        // server name at large text sizes or in a narrow window.
                        Text("\(preset.imapHost):\(preset.imapPort) · \(preset.smtpHost):\(preset.smtpPort)").font(.caption).foregroundColor(GM.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Section {
                    if case let .server(msg)? = vm.problem {
                        Text(msg ?? String(localized: "The account could not be connected.")).foregroundColor(GM.error)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if vm.problem == .offline {
                        Text("You are offline. Connect to the internet and try again.").foregroundColor(GM.error)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Button(vm.submitting ? String(localized: "Connecting…") : String(localized: "Connect")) {
                        Task { if await vm.submitImap() { dismiss() } }
                    }
                    .disabled(vm.submitting)
                    .accessibilityIdentifier("providers-imap-submit")
                } footer: {
                    Text("GrapMail checks the details during the first sync. If they are wrong, this account will show a sync problem. Your password is not kept on this device.")
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .gmMaxWidth(GMLayout.form)
            .navigationTitle("Connect an IMAP account")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { vm.form.password = ""; dismiss() } } }
        }
        // A form with up to eight fields: always the full-height sheet, never a
        // half sheet that hides the Connect button behind the keyboard.
        .presentationDetents([.large])
        .onDisappear { vm.form.password = "" }
    }

    @ViewBuilder private func server(_ title: String, host: Binding<String>, port: Binding<String>, tls: Binding<Bool>,
                                     hostBad: Bool, portBad: Bool, tag: String) -> some View {
        Section(title) {
            TextField("Server", text: host).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                .accessibilityIdentifier("providers-\(tag)-host")
            if hostBad { Text("Enter a server name such as imap.example.com.").font(.caption).foregroundColor(GM.error) }
            TextField("Port", text: port).keyboardType(.numberPad).accessibilityIdentifier("providers-\(tag)-port")
            if portBad { Text("Use a port from 1 to 65535.").font(.caption).foregroundColor(GM.error) }
            Toggle("TLS", isOn: tls).accessibilityIdentifier("providers-\(tag)-tls")
        }
    }
}
