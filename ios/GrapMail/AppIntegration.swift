import BackgroundTasks
import Foundation
import Intents
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
            Task { @MainActor in
                guard let self, self.online != available else { return }
                self.online = available
            }
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
    struct MailTarget: Equatable {
        let messageId: String
        let threadId: String?
        let account: String
        let reply: Bool
        let more: Bool
    }
    @Published var pendingMail: MailTarget?

    func receive(_ type: String) -> Bool {
        guard type == ShortcutRouter.composeType else { return false }
        pendingCompose = true
        return true
    }

    func consume() { pendingCompose = false }

    /// The same door a quick action knocks on, opened for App Intents and
    /// Siri: one flag, one consumer, so a draft asked for by voice and a
    /// draft asked for by long-press arrive at the same place.
    func requestCompose() { pendingCompose = true }
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
    private static let newestMessageKey = "grapmail.poll.inboxHead"
    static let channelName = "New mail"
    static let actionCategory = "grapmail.message"
    @MainActor private static var activeGeneration: UUID?

    static let didReadStats = Notification.Name("grapmail.mailPoll.didReadStats")

    struct PollResult {
        let generation: UUID
        let stats: MailStatsResponse
    }

    @MainActor
    static func runOnce(announceArrivals: Bool = true) async -> Bool {
        guard let token = SessionStore.token else { return false }
        let generation = SessionStore.generation
        guard activeGeneration != generation else { return false }
        activeGeneration = generation
        defer {
            if activeGeneration == generation { activeGeneration = nil }
        }
        let stats: MailStatsResponse
        do {
            stats = try await ApiClient.mailStats(token)
        } catch {
            // A refused read is not a quiet success: the task ends, nothing is
            // announced, and the next scheduled run asks again. No notification
            // here ever says "no new mail" — the app cannot know that from a
            // failure.
            return false
        }
        // A suspended read must not publish one account's result after sign-out.
        guard !Task.isCancelled, generation == SessionStore.generation else { return false }
        NotificationCenter.default.post(name: didReadStats, object: PollResult(generation: generation, stats: stats))
        guard let count = stats.inbox?.unread else { return false }
        let defaults = UserDefaults.standard
        let didSee = defaults.object(forKey: lastSeenKey) != nil
        let previous = defaults.integer(forKey: lastSeenKey)
        let priorHead = defaults.string(forKey: newestMessageKey)
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard !Task.isCancelled, generation == SessionStore.generation else { return false }
        let canNotify = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
        let recent: [MailMessage]?
        if canNotify {
            recent = (try? await ApiClient.mailInbox(token: token, box: .inbox, pageSize: 12))?
                .messages.filter { !$0.id.isEmpty }
        } else {
            recent = nil
        }
        guard !Task.isCancelled, generation == SessionStore.generation else { return false }
        defaults.set(count, forKey: lastSeenKey)
        if let recent { defaults.set(recent.first?.id ?? "", forKey: newestMessageKey) }
        else { defaults.removeObject(forKey: newestMessageKey) }
        // The widget and Lock Screen card show what this read actually saw -
        // the same numbers, at the moment they were true. Written before any
        // early return so a read that announces nothing still corrects it.
        WidgetBridge.update(unread: count, account: SessionStore.address, latest: recent?.first, recent: recent ?? [], signedIn: true)
        guard didSee, canNotify else { return false }
        guard announceArrivals, !UserDefaults.standard.bool(forKey: "gm.focusQuiet") else { return true }
        // Keep this device's copy of the notification rules current (at most every
        // six hours), so a rule changed on another device applies here too.
        await NotificationPolicy.refreshIfStale(account: SessionStore.address, token: token)
        guard !Task.isCancelled, generation == SessionStore.generation else { return false }

        let arrivals: [MailMessage]?
        if let priorHead, let recent, priorHead.isEmpty || recent.contains(where: { $0.id == priorHead }) {
            arrivals = recent.prefix(while: { $0.id != priorHead }).filter { !$0.isRead && $0.direction != "outbound" }
        } else {
            arrivals = nil
        }
        let grew = arrivals?.count ?? (count - previous)
        guard grew > 0 else { return false }
        let message = grew == 1 ? arrivals?.first : nil
        var previewFile: URL?
        if !UserDefaults.standard.bool(forKey: "mailGenericPreviews"),
           let id = message?.id,
           let details = await cacheNotificationMessage(id: id, token: token, generation: generation),
           !details.isE2eEncrypted,
           let item = details.attachments.first(where: {
               !$0.id.isEmpty && ["image/jpeg", "image/png", "image/webp", "image/gif"].contains($0.mimeType ?? "") &&
               (1...2_097_152).contains($0.sizeBytes ?? 0)
           }) {
            previewFile = await notificationImage(token: token, id: item.id)
        }
        defer { if let previewFile { try? FileManager.default.removeItem(at: previewFile) } }
        guard !Task.isCancelled, generation == SessionStore.generation else { return false }
        return await announce(grew: grew, total: count, message: message, previewFile: previewFile, generation: generation)
    }

    @MainActor
    static func cacheNotificationMessage(id: String, token: String, generation: UUID) async -> MailMessageFull? {
        guard let (message, payload) = try? await ApiClient.notificationMessage(token: token, id: id),
              !Task.isCancelled, generation == SessionStore.generation else { return nil }
        await MailStore.shared.saveNotificationMessage(message, payload: payload, generation: generation)
        return message
    }

    /// Hidden previews: "New mail", no sender, subject, photo or inline reply. A tap still opens it.
    @MainActor
    private static func announceGeneric(message: MailMessage, account: String?, generation: UUID) async -> Bool {
        let content = UNMutableNotificationContent()
        content.title = "GrapMail"
        content.body = "New mail"
        content.sound = .default
        if let account, !account.isEmpty {
            content.subtitle = account
            content.userInfo = ["messageId": message.id, "threadId": message.threadId ?? "", "account": account]
        }
        guard !Task.isCancelled, generation == SessionStore.generation else { return false }
        do {
            try await UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
            return true
        } catch {
            return false
        }
    }

    @MainActor
    private static func announce(grew: Int, total: Int, message: MailMessage?, previewFile: URL?, generation: UUID) async -> Bool {
        // The account's notification rules (NotificationPolicy.swift): the same
        // decision the server makes before it pushes, for the alert this app posts.
        let account = SessionStore.address
        if let message {
            let decision = NotificationPolicy.decide(
                account: account, fromAddress: message.from?.address,
                threadKey: message.threadId?.nilIfBlank() ?? message.id)
            guard decision.notify else { return false }
            if decision.generic { return await announceGeneric(message: message, account: account, generation: generation) }
        } else if !NotificationPolicy.allowsSummary(account: account) {
            return false
        }
        let content = UNMutableNotificationContent()
        content.title = "\(grew) new message\(grew == 1 ? "" : "s")"
        content.body = "\(total) unread in the inbox"
        let encrypted = message?.isE2eEncrypted == true
        let privatePreview = encrypted || UserDefaults.standard.bool(forKey: "mailGenericPreviews")
        if encrypted { content.title = "New encrypted message" }
        content.sound = .default
        if !privatePreview, let previewFile, let attachment = try? UNNotificationAttachment(identifier: "image", url: previewFile) {
            content.attachments = [attachment]
        }
        if let message, let account = SessionStore.address, !account.isEmpty {
            if !privatePreview { content.subtitle = account }
            content.categoryIdentifier = actionCategory
            // The reply path needs the sender's address and the subject at the
            // moment the person answers; both are carried here so the inline
            // send never has to fetch the message back. `e2e` marks a sealed
            // message, where replying inline would send plaintext.
            content.userInfo = ["messageId": message.id, "threadId": message.threadId ?? "", "account": account,
                                "sender": privatePreview ? "" : (message.from?.address ?? ""),
                                "subject": privatePreview ? "" : (message.subject ?? ""),
                                "e2e": message.isE2eEncrypted ? "1" : "0",
                                "privatePreview": privatePreview ? "1" : "0"]
        }

        var notification: UNNotificationContent = content
        if !privatePreview, let message, let sender = message.from?.display.nilIfBlank() {
            content.title = sender
            let subject = message.subject?.nilIfBlank() ?? "(no subject)"
            let preview = message.snippet?.nilIfBlank()
            content.body = [subject, preview].compactMap { $0 }.joined(separator: "\n")
            content.threadIdentifier = message.threadId ?? message.id
            if let address = message.from?.address?.nilIfBlank(),
               let avatar = await senderAvatar(sender, address: address) {
                let person = INPerson(
                    personHandle: INPersonHandle(value: address, type: .emailAddress),
                    nameComponents: nil, displayName: sender, image: avatar,
                    contactIdentifier: nil, customIdentifier: address
                )
                let intent = INSendMessageIntent(
                    recipients: nil, outgoingMessageType: .outgoingMessageText,
                    content: content.body, speakableGroupName: nil, conversationIdentifier: content.threadIdentifier,
                    serviceName: nil, sender: person, attachments: nil
                )
                let interaction = INInteraction(intent: intent, response: nil)
                interaction.direction = .incoming
                try? await interaction.donate()
                if let updated = try? content.updating(from: intent) { notification = updated }
            }
        }
        guard !Task.isCancelled, generation == SessionStore.generation else { return false }
        do {
            try await UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: UUID().uuidString, content: notification, trigger: nil)
            )
            return true
        } catch {
            return false
        }
    }

    @MainActor
    private static func notificationImage(token: String, id: String) async -> URL? {
        // The file read, decode and JPEG encode run detached — this path is a
        // background task, and burning BGTask budget on the main actor for an
        // image that ends up inside a notification is waste. Main only hands
        // the finished URL to the notification.
        let work = Task.detached { () -> URL? in
            guard let file = try? await ApiClient.mailAttachmentFile(token: token, id: id) else { return nil }
            defer { try? FileManager.default.removeItem(at: file) }
            guard let count = try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                  (1...2_097_152).contains(count), let data = try? Data(contentsOf: file),
                  let image = await AvatarThumbnail.decode(data, maxSize: 768),
                  let jpeg = UIImage(cgImage: image).jpegData(compressionQuality: 0.85) else { return nil }
            let preview = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
            do {
                try jpeg.write(to: preview)
                return preview
            } catch {
                return nil
            }
        }
        return await work.value
    }

    @MainActor
    private static func senderAvatar(_ sender: String, address: String) async -> INImage? {
        // The oval render + PNG encode run detached; main only awaits the image.
        let work = Task.detached { () -> INImage? in
            let size: CGFloat = 128
            let image = UIGraphicsImageRenderer(size: CGSize(width: size, height: size)).image { _ in
                UIColor(MailUI.tint(address: address, display: sender)).setFill()
                UIBezierPath(ovalIn: CGRect(x: 0, y: 0, width: size, height: size)).fill()
                let initial = String(sender.prefix(1)).uppercased() as NSString
                let attributes: [NSAttributedString.Key: Any] = [
                    .font: UIFont.boldSystemFont(ofSize: 54), .foregroundColor: UIColor.white,
                ]
                let bounds = initial.size(withAttributes: attributes)
                initial.draw(at: CGPoint(x: (size - bounds.width) / 2, y: (size - bounds.height) / 2),
                             withAttributes: attributes)
            }
            return image.pngData().map { INImage(imageData: $0) }
        }
        return await work.value
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
        UserDefaults.standard.removeObject(forKey: newestMessageKey)
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
        let address = SessionStore.address
        let registration = address.flatMap { PreviewCredential.read(mailbox: $0) }
        if let address {
            PreviewCredential.clear(mailbox: address)
            // The per-account mute record goes with the registration: the
            // device row the DELETE removes no longer exists, and a stored
            // mute against it is a record of nothing.
            AccountPushPrefs.clear(mailbox: address)
            let center = UNUserNotificationCenter.current()
            center.getDeliveredNotifications { notifications in
                let removed = notifications.filter {
                    let info = $0.request.content.userInfo
                    let owner = info["mailbox"] as? String ?? info["account"] as? String
                    return owner?.caseInsensitiveCompare(address) == .orderedSame
                }
                center.removeDeliveredNotifications(withIdentifiers: removed.map { $0.request.identifier })
            }
        }
        let bearer = SessionStore.token
        let token = deviceToken
        deviceToken = nil
        registrationFailed = false
        registerTask?.cancel()
        registerTask = nil
        Task {
            if let registration, await ApiClient.retireNotificationRegistration(registration) { return }
            if let bearer, let token {
                do {
                    try await ApiClient.unregisterPush(token: bearer, pushToken: Self.hex(token))
                } catch {
                    print("Push deregistration not confirmed.")
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

    func refreshPreferences() { postRegistration() }

    private func postRegistration() {
        guard let token = deviceToken else { return }
        let hex = Self.hex(token)
        registerTask?.cancel()
        registerTask = Task {
            for account in SessionStore.accounts() {
                guard !Task.isCancelled else { return }
                guard let bearer = await ApiClient.pushBearer(for: account.address) else { continue }
                do {
                    let secret = try await ApiClient.registerPush(
                        token: bearer, pushToken: hex,
                        genericPreview: UserDefaults.standard.object(forKey: "mailGenericPreviews") as? Bool,
                        mailbox: account.address
                    )
                    if SessionStore.account(account.address) == nil {
                        try? await ApiClient.unregisterPush(token: bearer, pushToken: hex)
                    } else if !Task.isCancelled, let secret,
                              !PreviewCredential.store(secret, mailbox: account.address) {
                        print("Push preview access could not be saved.")
                    }
                } catch {
                    if !Task.isCancelled { print("Push registration failed for a saved account.") }
                }
            }
        }
    }

    /// The token travels as lowercase hex — the form the route reads.
    private static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
}

// ── security alerts ─────────────────────────────────────────────────────────

/// The APNs surface for account-security alerts. The backend sends them as
/// mail pushes are sent, with two differences the app routes on: the payload's
/// top-level custom key `type` reads "security.alert", and `aps.category` reads
/// "grapmail.security" — the category the OS renders the two action buttons
/// from. The alert text itself is the server's; this app adds only the answers
/// the buttons promise: "This was me" acknowledges the event in the background,
/// and "Secure account" (or a plain tap on the banner) deep links into the
/// security hub. Mail notification behavior above is deliberately untouched:
/// a security alert carries no messageId, so it never reaches the mail paths.
enum SecurityPush {
    static let category = "grapmail.security"
    static let payloadType = "security.alert"
    static let ackAction = "ack"
    static let secureAction = "secure"

    /// The payload's own discriminator. Not the category: a notification the
    /// app re-renders locally would carry a category but no custom `type`, and
    /// this must be true only of what the backend actually sent.
    static func isSecurityAlert(_ userInfo: [AnyHashable: Any]) -> Bool {
        (userInfo["type"] as? String) == payloadType
    }

    /// One category with the two actions the alert offers. "This was me" runs
    /// in the background (no app launch); "Secure account" brings the app
    /// forward, which is the point — securing the account happens in it.
    static var registeredCategory: UNNotificationCategory {
        UNNotificationCategory(identifier: category, actions: [
            UNNotificationAction(identifier: ackAction, title: "This was me"),
            UNNotificationAction(identifier: secureAction, title: "Secure account", options: [.foreground]),
        ], intentIdentifiers: [], options: [])
    }

    /// A security alert's response, whichever button (or tap) produced it.
    static func handle(response: UNNotificationResponse, completion: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let eventId = (info["eventId"] as? String) ?? ""
        let action = response.actionIdentifier
        if action == ackAction {
            Task {
                defer { completion() }
                await acknowledge(eventId: eventId)
            }
            return
        }
        if action == secureAction || action == UNNotificationDefaultActionIdentifier {
            // Same door as a mail push: the notification posts, the signed-in
            // stack consumes it. The hub refetches on entry, so no event data
            // needs to ride along.
            DispatchQueue.main.async {
                NotificationCenter.default.post(name: .gmOpenAccountSecurity, object: nil)
            }
            completion()
            return
        }
        completion()
    }

    /// "This was me": a write against the stored session, refreshed through
    /// the same bearer path push registration uses, so a stale token does not
    /// turn an honest acknowledgement into a silent failure. A refused ack
    /// (already acknowledged, event gone) stays quiet — the write is the goal,
    /// and there is no screen to report to from a notification action.
    static func acknowledge(eventId: String) async {
        guard !eventId.isEmpty else { return }
        guard let address = SessionStore.address, let token = await ApiClient.pushBearer(for: address) else { return }
        let generation = SessionStore.generation
        guard generation == SessionStore.generation else { return }
        _ = try? await ApiClient.acknowledgeSecurityEvent(token: token, id: eventId)
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
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.setNotificationCategories([
            UNNotificationCategory(identifier: MailPoll.actionCategory, actions: [
                UNNotificationAction(identifier: "archive", title: "Archive"),
                UNNotificationAction(identifier: "read", title: "Mark as read"),
                // Inline reply: the person answers without the app ever coming
                // forward. `reply.text` is the text field itself; `reply` stays
                // as the door into the full composer - the option the encrypted
                // path falls back to.
                UNTextInputNotificationAction(identifier: "reply.text", title: "Reply", options: [],
                                              textInputButtonTitle: "Send", textInputPlaceholder: "Reply…"),
                UNNotificationAction(identifier: "reply", title: "Reply in app", options: [.foreground]),
                UNNotificationAction(identifier: "more", title: "More", options: [.foreground]),
            ], intentIdentifiers: [], options: []),
            // Security alerts are their own category: the OS renders "This was
            // me" / "Secure account" from it, and the response handler below
            // routes by it. setNotificationCategories REPLACES the set, so both
            // categories live in this one call — registering the security one
            // separately would wipe the mail actions.
            SecurityPush.registeredCategory,
        ])
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
        // Trusted-device enrollment rides the same session-change signal the
        // push registration does: once per (account, install), after sign-in.
        TrustedDeviceManager.shared.observeSessions()
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
        // A security alert is not mail: APNs has already presented it, the app
        // holds nothing to cache for it, and the mail baseline must not move
        // because of it. The wake carries no work — complete without touching
        // the poll so the unread count cannot be "refreshed" into announcing
        // mail nobody received a notification for.
        if SecurityPush.isSecurityAlert(userInfo) {
            completionHandler(.newData)
            return
        }
        // An alert has already been presented by APNs. Refresh the mailbox
        // baseline in that case so the same arrival does not alert twice.
        let hasAlert = (userInfo["aps"] as? [String: Any])?["alert"] != nil
        let account = userInfo["mailbox"] as? String
        let id = userInfo["messageId"] as? String
        Task {
            if let account, account.caseInsensitiveCompare(SessionStore.address ?? "") == .orderedSame,
               let id, !id.isEmpty, let token = SessionStore.token {
                _ = await MailPoll.cacheNotificationMessage(id: id, token: token, generation: SessionStore.generation)
            }
            completionHandler(await MailPoll.runOnce(announceArrivals: !hasAlert) ? .newData : .noData)
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let request = notification.request
        let info = request.content.userInfo
        let account = info["mailbox"] as? String ?? info["account"] as? String
        guard account.map({ SessionStore.account($0) != nil }) ?? (SessionStore.token != nil) else {
            completionHandler([])
            return
        }
        if request.trigger is UNPushNotificationTrigger,
           UserDefaults.standard.bool(forKey: "mailGenericPreviews"),
           info["messageId"] != nil, info["privatePreview"] as? String != "1" {
            completionHandler([])
            return
        }
        completionHandler([.banner, .sound, .badge])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let request = response.notification.request
        let info = request.content.userInfo
        // Security alerts route by their own category before the mail guard:
        // the payload carries no mailbox or messageId, so the mail path would
        // drop it silently and both buttons would do nothing.
        if request.content.categoryIdentifier == SecurityPush.category || SecurityPush.isSecurityAlert(info) {
            SecurityPush.handle(response: response) { completionHandler() }
            return
        }
        let isRemote = request.trigger is UNPushNotificationTrigger
        let accountKey = isRemote ? "mailbox" : "account"
        guard isRemote || request.content.categoryIdentifier == MailPoll.actionCategory,
              let account = info[accountKey] as? String, SessionStore.account(account) != nil,
              let messageId = info["messageId"] as? String, !messageId.isEmpty else {
            completionHandler()
            return
        }
        let action = response.actionIdentifier
        if action == "archive" || action == "read" {
            Task {
                defer { completionHandler() }
                guard let token = await ApiClient.pushBearer(for: account) else { return }
                let generation = SessionStore.generation
                let kind: MailAction = action == "archive" ? .archive : .read
                if (try? await ApiClient.mailAction(token: token, id: messageId, action: kind)) != nil,
                   account.caseInsensitiveCompare(SessionStore.address ?? "") == .orderedSame,
                   generation == SessionStore.generation {
                    await MailStore.shared.invalidate(.inbox, generation: generation)
                    if kind == .read {
                        await MailStore.shared.patchFlags(id: messageId, read: true, generation: generation)
                    } else {
                        await MailStore.shared.invalidate(.archive, generation: generation)
                    }
                }
            }
            return
        }
        if action == "reply.text" {
            // Inline reply. Sent through the same authenticated endpoint the
            // composer uses, threading by the message id that produced the
            // notification. Encrypted messages and announcements with no
            // sender fall back to the composer instead of guessing.
            let text = ((response as? UNTextInputNotificationResponse)?.userText ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let sender = (info["sender"] as? String)?.nilIfBlank()
            let encrypted = (info["e2e"] as? String) == "1"
            if !text.isEmpty, !encrypted, let sender {
                Task {
                    defer { completionHandler() }
                    guard let token = await ApiClient.pushBearer(for: account) else { return }
                    let subject = (info["subject"] as? String)?.nilIfBlank()
                    let replySubject = subject.map { $0.lowercased().hasPrefix("re:") ? $0 : "Re: \($0)" } ?? ""
                    let request = MailSendRequest(to: [sender], subject: replySubject,
                                                  bodyText: text, inReplyTo: messageId)
                    if (try? await ApiClient.mailSend(token: token, request)) != nil {
                        await MailStore.shared.invalidate(.sent, generation: SessionStore.generation)
                    }
                }
                return
            }
            ShortcutRouter.shared.pendingMail = .init(messageId: messageId,
                threadId: (info["threadId"] as? String)?.nilIfBlank(), account: account, reply: true, more: false)
            completionHandler()
            return
        }
        if [UNNotificationDefaultActionIdentifier, "reply", "more"].contains(action) {
            ShortcutRouter.shared.pendingMail = .init(messageId: messageId,
                threadId: (info["threadId"] as? String)?.nilIfBlank(), account: account, reply: action == "reply", more: action == "more")
        }
        completionHandler()
    }
}
