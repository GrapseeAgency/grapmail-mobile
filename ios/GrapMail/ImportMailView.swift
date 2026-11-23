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

    private struct Provider: Hashable { let label: String; let host: String; let help: String; let helpURL: String? }
    private static let providers = [
        Provider(label: "Gmail", host: "imap.gmail.com",
                 help: "Turn on 2-Step Verification for the Google account, then create an app password and paste it here. Your Google password itself will not work.",
                 helpURL: "https://myaccount.google.com/apppasswords"),
        Provider(label: "Yahoo", host: "imap.mail.yahoo.com",
                 help: "Create an app password in Yahoo's Account Security settings and paste it here.",
                 helpURL: "https://login.yahoo.com/account/security"),
        Provider(label: "iCloud", host: "imap.mail.me.com",
                 help: "Create an app-specific password for your Apple Account and use your full iCloud email address as the username.",
                 helpURL: "https://account.apple.com"),
        Provider(label: "Other", host: "",
                 help: "Any provider that offers IMAP over a secure connection (port 993). Check their help pages for the server name.",
                 helpURL: nil),
    ]
    private static let periods: [(Int, String)] = [(3, "3 months"), (12, "1 year"), (60, "5 years"), (0, "Everything")]

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
            Form {
                if let job { progress(job) }
                if !running { form }
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
                    Text("\(done) of \(total) checked · \(job.imported) imported · \(job.skipped) already here"
                         + (job.failed > 0 ? " · \(job.failed) could not be read" : ""))
                        .font(.footnote).foregroundColor(GM.textSecondary)
                }
                ForEach(job.folders, id: \.name) { f in
                    Text("\(f.name): \(f.done) of \(f.total)").font(.caption).foregroundColor(GM.textTertiary)
                }
                if let error = job.error { Text(error).font(.footnote).foregroundColor(GM.error) }
                if job.state == "done" {
                    Text("Imported mail is in your Inbox, Sent and Archive. Running the import again only brings what is new.")
                        .font(.footnote).foregroundColor(GM.textSecondary)
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
        case "connecting": return "Connecting to \(job.host)…"
        case "importing": return "Importing from \(job.username)"
        case "done": return "Import finished"
        case "cancelled": return "Import stopped"
        default: return "Import stopped with a problem"
        }
    }

    @ViewBuilder private var form: some View {
        Section {
            Picker("Account", selection: $provider) {
                ForEach(Self.providers, id: \.self) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
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
            Picker("How far back", selection: $months) {
                ForEach(Self.periods, id: \.0) { Text($0.1).tag($0.0) }
            }
            .pickerStyle(.segmented)
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
        if h.isEmpty { error = "Enter the IMAP server name."; return }
        if u.isEmpty { error = "Enter the email address of that account."; return }
        if password.isEmpty { error = "Enter the app password."; return }
        if !inbox && !sent && !archive { error = "Choose at least one folder."; return }
        guard let token = SessionStore.token else { error = "Sign in again."; return }
        error = nil
        busy = true
        Task {
            defer { busy = false }
            do {
                let r = try await ApiClient.importStart(token: token, host: h, username: u, password: password,
                                                       inbox: inbox, sent: sent, archive: archive, sinceMonths: months)
                if r.success { job = r.job; password = "" } else { error = r.error ?? "The import could not start." }
            } catch {
                self.error = (error as? ApiError)?.userMessage ?? "The import could not start."
            }
        }
    }
}
