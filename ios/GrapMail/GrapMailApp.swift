import SwiftUI

@main
struct GrapMailApp: App {
    /// The UIKit hooks this app needs and the `App` protocol does not have:
    /// background-refresh registration, home-screen shortcut delivery, and the
    /// reschedule when the app goes away. See `AppDelegate`.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var accountGeneration = UUID()
    // The launch splash: the mark and the brand's nine-slice "Grapsee" shimmer,
    // shown once per launch while the first mailbox read settles underneath.
    @State private var splashVisible = true

    init() {
        // Started before the first view so the very first mailbox read knows
        // whether it is being asked over a path that exists. `nil` until the
        // monitor's first callback, which is why the banner waits for a real
        // reading rather than announcing "offline" at launch.
        NetworkMonitor.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            ZStack {
                RootView()
                    .id(accountGeneration)
                    // Discard all account-owned view models after confirmed deletion.
                    .onReceive(NotificationCenter.default.publisher(for: .gmAccountDeleted)) { _ in
                        accountGeneration = UUID()
                    }
                if splashVisible {
                    LaunchSplashView(visible: $splashVisible)
                        .transition(.opacity)
                }
            }
        }
    }
}
