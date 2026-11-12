import BackgroundTasks
import Foundation
import Network
import SwiftUI
import UIKit
import UserNotifications

/// ── THE PLATFORM INTEGRATION LAYER ────────────────────────────────────────
/// Everything on this screen that is GrapMail-as-an-app rather than
/// GrapMail-as-a-website: the feel of an action landing, the knowledge that the
/// network went away, the home-screen shortcut, and the background read that
/// lets mail arrive to a notification instead to a screen the person has to
/// remember to open.
///
/// ── HOW MAIL ANNOUNCES ITSELF ────────────────────────────────────────────
/// Two paths, one rule: nothing is announced that the app did not verify with
/// its own authenticated read. The server can wake this app (the device's push
/// token is registered with the GrapMail backend at sign-in and removed at
/// sign-out), and `BGAppRefreshTask` is the fallback when no wake arrives —
/// but both land in the same `MailPoll.runOnce()`, which asks `GET /stats`
/// what it already answers and says that. A notification is a report of a
/// read this device actually made. It is NOT delivery, it is not instant, and
/// the wording below never claims either — the wake schedule is the server's
/// and the poll interval is the system's, not ours.

// ── haptics ─────────────────────────────────────────────────────────────────

/// The device's sense of an action having happened.
///
/// Three kinds, because the events are different: filing moves a message
/// somewhere, starring marks one, and a send either reached the server or did
/// not. A single generic tick on all of them is decoration — the person cannot
/// tell by feel whether the thing they did worked, which is exactly what a
/// haptic is for.
///
/// There is no system "reduce haptics" flag an app can read (Settings exposes
/// System Haptics without an API), so the honest gate is the one iOS gives:
/// VoiceOver already speaks these outcomes, and these calls stay silent when
/// the generator cannot run rather than forcing anything.
enum GMHaptics {
    /// A message moved boxes: archive, trash, spam, restore, snooze, and their
    /// reverses. Medium, because the mail leaves the screen.
    static func filing() {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }

    /// A star toggled. Light: nothing moved, a flag changed.
    static func marking() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    /// The server accepted a send.
    static func success() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    /// A write the server refused, or a read it would not answer.
    static func failure() {
        UINotificationFeedbackGenerator().notificationOccurred(.error)
    }
}

// ── network ─────────────────────────────────────────────────────────────────

/// Whether this device currently has a path to the internet.
///
/// The mailbox already tells the truth about a failed read ("this list may be
/// stale"), but it can only say it after asking. This says it before: the
/// person learns the network went away while the list stayed on screen, so a
/// stale list is not mistaken for a current one. `NWPathMonitor`, not
/// reachability-by-ping: the question is "is there an interface", and the
/// framework answers that without spending a request.
@MainActor
final class NetworkMonitor: ObservableObject {
    static let shared = NetworkMonitor()

    /// nil until the monitor's first update lands. "Unknown" is not "offline",
    /// and a banner that claims no network before the first reading is a lie
    /// about the device.
    @Published private(set) var online: Bool?

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.grapsee.grapmail.network")

    func start() {
        monitor.pathUpdateHandler = { [weak self] path in
            let available = path.status == .satisfied
            Task { @MainActor in self?.online = available }
        }
        monitor.start(queue: queue)
    }

    func stop() { monitor.cancel() }
}

// ── the home-screen shortcut ────────────────────────────────────────────────

/// The bridge between a static shortcut (declared in Info.plist, so it exists
/// before the app has ever run) and the navigation stack that can act on it.
///
/// A shortcut that launches the app and lands on the mailbox is a promise
/// broken: the person long-pressed "Compose mail". The request is held here
/// until a signed-in RootView can consume it, and it is consumed exactly once —
/// a stale request replayed on the next launch would open a composer nobody
/// asked for.
@MainActor
final class ShortcutRouter: ObservableObject {
    static let shared = ShortcutRouter()
    static let composeType = "com.grapsee.grapmail.compose"

    @Published private(set) var pendingCompose = false

    func receive(_ type: String) -> Bool {
        guard type == ShortcutRouter.composeType else { return false }
        pendingCompose = true
        return true
    }

    func consume() { pendingCompose = false }
}

// ── the background read ─────────────────────────────────────────────────────

/// One refresh: read the inbox badge, compare it to the last number this device
/// saw, and say something only when the comparison is real.
///
/// Every branch that cannot state a fact stays quiet rather than guessing:
/// no token (signed out), no answer (the read failed), no increase, and a first
/// ever read with nothing recorded before it. Announcing "12 unread" on a
/// number that was never compared to anything would tell the person the
/// mailbox grew when all that happened is that the app looked.
enum MailPoll {
    static let refreshIdentifier = "com.grapsee.grapmail.inbox-refresh"
    private static let lastSeenKey = "grapmail.poll.lastUnread"
    static let channelName = "New mail"

    @MainActor
    static func runOnce() async -> Bool {
        guard let token = SessionStore.token else { return false }
        let generation = SessionStore.generation
        let unread: Int?
        do {
            unread = try await ApiClient.mailStats(token).inbox?.unread
        } catch {
            // A refused read is not a quiet success: the task ends, nothing is
            // announced, and the next scheduled run asks again. No notification
            // here ever says "no new mail" — the app cannot know that from a
            // failure.
            return false
        }
        // A suspended read must not publish one account's result after sign-out.
        guard !Task.isCancelled, generation == SessionStore.generation else { return false }
        guard let count = unread else { return false }
        let defaults = UserDefaults.standard
        let didSee = defaults.object(forKey: lastSeenKey) != nil
        let previous = defaults.integer(forKey: lastSeenKey)
        defaults.set(count, forKey: lastSeenKey)
        guard didSee, count > previous else { return false }
        await announce(grew: count - previous, total: count, generation: generation)
        return true
    }

    @MainActor
    private static func announce(grew: Int, total: Int, generation: UUID) async {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard !Task.isCancelled, generation == SessionStore.generation,
              settings.authorizationStatus == .authorized
              || settings.authorizationStatus == .provisional else { return }
        let content = UNMutableNotificationContent()
        // Both numbers, because one is not enough to be true: "3 new" says what
        // arrived since the last read, and "12 unread in the inbox" says what
        // the server counted now. Neither says the mail was delivered, and
        // neither says it was read.
        content.title = "\(grew) new message\(grew == 1 ? "" : "s")"
        content.body = "\(total) unread in the inbox"
        content.sound = .default
        let request = UNNotificationRequest(identifier: UUID().uuidString,
                                            content: content, trigger: nil)
        try? await center.add(request)
    }

    /// Ask for the notification permission from the settings screen, on a tap.
    ///
    /// Not at first launch and not from here: `MailSettingsView` reads the
    /// system's real state, shows it, and calls `requestAuthorization` only
    /// when the person taps the row. A prompt that appears before anybody has
    /// used the app gets a no, and a background poll that has not been allowed
    /// stays quiet — `announce` checks, it does not assume.

    static func clear() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: refreshIdentifier)
        UserDefaults.standard.removeObject(forKey: lastSeenKey)
    }

    static func scheduleNext() {
        guard SessionStore.token != nil else { return }
        let request = BGAppRefreshTaskRequest(identifier: refreshIdentifier)
        // The system treats this as a minimum and runs it when the device's
        // own patterns allow — often less often than this. The app never
        // promises a minute, so no screen says "every 15 minutes"; the
        // schedule is the system's, and DATA-FLOW.md says so.
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}

// ── push registration ───────────────────────────────────────────────────────

/// The device's push token and its registration with the GrapMail backend.
///
/// The token is an opaque identifier Apple issues per app install; the backend
/// needs it (`POST /grapsee/grapmail/push/register`) to wake this device when
/// mail reaches the mailbox. The call carries the same bearer every other
/// authenticated call does, so a token is only ever attached to the account
/// that was signed in when it was issued — and sign-out removes it again
/// (`DELETE` the same path, same payload). The token is never logged, never
/// persisted (a fresh one arrives at every launch), and a refused write is
/// never reported anywhere as a working notification: BGAppRefresh polling
/// stays the fallback either way.
@MainActor
final class NotificationRegistration {
    static let shared = NotificationRegistration()
    private(set) var deviceToken: Data?
    private(set) var registrationFailed = false
    private var registerTask: Task<Void, Never>?

    func reconcile() async {
        let generation = SessionStore.generation
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        guard generation == SessionStore.generation else { return }
        guard SessionStore.token != nil,
              settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else {
            clear()
            return
        }
        UIApplication.shared.registerForRemoteNotifications()
    }

    func registered(_ token: Data) {
        guard SessionStore.token != nil else { clear(); return }
        deviceToken = token
        registrationFailed = false
        postRegistration()
    }

    func failed(_ error: Error) {
        deviceToken = nil
        registrationFailed = true
        // Honest log, nothing on screen: the system refused the registration
        // (no entitlement on the profile, no network, a simulator). The poll
        // still runs, so nothing promised was quietly dropped.
        print("Push registration refused: \(error.localizedDescription)")
    }

    /// Sign-out and account deletion. The authenticated DELETE has to run while
    /// this session's bearer is still in the Keychain, so `SessionStore.clear()`
    /// calls this BEFORE its own deletes. The device-side teardown stays
    /// deferred, so a sign-in that completed while clear() was running keeps
    /// its registration — its own reconcile re-registers anyway.
    func sessionEnding() {
        let bearer = SessionStore.token
        let token = deviceToken
        deviceToken = nil
        registrationFailed = false
        registerTask?.cancel()
        registerTask = nil
        if let bearer, let token {
            let hex = Self.hex(token)
            Task {
                do {
                    try await ApiClient.unregisterPush(token: bearer, pushToken: hex)
                } catch {
                    // Best effort: the bearer may already be dead server-side
                    // (account deletion invalidates tokens), which is not a
                    // defect of the sign-out path.
                    print("Push deregistration not confirmed: \(error.localizedDescription)")
                }
            }
        }
        DispatchQueue.main.async {
            if SessionStore.token == nil {
                UIApplication.shared.unregisterForRemoteNotifications()
                UIApplication.shared.applicationIconBadgeNumber = 0
                UNUserNotificationCenter.current().removeAllDeliveredNotifications()
                UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
            }
        }
    }

    /// Local-only teardown. Called from `reconcile` when there is no session or
    /// no permission: no bearer exists to authenticate a DELETE with, so only
    /// the device-side state goes.
    func clear() {
        deviceToken = nil
        registrationFailed = false
        registerTask?.cancel()
        registerTask = nil
        UIApplication.shared.unregisterForRemoteNotifications()
        UIApplication.shared.applicationIconBadgeNumber = 0
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
    }

    private func postRegistration() {
        guard let bearer = SessionStore.token, let token = deviceToken else { return }
        let hex = Self.hex(token)
        registerTask?.cancel()
        registerTask = Task {
            do {
                try await ApiClient.registerPush(token: bearer, pushToken: hex)
                // No screen reports this and no wording announces push: the
                // server accepted a token, and whether a wake ever arrives is
                // not observable here. The poll remains the fallback.
            } catch {
                if !Task.isCancelled {
                    print("Push registration not accepted: \(error.localizedDescription)")
                }
            }
        }
    }

    /// The token travels as lowercase hex — the form the route reads.
    private static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
}

// ── the app delegate ────────────────────────────────────────────────────────

/// The UIKit hooks SwiftUI's `App` lifecycle does not expose: background task
/// registration, shortcut delivery, and the reschedule on backgrounding.
@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    /// App-lifetime observer. The token is never removed: the delegate object
    /// lives for the whole process, and every future sign-in needs the hook.
    private var sessionChangeObserver: NSObjectProtocol?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        BGTaskScheduler.shared.register(forTaskWithIdentifier: MailPoll.refreshIdentifier, using: .main) { task in
            guard let refresh = task as? BGAppRefreshTask else { task.setTaskCompleted(success: false); return }
            MailPoll.scheduleNext()
            // Expiration cancels the URLSession task. Only the work closure completes
            // the BGTask, exactly once, including cancellation and unchanged inboxes.
            let work = Task { @MainActor in
                let done = await MailPoll.runOnce()
                refresh.setTaskCompleted(success: !Task.isCancelled && done)
            }
            refresh.expirationHandler = { work.cancel() }
        }
        Task { await NotificationRegistration.shared.reconcile() }
        // save() posts .gmSessionChanged: a fresh sign-in re-registers so the
        // push token attaches to the account that just signed in, not to the
        // previous one. Restore-at-launch is covered by the reconcile above.
        sessionChangeObserver = NotificationCenter.default.addObserver(
            forName: .gmSessionChanged, object: nil, queue: .main) { _ in
            Task { @MainActor in await NotificationRegistration.shared.reconcile() }
        }
        // A shortcut that cold-starts the app arrives here, before any view is
        // alive to route it, so it is parked and RootView picks it up.
        if let item = launchOptions?[.shortcutItem] as? UIApplicationShortcutItem {
            _ = ShortcutRouter.shared.receive(item.type)
        }
        return true
    }

    func application(_ application: UIApplication,
                     performActionFor shortcutItem: UIApplicationShortcutItem,
                     completionHandler: @escaping (Bool) -> Void) {
        completionHandler(ShortcutRouter.shared.receive(shortcutItem.type))
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        MailPoll.scheduleNext()
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        NotificationRegistration.shared.registered(deviceToken)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        NotificationRegistration.shared.failed(error)
    }

    func application(_ application: UIApplication,
                     didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        // A wake is a hint, never trusted mailbox data: whatever the payload
        // claims, the app re-reads the authenticated stats the poll uses, and
        // only that read's answer is announced.
        Task { completionHandler(await MailPoll.runOnce() ? .newData : .noData) }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler(SessionStore.token == nil ? [] : [.banner, .sound, .badge])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        // No backend payload/deep-link contract exists. Never navigate to an
        // untrusted message identifier or URL supplied by a notification.
        completionHandler()
    }
}
