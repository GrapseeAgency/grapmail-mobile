package com.grapsee.grapmail.logic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.AssignmentsRead
import com.grapsee.grapmail.data.LabelMark
import com.grapsee.grapmail.data.MailAction
import com.grapsee.grapmail.data.MailAttachmentDto
import com.grapsee.grapmail.data.MailBox
import com.grapsee.grapmail.data.MailDecision
import com.grapsee.grapmail.data.MailDraftRequest
import com.grapsee.grapmail.data.MailFilter
import com.grapsee.grapmail.data.MailLabelDto
import com.grapsee.grapmail.data.MailMessageDto
import com.grapsee.grapmail.data.MailMessageFullDto
import com.grapsee.grapmail.data.MailSendRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.grapsee.grapmail.data.MailSenderScope
import com.grapsee.grapmail.data.MailSort
import com.grapsee.grapmail.data.MailStore
import com.grapsee.grapmail.data.inverse
import com.grapsee.grapmail.data.pastLine
import com.grapsee.grapmail.data.SessionStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the reader's next/prev controls need to open a row: which resource, and
 * whether it is a conversation.
 *
 * The flag travels with the target rather than being assumed by the caller,
 * because the two thread routes are different resources — `GET /thread/:id`
 * answers a message id with a 404, and the list already had to learn that
 * (`isThread` exists on its tap for the same reason). A stepper that hardcoded
 * `true` would reopen the same dead end one button further in.
 */
data class MailStep(val id: String, val isThread: Boolean)

/**
 * The mailbox's state and its only writer.
 *
 * ── WHAT A FAILED REFRESH IS ALLOWED TO DO ──────────────────────────────────
 * None of the loaders blank data that is already on screen. The hub in this app
 * did exactly that — one rate-limited refetch out of the eight a visit makes set
 * `failed = true`, which disabled its own toggle and left a screen showing
 * nothing over data the app still held. Here a refresh failure keeps the list and
 * records the problem separately, so `error` explains a stale list rather than
 * replacing it. `loading` is only true when there is nothing to show.
 *
 * ── WHY SORT AND FILTER LIVE HERE AND SAY WHAT THEY SCOPED ──────────────────
 * The server orders by UID descending and nothing else (`grapmail-boxes.ts:34-39`),
 * and `/inbox` has no filter parameter. So `sender A→Z` and `unread` are applied
 * by the client to the pages that have been fetched. That is honest only if the UI
 * says so: [MailboxUiState.sortScope] is the number actually sorted, and the sort
 * sheet prints it rather than implying the whole box was reordered.
 */
/**
 * The mailbox's state and its only writer.
 *
 * `snapshotMode` is set by the CI snapshot host and by nothing else. It suppresses
 * the boot reads of `/labels` and `/me`, which would otherwise race the host's own
 * seed: the seed lands first so the first frame is the populated one, then the
 * network answer arrives and overwrites it with the CI account's real (empty) label
 * list -- so `72-mail-labels` could photograph "No labels yet" and the walk's
 * settled marker for that screen would either fail or, worse, pass on a frame of the
 * wrong branch. Seeding and fetching cannot both own the same field.
 *
 * `@JvmOverloads` is not decoration, and the build cannot say why it is needed —
 * only a run can. A Kotlin default parameter does NOT emit the shorter Java
 * constructor unless asked, so adding `snapshotMode: Boolean = false` left this
 * class with one Java constructor, `(Application, boolean)`. The default
 * `ViewModelProvider` factory builds an `AndroidViewModel` subclass by reflecting
 * for `<init>(Application)`, did not find it, and threw
 * `NoSuchMethodException: com.grapsee.grapmail.logic.MailboxViewModel.<init>
 * [class android.app.Application]` inside the first `setContent` — which is not a
 * mailbox crash but a whole-app one, on every screen, from the commit that added
 * the flag until this line. Eight Android runs photographed the Pixel launcher
 * before this was readable from a log.
 */
class MailboxViewModel @JvmOverloads constructor(
    app: Application,
    private val snapshotMode: Boolean = false,
) :
    AndroidViewModel(app) {

    data class MailboxUiState(
        val box: MailBox = MailBox.Inbox,
        val query: String = "",
        val filter: MailFilter = MailFilter.All,
        val sort: MailSort = MailSort.Newest,
        /** Rows after the client's filter and sort — what the list renders. */
        val rows: List<MailMessageDto> = emptyList(),
        /** Rows fetched for this box, before filtering. */
        val loaded: Int = 0,
        /** Rows the box contains, per the server. 0 until the first page lands. */
        val total: Int = 0,
        val pages: Int = 0,
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        /** A refresh that failed while rows were on screen. Non-null with rows
         *  present means "this list may be stale", not "there is no mail". */
        val staleError: String? = null,
        /** A load that failed with nothing to show. This is the error screen. */
        val fatal: String? = null,
        /** True when the server said this account has no mailbox at all. */
        val noMailbox: Boolean = false,
        val selection: Set<String> = emptySet(),
        val labels: List<MailLabelDto> = emptyList(),
        /**
         * Whether the account has been asked which messages carry which labels.
         *
         * Kept separate from [labelMarks] because the sheet must be able to say
         * “not known” out loud. Marking every label Off before the read answers
         * would offer to add a label the message already has, and the sheet would
         * look like it had checked.
         */
        val assignmentsRead: AssignmentsRead = AssignmentsRead.NotRead,
        /** Whether the label LIST has been read. Same distinction as above: an
         *  empty list is only a fact once somebody has answered. */
        val labelsRead: AssignmentsRead = AssignmentsRead.NotRead,
        /** labelId → its state against the current selection. */
        val labelMarks: Map<String, LabelMark> = emptyMap(),
        /** The Label sheet's own result line — what the last tap actually did. */
        val labelNote: String? = null,
        /**
         * The messages this session snoozed, kept so the snooze can be undone from
         * the banner the action itself raises.
         *
         * The listing's row shape (`publicRow`, `grapmail.ts:583-612`) carries no
         * `snoozedUntil`, so a row on screen can never say "this one is postponed
         * until Thursday" — the box that holds them can, and does: `?box=snoozed`
         * is answered by `grapmail-boxes.ts:178` and the drawer offers it as soon
         * as `/stats` reports the count. Undo is therefore reachable two ways: from
         * this banner, for exactly the mail the app just snoozed, and from a row in
         * the Snoozed box. Neither claim is invented — the banner lists only the
         * ids the server accepted, and the box lists only what the server lists.
         */
        val snoozeUndoIds: List<String> = emptyList(),
        val snoozeUndoNote: String? = null,
        /**
         * The last filing this session did, kept so the banner can offer its
         * exact reverse. Same rule as the snooze pair: only the ids the server
         * accepted are listed, and any newer filing supersedes the offer, so
         * "Undo" can never act on mail the app cannot name.
         */
        val undoIds: List<String> = emptyList(),
        val undoInverse: com.grapsee.grapmail.data.MailAction? = null,
        val undoNote: String? = null,
        /**
         * The advanced search's own filters, applied CLIENT-SIDE to the pages
         * this device has loaded.
         *
         * The server's `q` is a literal ILIKE over subject, body and from
         * (`grapmail-search.ts:110-118`, 2-100 chars) and nothing else -- there
         * is no from/to/attachment/date filter route to call. So these narrow
         * what is on the device, exactly like the sort does, and the sheet
         * prints [advScope] the way the sort sheet prints its scope. A whole-box
         * search is the typed term; the sheet must not imply otherwise.
         */
        val advFrom: String = "",
        val advTo: String = "",
        val advSubject: String = "",
        val advFiles: Boolean = false,
        val advAfter: String = "",   // YYYY-MM-DD
        val advBefore: String = "",   // YYYY-MM-DD
        /** Rows the advanced filters ran over -- the honest scope line. */
        val advScope: Int = 0,
        val unreadCount: Int? = null,
        /**
         * The mailbox's own identity from `GET /me`. The greeting is built from
         * these, not from the sign-in account: `/me` answers with the name the
         * mailbox carries, and null means it never said so — in which case the
         * screen shows no name rather than a plausible one.
         */
        val accountName: String? = null,
        val accountAddress: String? = null,
        /** The mailbox's own state, which is not the account's state. Shown
         *  separately so the two are never confused on screen. */
        val accountStatus: String? = null,
        val memberSince: String? = null,
        /**
         * The counts `/stats` answers for. Each is null until the server says it,
         * and a null draws no badge — `0` would claim the folder is empty when
         * nobody was asked. The list grew with the deployment: `starred`,
         * `archive`, `others` and `snoozed` are answered now
         * (`grapmail.ts:677-688`), which is why they have fields below rather
         * than the hardcoded nulls they used to have in the drawer.
         */
        val statsSent: Int? = null,
        val statsDrafts: Int? = null,
        val statsSpam: Int? = null,
        val statsTrash: Int? = null,
        /**
         * The four figures this deployment added to `/stats`
         * (`grapmail.ts:666-688`: `starred`, `archive`, `others`, `snoozed`),
         * counted by the same `boxCount` that answers the listing beside them.
         *
         * [statsSnoozed] does double duty: it is the capability signal for the
         * drawer's Snoozed row. `grapmail-box-terms.ts:43,66-68` puts `snoozed`
         * in `BOXES` and `grapmail-boxes.ts:178` answers it, so the box is real;
         * whether the server the app is talking to has that code is a fact about
         * the deployment, and the only honest way to learn it is to be answered.
         * A null therefore hides the row rather than showing a folder that might
         * answer 400 "Unknown box".
         */
        val statsStarred: Int? = null,
        val statsArchive: Int? = null,
        val statsOthers: Int? = null,
        val statsSnoozed: Int? = null,
        val busy: Boolean = false,
        /**
         * Bumped whenever a list load settles, successfully or not.
         *
         * The refresh gesture needs a signal that a load has ENDED, and cannot use
         * [loading]: part of the no-blank rule is that a refresh over a populated
         * list never raises `loading` at all, so keying the spinner's release on
         * that Boolean meant the key never changed, the effect never re-ran, and one
         * pull-to-refresh left an indeterminate spinner parked over the inbox for the
         * rest of the session.
         */
        val settleGeneration: Int = 0,
    ) {
        val selecting: Boolean get() = selection.isNotEmpty()
        val hasRows: Boolean get() = rows.isNotEmpty()
        val canLoadMore: Boolean get() = !loadingMore && loaded < total
        /** How many rows the chosen sort actually re-ordered. */
        val sortScope: Int get() = loaded
    }

    private val _state = MutableStateFlow(MailboxUiState())
    val state: StateFlow<MailboxUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    /**
     * The boxes this session has already asked the server about.
     *
     * A mailbox that re-reads page 1 every time the screen reappears is the hub's
     * defect wearing a different hat: it pays for a full page on every return
     * from a thread, and when one of those reads fails it looks like a load
     * failure on a list that is already on screen. So entering the home asks only
     * for a box that has never been fetched; choosing a box fetches, and
     * pull-to-refresh is the one other way page 1 is requested.
     */
    private val loadedBoxes = mutableSetOf<MailBox>()

    /// The conversation `thread` currently holds. Returning to a thread the app
    /// already has must not cost another payload read, and a snapshot that seeded
    /// a body must not have it fetched away on appear.
    private var threadLoadedFor: String? = null

    /**
     * The stored bearer, or null when there is none. Every loader treats null as
     * "sign in again" rather than calling the API anonymously: an unauthenticated
     * mail request answers 401, which the interceptor would read as a dead
     * session and act on, and a screen that was never signed in would be kicked
     * out of one.
     */
    private suspend fun token(): String? =
        SessionStore.token(getApplication())

    init {
        viewModelScope.launch {
            if (snapshotMode) return@launch
            val t = token() ?: return@launch
            // The label strip is the reference's only backed substitute for the
            // personal/work chips, so it loads with the list rather than waiting
            // for the labels screen.
            runCatching { Api.mailLabels(t) }
                .onSuccess {
                    MailStore.putLabels(it.labels)
                    _state.update { s -> s.copy(labels = it.labels, labelsRead = AssignmentsRead.Read) }
                }
                // An empty label list and a refused label list are different facts.
                // Without this the sheet says "No labels yet. Create one from the
                // drawer." for a mailbox whose labels simply could not be read -- the
                // same invented-negative the assignments read refuses to make.
                .onFailure {
                    _state.update { s -> s.copy(labelsRead = AssignmentsRead.Failed) }
                }
            // `/me` answers with the mailbox's own name and address. A failure
            // here is not an error screen: the greeting falls back to no name,
            // which is honest, and the list is what matters.
            runCatching { Api.mailMe(t) }.onSuccess { me ->
                MailStore.putMe(me)
                _state.update { it.copy(
                    accountName = me.name,
                    accountAddress = me.address,
                    accountStatus = me.status,
                    memberSince = me.memberSince,
                ) }
            }
        }
    }

    // ── list ─────────────────────────────────────────────────────────────────

    fun open(box: MailBox) {
        loadedBoxes.add(box)
        _state.update { it.copy(box = box, selection = emptySet()) }
        load(replace = true)
    }

    /// Fetch the current box when it has never been fetched. Called on entry to
    /// the home screen — see `loadedBoxes` for why this is not just `load()`.
    fun ensureLoaded() {
        if (loadedBoxes.contains(_state.value.box)) return
        // Recorded before the request, the way `open()` records it. Testing the set
        // and never writing to it left the default Inbox permanently absent from it,
        // so every return from a thread, a reader or Settings paid for a full page-1
        // read — the exact over-fetch this field's own comment says it exists to
        // prevent — and one of those reads failing raises a stale-list banner over
        // mail that is already on screen. iOS inserts here and did not need fixing.
        loadedBoxes.add(_state.value.box)
        load(replace = true)
    }

    /**
     * Typing. Debounced, and a term shorter than 3 characters never leaves the
     * device: a one-character `q` scans the whole mailbox server-side
     * (`grapmail.ts:688`), so every keystroke would be a full-table read.
     */
    fun query(text: String) {
        _state.update { it.copy(query = text) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            load(replace = true)
        }
    }

    fun setFilter(f: MailFilter) {
        _state.update { it.copy(filter = f) }
        viewModelScope.launch { reproject() }
    }

    fun setSort(s: MailSort) {
        _state.update { it.copy(sort = s) }
        viewModelScope.launch { reproject() }
    }

    /**
     * Load a page. `replace` restarts the box (refresh, new search, new box);
     * otherwise the page is appended, which is what makes scrolling cheap.
     */
    fun load(replace: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val box = _state.value.box
            val term = _state.value.query.trim().takeIf { it.length >= 3 }
            val page = if (replace) 1 else _state.value.pages + 1

            // Show the spinner only when there is nothing to show. A refresh over
            // an existing list must not blank it — the hub's defect, designed out
            // here rather than rediscovered.
            if (replace && !_state.value.hasRows) _state.update { it.copy(loading = true, staleError = null) }
            if (!replace) _state.update { it.copy(loadingMore = true) }

            val t = token()
            if (t == null) {
                // The generation moves here too. This is an exit from a load, and the
                // refresh gesture waits on precisely that signal; leaving it out
                // parks an indeterminate spinner over the list for the rest of the
                // screen's life the moment a session has expired.
                _state.update { it.copy(loading = false, loadingMore = false,
                    fatal = "Sign in again.", settleGeneration = it.settleGeneration + 1) }
                return@launch
            }

            // Not `runCatching`: it swallows `CancellationException` like any other
            // Throwable, and this method cancels the previous read on every call. So a
            // box switch, or one keystroke past the search debounce, ran the failure
            // arm for a read that was stopped on purpose — and because the blocked
            // OkHttp call only notices the cancellation when its response lands, the
            // superseded read can finish AFTER the newer one, printing "couldn't reach
            // the server" over the list the newer read had just filled.
            try {
                val resp = Api.mailInbox(t, box, page = page, q = term)
                MailStore.putPage(box, term, resp, replace)
                if (term == null) {
                    // A side read. If it fails the list above still says what it
                    // knows, so nothing here raises an error; a cancelled one is
                    // rethrown like the outer call's.
                    try {
                        MailStore.putStats(Api.mailStats(t))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Counts stay null, which draws no badge, rather than 0.
                    }
                }
                _state.update { it.copy(loading = false, loadingMore = false, fatal = null,
                    staleError = null, settleGeneration = it.settleGeneration + 1) }
                reproject()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e            // superseded: the read that replaced this one reports
            } catch (e: Exception) {
                val noBox = (e as? ApiException)?.code == "NO_MAILBOX"
                val msg = mailReason(e)
                _state.update { cur ->
                    cur.copy(
                        loading = false, loadingMore = false,
                        noMailbox = noBox,
                        // With rows on screen this is a stale list, not an
                        // empty one. Without them it is the error screen.
                        staleError = if (cur.hasRows) msg else null,
                        fatal = if (cur.hasRows || noBox) null else msg,
                        settleGeneration = cur.settleGeneration + 1,
                    )
                }
                if (noBox) _state.update { it.copy(rows = emptyList(), loaded = 0, total = 0) }
            }
        }
    }

    fun loadMore() {
        if (!_state.value.canLoadMore) return
        load(replace = false)
    }

    /**
     * Re-derive the visible rows from what is loaded. Never a network call — this
     * runs on filter and sort changes, which are local operations.
     */
    private suspend fun reproject() {
        val s = _state.value
        val cache = MailStore.page(s.box, s.query.trim().takeIf { it.length >= 3 })
        var rows = cache?.messages ?: s.rows
        val total = cache?.total ?: s.total

        if (s.filter == MailFilter.Unread) rows = rows.filterNot { it.isRead }

        // The advanced filters run here, on the loaded pages, before the sort.
        // Blank fields are absent filters, not empty-string matches: a screen
        // that sent "" at the server would match nothing, and one that matched
        // "" here would hide mail the person never meant to exclude.
        val advOn = s.advFrom.isNotBlank() || s.advTo.isNotBlank() ||
            s.advSubject.isNotBlank() || s.advFiles ||
            s.advAfter.isNotBlank() || s.advBefore.isNotBlank()
        if (advOn) {
            rows = rows.filter { m ->
                (s.advFrom.isBlank() ||
                    (m.from?.display ?: "").contains(s.advFrom.trim(), ignoreCase = true)) &&
                (s.advTo.isBlank() || m.to.any { it.contains(s.advTo.trim(), ignoreCase = true) } ||
                    m.cc.any { it.contains(s.advTo.trim(), ignoreCase = true) }) &&
                (s.advSubject.isBlank() ||
                    (m.subject ?: "").contains(s.advSubject.trim(), ignoreCase = true)) &&
                (!s.advFiles || m.hasAttachments) &&
                (s.advAfter.isBlank() || (m.createdAt ?: "").take(10) >= s.advAfter.trim()) &&
                (s.advBefore.isBlank() || (m.createdAt ?: "").take(10) <= s.advBefore.trim())
            }
        }

        rows = when (s.sort) {
            // The server's own order: covers the whole box, not just the pages.
            MailSort.Newest -> rows
            MailSort.Oldest -> rows.reversed()
            MailSort.SenderAsc -> rows.sortedBy { it.from?.display.orEmpty().lowercase() }
            MailSort.SenderDesc -> rows.sortedByDescending { it.from?.display.orEmpty().lowercase() }
            MailSort.SubjectAsc -> rows.sortedBy { (it.subject ?: "").lowercase() }
            MailSort.SubjectDesc -> rows.sortedByDescending { (it.subject ?: "").lowercase() }
        }

        val stats = runCatching { MailStore.stats() }.getOrNull()
        _state.update { it.copy(
            rows = rows,
            total = total,
            loaded = cache?.messages?.size ?: it.loaded,
            pages = cache?.pages ?: it.pages,
            // The scope the sheet prints: how many loaded rows the filters ran
            // over BEFORE filtering, so "12 of 40" cannot be read as the
            // server's answer about the whole box.
            advScope = cache?.messages?.size ?: it.loaded,
            // Each stays null when the server did not answer it. A null draws no
            // badge; a 0 would claim an empty folder.
            unreadCount = stats?.inbox?.unread,
            statsSent = stats?.sent,
            statsDrafts = stats?.drafts,
            statsSpam = stats?.spam,
            statsTrash = stats?.trash,
            statsStarred = stats?.starred,
            statsArchive = stats?.archive,
            statsOthers = stats?.others,
            statsSnoozed = stats?.snoozed,
        ) }
    }

    // ── selection ────────────────────────────────────────────────────────────

    fun toggleSelect(id: String) = _state.update {
        val next = it.selection.toMutableSet()
        if (!next.add(id)) next.remove(id)
        it.copy(selection = next)
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun selectAll() = _state.update { it.copy(selection = it.rows.map { r -> r.id }.toSet()) }

    // ── actions ──────────────────────────────────────────────────────────────

    /**
     * Apply one action to the selected messages.
     *
     * Filing invalidates the *destination* box rather than inserting into it: the
     * app cannot know where the server put the row in that box's order, and
     * inventing a position would show mail the server does not list there.
     */
    fun act(action: MailAction, until: String? = null) {
        val ids = _state.value.selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val t = token() ?: return@launch
            _state.update { it.copy(busy = true) }
            var failure: String? = null
            val done = mutableListOf<String>()
            for (id in ids) {
                runCatching { Api.mailAction(t, id, action, until) }
                    .onSuccess { done.add(id); applyLocally(id, action) }
                    .onFailure { failure = it.message ?: "That action didn't go through." }
            }
            _state.update { it.copy(busy = false, selection = emptySet(), staleError = failure) }
            if (action == MailAction.Snooze) {
                // Only the messages the server accepted get an undo. Offering one
                // for a message whose snooze failed would unsnooze mail that is
                // still in the inbox and say it had been postponed.
                _state.update { s ->
                    if (done.isEmpty()) s else s.copy(
                        snoozeUndoIds = done,
                        snoozeUndoNote = snoozeNote(done.size, until),
                    )
                }
            } else if (action != MailAction.Unsnooze) {
                // Any other filing action supersedes the snooze offer. An
                // unsnooze keeps it deliberately: a partial undo leaves the rest
                // postponed, and the note lists what still is.
                _state.update { it.copy(snoozeUndoIds = emptyList(), snoozeUndoNote = null) }
            }
            // The filing undo, same rule as the snooze pair: only the ids the
            // server accepted, and only for the six actions that have a real
            // inverse route. Read/star do not raise it -- their rows stay on
            // screen with their own toggle.
            val inv = action.inverse()
            _state.update { s ->
                if (inv == null || done.isEmpty()) s.copy(undoIds = emptyList(),
                    undoInverse = null, undoNote = null)
                else s.copy(undoIds = done, undoInverse = inv,
                    undoNote = action.pastLine(done.size))
            }
            invalidateFor(action)
            load(replace = true)
        }
    }

    /** The undo line, with the instant the server was told to hold the mail until. */
    private fun snoozeNote(count: Int, until: String?): String {
        val when_ = until?.let { raw ->
            runCatching {
                java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a")
                    .format(java.time.ZonedDateTime.parse(raw)
                        .withZoneSameInstant(java.time.ZoneId.systemDefault()))
            }.getOrNull()
        }
        val head = if (count == 1) "Snoozed 1 message" else "Snoozed $count messages"
        return if (when_ != null) "$head until $when_" else head
    }

    /**
     * Take the mail back out of snooze, for the ids this session just postponed.
     *
     * The banner exists because the row that was snoozed disappears from the inbox
     * the instant the action succeeds: without a record here, "undo" would have had
     * nothing to name. The Snoozed box is the other way in, and it needs no state —
     * the server already knows which mail is waiting, so `actOn(id, Unsnooze)` on a
     * row in that box is the permanent path. This is the immediate one.
     */
    fun undoSnooze() {
        val ids = _state.value.snoozeUndoIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val t = token() ?: return@launch
            _state.update { it.copy(busy = true) }
            var failure: String? = null
            val restored = mutableListOf<String>()
            for (id in ids) {
                runCatching { Api.mailAction(t, id, MailAction.Unsnooze, null) }
                    .onSuccess { restored.add(id); MailStore.invalidate(MailBox.Inbox) }
                    .onFailure { failure = it.message ?: "That action didn't go through." }
            }
            _state.update { s ->
                s.copy(
                    busy = false,
                    // Only the messages that did NOT come back stay offered. Keeping
                    // the whole list on a partial failure would make the next Undo
                    // re-send the action for mail that is already back in the inbox,
                    // and reporting `restored.size` from the full list would count
                    // them twice.
                    snoozeUndoIds = s.snoozeUndoIds - restored,
                    snoozeUndoNote = when {
                        failure != null -> "${restored.size} of ${ids.size} came back. $failure"
                        else -> null
                    },
                    staleError = failure,
                )
            }
            load(replace = true)
        }
    }

    fun dismissSnoozeUndo() {
        _state.update { it.copy(snoozeUndoIds = emptyList(), snoozeUndoNote = null) }
    }

    /**
     * Reverse the last filing, for exactly the ids the server accepted then.
     *
     * The inverse is another `PATCH /message/:id/:action` -- the route is
     * reversible by shape (archive/unarchive, spam/unspam, trash/restore are
     * its own pairs) -- so this is a real path back, not a local reshuffle of
     * rows the server already moved. A partial failure keeps offering the
     * ones that did NOT go back, same as the snooze undo.
     */
    fun undoFiling() {
        val ids = _state.value.undoIds
        val inv = _state.value.undoInverse
        if (ids.isEmpty() || inv == null) return
        viewModelScope.launch {
            val t = token() ?: return@launch
            _state.update { it.copy(busy = true) }
            var failure: String? = null
            val redone = mutableListOf<String>()
            for (id in ids) {
                runCatching { Api.mailAction(t, id, inv, null) }
                    .onSuccess { redone.add(id); applyLocally(id, inv) }
                    .onFailure { failure = it.message ?: "That action didn't go through." }
            }
            _state.update { s ->
                val left = s.undoIds - redone
                s.copy(
                    busy = false,
                    undoIds = left,
                    undoInverse = if (left.isEmpty()) null else s.undoInverse,
                    undoNote = when {
                        failure != null -> "${redone.size} of ${ids.size} reversed. $failure"
                        else -> null
                    },
                    staleError = failure,
                )
            }
            invalidateFor(inv)
            load(replace = true)
        }
    }

    fun dismissUndo() {
        _state.update { it.copy(undoIds = emptyList(), undoInverse = null, undoNote = null) }
    }

    /** Write the advanced filters and re-project. No network call: these run
     *  on the loaded pages, which is what the sheet's scope line says. */
    fun setAdvanced(from: String, to: String, subject: String, files: Boolean,
                    after: String, before: String) {
        _state.update { it.copy(advFrom = from, advTo = to, advSubject = subject,
            advFiles = files, advAfter = after, advBefore = before) }
        viewModelScope.launch { reproject() }
    }

    fun clearAdvanced() = setAdvanced("", "", "", false, "", "")

    fun actOn(id: String, action: MailAction) {
        viewModelScope.launch {
            val t = token() ?: return@launch
            runCatching { Api.mailAction(t, id, action, null) }
                .onSuccess {
                    applyLocally(id, action); invalidateFor(action); reproject()
                    // A single-message action supersedes the undo offer for THAT
                    // message. Without this, snooze three, swipe one of them to
                    // Trash, press Undo, and the trashed message is unsnoozed too --
                    // `act()` clears the offer for bulk actions but this path had no
                    // equivalent, so the banner outlived the mail it described.
                    _state.update { s ->
                        if (!s.snoozeUndoIds.contains(id)) s
                        else {
                            val left = s.snoozeUndoIds - id
                            s.copy(snoozeUndoIds = left,
                                snoozeUndoNote = if (left.isEmpty()) null else s.snoozeUndoNote)
                        }
                    }
                    // A swipe is the most common filing there is, and the mail
                    // leaves the screen the instant it succeeds -- so the banner
                    // must offer the reverse here too, or the undo would exist
                    // only for selections and the single most undoable action
                    // would have no path back.
                    val inv = action.inverse()
                    _state.update { s ->
                        if (inv == null) s else s.copy(
                            undoIds = listOf(id), undoInverse = inv,
                            undoNote = action.pastLine(1),
                        )
                    }
                }
                .onFailure {
                    _state.update { s ->
                        s.copy(staleError = (it as? ApiException)?.message
                            ?: "That action didn't go through.")
                    }
                }
        }
    }

    private suspend fun applyLocally(id: String, action: MailAction) {
        when (action) {
            MailAction.Read -> MailStore.patchFlags(id, read = true)
            MailAction.Unread -> MailStore.patchFlags(id, read = false)
            MailAction.Star -> MailStore.patchFlags(id, starred = true)
            MailAction.Unstar -> MailStore.patchFlags(id, starred = false)
            MailAction.Trash, MailAction.Archive, MailAction.Spam, MailAction.Snooze ->
                MailStore.removeEverywhere(id)
            else -> Unit
        }
    }

    private suspend fun invalidateFor(action: MailAction) {
        when (action) {
            MailAction.Trash -> MailStore.invalidate(MailBox.Trash)
            MailAction.Archive -> MailStore.invalidate(MailBox.Archive)
            MailAction.Spam -> MailStore.invalidate(MailBox.Spam)
            MailAction.Restore, MailAction.Unarchive, MailAction.Unspam ->
                MailStore.invalidateAllBoxes()
            // A snooze moves the message out of the inbox's view and into the
            // snoozed box's (`grapmail-boxes.ts:178` lists it over every folder),
            // so both listings are wrong the moment the action succeeds and
            // neither may be shown from the cache it just invalidated.
            MailAction.Snooze -> {
                MailStore.invalidate(MailBox.Inbox)
                MailStore.invalidate(MailBox.Snoozed)
            }
            // Undoing a snooze returns the mail to the listing it was hidden from,
            // which is the inbox — so that is the box that must be re-read, and the
            // snoozed box loses it at the same moment.
            MailAction.Unsnooze -> {
                MailStore.invalidate(MailBox.Inbox)
                MailStore.invalidate(MailBox.Snoozed)
            }
            else -> Unit
        }
    }

    /**
     * Permanent destruction, from Trash only. Separate from [act] because it is
     * the one action that cannot be undone and therefore never rides along with a
     * bulk "delete" — the reference's Delete button trashes; this is the second,
     * explicit step the design's confirmation screen is for.
     */
    fun deletePermanently() {
        val ids = _state.value.selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val t = token() ?: return@launch
            _state.update { it.copy(busy = true) }
            var failure: String? = null
            for (id in ids) {
                runCatching { Api.mailDeletePermanent(t, id) }
                    .onSuccess {
                        MailStore.removeEverywhere(id)
                        // The message is gone, so its label pairs are gone with it.
                        // Left in the map they would be resurrected if the same id
                        // ever appeared again, showing a tick for mail that does not
                        // exist — and the next read would say nothing about it.
                        MailStore.forgetAssignments(id)
                    }
                    .onFailure { failure = it.message }
            }
            _state.update { it.copy(busy = false, selection = emptySet(), staleError = failure) }
            load(replace = true)
        }
    }

    /**
     * Read the account's label pairs, if that has not already happened.
     *
     * Asked once per session, not once per sheet opening: the route returns the
     * whole set, so a second call buys nothing the first one did not already
     * contain, and a sheet that re-read on every appearance inherits the hub's
     * defect — a failed refresh that reads as a failed load.
     */
    fun ensureAssignments() {
        // A note from the previous selection must not be sitting there when the
        // sheet opens for a different set of messages.
        _state.update { it.copy(labelNote = null) }
        when (_state.value.assignmentsRead) {
            AssignmentsRead.Reading -> return
            AssignmentsRead.Read -> refreshMarks()
            else -> loadAssignments()
        }
    }

    private fun loadAssignments() {
        val ids = _state.value.selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val t = token() ?: return@launch
            _state.update { it.copy(assignmentsRead = AssignmentsRead.Reading, labelNote = null) }
            runCatching { Api.mailLabelAssignments(t) }
                .onSuccess {
                    MailStore.putAssignments(it.assignments)
                    _state.update { s -> s.copy(assignmentsRead = AssignmentsRead.Read) }
                    applyMarks()
                }
                .onFailure { e ->
                    // Either way the flag has to leave `Reading`, or a second failure
                    // is impossible to notice: `ensureAssignments` short-circuits on
                    // Reading, so a state left there silently stops every later read
                    // for the rest of the session and the sheet keeps drawing marks
                    // it computed from an empty map.
                    //
                    // With a cached map the marks already on screen stay (a refresh
                    // may not blank what is displayed) and the state resolves to Read,
                    // which is the truth about the cache; without one, Failed, which is
                    // the honest unknown. Both report the failure.
                    val hadMap = MailStore.assignments() != null
                    _state.update { s ->
                        s.copy(
                            assignmentsRead = if (hadMap) AssignmentsRead.Read else AssignmentsRead.Failed,
                            staleError = e.message ?: "The labels could not be read.",
                        )
                    }
                    if (hadMap) applyMarks()
                }
        }
    }

    fun refreshMarks() { viewModelScope.launch { applyMarks() } }

    /** Recompute the sheet's marks from the store. Cheap, and never a network call. */
    private suspend fun applyMarks() {
        val ids = _state.value.selection.toList()
        val map = MailStore.assignments()
        val marks = _state.value.labels.associate { l ->
            val with = ids.count { (map?.get(it) ?: emptySet()).contains(l.id) }
            l.id to when {
                map == null || ids.isEmpty() -> LabelMark.Unknown
                with == 0 -> LabelMark.Off
                with == ids.size -> LabelMark.On
                else -> LabelMark.Mixed
            }
        }
        _state.update { it.copy(labelMarks = marks) }
    }

    /**
     * Tap a label in the sheet: apply it to the selection, or take it off.
     *
     * The direction comes from the state the read produced, and the two ambiguous
     * cases are resolved toward adding rather than removing:
     *
     *  - `Mixed` completes the label across the selection. Half-checked means the
     *    user is mid-operation on a group; finishing is what every mail client does
     *    and un-tagging the ones that have it would discard work they did not ask
     *    to discard.
     *  - `Unknown` (the read never answered) only ever assigns. Both writes are
     *    idempotent server-side, so adding a label that happens to be there costs
     *    nothing — while REMOVING one on an unknown baseline could take off a label
     *    the user never meant to touch. The sheet says the state is not known, so
     *    the tap is not made on a false claim.
     *
     * The store is patched only for the messages the server accepted, and the
     * sheet stays open to show the result: unlike a filing action, nothing in the
     * list visibly changes here, so a sheet that closed would leave the user
     * without an answer.
     */
    fun toggleLabel(labelId: String) {
        val ids = _state.value.selection.toList()
        if (ids.isEmpty()) return
        val mark = _state.value.labelMarks[labelId] ?: LabelMark.Unknown
        val remove = mark == LabelMark.On
        viewModelScope.launch {
            val t = token() ?: return@launch
            _state.update { it.copy(busy = true, labelNote = null) }
            // On `Mixed` only the messages that lack it are written; on `On` only
            // the ones carrying it are. Writing all of either way would report a
            // count the server never changed.
            val targets = ids.filter { id ->
                val has = (MailStore.assignments()?.get(id) ?: emptySet()).contains(labelId)
                if (remove) has else !has
            }
            if (targets.isEmpty()) {
                _state.update { it.copy(busy = false) }
                return@launch
            }
            var done = 0
            var failure: String? = null
            val labelName = _state.value.labels.firstOrNull { it.id == labelId }?.name ?: "label"
            targets.forEach { id ->
                runCatching { if (remove) Api.mailLabelDetach(t, id, labelId)
                              else Api.mailLabelAssign(t, id, labelId) }
                    .onSuccess {
                        done++
                        if (remove) MailStore.markUnassigned(id, labelId)
                        else MailStore.markAssigned(id, labelId)
                    }
                    .onFailure { failure = (it as? ApiException)?.message
                        ?: "The label was not saved." }
            }
            _state.update { s ->
                s.copy(
                    busy = false,
                    // `done == 0` is tested FIRST, and the failure string can never
                    // be null: `Throwable.message` is nullable, so a bare
                    // `it.message` made "every write failed, none said why" fall
                    // through to the success wording and render "Labelled 0
                    // messages" over a selection that was not labelled at all.
                    labelNote = when {
                        done == 0 -> failure ?: "The label was not saved."
                        failure != null -> "$done of ${targets.size} saved. $failure"
                        remove -> "Removed \"$labelName\" from $done"
                        done == 1 -> "Labelled 1 message"
                        else -> "Labelled $done messages"
                    },
                )
            }
            refreshMarks()
        }
    }

    // ── compose ──────────────────────────────────────────────────────────────

    data class ComposeState(
        val to: String = "", val cc: String = "", val bcc: String = "",
        val subject: String = "", val body: String = "",
        val inReplyTo: String? = null,
        /** The draft row this compose was opened FROM. Non-empty means the
         *  person is continuing a saved message, and a successful send
         *  deletes it — see [send]. */
        val draftId: String = "",
        val sending: Boolean = false,
        /** The server's own verdict, shown verbatim. Never a local "Sent". */
        val result: String? = null,
        val resultStatus: String? = null,
        val error: String? = null,
    )

    private val _compose = MutableStateFlow(ComposeState())
    val compose: StateFlow<ComposeState> = _compose.asStateFlow()

    fun composeUpdate(transform: (ComposeState) -> ComposeState) = _compose.update(transform)

    /** Reply: prefill the thread's addressing and carry In-Reply-To. */
    fun startReply(m: MailMessageDto) = _compose.update {
        it.copy(to = m.from?.address ?: "", subject = replySubject(m.subject), inReplyTo = m.id)
    }

    /**
     * Prepare a reply, reply-all or forward from the FULL message row.
     *
     * The quote is built from what the server actually said the message
     * contains (`GET /message/:id` -- bodyText, the to/cc lists, the date),
     * never from the snippet the list row carries: a quoted reply that got
     * the first 90 characters would look complete and be wrong. Reply-all
     * adds the original To and Cc minus this account's own address, because
     * "reply all" that drops the thread's other recipients is a lie about
     * who will read it. Forward carries no In-Reply-To: the server threads
     * replies by that header, and a forwarded message is not a reply.
     */
    fun prepareReply(id: String, mode: String, signature: String) {
        viewModelScope.launch {
            val t = token() ?: return@launch
            val full = runCatching { Api.mailMessage(t, id) }.getOrNull()?.message
                ?: return@launch
            val me = _state.value.accountAddress?.lowercase()
            val quoted = quoteOf(full)
            when (mode) {
                // A draft is not a reply: the row IS the person's own
                // half-written message, so it loads verbatim — no quote
                // block, no "Re:", no second signature (the draft already
                // carries the one they saved). This is the leg the app was
                // missing: tapping a draft used to open a read-only thread,
                // so a saved message could never be finished or sent.
                "draft" -> _compose.update { it.copy(
                    to = full.to.joinToString(", "),
                    cc = full.cc.joinToString(", "),
                    // No bcc: the message DTO has no bcc field, because the
                    // route strips it before storing — a draft's hidden
                    // recipients are genuinely not recoverable, so the
                    // composer leaves the field empty rather than showing
                    // a list it invented. The person re-adds them; sending
                    // to a Bcc they cannot see would be the worse error.
                    subject = full.subject ?: "",
                    body = full.bodyText ?: "",
                    inReplyTo = full.inReplyTo,
                    draftId = full.id,
                    result = null, resultStatus = null, error = null,
                ) }
                "all" -> {
                    val others = (listOfNotNull(full.from?.address) + full.to + full.cc)
                        .map { it.trim() }.filter { it.isNotEmpty() }
                        .filterNot { it.lowercase() == me }
                        .distinct()
                    _compose.update { it.copy(
                        to = others.joinToString(", "),
                        subject = replySubject(full.subject),
                        body = quoted + signatureBlock(signature),
                        inReplyTo = full.id,
                    ) }
                }
                "forward" -> {
                    val head = "Forwarded message\n" +
                        "From: ${full.from?.display ?: full.from?.address ?: ""}\n" +
                        "Date: ${full.createdAt ?: ""}\n" +
                        "Subject: ${full.subject ?: "(no subject)"}\n" +
                        "To: ${full.to.joinToString(", ")}\n\n"
                    _compose.update { it.copy(
                        to = "", subject = forwardSubject(full.subject),
                        body = head + quoted + signatureBlock(signature),
                        inReplyTo = null,
                    ) }
                }
                else -> {
                    _compose.update { it.copy(
                        to = full.from?.address ?: "",
                        subject = replySubject(full.subject),
                        body = quoted + signatureBlock(signature),
                        inReplyTo = full.id,
                    ) }
                }
            }
        }
    }

    /** The quote block: every body line prefixed, the way the web's replies
     *  read. An empty body quotes nothing -- inventing a blank line under the
     *  header would claim the message had a body. */
    private fun quoteOf(m: MailMessageFullDto): String {
        val body = m.bodyText?.trim() ?: ""
        if (body.isEmpty()) return "\n\n" +
            "On ${m.createdAt ?: "an unknown date"}, ${(m.from?.display ?: m.from?.address ?: "")} wrote:\n"
        val who = (m.from?.display ?: m.from?.address ?: "")
        val lines = body.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
        return "\n\nOn ${m.createdAt ?: "an unknown date"}, $who wrote:\n$lines\n"
    }

    /**
     * The signature arrives as EDITABLE text appended when the compose opens,
     * the same way the web client appends it client-side -- what the person
     * sees in the body is what is sent; nothing is stitched on behind the
     * scenes at send time. Per-address override first (`settings.signatures`),
     * then the default, because the profile keys one per alias.
     */
    private fun signatureBlock(signature: String): String =
        if (signature.isBlank()) "" else "\n\n-- \n$signature"

    private fun forwardSubject(s: String?): String {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return "Fwd:"
        if (t.startsWith("Fwd:", true)) return t
        return "Fwd: $t"
    }

    private fun replySubject(s: String?): String {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return "Re:"
        if (t.startsWith("Re:", true) || t.startsWith("Fwd:", true)) return t
        return "Re: $t"
    }

    fun resetCompose() { _compose.value = ComposeState() }

    /**
     * Send. The receipt shows `status` and `deliveryError` as the server reported
     * them — a message the queue has only accepted is not a delivered message,
     * and saying otherwise is the exact dishonesty the send-status test exists to
     * pin.
     */
    fun send(e2eEnvelope: String? = null) {
        val s = _compose.value
        if (s.sending) return
        // An empty To is refused here, not by the server: a message with no
        // recipient is not a send, and a receipt naming the server's 422 after
        // the person pressed Send twice is worse than a button that says why
        // it is waiting. Cc and Bcc do not count -- the route requires `to`.
        if (splitAddresses(s.to).isEmpty()) {
            _compose.update { it.copy(error = "Add at least one recipient in To.") }
            return
        }
        viewModelScope.launch {
            val t = token() ?: run { _compose.update { it.copy(error = "Sign in again.") }; return@launch }
            // The envelope goes on the wire as the JSON OBJECT the route
            // parses — a string is silently dropped by sanitiseE2eEnvelope
            // and the sealed send would then 400 on its deliberate empty
            // body. Unparseable text refuses the send whole: the alternative
            // reading of "no envelope" would send the plaintext the envelope
            // exists to keep private.
            val envelope = e2eEnvelope?.let {
                try { Json.parseToJsonElement(it).jsonObject }
                catch (e: Exception) {
                    _compose.update { s -> s.copy(error = "The encrypted envelope could not be prepared — nothing was sent.") }
                    null
                }
            }
            if (e2eEnvelope != null && envelope == null) return@launch
            _compose.update { it.copy(sending = true, error = null, result = null, resultStatus = null) }
            runCatching {
                Api.mailSend(t, MailSendRequest(
                    to = splitAddresses(s.to), cc = splitAddresses(s.cc),
                    bcc = splitAddresses(s.bcc), subject = s.subject,
                    // With an envelope the plaintext does not travel: the
                    // route stores its notice in place of a body, and a
                    // client that sent both would leak exactly what the
                    // envelope exists to keep private.
                    bodyText = if (envelope != null) "" else s.body,
                    inReplyTo = s.inReplyTo,
                    e2eEnvelope = envelope,
                ))
            }.onSuccess { r ->
                _compose.update { it.copy(sending = false,
                    result = r.message ?: r.error, resultStatus = r.status,
                    error = r.deliveryError ?: r.error) }
                MailStore.invalidate(MailBox.Sent)
                MailStore.invalidate(MailBox.Inbox)
                // A draft that was just sent is no longer a draft. The order
                // is the whole point: the delete runs only AFTER the server
                // accepted the message, so a failed send leaves the draft
                // exactly where it was. Deleting first would trade a
                // half-written mail for an error message.
                val sent = r.status == "sent" || r.status == "queued"
                if (s.draftId.isNotEmpty() && sent) {
                    runCatching { Api.mailDeleteMessage(t, s.draftId) }
                        .onSuccess { MailStore.invalidate(MailBox.Drafts) }
                        // The mail IS sent. A draft row that outlives its
                        // send is a stale copy, not a lost message, so this
                        // says nothing and does not fail the receipt.
                        .onFailure { }
                    _compose.update { it.copy(draftId = "") }
                }
                load(replace = true)
            }.onFailure { e ->
                _compose.update { it.copy(sending = false,
                    error = e.message ?: "The message could not be sent.") }
            }
        }
    }

    /** Save as a draft. Discarding a half-written message without asking is the
     *  other way a mail app loses something; this is what the back button does. */
    fun saveDraft() {
        val s = _compose.value
        viewModelScope.launch {
            val t = token() ?: return@launch
            _compose.update { it.copy(sending = true) }
            runCatching {
                Api.mailDraft(t, MailDraftRequest(
                    to = splitAddresses(s.to), cc = splitAddresses(s.cc),
                    subject = s.subject, bodyText = s.body, inReplyTo = s.inReplyTo,
                ))
            }.onSuccess {
                _compose.update { it.copy(sending = false, result = "Saved to Drafts", resultStatus = "draft") }
                MailStore.invalidate(MailBox.Drafts)
            }.onFailure { e ->
                _compose.update { it.copy(sending = false, error = e.message ?: "The draft was not saved.") }
            }
        }
    }

    private fun splitAddresses(raw: String): List<String> =
        raw.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Save an attachment into the app's own files directory and report what
     * actually happened.
     *
     * The callback receives the exact text the row shows, and a failure never
     * receives a path. The file lands in `getExternalFilesDir`, which the app
     * owns and which the system removes with the app — not shared storage,
     * because a downloaded email attachment has no business being readable by
     * every other app on the device.
     *
     * Opening it is deliberately not attempted here: that needs a FileProvider
     * authority and a MIME-typed intent, and a half-wired "open" that throws on
     * some devices is worse than a saved file with a clear message about where it
     * went. Recorded as the sector's one unfinished affordance rather than left
     * implicit.
     */
    fun downloadAttachment(a: MailAttachmentDto, then: (String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: run { then("Sign in again to save this."); return@launch }
            runCatching { Api.mailAttachmentBytes(t, a.id) }
                .onSuccess { bytes ->
                    val outcome = runCatching {
                        val dir = getApplication<android.app.Application>()
                            .getExternalFilesDir(null) ?: return@runCatching "No app storage available."
                        val safe = (a.name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
                        val file = java.io.File(dir, safe)
                        file.writeBytes(bytes)
                        "Saved to ${file.absolutePath}"
                    }.getOrElse { "Saved, but the file could not be written." }
                    then(outcome)
                }
                .onFailure { e ->
                    then((e as? ApiException)?.message ?: "This attachment could not be downloaded.")
                }
        }
    }

    /**
     * Open an attachment in another app.
     *
     * This is the half `downloadAttachment` deliberately left undone — it said
     * "saved to a path" and stopped, because handing a `file://` URI to
     * ACTION_VIEW is rejected outright on modern Android. With a FileProvider
     * declared in the manifest the handoff works: the bytes go into the
     * attachment cache directory the provider exposes, the URI carries a
     * per-grant read permission, and the MIME type comes from the server's own
     * field rather than a guessed extension.
     *
     * Two honest limits: a file the person never saved is downloaded first
     * (nothing is invented about its contents), and when no app on the device
     * claims that type the message says so — "Nothing on this device can open
     * this" — instead of failing silently or pretending the open happened.
     */
    fun openAttachment(a: MailAttachmentDto, then: (String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: run { then("Sign in again to open this."); return@launch }
            val bytes = runCatching { Api.mailAttachmentBytes(t, a.id) }
                .getOrElse { e ->
                    then((e as? ApiException)?.message ?: "This attachment could not be downloaded.")
                    return@launch
                }
            val app = getApplication<android.app.Application>()
            val outcome = runCatching {
                val dir = java.io.File(app.cacheDir, "attachments").apply { mkdirs() }
                val safe = (a.name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
                val file = java.io.File(dir, safe)
                file.writeBytes(bytes)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    app, "${app.packageName}.fileprovider", file)
                // The server's own type wins; a filename extension is a guess
                // about a file the server described differently.
                val mime = a.mimeType?.takeIf { it.isNotBlank() } ?: "*/*"
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val chooser = android.content.Intent.createChooser(intent, null)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Asked by trying, not by resolveActivity: from API 30 a
                // visibility query returns null for apps this one cannot see,
                // which would say "nothing can open this" on a phone that has
                // a viewer installed. The exception is the honest answer.
                try {
                    app.startActivity(chooser)
                    "Opened."
                } catch (e: android.content.ActivityNotFoundException) {
                    "Nothing on this device can open a $mime file. Save it with " +
                        "Save to this device and open it from Files."
                }
            }.getOrElse {
                "This attachment could not be handed to another app."
            }
            then(outcome)
        }
    }

    // ── people ───────────────────────────────────────────────────────────────

    /**
     * The contacts the app can actually name: everyone who appears in the mail
     * it has loaded.
     *
     * There is no contacts endpoint on the GrapMail API — the web client derives
     * people the same way, from `?box=inbox` plus `?box=sent`
     * (`src/app/(mail)/mail/contacts/page.tsx:26-28`). Saying "contacts" while
     * reading the mailbox is honest; showing a list that looks like an address
     * book the user never filled in would not be.
     */
    data class Person(val address: String, val name: String?, val seen: Int)

    suspend fun people(): List<Person> {
        val counts = LinkedHashMap<String, Pair<String?, Int>>()
        MailBox.tabOrder.forEach { box ->
            MailStore.page(box, null)?.messages?.forEach { m ->
                m.from?.address?.takeIf { it.isNotBlank() }?.let { a ->
                    val prior = counts[a]
                    counts[a] = (m.from?.name ?: prior?.first) to ((prior?.second ?: 0) + 1)
                }
                m.to.forEach { a -> if (a.isNotBlank()) counts.putIfAbsent(a, null to 0) }
            }
        }
        return counts.map { (addr, v) -> Person(addr, v.first, v.second) }
            .sortedWith(compareByDescending<Person> { it.seen }.thenBy { it.address.lowercase() })
    }

    // ── attachments ─────────────────────────────────────────────────────────
    /**
     * The addresses this mailbox also receives as. This is what the reference's
     * account chip can honestly show: one signed-in account and its aliases, not
     * a switcher between accounts the app cannot hold — `SessionStore` stores
     * exactly one session.
     */
    suspend fun aliases(): List<com.grapsee.grapmail.data.MailAliasDto> {
        MailStore.aliases()?.let { return it }
        val t = token() ?: return emptyList()
        val fetched = runCatching { Api.mailAliases(t) }.getOrNull() ?: return emptyList()
        MailStore.putAliases(fetched.aliases)
        return fetched.aliases
    }

    /**
     * Upload bytes picked by the compose screen. The route answers with the row
     * it wrote, and the returned id is what a send carries — an attachment that
     * was never uploaded is a filename the recipient cannot open.
     */
    fun uploadAttachment(bytes: ByteArray, filename: String, mimeType: String, then: (MailAttachmentDto?, String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: run { then(null, "Sign in again to attach a file."); return@launch }
            runCatching { Api.mailAttachmentUpload(t, bytes, filename, mimeType, null) }
                .onSuccess { then(it, "Attached ${it.name ?: filename}.") }
                .onFailure { e ->
                    then(null, (e as? ApiException)?.message
                        ?: "This file could not be attached.")
                }
        }
    }

    // ── thread ──────────────────────────────────────────────────────────────

    data class ThreadState(
        val messages: List<com.grapsee.grapmail.data.MailMessageFullDto> = emptyList(),
        val loading: Boolean = false, val error: String? = null,
    )

    private val _thread = MutableStateFlow(ThreadState())
    val thread: StateFlow<ThreadState> = _thread.asStateFlow()

    /**
     * Open a thread. The list payload has no bodies by design, so the bodies are
     * read here and memoised — a row re-rendering must not re-read a payload.
     */
    fun openThread(threadId: String) {
        if (threadLoadedFor == threadId) return
        viewModelScope.launch {
            val t = token() ?: return@launch
            _thread.update { it.copy(loading = true, error = null) }
            runCatching { Api.mailThread(t, threadId) }
                .onSuccess { r ->
                    // Remembered on success, because this field IS the memo: without
                    // it the guard above never fires and every return from a thread
                    // to the list costs the whole conversation payload again -- which
                    // is the over-fetch the field's own comment promises not to do,
                    // and iOS does not. A failure deliberately does not record it, so
                    // re-opening retries instead of caching an empty conversation.
                    threadLoadedFor = threadId
                    r.messages.forEach { MailStore.putBody(it) }
                    _thread.update { it.copy(messages = r.messages, loading = false) }
                    // Opening marks read server-side; mirror it locally so the
                    // list it came from agrees without a second write.
                    r.messages.firstOrNull()?.let { MailStore.patchFlags(it.id, read = true) }
                    reproject()
                }
                .onFailure { e ->
                    _thread.update { it.copy(loading = false,
                        error = e.message ?: "This conversation could not be opened.") }
                }
        }
    }

    /**
     * The neighbours of an open message, in the order the list showed them.
     *
     * Every serious mail reader can step without going back to the list, and
     * the affordance has to be honest about its range: it walks the rows this
     * device actually loaded for the current box, not the whole mailbox. The
     * reader says "3 of 12 loaded" for exactly that reason — claiming the
     * box's own total would be a number nothing on this device has seen.
     *
     * Returns null on either side when there is nothing to step to, so the
     * chevron is disabled rather than tapping into nowhere.
     */
    fun neighboursOf(id: String): Pair<MailStep?, MailStep?> {
        val rows = _state.value.rows
        val idx = rows.indexOfFirst { it.id == id || it.threadId == id }
        if (idx < 0) return null to null
        return stepOf(rows.getOrNull(idx - 1)) to stepOf(rows.getOrNull(idx + 1))
    }

    /**
     * The row as the reader must open it. A row with a `threadId` is a
     * conversation and goes to the thread route; a row without one is a single
     * message and the thread route answers its id with a 404. The list's own
     * tap makes exactly this pair of decisions (`MailboxScreen`'s `tid ?: row.id`
     * and `tid != null`), so the stepper derives its target from the row instead
     * of assuming every neighbour is a thread — a reader that opened a message id
     * on the thread route would show "could not be opened" for mail that exists.
     */
    fun stepOf(row: MailMessageDto?): MailStep? =
        row?.let { MailStep(it.threadId ?: it.id, it.threadId != null) }

    /** The reader's position within the loaded rows, 1-based; 0 when absent. */
    fun loadedPositionOf(id: String): Int {
        val rows = _state.value.rows
        val idx = rows.indexOfFirst { it.id == id || it.threadId == id }
        return if (idx < 0) 0 else idx + 1
    }

    /** How many loaded rows the reader is stepping between, for its own label. */
    fun loadedRowCount(): Int = _state.value.rows.size

    /**
     * Mark one message unread from inside the reader, and report the outcome to
     * the reader.
     *
     * `actOn` is the list's path: it files a failure into the list's stale-read
     * banner, which is not on screen here, and it leaves the conversation's own
     * copy of the message flagged as read. A control in the reader therefore
     * needs both halves — the thread's flag patched, so the button that offered
     * this stops offering it, and the real result handed back to say out loud
     * what happened. `null` means the server accepted it.
     */
    fun markUnread(id: String, onOutcome: (String?) -> Unit) {
        viewModelScope.launch {
            val t = token()
            if (t == null) {
                onOutcome("You are signed out, so this could not be saved.")
                return@launch
            }
            runCatching { Api.mailAction(t, id, MailAction.Unread, null) }
                .onSuccess {
                    applyLocally(id, MailAction.Unread)
                    _thread.update { s ->
                        s.copy(messages = s.messages.map { m ->
                            if (m.id == id) m.copy(isRead = false) else m
                        })
                    }
                    reproject()
                    onOutcome(null)
                }
                .onFailure {
                    onOutcome((it as? ApiException)?.message
                        ?: "That action didn't go through.")
                }
        }
    }

    fun openMessage(id: String) {
        if (threadLoadedFor == id) return
        viewModelScope.launch {
            val t = token() ?: return@launch
            _thread.update { it.copy(loading = true, error = null) }
            val cached = MailStore.body(id)
            if (cached != null) {
                threadLoadedFor = id
                _thread.update { it.copy(messages = listOf(cached), loading = false) }
                return@launch
            }
            runCatching { Api.mailMessage(t, id) }
                .onSuccess { r ->
                    // Recorded only when a body actually arrived: caching the id for
                    // an empty response would make the guard above skip the retry and
                    // leave the screen showing nothing for a message that exists.
                    r.message?.let {
                        threadLoadedFor = id
                        MailStore.putBody(it)
                        _thread.update { s -> s.copy(messages = listOf(it), loading = false) }
                        MailStore.patchFlags(it.id, read = true)
                    }
                    reproject()
                }
                .onFailure { e ->
                    _thread.update { it.copy(loading = false,
                        error = e.message ?: "This message could not be opened.") }
                }
        }
    }

    // ── labels ─────────────────────────────────────────────────────────────

    data class LabelState(
        val labels: List<MailLabelDto> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val saved: String? = null,
    )

    private val _labels = MutableStateFlow(LabelState())
    val labelState: StateFlow<LabelState> = _labels.asStateFlow()

    fun refreshLabels() {
        viewModelScope.launch {
            if (snapshotMode) return@launch
            val t = token() ?: return@launch
            _labels.update { it.copy(loading = true, error = null) }
            runCatching { Api.mailLabels(t) }
                .onSuccess {
                    MailStore.putLabels(it.labels)
                    _labels.update { s -> s.copy(labels = it.labels, loading = false) }
                    _state.update { s -> s.copy(labels = it.labels, labelsRead = AssignmentsRead.Read) }
                }
                .onFailure { e ->
                    _labels.update { it.copy(loading = false,
                        error = e.message ?: "Labels could not be loaded.") }
                    _state.update { it.copy(labelsRead = AssignmentsRead.Failed) }
                }
        }
    }

    /**
     * Create a label. The colour key the route reads is `colour`
     * (`grapmail-labels.ts:105`); sending `color` creates an uncoloured label and
     * still returns 200, so the mistake would be invisible.
     */
    fun createLabel(name: String, colour: String?, then: (String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: run { then("Sign in again."); return@launch }
            runCatching { Api.mailCreateLabel(t, name, colour) }
                .onSuccess {
                    refreshLabels()
                    then("Created ${it.label?.name ?: name}.")
                }
                .onFailure { e ->
                    then((e as? ApiException)?.message ?: "The label could not be created.")
                }
        }
    }

    /// Rename or recolour a label. The route is `PATCH /labels/:id` and it
    /// answers with the row it wrote, so the list is re-read rather than
    /// patched in place — the same rule the filing actions follow.
    fun renameLabel(id: String, name: String, colour: String?, then: (String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: run { then("Sign in again."); return@launch }
            runCatching { Api.mailRenameLabel(t, id, name, colour) }
                .onSuccess {
                    refreshLabels()
                    then("Saved.")
                }
                .onFailure { e ->
                    then((e as? ApiException)?.message ?: "The label could not be saved.")
                }
        }
    }

    fun deleteLabel(id: String, then: (String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: run { then("Sign in again."); return@launch }
            runCatching { Api.mailDeleteLabel(t, id) }
                .onSuccess { refreshLabels(); then("Label removed. Its messages keep their other labels.") }
                .onFailure { e -> then((e as? ApiException)?.message ?: "The label could not be removed.") }
        }
    }

    // ── screener ─────────────────────────────────────────────────────────────

    data class ScreenerState(
        val loaded: Boolean = false,
        val enabled: Boolean? = null,
        val pending: Int? = null,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _screener = MutableStateFlow(ScreenerState())
    val screener: StateFlow<ScreenerState> = _screener.asStateFlow()

    /**
     * The sentence a mail screen shows for a failed call.
     *
     * A 401 from the mail service is its own fact: that service does not accept
     * this credential. It does not follow that the person's session died — the
     * account endpoints accept it, which is why the interceptor is scoped by URL
     * — so "sign in again" would be a wrong diagnosis and silence would hide a
     * real one. Both get said instead.
     */
    private fun mailReason(e: Throwable): String = when {
        e is com.grapsee.grapmail.data.ApiException && e.status == 401 ->
            "The mail service did not accept this session. " +
                "You are still signed in to your account."
        else -> e.message ?: "Couldn't reach your mailbox."
    }

    // ── snapshot host only ───────────────────────────────────────────────────
    //
    // The mailbox's state is written by its loaders and nothing else. These four
    // exist so the test-only host can photograph a screen that needs content a
    // throwaway CI account cannot have: the sample values come from
    // MailSnapshotScreen, never from here, and no live path calls them. The
    // seeded shape is the real one, so what is photographed is the screen, not a
    // stand-in for it.
    fun seedForSnapshot(transform: (MailboxUiState) -> MailboxUiState) {
        _state.update { transform(it) }
    }

    fun seedThreadForSnapshot(
        messages: List<com.grapsee.grapmail.data.MailMessageFullDto>, forId: String,
    ) {
        threadLoadedFor = forId
        _thread.update { it.copy(messages = messages, loading = false, error = null) }
    }

    fun seedLabelsForSnapshot(labels: List<MailLabelDto>) {
        // Both stores. The labels screen reads `MailboxUiState.labels` and the
        // inbox's chip strip reads this one; filling only the second is how the
        // labels frame photographs "No labels yet" with the sample data set.
        _labels.update { it.copy(labels = labels, loading = false, error = null) }
        _state.update { it.copy(labels = labels) }
    }

    fun seedScreenerForSnapshot(enabled: Boolean, pending: Int) {
        _screener.update { it.copy(loaded = true, enabled = enabled, pending = pending, error = null) }
    }

    /**
     * Read the screener switch and its queue size.
     *
     * `enabled` stays null until the server answers. A switch drawn as "off"
     * because the read failed would tell the person their inbound mail is
     * unfiltered when nothing was said either way — the same rule the security
     * hub learned the hard way.
     */
    fun loadScreener() {
        viewModelScope.launch {
            val t = token() ?: return@launch
            runCatching { Api.mailScreener(t) }.onSuccess {
                _screener.update { s -> s.copy(loaded = true, enabled = it.enabled, error = it.error) }
            }
            // The queue is a box like any other, so its count comes from the
            // listing rather than a number the settings route never promised.
            runCatching { Api.mailInbox(t, MailBox.Screener, pageSize = 5) }.onSuccess { r ->
                _screener.update { it.copy(pending = r.total) }
            }
        }
    }

    fun setScreener(enabled: Boolean) {
        viewModelScope.launch {
            val t = token() ?: return@launch
            _screener.update { it.copy(busy = true) }
            runCatching { Api.mailScreenerSet(t, enabled) }
                .onSuccess { _screener.update { s -> s.copy(busy = false, enabled = it.enabled ?: enabled) } }
                .onFailure { e ->
                    _screener.update { s -> s.copy(busy = false,
                        error = e.message ?: "That change was not saved.") }
                }
        }
    }

    /**
     * Decide the senders behind the selected queue entries.
     *
     * Two things make this different from a message action, and the code follows
     * both. It writes a rule about a SENDER, not about the message, so several
     * rows from one person collapse to one decision — and the effect is on the
     * NEXT delivery, which means the message on screen does not move because the
     * server accepted the rule. So the queue is re-read rather than spliced: the
     * folder is the queue's only definition, and pretending a row left would show
     * mail the server still lists there.
     *
     * Nothing here claims to know a sender's current decision. The screener
     * surface is `PUT` and `DELETE /decision` with no read, so the sheet offers
     * the four choices and never marks one as already in force — an unknown read
     * is shown as unknown, the rule this whole sector is built on.
     */
    fun decideSenders(
        addresses: List<String>,
        scope: MailSenderScope,
        decision: MailDecision,
        then: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val t = token() ?: run { then("Sign in again to decide on this sender."); return@launch }
            val senders = addresses.asSequence()
                .map { it.trim() }.filter { it.isNotEmpty() }
                .map { if (scope == MailSenderScope.Domain) it.substringAfter('@', "") else it }
                .filter { it.isNotEmpty() }
                .distinct().toList()
            if (senders.isEmpty()) { then("Those messages name no sender to decide on."); return@launch }

            _screener.update { it.copy(busy = true, error = null) }
            var failure: String? = null
            var saved = 0
            for (s in senders) {
                runCatching { Api.mailScreenerDecision(t, s, scope, decision) }
                    .onSuccess { r ->
                        // `success` is the store's own word for the write landing.
                        // A 200 that did not say so is not counted as saved.
                        if (r.success) saved++ else failure = r.error ?: "The server refused that decision."
                    }
                    .onFailure { e -> failure = mailReason(e) }
            }
            MailStore.invalidate(MailBox.Screener)
            loadScreener()
            _screener.update { it.copy(busy = false, error = failure) }
            then(
                when {
                    failure != null && saved == 0 -> failure!!
                    failure != null -> "$saved of ${senders.size} saved. $failure"
                    // `done` is a word the enum carries, not a suffix appended
                    // here: "Approve" + "d" and "Keep waiting" + "d" are not both
                    // English, and a receipt that mis-spells what happened is the
                    // same defect as one that mis-states it.
                    saved == 1 -> "${decision.done}: ${senders[0]}"
                    else -> "${decision.done}: $saved senders"
                }
            )
        }
    }

    /**
     * Forget a decision so the sender is undecided again.
     *
     * `removed: false` arrives as a 200 and means the same thing to the user as
     * `removed: true` did — there is no rule for this sender now — so it is not
     * reported as a failure. Saying "forgotten" when the server said it had
     * nothing to forget would be a small lie with a real cost: the person would
     * think a rule was still in force somewhere.
     */
    fun undoDecision(address: String, scope: MailSenderScope, then: (String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: run { then("Sign in again to change this."); return@launch }
            val sender = if (scope == MailSenderScope.Domain)
                address.trim().substringAfter('@', "") else address.trim()
            if (sender.isEmpty()) { then("That message names no sender to change."); return@launch }
            _screener.update { it.copy(busy = true, error = null) }
            runCatching { Api.mailScreenerDecisionRemove(t, sender, scope) }
                .onSuccess { r ->
                    MailStore.invalidate(MailBox.Screener)
                    loadScreener()
                    _screener.update { it.copy(busy = false) }
                    then(if (r.removed == true) "Forgotten — their next message waits in the queue again."
                    else "No decision was recorded for them, so there was nothing to forget.")
                }
                .onFailure { e ->
                    _screener.update { it.copy(busy = false, error = mailReason(e)) }
                    then(mailReason(e))
                }
        }
    }

    // ── files ────────────────────────────────────────────────────────────────

    data class FileEntry(val attachment: MailAttachmentDto, val fromMessage: String?)

    /**
     * Attachments the app has actually seen. Partial by construction — see
     * [MailStore.allBodies] for why the screen has to say so.
     */
    suspend fun files(): List<FileEntry> =
        MailStore.allBodies().flatMap { m ->
            m.attachments.map { FileEntry(it, m.subject?.takeIf { s -> s.isNotBlank() }) }
        }

    /**
     * Take one attachment out of the message for good.
     *
     * `DELETE /attachments/:id` is scoped to the caller's own rows
     * (`grapmail-attachments.ts:412`), and the answer is the route's, not the
     * app's guess. The body cache is dropped on success because the row the app
     * is holding still lists a file that no longer exists — leaving it would let
     * the Files screen offer a download of bytes the server has thrown away.
     */
    fun deleteAttachment(id: String, then: (String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: run { then("Sign in again to remove this."); return@launch }
            runCatching { Api.mailDeleteAttachment(t, id) }
                .onSuccess { r ->
                    if (r.success) {
                        MailStore.forgetAttachment(id)
                        MailStore.invalidateAllBoxes()
                        then("Removed.")
                    } else {
                        then(r.error ?: "The server refused to remove that file.")
                    }
                }
                .onFailure { e ->
                    then(mailReason(e))
                }
        }
    }

    // ── aliases ──────────────────────────────────────────────────────────────
    //
    // Create and delete moved to `ProfileViewModel.aliasWrite` with the Addresses
    // screen: the minted address is only knowable from the server's answer, the
    // alias list rides the profile document beside every other address claim, and
    // a second holder of the same record is a second chance to disagree about it.
    // The READ stays here because the compose sender sheet needs the aliases
    // before any account screen exists.

    /**
     * The aliases, with the state of the read that produced them.
     *
     * `isActive` and `id` are not in the route's answer, so the row type has no
     * such fields: a cached list that defaulted `isActive` to true drew a mailbox
     * whose aliases were all asserted live and gave every row the same empty
     * identity, which is the shape of a duplicate-key crash rather than a missing
     * feature. A failed read is `Failed`, not an empty list — "this mailbox has no
     * aliases" is a statement about the account, and the screen may only make it
     * when the server made it.
     */
    data class AliasesState(
        val read: AssignmentsRead = AssignmentsRead.NotRead,
        val list: List<com.grapsee.grapmail.data.MailAliasDto> = emptyList(),
        val error: String? = null,
    )

    private val _aliases = MutableStateFlow(AliasesState())
    val aliasesState: StateFlow<AliasesState> = _aliases.asStateFlow()

    fun loadAliases(force: Boolean = false) {
        when (_aliases.value.read) {
            AssignmentsRead.Reading -> return
            AssignmentsRead.Read -> if (!force) return
            else -> Unit
        }
        viewModelScope.launch {
            val t = token() ?: run {
                _aliases.update { it.copy(read = AssignmentsRead.Failed, error = "Sign in again.") }
                return@launch
            }
            _aliases.update { it.copy(read = AssignmentsRead.Reading) }
            runCatching { Api.mailAliases(t) }
                .onSuccess { r ->
                    MailStore.putAliases(r.aliases)
                    _aliases.update {
                        it.copy(read = if (r.success) AssignmentsRead.Read else AssignmentsRead.Failed,
                            list = r.aliases,
                            error = if (r.success) null else "The alias list did not load.")
                    }
                }
                .onFailure { e ->
                    val cached = MailStore.aliases()
                    _aliases.update {
                        it.copy(
                            read = if (cached != null) AssignmentsRead.Read else AssignmentsRead.Failed,
                            list = cached ?: emptyList(),
                            error = mailReason(e),
                        )
                    }
                }
        }
    }

    // ── end-to-end encryption ───────────────────────────────────────────────

    /**
     * The key directory's state, plus the last verification a screen asked for.
     *
     * Nothing here claims a message was opened. The device publishes the PUBLIC
     * halves the platform can make (`MailE2e.generateIdentity`) and asks the
     * server what it
     * knows about other people's keys (`/plan`, `/lookup`); the sealed body itself
     * is left exactly as the server stored it, because a client that showed a
     * half-implemented decrypt would be inventing plaintext.
     */
    data class E2eState(
        val read: AssignmentsRead = AssignmentsRead.NotRead,
        val keys: com.grapsee.grapmail.data.MailE2eKeysDto? = null,
        val discoverable: Boolean? = null,
        val busy: Boolean = false,
        val note: String? = null,
        val error: String? = null,
        val plan: com.grapsee.grapmail.data.MailE2ePlanDto? = null,
        val lookup: List<com.grapsee.grapmail.data.MailE2eLookupEntryDto> = emptyList(),
        val lookupFor: List<String> = emptyList(),
    )

    private val _e2e = MutableStateFlow(E2eState())
    val e2e: StateFlow<E2eState> = _e2e.asStateFlow()

    fun loadE2eKeys(force: Boolean = false) {
        when (_e2e.value.read) {
            AssignmentsRead.Reading -> return
            AssignmentsRead.Read -> if (!force) return
            else -> Unit
        }
        viewModelScope.launch {
            val t = token() ?: run { _e2e.update { it.copy(read = AssignmentsRead.Failed) }; return@launch }
            _e2e.update { it.copy(read = AssignmentsRead.Reading) }
            runCatching { Api.mailE2eKeys(t) }
                .onSuccess { r ->
                    _e2e.update {
                        it.copy(
                            read = if (r.success) AssignmentsRead.Read else AssignmentsRead.Failed,
                            keys = r.keys,
                            discoverable = r.keys?.let { k -> k.encryption != null },
                            error = if (r.success) null else (r.error ?: "The key directory did not answer."),
                        )
                    }
                }
                .onFailure { e ->
                    _e2e.update { it.copy(read = AssignmentsRead.Failed, error = mailReason(e)) }
                }
        }
    }

    /**
     * Generate this device's keypair and publish the public halves.
     *
     * The private halves never leave: the store rejects any body carrying private
     * material with 400 (`mail-service/src/routes/e2e.ts:209`), and this client has
     * no field to put one in. When the platform cannot make an X25519 key the call
     * says which Android version introduced it rather than publishing half a
     * identity or faking one.
     */
    fun publishKeys(passphrase: String?, then: (String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: run { then("Sign in again to set up encryption."); return@launch }
            _e2e.update { it.copy(busy = true, note = null, error = null) }
            // generateIdentity is the module's own entry: it makes both pairs,
            // writes the passphrase-protected record BEFORE the network ask, and
            // throws with the device's or the passphrase's reason when either
            // refuses. runCatching keeps that reason as the answer; a publish
            // that then fails leaves a usable identity the screen can retry.
            val made = runCatching {
                com.grapsee.grapmail.data.MailE2e.generateIdentity(getApplication(), t, passphrase ?: "")
            }
            if (made.isFailure) {
                val why = made.exceptionOrNull()?.message ?: "This device could not create the keys."
                _e2e.update { it.copy(busy = false, error = why) }
                then(why)
                return@launch
            }
            val identity = made.getOrNull()!!
            runCatching {
                Api.mailE2ePublish(t, identity.encryption.publicKey, identity.signing?.publicKey)
            }
                .onSuccess { r ->
                    if (r.success) {
                        _e2e.update { it.copy(busy = false, keys = r.keys, read = AssignmentsRead.Read) }
                        then(if (r.discoverable == false)
                            "Published — but only people you name can use it." else "Published.")
                    } else {
                        val why = r.error ?: "The directory refused those keys."
                        _e2e.update { it.copy(busy = false, error = why) }
                        then(why)
                    }
                }
                .onFailure { e ->
                    val why = mailReason(e)
                    _e2e.update { it.copy(busy = false, error = why) }
                    then(why)
                }
        }
    }

    fun unpublishKeys(then: (String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: run { then("Sign in again to change this."); return@launch }
            _e2e.update { it.copy(busy = true, error = null) }
            runCatching { Api.mailE2eUnpublish(t) }
                .onSuccess { r ->
                    _e2e.update {
                        it.copy(busy = false, read = AssignmentsRead.Read,
                            keys = if (r.removed == true) null else it.keys,
                            discoverable = if (r.removed == true) false else it.discoverable)
                    }
                    then(if (r.removed == true) r.detail ?: "Keys removed."
                    else r.detail ?: "No published key was found to remove.")
                }
                .onFailure { e ->
                    _e2e.update { it.copy(busy = false, error = mailReason(e)) }
                    then(mailReason(e))
                }
        }
    }

    /**
     * Ask the server whether a message to THESE people could be sealed.
     *
     * Addresses go in the body, never the URL (`grapmail-e2e.ts:194`), so a
     * recipient's address does not end up in an access log. The answer's `mode` is
     * what the compose screen shows: `seal` means every address has a key,
     * `mixed`/`plain` name the ones that do not, and the screen repeats the
     * server's reason for each rather than inventing one.
     */
    fun planEncryption(addresses: List<String>, then: (String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: run { then("Sign in again to check this."); return@launch }
            _e2e.update { it.copy(busy = true, plan = null) }
            runCatching { Api.mailE2ePlan(t, addresses) }
                .onSuccess { r ->
                    _e2e.update { it.copy(busy = false, plan = r.plan, error = if (r.success) null else r.error) }
                    then(r.plan?.let { p ->
                        if (p.canSeal) "Every recipient has a published key."
                        else "${p.blocked.size} of ${addresses.size} cannot be sealed."
                    } ?: r.error ?: "The server did not answer.")
                }
                .onFailure { e ->
                    _e2e.update { it.copy(busy = false, error = mailReason(e)) }
                    then(mailReason(e))
                }
        }
    }

    /**
     * The compose-time plan, asked synchronously by the send path: mode and
     * the keys to seal to. Null when the directory did not answer — which the
     * send treats as "not encrypted", never as "encrypt anyway": the
     * difference between "the server said this cannot be encrypted" and "we
     * could not ask" decides whether the message goes plain or not at all.
     */
    suspend fun encryptionPlan(addresses: List<String>): Pair<String, List<String>>? {
        val t = token() ?: return null
        return runCatching { Api.mailE2ePlan(t, addresses) }.getOrNull()
            ?.plan?.let { it.mode to it.keys }
    }

    /** Who has a key, and what their fingerprint is — the verification read. */
    fun lookupContacts(addresses: List<String>, then: (String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: run { then("Sign in again to look this up."); return@launch }
            _e2e.update { it.copy(busy = true) }
            runCatching { Api.mailE2eLookup(t, addresses) }
                .onSuccess { r ->
                    _e2e.update {
                        it.copy(busy = false, lookup = r.results, lookupFor = addresses,
                            error = if (r.success) null else r.error)
                    }
                    then(if (r.success) "${r.results.size} addressed checked."
                    else r.error ?: "The directory did not answer.")
                }
                .onFailure { e ->
                    _e2e.update { it.copy(busy = false, error = mailReason(e)) }
                    then(mailReason(e))
                }
        }
    }
}

