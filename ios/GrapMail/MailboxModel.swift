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
        var unreadCount: Int?
        var statsSent: Int?
        var statsDrafts: Int?
        var statsSpam: Int?
        var statsTrash: Int?
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
    func seedForSnapshot(_ mutate: (inout State) -> Void) { mutate(&state) }

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
                self.state.loading = false
                self.state.loadingMore = false
                self.state.fatal = "Sign in again."
                return
            }

            do {
                let resp = try await ApiClient.mailInbox(
                    token: token, box: box, page: page, query: term)
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
                self.reproject()
            } catch {
                let noBox = (error as? ApiError).map { Self.isNoMailbox($0) } ?? false
                let message = Self.describe(error)
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
            // Each stays nil when the server did not answer it. A nil draws no
            // badge; a 0 would claim an empty folder.
            self.state.unreadCount = stats?.inbox?.unread
            self.state.statsSent = stats?.sent
            self.state.statsDrafts = stats?.drafts
            self.state.statsSpam = stats?.spam
            self.state.statsTrash = stats?.trash
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
        guard let token, !accountLoaded else { return }
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
            if let labels = try? await ApiClient.mailLabels(token) {
                await MailStore.shared.putLabels(labels.labels)
                self.state.labels = labels.labels
                self.labelState.labels = labels.labels
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
            for id in ids {
                do {
                    _ = try await ApiClient.mailAction(token: token, id: id, action: action, until: until)
                    await self.applyLocally(id: id, action: action)
                } catch { failure = Self.describe(error) }
            }
            await self.invalidateFor(action)
            self.state.busy = false
            self.state.selection = []
            self.state.staleError = failure
            self.load(replace: true)
        }
    }

    func actOn(_ id: String, _ action: MailAction) {
        guard let token else { return }
        Task { [weak self] in
            guard let self else { return }
            do {
                _ = try await ApiClient.mailAction(token: token, id: id, action: action, until: nil)
                await self.applyLocally(id: id, action: action)
                await self.invalidateFor(action)
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
                do { try await ApiClient.mailDeletePermanent(token: token, id: id) }
                catch { failure = Self.describe(error) }
                await MailStore.shared.removeEverywhere(id: id)
            }
            self.state.busy = false
            self.state.selection = []
            self.state.staleError = failure
            self.load(replace: true)
        }
    }

    func assignLabel(_ labelId: String) {
        let ids = Array(state.selection)
        guard !ids.isEmpty, let token else { return }
        state.busy = true
        Task { [weak self] in
            guard let self else { return }
            var failure: String?
            for id in ids {
                do { try await ApiClient.mailLabelAssign(token: token, messageId: id, labelId: labelId) }
                catch { failure = Self.describe(error) }
            }
            self.state.busy = false
            self.state.selection = []
            self.state.staleError = failure
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

    /// Save an attachment into the app's own Documents directory and report what
    /// actually happened. A failure never receives a path.
    func downloadAttachment(_ a: MailAttachment, then: @escaping (String) -> Void) {
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

    private static func replySubject(_ s: String?) -> String {
        guard let t = s?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return "Re:" }
        if t.hasPrefix("Re:") || t.hasPrefix("Fwd:") { return t }
        return "Re: \(t)"
    }

    func resetCompose() { compose = ComposeState() }

    /// Send. The receipt shows `status` and `deliveryError` as the server reported
    /// them — a message the queue has only accepted is not a delivered message,
    /// and saying otherwise is the exact dishonesty the send-status test exists to
    /// pin.
    func send() {
        guard let token, !compose.sending else { return }
        compose.sending = true
        compose.error = nil
        compose.result = nil
        compose.resultStatus = nil
        let req = MailSendRequest(
            to: Self.splitAddresses(compose.to), cc: Self.splitAddresses(compose.cc),
            bcc: Self.splitAddresses(compose.bcc), subject: compose.subject,
            bodyText: compose.body, inReplyTo: compose.inReplyTo)
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
        guard let token else { return }
        labelState.loading = true
        Task { [weak self] in
            guard let self else { return }
            do {
                let r = try await ApiClient.mailLabels(token)
                await MailStore.shared.putLabels(r.labels)
                self.labelState.labels = r.labels
                self.labelState.loading = false
                self.state.labels = r.labels
            } catch {
                self.labelState.loading = false
                self.labelState.error = Self.describe(error)
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
