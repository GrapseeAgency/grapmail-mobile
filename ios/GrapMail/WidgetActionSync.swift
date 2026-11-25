import Foundation

/// Sends the widget's queued taps (Mark as read, Archive, Star — see
/// `WidgetShared.PendingAction`) through the app's normal authenticated path.
///
/// The widget extension holds no session (no Keychain access group, no pinned
/// session, no device key), so it only queues; this is where a tap becomes a
/// `PATCH /message/:id/:action`, exactly as the notification's Archive / Mark
/// as read buttons send it (AppDelegate in AppIntegration.swift):
///   * the bearer is `ApiClient.pushBearer(for:)` for the account the widget
///     showed — never the active account by assumption, and a tap whose
///     account has since been signed out is dropped, not sent as anyone else;
///   * a refusal the server decided (`Outbox.isFinalRefusal`, e.g. 404 for a
///     message already deleted) drops the tap; no answer, 408/429/5xx keep it
///     for the next drain; a tap older than `Outbox.maxAge` is dropped;
///   * every action is idempotent on the server (read twice is read), so a
///     tap whose answer was lost is safe to send again.
///
/// Drained from `Outbox.flush()` (launch and every time the network returns)
/// and when the app comes to the foreground (GrapMailApp).
actor WidgetActionSync {
    static let shared = WidgetActionSync()
    /// A tap that has failed this many drains in a row is given up on; the
    /// next read corrects the widget either way.
    static let maxAttempts = 20

    private var draining = false

    func drain() async {
        guard !draining else { return }
        draining = true
        defer { draining = false }
        let pending = WidgetShared.pendingActions()
        guard !pending.isEmpty else { return }

        var done = Set<String>()
        var attempts: [String: Int] = [:]
        var sent: [WidgetShared.PendingAction] = []
        for action in pending {
            if Outbox.expired(createdAt: action.createdAt) || action.attempts >= Self.maxAttempts {
                done.insert(action.id)
                continue
            }
            // Signed out of that account since the tap: nobody may send it.
            guard SessionStore.account(action.account) != nil else {
                done.insert(action.id)
                continue
            }
            guard let token = await ApiClient.pushBearer(for: action.account) else {
                attempts[action.id] = action.attempts + 1
                continue
            }
            do {
                _ = try await ApiClient.mailAction(token: token, id: action.messageId, action: Self.mailAction(action.kind))
                done.insert(action.id)
                sent.append(action)
            } catch let ApiError.http(status, _, _) where Outbox.isFinalRefusal(status: status) {
                done.insert(action.id)
            } catch {
                attempts[action.id] = action.attempts + 1
            }
        }
        WidgetShared.finishPending(done: done, attempts: attempts)

        // The app's own cache learns what the server now knows, for the
        // account on screen (the same updates the notification actions make).
        let active = SessionStore.address ?? ""
        let generation = SessionStore.generation
        let mine = sent.filter { $0.account.caseInsensitiveCompare(active) == .orderedSame }
        if !mine.isEmpty {
            await MailStore.shared.invalidate(.inbox, generation: generation)
            for action in mine {
                switch action.kind {
                case .read:
                    await MailStore.shared.patchFlags(id: action.messageId, read: true, generation: generation)
                case .archive:
                    await MailStore.shared.invalidate(.archive, generation: generation)
                case .star, .unstar:
                    await MailStore.shared.patchFlags(id: action.messageId, starred: action.kind == .star, generation: generation)
                    await MailStore.shared.invalidate(.starred, generation: generation)
                }
            }
        }
        if !done.isEmpty { WidgetBridge.reload() }
    }

    private static func mailAction(_ kind: WidgetShared.PendingAction.Kind) -> MailAction {
        switch kind {
        case .read: return .read
        case .archive: return .archive
        case .star: return .star
        case .unstar: return .unstar
        }
    }
}
