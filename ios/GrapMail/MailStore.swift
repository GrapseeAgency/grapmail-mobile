import CryptoKit
import Foundation

/// The mailbox's process-lifetime cache: the last page of each box, the last
/// stats, the last labels, the label pairs the account carries, and the message
/// bodies that were opened.
///
/// WHY THIS EXISTS
/// ---------------
/// The hub's bug this sector inherits if it is not designed out: a screen that
/// re-fetches on every reappearance treats a *failed refresh* as a *failed load*,
/// blanking data it already had and disabling its own controls. On a mailbox the
/// symptom is worse than a spinner — returning from a thread to the list would
/// drop the list you were reading.
///
/// So the cache is the screen's starting value, and a refresh that fails leaves
/// what is already on screen on screen.
///
/// CLEARED ON SIGN-OUT, and only there. A cached mailbox left behind would be the
/// first thing the next account on this device sees — the same cross-account leak
/// `SecurityProfileCache` closes for the security verdicts.
actor MailStore {

    struct PageCache {
        var messages: [MailMessage] = []
        var total: Int = 0
        var pages: Int = 0
        /// The search term the pages belong to, so a search result is never
        /// mistaken for the unfiltered box.
        var query: String?
        var loadedAt: Date = Date()
    }

    static let shared = MailStore()
    private static let maxCachedSearches = 8
    private static let snapshotVersion = 1

    private struct DiskPerson: Codable, Sendable {
        let address: String?
        let name: String?

        init(_ person: MailPerson?) {
            address = person?.address
            name = person?.name
        }

        var live: MailPerson? {
            guard address != nil || name != nil else { return nil }
            var person = MailPerson()
            person.address = address
            person.name = name
            return person
        }
    }

    private struct DiskMessage: Codable, Sendable {
        let id: String
        let threadId: String?
        let direction: String?
        let status: String?
        let from: DiskPerson
        let to: [String]
        let cc: [String]
        let subject: String?
        let snippet: String?
        let isRead: Bool
        let isStarred: Bool
        let hasAttachments: Bool
        let isE2eEncrypted: Bool
        let createdAt: String?

        init(_ message: MailMessage) {
            id = message.id
            threadId = message.threadId
            direction = message.direction
            status = message.status
            from = DiskPerson(message.from)
            to = message.to
            cc = message.cc
            subject = message.subject
            snippet = message.snippet
            isRead = message.isRead
            isStarred = message.isStarred
            hasAttachments = message.hasAttachments
            isE2eEncrypted = message.isE2eEncrypted
            createdAt = message.createdAt
        }

        var live: MailMessage {
            var message = MailMessage()
            message.id = id
            message.threadId = threadId
            message.direction = direction
            message.status = status
            message.from = from.live
            message.to = to
            message.cc = cc
            message.subject = subject
            message.snippet = snippet
            message.isRead = isRead
            message.isStarred = isStarred
            message.hasAttachments = hasAttachments
            message.isE2eEncrypted = isE2eEncrypted
            message.createdAt = createdAt
            return message
        }
    }

    private struct DiskPage: Codable, Sendable {
        let messages: [DiskMessage]
        let total: Int
        let pages: Int
        let query: String?
        let loadedAt: Date

        init(_ page: PageCache) {
            messages = page.messages.map(DiskMessage.init)
            total = page.total
            pages = page.pages
            query = page.query
            loadedAt = page.loadedAt
        }

        var live: PageCache {
            PageCache(messages: messages.map(\.live), total: total, pages: pages,
                      query: query, loadedAt: loadedAt)
        }
    }

    private struct DiskSnapshot: Codable, Sendable {
        let version: Int
        let account: String
        let pages: [String: DiskPage]
    }

    private static var snapshotURL: URL? {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
            .appendingPathComponent("grapmail-mailbox-snapshot.json")
    }

    private var pages: [String: PageCache] = [:]
    private var statsValue: MailStatsResponse?
    private var labelsValue: [MailLabel]?
    private var aliasesValue: [MailAlias]?
    private var meValue: MailMeResponse?
    // Bounded thread metadata: bodies stay in the existing cost-limited NSCache;
    // this only remembers which ids make one conversation and its recent order.
    // Re-opening a thread after reading another message can therefore be a memory
    // hit without a second body store or an unbounded [threadId: payload] map.
    struct ThreadPage {
        let messages: [MailMessageFull]
        let nextCursor: String?
        let hasMore: Bool
    }
    private struct ThreadIndex {
        let messageIds: [String]
        let loadedAt: Date
        let nextCursor: String?
        let hasMore: Bool
    }
    private var threadMessageIds: [String: ThreadIndex] = [:]
    private var threadOrder: [String] = []
    private static let maxCachedThreads = 24
    private static let threadFreshness: TimeInterval = 30
    private final class CachedMessage {
        let message: MailMessageFull
        init(_ message: MailMessageFull) { self.message = message }
    }

    private let bodies: NSCache<NSString, CachedMessage> = {
        let cache = NSCache<NSString, CachedMessage>()
        cache.totalCostLimit = 8 * 1024 * 1024
        cache.countLimit = 96
        return cache
    }()

    struct IndexedAttachment {
        let attachment: MailAttachment
        let subject: String?
    }

    private var attachmentsByMessage: [String: [IndexedAttachment]] = [:]

    /// messageId → the label ids that message carries. nil means the account has
    /// never been asked; an EMPTY dictionary means it was asked and nothing is
    /// labelled.
    ///
    /// The two are not the same fact and the sheet must not merge them: reading
    /// “not yet asked” as “no labels” would offer to ADD a label the message
    /// already has, and would let a tap take one away on a guess.
    private var assignments: [String: Set<String>]?
    private var cacheGeneration = SessionStore.generation
    private var diskLoaded = false
    private var snapshotWriteTask: Task<Void, Never>?
    private var snapshotRevision = 0

    func awaitDiskReady() async {
        ensureSession()
        guard !diskLoaded else { return }
        diskLoaded = true
        guard let account = SessionStore.address?.lowercased(),
              let url = Self.snapshotURL else { return }
        let capturedGeneration = cacheGeneration
        let capturedAccount = account
        let snapshot: DiskSnapshot? = await Task.detached(priority: .userInitiated) {
            guard let data = try? Data(contentsOf: url) else { return nil }
            return try? JSONDecoder().decode(DiskSnapshot.self, from: data)
        }.value
        guard let snapshot, snapshot.version == Self.snapshotVersion,
              snapshot.account == capturedAccount,
              capturedGeneration == cacheGeneration,
              capturedGeneration == SessionStore.generation,
              SessionStore.address?.lowercased() == capturedAccount,
              pages.isEmpty else { return }
        pages = snapshot.pages.mapValues(\.live)
    }

    private func scheduleSnapshotWrite() {
        snapshotWriteTask?.cancel()
        snapshotRevision &+= 1
        let revision = snapshotRevision
        let generation = cacheGeneration
        snapshotWriteTask = Task {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled, revision == snapshotRevision,
                  generation == SessionStore.generation,
                  let account = SessionStore.address?.lowercased(),
                  let url = Self.snapshotURL else { return }
            // Capture AFTER the debounce, not before it. Every cache mutation
            // schedules/cancels this task, and the revision check prevents an
            // older detached write from committing over newer state.
            let snapshotPages = pages.compactMapValues { page -> PageCache? in
                guard page.query == nil else { return nil }
                var first = page
                first.messages = Array(page.messages.prefix(25))
                first.pages = first.messages.isEmpty ? 0 : 1
                return first
            }
            let snapshot = DiskSnapshot(
                version: Self.snapshotVersion,
                account: account,
                pages: snapshotPages.mapValues(DiskPage.init)
            )
            let data = try? JSONEncoder().encode(snapshot)
            guard !Task.isCancelled, revision == snapshotRevision,
                  generation == SessionStore.generation, let data else { return }
            // MailStore's actor serializes this bounded atomic write with _clear.
            // A detached writer could outlive cancellation and recreate a deleted
            // snapshot after sign-out; keeping the commit on this actor means a
            // queued clear always runs after an in-progress write and deletes it.
            try? data.write(to: url, options: .atomic)
        }
    }

    private func ensureSession() {
        let current = SessionStore.generation
        guard current != cacheGeneration else { return }
        _clear()
        cacheGeneration = current
        diskLoaded = false
    }

    private func key(_ box: MailBox, _ query: String?) -> String {
        "\(box.rawValue)|\((query ?? "").trimmingCharacters(in: .whitespaces).lowercased())"
    }

    private func normalised(_ query: String?) -> String {
        (query ?? "").trimmingCharacters(in: .whitespaces).lowercased()
    }

    func page(_ box: MailBox, _ query: String? = nil) -> PageCache? {
        ensureSession()
        guard let hit = pages[key(box, query)] else { return nil }
        // A cached page under a *different* term is not this page. Returning it
        // would show unfiltered mail beneath a search box that says otherwise.
        return normalised(hit.query) == normalised(query) ? hit : nil
    }

    /// Merge a fetched page. `replace` is true for page 1 (a refresh or a new
    /// search), false for a later page appended to what is already shown.
    @discardableResult
    func putPage(_ box: MailBox, _ query: String?, _ resp: MailInboxResponse, replace: Bool, generation: UUID? = nil) -> Bool {
        ensureSession()
        if let generation, generation != cacheGeneration { return false }
        let k = key(box, query)
        let prior = replace ? nil : pages[k]
        let merged = (prior?.messages ?? []) + resp.messages
        var seen = Set<String>()
        let messages = merged.filter { seen.insert($0.id).inserted }
        pages[k] = PageCache(
            messages: messages,
            total: resp.total,
            pages: (prior?.pages ?? 0) + 1,
            query: query?.trimmingCharacters(in: .whitespaces).nilIfBlank(),
            loadedAt: Date()
        )
        if !normalised(query).isEmpty {
            let oldSearches = pages
                .filter { $0.key != k && $0.value.query != nil }
                .sorted { $0.value.loadedAt > $1.value.loadedAt }
                .dropFirst(Self.maxCachedSearches - 1)
            for entry in oldSearches { pages.removeValue(forKey: entry.key) }
        }
        scheduleSnapshotWrite()
        return !resp.messages.isEmpty
    }

    func stats() -> MailStatsResponse? { ensureSession(); return statsValue }
    func putStats(_ v: MailStatsResponse, generation: UUID? = nil) {
        ensureSession()
        if let generation, generation != cacheGeneration { return }
        statsValue = v
    }

    func labels() -> [MailLabel]? { ensureSession(); return labelsValue }
    func putLabels(_ v: [MailLabel], generation: UUID? = nil) {
        ensureSession(); if let generation, generation != cacheGeneration { return }; labelsValue = v
    }

    func aliases() -> [MailAlias]? { ensureSession(); return aliasesValue }
    func putAliases(_ v: [MailAlias], generation: UUID? = nil) {
        ensureSession(); if let generation, generation != cacheGeneration { return }; aliasesValue = v
    }

    func me() -> MailMeResponse? { ensureSession(); return meValue }
    func putMe(_ v: MailMeResponse, generation: UUID? = nil) {
        ensureSession(); if let generation, generation != cacheGeneration { return }; meValue = v
    }

    func assignmentsMap() -> [String: Set<String>]? { ensureSession(); return assignments }

    /// Replace the whole map. The route returns every pair the account has, so a
    /// successful read supersedes the previous answer rather than adding to it —
    /// keeping a stale pair would show a tick for a label the server has since
    /// un-tagged.
    func putAssignments(_ rows: [MailAssignment], generation: UUID? = nil) {
        ensureSession()
        if let generation, generation != cacheGeneration { return }
        var built: [String: Set<String>] = [:]
        for r in rows where !r.messageId.isEmpty && !r.labelId.isEmpty {
            built[r.messageId, default: []].insert(r.labelId)
        }
        assignments = built
    }

    /// Patch one pair after a write the server accepted. The label routes answer
    /// with no body of their own, so there is nothing to reconcile against and a
    /// re-read of the whole map would make a routine label feel like a page load.
    /// A map that was never read stays nil — patching it would invent a baseline.
    func markAssigned(messageId: String, labelId: String, generation: UUID? = nil) {
        ensureSession()
        if let generation, generation != cacheGeneration { return }
        guard var cur = assignments else { return }
        cur[messageId, default: []].insert(labelId)
        assignments = cur
    }

    func markUnassigned(messageId: String, labelId: String, generation: UUID? = nil) {
        ensureSession()
        if let generation, generation != cacheGeneration { return }
        // Both operator spellings reached the compiler and both were refused on
        // this line: `- labelId` gave "binary operator '-' cannot be applied to
        // Set<String> and String", and `- [labelId]` — which ought to resolve
        // the array literal to a `Set` through `ExpressibleByArrayLiteral` —
        // gave "cannot be applied to two 'Set<String>' operands", even though
        // `Set` conforms to `SetAlgebra` and `SetAlgebra` declares `-`. The
        // runner's toolchain decides this, not the reading of the manuals, so
        // the mutation goes through `remove`, which has no overload resolution
        // to lose. A message with no pairs keeps having none: nothing is
        // invented, and a map that was never read is left nil by the guard.
        guard var cur = assignments, var labels = cur[messageId] else { return }
        labels.remove(labelId)
        cur[messageId] = labels
        assignments = cur
    }

    /// Drop a message's pairs when the message itself is destroyed, so a permanent
    /// delete cannot leave a ghost label keyed on an id nothing has any more.
    func forgetAssignments(_ messageId: String, generation: UUID? = nil) {
        ensureSession(); if let generation, generation != cacheGeneration { return }; assignments?[messageId] = nil
    }

    func body(_ id: String) -> MailMessageFull? {
        ensureSession()
        return bodies.object(forKey: id as NSString)?.message
    }

    private static var notificationDirectory: URL? {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
            .appendingPathComponent("grapmail-notifications", isDirectory: true)
    }

    private static func notificationURL(_ id: String) -> URL? {
        guard let session = SessionStore.notificationSession, let directory = notificationDirectory else { return nil }
        let digest = SHA256.hash(data: Data("\(session):\(id)".utf8))
        return directory.appendingPathComponent(digest.map { String(format: "%02x", $0) }.joined())
    }

    func saveNotificationMessage(_ message: MailMessageFull, payload: Data, generation: UUID) {
        ensureSession()
        guard generation == cacheGeneration else { return }
        putBody(message, generation: generation)
        guard payload.count <= 512 * 1024, let url = Self.notificationURL(message.id),
              let directory = Self.notificationDirectory else { return }
        do {
            let files = FileManager.default
            try files.createDirectory(at: directory, withIntermediateDirectories: true)
            try payload.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            let contents = try files.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey])
            let byDate = contents.map { file in
                let date = (try? file.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
                return (file, date ?? .distantPast)
            }.sorted { $0.1 > $1.1 }
            for (file, _) in byDate.dropFirst(12) { try? files.removeItem(at: file) }
        } catch {
            // Storage pressure or device protection can leave only the memory copy.
        }
    }

    func notificationMessage(_ id: String) -> MailMessageFull? {
        ensureSession()
        if let message = body(id) { return message }
        guard let url = Self.notificationURL(id),
              let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey]),
              let size = values.fileSize, size <= 512 * 1024,
              let modified = values.contentModificationDate, Date().timeIntervalSince(modified) < 7 * 24 * 60 * 60,
              let data = try? Data(contentsOf: url),
              let message = (try? JSONDecoder().decode(MailMessageResponse.self, from: data))?.message,
              message.id == id else { return nil }
        putBody(message)
        return message
    }

    func putBody(_ message: MailMessageFull, generation: UUID? = nil) {
        ensureSession()
        if let generation, generation != cacheGeneration { return }
        let bodySize = ((message.bodyText?.utf16.count ?? 0) + (message.bodyHtml?.utf16.count ?? 0)) * 2
        let cost = max(1_024, max(message.sizeBytes ?? 0, bodySize))
        if cost <= 8 * 1024 * 1024 {
            bodies.setObject(CachedMessage(message), forKey: message.id as NSString, cost: cost)
        } else {
            bodies.removeObject(forKey: message.id as NSString)
        }
        let subject = message.subject?.nilIfBlank()
        attachmentsByMessage[message.id] = message.attachments.isEmpty ? nil :
            message.attachments.map { IndexedAttachment(attachment: $0, subject: subject) }
    }

    func putBodies(_ messages: [MailMessageFull]) -> [MailMessageFull] {
        ensureSession()
        return messages.map { message in
            var merged = message
            if message.hasAttachments, message.attachments.isEmpty, let known = attachmentsByMessage[message.id] {
                merged.attachments = known.map(\.attachment)
            }
            putBody(merged)
            return merged
        }
    }

    func thread(_ id: String) -> ThreadPage? {
        ensureSession()
        guard let index = threadMessageIds[id], !index.messageIds.isEmpty else { return nil }
        guard Date().timeIntervalSince(index.loadedAt) <= Self.threadFreshness else {
            threadMessageIds[id] = nil
            threadOrder.removeAll { $0 == id }
            return nil
        }
        let messages = index.messageIds.compactMap { body($0) }
        guard messages.count == index.messageIds.count else {
            // One body was evicted by the cost-limited cache; this thread is no
            // longer complete, so the route must be asked again.
            threadMessageIds[id] = nil
            threadOrder.removeAll { $0 == id }
            return nil
        }
        threadOrder.removeAll { $0 == id }
        threadOrder.append(id)
        return ThreadPage(messages: messages, nextCursor: index.nextCursor, hasMore: index.hasMore)
    }

    func putThread(
        _ id: String,
        messages: [MailMessageFull],
        nextCursor: String?,
        hasMore: Bool,
        generation: UUID? = nil
    ) -> ThreadPage {
        ensureSession()
        if let generation, generation != cacheGeneration {
            return ThreadPage(messages: messages, nextCursor: nextCursor, hasMore: hasMore)
        }
        let merged = putBodies(messages)
        threadMessageIds[id] = ThreadIndex(
            messageIds: merged.map(\.id), loadedAt: Date(), nextCursor: nextCursor, hasMore: hasMore,
        )
        threadOrder.removeAll { $0 == id }
        threadOrder.append(id)
        while threadOrder.count > Self.maxCachedThreads {
            threadMessageIds[threadOrder.removeFirst()] = nil
        }
        return ThreadPage(messages: merged, nextCursor: nextCursor, hasMore: hasMore)
    }

    func forgetBody(_ id: String, generation: UUID? = nil) {
        ensureSession()
        if let generation, generation != cacheGeneration { return }
        bodies.removeObject(forKey: id as NSString)
        if let url = Self.notificationURL(id) { try? FileManager.default.removeItem(at: url) }
        attachmentsByMessage[id] = nil
    }

    func forgetAttachment(_ attachmentId: String, generation: UUID? = nil) {
        ensureSession()
        if let generation, generation != cacheGeneration { return }
        for (id, entries) in attachmentsByMessage {
            let remaining = entries.filter { $0.attachment.id != attachmentId }
            guard remaining.count != entries.count else { continue }
            bodies.removeObject(forKey: id as NSString)
            attachmentsByMessage[id] = remaining.isEmpty ? nil : remaining
        }
    }

    func indexedAttachments() -> [IndexedAttachment] { ensureSession(); return attachmentsByMessage.values.flatMap { $0 } }

    func indexedAttachment(_ id: String) -> IndexedAttachment? {
        ensureSession()
        return attachmentsByMessage.values.lazy.compactMap { entries in
            entries.first { $0.attachment.id == id }
        }.first
    }

    /// Apply a local flag change to every cached page, so starring or reading a
    /// message shows immediately and survives a scroll back — without waiting for
    /// a refetch that may not happen and may not succeed.
    func patchFlags(id: String, read: Bool? = nil, starred: Bool? = nil, generation: UUID? = nil) {
        ensureSession()
        if let generation, generation != cacheGeneration { return }
        if var message = body(id),
           read.map({ $0 != message.isRead }) == true || starred.map({ $0 != message.isStarred }) == true {
            message.isRead = read ?? message.isRead
            message.isStarred = starred ?? message.isStarred
            putBody(message)
        }
        for (k, cache) in pages {
            guard let index = cache.messages.firstIndex(where: { $0.id == id }) else { continue }
            let message = cache.messages[index]
            guard read.map({ $0 != message.isRead }) == true
                    || starred.map({ $0 != message.isStarred }) == true else { continue }
            pages[k]?.messages[index] = MailMessage(patching: message, read: read, starred: starred)
        }
        scheduleSnapshotWrite()
    }

    /// Remove a filed row from the box the person is currently looking at.
    ///
    /// Do not delete it from every cached box: archive still belongs in All
    /// Mail and a flagged message still belongs in Starred. Other affected or
    /// derived boxes are invalidated by the model and refetched on entry.
    func remove(id: String, from box: MailBox, generation: UUID? = nil) {
        ensureSession()
        if let generation, generation != cacheGeneration { return }
        let prefix = "\(box.rawValue)|"
        for (k, cache) in pages where k.hasPrefix(prefix) {
            guard let index = cache.messages.firstIndex(where: { $0.id == id }) else { continue }
            pages[k]?.messages.remove(at: index)
            pages[k]?.total = max(0, cache.total - 1)
        }
        scheduleSnapshotWrite()
    }

    func invalidate(_ box: MailBox, generation: UUID? = nil) {
        ensureSession()
        if let generation, generation != cacheGeneration { return }
        pages = pages.filter { !$0.key.hasPrefix("\(box.rawValue)|") }
        scheduleSnapshotWrite()
    }

    func invalidateAllBoxes(generation: UUID? = nil) {
        ensureSession()
        if let generation, generation != cacheGeneration { return }
        pages = [:]
        scheduleSnapshotWrite()
    }

    /// Non-async on purpose: this is called from the sign-out path, where a
    /// cancelled task would leave the next account reading this one's mail.
    nonisolated func clearNow() {
        Task { await self.ensureSession() }
    }

    private func _clear() {
        snapshotWriteTask?.cancel()
        snapshotWriteTask = nil
        snapshotRevision &+= 1
        if let url = Self.snapshotURL { try? FileManager.default.removeItem(at: url) }
        if let url = Self.notificationDirectory { try? FileManager.default.removeItem(at: url) }
        pages = [:]
        statsValue = nil
        labelsValue = nil
        aliasesValue = nil
        meValue = nil
        bodies.removeAllObjects()
        threadMessageIds.removeAll()
        threadOrder.removeAll()
        attachmentsByMessage.removeAll()
        // Back to nil, not to an empty map: the next account has not been asked
        // anything, and an empty map would claim its mail carries no labels.
        assignments = nil
    }
}

/// Copy-with-flags. Swift's synthesized memberwise init is unavailable on a
/// hand-decoded struct, so the patched copy is written out here rather than
/// reached for a `with` that does not exist.
extension MailMessage {
    init(patching other: MailMessage, read: Bool?, starred: Bool?) {
        self.init()
        id = other.id
        threadId = other.threadId
        direction = other.direction
        status = other.status
        from = other.from
        to = other.to
        cc = other.cc
        subject = other.subject
        snippet = other.snippet
        isRead = read ?? other.isRead
        isStarred = starred ?? other.isStarred
        hasAttachments = other.hasAttachments
        unsubscribe = other.unsubscribe
        isE2eEncrypted = other.isE2eEncrypted
        createdAt = other.createdAt
    }
}
