import Foundation

/// The mailbox's process-lifetime cache: the last page of each box, the last
/// stats, the last labels, and the message bodies that were opened.
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

    private var pages: [String: PageCache] = [:]
    private var statsValue: MailStatsResponse?
    private var labelsValue: [MailLabel]?
    private var aliasesValue: [MailAlias]?
    private var meValue: MailMeResponse?
    private var bodies: [String: MailMessageFull] = [:]

    private func key(_ box: MailBox, _ query: String?) -> String {
        "\(box.rawValue)|\((query ?? "").trimmingCharacters(in: .whitespaces).lowercased())"
    }

    private func normalised(_ query: String?) -> String {
        (query ?? "").trimmingCharacters(in: .whitespaces).lowercased()
    }

    func page(_ box: MailBox, _ query: String? = nil) -> PageCache? {
        guard let hit = pages[key(box, query)] else { return nil }
        // A cached page under a *different* term is not this page. Returning it
        // would show unfiltered mail beneath a search box that says otherwise.
        return normalised(hit.query) == normalised(query) ? hit : nil
    }

    /// Merge a fetched page. `replace` is true for page 1 (a refresh or a new
    /// search), false for a later page appended to what is already shown.
    func putPage(_ box: MailBox, _ query: String?, _ resp: MailInboxResponse, replace: Bool) {
        let k = key(box, query)
        let prior = replace ? nil : pages[k]
        let merged = (prior?.messages ?? []) + resp.messages
        var seen = Set<String>()
        pages[k] = PageCache(
            messages: merged.filter { seen.insert($0.id).inserted },
            total: resp.total > 0 ? resp.total : (prior?.total ?? 0),
            pages: (prior?.pages ?? 0) + 1,
            query: query?.trimmingCharacters(in: .whitespaces).nilIfBlank(),
            loadedAt: Date()
        )
    }

    func stats() -> MailStatsResponse? { statsValue }
    func putStats(_ v: MailStatsResponse) { statsValue = v }

    func labels() -> [MailLabel]? { labelsValue }
    func putLabels(_ v: [MailLabel]) { labelsValue = v }

    func aliases() -> [MailAlias]? { aliasesValue }
    func putAliases(_ v: [MailAlias]) { aliasesValue = v }

    func me() -> MailMeResponse? { meValue }
    func putMe(_ v: MailMeResponse) { meValue = v }

    /// Opened bodies, memoised by id. A body is fetched once: the list payload
    /// has none, and re-reading a payload to redraw a row is the over-fetch the
    /// shape was designed to avoid.
    func body(_ id: String) -> MailMessageFull? { bodies[id] }
    func putBody(_ v: MailMessageFull) { bodies[v.id] = v }

    /// Every body the app has opened. This is what the Files screen is built
    /// from, and it is honest only because the screen says so: there is no
    /// list-all attachments route, so a file inside mail the user has not opened
    /// is genuinely not here.
    func allBodies() -> [MailMessageFull] { Array(bodies.values) }

    /// Apply a local flag change to every cached page, so starring or reading a
    /// message shows immediately and survives a scroll back — without waiting for
    /// a refetch that may not happen and may not succeed.
    func patchFlags(id: String, read: Bool? = nil, starred: Bool? = nil) {
        for (k, cache) in pages {
            pages[k] = PageCache(
                messages: cache.messages.map { m in
                    m.id == id
                        ? MailMessage(patching: m, read: read, starred: starred)
                        : m
                },
                total: cache.total, pages: cache.pages, query: cache.query, loadedAt: cache.loadedAt)
        }
    }

    /// Drop a message from the cached pages after it has been filed elsewhere.
    /// The box it moved *to* is invalidated rather than inserted into, because
    /// the app cannot know that box's ordering without asking the server.
    func removeEverywhere(id: String) {
        for (k, cache) in pages {
            pages[k] = PageCache(
                messages: cache.messages.filter { $0.id != id },
                total: cache.total, pages: cache.pages, query: cache.query, loadedAt: cache.loadedAt)
        }
    }

    func invalidate(_ box: MailBox) {
        pages = pages.filter { !$0.key.hasPrefix("\(box.rawValue)|") }
    }

    func invalidateAllBoxes() {
        pages = [:]
        statsValue = nil
        bodies = [:]
    }

    /// Non-async on purpose: this is called from the sign-out path, where a
    /// cancelled task would leave the next account reading this one's mail.
    nonisolated func clearNow() {
        Task { await self._clear() }
    }

    private func _clear() {
        pages = [:]
        statsValue = nil
        labelsValue = nil
        aliasesValue = nil
        meValue = nil
        bodies = [:]
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
