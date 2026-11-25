import SwiftUI

/// `GET/POST /grapmail/import/imap` (backend lib/imap-import/job.ts).
struct ImportJob: Decodable, Equatable {
    struct Folder: Decodable, Equatable {
        var name = ""; var kind = ""; var total = 0; var done = 0
        private enum CodingKeys: String, CodingKey { case name, kind, total, done }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            name = c.lenient(.name, ""); kind = c.lenient(.kind, "")
            total = c.lenient(.total, 0); done = c.lenient(.done, 0)
        }
    }
    var id = ""
    /// connecting, importing, done, failed, cancelled
    var state = ""
    var host = ""
    var username = ""
    var finishedAt: String?
    var folders: [Folder] = []
    var imported = 0
    var skipped = 0
    var failed = 0
    var error: String?

    private enum CodingKeys: String, CodingKey { case id, state, host, username, finishedAt, folders, imported, skipped, failed, error }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, ""); state = c.lenient(.state, ""); host = c.lenient(.host, ""); username = c.lenient(.username, "")
        finishedAt = c.lenientOpt(.finishedAt); folders = c.lenient(.folders, [])
        imported = c.lenient(.imported, 0); skipped = c.lenient(.skipped, 0); failed = c.lenient(.failed, 0)
        error = c.lenientOpt(.error)
    }
}

struct ImportJobResponse: Decodable {
    var success = false
    var job: ImportJob?
    var error: String?

    private enum CodingKeys: String, CodingKey { case success, job, error }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        job = c.lenientOpt(.job)
        error = c.lenientOpt(.error)
    }
}

extension ApiClient {
    static func importStatus(token: String) async throws -> ImportJobResponse {
        try await request("\(mailBase)/import/imap", bearer: token)
    }

    /// The app password travels once, over TLS, and is never stored by the server.
    static func importStart(token: String, host: String, username: String, password: String,
                            inbox: Bool, sent: Bool, archive: Bool, sinceMonths: Int) async throws -> ImportJobResponse {
        let body = try JSONSerialization.data(withJSONObject: [
            "host": host, "port": 993, "username": username, "password": password, "sinceMonths": sinceMonths,
            "include": ["inbox": inbox, "sent": sent, "archive": archive],
        ])
        return try await request("\(mailBase)/import/imap", method: "POST", body: body, bearer: token)
    }

    static func importCancel(token: String) async throws -> ImportJobResponse {
        try await request("\(mailBase)/import/imap/cancel", method: "POST", body: Data("{}".utf8), bearer: token)
    }
}

/// Import mail from another account over IMAP. The other account is only read, and
/// the app password is sent once to start the import and kept nowhere.
struct ImportMailView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Environment(\.dynamicTypeSize) private var dts

    private struct Provider: Hashable { let label: String; let host: String; let help: String; let helpURL: String? }
    private static let providers = [
        Provider(label: "Gmail", host: "imap.gmail.com",
                 help: String(localized: "Turn on 2-Step Verification for the Google account, then create an app password and paste it here. Your Google password itself will not work."),
                 helpURL: "https://myaccount.google.com/apppasswords"),
        Provider(label: "Yahoo", host: "imap.mail.yahoo.com",
                 help: String(localized: "Create an app password in Yahoo's Account Security settings and paste it here."),
                 helpURL: "https://login.yahoo.com/account/security"),
        Provider(label: "iCloud", host: "imap.mail.me.com",
                 help: String(localized: "Create an app-specific password for your Apple Account and use your full iCloud email address as the username."),
                 helpURL: "https://account.apple.com"),
        Provider(label: String(localized: "Other", comment: "Mail import: any other IMAP provider"), host: "",
                 help: String(localized: "Any provider that offers IMAP over a secure connection (port 993). Check their help pages for the server name."),
                 helpURL: nil),
    ]
    private static let periods: [(Int, String)] = [(3, String(localized: "3 months")), (12, String(localized: "1 year")), (60, String(localized: "5 years")), (0, String(localized: "Everything", comment: "Mail import period: all mail"))]

    @State private var provider = ImportMailView.providers[0]
    @State private var host = ImportMailView.providers[0].host
    @State private var username = ""
    @State private var password = ""
    @State private var inbox = true
    @State private var sent = true
    @State private var archive = false
    @State private var months = 12
    @State private var job: ImportJob?
    @State private var error: String?
    @State private var busy = false

    private var running: Bool { job != nil && job?.finishedAt == nil }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
            GMOfflineBanner()
            Form {
                if let job { progress(job) }
                if !running { form }
            }
            }
            .navigationTitle("Import mail")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .task(id: running) { await poll() }
    }

    private func poll() async {
        repeat {
            guard let token = SessionStore.token,
                  let status = try? await ApiClient.importStatus(token: token), status.success else { return }
            job = status.job
            guard let current = job, current.finishedAt == nil else { return }
            try? await Task.sleep(nanoseconds: 2_000_000_000)
        } while !Task.isCancelled
    }

    @ViewBuilder private func progress(_ job: ImportJob) -> some View {
        let total = job.folders.reduce(0) { $0 + $1.total }
        let done = job.folders.reduce(0) { $0 + $1.done }
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text(Self.headline(job)).font(.headline)
                if total > 0 {
                    ProgressView(value: Double(done), total: Double(total)).tint(GM.primarySolid)
                    // Two whole sentences rather than an optional clause glued on.
                    Text(job.failed > 0
                         ? String(localized: "\(done) of \(total) checked · \(job.imported) imported · \(job.skipped) already here · \(job.failed) could not be read")
                         : String(localized: "\(done) of \(total) checked · \(job.imported) imported · \(job.skipped) already here"))
                        .font(.footnote).foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                ForEach(job.folders, id: \.name) { f in
                    Text("\(f.name): \(f.done) of \(f.total)").font(.caption).foregroundColor(GM.textTertiary)
                }
                if let error = job.error { Text(error).font(.footnote).foregroundColor(GM.error) }
                if job.state == "done" {
                    Text("Imported mail is in your Inbox, Sent and Archive. Running the import again only brings what is new.")
                        .font(.footnote).foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("import-progress")
            if job.finishedAt == nil {
                Button("Stop the import", role: .destructive) {
                    Task {
                        guard let token = SessionStore.token else { return }
                        if let r = try? await ApiClient.importCancel(token: token) { self.job = r.job ?? self.job }
                    }
                }
                .accessibilityIdentifier("import-cancel")
            }
        }
    }

    static func headline(_ job: ImportJob) -> String {
        switch job.state {
        case "connecting": return String(localized: "Connecting to \(job.host)…")
        case "importing": return String(localized: "Importing from \(job.username)")
        case "done": return String(localized: "Import finished")
        case "cancelled": return String(localized: "Import stopped")
        default: return String(localized: "Import stopped with a problem")
        }
    }

    /// Four segments do not fit across a phone at the accessibility text sizes
    /// (the labels truncate to "…"), so the pickers become menus there.
    @ViewBuilder private func adaptivePicker<P: View>(_ picker: P) -> some View {
        if dts.isAccessibilitySize {
            picker.pickerStyle(.menu)
        } else {
            picker.pickerStyle(.segmented)
        }
    }

    @ViewBuilder private var form: some View {
        Section {
            adaptivePicker(
                Picker("Account", selection: $provider) {
                    ForEach(Self.providers, id: \.self) { Text($0.label).tag($0) }
                }
            )
            .onChange(of: provider) { p in host = p.host }
            Text(provider.help).font(.footnote).foregroundColor(GM.textSecondary)
            if let url = provider.helpURL.flatMap(URL.init(string:)) {
                Button("Create an app password") { openURL(url) }
            }
        } footer: {
            Text("Bring your mail from another account into GrapMail. The other account is only read: nothing there changes, and your password is not kept.")
        }
        Section {
            if provider.host.isEmpty {
                TextField("IMAP server (imap.example.com)", text: $host)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    .accessibilityIdentifier("import-host")
            }
            TextField("Email address", text: $username)
                .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.emailAddress)
                .textContentType(.username)
                .accessibilityIdentifier("import-username")
            SecureField("App password", text: $password)
                .textContentType(.password)
                .accessibilityIdentifier("import-password")
        }
        Section("Folders") {
            Toggle("Inbox", isOn: $inbox)
            Toggle("Sent", isOn: $sent)
            Toggle("Archive (or All Mail)", isOn: $archive)
        }
        Section("How far back") {
            adaptivePicker(
                Picker("How far back", selection: $months) {
                    ForEach(Self.periods, id: \.0) { Text($0.1).tag($0.0) }
                }
            )
        }
        Section {
            if let error { Text(error).foregroundColor(GM.error).accessibilityIdentifier("import-error") }
            Button(busy ? "Starting…" : "Start import", action: start).disabled(busy)
        } footer: {
            Text("Outlook.com and Microsoft 365 no longer allow importing with a password, so they are not listed. Mail from Outlook can be forwarded to your GrapMail address instead.")
        }
    }

    private func start() {
        let h = host.trimmingCharacters(in: .whitespaces), u = username.trimmingCharacters(in: .whitespaces)
        if h.isEmpty { error = String(localized: "Enter the IMAP server name."); return }
        if u.isEmpty { error = String(localized: "Enter the email address of that account."); return }
        if password.isEmpty { error = String(localized: "Enter the app password."); return }
        if !inbox && !sent && !archive { error = String(localized: "Choose at least one folder."); return }
        guard let token = SessionStore.token else { error = String(localized: "Sign in again."); return }
        error = nil
        busy = true
        Task {
            defer { busy = false }
            do {
                let r = try await ApiClient.importStart(token: token, host: h, username: u, password: password,
                                                       inbox: inbox, sent: sent, archive: archive, sinceMonths: months)
                if r.success { job = r.job; password = "" } else { error = r.error ?? String(localized: "The import could not start.") }
            } catch {
                self.error = (error as? ApiError)?.userMessage ?? String(localized: "The import could not start.")
            }
        }
    }
}
