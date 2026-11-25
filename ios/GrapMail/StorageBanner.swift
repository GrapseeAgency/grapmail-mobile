import SwiftUI

/// `GET /grapmail/storage` (backend routes/grapmail-storage.ts): space used, the
/// limit, and the warning level (ok, warning at 80%, critical at 95%, full). The
/// labels are the server's own, so both apps say the same thing.
struct MailStorageResponse: Decodable {
    var success = false
    var usedBytes: Double = 0
    var limitBytes: Double = 0
    var percent: Double = 0
    var level = "ok"
    var usedLabel = ""
    var limitLabel = ""

    private enum CodingKeys: String, CodingKey { case success, usedBytes, limitBytes, percent, level, usedLabel, limitLabel }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        usedBytes = c.lenient(.usedBytes, 0)
        limitBytes = c.lenient(.limitBytes, 0)
        percent = c.lenient(.percent, 0)
        level = c.lenient(.level, "ok")
        usedLabel = c.lenient(.usedLabel, "")
        limitLabel = c.lenient(.limitLabel, "")
    }
}

extension ApiClient {
    static func mailStorage(_ token: String) async throws -> MailStorageResponse {
        try await request("\(mailBase)/storage", bearer: token)
    }
}

/// The storage banner above the mail list, the way Gmail warns as an account fills.
/// Read when the mailbox appears or returns to the front, at most once a minute.
/// Drawn at 80% and above; "Not now" hides it until the level rises. A failed read
/// draws nothing.
///
/// Text follows Dynamic Type (ScaledFont.swift); at the accessibility sizes the
/// two buttons stack instead of squeezing each other onto one line.
struct StorageBanner: View {
    let onOpenFiles: () -> Void
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var dts
    @State private var usage: MailStorageResponse?
    @State private var dismissedLevel: String?
    @State private var lastRead = Date.distantPast

    var body: some View {
        Group {
            if let u = usage, u.level != "ok", u.level != dismissedLevel {
                let warning = u.level == "warning"
                VStack(alignment: .leading, spacing: 4) {
                    Text(u.level == "full" ? "Your storage is full" : "Your storage is almost full")
                        .gmFont(13, .semibold)
                        .foregroundColor(warning ? GM.warnInk : GM.error)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("\(u.usedLabel) of \(u.limitLabel) used (\(Self.percent(u.percent))). Delete large attachments, then empty Trash and Spam.")
                        .gmFont(12)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    GMAdaptiveStack(spacing: dts.isAccessibilitySize ? 8 : 16) {
                        Button("See large files", action: onOpenFiles)
                        Button("Not now") { dismissedLevel = u.level }
                    }
                    .gmFont(12, .semibold)
                    .foregroundColor(GM.primarySolid)
                    .padding(.top, 2)
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(warning ? GM.warnChipBg : GM.error.opacity(0.12)))
                .padding(.horizontal, 16).padding(.vertical, 6)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("mail-storage-banner")
            }
        }
        .task { await read() }
        .onChange(of: scenePhase) { phase in
            if phase == .active { Task { await read() } }
        }
    }

    private func read() async {
        guard Date().timeIntervalSince(lastRead) >= 60, let token = SessionStore.token else { return }
        lastRead = Date()
        if let read = try? await ApiClient.mailStorage(token), read.success { usage = read }
    }

    private static func percent(_ p: Double) -> String {
        p.rounded() == p ? "\(Int(p))%" : String(format: "%.1f%%", p)
    }
}
