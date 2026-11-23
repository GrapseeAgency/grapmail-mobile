import SwiftUI

@main
struct GrapMailApp: App {
    /// The UIKit hooks this app needs and the `App` protocol does not have:
    /// background-refresh registration, home-screen shortcut delivery, and the
    /// reschedule when the app goes away. See `AppDelegate`.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var accountGeneration = UUID()
    @StateObject private var remote = RemoteConfig.shared
    @State private var dismissedNotice: String?
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Started before the first view so the very first mailbox read knows
        // whether it is being asked over a path that exists. `nil` until the
        // monitor's first callback, which is why the banner waits for a real
        // reading rather than announcing "offline" at launch.
        NetworkMonitor.shared.start()
        // Unsent mail from an earlier launch goes out as soon as a network path
        // exists; the monitor fires once at start, so this also covers launch.
        Outbox.shared.start()
        // Crash, hang and session reports (RemoteConfig.swift).
        CrashReporter.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            Group {
                // Below the server's minimum version, only the update screen.
                if remote.updateRequired {
                    RequiredUpdateView()
                } else {
                    RootView()
                        .id(accountGeneration)
                        // Discard all account-owned view models after confirmed deletion.
                        .onReceive(NotificationCenter.default.publisher(for: .gmAccountDeleted)) { _ in
                            accountGeneration = UUID()
                        }
                        .safeAreaInset(edge: .top, spacing: 0) {
                            if let notice = remote.notice, notice.text != dismissedNotice {
                                ServerNoticeBanner(notice: notice) { dismissedNotice = notice.text }
                            }
                        }
                }
            }
            .task { await remote.refresh(force: true) }
            .onChange(of: scenePhase) { phase in
                if phase == .active { Task { await remote.refresh() } }
            }
        }
    }
}
