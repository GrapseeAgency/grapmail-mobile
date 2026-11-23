import SwiftUI
import MetricKit

/// The server's app configuration (backend lib/app-config.ts): kill switches, the
/// minimum supported version, a notice and store links. Read at launch and when the
/// app returns to the front (at most every 15 minutes), cached for an offline start.
/// The server enforces the switches; the app reads them to explain.
@MainActor
final class RemoteConfig: ObservableObject {
    static let shared = RemoteConfig()

    struct Notice: Equatable { let text: String; let warning: Bool }

    @Published private(set) var minIOS = "0"
    @Published private(set) var killSwitches: [String: Bool] = [:]
    @Published private(set) var notice: Notice?
    @Published private(set) var flags: [String: Bool] = [:]
    @Published private(set) var storeURL: URL?

    private var lastFetch = Date.distantPast
    private let cacheKey = "gm.remoteConfig"

    private init() {
        if let data = UserDefaults.standard.data(forKey: cacheKey) { apply(data) }
    }

    private func apply(_ data: Data) {
        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
        let mv = obj["minVersion"] as? [String: Any] ?? [:]
        minIOS = mv["ios"] as? String ?? "0"
        killSwitches = obj["killSwitches"] as? [String: Bool] ?? [:]
        if let n = obj["notice"] as? [String: Any], let text = n["text"] as? String, !text.isEmpty {
            notice = Notice(text: text, warning: n["level"] as? String == "warning")
        } else {
            notice = nil
        }
        flags = obj["flags"] as? [String: Bool] ?? [:]
        let urls = obj["storeUrls"] as? [String: Any] ?? [:]
        storeURL = (urls["ios"] as? String).flatMap { $0.hasPrefix("https://") ? URL(string: $0) : nil }
    }

    func refresh(force: Bool = false) async {
        guard force || Date().timeIntervalSince(lastFetch) > 15 * 60 else { return }
        lastFetch = Date()
        guard let url = URL(string: "\(ApiClient.base)\(ApiClient.mailBase)/app-config") else { return }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        request.setValue(ApiClient.userAgent, forHTTPHeaderField: "User-Agent")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200,
              ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["success"] as? Bool == true
        else { return }
        apply(data)
        UserDefaults.standard.set(data, forKey: cacheKey)
    }

    func isPaused(_ feature: String) -> Bool { killSwitches[feature] == true }
    func flag(_ name: String, default value: Bool = false) -> Bool { flags[name] ?? value }

    static var appVersion: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0" }

    /// True when this version is older than the server's minimum: only the update screen shows.
    var updateRequired: Bool { Self.compare(Self.appVersion, minIOS) < 0 }

    static func compare(_ a: String, _ b: String) -> Int {
        let pa = a.split(separator: ".").map { Int($0) ?? 0 }
        let pb = b.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(pa.count, pb.count) {
            let d = (i < pa.count ? pa[i] : 0) - (i < pb.count ? pb[i] : 0)
            if d != 0 { return d < 0 ? -1 : 1 }
        }
        return 0
    }
}

/// Shown instead of the app below the server's minimum version.
struct RequiredUpdateView: View {
    @ObservedObject var config = RemoteConfig.shared
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "arrow.down.app")
                .font(.system(size: 48)).foregroundColor(GM.primarySolid)
                .accessibilityHidden(true)
            Text("Update GrapMail to continue")
                .font(.system(size: 20, weight: .bold)).foregroundColor(GM.textPrimary)
                .multilineTextAlignment(.center)
            Text("This version is no longer supported. Your mail is safe; the new version opens it right where you left it.")
                .font(.system(size: 14)).foregroundColor(GM.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            GradientButton(title: "Update now") {
                openURL(config.storeURL ?? URL(string: "https://apps.apple.com/search?term=GrapMail")!)
            }
            .padding(.top, 8)
            Spacer()
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(GM.bg.ignoresSafeArea())
        .accessibilityIdentifier("gm-update-required")
    }
}

/// The server's notice above every screen, until dismissed.
struct ServerNoticeBanner: View {
    let notice: RemoteConfig.Notice
    let onDismiss: () -> Void
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "info.circle")
                .foregroundColor(notice.warning ? GM.warnInk : GM.primarySolid)
                .accessibilityHidden(true)
            Text(notice.text)
                .font(.system(size: 13))
                .foregroundColor(notice.warning ? GM.warnInk : GM.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 4)
            Button("OK", action: onDismiss)
                .font(.system(size: 13, weight: .semibold))
                .frame(minWidth: 44, minHeight: 44)
        }
        .padding(.horizontal, 12).padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous)
            .fill(notice.warning ? GM.warnChipBg : GM.softFill))
        .padding(.horizontal, 12)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("gm-server-notice")
    }
}

/// Crash, hang and session reports to GrapMail's own server (backend
/// routes/grapmail-client.ts). Crashes and hangs come from MetricKit, which hands the
/// app the system's own diagnostics at the next launch; Objective-C exceptions are
/// also caught as they happen and sent next launch. Platform, version, device model
/// and the stack only, never the account or mail. Settings → "Send crash reports".
final class CrashReporter: NSObject, MXMetricManagerSubscriber {
    static let shared = CrashReporter()
    private static let enabledKey = "gm.crashReports"
    private static let pendingKey = "gm.crashPending"

    static var enabled: Bool {
        get { UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? true }
        set {
            UserDefaults.standard.set(newValue, forKey: enabledKey)
            if !newValue { UserDefaults.standard.removeObject(forKey: pendingKey) }
        }
    }

    func start() {
        MXMetricManager.shared.add(self)
        NSSetUncaughtExceptionHandler { exception in
            guard CrashReporter.enabled else { return }
            let report = CrashReporter.base("crash").merging([
                "message": "\(exception.name.rawValue): \(exception.reason ?? "")",
                "stack": exception.callStackSymbols.joined(separator: "\n"),
            ]) { $1 }
            if let data = try? JSONSerialization.data(withJSONObject: report) {
                UserDefaults.standard.set(data, forKey: CrashReporter.pendingKey)
            }
        }
        Task.detached(priority: .utility) {
            var reports: [[String: Any]] = [CrashReporter.base("session")]
            if let data = UserDefaults.standard.data(forKey: CrashReporter.pendingKey),
               let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] { reports.append(obj) }
            if await CrashReporter.send(reports) { UserDefaults.standard.removeObject(forKey: CrashReporter.pendingKey) }
        }
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        guard CrashReporter.enabled else { return }
        var reports: [[String: Any]] = []
        for payload in payloads {
            for crash in payload.crashDiagnostics ?? [] {
                reports.append(Self.diagnostic("crash", crash.jsonRepresentation(),
                    message: "Crash: \(crash.exceptionType?.stringValue ?? "") signal \(crash.signal?.stringValue ?? "")"))
            }
            for hang in payload.hangDiagnostics ?? [] {
                reports.append(Self.diagnostic("hang", hang.jsonRepresentation(),
                    message: "Hang: \(hang.hangDuration.formatted())"))
            }
        }
        guard !reports.isEmpty else { return }
        Task.detached(priority: .utility) { _ = await CrashReporter.send(Array(reports.prefix(19))) }
    }

    private static func diagnostic(_ kind: String, _ json: Data, message: String) -> [String: Any] {
        base(kind).merging(["message": message, "stack": String(decoding: json.prefix(16000), as: UTF8.self)]) { $1 }
    }

    static func base(_ kind: String) -> [String: Any] {
        var info = utsname()
        uname(&info)
        let model = withUnsafePointer(to: &info.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) }
        }
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "0"
        return [
            "platform": "ios",
            "appVersion": "\(RemoteConfig.appVersion) (\(build))",
            "osVersion": "iOS \(ProcessInfo.processInfo.operatingSystemVersionString)",
            "device": model,
            "kind": kind,
            "occurredAt": ISO8601DateFormatter().string(from: Date()),
        ]
    }

    static func send(_ reports: [[String: Any]]) async -> Bool {
        guard enabled, let url = URL(string: "\(ApiClient.base)\(ApiClient.mailBase)/client-reports"),
              let body = try? JSONSerialization.data(withJSONObject: ["reports": reports]) else { return false }
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        guard let (_, response) = try? await URLSession.shared.data(for: request) else { return false }
        return (response as? HTTPURLResponse).map { $0.statusCode < 400 } ?? false
    }
}
