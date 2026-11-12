import Foundation
import Combine

/// What the reader's next/prev controls need to open a row: which resource, and
/// whether it is a conversation.
///
/// The flag travels with the target rather than being assumed by the caller,
/// because the two thread routes are different resources — `/thread/:id` answers
/// a message id with a 404, and the list already had to learn that (`isThread`
/// exists on its tap for the same reason). A stepper that hardcoded `true` would
/// reopen the same dead end one button further in. Android's `MailStep` is the
/// same pair.
struct MailStep: Equatable {
    let id: String
    let isThread: Bool
}

/// The mailbox's state and its only writer.
///
/// ── WHAT A FAILED REFRESH IS ALLOWED TO DO ──────────────────────────────────
/// None of the loaders blank data that is already on screen. The hub in this app
/// did exactly that — one rate-limited refetch out of the eight a visit makes set
/// `failed = true`, which disabled its own toggle and left a screen showing
/// nothing over data the app still held. Here a refresh failure keeps the list and
/// records the problem separately, so `staleError` explains a stale list rather
/// than replacing it. `loading` is only true when there is nothing to show.
///
/// ── WHY SORT AND FILTER SAY WHAT THEY SCOPED ────────────────────────────────
/// The server orders by UID descending and nothing else (`grapmail-boxes.ts:34-39`),
/// and `/inbox` has no filter parameter. So `sender A→Z` and `unread` are applied
/// by the client to the pages that have been fetched. `sortScope` is the number
/// actually re-ordered, and the sort sheet prints it rather than implying the
/// whole box was resorted.
@MainActor
final class MailboxModel: ObservableObject {

    struct State {
        var box: MailBox = .inbox
        var query: String = ""
        var filter: MailFilter = .all
        var sort: MailSort = .newest
        /// Rows after the client's filter and sort — what the list renders.
        var rows: [MailMessage] = []
        /// Rows fetched for this box, before filtering.
        var loaded: Int = 0
        /// Rows the box contains, per the server. 0 until the first page lands.
        var total: Int = 0
        var pages: Int = 0
        var loading = false
        var loadingMore = false
        var appendError: String?
        var endOfPages = false
        var canLoadMore: Bool { !loading && !loadingMore && !endOfPages && loaded < total }
        /// A refresh that failed while rows were on screen. Non-nil with rows
        /// present means "this list may be stale", not "there is no mail".
        var staleError: String?
        var actionError: String?
        /// A load that failed with nothing to show. This is the error screen.
        var fatal: String?
        /// True when the server said this account has no mailbox at all.
        var noMailbox = false
        var selection: Set<String> = []
        var labels: [MailLabel] = []
        /// Whether `GET /labels/assignments` has answered. Kept apart from
        /// `labelMarks` so the sheet can say “not known” out loud: marking every
        /// label off before the read answers would offer to add a label the message
        /// already carries, and would let a tap remove one on a guess.
        var assignmentsRead: AssignmentsRead = .notRead
        /// Whether the label LIST has been read. An empty list is only a fact once
        /// somebody has answered: `labels` staying empty after a refused read is not
        /// evidence that the mailbox has no labels.
        var labelsRead: AssignmentsRead = .notRead
        /// labelId → its state against the current selection.
        var labelMarks: [String: LabelMark] = [:]
        /// The Label sheet's own result line — what the last tap actually did.
        var labelNote: String?
        ///
        /// The messages this session snoozed, kept so the snooze can be undone.
        ///
        /// `publicRow` (`grapmail.ts:583-611`) carries no `snoozedUntil` and the
        /// inbox hides a pending snooze, so the app cannot tell which of the mail on
        /// screen is postponed — `?box=snoozed` is implemented but not deployed.
        /// Undo is therefore offered for exactly the mail the app just snoozed,
        /// which is the whole set a client can honestly name.
        var snoozeUndoIds: [String] = []
        var snoozeUndoNote: String?
        /// The last filing this session did, kept so the banner can offer its
        /// exact reverse. Same rule as the snooze pair: only the ids the server
        /// accepted, and any newer filing supersedes the offer.
        var undoIds: [String] = []
        var undoInverse: MailAction?
        var undoNote: String?
        /// The advanced search's filters, applied CLIENT-SIDE to the pages this
        /// device has loaded -- the server's `q` is one literal term over
        /// subject, body and from (`grapmail-search.ts:110-118`) and there is
        /// no from/to/attachment/date route to call. The sheet prints
        /// `advScope` the way the sort sheet prints its scope.
        var advFrom = ""
        var advTo = ""
        var advSubject = ""
        var advFiles = false
        var advAfter = ""    // YYYY-MM-DD
        var advBefore = ""   // YYYY-MM-DD
        /// Rows the advanced filters ran over -- the honest scope line.
        var advScope = 0
        var unreadCount: Int?
        var statsSent: Int?
        var statsDrafts: Int?
        var statsSpam: Int?
        var statsTrash: Int?
        /// Counted by the same `boxCount` that builds the listing beside each
        /// badge (`grapmail.ts:666-688`). Nil on a deployment that has not
        /// answered; the drawer reads that nil as "not offered here", never 0.
        var statsStarred: Int?
        var statsArchive: Int?
        /// `?box=others` -- everything still in the account
        /// (`grapmail-box-terms.ts:78`).
        var statsOthers: Int?
        /// The count of `?box=snoozed`. Nil means "nobody asked" or "this
        /// deployment has no snoozed list" -- neither licenses a 0, and the
        /// drawer row exists only when this is not nil.
        var statsSnoozed: Int?
        /// `GET /me`. The greeting is built from these, not from the sign-in
        /// account: `/me` answers with the name the mailbox carries, and nil
        /// means it never said so — so the screen shows no name rather than a
        /// plausible one.
        var accountName: String?
        var accountAddress: String?
        /// The mailbox's own state, which is not the account's state.
        var accountStatus: String?
        var memberSince: String?
        var busy = false

    }

    @Published private(set) var state = State()
    @Published private(set) var thread = ThreadState()
    @Published private(set) var compose = ComposeState()
    @Published private(set) var labelState = LabelState()
    @Published private(set) var screener = ScreenerState()

    struct ThreadState {
        var messages: [MailMessageFull] = []
        var loading = false
        var error: String?
    }

    struct ComposeState {
        var to = ""
        var cc = ""
        var bcc = ""
        var subject = ""
        var body = ""
        var inReplyTo: String?
        /// The draft row this compose was opened FROM. Non-empty means the
        /// person is continuing a saved message, and a successful send
        /// deletes it — see [send]. The autosave loop keeps it current: the
        /// first autosave records the id its insert produced, and every later
        /// one updates that row.
        var draftId = ""
        var sending = false
        /// The server's own verdict, shown verbatim. Never a local "Sent".
        var result: String?
        var resultStatus: String?
        var error: String?
        /// The autosave's own quiet state, deliberately separate from
        /// [result]: a background save must never read like a send receipt,
        /// and the composer draws it as one small tertiary line.
        var autosaving = false
        var autosaveNote: String?
        var accepted: Bool { resultStatus == "sent" || resultStatus == "queued" || resultStatus == "delivered" }
    }

    struct LabelState {
        var labels: [MailLabel] = []
        var loading = false
        var error: String?
    }

    struct ScreenerState {
        var loaded = false
        /// nil until the server answers. A switch drawn as "off" because the read
        /// failed would tell the person their inbound mail is unfiltered when
        /// nothing was said either way.
        var enabled: Bool?
        var pending: Int?
        var busy = false
        var error: String?
    }

    struct Person {
        let address: String
        let name: String?
        let seen: Int
    }

    struct FileEntry {
        let attachment: MailAttachment
        let fromMessage: String?
    }

    private var loadTask: Task<Void, Never>?
    private var projectedTerm: String?
    private var searchTerm: String? {
        let term = state.query.trimmed
        return term.count >= 3 ? term : nil
    }
    private var searchTask: Task<Void, Never>?

    /// The boxes this session has already asked the server about, and whether
    /// `/me` has been read. A mailbox that re-reads page 1 every time the screen
    /// reappears pays for a full page on every return from a thread, and a failed
    /// one of those reads looks like a load failure on a list that is already on
    /// screen — the hub's defect wearing a different hat. So entering the home
    /// asks only for a box that has never been fetched, choosing a box fetches,
    /// and pull-to-refresh is the one other way page 1 is requested.
    private var loadedBoxes: Set<MailBox> = []

    /// The conversation the thread state currently holds. Returning to a thread
    /// the app already has must not cost another full payload read — the same
    /// egress discipline the list keeps — and a snapshot that seeded a body must
    /// not have it fetched away on appear.
    private var threadLoadedFor: String?
    private var readerTask: Task<Void, Never>?
    private var readerTarget: MailStep?

    private func loadReader(_ id: String, isThread: Bool, read: @escaping @MainActor () async -> Void) {
        if let target = readerTarget, target.id == id, target.isThread == isThread,
           threadLoadedFor == id || thread.loading { return }
        readerTask?.cancel()
        readerTarget = MailStep(id: id, isThread: isThread)
        threadLoadedFor = nil
        thread = ThreadState(loading: true)
        readerTask = Task { await read() }
    }
    private var accountLoaded = false

    /// Test-only seam for the snapshot host. The mailbox's state is written by
    /// its loaders and nothing else; this exists so a screen that needs content
    /// the CI account cannot have can still be photographed. The values come
    /// from `MailSnapshot`, never from here, and no live path calls it.
    func seedForSnapshot(_ mutate: (inout State) -> Void) {
        snapshotSeeded = true
        mutate(&state)
    }

    /// The same seam for the thread, which is a separate published value because
    /// a thread is one message's body rather than the box's listing.
    func seedThreadForSnapshot(_ messages: [MailMessageFull], for id: String) {
        thread = ThreadState(messages: messages)
        threadLoadedFor = id
    }

    /// Labels live in `labelState` for the labels screen and in `state.labels`
    /// for the inbox's chip strip. The snapshot host has to fill both: seeding
    /// one and reading the other is how `mail-labels` photographs "No labels
    /// yet" while the sample data sits unused in the other store.
    func seedLabelsForSnapshot(_ labels: [MailLabel]) {
        state.labels = labels
        labelState.labels = labels
    }

    /// The screener switch. Android's host seeds this too; without it the two
    /// platforms photograph the same screen in different states.
    func seedScreenerForSnapshot(enabled: Bool, pending: Int) {
        screener.loaded = true
        screener.enabled = enabled
        screener.pending = pending
        screener.error = nil
    }

    private var sessionGeneration: UUID?

    private var token: String? {
        let generation = SessionStore.generation
        guard sessionGeneration == nil || sessionGeneration == generation,
              let token = SessionStore.token else { return nil }
        sessionGeneration = generation
        return token
    }

    /// Whether a bearer is still held. Every cache write checks this AFTER its
    /// await, because sign-out clears the store and the cache together, and a
    /// request that was already in flight would otherwise re-seed the previous
    /// account's pages a moment later -- the exact cross-account leak the cache
    /// exists to prevent. iOS cannot await the clear itself: `SessionStore.clear()`
    /// is synchronous, so the guard belongs on the writer.
    private var sessionIsLive: Bool { sessionGeneration == SessionStore.generation }

    /// Set by the CI snapshot host's seams, and checked by every loader that would
    /// otherwise overwrite a seeded field. Nothing in the live flow sets it.
    var snapshotSeeded = false

    // ── list ─────────────────────────────────────────────────────────────────

    func open(_ box: MailBox) {
        guard state.box != box else { return }
        searchTask?.cancel()
        searchTask = nil
        loadedBoxes.insert(box)
        var updated = state
        updated.box = box
        updated.selection = []
        updated.rows = []
        updated.pages = 0
        updated.loaded = 0
        updated.total = 0
        updated.advScope = 0
        updated.loading = true
        updated.loadingMore = false
        updated.appendError = nil
        updated.endOfPages = false
        updated.noMailbox = false
        updated.fatal = nil
        updated.staleError = nil
        updated.actionError = nil
        state = updated
        load(replace: true, reuseRecentFolder: true, forceCounts: false)
    }

    /// Fetch the current box when it has never been fetched. The home screen's
    /// entry point.
    func ensureLoaded() {
        if loadedBoxes.contains(state.box) { return }
        loadedBoxes.insert(state.box)
        load(replace: true)
    }

    private var lastFirstPageAttempt: TimeInterval?

    func refreshOnResume(atTop: Bool) {
        guard !snapshotSeeded, canRefreshInBackground(atTop: atTop),
              let lastFirstPageAttempt,
              ProcessInfo.processInfo.systemUptime - lastFirstPageAttempt >= 60 else { return }
        load(forceCounts: false)
    }

    func acceptPoll(_ result: MailPoll.PollResult, atTop: Bool) {
        guard !snapshotSeeded, result.generation == SessionStore.generation, sessionIsLive else { return }
        Task { [weak self] in
            guard let self, self.sessionIsLive else { return }
            await MailStore.shared.putStats(result.stats)
            guard self.sessionIsLive else { return }
            let inbox = result.stats.inbox
            let unreadChanged = inbox?.unread.map { $0 != self.state.unreadCount } ?? false
            let totalChanged = inbox?.total.map { $0 != self.state.total } ?? false
            let changed = unreadChanged || (self.state.box == .inbox && totalChanged)
            var updated = self.state
            self.applyFolderCounts(result.stats, to: &updated)
            self.state = updated
            self.countsLoadedAt = ProcessInfo.processInfo.systemUptime
            guard changed, self.state.box == .inbox, self.canRefreshInBackground(atTop: atTop) else { return }
            self.load(forceCounts: false)
        }
    }

    private func canRefreshInBackground(atTop: Bool) -> Bool {
        atTop && loadTask == nil && searchTask == nil && searchTerm == nil &&
            NetworkMonitor.shared.online != false && state.pages <= 1 && state.selection.isEmpty
    }

    /// Typing. Debounced, and a term shorter than 3 characters never leaves the
    /// device: a one-character `q` scans the whole mailbox server-side
    /// (`grapmail.ts:688`), so every keystroke would be a full-table read.
    func query(_ text: String) {
        let previous = state.query
        guard previous != text else { return }
        state.query = text
        let oldTerm = previous.trimmingCharacters(in: .whitespacesAndNewlines)
        let newTerm = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (oldTerm.count >= 3 ? oldTerm : "") != (newTerm.count >= 3 ? newTerm : "") else { return }
        searchTask?.cancel()
        searchTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled else { return }
            self?.searchTask = nil
            self?.load(replace: true)
        }
    }

    func setSortAndFilter(_ sort: MailSort, _ filter: MailFilter) {
        guard state.sort != sort || state.filter != filter else { return }
        var updated = state
        updated.sort = sort
        updated.filter = filter
        state = updated
        reproject()
    }

    /// Load a page. `replace` restarts the box (refresh, new search, new box);
    /// otherwise the page is appended, which is what makes scrolling cheap.
    func load(replace: Bool = true, reuseRecentFolder: Bool = false, forceCounts: Bool = true) {
        loadTask?.cancel()
        loadTask = Task { [weak self] in
            guard let self, !Task.isCancelled else { return }
            defer {
                if !Task.isCancelled { self.loadTask = nil }
            }
            let box = self.state.box
            let term = self.searchTerm
            let page = replace ? 1 : self.state.pages + 1

            // Show the spinner only when there is nothing to show. A refresh over
            // an existing list must not blank it — the hub's defect, designed out
            // here rather than rediscovered.
            if replace && self.state.rows.isEmpty {
                self.state.loading = true
                self.state.staleError = nil
            }
            if !replace {
                self.state.loadingMore = true
                self.state.appendError = nil
            }

            guard let token = self.token else {
                self.state.loading = false
                self.state.loadingMore = false
                self.state.fatal = "Sign in again."
                return
            }

            do {
                if replace, self.state.rows.isEmpty,
                   let cached = await MailStore.shared.page(box, term),
                   !Task.isCancelled, sessionIsLive {
                    guard term == self.searchTerm else { return }
                    await self.projectCurrentRows()
                    guard !Task.isCancelled, sessionIsLive, term == self.searchTerm else { return }
                    self.state.loading = false
                    let age = Date().timeIntervalSince(cached.loadedAt)
                    if reuseRecentFolder, term == nil, (0...15).contains(age) {
                        self.refreshCounts(token, force: false)
                        return
                    }
                }
                guard !Task.isCancelled else { return }
                if replace, term == nil { self.lastFirstPageAttempt = ProcessInfo.processInfo.systemUptime }
                let resp = try await ApiClient.mailInbox(
                    token: token, box: box, page: page, query: term)
                guard !Task.isCancelled, term == self.searchTerm else { return }
                guard sessionIsLive else {
                    // Signed out mid-flight: the same rule as the token guard above.
                    self.state.loading = false
                    self.state.loadingMore = false
                    return
                }
                let addedRows = await MailStore.shared.putPage(box, term, resp, replace: replace)
                guard !Task.isCancelled, sessionIsLive, term == self.searchTerm else { return }
                await self.projectCurrentRows()
                guard !Task.isCancelled, sessionIsLive, term == self.searchTerm else { return }
                self.loadedBoxes.insert(box)
                var updated = self.state
                updated.loading = false
                updated.loadingMore = false
                updated.appendError = nil
                updated.endOfPages = !addedRows
                updated.fatal = nil
                updated.staleError = nil
                self.state = updated
                if replace, term == nil { self.refreshCounts(token, force: forceCounts) }
                if replace { self.prefetchBodies() }
            } catch {
                // A superseded read says nothing. `load()` cancels the previous task
                // on every call, so a box switch or one keystroke past the debounce
                // arrived here as a `cancelled` URLError, which `describe` renders as
                // "Network error. Please try again." — an error banner painted over
                // the list the newer read had just filled. Same reasoning as Android's
                // `CancellationException` rethrow in `load()`.
                if error is CancellationError || Task.isCancelled || term != self.searchTerm { return }
                let noBox = (error as? ApiError).map { Self.isNoMailbox($0) } ?? false
                let message = Self.describe(error)
                if !replace, !noBox {
                    self.state.loadingMore = false
                    self.state.appendError = message
                    return
                }
                self.state.loading = false
                self.state.loadingMore = false
                self.state.noMailbox = noBox
                // With rows on screen this is a stale list, not an empty one.
                // Without them it is the error screen.
                self.state.staleError = self.state.rows.isEmpty ? nil : message
                self.state.fatal = (self.state.rows.isEmpty && !noBox) ? message : nil
                if noBox {
                    self.state.rows = []
                    self.state.loaded = 0
                    self.state.total = 0
                }
            }
        }
    }

    private var countsTask: Task<Void, Never>?
    private var countsLoadedAt: TimeInterval?

    private func refreshCounts(_ token: String, force: Bool) {
        guard countsTask == nil else { return }
        let now = ProcessInfo.processInfo.systemUptime
        if !force, let countsLoadedAt, now - countsLoadedAt < 30 { return }
        countsTask = Task { [weak self] in
            guard let self else { return }
            defer { self.countsTask = nil }
            guard let stats = try? await ApiClient.mailStats(token), self.sessionIsLive else { return }
            await MailStore.shared.putStats(stats)
            guard self.sessionIsLive else { return }
            self.countsLoadedAt = ProcessInfo.processInfo.systemUptime
            var updated = self.state
            self.applyFolderCounts(stats, to: &updated)
            self.state = updated
        }
    }

    func loadAndSettle() async {
        load()
        await loadTask?.value
    }

    func loadMore() {
        guard searchTask == nil, loadTask == nil, projectedTerm == searchTerm, state.canLoadMore else { return }
        load(replace: false)
    }

    private var projectionGeneration = 0

    func reproject() {
        Task { [weak self] in await self?.projectCurrentRows() }
    }

    private func projectCurrentRows() async {
        projectionGeneration &+= 1
        let generation = projectionGeneration
        let settings = state
        let box = state.box
        let term = searchTerm
        let cache = await MailStore.shared.page(box, term)
        let rows = await Self.projectRows(cache?.messages ?? settings.rows, settings: settings)
        let stats = await MailStore.shared.stats()
        guard !Task.isCancelled, projectionGeneration == generation, state.box == box,
              searchTerm == term else { return }
        if cache != nil { projectedTerm = term }
        var updated = state
        updated.rows = rows
        updated.total = cache?.total ?? updated.total
        updated.loaded = cache?.messages.count ?? updated.loaded
        updated.pages = cache?.pages ?? updated.pages
        updated.advScope = cache?.messages.count ?? updated.loaded
        applyFolderCounts(stats, to: &updated)
        state = updated
    }

    private nonisolated static func projectRows(_ messages: [MailMessage], settings: State) async -> [MailMessage] {
        var rows = messages
        if settings.filter == .unread { rows = rows.filter { !$0.isRead } }

        // The advanced filters run here, on the loaded pages, before the
        // sort. Blank fields are absent filters, not empty-string matches.
        let advOn = !settings.advFrom.trimmed.isEmpty || !settings.advTo.trimmed.isEmpty
            || !settings.advSubject.trimmed.isEmpty || settings.advFiles
            || !settings.advAfter.trimmed.isEmpty || !settings.advBefore.trimmed.isEmpty
        if advOn {
            let f = settings.advFrom.trimmed.lowercased()
            let tt = settings.advTo.trimmed.lowercased()
            let sj = settings.advSubject.trimmed.lowercased()
            let after = settings.advAfter.trimmed
            let before = settings.advBefore.trimmed
            rows = rows.filter { m in
                (f.isEmpty || (m.from?.display ?? "").lowercased().contains(f))
                && (tt.isEmpty || m.to.contains { $0.lowercased().contains(tt) }
                    || m.cc.contains { $0.lowercased().contains(tt) })
                && (sj.isEmpty || (m.subject ?? "").lowercased().contains(sj))
                && (!settings.advFiles || m.hasAttachments)
                && (after.isEmpty || String((m.createdAt ?? "").prefix(10)) >= after)
                && (before.isEmpty || String((m.createdAt ?? "").prefix(10)) <= before)
            }
        }

        // The keys are computed through locals on purpose. Written inline as
        // `$0.from?.display.lowercased() < $1.from?.display.lowercased()`,
        // the optional chain makes both sides `String?`, `<` has no such
        // overload, and the compiler then resolves `sort` to the
        // `Comparator` API and reports an error that names nothing useful.
        switch settings.sort {
        case .newest: break                                  // the server's own order
        case .oldest: rows = rows.reversed()
        case .senderAsc:
            rows.sort { a, b in Self.senderKey(a) < Self.senderKey(b) }
        case .senderDesc:
            rows.sort { a, b in Self.senderKey(a) > Self.senderKey(b) }
        case .subjectAsc:
            rows.sort { a, b in Self.subjectKey(a) < Self.subjectKey(b) }
        case .subjectDesc:
            rows.sort { a, b in Self.subjectKey(a) > Self.subjectKey(b) }
        }
        return rows
    }

    private func applyFolderCounts(_ stats: MailStatsResponse?, to state: inout State) {
        state.unreadCount = stats?.inbox?.unread
        state.statsSent = stats?.sent
        state.statsDrafts = stats?.drafts
        state.statsSpam = stats?.spam
        state.statsTrash = stats?.trash
        state.statsStarred = stats?.starred
        state.statsArchive = stats?.archive
        state.statsOthers = stats?.others
        state.statsSnoozed = stats?.snoozed
    }

    /// How many rows the chosen sort actually re-ordered. The sort sheet prints
    /// this so a client-side ordering is not mistaken for the server's.
    var sortScope: Int { state.loaded }

    // ── identity ─────────────────────────────────────────────────────────────

    /// The mailbox identity is shared by the account menu and compose.
    func loadAccount() {
        guard !snapshotSeeded, let token, !accountLoaded else { return }
        accountLoaded = true
        Task { [weak self] in
            guard let self else { return }
            if let me = try? await ApiClient.mailMe(token), self.sessionIsLive {
                await MailStore.shared.putMe(me)
                var updated = self.state
                updated.accountName = me.name
                updated.accountAddress = me.address
                updated.accountStatus = me.status
                updated.memberSince = me.memberSince
                self.state = updated
            } else {
                self.accountLoaded = false
            }
        }
    }

    // ── selection ────────────────────────────────────────────────────────────

    func toggleSelect(_ id: String) {
        if state.selection.contains(id) { state.selection.remove(id) }
        else { state.selection.insert(id) }
    }

    func dismissActionError() { state.actionError = nil }

    func clearSelection() { state.selection = [] }
    func selectAll() { state.selection = Set(state.rows.map(\.id)) }

    // ── actions ─────────────────────────────────────────────────────────────

    /// Apply one action to the selected messages.
    ///
    /// Filing invalidates the *destination* box rather than inserting into it: the
    /// app cannot know where the server put the row in that box's order, and
    /// inventing a position would show mail the server does not list there.
    func act(_ action: MailAction, until: String? = nil) {
        let ids = Array(state.selection)
        guard !ids.isEmpty, !state.busy, pendingRowActions.isEmpty, let token else { return }
        state.busy = true
        Task { [weak self] in
            guard let self else { return }
            var failure: String?
            var done: [String] = []
            // One request per message, run serially, made a selection of twenty
            // pay twenty round trips with the toolbar locked the whole time. The
            // writes are independent, so they run four at a time — same
            // per-message answers, same per-success local applies, a selection
            // that settles in a fraction of the wall clock. Four, not twenty,
            // because the server rate-limits writes and a burst that trips the
            // limit would fail the very actions the parallelism was meant to
            // speed up. `ApiClient` statics are nonisolated, so the grouped
            // children genuinely run concurrently; everything that touches
            // `self` stays on the actor between chunks.
            let source: MailBox?
            switch action {
            case .unstar: source = .starred
            case .restore: source = .trash
            case .unarchive: source = .archive
            case .unspam: source = .spam
            case .unsnooze: source = .snoozed
            default: source = nil
            }
            let removesRow = action == .trash || action == .archive ||
                action == .spam || action == .snooze || source == self.state.box
            let written = await self.parallelWrites(
                ids,
                write: { try await ApiClient.mailAction(token: token, id: $0, action: action, until: until) },
                onAccept: { id in
                    await self.applyLocally(id: id, action: action)
                    // The row answers as soon as ITS write lands, not when the
                    // whole selection has settled.
                    self.state.selection.remove(id)
                    if removesRow { self.state.rows.removeAll { $0.id == id } }
                },
            )
            done = written.done
            failure = written.failure
            guard self.sessionIsLive, !written.stopped else { return }
            if !done.isEmpty { await self.invalidateFor(action) }
            self.state.busy = false
            self.state.selection.subtract(done)
            self.state.actionError = failure
            // The feel follows the fact: the mail moved only where the server
            // accepted it, so a refused filing gets the error tap and not the
            // success one. A haptic that fires on a tap that changed nothing is
            // the same lie as a banner that says "Archived" over mail still in
            // the inbox.
            if failure == nil {
                switch action {
                case .star, .unstar, .read, .unread: GMHaptics.marking()
                default: GMHaptics.filing()
                }
            } else {
                GMHaptics.failure()
            }
            // Only the messages the server accepted get an undo: offering one for a
            // message whose snooze failed would unsnooze mail still in the inbox and
            // report it as postponed.
            if action == .snooze {
                if !done.isEmpty {
                    self.state.snoozeUndoIds = done
                    self.state.snoozeUndoNote = Self.snoozeNote(done.count, until: until)
                }
            } else if action != .unsnooze {
                // Any other filing action supersedes the snooze offer. An
                // unsnooze keeps it: a partial undo leaves the rest postponed
                // and still offered.
                self.state.snoozeUndoIds = []
                self.state.snoozeUndoNote = nil
            }
            // The filing undo, same rule as the snooze pair: only the ids the
            // server accepted, and only for the actions with a real inverse
            // route. Read/star do not raise it -- their rows keep their toggle.
            if let inv = action.inverse(), !done.isEmpty {
                self.state.undoIds = done
                self.state.undoInverse = inv
                self.state.undoNote = action.pastLine(done.count)
            } else if action.inverse() == nil {
                self.state.undoIds = []
                self.state.undoInverse = nil
                self.state.undoNote = nil
            }
            guard !done.isEmpty else { return }
            switch action {
            case .read, .unread:
                await self.projectCurrentRows()
            case .star, .unstar:
                if self.state.box == .starred {
                    if action == .unstar {
                        let removed = Set(done)
                        self.state.rows.removeAll { removed.contains($0.id) }
                    }
                    self.load(forceCounts: false)
                } else { await self.projectCurrentRows() }
            default:
                self.load(replace: true)
            }
        }
    }

    /// The undo line, naming the instant the server was told to hold the mail until.
    /// A `until` it cannot parse still produces the count rather than nothing: the
    /// undo must exist even when the date cannot be shown.
    static func snoozeNote(_ count: Int, until: String?) -> String {
        let head = count == 1 ? "Snoozed 1 message" : "Snoozed \(count) messages"
        // `MailTime.parse` is private to that type, so the formatter is used
        // directly rather than reaching for a helper that is not exposed.
        guard let raw = until else { return head }
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = iso.date(from: raw) ?? ISO8601DateFormatter().date(from: raw)
        else { return head }
        let f = DateFormatter()
        f.dateFormat = "EEE, d MMM · h:mm a"
        return "\(head) until \(f.string(from: date))"
    }

    /// Take the mail back out of snooze — the one place `unsnooze` is reachable.
    ///
    /// The action existed on both platforms with nothing calling it, because the
    /// client could not see which messages were snoozed. The moment of snoozing is
    /// the moment the app knows, so that is where the undo lives.
    func undoSnooze() {
        let ids = state.snoozeUndoIds
        guard !ids.isEmpty, !state.busy, pendingRowActions.isEmpty, let token else { return }
        state.busy = true
        Task { [weak self] in
            guard let self else { return }
            let written = await self.parallelWrites(
                ids,
                write: { try await ApiClient.mailAction(token: token, id: $0, action: .unsnooze, until: nil) },
                onAccept: { id in
                    self.state.snoozeUndoIds = self.state.snoozeUndoIds.filter { $0 != id }
                },
            )
            let restored = written.done
            let failure = written.failure
            let undone = restored.count
            guard self.sessionIsLive, !written.stopped else { return }
            if !restored.isEmpty { await self.invalidateFor(.unsnooze) }
            self.state.busy = false
            // Cleared only for what actually came back; a partial undo keeps the
            // REST offered rather than forgetting them. The ids removed above are the
            // proof: leaving the whole list in place would make the next Undo re-send
            // the action for mail that is already back in the inbox, and count it a
            // second time in the note — which is exactly what Android's
            // `snoozeUndoIds - restored` does and this side did not.
            if failure == nil, restored.count == ids.count {
                self.state.snoozeUndoIds = []
                self.state.snoozeUndoNote = nil
            } else if let failure {
                // `if let`, not an interpolation of the Optional: a nil failure
                // reaching this line would print "2 of 3 came back. " with a dangling
                // period, the same shape as the truncated sender clause the
                // unsubscribe sheet had.
                self.state.snoozeUndoNote = "\(undone) of \(ids.count) came back. \(failure)"
            }
            if !restored.isEmpty { self.load(replace: true) }
        }
    }

    func dismissSnoozeUndo() {
        state.snoozeUndoIds = []
        state.snoozeUndoNote = nil
    }

    /// Reverse the last filing, for exactly the ids the server accepted then.
    /// The inverse is another `PATCH /message/:id/:action` -- archive/unarchive,
    /// spam/unspam, trash/restore are the route's own pairs -- so this is a real
    /// path back, not a local reshuffle of rows the server already moved. A
    /// partial failure keeps offering only the ones that did NOT go back, same
    /// as the snooze undo.
    /// Independent per-id writes, four at a time.
    ///
    /// One request per message, run serially, made a selection of twenty pay
    /// twenty round trips with the toolbar locked the whole time. The writes are
    /// independent, so they run four at a time — same per-message answers, same
    /// per-success local applies, a selection that settles in a fraction of the
    /// wall clock. Four, not twenty, because the server rate-limits writes and a
    /// burst that trips the limit would fail the very actions the parallelism
    /// was meant to speed up. `ApiClient` statics are nonisolated, so the
    /// grouped children genuinely run concurrently; every result is processed
    /// here on the actor, between chunks.
    private func parallelWrites(
        _ ids: [String],
        write: @escaping (String) async throws -> Void,
        onAccept: (String) async -> Void = { _ in },
    ) async -> (done: [String], failure: String?, stopped: Bool) {
        var done: [String] = []
        var failure: String?
        var index = ids.startIndex
        while index < ids.endIndex {
            let chunk = Array(ids[index..<Swift.min(index + 4, ids.endIndex)])
            index += 4
            var landed: [(id: String, error: Error?)] = []
            await withTaskGroup(of: (String, Error?).self) { group in
                for id in chunk {
                    group.addTask {
                        do { try await write(id); return (id, nil) }
                        catch { return (id, error) }
                    }
                }
                for await result in group { landed.append(result) }
            }
            for (id, error) in landed {
                if let error {
                    if error is CancellationError { return (done, failure, true) }
                    failure = failure ?? Self.describe(error)
                    continue
                }
                guard sessionIsLive else { return (done, failure, true) }
                done.append(id)
                await onAccept(id)
            }
        }
        return (done, failure, false)
    }

    func undoFiling() {
        let ids = state.undoIds
        guard let inv = state.undoInverse, !ids.isEmpty, !state.busy, pendingRowActions.isEmpty, let token else { return }
        state.busy = true
        Task { [weak self] in
            guard let self else { return }
            let written = await self.parallelWrites(
                ids,
                write: { try await ApiClient.mailAction(token: token, id: $0, action: inv, until: nil) },
                onAccept: { id in await self.applyLocally(id: id, action: inv) },
            )
            let (redone, failure) = (written.done, written.failure)
            guard self.sessionIsLive, !written.stopped else { return }
            if !redone.isEmpty { await self.invalidateFor(inv) }
            let left = self.state.undoIds.filter { !redone.contains($0) }
            self.state.busy = false
            self.state.undoIds = left
            if left.isEmpty { self.state.undoInverse = nil }
            self.state.undoNote = failure.map { "\(redone.count) of \(ids.count) reversed. \($0)" }
            self.state.actionError = failure
            if !redone.isEmpty { self.load(replace: true) }
        }
    }

    func dismissUndo() {
        state.undoIds = []
        state.undoInverse = nil
        state.undoNote = nil
    }

    /// Write the advanced filters and re-project. No network call: these run
    /// on the loaded pages, which is what the sheet's scope line says.
    func setAdvanced(from: String, to: String, subject: String, files: Bool,
                     after: String, before: String) {
        state.advFrom = from; state.advTo = to; state.advSubject = subject
        state.advFiles = files; state.advAfter = after; state.advBefore = before
        reproject()
    }

    func clearAdvanced() { setAdvanced(from: "", to: "", subject: "", files: false, after: "", before: "") }

    private var pendingRowActions: Set<String> = []

    func actOn(_ id: String, _ action: MailAction) {
        guard !state.busy, let token, pendingRowActions.insert(id).inserted else { return }
        Task { [weak self] in
            guard let self else { return }
            defer { self.pendingRowActions.remove(id) }
            // ── Optimistic first paint ─────────────────────────────────────────
            // The row answers the gesture before the network does: the flag
            // flips in place, or the filed row leaves the screen, in the same
            // frame as the swipe. The server's answer is still the truth — a
            // refused write re-reads the box below, the row comes back, and the
            // error names what did not go through. Nothing here claims a
            // delivery: the undo banner is raised only after the server
            // accepted, the same rule the bulk path keeps.
            let source: MailBox?
            switch action {
            case .unstar: source = .starred
            case .restore: source = .trash
            case .unarchive: source = .archive
            case .unspam: source = .spam
            case .unsnooze: source = .snoozed
            default: source = nil
            }
            let removesRow = action == .trash || action == .archive ||
                action == .spam || action == .snooze || source == self.state.box
            await self.applyLocally(id: id, action: action)
            if removesRow {
                self.state.rows.removeAll { $0.id == id }
            } else {
                await self.projectCurrentRows()
            }
            // The swipe is the one filing the person cannot see land, so the tap
            // is the feedback — it fires with the movement now, and a refusal
            // corrects it below.
            switch action {
            case .star, .unstar, .read, .unread: GMHaptics.marking()
            default: GMHaptics.filing()
            }
            do {
                _ = try await ApiClient.mailAction(token: token, id: id, action: action, until: nil)
                guard self.sessionIsLive else { return }
                await self.invalidateFor(action)
                self.refreshCounts(token, force: false)
                // The undo offer must not outlive the mail it describes: snooze three,
                // swipe one to Trash, press Undo, and the trashed message is
                // unsnoozed too. `act()` clears for bulk actions; this path needed
                // the equivalent for the single row.
                if self.state.snoozeUndoIds.contains(id) {
                    let left = self.state.snoozeUndoIds.filter { $0 != id }
                    self.state.snoozeUndoIds = left
                    if left.isEmpty { self.state.snoozeUndoNote = nil }
                }
                // A swipe is the most common filing there is, and the mail
                // leaves the screen the instant it succeeds -- the banner must
                // offer the reverse here too, or the single most undoable
                // action would have no path back (Android's `actOn` learned the
                // same lesson from the same gap). Raised here, after the server
                // accepted — never on the optimistic frame.
                if let inv = action.inverse() {
                    self.state.undoIds = [id]
                    self.state.undoInverse = inv
                    self.state.undoNote = action.pastLine(1)
                }
                // Reconcile the bookkeeping the optimistic paint could not know:
                // the box's totals and the neighbouring pages. First paint comes
                // from the store, so this lands behind the row the person
                // already saw move.
                if source == self.state.box {
                    self.load(forceCounts: false)
                } else if action == .restore || action == .unarchive || action == .unspam || action == .unsnooze {
                    self.load(forceCounts: false)
                } else {
                    await self.projectCurrentRows()
                }
            } catch {
                GMHaptics.failure()
                // The write was refused, so the optimistic move is not the truth.
                // Re-read the box — the row returns exactly where the server says
                // it is — and say what failed.
                self.loadedBoxes.remove(self.state.box)
                await MailStore.shared.invalidate(self.state.box)
                self.load(replace: true)
                self.state.actionError = Self.describe(error)
            }
        }
    }

    private func applyLocally(id: String, action: MailAction) async {
        let flags: (read: Bool?, starred: Bool?)
        switch action {
        case .read: flags = (true, nil)
        case .unread: flags = (false, nil)
        case .star: flags = (nil, true)
        case .unstar: flags = (nil, false)
        case .trash, .archive, .spam, .snooze:
            await MailStore.shared.removeEverywhere(id: id)
            return
        default:
            return
        }
        await MailStore.shared.patchFlags(id: id, read: flags.read, starred: flags.starred)
        if let index = thread.messages.firstIndex(where: { $0.id == id }) {
            var message = thread.messages[index]
            message.isRead = flags.read ?? message.isRead
            message.isStarred = flags.starred ?? message.isStarred
            thread.messages[index] = message
        }
    }

    private func invalidateFor(_ action: MailAction) async {
        switch action {
        case .star, .unstar:
            loadedBoxes.remove(.starred)
            await MailStore.shared.invalidate(.starred)
        case .trash: await MailStore.shared.invalidate(.trash)
        case .archive: await MailStore.shared.invalidate(.archive)
        case .spam: await MailStore.shared.invalidate(.spam)
        case .restore, .unarchive, .unspam: await MailStore.shared.invalidateAllBoxes()
        case .snooze:
            await MailStore.shared.invalidate(.snoozed)
        case .unsnooze:
            await MailStore.shared.invalidate(.inbox)
            await MailStore.shared.invalidate(.snoozed)
        default: break
        }
    }

    /// Permanent destruction, from Trash only. Separate from `act` because it is
    /// the one action that cannot be undone and therefore never rides along with a
    /// bulk "delete" — the reference's Delete button trashes; this is the second,
    /// explicit step the design's confirmation screen is for.
    func deletePermanently() {
        let ids = Array(state.selection)
        guard state.box == .trash, !ids.isEmpty, !state.busy, pendingRowActions.isEmpty, let token else { return }
        state.busy = true
        Task { [weak self] in
            guard let self else { return }
            let written = await self.parallelWrites(
                ids,
                write: { try await ApiClient.mailDeletePermanent(token: token, id: $0) },
                // Only on success. The old order ran the cache removal even after the
                // server refused, so a refused delete emptied the row from a list
                // whose mail still exists -- and dropping its label pairs from a map
                // the sheet will not re-read let the Label sheet later claim `off`
                // for a label the living message still carries.
                onAccept: { id in
                    await MailStore.shared.removeEverywhere(id: id)
                    await MailStore.shared.forgetBody(id)
                    // The message is gone, so its label pairs are gone with it. Left in
                    // the map they could resurface as a tick on mail that does not exist.
                    await MailStore.shared.forgetAssignments(id)
                },
            )
            let removed = Set(written.done)
            let failure = written.failure
            guard self.sessionIsLive, !written.stopped else { return }
            var updated = self.state
            updated.busy = false
            updated.selection.subtract(removed)
            updated.actionError = failure
            updated.rows.removeAll { removed.contains($0.id) }
            self.state = updated
            if !removed.isEmpty { self.load(replace: true) }
        }
    }

    // ── labels ───────────────────────────────────────────────────────────────

    /// Read the account's label pairs, if that has not already happened.
    ///
    /// Asked once per session rather than once per sheet opening: the route returns
    /// the whole set, so a second call buys nothing the first did not already
    /// contain, and a sheet that re-read on every appearance inherits the hub's
    /// defect — a failed refresh presenting itself as a failed load.
    func ensureAssignments() {
        if state.labelsRead != .read, !labelState.loading { refreshLabels() }
        // A note from the previous selection must not still be there when the sheet
        // opens for a different set of messages.
        state.labelNote = nil
        switch state.assignmentsRead {
        case .reading: return
        case .read: refreshMarks()
        default: loadAssignments()
        }
    }

    private func loadAssignments() {
        let ids = Array(state.selection)
        guard !ids.isEmpty, let token else { return }
        state.assignmentsRead = .reading
        state.labelNote = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                let resp = try await ApiClient.mailLabelAssignments(token)
                guard sessionIsLive else {
                    // The flag has to leave `.reading` even when the read was
                    // abandoned: `ensureAssignments` short-circuits on `.reading`, so
                    // a sign-out during one label read would stop every later one for
                    // the life of the model — which is a `@StateObject` that lives as
                    // long as the process. The sheet would go on drawing marks and the
                    // tap direction would stay locked to "only ever add", making the
                    // detach unreachable for the rest of the session. Resolving to the
                    // truth about whatever cache survives is the honest answer.
                    let stillThere = await MailStore.shared.assignmentsMap() != nil
                    self.state.assignmentsRead = stillThere ? .read : .failed
                    return
                }
                await MailStore.shared.putAssignments(resp.assignments)
                self.state.assignmentsRead = .read
                await self.applyMarks()
            } catch {
                // Either way the flag has to leave `.reading`, or the failure is
                // unnoticeable forever: `ensureAssignments` short-circuits on
                // `.reading`, so a state left there silently stops every later read
                // for the session and the sheet keeps drawing marks computed from an
                // empty map.
                //
                // With a cached map the marks on screen stay (a refresh may not blank
                // what is displayed) and the state resolves to `.read`, which is the
                // truth about the cache; without one, `.failed`, which is the honest
                // unknown. Both report the failure.
                let hadMap = await MailStore.shared.assignmentsMap() != nil
                self.state.assignmentsRead = hadMap ? .read : .failed
                self.state.staleError = Self.describe(error)
                if hadMap { await self.applyMarks() }
            }
        }
    }

    func refreshMarks() {
        Task { await applyMarks() }
    }

    /// Recompute the sheet's marks from the cache. Cheap, and never a network call.
    ///
    /// `filter{…}.count` rather than `count { … }`: no such overload exists on
    /// Array (`count(where:)` was withdrawn from the standard library), and it is
    /// the kind of call that reads as obvious until the compiler rejects it.
    private func applyMarks() async {
        let ids = Array(state.selection)
        let map = await MailStore.shared.assignmentsMap()
        var marks: [String: LabelMark] = [:]
        for l in state.labels {
            let with = ids.filter { (map?[$0] ?? []).contains(l.id) }.count
            if map == nil || ids.isEmpty { marks[l.id] = .unknown }
            else if with == 0 { marks[l.id] = .off }
            else if with == ids.count { marks[l.id] = .on }
            else { marks[l.id] = .mixed }
        }
        state.labelMarks = marks
    }

    /// Tap a label in the sheet: apply it to the selection, or take it off.
    ///
    /// The direction comes from the state the read produced, and both ambiguous
    /// cases resolve toward ADDING rather than removing:
    ///
    ///  - `mixed` completes the label across the selection. Half-checked means the
    ///    user is mid-operation on a group; finishing it is what a mail client does,
    ///    and un-tagging the ones that have it would discard work nobody asked to
    ///    discard.
    ///  - `unknown` (the read never answered) only ever assigns. Both writes are
    ///    idempotent server-side, so adding a label that happens to be there costs
    ///    nothing, while REMOVING on an unknown baseline could take a label off mail
    ///    the user never meant to touch.
    ///
    /// The cache is patched only for the messages the server accepted, and the sheet
    /// stays open to show the result: nothing in the list changes visibly here, so a
    /// sheet that closed would leave the user without an answer.
    func toggleLabel(_ labelId: String) {
        let ids = Array(state.selection)
        guard !ids.isEmpty, let token else { return }
        let mark = state.labelMarks[labelId] ?? .unknown
        let remove = (mark == .on)
        state.busy = true
        Task { [weak self] in
            guard let self else { return }
            // On `mixed` only the messages that lack it are written; on `on`, only
            // the ones carrying it. Writing all of either way would report a count
            // the server never changed.
            let map = await MailStore.shared.assignmentsMap()
            let targets = ids.filter { id in
                let has = (map?[id] ?? []).contains(labelId)
                return remove ? has : !has
            }
            guard !targets.isEmpty else {
                self.state.busy = false
                return
            }
            var done = 0
            var failure: String?
            let labelName = self.state.labels.first { $0.id == labelId }?.name ?? "label"
            for id in targets {
                do {
                    if remove { try await ApiClient.mailLabelDetach(token: token, messageId: id, labelId: labelId) }
                    else { try await ApiClient.mailLabelAssign(token: token, messageId: id, labelId: labelId) }
                    done += 1
                    if remove { await MailStore.shared.markUnassigned(messageId: id, labelId: labelId) }
                    else { await MailStore.shared.markAssigned(messageId: id, labelId: labelId) }
                } catch { failure = Self.describe(error) }
            }
            self.state.busy = false
            // A switch, not a chain of nested ternaries. Three of these sentences
            // depend on two values and a ternary tree deep enough to pick between
            // them is exactly the expression shape this app's build has already
            // timed out type-checking in.
            let note: String
            // `done == 0` tested first. `Self.describe` always yields a String, so
            // unlike Android's nullable `it.message` no failure can go unreported
            // here -- but the ORDER still decides whether a zero-count write reads
            // as a success, and "Labelled 0 messages" over a selection that was not
            // labelled is the same lie in a nicer form.
            if done == 0 {
                note = failure ?? "The label was not saved."
            } else if let f = failure {
                note = "\(done) of \(targets.count) saved. \(f)"
            } else if remove {
                note = "Removed \"\(labelName)\" from \(done)"
            } else {
                note = done == 1 ? "Labelled 1 message" : "Labelled \(done) messages"
            }
            self.state.labelNote = note
            await self.applyMarks()
        }
    }

    // ── people ───────────────────────────────────────────────────────────────

    /// The contacts the app can actually name: everyone who appears in the mail
    /// it has loaded. There is no contacts endpoint on the GrapMail API — the web
    /// client derives people the same way, from `?box=inbox` plus `?box=sent`
    /// (`src/app/(mail)/mail/contacts/page.tsx:26-28`). Saying "contacts" while
    /// reading the mailbox is honest; showing a list that looks like an address
    /// book the user never filled in would not be.
    nonisolated func people() async -> [Person] {
        var counts: [String: (name: String?, seen: Int)] = [:]
        var seenMessages: Set<String> = []
        for box in MailBox.tabOrder {
            guard let page = await MailStore.shared.page(box, nil) else { continue }
            for m in page.messages {
                guard seenMessages.insert(m.id).inserted else { continue }
                if let a = m.from?.address?.nilIfBlank() {
                    let prior = counts[a]
                    counts[a] = (m.from?.name ?? prior?.name, (prior?.seen ?? 0) + 1)
                }
                for a in m.to where !a.isEmpty {
                    if counts[a] == nil { counts[a] = (nil, 0) }
                }
            }
        }
        return counts.map { Person(address: $0.key, name: $0.value.name, seen: $0.value.seen) }
            .sorted { ($0.seen, $1.seen) != ($1.seen, $0.seen)
                ? $0.seen > $1.seen
                : $0.address.lowercased() < $1.address.lowercased() }
    }

    // ── attachments ──────────────────────────────────────────────────────────

    /// Attachments the app has actually seen. Partial by construction — the Files
    /// screen says so, because there is no list-all attachments route.
    @Published private(set) var filesRevision = 0

    nonisolated func file(_ id: String) async -> FileEntry? {
        guard let entry = await MailStore.shared.indexedAttachment(id) else { return nil }
        return FileEntry(attachment: entry.attachment, fromMessage: entry.subject)
    }

    nonisolated func files() async -> [FileEntry] {
        await MailStore.shared.indexedAttachments().map {
            FileEntry(attachment: $0.attachment, fromMessage: $0.subject)
        }
    }

    /// Take one attachment out of the message for good.
    ///
    /// `DELETE /attachments/:id` is scoped to the caller's own rows
    /// (`grapmail-attachments.ts:412`), and the answer is the route's, not the
    /// app's guess. The body cache is dropped on success because the row the
    /// app is holding still lists a file that no longer exists -- leaving it
    /// would let the Files screen offer a download of bytes the server has
    /// thrown away.
    func deleteAttachment(_ id: String, then: @escaping (Bool, String) -> Void) {
        guard let token else { then(false, "Sign in again to remove this."); return }
        Task { [weak self] in
            guard let self else { return }
            do {
                let r = try await ApiClient.mailDeleteAttachment(token, id: id)
                guard self.sessionIsLive else { return }
                if r.success {
                    await MailStore.shared.forgetAttachment(id)
                    await MailStore.shared.invalidateAllBoxes()
                    self.loadedBoxes.removeAll()
                    self.thread.messages = self.thread.messages.map { message in
                        var updated = message
                        updated.attachments.removeAll { $0.id == id }
                        if updated.attachments.count != message.attachments.count {
                            updated.hasAttachments = !updated.attachments.isEmpty
                        }
                        return updated
                    }
                    self.filesRevision &+= 1
                    then(true, "Removed.")
                } else {
                    then(false, r.error ?? "The server refused to remove that file.")
                }
            } catch {
                then(false, Self.describe(error))
            }
        }
    }

    func downloadAttachment(_ a: MailAttachment, then: @escaping (String) -> Void) {
        guard let token else { then("Sign in again to save this."); return }
        Task {
            do {
                let bytes = try await ApiClient.mailAttachmentBytes(token: token, id: a.id)
                do {
                    let url = try await Self.writeAttachment(bytes, name: a.name, temporary: false)
                    then("Saved as \(url.lastPathComponent) in the Files app.")
                } catch {
                    then("Downloaded, but the file could not be written.")
                }
            } catch {
                then(Self.describe(error))
            }
        }
    }

    func prepareAttachment(_ a: MailAttachment, then: @escaping (URL?, String?) -> Void) {
        guard let token else { then(nil, "Sign in again to open this."); return }
        Task {
            do {
                let bytes = try await ApiClient.mailAttachmentBytes(token: token, id: a.id)
                let url = try await Self.writeAttachment(bytes, name: a.name, temporary: true)
                then(url, nil)
            } catch {
                then(nil, Self.describe(error))
            }
        }
    }

    private nonisolated static func writeAttachment(_ bytes: Data, name: String?, temporary: Bool) async throws -> URL {
        let sanitized = String((name ?? "").prefix(60).map { character in
            (character.isLetter || character.isNumber || character == "." || character == "_" || character == "-")
                ? character : "_"
        })
        let filename = sanitized.isEmpty || sanitized == "." || sanitized == ".." ? "attachment" : sanitized
        let files = FileManager.default
        let directory: URL
        if temporary {
            directory = files.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
            try files.createDirectory(at: directory, withIntermediateDirectories: true)
        } else {
            guard let documents = files.urls(for: .documentDirectory, in: .userDomainMask).first else {
                throw CocoaError(.fileNoSuchFile)
            }
            directory = documents
        }
        let url = directory.appendingPathComponent(filename)
        try bytes.write(to: url, options: .atomic)
        return url
    }

    /// Upload bytes picked by the compose screen. The route answers with the row
    /// it wrote, and the returned id is what a send carries — an attachment that
    /// was never uploaded is a filename the recipient cannot open.
    func uploadAttachment(bytes: Data, filename: String, mimeType: String,
                          then: @escaping (MailAttachment?, String) -> Void) {
        guard let token else { then(nil, "Sign in again to attach a file."); return }
        Task {
            do {
                let a = try await ApiClient.mailAttachmentUpload(
                    token: token, bytes: bytes, filename: filename, mimeType: mimeType)
                then(a, "Attached \(a.name ?? filename).")
            } catch {
                then(nil, Self.describe(error))
            }
        }
    }

    // ── thread ───────────────────────────────────────────────────────────────

    /// Opening a thread. The list payload has no bodies by design, so the bodies
    /// are read here and memoised — a row re-rendering must not re-read a payload.
    func openThread(_ threadId: String) {
        guard let token, readerTarget != nil || threadLoadedFor != threadId else { return }
        loadReader(threadId, isThread: true) { [weak self] in
            guard let self else { return }
            do {
                let resp = try await ApiClient.mailThread(token: token, threadId: threadId)
                guard !Task.isCancelled, self.sessionIsLive else { return }
                let messages = await MailStore.shared.putBodies(resp.messages)
                guard !Task.isCancelled, self.sessionIsLive else { return }
                self.thread = ThreadState(messages: messages)
                self.threadLoadedFor = threadId
            } catch {
                guard !Task.isCancelled, self.sessionIsLive else { return }
                self.thread = ThreadState(error: Self.describe(error))
            }
        }
    }

    /// The neighbours of an open message, in the order the list showed them.
    ///
    /// A mail reader that cannot step makes the person walk back to the list
    /// for every message. The range is stated honestly: this walks the rows
    /// this device actually loaded for the current box, and the reader's own
    /// label says "of N loaded" rather than claiming the box's total, which
    /// is a number nothing here has seen.
    ///
    /// Nil on either side when there is nothing to step to, so the chevron is
    /// disabled rather than tapping into nowhere.
    func neighbours(of id: String) -> (prev: MailStep?, next: MailStep?) {
        let rows = state.rows
        guard let idx = rows.firstIndex(where: { $0.id == id || $0.threadId == id })
        else { return (nil, nil) }
        return (step(of: idx > 0 ? rows[idx - 1] : nil),
                step(of: idx + 1 < rows.count ? rows[idx + 1] : nil))
    }

    /// The row as the reader must open it: a row with a `threadId` is a
    /// conversation and goes to the thread route, a row without one is a single
    /// message and the thread route answers its id with a 404. The list's own
    /// tap makes exactly this pair of decisions, so the stepper derives the
    /// target from the row instead of assuming every neighbour is a thread.
    func step(of row: MailMessage?) -> MailStep? {
        guard let row = row else { return nil }
        return MailStep(id: row.threadId ?? row.id, isThread: row.threadId != nil)
    }

    /// The reader's position within the loaded rows, 1-based; 0 when absent.
    func loadedPosition(of id: String) -> Int {
        guard let idx = state.rows.firstIndex(where: { $0.id == id || $0.threadId == id })
        else { return 0 }
        return idx + 1
    }

    /// How many loaded rows the reader is stepping between, for its own label.
    func loadedRowCount() -> Int { state.rows.count }

    /// Mark one message unread from inside the reader, and report the outcome to
    /// the reader.
    ///
    /// `actOn` is the list's path: it files a failure into the list's stale-read
    /// banner, which is not on screen here, and it leaves the conversation's own
    /// copy of the message flagged as read. A control in the reader needs both
    /// halves — the thread's flag patched, so the button that offered this stops
    /// offering it, and the real result handed back to be said out loud. `nil`
    /// means the server accepted it. Android's `markUnread` is the same contract.
    func markUnread(_ id: String, onOutcome: @escaping (String?) -> Void) {
        guard let token else {
            onOutcome("You are signed out, so this could not be saved.")
            return
        }
        Task { [weak self] in
            guard let self else { return }
            do {
                _ = try await ApiClient.mailAction(token: token, id: id,
                                                   action: .unread, until: nil)
                await self.applyLocally(id: id, action: .unread)
                self.reproject()
                GMHaptics.marking()
                onOutcome(nil)
            } catch {
                GMHaptics.failure()
                onOutcome(Self.describe(error))
            }
        }
    }

    func viewMessage(_ id: String) async -> String? {
        guard !snapshotSeeded, let message = thread.messages.first(where: { $0.id == id }) else { return nil }
        let needsAttachments = message.hasAttachments && message.attachments.isEmpty
        guard needsAttachments || (!message.isRead && message.direction == "inbound") else { return nil }
        guard let token else { return "Sign in again." }
        do {
            let details: MailMessageFull?
            if needsAttachments {
                details = try await ApiClient.mailMessage(token: token, id: id).message
                guard details != nil else { return "Attachment details are unavailable." }
            } else {
                _ = try await ApiClient.mailAction(token: token, id: id, action: .read)
                details = nil
            }
            guard !Task.isCancelled, sessionIsLive else { return nil }
            let index = thread.messages.firstIndex(where: { $0.id == id })
            var viewed = index.map { thread.messages[$0] } ?? message
            if let details { viewed.attachments = details.attachments }
            viewed.isRead = details?.isRead ?? true
            if let index { thread.messages[index] = viewed }
            await MailStore.shared.putBody(viewed)
            guard !Task.isCancelled, sessionIsLive else { return nil }
            if viewed.isRead {
                await MailStore.shared.patchFlags(id: id, read: true)
                reproject()
            }
            return nil
        } catch {
            return Task.isCancelled ? nil : Self.describe(error)
        }
    }

    func openMessage(_ id: String) {
        guard let token, readerTarget != nil || threadLoadedFor != id else { return }
        loadReader(id, isThread: false) { [weak self] in
            guard let self else { return }
            if let cached = await MailStore.shared.body(id) {
                guard !Task.isCancelled, self.sessionIsLive else { return }
                // Recorded on the hit as well as the fetch, because this field IS the
                // memo: without it the guard above never fires and every return to an
                // already-read message costs another actor hop. What is deliberately
                // NOT changed here is the synchronous `loading` state above — the
                // cache can only be consulted across an `await`, and clearing the
                // spinner instead of raising it would leave the PREVIOUS message on
                // screen during that hop. A brief loader over the right content beats
                // a moment of the wrong content, which is the trade the reviewer's
                // suggested shape would have made.
                self.threadLoadedFor = id
                self.thread = ThreadState(messages: [cached])
                return
            }
            do {
                let resp = try await ApiClient.mailMessage(token: token, id: id)
                guard !Task.isCancelled, self.sessionIsLive else { return }
                if let m = resp.message {
                    await MailStore.shared.putBody(m)
                    guard !Task.isCancelled, self.sessionIsLive else { return }
                    self.threadLoadedFor = id
                    self.thread = ThreadState(messages: [m])
                    await MailStore.shared.patchFlags(id: m.id, read: true)
                } else {
                    self.thread = ThreadState(error: "This message could not be opened.")
                }
                self.reproject()
            } catch {
                guard !Task.isCancelled, self.sessionIsLive else { return }
                self.thread = ThreadState(error: Self.describe(error))
            }
        }
    }

    // ── the reader's formatted body ──────────────────────────────────────────

    /// One render per message. The html IS the server's answer: the body
    /// endpoint sanitized it with the web's untrusted-email profile before it
    /// left the app's origin, so this map is the only source a WebView is ever
    /// fed from. `allowImages` is stored with the render because the two
    /// variants are different answers, not a reload of one: without it, an
    /// images-off render would be mistaken for the opt-in one and the gate's
    /// second fetch would be skipped.
    struct BodyRender: Equatable {
        let html: String?
        let bodyText: String?
        let blockedImages: Int
        let allowImages: Bool
        let error: String?
    }

    private final class CachedBody {
        let render: BodyRender
        init(_ render: BodyRender) { self.render = render }
    }

    private let formattedBodyCache: NSCache<NSString, CachedBody> = {
        let cache = NSCache<NSString, CachedBody>()
        cache.countLimit = 24
        cache.totalCostLimit = 8 * 1024 * 1024
        return cache
    }()

    private var prefetchTask: Task<Void, Never>?

    /// The first screen's bodies, fetched before anyone taps a row.
    ///
    /// A reader open used to pay one round trip for the formatted body on every
    /// message — dead air a mail app does not need, because the rows a person
    /// is most likely to open are knowable the moment the page lands. This
    /// fills the same cache `loadBody` reads, with the same images-blocked
    /// render the reader defaults to, so the first open is a cache hit and the
    /// round trip happens while the list is being read. Sequential: the server
    /// rate-limits bursts, and the point is warmth, not speed of warmth.
    private func prefetchBodies() {
        guard !snapshotSeeded, prefetchTask == nil, let token else { return }
        let ids = state.rows.prefix(6).map(\.id)
        guard !ids.isEmpty else { return }
        prefetchTask = Task { [weak self] in
            defer { self?.prefetchTask = nil }
            for id in ids {
                guard let self, !Task.isCancelled, self.sessionIsLive else { return }
                if self.formattedBodyCache.object(forKey: id as NSString) != nil { continue }
                _ = try? await self.loadBody(id, allowImages: false)
            }
        }
    }

    func loadBody(_ messageId: String, allowImages: Bool) async throws -> BodyRender {
        if snapshotSeeded { return mailSnapshotBodyRender(id: messageId) }
        let key = messageId as NSString
        if !allowImages, let cached = formattedBodyCache.object(forKey: key) { return cached.render }
        guard let token else {
            return BodyRender(html: nil, bodyText: nil, blockedImages: 0,
                              allowImages: allowImages, error: "Sign in again.")
        }
        do {
            let response = try await ApiClient.mailBody(token: token, id: messageId, allowImages: allowImages)
            try Task.checkCancellation()
            guard sessionIsLive else { throw CancellationError() }
            let html = response.bodyHtml?.nilIfBlank()
            let render = BodyRender(html: html, bodyText: nil, blockedImages: response.blockedImages,
                                    allowImages: allowImages, error: html == nil ? "The formatted body was empty." : nil)
            if !allowImages, let html {
                let length = html.utf16.count
                if length <= 262_144 {
                    formattedBodyCache.setObject(CachedBody(render), forKey: key, cost: length * 2)
                }
            }
            return render
        } catch {
            try Task.checkCancellation()
            guard sessionIsLive else { throw CancellationError() }
            return BodyRender(html: nil, bodyText: nil, blockedImages: 0,
                              allowImages: allowImages, error: Self.describe(error))
        }
    }

    // ── compose ──────────────────────────────────────────────────────────────

    func composeUpdate(_ transform: (inout ComposeState) -> Void) {
        transform(&compose)
        scheduleAutosave()
    }

    // ── autosave ─────────────────────────────────────────────────────────────

    /// The compose box saves itself. Every edit schedules a debounced draft
    /// write; the first save inserts and records the row's id, and every save
    /// after it updates that row in place, so a compose that stays open for an
    /// afternoon is ONE draft, not one draft per keystroke batch.
    private var composeGeneration = 0
    private var autosaveTask: Task<Void, Never>?
    private var autosaveInFlight = false
    private var autosaveNeedsFollowUp = false

    /// The rules the debounce obeys, each one a refusal rather than a feature:
    /// an EMPTY compose saves nothing (a person opening the composer and
    /// leaving must not find an empty draft row named after nothing); a send
    /// or a manual save in flight is not interrupted; and the autosave never
    /// writes the receipt — its outcome is the composer's own quiet line, not
    /// the "Saved to Drafts" banner the manual save earns.
    private func scheduleAutosave() {
        let s = compose
        if s.sending || s.accepted { return }
        if autosaveInFlight {
            autosaveNeedsFollowUp = true
            return
        }
        autosaveTask?.cancel()
        if s.to.trimmingCharacters(in: .whitespaces).isEmpty
            && s.cc.trimmingCharacters(in: .whitespaces).isEmpty
            && s.subject.trimmingCharacters(in: .whitespaces).isEmpty
            && s.body.trimmingCharacters(in: .whitespaces).isEmpty {
            return
        }
        autosaveTask = Task { [weak self] in
            guard let self else { return }
            do {
                try await Task.sleep(nanoseconds: 2_000_000_000)
            } catch {
                return // cancelled by a newer edit; that edit schedules its own save
            }
            guard let token = self.token, !self.compose.sending, !self.autosaveInFlight else { return }
            self.autosaveInFlight = true
            self.autosaveNeedsFollowUp = false
            defer {
                self.autosaveInFlight = false
                if self.autosaveNeedsFollowUp, !Task.isCancelled {
                    self.autosaveNeedsFollowUp = false
                    self.scheduleAutosave()
                }
            }
            self.compose.autosaving = true
            do {
                let resp = try await ApiClient.mailDraft(
                    token: token,
                    MailDraftRequest(
                        to: Self.splitAddresses(self.compose.to), cc: Self.splitAddresses(self.compose.cc),
                        subject: self.compose.subject, bodyText: self.compose.body,
                        inReplyTo: self.compose.inReplyTo,
                        id: self.compose.draftId.nilIfBlank(),
                    ))
                guard !Task.isCancelled, self.sessionIsLive else { return }
                self.compose.autosaving = false
                self.compose.autosaveNote = "Draft saved"
                if let newId = resp.id { self.compose.draftId = newId }
                self.loadedBoxes.remove(.drafts)
                await MailStore.shared.invalidate(.drafts)
            } catch {
                guard !Task.isCancelled, self.sessionIsLive else { return }
                self.compose.autosaving = false
                self.compose.autosaveNote = "Draft not saved — it will be saved on the next change."
            }
        }
    }

    func startReply(_ m: MailMessage) {
        compose.to = m.from?.address ?? ""
        compose.subject = Self.replySubject(m.subject)
        compose.inReplyTo = m.id
    }

    /// Prepare a reply, reply-all or forward from the FULL message row.
    ///
    /// The quote is built from what the server actually said the message
    /// contains (`GET /message/:id` -- bodyText, the to/cc lists, the date),
    /// never from the snippet the list row carries. Reply-all adds the
    /// original To and Cc minus this account's own address, because "reply
    /// all" that drops the thread's other recipients is a lie about who will
    /// read it. Forward carries no In-Reply-To: the server threads replies by
    /// that header, and a forwarded message is not a reply.
    func prepareReply(id: String, mode: String, signature: String) {
        guard let token else { return }
        let generation = composeGeneration
        Task { [weak self] in
            guard let self else { return }
            guard let full = try? await ApiClient.mailMessage(token: token, id: id),
                  let m = full.message, self.composeGeneration == generation,
                  self.sessionIsLive else { return }
            let me = self.state.accountAddress?.lowercased()
            let quoted = Self.quoteOf(m)
            let sig = Self.signatureBlock(signature)
            switch mode {
            // A draft is not a reply: the row IS the person's own
            // half-written message, so it loads verbatim — no quote block,
            // no "Re:", no second signature (the draft already carries the
            // one they saved). This is the leg the app was missing: tapping
            // a draft opened a read-only thread, so a saved message could
            // never be finished or sent.
            case "draft":
                self.compose.to = m.to.joined(separator: ", ")
                self.compose.cc = m.cc.joined(separator: ", ")
                // No bcc: the message DTO has no bcc field, because the
                // route strips it before storing — a draft's hidden
                // recipients are genuinely not recoverable, so the composer
                // leaves the field empty rather than showing a list it
                // invented.
                self.compose.subject = m.subject ?? ""
                self.compose.body = m.bodyText ?? ""
                self.compose.inReplyTo = m.inReplyTo
                self.compose.draftId = m.id
                self.compose.result = nil
                self.compose.resultStatus = nil
                self.compose.error = nil
            case "all":
                var seen = Set<String>()
                let others = ([m.from?.address].compactMap { $0 } + m.to + m.cc)
                    .map { $0.trimmingCharacters(in: .whitespaces) }
                    .filter { !$0.isEmpty && $0.lowercased() != me }
                    .filter { seen.insert($0.lowercased()).inserted }
                self.compose.to = others.joined(separator: ", ")
                self.compose.subject = Self.replySubject(m.subject)
                self.compose.body = quoted + sig
                self.compose.inReplyTo = m.id
            case "forward":
                let head = "Forwarded message\n" +
                    "From: \(m.from?.display ?? m.from?.address ?? "")\n" +
                    "Date: \(m.createdAt ?? "")\n" +
                    "Subject: \(m.subject ?? "(no subject)")\n" +
                    "To: \(m.to.joined(separator: ", "))\n\n"
                self.compose.to = ""
                self.compose.subject = Self.forwardSubject(m.subject)
                self.compose.body = head + quoted + sig
                self.compose.inReplyTo = nil
            default:
                self.compose.to = m.from?.address ?? ""
                self.compose.subject = Self.replySubject(m.subject)
                self.compose.body = quoted + sig
                self.compose.inReplyTo = m.id
            }
        }
    }

    /// The quote block: every body line prefixed. An empty body quotes
    /// nothing -- inventing a blank line under the header would claim the
    /// message had a body.
    static func quoteOf(_ m: MailMessageFull) -> String {
        let who = m.from?.display ?? m.from?.address ?? ""
        let body = (m.bodyText ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if body.isEmpty {
            return "\n\nOn \(m.createdAt ?? "an unknown date"), \(who) wrote:\n"
        }
        let lines = body.components(separatedBy: "\n")
            .map { $0.isEmpty ? ">" : "> \($0)" }
            .joined(separator: "\n")
        return "\n\nOn \(m.createdAt ?? "an unknown date"), \(who) wrote:\n\(lines)\n"
    }

    /// The signature arrives as EDITABLE text appended when the compose opens,
    /// the same way the web client appends it client-side -- what the person
    /// sees in the body is what is sent; nothing is stitched on behind the
    /// scenes at send time.
    static func signatureBlock(_ signature: String) -> String {
        signature.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "" : "\n\n-- \n\(signature)"
    }

    static func forwardSubject(_ s: String?) -> String {
        guard let t = s?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty
        else { return "Fwd:" }
        if t.hasPrefix("Fwd:") { return t }
        return "Fwd: \(t)"
    }

    private static func replySubject(_ s: String?) -> String {
        guard let t = s?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return "Re:" }
        if t.hasPrefix("Re:") || t.hasPrefix("Fwd:") { return t }
        return "Re: \(t)"
    }

    func resetCompose(initialRecipient: String = "") {
        guard !snapshotSeeded else { return }
        composeGeneration &+= 1
        autosaveNeedsFollowUp = false
        autosaveTask?.cancel()
        autosaveTask = nil
        compose = ComposeState(to: initialRecipient)
    }

    private func stopAutosaveDebounce() -> Task<Void, Never>? {
        autosaveNeedsFollowUp = false
        let pending = autosaveTask
        if !autosaveInFlight { pending?.cancel() }
        return pending
    }

    /// The compose-time plan, asked by the send path: mode and the keys to
    /// seal to. Nil when the directory did not answer — which the send treats
    /// as "not encrypted", never as "encrypt anyway": the difference between
    /// "the server said this cannot be encrypted" and "we could not ask"
    /// decides whether the message goes plain or not at all. Mirror of
    /// Android's `encryptionPlan`.
    func encryptionPlan(addresses: [String]) async -> MailE2ePlan? {
        guard let token else { return nil }
        return try? await ApiClient.mailE2ePlan(token, addresses: addresses).plan
    }

    /// Send. The receipt shows `status` and `deliveryError` as the server reported
    /// them — a message the queue has only accepted is not a delivered message,
    /// and saying otherwise is the exact dishonesty the send-status test exists to
    /// pin. With an envelope, the server stores its notice in place of the body:
    /// `bodyText` goes empty, because a sealed message's plaintext never
    /// travels — that is the whole claim the encrypt row makes.
    func send(e2eEnvelope: String? = nil, attachmentIds: [String] = []) {
        guard let token, !compose.sending, !compose.accepted else { return }
        // An empty To is refused here, not by the server: a message with no
        // recipient is not a send, and a receipt naming the server's 422 after
        // the person pressed Send twice is worse than a button that says why
        // it is waiting. Cc and Bcc do not count -- the route requires `to`.
        if Self.splitAddresses(compose.to).isEmpty {
            compose.error = "Add at least one recipient in To."
            return
        }
        compose.sending = true
        compose.error = nil
        compose.result = nil
        compose.resultStatus = nil
        // The envelope goes on the wire as the JSON OBJECT the route parses —
        // a string is silently dropped by sanitiseE2eEnvelope and the sealed
        // send would then 400 on its deliberate empty body (and worse: any
        // "fallback" to plaintext here would leak exactly what the envelope
        // exists to keep private). Unparseable text refuses the send whole.
        var envelopeJson: RawJSON? = nil
        if let env = e2eEnvelope {
            guard let parsed = RawJSON(object: env) else {
                compose.sending = false
                compose.error = "The encrypted envelope could not be prepared — nothing was sent."
                return
            }
            envelopeJson = parsed
        }
        let req = MailSendRequest(
            to: Self.splitAddresses(compose.to), cc: Self.splitAddresses(compose.cc),
            bcc: Self.splitAddresses(compose.bcc), subject: compose.subject,
            bodyText: envelopeJson == nil ? compose.body : "",
            inReplyTo: compose.inReplyTo, e2eEnvelope: envelopeJson, attachmentIds: attachmentIds)
        let generation = composeGeneration
        let pending = stopAutosaveDebounce()
        Task { [weak self] in
            await pending?.value
            guard let self, self.composeGeneration == generation, self.sessionIsLive else { return }
            let draft = self.compose.draftId
            do {
                let r = try await ApiClient.mailSend(token: token, req)
                guard self.sessionIsLive else { return }
                let sent = r.status == "sent" || r.status == "queued" || r.status == "delivered"
                if self.composeGeneration == generation {
                    var updated = self.compose
                    updated.sending = false
                    updated.result = r.message ?? r.error
                    updated.resultStatus = r.status
                    updated.error = r.deliveryError ?? r.error
                    if sent { updated.draftId = "" }
                    self.compose = updated
                }
                if sent {
                    self.loadedBoxes.remove(.sent)
                    self.loadedBoxes.remove(.inbox)
                    await MailStore.shared.invalidate(.sent)
                    await MailStore.shared.invalidate(.inbox)
                }
                // A draft that was just sent is no longer a draft. The
                // order is the whole point: the delete runs only AFTER the
                // server accepted the message, so a failed send leaves the
                // draft exactly where it was. Deleting first would trade a
                // half-written mail for an error message.
                if !draft.isEmpty && sent {
                    // The mail IS sent. A draft row that outlives its send
                    // is a stale copy, not a lost message, so a failed
                    // cleanup stays silent and does not touch the receipt.
                    if (try? await ApiClient.mailDeleteMessage(token: token, id: draft)) != nil, self.sessionIsLive {
                        self.loadedBoxes.remove(.drafts)
                        await MailStore.shared.invalidate(.drafts)
                    }
                }
                if sent, self.sessionIsLive { self.load(replace: true) }
                // The success tap is for the server's answer, not for the
                // button: a send the relay has only QUEUED gets the same tap a
                // delivered one would, and the receipt already says those are
                // different claims in words. A refused send gets the error tap.
                if sent { GMHaptics.success() }
                else { GMHaptics.failure() }
            } catch {
                guard self.composeGeneration == generation, self.sessionIsLive else { return }
                GMHaptics.failure()
                self.compose.sending = false
                self.compose.error = Self.describe(error)
            }
        }
    }

    /// Save as a draft. Discarding a half-written message without asking is the
    /// other way a mail app loses something; this is what the back button does.
    func saveDraft(onSaved: @escaping () -> Void = {}) {
        guard let token, !compose.sending, !compose.accepted else { return }
        let snapshot = compose
        let generation = composeGeneration
        let pending = stopAutosaveDebounce()
        compose.sending = true
        Task { [weak self] in
            await pending?.value
            guard let self, self.composeGeneration == generation, self.sessionIsLive else { return }
            let req = MailDraftRequest(
                to: Self.splitAddresses(snapshot.to), cc: Self.splitAddresses(snapshot.cc),
                subject: snapshot.subject, bodyText: snapshot.body, inReplyTo: snapshot.inReplyTo,
                id: self.compose.draftId.nilIfBlank())
            do {
                let resp = try await ApiClient.mailDraft(token: token, req)
                guard self.sessionIsLive else { return }
                if self.composeGeneration == generation {
                    var updated = self.compose
                    updated.sending = false
                    updated.result = "Saved to Drafts"
                    updated.resultStatus = "draft"
                    updated.error = nil
                    if let newId = resp.id { updated.draftId = newId }
                    self.compose = updated
                }
                self.loadedBoxes.remove(.drafts)
                await MailStore.shared.invalidate(.drafts)
                if self.composeGeneration == generation, self.sessionIsLive { onSaved() }
            } catch {
                guard self.composeGeneration == generation, self.sessionIsLive else { return }
                self.compose.sending = false
                self.compose.error = Self.describe(error)
            }
        }
    }

    static func splitAddresses(_ raw: String) -> [String] {
        raw.split(whereSeparator: { $0 == "," || $0 == ";" || $0 == "\n" })
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }

    // ── labels ──────────────────────────────────────────────────────────────

    private var labelLoad: Task<Void, Never>?
    private var labelsLoadedAt: TimeInterval?

    func refreshLabels(force: Bool = false) {
        // Not while the snapshot host owns these fields: its seed is the first
        // frame's content, and a live read landing afterwards would replace it
        // with the CI account's real (empty) label list -- which is how a
        // screenshot of a populated screen photographs its empty branch and still
        // passes a file-count gate. Android models this with a constructor flag.
        guard !snapshotSeeded, let token else { return }
        if !force, labelLoad != nil || labelsLoadedAt.map({ ProcessInfo.processInfo.systemUptime - $0 < 15 }) == true {
            return
        }
        labelLoad?.cancel()
        labelState.loading = true
        labelState.error = nil
        state.labelsRead = .reading
        labelLoad = Task { [weak self] in
            guard let self, !Task.isCancelled else { return }
            defer {
                if !Task.isCancelled {
                    self.labelLoad = nil
                    if self.labelState.loading { self.labelState.loading = false }
                }
            }
            do {
                let r = try await ApiClient.mailLabels(token)
                guard !Task.isCancelled, self.sessionIsLive else { return }
                await MailStore.shared.putLabels(r.labels)
                guard !Task.isCancelled, self.sessionIsLive else { return }
                self.labelsLoadedAt = ProcessInfo.processInfo.systemUptime
                self.labelState.labels = r.labels
                self.labelState.loading = false
                self.state.labels = r.labels
                self.state.labelsRead = .read
            } catch {
                guard !Task.isCancelled, self.sessionIsLive else { return }
                self.labelState.loading = false
                self.labelState.error = Self.describe(error)
                self.state.labelsRead = .failed
            }
        }
    }

    /// Create a label. The colour key the route reads is `colour`
    /// (`grapmail-labels.ts:105`); sending `color` creates an uncoloured label and
    /// still returns 200, so the mistake would be invisible.
    func createLabel(name: String, colour: String?, then: @escaping (Bool, String) -> Void) {
        guard let token else { then(false, "Sign in again."); return }
        Task {
            do {
                let r = try await ApiClient.mailCreateLabel(token: token, name: name, colour: colour)
                guard sessionIsLive else { return }
                guard r.success, let label = r.label else {
                    then(false, r.error ?? "The label could not be created.")
                    return
                }
                refreshLabels(force: true)
                then(true, "Created \(label.name).")
            } catch { then(false, Self.describe(error)) }
        }
    }

    /// Rename or recolour a label, then re-read the list. The response is not
    /// patched into the store: the route returns the row it wrote, and the list
    /// showing what the server holds beats the list showing what was typed.
    func renameLabel(id: String, name: String, colour: String?,
                     then: @escaping (Bool, String) -> Void) {
        guard let token else { then(false, "Sign in again."); return }
        Task {
            do {
                _ = try await ApiClient.mailRenameLabel(token: token, id: id,
                                                         name: name, colour: colour)
                guard sessionIsLive else { return }
                refreshLabels(force: true)
                then(true, "Saved.")
            } catch { then(false, Self.describe(error)) }
        }
    }

    func deleteLabel(id: String, then: @escaping (String) -> Void) {
        guard let token else { then("Sign in again."); return }
        Task {
            do {
                try await ApiClient.mailDeleteLabel(token: token, id: id)
                guard sessionIsLive else { return }
                refreshLabels(force: true)
                then("Label removed. Its messages keep their other labels.")
            } catch { then(Self.describe(error)) }
        }
    }

    // ── aliases ──────────────────────────────────────────────────────────────

    /// The addresses this mailbox also receives as, WITH the state of the read
    /// that produced them — the mirror of Android's `AliasesState`. The
    /// settings rows must not claim "no aliases on this mailbox" while the
    /// read is outstanding or after it died: "nobody answered" and "there are
    /// none" are different sentences, and only the second one is the server's.
    struct AliasesState {
        var read: AssignmentsRead = .notRead
        var list: [MailAlias] = []
        var error: String?
    }

    @Published var aliasesState = AliasesState()

    func loadAliases(force: Bool = false) {
        switch aliasesState.read {
        case .reading: return
        case .read: if !force { return }
        default: break
        }
        guard let token else {
            aliasesState.read = .failed
            aliasesState.error = "Sign in again."
            return
        }
        aliasesState.read = .reading
        Task {
            if let cached = await MailStore.shared.aliases() {
                aliasesState.read = .read
                aliasesState.list = cached
                return
            }
            do {
                let r = try await ApiClient.mailAliases(token)
                await MailStore.shared.putAliases(r.aliases)
                aliasesState.read = .read
                aliasesState.list = r.aliases
                aliasesState.error = nil
            } catch {
                // A failed read over a cache is still the cache's answer; a
                // failed read with nothing held is `failed`, and the screen
                // says so rather than showing an invented empty list.
                if let cached = await MailStore.shared.aliases() {
                    aliasesState.read = .read
                    aliasesState.list = cached
                } else {
                    aliasesState.read = .failed
                }
                aliasesState.error = Self.describe(error)
            }
        }
    }

    /// The addresses this mailbox also receives as. This is what the reference's
    /// account chip can honestly show: one signed-in account and its aliases, not
    /// a switcher between accounts the app cannot hold — `SessionStore` stores
    /// exactly one session.
    func aliases() async -> [MailAlias] {
        if let cached = await MailStore.shared.aliases() { return cached }
        guard let token, let r = try? await ApiClient.mailAliases(token) else { return [] }
        await MailStore.shared.putAliases(r.aliases)
        return r.aliases
    }

    // ── screener ─────────────────────────────────────────────────────────────

    /// Read the screener switch and its queue size. `enabled` stays nil until the
    /// server answers; a switch drawn as off after a failed read would claim
    /// inbound mail is unfiltered when nothing was said either way.
    private var screenerLoad: Task<Void, Never>?
    private var screenerSettingVersion = 0

    func loadScreener() {
        guard !snapshotSeeded, screenerLoad == nil, let token else { return }
        let version = screenerSettingVersion
        screenerLoad = Task { [weak self] in
            guard let self else { return }
            defer { self.screenerLoad = nil }
            async let pending = Self.readScreenerCount(token: token)
            do {
                let response = try await ApiClient.mailScreener(token)
                if self.sessionIsLive, self.screenerSettingVersion == version {
                    var updated = self.screener
                    updated.loaded = true
                    updated.enabled = response.enabled ?? updated.enabled
                    updated.error = response.error ?? (response.enabled == nil ? "Screener status is unavailable." : nil)
                    self.screener = updated
                }
            } catch {
                if self.sessionIsLive, self.screenerSettingVersion == version {
                    var updated = self.screener
                    updated.loaded = true
                    updated.error = Self.describe(error)
                    self.screener = updated
                }
            }
            if let count = await pending, self.sessionIsLive {
                self.screener.pending = count
            }
        }
    }

    private nonisolated static func readScreenerCount(token: String) async -> Int? {
        if let page = await MailStore.shared.page(.screener),
           (0...30).contains(Date().timeIntervalSince(page.loadedAt)) {
            return page.total
        }
        return try? await ApiClient.mailInbox(token: token, box: .screener, pageSize: 5).total
    }

    func setScreener(enabled: Bool) {
        guard !screener.busy, screener.enabled != enabled, let token else { return }
        screenerSettingVersion &+= 1
        var updating = screener
        updating.busy = true
        updating.error = nil
        screener = updating
        Task { [weak self] in
            guard let self else { return }
            do {
                let response = try await ApiClient.mailScreenerSet(token: token, enabled: enabled)
                guard self.sessionIsLive else { return }
                var updated = self.screener
                updated.busy = false
                updated.enabled = response.enabled ?? updated.enabled
                updated.error = response.error ?? (response.enabled == nil ? "The setting could not be confirmed." : nil)
                self.screener = updated
            } catch {
                guard self.sessionIsLive else { return }
                var updated = self.screener
                updated.busy = false
                updated.error = Self.describe(error)
                self.screener = updated
            }
        }
    }

    // ── sender decisions ─────────────────────────────────────────────────────

    /// Decide the senders behind the selected queue entries.
    ///
    /// Different from a message action in two ways the code has to respect. It
    /// writes a rule about a SENDER, so several rows from one person collapse to
    /// one decision; and the effect lands on the NEXT delivery, so the message on
    /// screen does not move because the server said yes.
    ///
    /// Nothing here reports which decision is already in force. The screener
    /// surface writes decisions and cannot read them back, so the sheet offers the
    /// four and marks none as current: an unknown read stays unknown rather than
    /// being filled in with the plausible-looking default.
    func decideSenders(
        _ addresses: [String], scope: MailSenderScope, decision: MailDecision,
        then: @escaping (String) -> Void,
    ) {
        guard !screener.busy else { return }
        guard let token else { then("Sign in again to decide on this sender."); return }
        let senders = Self.senders(from: addresses, scope: scope)
        guard !senders.isEmpty else {
            then("Those messages name no sender to decide on."); return
        }
        screener.busy = true
        screener.error = nil
        Task { [weak self] in
            guard let self else { return }
            var failure: String?
            var saved = 0
            for s in senders {
                do {
                    let r = try await ApiClient.mailScreenerDecision(
                        token: token, sender: s, scope: scope, decision: decision)
                    // `success` is the store's own word for the write landing. A
                    // 200 that did not say so is not counted as saved.
                    if r.success { saved += 1 }
                    else { failure = r.error ?? "The server refused that decision." }
                } catch { failure = Self.describe(error) }
            }
            guard self.sessionIsLive else { return }
            self.screener.busy = false
            self.screener.error = failure
            let message: String
            if let failure, saved == 0 { message = failure }
            else if let failure { message = "\(saved) of \(senders.count) saved. \(failure)" }
            else if saved == 1 { message = "\(decision.done): \(senders[0])" }
            else { message = "\(decision.done): \(saved) senders" }
            then(message)
        }
    }

    /// Forget a decision so the sender is undecided again.
    ///
    /// `removed == false` arrives as a 200 and means the same thing to the user as
    /// `true` did — there is no rule for this sender now — so it is reported as
    /// what happened rather than as a failure. Saying "forgotten" when the server
    /// said it had nothing to forget would leave the person believing a rule is
    /// still in force somewhere.
    func undoDecision(_ address: String, scope: MailSenderScope, then: @escaping (String) -> Void) {
        guard !screener.busy else { return }
        guard let token else { then("Sign in again to change this."); return }
        let sender = Self.senders(from: [address], scope: scope).first
        guard let sender else {
            then("That message names no sender to change."); return
        }
        screener.busy = true
        Task { [weak self] in
            guard let self else { return }
            do {
                let r = try await ApiClient.mailScreenerDecisionRemove(
                    token: token, sender: sender, scope: scope)
                guard self.sessionIsLive else { return }
                self.screener.busy = false
                then(r.removed == true
                     ? "Forgotten — their next message waits in the queue again."
                     : "No decision was recorded for them, so there was nothing to forget.")
            } catch {
                self.screener.busy = false
                self.screener.error = Self.describe(error)
                then(Self.describe(error))
            }
        }
    }

    /// The distinct senders a set of message addresses names, at the chosen scope.
    ///
    /// A domain is the text after the `@`, and an address with no `@` yields no
    /// domain — so a malformed sender is dropped rather than decided as a blank
    /// string, which the store would reject while the receipt said it was saved.
    static func senders(from addresses: [String], scope: MailSenderScope) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for raw in addresses {
            let address = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !address.isEmpty else { continue }
            let value: String
            if scope == .domain {
                guard let at = address.lastIndex(of: "@"),
                      address.index(after: at) < address.endIndex else { continue }
                value = String(address[address.index(after: at)...])
            } else {
                value = address
            }
            if !value.isEmpty && !seen.contains(value) {
                seen.insert(value)
                out.append(value)
            }
        }
        return out
    }

    // ── errors ───────────────────────────────────────────────────────────────

    /// Sort keys. Blank stays blank and case folds away: a message whose sender
    /// the server never named belongs at one end of the list, not scattered
    /// through it, and it must not sort differently on two runs.
    nonisolated static func senderKey(_ m: MailMessage) -> String { (m.from?.display ?? "").lowercased() }
    nonisolated static func subjectKey(_ m: MailMessage) -> String { (m.subject ?? "").lowercased() }

    private static func isNoMailbox(_ e: ApiError) -> Bool {
        if case .http(_, let code, _) = e, code == "NO_MAILBOX" { return true }
        return false
    }

    /// The server's own words when it has them, and a plain sentence when it does
    /// not. Never a status code presented to the user as the explanation.
    /// The sentence a mail screen shows for a failed call. A 401 here is the mail
    /// service declining this credential, not the session dying — the account
    /// endpoints still accept it, which is why the 401 handler is scoped by path.
    /// "Sign in again" would be a wrong diagnosis; silence would hide a real one.
    private static func describe(_ error: Error) -> String {
        // Two steps, not one: `error as? ApiError` is an Optional, and a
        // `.http` pattern does not match through it implicitly.
        if let api = error as? ApiError, case let .http(status, _, _) = api,
           status == 401 {
            return "The mail service did not accept this session. " +
                   "You are still signed in to your account."
        }
        if let api = error as? ApiError { return api.userMessage }
        return error.localizedDescription
    }
}

extension String? {
    func orEmpty() -> String { self ?? "" }
}
