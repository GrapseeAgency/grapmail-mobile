import Foundation
import Network
import UserNotifications

/// Mail that was sent while the device had no connection.
///
/// Before this, Send with no network failed in the composer and the only copy
/// was whatever the last autosave reached. Now a send that fails for lack of a
/// connection (never one the server refused) is written here and goes out when
/// the device is back online: as soon as the network path returns while the app
/// runs, and at the next launch otherwise.
///
/// ── WHY IT CANNOT SEND TWICE ───────────────────────────────────────────────
/// Each item stores its own `Idempotency-Key`, minted once. Every retry, from
/// any launch, presents the same key, so a send whose answer was lost is
/// replayed by the server (lib/send-idempotency.ts) instead of sent again.
///
/// ── WHERE IT LIVES ─────────────────────────────────────────────────────────
/// Application Support, excluded from backup, written with
/// `.completeUntilFirstUserAuthentication` file protection: unsent emails are
/// as private as sent ones (MASVS-STORAGE-1).
struct OutboxItem: Codable, Identifiable {
    let id: String
    /// The account it was sent from. It is only ever sent as that account.
    let address: String
    /// The exact `POST /send` JSON body, encoded at Send time.
    let body: Data
    let draftId: String
    let idempotencyKey: String
    let createdAt: Date
    var attempts: Int = 0
}

actor Outbox {
    static let shared = Outbox()
    /// Mail is time-sensitive: an item older than this is handed back as a failure.
    static let maxAge: TimeInterval = 3 * 24 * 60 * 60

    private var flushing = false
    private nonisolated let monitor = NWPathMonitor()

    private var fileURL: URL? {
        guard var dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return nil }
        dir.appendPathComponent("Outbox", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? dir.setResourceValues(values)
        }
        return dir.appendingPathComponent("outbox.json")
    }

    private func readAll() -> [OutboxItem] {
        guard let url = fileURL, let data = try? Data(contentsOf: url) else { return [] }
        return (try? JSONDecoder().decode([OutboxItem].self, from: data)) ?? []
    }

    private func writeAll(_ items: [OutboxItem]) {
        guard let url = fileURL else { return }
        if items.isEmpty { try? FileManager.default.removeItem(at: url); return }
        guard let data = try? JSONEncoder().encode(items) else { return }
        try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    func items() -> [OutboxItem] { readAll() }

    @discardableResult
    func enqueue(address: String, body: Data, draftId: String) -> OutboxItem {
        let item = OutboxItem(
            id: UUID().uuidString,
            address: address.lowercased(),
            body: body,
            draftId: draftId,
            idempotencyKey: "o-" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased(),
            createdAt: Date()
        )
        writeAll(readAll() + [item])
        return item
    }

    /// Signing out of an account drops its unsent mail: nobody else may send it.
    func removeAll(for address: String) {
        writeAll(readAll().filter { $0.address != address.lowercased() })
    }

    /// Send what can be sent now. Only the signed-in account's items go; the
    /// others wait until that account is the active one again.
    func flush() async {
        guard !flushing else { return }
        flushing = true
        defer { flushing = false }
        let (address, token) = await MainActor.run { (SessionStore.address?.lowercased(), SessionStore.token) }
        guard let address, let token else { return }
        // Paused by the server (RemoteConfig.swift): the mail stays queued for the next flush.
        await RemoteConfig.shared.refresh()
        if await MainActor.run(body: { RemoteConfig.shared.isPaused("outbox") }) { return }
        var done = Set<String>()
        var attempts: [String: Int] = [:]
        var sentAny = false
        for item in readAll() where item.address == address {
            if Date().timeIntervalSince(item.createdAt) > Self.maxAge {
                await notifyFailed(item, reason: "It waited too long without a connection.")
                done.insert(item.id)
                continue
            }
            do {
                let r = try await ApiClient.mailSendQueued(token: token, body: item.body, idempotencyKey: item.idempotencyKey)
                let accepted = r.status == "sent" || r.status == "queued" || r.status == "delivered"
                done.insert(item.id)
                if !accepted {
                    await notifyFailed(item, reason: r.deliveryError ?? r.error ?? "The server did not accept it.")
                    continue
                }
                if !item.draftId.isEmpty { _ = try? await ApiClient.mailDeleteMessage(token: token, id: item.draftId) }
                sentAny = true
            } catch let ApiError.http(status, _, message) where (400..<500).contains(status) && status != 408 && status != 429 {
                // The server decided: retrying would get the same answer.
                done.insert(item.id)
                await notifyFailed(item, reason: message.isEmpty ? "The server refused it." : message)
            } catch {
                // No answer (offline, timeout) or a server error: try again later.
                attempts[item.id] = item.attempts + 1
            }
        }
        // Re-read: an item enqueued while this was sending is kept.
        writeAll(readAll().compactMap { item in
            if done.contains(item.id) { return nil }
            var copy = item
            if let n = attempts[item.id] { copy.attempts = n }
            return copy
        })
        if sentAny {
            await MainActor.run { NotificationCenter.default.post(name: .gmOutboxSent, object: nil) }
        }
    }

    /// Start watching the network: every time a path appears, flush.
    nonisolated func start() {
        monitor.pathUpdateHandler = { path in
            guard path.status == .satisfied else { return }
            Task { await Outbox.shared.flush() }
        }
        monitor.start(queue: DispatchQueue(label: "grapmail.outbox.monitor"))
    }

    private func notifyFailed(_ item: OutboxItem, reason: String) async {
        let subject = ((try? JSONSerialization.jsonObject(with: item.body)) as? [String: Any])?["subject"] as? String
        let content = UNMutableNotificationContent()
        content.title = "Not sent: " + ((subject?.isEmpty == false) ? subject! : "(no subject)")
        content.body = reason + (item.draftId.isEmpty ? "" : " It is still in Drafts.")
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
        try? await UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: "outbox-" + item.id, content: content, trigger: nil))
    }
}

extension Notification.Name {
    /// Mail left the Outbox; the Sent box is stale.
    static let gmOutboxSent = Notification.Name("gmOutboxSent")
}
