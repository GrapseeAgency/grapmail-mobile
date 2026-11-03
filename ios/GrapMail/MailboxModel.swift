import Foundation
import Combine

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
        /// A refresh that failed while rows were on screen. Non-nil with rows
        /// present means "this list may be stale", not "there is no mail".
        var staleError: String?
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
        /// Bumped whenever a list load settles, in either direction. Pull-to-refresh
        /// needs an async work item that ENDS when the answer arrives, and `loading`
        /// cannot be that signal: under the no-blank rule a refresh over a populated
        /// list never raises it, so `.refreshable` would release the spinner the
        /// instant the request went out. Android waits on the same counter.
        var settleGeneration = 0
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
        var sending = false
        /// The server's own verdict, shown verbatim. Never a local "Sent".
        var result: String?
        var resultStatus: String?
        var error: String?
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

    private var token: String? { SessionStore.token }

    /// Whether a bearer is still held. Every cache write checks this AFTER its
    /// await, because sign-out clears the store and the cache together, and a
    /// request that was already in flight would otherwise re-seed the previous
    /// account's pages a moment later -- the exact cross-account leak the cache
    /// exists to prevent. iOS cannot await the clear itself: `SessionStore.clear()`
    /// is synchronous, so the guard belongs on the writer.
    private var sessionIsLive: Bool { SessionStore.token != nil }

    /// Set by the CI snapshot host's seams, and checked by every loader that would
    /// otherwise overwrite a seeded field. Nothing in the live flow sets it.
    var snapshotSeeded = false

    // ── list ─────────────────────────────────────────────────────────────────

    func open(_ box: MailBox) {
        loadedBoxes.insert(box)
        state.box = box
        state.selection = []
        load(replace: true)
    }

    /// Fetch the current box when it has never been fetched. The home screen's
    /// entry point.
    func ensureLoaded() {
        if loadedBoxes.contains(state.box) { return }
        loadedBoxes.insert(state.box)
        load(replace: true)
    }

    /// Typing. Debounced, and a term shorter than 3 characters never leaves the
    /// device: a one-character `q` scans the whole mailbox server-side
    /// (`grapmail.ts:688`), so every keystroke would be a full-table read.
    func query(_ text: String) {
        state.query = text
        searchTask?.cancel()
        searchTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled else { return }
            self?.load(replace: true)
        }
    }

    func setFilter(_ f: MailFilter) { state.filter = f; reproject() }
    func setSort(_ s: MailSort) { state.sort = s; reproject() }

    /// Load a page. `replace` restarts the box (refresh, new search, new box);
    /// otherwise the page is appended, which is what makes scrolling cheap.
    func load(replace: Bool = true) {
        loadTask?.cancel()
        loadTask = Task { [weak self] in
            guard let self else { return }
            let box = self.state.box
            let term = self.state.query.trimmingCharacters(in: .whitespaces)
                .nilIfBlank().flatMap { $0.count >= 3 ? $0 : nil }
            let page = replace ? 1 : self.state.pages + 1

            // Show the spinner only when there is nothing to show. A refresh over
            // an existing list must not blank it — the hub's defect, designed out
            // here rather than rediscovered.
            if replace && self.state.rows.isEmpty {
                self.state.loading = true
                self.state.staleError = nil
            }
            if !replace { self.state.loadingMore = true }

            guard let token = self.token else {
                // The generation moves on this exit too, and `loadAndSettle` below is
                // the reason: it waits for the number to change, so an early return
                // that leaves it alone is a pull-to-refresh that never ends.
                self.state.loading = false
                self.state.loadingMore = false
                self.state.fatal = "Sign in again."
                self.state.settleGeneration += 1
                return
            }

            do {
                let resp = try await ApiClient.mailInbox(
                    token: token, box: box, page: page, query: term)
                guard sessionIsLive else {
                    // Signed out mid-flight: the same rule as the token guard above.
                    self.state.loading = false
                    self.state.loadingMore = false
                    self.state.settleGeneration += 1
                    return
                }
                await MailStore.shared.putPage(box, term, resp, replace: replace)
                if term == nil {
                    if let s = try? await ApiClient.mailStats(token) {
                        await MailStore.shared.putStats(s)
                    }
                }
                self.loadedBoxes.insert(box)
                self.state.loading = false
                self.state.loadingMore = false
                self.state.fatal = nil
                self.state.staleError = nil
                self.state.settleGeneration += 1
                self.reproject()
            } catch {
                // A superseded read says nothing. `load()` cancels the previous task
                // on every call, so a box switch or one keystroke past the debounce
                // arrived here as a `cancelled` URLError, which `describe` renders as
                // "Network error. Please try again." — an error banner painted over
                // the list the newer read had just filled. Same reasoning as Android's
                // `CancellationException` rethrow in `load()`.
                if error is CancellationError || Task.isCancelled { return }
                let noBox = (error as? ApiError).map { Self.isNoMailbox($0) } ?? false
                let message = Self.describe(error)
                self.state.loading = false
                self.state.loadingMore = false
                self.state.noMailbox = noBox
                self.state.settleGeneration += 1
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

    /// Pull-to-refresh's work item: returns only once the load has settled, whether
    /// it landed rows or an error.
    ///
    /// Waiting on `settleGeneration` is right — the no-blank rule means a refresh over
    /// a populated list never raises `loading`, so there is nothing else to watch —
    /// but a bare spin on it is a trap: every early exit that does not bump the number
    /// leaves this looping at 10 Hz on the main actor for the rest of the process, and
    /// `try?` swallows the cancellation SwiftUI raises when the gesture dies. So the
    /// wait checks for cancellation and carries a ceiling that is a safety net, not a
    /// timeout anyone is meant to hit: every loader that can finish does so long
    /// before, and the request's own read timeout is 20s (`APIClient`).
    func loadAndSettle() async {
        let before = state.settleGeneration
        load(replace: true)
        var ticks = 0
        while state.settleGeneration == before, ticks < 900 {
            if Task.isCancelled { return }
            try? await Task.sleep(nanoseconds: 100_000_000)
            ticks += 1
        }
    }

    func loadMore() {
        guard state.loaded < state.total, !state.loadingMore else { return }
        load(replace: false)
    }

    /// Re-derive the visible rows from what is loaded. Never a network call — this
    /// runs on filter and sort changes, which are local operations.
    func reproject() {
        let box = state.box
        let term = state.query.trimmingCharacters(in: .whitespaces)
            .nilIfBlank().flatMap { $0.count >= 3 ? $0 : nil }
        Task { [weak self] in
            guard let self else { return }
            let cache = await MailStore.shared.page(box, term)
            var rows = cache?.messages ?? self.state.rows
            let total = cache?.total ?? self.state.total

            if self.state.filter == .unread { rows = rows.filter { !$0.isRead } }

            // The advanced filters run here, on the loaded pages, before the
            // sort. Blank fields are absent filters, not empty-string matches.
            let advOn = !self.state.advFrom.trimmed.isEmpty || !self.state.advTo.trimmed.isEmpty
                || !self.state.advSubject.trimmed.isEmpty || self.state.advFiles
                || !self.state.advAfter.trimmed.isEmpty || !self.state.advBefore.trimmed.isEmpty
            if advOn {
                let f = self.state.advFrom.trimmed.lowercased()
                let tt = self.state.advTo.trimmed.lowercased()
                let sj = self.state.advSubject.trimmed.lowercased()
                let after = self.state.advAfter.trimmed
                let before = self.state.advBefore.trimmed
                rows = rows.filter { m in
                    (f.isEmpty || (m.from?.display ?? "").lowercased().contains(f))
                    && (tt.isEmpty || m.to.contains { $0.lowercased().contains(tt) }
                        || m.cc.contains { $0.lowercased().contains(tt) })
                    && (sj.isEmpty || (m.subject ?? "").lowercased().contains(sj))
                    && (!self.state.advFiles || m.hasAttachments)
                    && (after.isEmpty || String((m.createdAt ?? "").prefix(10)) >= after)
                    && (before.isEmpty || String((m.createdAt ?? "").prefix(10)) <= before)
                }
            }

            // The keys are computed through locals on purpose. Written inline as
            // `$0.from?.display.lowercased() < $1.from?.display.lowercased()`,
            // the optional chain makes both sides `String?`, `<` has no such
            // overload, and the compiler then resolves `sort` to the
            // `Comparator` API and reports an error that names nothing useful.
            switch self.state.sort {
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

            let stats = await MailStore.shared.stats()
            self.state.rows = rows
            self.state.total = total
            self.state.loaded = cache?.messages.count ?? self.state.loaded
            self.state.pages = cache?.pages ?? self.state.pages
            // The scope the sheet prints: how many loaded rows the filters ran
            // over BEFORE filtering, so "12 of 40" cannot be read as the
            // server's answer about the whole box.
            self.state.advScope = cache?.messages.count ?? self.state.loaded
            // Each stays nil when the server did not answer it. A nil draws no
            // badge; a 0 would claim an empty folder.
            self.state.unreadCount = stats?.inbox?.unread
            self.state.statsSent = stats?.sent
            self.state.statsDrafts = stats?.drafts
            self.state.statsSpam = stats?.spam
            self.state.statsTrash = stats?.trash
            self.state.statsStarred = stats?.starred
            self.state.statsArchive = stats?.archive
            self.state.statsOthers = stats?.others
            self.state.statsSnoozed = stats?.snoozed
        }
    }

    /// How many rows the chosen sort actually re-ordered. The sort sheet prints
    /// this so a client-side ordering is not mistaken for the server's.
    var sortScope: Int { state.loaded }

    // ── identity ─────────────────────────────────────────────────────────────

    /// The mailbox's own identity and its label strip. Idempotent: the home
    /// screen calls it on entry, and a screen that re-enters on every return from
    /// a thread must not re-read the same two documents each time.
    func loadAccount() {
        guard !snapshotSeeded, let token, !accountLoaded else { return }
        accountLoaded = true
        Task { [weak self] in
            guard let self else { return }
            if let me = try? await ApiClient.mailMe(token) {
                await MailStore.shared.putMe(me)
                self.state.accountName = me.name
                self.state.accountAddress = me.address
                self.state.accountStatus = me.status
                self.state.memberSince = me.memberSince
            }
            // A refused label read is recorded rather than swallowed. `labels`
            // staying empty is not evidence that the mailbox has no labels, and the
            // Label sheet says "No labels yet" only when somebody actually answered.
            do {
                let labels = try await ApiClient.mailLabels(token)
                guard sessionIsLive else { return }
                await MailStore.shared.putLabels(labels.labels)
                self.state.labels = labels.labels
                self.state.labelsRead = .read
                self.labelState.labels = labels.labels
            } catch {
                self.state.labelsRead = .failed
            }
        }
    }

    // ── selection ────────────────────────────────────────────────────────────

    func toggleSelect(_ id: String) {
        if state.selection.contains(id) { state.selection.remove(id) }
        else { state.selection.insert(id) }
    }

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
        guard !ids.isEmpty, let token else { return }
        state.busy = true
        Task { [weak self] in
            guard let self else { return }
            var failure: String?
            var done: [String] = []
            for id in ids {
                do {
                    _ = try await ApiClient.mailAction(token: token, id: id, action: action, until: until)
                    done.append(id)
                    await self.applyLocally(id: id, action: action)
                } catch { failure = Self.describe(error) }
            }
            await self.invalidateFor(action)
            self.state.busy = false
            self.state.selection = []
            self.state.staleError = failure
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
            self.load(replace: true)
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
        guard !ids.isEmpty, let token else { return }
        state.busy = true
        Task { [weak self] in
            guard let self else { return }
            var failure: String?
            var undone = 0
            var restored: [String] = []
            for id in ids {
                do {
                    _ = try await ApiClient.mailAction(token: token, id: id, action: .unsnooze, until: nil)
                    undone += 1
                    restored.append(id)
                    self.state.snoozeUndoIds = self.state.snoozeUndoIds.filter { $0 != id }
                } catch { failure = Self.describe(error) }
            }
            await self.invalidateFor(.unsnooze)
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
            self.load(replace: true)
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
    func undoFiling() {
        let ids = state.undoIds
        guard let inv = state.undoInverse, !ids.isEmpty, let token else { return }
        state.busy = true
        Task { [weak self] in
            guard let self else { return }
            var failure: String?
            var redone: [String] = []
            for id in ids {
                do {
                    _ = try await ApiClient.mailAction(token: token, id: id, action: inv, until: nil)
                    redone.append(id)
                    await self.applyLocally(id: id, action: inv)
                } catch { failure = Self.describe(error) }
            }
            await self.invalidateFor(inv)
            let left = self.state.undoIds.filter { !redone.contains($0) }
            self.state.busy = false
            self.state.undoIds = left
            if left.isEmpty { self.state.undoInverse = nil }
            self.state.undoNote = failure.map { "\(redone.count) of \(ids.count) reversed. \($0)" }
            self.state.staleError = failure
            self.load(replace: true)
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

    func actOn(_ id: String, _ action: MailAction) {
        guard let token else { return }
        Task { [weak self] in
            guard let self else { return }
            do {
                _ = try await ApiClient.mailAction(token: token, id: id, action: action, until: nil)
                await self.applyLocally(id: id, action: action)
                await self.invalidateFor(action)
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
                // same lesson from the same gap).
                if let inv = action.inverse() {
                    self.state.undoIds = [id]
                    self.state.undoInverse = inv
                    self.state.undoNote = action.pastLine(1)
                }
                self.reproject()
            } catch {
                self.state.staleError = Self.describe(error)
            }
        }
    }

    private func applyLocally(id: String, action: MailAction) async {
        switch action {
        case .read: await MailStore.shared.patchFlags(id: id, read: true)
        case .unread: await MailStore.shared.patchFlags(id: id, read: false)
        case .star: await MailStore.shared.patchFlags(id: id, starred: true)
        case .unstar: await MailStore.shared.patchFlags(id: id, starred: false)
        case .trash, .archive, .spam, .snooze:
            await MailStore.shared.removeEverywhere(id: id)
        default: break
        }
    }

    private func invalidateFor(_ action: MailAction) async {
        switch action {
        case .trash: await MailStore.shared.invalidate(.trash)
        case .archive: await MailStore.shared.invalidate(.archive)
        case .spam: await MailStore.shared.invalidate(.spam)
        case .restore, .unarchive, .unspam: await MailStore.shared.invalidateAllBoxes()
        case .snooze: await MailStore.shared.invalidate(.inbox)
        // Undoing a snooze puts the mail back where it was hidden from, which is the
        // inbox listing — so the inbox is the box that must be re-read.
        case .unsnooze: await MailStore.shared.invalidate(.inbox)
        default: break
        }
    }

    /// Permanent destruction, from Trash only. Separate from `act` because it is
    /// the one action that cannot be undone and therefore never rides along with a
    /// bulk "delete" — the reference's Delete button trashes; this is the second,
    /// explicit step the design's confirmation screen is for.
    func deletePermanently() {
        let ids = Array(state.selection)
        guard !ids.isEmpty, let token else { return }
        state.busy = true
        Task { [weak self] in
            guard let self else { return }
            var failure: String?
            for id in ids {
                // Only on success. The old order ran the cache removal even after the
                // server refused, so a refused delete emptied the row from a list
                // whose mail still exists -- and dropping its label pairs from a map
                // the sheet will not re-read let the Label sheet later claim `off`
                // for a label the living message still carries.
                do { try await ApiClient.mailDeletePermanent(token: token, id: id) }
                catch { failure = Self.describe(error); continue }
                await MailStore.shared.removeEverywhere(id: id)
                // The message is gone, so its label pairs are gone with it. Left in
                // the map they could resurface as a tick on mail that does not exist.
                await MailStore.shared.forgetAssignments(id)
            }
            self.state.busy = false
            self.state.selection = []
            self.state.staleError = failure
            self.load(replace: true)
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
    func people() async -> [Person] {
        var counts: [String: (name: String?, seen: Int)] = [:]
        for box in MailBox.tabOrder {
            guard let page = await MailStore.shared.page(box, nil) else { continue }
            for m in page.messages {
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
    func files() async -> [FileEntry] {
        (await MailStore.shared.allBodies()).flatMap { m in
            m.attachments.map { FileEntry(attachment: $0, fromMessage: m.subject?.nilIfBlank()) }
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
    func deleteAttachment(_ id: String, then: @escaping (String) -> Void) {
        guard let token else { then("Sign in again to remove this."); return }
        Task { [weak self] in
            guard let self else { return }
            do {
                let r = try await ApiClient.mailDeleteAttachment(token, id: id)
                if r.success {
                    await MailStore.shared.forgetAttachment(id)
                    await MailStore.shared.invalidateAllBoxes()
                    then("Removed.")
                } else {
                    then(r.error ?? "The server refused to remove that file.")
                }
            } catch {
                then(Self.describe(error))
            }
        }
    }

    func downloadAttachment(_ a: MailAttachment, then: @escaping (String) -> Void) {
        // Save an attachment into the app's own Documents directory and report
        // what actually happened. A failure never receives a path.
        guard let token else { then("Sign in again to save this."); return }
        // No `self` captured: this task reads the network, writes to disk and
        // reports back through the caller's closure. Holding the model until the
        // download finished would keep a mailbox alive for a file the user may
        // have already navigated away from.
        Task {
            do {
                let bytes = try await ApiClient.mailAttachmentBytes(token: token, id: a.id)
                // The name arrives from a remote message, so it is not trusted to
                // be a filename. Only the characters a path segment can hold are
                // kept; anything else becomes an underscore. Written with
                // CharacterSet this way would need Foundation's NSString-only
                // masking call, so the allowed set is stated literally.
                let raw = a.name ?? "attachment"
                let name = String(raw.map { ch in
                    (ch.isLetter || ch.isNumber || ch == "." || ch == "_" || ch == "-") ? ch : "_"
                })
                let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                    .appendingPathComponent(name.isEmpty ? "attachment" : name)
                do {
                    try bytes.write(to: url)
                    then("Saved as \(name) in the Files app.")
                } catch {
                    then("Downloaded, but the file could not be written.")
                }
            } catch {
                then(Self.describe(error))
            }
        }
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
    ///
    /// Opening marks the message read server-side (`grapmail.ts:750-757`), so no
    /// second `read` action is sent: it would be redundant and would race the GET.
    func openThread(_ threadId: String) {
        guard let token, threadLoadedFor != threadId else { return }
        thread = ThreadState(loading: true)
        Task { [weak self] in
            guard let self else { return }
            do {
                let resp = try await ApiClient.mailThread(token: token, threadId: threadId)
                for m in resp.messages { await MailStore.shared.putBody(m) }
                self.thread = ThreadState(messages: resp.messages)
                self.threadLoadedFor = threadId
                if let first = resp.messages.first {
                    await MailStore.shared.patchFlags(id: first.id, read: true)
                }
                self.reproject()
            } catch {
                self.thread = ThreadState(error: Self.describe(error))
            }
        }
    }

    func openMessage(_ id: String) {
        guard let token, threadLoadedFor != id else { return }
        thread = ThreadState(loading: true)
        Task { [weak self] in
            guard let self else { return }
            if let cached = await MailStore.shared.body(id) {
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
                if let m = resp.message {
                    await MailStore.shared.putBody(m)
                    self.threadLoadedFor = id
                    self.thread = ThreadState(messages: [m])
                    await MailStore.shared.patchFlags(id: m.id, read: true)
                }
                self.reproject()
            } catch {
                self.thread = ThreadState(error: Self.describe(error))
            }
        }
    }

    // ── compose ──────────────────────────────────────────────────────────────

    func composeUpdate(_ transform: (inout ComposeState) -> Void) { transform(&compose) }

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
        Task { [weak self] in
            guard let self else { return }
            guard let full = try? await ApiClient.mailMessage(token: token, id: id),
                  let m = full.message else { return }
            let me = self.state.accountAddress?.lowercased()
            let quoted = Self.quoteOf(m)
            let sig = Self.signatureBlock(signature)
            switch mode {
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

    func resetCompose() { compose = ComposeState() }

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
    func send(e2eEnvelope: String? = nil) {
        guard let token, !compose.sending else { return }
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
        let req = MailSendRequest(
            to: Self.splitAddresses(compose.to), cc: Self.splitAddresses(compose.cc),
            bcc: Self.splitAddresses(compose.bcc), subject: compose.subject,
            bodyText: e2eEnvelope == nil ? compose.body : "",
            inReplyTo: compose.inReplyTo, e2eEnvelope: e2eEnvelope)
        Task { [weak self] in
            guard let self else { return }
            do {
                let r = try await ApiClient.mailSend(token: token, req)
                self.compose.sending = false
                self.compose.result = r.message ?? r.error
                self.compose.resultStatus = r.status
                self.compose.error = r.deliveryError ?? r.error
                await MailStore.shared.invalidate(.sent)
                await MailStore.shared.invalidate(.inbox)
                self.load(replace: true)
            } catch {
                self.compose.sending = false
                self.compose.error = Self.describe(error)
            }
        }
    }

    /// Save as a draft. Discarding a half-written message without asking is the
    /// other way a mail app loses something; this is what the back button does.
    func saveDraft() {
        guard let token else { return }
        let req = MailDraftRequest(
            to: Self.splitAddresses(compose.to), cc: Self.splitAddresses(compose.cc),
            subject: compose.subject, bodyText: compose.body, inReplyTo: compose.inReplyTo)
        compose.sending = true
        Task { [weak self] in
            guard let self else { return }
            do {
                _ = try await ApiClient.mailDraft(token: token, req)
                self.compose.sending = false
                self.compose.result = "Saved to Drafts"
                self.compose.resultStatus = "draft"
                await MailStore.shared.invalidate(.drafts)
            } catch {
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

    func refreshLabels() {
        // Not while the snapshot host owns these fields: its seed is the first
        // frame's content, and a live read landing afterwards would replace it
        // with the CI account's real (empty) label list -- which is how a
        // screenshot of a populated screen photographs its empty branch and still
        // passes a file-count gate. Android models this with a constructor flag.
        guard !snapshotSeeded, let token else { return }
        labelState.loading = true
        Task { [weak self] in
            guard let self else { return }
            do {
                let r = try await ApiClient.mailLabels(token)
                guard sessionIsLive else {
                    // Same trap in the other screen: `LabelState.loading` is what the
                    // Labels screen draws, and leaving it true after an abandoned read
                    // shows a spinner forever with nobody left to stop it.
                    self.labelState.loading = false
                    return
                }
                await MailStore.shared.putLabels(r.labels)
                self.labelState.labels = r.labels
                self.labelState.loading = false
                self.state.labels = r.labels
                self.state.labelsRead = .read
            } catch {
                self.labelState.loading = false
                self.labelState.error = Self.describe(error)
                self.state.labelsRead = .failed
            }
        }
    }

    /// Create a label. The colour key the route reads is `colour`
    /// (`grapmail-labels.ts:105`); sending `color` creates an uncoloured label and
    /// still returns 200, so the mistake would be invisible.
    func createLabel(name: String, colour: String?, then: @escaping (String) -> Void) {
        guard let token else { then("Sign in again."); return }
        Task {
            do {
                let r = try await ApiClient.mailCreateLabel(token: token, name: name, colour: colour)
                then("Created \(r.label?.name ?? name).")
            } catch { then(Self.describe(error)) }
        }
    }

    /// Rename or recolour a label, then re-read the list. The response is not
    /// patched into the store: the route returns the row it wrote, and the list
    /// showing what the server holds beats the list showing what was typed.
    func renameLabel(id: String, name: String, colour: String?,
                     then: @escaping (String) -> Void) {
        guard let token else { then("Sign in again."); return }
        Task {
            do {
                _ = try await ApiClient.mailRenameLabel(token: token, id: id,
                                                         name: name, colour: colour)
                if let r = try? await ApiClient.mailLabels(token) {
                    await MailStore.shared.putLabels(r.labels)
                    state.labels = r.labels
                    labelState.labels = r.labels
                }
                then("Saved.")
            } catch { then(Self.describe(error)) }
        }
    }

    func deleteLabel(id: String, then: @escaping (String) -> Void) {
        guard let token else { then("Sign in again."); return }
        Task {
            do {
                try await ApiClient.mailDeleteLabel(token: token, id: id)
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
    func loadScreener() {
        guard let token else { return }
        Task { [weak self] in
            guard let self else { return }
            if let r = try? await ApiClient.mailScreener(token) {
                self.screener.loaded = true
                self.screener.enabled = r.enabled
                self.screener.error = r.error
            }
            // The queue is a box like any other, so its count comes from the
            // listing rather than a number the settings route never promised.
            if let page = try? await ApiClient.mailInbox(token: token, box: .screener, pageSize: 5) {
                self.screener.pending = page.total
            }
        }
    }

    func setScreener(enabled: Bool) {
        guard let token else { return }
        screener.busy = true
        Task { [weak self] in
            guard let self else { return }
            do {
                let r = try await ApiClient.mailScreenerSet(token: token, enabled: enabled)
                self.screener.busy = false
                self.screener.enabled = r.enabled ?? enabled
            } catch {
                self.screener.busy = false
                self.screener.error = Self.describe(error)
            }
        }
    }

    // ── sender decisions ─────────────────────────────────────────────────────

    /// Decide the senders behind the selected queue entries.
    ///
    /// Different from a message action in two ways the code has to respect. It
    /// writes a rule about a SENDER, so several rows from one person collapse to
    /// one decision; and the effect lands on the NEXT delivery, so the message on
    /// screen does not move because the server said yes. The queue is therefore
    /// re-read rather than spliced — the folder is the queue's only definition, and
    /// removing a row locally would show fewer messages than the server lists.
    ///
    /// Nothing here reports which decision is already in force. The screener
    /// surface writes decisions and cannot read them back, so the sheet offers the
    /// four and marks none as current: an unknown read stays unknown rather than
    /// being filled in with the plausible-looking default.
    func decideSenders(
        _ addresses: [String], scope: MailSenderScope, decision: MailDecision,
        then: @escaping (String) -> Void,
    ) {
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
            await MailStore.shared.invalidate(.screener)
            self.loadScreener()
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
                await MailStore.shared.invalidate(.screener)
                self.loadScreener()
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
    static func senderKey(_ m: MailMessage) -> String { (m.from?.display ?? "").lowercased() }
    static func subjectKey(_ m: MailMessage) -> String { (m.subject ?? "").lowercased() }

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
