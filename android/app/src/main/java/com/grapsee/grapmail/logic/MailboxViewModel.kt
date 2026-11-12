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
import com.grapsee.grapmail.data.MailScheduledDto
import com.grapsee.grapmail.data.MailSenderScope
import com.grapsee.grapmail.data.MailSort
import com.grapsee.grapmail.data.MailStore
import com.grapsee.grapmail.data.inverse
import com.grapsee.grapmail.data.pastLine
import com.grapsee.grapmail.data.SessionStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
        val actionError: String? = null,
        /** A load that failed with nothing to show. This is the error screen. */
        val fatal: String? = null,
        /** True when the server said this account has no mailbox at all. */
        val noMailbox: Boolean = false,
        /**
         * Whether this device has a path to the network, as the system last
         * said. `null` means no reading has landed yet — which draws no banner,
         * because "offline" before the callback has spoken is a claim about a
         * network nobody checked.
         *
         * The stale-list chip can only report a refresh that was refused. This
         * says why, while the list is still on screen, so a list that has not
         * moved for ten minutes is not mistaken for a quiet inbox. iOS reads
         * the same fact through `NWPathMonitor` (`AppIntegration.swift`).
         */
        val online: Boolean? = null,
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
        val appendError: String? = null,
        val endOfPages: Boolean = false,
    ) {
        val selecting: Boolean get() = selection.isNotEmpty()
        val hasRows: Boolean get() = rows.isNotEmpty()
        val canLoadMore: Boolean get() = !loading && !loadingMore && !endOfPages && loaded < total
        /** How many rows the chosen sort actually re-ordered. */
        val sortScope: Int get() = loaded
    }

    private val _state = MutableStateFlow(MailboxUiState())
    val state: StateFlow<MailboxUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var projectedTerm: String? = null
    private val searchTerm: String?
        get() = _state.value.query.trim().takeIf { it.length >= 3 }
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
    private var readerJob: Job? = null
    private var readerTarget: Pair<String, Boolean>? = null

    private fun loadReader(id: String, isThread: Boolean, read: suspend () -> Unit) {
        val target = id to isThread
        if (readerTarget == target && (threadLoadedFor == id || readerJob?.isActive == true)) return
        readerJob?.cancel()
        readerTarget = target
        threadLoadedFor = null
        _thread.value = ThreadState(loading = true)
        readerJob = viewModelScope.launch { read() }
    }

    /**
     * The stored bearer, or null when there is none. Every loader treats null as
     * "sign in again" rather than calling the API anonymously: an unauthenticated
     * mail request answers 401, which the interceptor would read as a dead
     * session and act on, and a screen that was never signed in would be kicked
     * out of one.
     */
    private suspend fun token(): String? =
        SessionStore.token(getApplication())

    /**
     * The device's own connectivity, watched rather than inferred.
     *
     * A failed refresh already says the list may be stale. What it cannot say
     * is *why*, and the difference matters to a person looking at a list that
     * has not moved: the network went away, or the server is refusing. So the
     * system is asked directly, once, at the same time the mailbox starts, and
     * every change afterwards arrives through the callback.
     *
     * `registerDefaultNetworkCallback` is the API for exactly this question —
     * it tracks the network the device would actually use, rather than any one
     * transport. The first reading comes from the current network rather than
     * waiting for a change that may never happen: a mailbox opened on a device
     * that has been offline for an hour should say so on its first frame, not
     * after the next transition.
     */
    private fun watchNetwork() {
        val manager = getApplication<android.app.Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return
        val active = manager.activeNetwork
            ?.let { manager.getNetworkCapabilities(it) }
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        _state.update { it.copy(online = active) }
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                _state.update { it.copy(online = true) }
            }

            override fun onLost(network: android.net.Network) {
                // The default callback reports the network the device would use
                // now. A loss that leaves another one standing (wifi off, cell
                // on) is answered by the capabilities of what is active, not by
                // a flat "offline" — saying offline while mail is still flowing
                // is worse than saying nothing.
                val still = manager.activeNetwork
                    ?.let { manager.getNetworkCapabilities(it) }
                    ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                _state.update { it.copy(online = still == true) }
            }

            override fun onCapabilitiesChanged(
                network: android.net.Network,
                capabilities: android.net.NetworkCapabilities,
            ) {
                _state.update {
                    it.copy(online = capabilities
                        .hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET))
                }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCleared() {
        // The callback outlives no view model: a leaked registration keeps a
        // mailbox's state object reachable after the screen that owned it is
        // gone, and every later connectivity change would write into it.
        val manager = getApplication<android.app.Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
        (networkCallback?.let { cb -> manager?.runCatching { unregisterNetworkCallback(cb) } })
        networkCallback = null
        super.onCleared()
    }

    init {
        watchNetwork()
    }

    private var accountLoadJob: Job? = null

    fun loadAccount() {
        if (snapshotMode || accountLoadJob?.isActive == true) return
        accountLoadJob = viewModelScope.launch {
            val context = getApplication<Application>()
            val current = com.grapsee.grapmail.data.SessionStore.snapshot(context) ?: return@launch
            val cached = MailStore.me()
            if (cached != null && cached.address.equals(current.address, ignoreCase = true)) {
                applyAccount(cached)
                return@launch
            }
            val me = try {
                Api.mailMe(current.token)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            val active = com.grapsee.grapmail.data.SessionStore.snapshot(context)
            if (active?.id != current.id) return@launch
            MailStore.putMe(me)
            applyAccount(me)
        }
    }

    private fun applyAccount(me: com.grapsee.grapmail.data.MailMeResponse) {
        _state.update { it.copy(
            accountName = me.name,
            accountAddress = me.address,
            accountStatus = me.status,
            memberSince = me.memberSince,
        ) }
    }

    // ── list ─────────────────────────────────────────────────────────────────

    fun open(box: MailBox) {
        if (_state.value.box == box) return
        searchJob?.cancel()
        loadedBoxes.add(box)
        _state.update { it.copy(box = box, selection = emptySet(), rows = emptyList(),
            pages = 0, loaded = 0, total = 0, advScope = 0, loading = true,
            loadingMore = false, noMailbox = false, fatal = null, staleError = null, actionError = null,
            appendError = null, endOfPages = false) }
        load(replace = true, reuseRecentFolder = true, forceCounts = false)
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

    private var lastFirstPageAttempt: Long? = null

    fun refreshOnResume(atTop: Boolean) {
        if (snapshotMode || !canRefreshInBackground(atTop)) return
        val lastAttempt = lastFirstPageAttempt ?: return
        if (android.os.SystemClock.elapsedRealtime() - lastAttempt >= 60_000) {
            load(forceCounts = false)
        }
    }

    suspend fun acceptPoll(result: MailPollWorker.PollResult, atTop: Boolean) {
        if (snapshotMode || !SessionStore.isCurrent(getApplication(), result.sessionId)) return
        val previous = _state.value
        val inbox = result.stats.inbox
        val changed = inbox?.unread?.let { it != previous.unreadCount } == true ||
            (previous.box == MailBox.Inbox && inbox?.total?.let { it != previous.total } == true)
        MailStore.putStats(result.stats)
        countsLoadedAt = android.os.SystemClock.elapsedRealtime()
        _state.update { it.withFolderCounts(result.stats) }
        if (changed && canRefreshInBackground(atTop) && _state.value.box == MailBox.Inbox) {
            load(forceCounts = false)
        }
    }

    private fun canRefreshInBackground(atTop: Boolean): Boolean {
        val current = _state.value
        return atTop && loadJob?.isActive != true && searchJob?.isActive != true &&
            searchTerm == null && current.online != false && current.pages <= 1 && !current.selecting
    }

    /**
     * Typing. Debounced, and a term shorter than 3 characters never leaves the
     * device: a one-character `q` scans the whole mailbox server-side
     * (`grapmail.ts:688`), so every keystroke would be a full-table read.
     */
    fun query(text: String) {
        val previous = _state.value.query
        if (previous == text) return
        _state.update { it.copy(query = text) }
        if (previous.trim().takeIf { it.length >= 3 } == text.trim().takeIf { it.length >= 3 }) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            load(replace = true)
        }
    }

    fun setSortAndFilter(sort: MailSort, filter: MailFilter) {
        val current = _state.value
        if (current.sort == sort && current.filter == filter) return
        _state.update { it.copy(sort = sort, filter = filter) }
        viewModelScope.launch { reproject() }
    }

    /**
     * Load a page. `replace` restarts the box (refresh, new search, new box);
     * otherwise the page is appended, which is what makes scrolling cheap.
     */
    fun load(replace: Boolean = true, reuseRecentFolder: Boolean = false, forceCounts: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val box = _state.value.box
            val term = searchTerm
            val page = if (replace) 1 else _state.value.pages + 1

            // Show the spinner only when there is nothing to show. A refresh over
            // an existing list must not blank it — the hub's defect, designed out
            // here rather than rediscovered.
            if (replace && !_state.value.hasRows) _state.update { it.copy(loading = true, staleError = null) }
            if (!replace) _state.update { it.copy(loadingMore = true, appendError = null) }

            val context = getApplication<Application>()
            val session = com.grapsee.grapmail.data.SessionStore.snapshot(context)
            if (session == null) {
                // The generation moves here too. This is an exit from a load, and the
                // refresh gesture waits on precisely that signal; leaving it out
                // parks an indeterminate spinner over the list for the rest of the
                // screen's life the moment a session has expired.
                _state.update { it.copy(loading = false, loadingMore = false,
                    fatal = "Sign in again.", settleGeneration = it.settleGeneration + 1) }
                return@launch
            }

            // Not `runCatching`: it swallows `CancellationException` like any other
            // Throwable. A box switch or a new search cancels the previous read,
            // which should leave error reporting to the request that replaced it.
            try {
                if (replace && !_state.value.hasRows) {
                    val cached = MailStore.page(box, term)
                    if (cached != null && term == searchTerm) {
                        reproject()
                        if (term != searchTerm) return@launch
                        val age = System.currentTimeMillis() - cached.loadedAtMillis
                        if (reuseRecentFolder && term == null && age in 0L..15_000L) {
                            _state.update { it.copy(loading = false,
                                settleGeneration = it.settleGeneration + 1) }
                            refreshCounts(session, force = false)
                            return@launch
                        }
                        _state.update { it.copy(loading = false) }
                    }
                }
                if (term != searchTerm) return@launch
                if (replace && term == null) lastFirstPageAttempt = android.os.SystemClock.elapsedRealtime()
                val resp = Api.mailInbox(session.token, box, page = page, q = term)
                if (term != searchTerm) return@launch
                val addedRows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    if (!SessionStore.isCurrent(context, session.id)) return@withContext null
                    MailStore.putPage(box, term, resp, replace)
                } ?: return@launch
                if (term != searchTerm) return@launch
                reproject()
                if (term != searchTerm) return@launch
                _state.update { it.copy(loading = false, loadingMore = false, fatal = null,
                    staleError = null, appendError = null, endOfPages = !addedRows,
                    settleGeneration = it.settleGeneration + 1) }
                if (replace && term == null) refreshCounts(session, force = forceCounts)
                if (replace) prefetchVisibleBodies()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e            // superseded: the read that replaced this one reports
            } catch (e: Exception) {
                if (term != searchTerm) return@launch
                val noBox = (e as? ApiException)?.code == "NO_MAILBOX"
                val msg = mailReason(e)
                if (!replace && !noBox) {
                    _state.update { it.copy(loadingMore = false, appendError = msg,
                        settleGeneration = it.settleGeneration + 1) }
                    return@launch
                }
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

    private var countsJob: Job? = null
    private var countsLoadedAt: Long? = null

    private fun refreshCounts(session: com.grapsee.grapmail.data.SessionSnapshot, force: Boolean) {
        if (countsJob?.isActive == true) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && countsLoadedAt?.let { now - it < 30_000L } == true) return
        countsJob = viewModelScope.launch {
            val stats = try {
                Api.mailStats(session.token)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            if (!SessionStore.isCurrent(getApplication(), session.id)) return@launch
            MailStore.putStats(stats)
            countsLoadedAt = android.os.SystemClock.elapsedRealtime()
            _state.update { it.withFolderCounts(stats) }
        }
    }

    fun loadMore() {
        if (searchJob?.isActive == true || loadJob?.isActive == true ||
            projectedTerm != searchTerm || !_state.value.canLoadMore
        ) return
        load(replace = false)
    }

    /**
     * Re-derive the visible rows from what is loaded. Never a network call — this
     * runs on filter and sort changes, which are local operations.
     */
    private var projectionGeneration = 0L

    private suspend fun reproject() {
        val generation = ++projectionGeneration
        val s = _state.value
        val term = searchTerm
        val cache = MailStore.page(s.box, term)
        val total = cache?.total ?: s.total
        val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            var rows = cache?.messages ?: s.rows

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

            when (s.sort) {
                // The server's own order: covers the whole box, not just the pages.
                MailSort.Newest -> rows
                MailSort.Oldest -> rows.reversed()
                MailSort.SenderAsc -> rows.sortedBy { it.from?.display.orEmpty().lowercase() }
                MailSort.SenderDesc -> rows.sortedByDescending { it.from?.display.orEmpty().lowercase() }
                MailSort.SubjectAsc -> rows.sortedBy { (it.subject ?: "").lowercase() }
                MailSort.SubjectDesc -> rows.sortedByDescending { (it.subject ?: "").lowercase() }
            }
        }

        val stats = MailStore.stats()
        val current = _state.value
        if (generation != projectionGeneration || current.box != s.box || searchTerm != term ||
            current.filter != s.filter || current.sort != s.sort
        ) return
        if (cache != null) projectedTerm = term
        _state.update {
            it.copy(
                rows = rows,
                total = total,
                loaded = cache?.messages?.size ?: it.loaded,
                pages = cache?.pages ?: it.pages,
                advScope = cache?.messages?.size ?: it.loaded,
            ).withFolderCounts(stats)
        }
    }

    private fun MailboxUiState.withFolderCounts(stats: com.grapsee.grapmail.data.MailStatsResponse?) =
        copy(
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
        )

    // ── selection ────────────────────────────────────────────────────────────

    fun toggleSelect(id: String) = _state.update {
        val next = it.selection.toMutableSet()
        if (!next.add(id)) next.remove(id)
        it.copy(selection = next)
    }

    fun dismissActionError() = _state.update { it.copy(actionError = null) }

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
        if (ids.isEmpty() || _state.value.busy || pendingRowActions.isNotEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val t = token() ?: run {
                _state.update { it.copy(busy = false, actionError = "Sign in again.") }
                return@launch
            }
            // One request per message, run serially, made a selection of twenty
            // pay twenty round trips with the toolbar locked the whole time. The
            // writes are independent, so they run four at a time — same
            // per-message answers, same per-success local applies, a selection
            // that settles in a fraction of the wall clock. Four, not twenty,
            // because the server rate-limits writes and a burst that trips the
            // limit would fail the very actions the parallelism was meant to
            // speed up. `viewModelScope` is Main-confined, so the callbacks that
            // mutate `done` and `failure` stay single-threaded — only the
            // requests themselves wait in parallel.
            val writePermits = Semaphore(4)
            var failure: String? = null
            val done = mutableListOf<String>()
            // Whether one accepted write removes that row from the screen: filing
            // takes the message out of every box's view, and a restore-style
            // action takes it out of the box it is being seen in. Rows leave as
            // their own write lands, not when the whole selection has settled.
            val source = when (action) {
                MailAction.Unstar -> MailBox.Starred
                MailAction.Restore -> MailBox.Trash
                MailAction.Unarchive -> MailBox.Archive
                MailAction.Unspam -> MailBox.Spam
                MailAction.Unsnooze -> MailBox.Snoozed
                else -> null
            }
            val removesRow = action == MailAction.Trash || action == MailAction.Archive ||
                action == MailAction.Spam || action == MailAction.Snooze || source == _state.value.box
            coroutineScope {
                ids.map { id ->
                    async {
                        writePermits.withPermit {
                            try {
                                Api.mailAction(t, id, action, until)
                                done.add(id)
                                applyLocally(id, action)
                                _state.update { s ->
                                    if (removesRow) s.copy(
                                        selection = s.selection - id,
                                        rows = s.rows.filterNot { it.id == id },
                                    ) else s.copy(selection = s.selection - id)
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failure = failure ?: (e.message ?: "That action didn't go through.")
                            }
                        }
                    }
                }.awaitAll()
            }
            if (done.isNotEmpty()) invalidateFor(action)
            _state.update { it.copy(busy = false, selection = it.selection - done, actionError = failure) }
            // Same rule as the undo below, and the same rule iOS's `act`
            // learned: the tap belongs to the write. A partial failure is a
            // failure — some mail did not move, and a success buzz would say it
            // all did.
            if (failure == null) {
                when (action) {
                    MailAction.Star, MailAction.Unstar,
                    MailAction.Read, MailAction.Unread -> GMHaptics.marking()
                    else -> GMHaptics.filing()
                }
            } else GMHaptics.failure()
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
            if (done.isEmpty()) return@launch
            when (action) {
                MailAction.Read, MailAction.Unread -> reproject()
                MailAction.Star, MailAction.Unstar -> {
                    if (_state.value.box == MailBox.Starred) {
                        if (action == MailAction.Unstar) {
                            val removed = done.toHashSet()
                            _state.update { it.copy(rows = it.rows.filterNot { row -> row.id in removed }) }
                        }
                        load(forceCounts = false)
                    } else reproject()
                }
                else -> load(replace = true)
            }
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
        if (ids.isEmpty() || _state.value.busy || pendingRowActions.isNotEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val t = token() ?: run {
                _state.update { it.copy(busy = false, actionError = "Sign in again.") }
                return@launch
            }
            var failure: String? = null
            val restored = mutableListOf<String>()
            for (id in ids) {
                runCatching { Api.mailAction(t, id, MailAction.Unsnooze, null) }
                    .onSuccess { restored.add(id) }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        failure = it.message ?: "That action didn't go through."
                    }
            }
            if (restored.isNotEmpty()) invalidateFor(MailAction.Unsnooze)
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
                    actionError = failure,
                )
            }
            if (restored.isNotEmpty()) load(replace = true)
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
        if (ids.isEmpty() || inv == null || _state.value.busy || pendingRowActions.isNotEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val t = token() ?: run {
                _state.update { it.copy(busy = false, actionError = "Sign in again.") }
                return@launch
            }
            var failure: String? = null
            val redone = mutableListOf<String>()
            for (id in ids) {
                runCatching { Api.mailAction(t, id, inv, null) }
                    .onSuccess { redone.add(id); applyLocally(id, inv) }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        failure = it.message ?: "That action didn't go through."
                    }
            }
            if (redone.isNotEmpty()) invalidateFor(inv)
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
                    actionError = failure,
                )
            }
            if (redone.isNotEmpty()) load(replace = true)
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

    private val pendingRowActions = mutableSetOf<String>()

    fun actOn(id: String, action: MailAction) {
        if (_state.value.busy) return
        viewModelScope.launch {
            val t = token() ?: return@launch
            if (_state.value.busy || !pendingRowActions.add(id)) return@launch
            try {
                // ── Optimistic first paint ──────────────────────────────────────
                // The row answers the gesture before the network does: the flag
                // flips in place, or the filed row leaves the screen, in the same
                // frame as the swipe. The server's answer is still the truth —
                // a refused write re-reads the box below, the row comes back,
                // and the error names what did not go through. Nothing here
                // claims a delivery: the undo banner is raised only after the
                // server accepted, the same rule the bulk path keeps.
                applyLocally(id, action)
                val source = when (action) {
                    MailAction.Unstar -> MailBox.Starred
                    MailAction.Restore -> MailBox.Trash
                    MailAction.Unarchive -> MailBox.Archive
                    MailAction.Unspam -> MailBox.Spam
                    MailAction.Unsnooze -> MailBox.Snoozed
                    else -> null
                }
                val box = _state.value.box
                val removesRow = action == MailAction.Trash || action == MailAction.Archive ||
                    action == MailAction.Spam || action == MailAction.Snooze || source == box
                if (removesRow) {
                    _state.update { s -> s.copy(rows = s.rows.filterNot { it.id == id }) }
                } else {
                    val read = when (action) {
                        MailAction.Read -> true
                        MailAction.Unread -> false
                        else -> null
                    }
                    val starred = when (action) {
                        MailAction.Star -> true
                        MailAction.Unstar -> false
                        else -> null
                    }
                    if (read != null || starred != null) {
                        _state.update { s -> s.copy(rows = s.rows.map {
                            if (it.id == id) it.copy(
                                isRead = read ?: it.isRead,
                                isStarred = starred ?: it.isStarred,
                            ) else it
                        }) }
                    }
                }
                // The swipe is the one filing the person cannot see land (the row
                // is already off screen), so the tap is the feedback — it fires
                // with the movement now, and a refusal corrects it below.
                when (action) {
                    MailAction.Star, MailAction.Unstar,
                    MailAction.Read, MailAction.Unread -> GMHaptics.marking()
                    else -> GMHaptics.filing()
                }
                try {
                    Api.mailAction(t, id, action, null)
                    invalidateFor(action)
                    com.grapsee.grapmail.data.SessionStore.snapshot(getApplication())
                        ?.let { refreshCounts(it, force = false) }
                    // A single-message action supersedes the undo offer for THAT
                    // message. Without this, snooze three, swipe one of them to
                    // Trash, press Undo, and the trashed message is
                    // unsnoozed too -- `act()` clears the offer for bulk actions but this path had no
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
                    // would have no path back. Raised here, after the server
                    // accepted — never on the optimistic frame.
                    val inv = action.inverse()
                    _state.update { s ->
                        if (inv == null) s else s.copy(
                            undoIds = listOf(id), undoInverse = inv,
                            undoNote = action.pastLine(1),
                        )
                    }
                    // Reconcile the bookkeeping the optimistic paint could not
                    // know: the box's totals and the neighbouring pages. First
                    // paint comes from the cache, so this lands behind the row
                    // the person already saw move.
                    val sourceAfter = when (action) {
                        MailAction.Unstar -> MailBox.Starred
                        MailAction.Restore -> MailBox.Trash
                        MailAction.Unarchive -> MailBox.Archive
                        MailAction.Unspam -> MailBox.Spam
                        MailAction.Unsnooze -> MailBox.Snoozed
                        else -> null
                    }
                    if (sourceAfter == _state.value.box) {
                        load(forceCounts = false)
                    } else if (action == MailAction.Restore || action == MailAction.Unarchive ||
                        action == MailAction.Unspam || action == MailAction.Unsnooze
                    ) {
                        load(forceCounts = false)
                    } else {
                        reproject()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    GMHaptics.failure()
                    // The write was refused, so the optimistic move is not the
                    // truth. Re-read the box — the row returns exactly where
                    // the server says it is — and say what failed.
                    loadedBoxes.remove(_state.value.box)
                    MailStore.invalidate(_state.value.box)
                    load(forceCounts = false)
                    _state.update { s ->
                        s.copy(actionError = (e as? ApiException)?.message
                            ?: "That action didn't go through.")
                    }
                }
            } finally {
                pendingRowActions.remove(id)
            }
        }
    }

    private suspend fun applyLocally(id: String, action: MailAction) {
        val read = when (action) {
            MailAction.Read -> true
            MailAction.Unread -> false
            else -> null
        }
        val starred = when (action) {
            MailAction.Star -> true
            MailAction.Unstar -> false
            else -> null
        }
        if (read != null || starred != null) {
            MailStore.patchFlags(id, read = read, starred = starred)
            _thread.update { state ->
                val index = state.messages.indexOfFirst { it.id == id }
                if (index < 0) state else {
                    val messages = state.messages.toMutableList()
                    val message = messages[index]
                    messages[index] = message.copy(
                        isRead = read ?: message.isRead,
                        isStarred = starred ?: message.isStarred,
                    )
                    state.copy(messages = messages)
                }
            }
        } else {
            when (action) {
                MailAction.Trash, MailAction.Archive, MailAction.Spam, MailAction.Snooze ->
                    MailStore.removeEverywhere(id)
                else -> Unit
            }
        }
    }

    private suspend fun invalidateFor(action: MailAction) {
        when (action) {
            MailAction.Star, MailAction.Unstar -> {
                loadedBoxes.remove(MailBox.Starred)
                MailStore.invalidate(MailBox.Starred)
            }
            MailAction.Trash -> MailStore.invalidate(MailBox.Trash)
            MailAction.Archive -> MailStore.invalidate(MailBox.Archive)
            MailAction.Spam -> MailStore.invalidate(MailBox.Spam)
            MailAction.Restore, MailAction.Unarchive, MailAction.Unspam ->
                MailStore.invalidateAllBoxes()
            // A snooze moves the message out of the inbox's view and into the
            // snoozed box's (`grapmail-boxes.ts:178` lists it over every folder),
            // so both listings are wrong the moment the action succeeds and
            // neither may be shown from the cache it just invalidated.
            MailAction.Snooze -> MailStore.invalidate(MailBox.Snoozed)
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
        val current = _state.value
        val ids = current.selection.toList()
        if (current.box != MailBox.Trash || ids.isEmpty() || current.busy || pendingRowActions.isNotEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val t = token() ?: run {
                _state.update { it.copy(busy = false, actionError = "Sign in again.") }
                return@launch
            }
            var failure: String? = null
            val removed = mutableSetOf<String>()
            for (id in ids) {
                runCatching { Api.mailDeletePermanent(t, id) }
                    .onSuccess {
                        removed.add(id)
                        MailStore.removeEverywhere(id)
                        MailStore.forgetBody(id)
                        // The message is gone, so its label pairs are gone with it.
                        // Left in the map they would be resurrected if the same id
                        // ever appeared again, showing a tick for mail that does not
                        // exist — and the next read would say nothing about it.
                        MailStore.forgetAssignments(id)
                    }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        failure = it.message ?: "That message could not be deleted."
                    }
            }
            _state.update {
                it.copy(busy = false, selection = it.selection - removed, actionError = failure,
                    rows = it.rows.filterNot { row -> row.id in removed })
            }
            if (removed.isNotEmpty()) load(replace = true)
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
        if (_state.value.labelsRead != AssignmentsRead.Read && !_labels.value.loading) refreshLabels()
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
         *  deletes it — see [send]. The autosave loop keeps it current: the
         *  first autosave records the id its insert produced, and every later
         *  one updates that row. */
        val draftId: String = "",
        val sending: Boolean = false,
        /** The undo-send window: the message is held on-device for five
         *  seconds after Send, with Undo offered the whole time. Nothing is
         *  on the wire and nothing claims sent during it. */
        val pendingSend: Boolean = false,
        /** The server's own verdict, shown verbatim. Never a local "Sent". */
        val result: String? = null,
        val resultStatus: String? = null,
        val error: String? = null,
        /** The autosave's own quiet state, deliberately separate from
         *  [result]: a background save must never read like a send receipt,
         *  and the composer draws it as one small tertiary line. */
        val autosaving: Boolean = false,
        val autosaveNote: String? = null,
    ) {
        val accepted: Boolean get() = resultStatus == "sent" || resultStatus == "queued" || resultStatus == "delivered"
    }

    private val _compose = MutableStateFlow(ComposeState())
    val compose: StateFlow<ComposeState> = _compose.asStateFlow()

    fun composeUpdate(transform: (ComposeState) -> ComposeState) {
        _compose.update(transform)
        scheduleAutosave()
    }

    // ── autosave ─────────────────────────────────────────────────────────────
    //
    // The compose box saves itself. Every edit schedules a debounced draft
    // write; the first save inserts and records the row's id, and every save
    // after it updates that row in place, so a compose that stays open for an
    // afternoon is ONE draft, not one draft per keystroke batch.

    private var composeGeneration = 0L
    private var autosaveJob: kotlinx.coroutines.Job? = null
    private var autosaveInFlight = false
    private var autosaveNeedsFollowUp = false

    /**
     * The rules the debounce obeys, each one a refusal rather than a feature:
     * an EMPTY compose saves nothing (a person opening the composer and
     * leaving must not find an empty draft row named after nothing); a send or
     * a manual save in flight is not interrupted; and the autosave never
     * writes the receipt — its outcome is the composer's own quiet line, not
     * the "Saved to Drafts" banner the manual save earns.
     */
    private fun scheduleAutosave() {
        val s = _compose.value
        if (s.sending || s.accepted) return
        if (autosaveInFlight) {
            autosaveNeedsFollowUp = true
            return
        }
        autosaveJob?.cancel()
        if (s.to.isBlank() && s.cc.isBlank() && s.subject.isBlank() && s.body.isBlank()) return
        autosaveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(2_000)
            val t = token() ?: return@launch
            val cur = _compose.value
            if (cur.sending || autosaveInFlight) return@launch
            autosaveInFlight = true
            autosaveNeedsFollowUp = false
            _compose.update { it.copy(autosaving = true) }
            try {
                val r = Api.mailDraft(t, MailDraftRequest(
                    to = splitAddresses(cur.to), cc = splitAddresses(cur.cc),
                    subject = cur.subject, bodyText = cur.body, inReplyTo = cur.inReplyTo,
                    id = cur.draftId.takeIf { it.isNotEmpty() },
                ))
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                _compose.update {
                    it.copy(
                        autosaving = false,
                        autosaveNote = "Draft saved",
                        draftId = r.id ?: it.draftId,
                    )
                }
                loadedBoxes.remove(MailBox.Drafts)
                MailStore.invalidate(MailBox.Drafts)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _compose.update {
                    it.copy(autosaving = false, autosaveNote = "Draft not saved — it will be saved on the next change.")
                }
            } finally {
                autosaveInFlight = false
                if (autosaveNeedsFollowUp && kotlinx.coroutines.currentCoroutineContext().isActive) {
                    autosaveNeedsFollowUp = false
                    scheduleAutosave()
                }
            }
        }
    }


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
        val generation = composeGeneration
        viewModelScope.launch {
            val t = token() ?: return@launch
            val full = runCatching { Api.mailMessage(t, id) }.getOrNull()?.message
                ?: return@launch
            if (generation != composeGeneration) return@launch
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

    fun resetCompose(initialRecipient: String = "") {
        if (snapshotMode) return
        composeGeneration++
        autosaveNeedsFollowUp = false
        autosaveJob?.cancel()
        autosaveJob = null
        _compose.value = ComposeState(to = initialRecipient)
    }

    private fun stopAutosaveDebounce(): Job? {
        autosaveNeedsFollowUp = false
        val pending = autosaveJob
        if (!autosaveInFlight) pending?.cancel()
        return pending
    }

    /**
     * Send. The receipt shows `status` and `deliveryError` as the server reported
     * them — a message the queue has only accepted is not a delivered message,
     * and saying otherwise is the exact dishonesty the send-status test exists to
     * pin.
     */
    // ── undo send ────────────────────────────────────────────────────────────
    //
    // The reference's signature safety net, and the one guarantee a mail app
    // can make that nothing else can: for five seconds after Send the message
    // exists ONLY on this device. Nothing is on the wire, nothing is claimed
    // sent, and the composer offers Undo the whole time. Undo restores the
    // text exactly as Send left it; the window's end fires the real send, and
    // only the server's own answer produces a receipt.

    private data class HeldSend(
        val to: String, val cc: String, val bcc: String,
        val subject: String, val body: String, val inReplyTo: String?,
        val draftId: String, val e2eEnvelope: String?, val attachmentIds: List<String>,
        val generation: Long,
    )

    private var undoSendJob: kotlinx.coroutines.Job? = null
    private var heldSend: HeldSend? = null

    fun send(e2eEnvelope: String? = null, attachmentIds: List<String> = emptyList()) {
        val s = _compose.value
        if (s.sending || s.accepted || s.pendingSend) return
        // An empty To is refused here, not by the server: a message with no
        // recipient is not a send, and a receipt naming the server's 422 after
        // the person pressed Send twice is worse than a button that says why
        // it is waiting. Cc and Bcc do not count -- the route requires `to`.
        if (splitAddresses(s.to).isEmpty()) {
            _compose.update { it.copy(error = "Add at least one recipient in To.") }
            return
        }
        val held = HeldSend(
            to = s.to, cc = s.cc, bcc = s.bcc, subject = s.subject, body = s.body,
            inReplyTo = s.inReplyTo, draftId = _compose.value.draftId,
            e2eEnvelope = e2eEnvelope, attachmentIds = attachmentIds,
            generation = composeGeneration,
        )
        heldSend = held
        stopAutosaveDebounce()
        // sending stays FALSE through the window: no network call is in
        // flight, and the composer's own text is left untouched so Undo hands
        // back exactly what the person wrote.
        _compose.update { it.copy(pendingSend = true, error = null, result = null, resultStatus = null) }
        undoSendJob?.cancel()
        undoSendJob = viewModelScope.launch {
            kotlinx.coroutines.delay(5_000)
            executeSend(held)
        }
    }

    /** The undo-send window's tap. Cancels the held send before it touches the
     *  network; the composer text was never cleared, so this hands back the
     *  message exactly as Send received it — editable, re-sendable. */
    fun undoSend() {
        undoSendJob?.cancel()
        undoSendJob = null
        heldSend = null
        _compose.update { it.copy(pendingSend = false) }
        GMHaptics.marking()
    }

    // ── scheduled send ───────────────────────────────────────────────────────
    //
    // The server holds a send with status 'scheduled' and the worker delivers
    // it at `sendAt` (grapmail-scheduled.ts). The undo window does not apply:
    // choosing a future instant IS the reconsideration. The receipt names the
    // instant — a send that has not happened yet may not read like one that
    // has, and `status: 'scheduled'` is the server's own word for it.

    fun scheduleSend(at: String) {
        val s = _compose.value
        if (s.sending || s.accepted || s.pendingSend) return
        if (splitAddresses(s.to).isEmpty()) {
            _compose.update { it.copy(error = "Add at least one recipient in To.") }
            return
        }
        val generation = composeGeneration
        val pending = stopAutosaveDebounce()
        _compose.update { it.copy(sending = true, error = null, result = null, resultStatus = null) }
        viewModelScope.launch {
            pending?.join()
            if (generation != composeGeneration) return@launch
            val t = token() ?: run {
                _compose.update { it.copy(sending = false, error = "Sign in again.") }
                return@launch
            }
            runCatching {
                Api.mailSend(t, MailSendRequest(
                    to = splitAddresses(s.to), cc = splitAddresses(s.cc),
                    bcc = splitAddresses(s.bcc), subject = s.subject,
                    bodyText = s.body, inReplyTo = s.inReplyTo,
                    sendAt = at,
                ))
            }.onSuccess { r ->
                val scheduled = r.status == "scheduled"
                val draftId = _compose.value.draftId
                if (generation == composeGeneration) _compose.update { it.copy(sending = false,
                    result = r.message ?: r.error, resultStatus = r.status,
                    error = r.deliveryError ?: r.error) }
                if (scheduled && draftId.isNotEmpty()) {
                    // The scheduled message is not a draft anymore: the server
                    // holds its own copy, so the local draft row would twin it.
                    runCatching { Api.mailDeleteMessage(t, draftId) }.onFailure { }
                    if (generation == composeGeneration) _compose.update { it.copy(draftId = "") }
                    loadedBoxes.remove(MailBox.Drafts)
                    MailStore.invalidate(MailBox.Drafts)
                }
                if (scheduled) GMHaptics.success() else GMHaptics.failure()
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (generation != composeGeneration) return@onFailure
                _compose.update { it.copy(sending = false, error = e.message ?: "The message could not be scheduled.") }
                GMHaptics.failure()
            }
        }
    }

    /** The compose screen's Send-later presets, computed fresh each open so a
     *  "tomorrow 8:00" chosen at 23:00 means tomorrow, not today. */
    fun sendLaterPresets(): List<Pair<String, String>> {
        val zone = java.time.ZoneId.systemDefault()
        val fmt = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val tomorrow = java.time.LocalDate.now().plusDays(1)
        var monday = tomorrow
        while (monday.dayOfWeek != java.time.DayOfWeek.MONDAY) monday = monday.plusDays(1)
        val mondayLabel = if (monday == tomorrow) "Monday 8:00" else "Next Monday 8:00"
        return listOf(
            "In 1 hour" to java.time.ZonedDateTime.now().plusHours(1).format(fmt),
            "Tomorrow 8:00" to tomorrow.atTime(8, 0).atZone(zone).format(fmt),
            mondayLabel to monday.atTime(8, 0).atZone(zone).format(fmt),
        )
    }

    // ── scheduled send list ──────────────────────────────────────────────────

    data class ScheduledState(
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val error: String? = null,
        val messages: List<MailScheduledDto> = emptyList(),
        val cancellingId: String? = null,
        val note: String? = null,
    )

    private val _scheduled = MutableStateFlow(ScheduledState())
    val scheduled: StateFlow<ScheduledState> = _scheduled.asStateFlow()

    fun loadScheduled() {
        if (_scheduled.value.loading) return
        _scheduled.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val t = token() ?: run {
                _scheduled.update { it.copy(loading = false, error = "Sign in again.") }
                return@launch
            }
            try {
                val r = Api.mailScheduledList(t)
                if (!r.success) {
                    _scheduled.update { it.copy(loading = false, loaded = true,
                        error = "The server returned no listing.") }
                    return@launch
                }
                _scheduled.update { it.copy(loading = false, loaded = true, messages = r.messages) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _scheduled.update { it.copy(loading = false, error = mailReason(e)) }
            }
        }
    }

    /** Take one future send back. The server clears `scheduled_at`; the row
     *  stops being mail the worker will deliver — which is the honest sentence
     *  the note prints, not "deleted". */
    fun cancelScheduled(id: String) {
        if (_scheduled.value.cancellingId != null) return
        _scheduled.update { it.copy(cancellingId = id) }
        viewModelScope.launch {
            val t = token() ?: run {
                _scheduled.update { it.copy(cancellingId = null, error = "Sign in again.") }
                return@launch
            }
            try {
                Api.mailScheduledCancel(t, id)
                _scheduled.update { it.copy(cancellingId = null,
                    messages = it.messages.filterNot { m -> m.id == id },
                    note = "Cancelled — it will not be sent.") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _scheduled.update { it.copy(cancellingId = null, error = mailReason(e)) }
            }
        }
    }

    private fun executeSend(held: HeldSend) {
        if (held.generation != composeGeneration) return
        heldSend = null
        undoSendJob = null
        val pending = stopAutosaveDebounce()
        _compose.update { it.copy(pendingSend = false, sending = true, error = null, result = null, resultStatus = null) }
        viewModelScope.launch {
            pending?.join()
            // No generation gate here on purpose: once the window expired
            // without Undo, the send is owed — a composer the person closed
            // in the meantime must not swallow the mail. Only the UI updates
            // below are generation-gated.
            val draftId = held.draftId
            val t = token() ?: run {
                if (held.generation == composeGeneration) _compose.update { it.copy(sending = false, error = "Sign in again.") }
                return@launch
            }
            // The envelope goes on the wire as the JSON OBJECT the route
            // parses — a string is silently dropped by sanitiseE2eEnvelope
            // and the sealed send would then 400 on its deliberate empty
            // body. Unparseable text refuses the send whole: the alternative
            // reading of "no envelope" would send the plaintext the envelope
            // exists to keep private.
            val envelope = held.e2eEnvelope?.let {
                try { Json.parseToJsonElement(it).jsonObject }
                catch (e: Exception) {
                    _compose.update { s -> s.copy(sending = false, error = "The encrypted envelope could not be prepared — nothing was sent.") }
                    null
                }
            }
            if (held.e2eEnvelope != null && envelope == null) return@launch
            runCatching {
                Api.mailSend(t, MailSendRequest(
                    to = splitAddresses(held.to), cc = splitAddresses(held.cc),
                    bcc = splitAddresses(held.bcc), subject = held.subject,
                    // With an envelope the plaintext does not travel: the
                    // route stores its notice in place of a body, and a
                    // client that sent both would leak exactly what the
                    // envelope exists to keep private.
                    bodyText = if (envelope != null) "" else held.body,
                    inReplyTo = held.inReplyTo,
                    e2eEnvelope = envelope,
                    attachmentIds = held.attachmentIds,
                ))
            }.onSuccess { r ->
                val generation = held.generation
                if (generation == composeGeneration) _compose.update { it.copy(sending = false,
                    result = r.message ?: r.error, resultStatus = r.status,
                    error = r.deliveryError ?: r.error) }
                if (generation == composeGeneration) _compose.update { it.copy(sending = false,
                    result = r.message ?: r.error, resultStatus = r.status,
                    error = r.deliveryError ?: r.error) }
                // A draft that was just sent is no longer a draft. The order
                // is the whole point: the delete runs only AFTER the server
                // accepted the message, so a failed send leaves the draft
                // exactly where it was. Deleting first would trade a
                // half-written mail for an error message.
                // The live server answers a delivered self-send with status
                // "delivered" — a third success word, measured by the walk in
                // 2026-11-08's run (the receipt said "Server reported:
                // delivered" in warn ink beneath a failure tap). Without it
                // here, every delivered message buzzed as a failure.
                val sent = r.status == "sent" || r.status == "queued" || r.status == "delivered"
                if (sent) {
                    loadedBoxes.remove(MailBox.Sent)
                    loadedBoxes.remove(MailBox.Inbox)
                    MailStore.invalidate(MailBox.Sent)
                    MailStore.invalidate(MailBox.Inbox)
                }
                if (draftId.isNotEmpty() && sent) {
                    runCatching { Api.mailDeleteMessage(t, draftId) }
                        .onSuccess {
                            loadedBoxes.remove(MailBox.Drafts)
                            MailStore.invalidate(MailBox.Drafts)
                        }
                        // The mail IS sent. A draft row that outlives its
                        // send is a stale copy, not a lost message, so this
                        // says nothing and does not fail the receipt.
                        .onFailure { }
                    if (generation == composeGeneration) _compose.update { it.copy(draftId = "") }
                }
                if (sent) load(replace = true)
                // The feel follows the answer. A send the relay has only
                // QUEUED gets the same tap a delivered one would, and the
                // receipt already says in words that those are different
                // claims; a refused send gets the failure tap.
                if (sent) GMHaptics.success() else GMHaptics.failure()
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (held.generation != composeGeneration) return@onFailure
                _compose.update { it.copy(sending = false,
                    error = e.message ?: "The message could not be sent.") }
                GMHaptics.failure()
            }
        }
    }

    /** Save as a draft. Discarding a half-written message without asking is the
     *  other way a mail app loses something; this is what the back button does. */
    fun saveDraft(onSaved: () -> Unit = {}) {
        val s = _compose.value
        if (s.sending || s.accepted) return
        val generation = composeGeneration
        val pending = stopAutosaveDebounce()
        _compose.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            pending?.join()
            val t = token()
            val draftId = _compose.value.draftId
            // A close may have opened another composer while the save waited.
            if (generation != composeGeneration) return@launch
            if (t == null) {
                _compose.update { it.copy(sending = false, error = "Sign in again.") }
                return@launch
            }
            runCatching {
                Api.mailDraft(t, MailDraftRequest(
                    to = splitAddresses(s.to), cc = splitAddresses(s.cc),
                    subject = s.subject, bodyText = s.body, inReplyTo = s.inReplyTo,
                    id = draftId.takeIf { it.isNotEmpty() },
                ))
            }.onSuccess { r ->
                // The manual save keeps the draft's id current too: continuing
                // a saved message and pressing Save updates that row now
                // instead of inserting a second one.
                if (generation == composeGeneration) _compose.update {
                    it.copy(
                        sending = false, result = "Saved to Drafts", resultStatus = "draft",
                        draftId = r.id ?: it.draftId,
                    )
                }
                loadedBoxes.remove(MailBox.Drafts)
                MailStore.invalidate(MailBox.Drafts)
                if (generation == composeGeneration) onSaved()
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (generation == composeGeneration) {
                    _compose.update { it.copy(sending = false, error = e.message ?: "The draft was not saved.") }
                }
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
                    val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val dir = getApplication<android.app.Application>()
                                .getExternalFilesDir(null) ?: return@runCatching "No app storage available."
                            val file = java.io.File(dir, attachmentFilename(a.name))
                            file.writeBytes(bytes)
                            "Saved to ${file.absolutePath}"
                        }.getOrElse { "Downloaded, but the file could not be written." }
                    }
                    then(outcome)
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
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
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    then((e as? ApiException)?.message ?: "This attachment could not be downloaded.")
                    return@launch
                }
            val app = getApplication<android.app.Application>()
            val outcome = runCatching {
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val root = java.io.File(app.cacheDir, "attachments").apply { mkdirs() }
                    val dir = java.nio.file.Files.createTempDirectory(root.toPath(), "preview-").toFile()
                    java.io.File(dir, attachmentFilename(a.name)).apply { writeBytes(bytes) }
                }
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
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                "This attachment could not be handed to another app."
            }
            then(outcome)
        }
    }

    private fun attachmentFilename(raw: String?): String =
        raw.orEmpty().replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"

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

    suspend fun people(): List<Person> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val counts = LinkedHashMap<String, Pair<String?, Int>>()
        val seenMessages = HashSet<String>()
        MailBox.tabOrder.forEach { box ->
            MailStore.page(box, null)?.messages?.forEach { m ->
                if (seenMessages.add(m.id)) {
                    m.from?.address?.takeIf { it.isNotBlank() }?.let { a ->
                        val prior = counts[a]
                        counts[a] = (m.from?.name ?: prior?.first) to ((prior?.second ?: 0) + 1)
                    }
                    m.to.forEach { a -> if (a.isNotBlank()) counts.putIfAbsent(a, null to 0) }
                }
            }
        }
        counts.map { (addr, v) -> Person(addr, v.first, v.second) }
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
    suspend fun uploadAttachment(bytes: ByteArray, filename: String, mimeType: String): MailAttachmentDto {
        val bearer = token() ?: throw IllegalStateException("Sign in again to attach a file.")
        return Api.mailAttachmentUpload(bearer, bytes, filename, mimeType, null)
    }

    // ── thread ──────────────────────────────────────────────────────────────

    data class ThreadState(
        val messages: List<com.grapsee.grapmail.data.MailMessageFullDto> = emptyList(),
        val loading: Boolean = false, val error: String? = null,
    )

    private val _thread = MutableStateFlow(ThreadState())
    val thread: StateFlow<ThreadState> = _thread.asStateFlow()

    // ── the reader's formatted body ─────────────────────────────────────────
    //
    // One render per message, keyed by id. The html IS the server's answer: the
    // body endpoint sanitized it with the web's untrusted-email profile before
    // it left the app's origin, so this map is the only source a WebView is
    // ever fed from. `allowImages` is stored with the render because the two
    // variants are different answers, not a reload of one: without it, an
    // images-off render would be mistaken for the opt-in one and the gate's
    // second fetch would be skipped.

    data class BodyRender(
        val html: String? = null,
        val bodyText: String? = null,
        val blockedImages: Int = 0,
        val allowImages: Boolean = false,
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val formattedBodyCache = android.util.LruCache<String, BodyRender>(24)

    /**
     * The snapshot host's pre-seeded renders, keyed by message id. The host
     * seeds the render beside the row it belongs to, so the walk photographs
     * the formatted branch without a session or a backend.
     */
    private val snapshotBodies = mutableMapOf<String, BodyRender>()

    /** Test-only seam, in the shape of [seedThreadForSnapshot]: the host plants
     *  the images-off render the body endpoint would have answered. */
    fun seedBodyForSnapshot(messageId: String, render: BodyRender) {
        snapshotBodies[messageId] = render
    }

    private var prefetchJob: Job? = null

    /**
     * The first screen's bodies, fetched before anyone taps a row.
     *
     * A reader open used to pay one round trip for the formatted body on every
     * message — dead air a mail app does not need, because the rows a person is
     * most likely to open are knowable the moment the page lands. This fills
     * the same cache [loadBody] reads, with the same images-blocked render the
     * reader defaults to, so the first open is a cache hit and the round trip
     * happens while the list is being read. Sequential, not parallel: a burst
     * of body reads is exactly what the server rate-limits, and the point is
     * warmth, not speed of warmth.
     */
    private fun prefetchVisibleBodies() {
        if (snapshotMode) return
        val ids = _state.value.rows.take(6).map { it.id }
        if (ids.isEmpty() || prefetchJob?.isActive == true) return
        prefetchJob = viewModelScope.launch {
            for (id in ids) {
                ensureActive()
                if (formattedBodyCache.get(id) != null) continue
                try {
                    loadBody(id, allowImages = false)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A body that will not prefetch now loads on tap, exactly as
                    // it always has. The list does not wait for this.
                    break
                }
            }
        }
    }

    suspend fun loadBody(messageId: String, allowImages: Boolean): BodyRender {
        if (snapshotMode) return snapshotBodies[messageId] ?: BodyRender()
        if (!allowImages) formattedBodyCache.get(messageId)?.let { return it }
        val t = token() ?: return BodyRender(allowImages = allowImages, error = "Sign in again.")
        return try {
            val response = Api.mailBody(t, messageId, allowImages)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val render = BodyRender(
                html = response.bodyHtml,
                blockedImages = response.blockedImages,
                allowImages = allowImages,
                error = if (response.bodyHtml.isNullOrBlank()) "The formatted body was empty." else null,
            )
            if (!allowImages && render.error == null && (render.html?.length ?: 0) <= 262_144) {
                formattedBodyCache.put(messageId, render)
            }
            render
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            BodyRender(allowImages = allowImages, error = error.message ?: "The formatted view could not be loaded.")
        }
    }

    /**
     * Open a thread. The list payload has no bodies by design, so the bodies are
     * read here and memoised — a row re-rendering must not re-read a payload.
     */
    fun openThread(threadId: String) {
        if (readerTarget == null && threadLoadedFor == threadId) return
        loadReader(threadId, isThread = true) {
            val t = token()
            if (t == null) {
                _thread.value = ThreadState(error = "Sign in again.")
                return@loadReader
            }
            runCatching { Api.mailThread(t, threadId) }
                .onSuccess { r ->
                    // Remembered on success, because this field IS the memo: without
                    // it the guard above never fires and every return from a thread
                    // to the list costs the whole conversation payload again -- which
                    // is the over-fetch the field's own comment promises not to do,
                    // and iOS does not. A failure deliberately does not record it, so
                    // re-opening retries instead of caching an empty conversation.
                    val messages = MailStore.putBodies(r.messages)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    threadLoadedFor = threadId
                    _thread.update { it.copy(messages = messages, loading = false) }
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
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
                    reproject()
                    GMHaptics.marking()
                    onOutcome(null)
                }
                .onFailure {
                    GMHaptics.failure()
                    onOutcome((it as? ApiException)?.message
                        ?: "That action didn't go through.")
                }
        }
    }

    suspend fun viewMessage(id: String): String? {
        if (snapshotMode) return null
        val message = _thread.value.messages.firstOrNull { it.id == id } ?: return null
        val needsAttachments = message.hasAttachments && message.attachments.isEmpty()
        if (!needsAttachments && (message.isRead || message.direction != "inbound")) return null
        val context = getApplication<Application>()
        val session = SessionStore.snapshot(context) ?: return "Sign in again."
        return try {
            val details = if (needsAttachments) Api.mailMessage(session.token, id).message else null
            if (needsAttachments && details == null) return "Attachment details are unavailable."
            if (!needsAttachments) Api.mailAction(session.token, id, MailAction.Read)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!SessionStore.isCurrent(context, session.id)) return null
            val current = _thread.value.messages.firstOrNull { it.id == id } ?: message
            val viewed = if (details != null) {
                current.copy(attachments = details.attachments, isRead = details.isRead)
            } else {
                current.copy(isRead = true)
            }
            _thread.update { state ->
                state.copy(messages = state.messages.map { if (it.id == id) viewed else it })
            }
            MailStore.putBody(viewed)
            if (viewed.isRead) {
                MailStore.patchFlags(id, read = true)
                reproject()
            }
            null
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            error.message ?: "The message details could not be loaded."
        }
    }

    fun openMessage(id: String) {
        if (readerTarget == null && threadLoadedFor == id) return
        loadReader(id, isThread = false) {
            val t = token()
            if (t == null) {
                _thread.value = ThreadState(error = "Sign in again.")
                return@loadReader
            }
            val cached = MailStore.body(id)
            if (cached != null) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                threadLoadedFor = id
                _thread.update { it.copy(messages = listOf(cached), loading = false) }
                return@loadReader
            }
            runCatching { Api.mailMessage(t, id) }
                .onSuccess { r ->
                    // Recorded only when a body actually arrived: caching the id for
                    // an empty response would make the guard above skip the retry and
                    // leave the screen showing nothing for a message that exists.
                    val message = r.message
                    if (message == null) {
                        _thread.value = ThreadState(error = "This message could not be opened.")
                    } else {
                        MailStore.putBody(message)
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        threadLoadedFor = id
                        _thread.update { s -> s.copy(messages = listOf(message), loading = false) }
                        MailStore.patchFlags(message.id, read = true)
                    }
                    reproject()
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
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

    private var labelLoad: Job? = null
    private var labelsLoadedAt: Long? = null

    fun refreshLabels(force: Boolean = false) {
        if (snapshotMode) return
        if (!force && (labelLoad?.isActive == true ||
            labelsLoadedAt?.let { android.os.SystemClock.elapsedRealtime() - it < 15_000 } == true)
        ) return
        labelLoad?.cancel()
        labelLoad = viewModelScope.launch {
            val t = token() ?: return@launch
            _labels.update { it.copy(loading = true, error = null) }
            _state.update { it.copy(labelsRead = AssignmentsRead.Reading) }
            runCatching { Api.mailLabels(t) }
                .onSuccess {
                    MailStore.putLabels(it.labels)
                    labelsLoadedAt = android.os.SystemClock.elapsedRealtime()
                    _labels.update { s -> s.copy(labels = it.labels, loading = false) }
                    _state.update { s -> s.copy(labels = it.labels, labelsRead = AssignmentsRead.Read) }
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
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
    fun createLabel(name: String, colour: String?, then: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: run { then(false, "Sign in again."); return@launch }
            runCatching { Api.mailCreateLabel(t, name, colour) }
                .onSuccess {
                    if (!it.success || it.label == null) {
                        then(false, it.error ?: "The label could not be created.")
                        return@onSuccess
                    }
                    refreshLabels(force = true)
                    then(true, "Created ${it.label.name}.")
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    then(false, (e as? ApiException)?.message ?: "The label could not be created.")
                }
        }
    }

    /// Rename or recolour a label. The route is `PATCH /labels/:id` and it
    /// answers with the row it wrote, so the list is re-read rather than
    /// patched in place — the same rule the filing actions follow.
    fun renameLabel(id: String, name: String, colour: String?, then: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: run { then(false, "Sign in again."); return@launch }
            runCatching { Api.mailRenameLabel(t, id, name, colour) }
                .onSuccess {
                    refreshLabels(force = true)
                    then(true, "Saved.")
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    then(false, (e as? ApiException)?.message ?: "The label could not be saved.")
                }
        }
    }

    fun deleteLabel(id: String, then: (String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: run { then("Sign in again."); return@launch }
            runCatching { Api.mailDeleteLabel(t, id) }
                .onSuccess { refreshLabels(force = true); then("Label removed. Its messages keep their other labels.") }
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
    private var screenerLoad: Job? = null
    private var screenerSettingVersion = 0

    fun loadScreener() {
        if (snapshotMode || screenerLoad?.isActive == true) return
        screenerLoad = viewModelScope.launch {
            val context = getApplication<Application>()
            val session = SessionStore.snapshot(context) ?: return@launch
            val version = screenerSettingVersion
            launch settings@{
                try {
                    val response = Api.mailScreener(session.token)
                    if (version != screenerSettingVersion || !SessionStore.isCurrent(context, session.id)) return@settings
                    _screener.update { it.copy(loaded = true, enabled = response.enabled ?: it.enabled,
                        error = response.error ?: if (response.enabled == null) "Screener status is unavailable." else null) }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (version == screenerSettingVersion && SessionStore.isCurrent(context, session.id)) {
                        _screener.update { it.copy(loaded = true, error = mailReason(error)) }
                    }
                }
            }
            launch queue@{
                val cached = MailStore.page(MailBox.Screener)
                    ?.takeIf { System.currentTimeMillis() - it.loadedAtMillis in 0L..30_000L }
                val total = cached?.total ?: try {
                    Api.mailInbox(session.token, MailBox.Screener, pageSize = 5).total
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@queue
                }
                if (SessionStore.isCurrent(context, session.id)) _screener.update { it.copy(pending = total) }
            }
        }
    }

    fun setScreener(enabled: Boolean) {
        if (_screener.value.busy || _screener.value.enabled == enabled) return
        screenerSettingVersion++
        _screener.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val t = token()
            if (t == null) {
                _screener.update { it.copy(busy = false, error = "Sign in again.") }
                return@launch
            }
            try {
                val response = Api.mailScreenerSet(t, enabled)
                _screener.update { it.copy(busy = false, enabled = response.enabled ?: it.enabled,
                    error = response.error ?: if (response.enabled == null) "The setting could not be confirmed." else null) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _screener.update { it.copy(busy = false, error = mailReason(error)) }
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
     * server accepted the rule.
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

            if (_screener.value.busy) return@launch
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
                    .onFailure { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        failure = mailReason(e)
                    }
            }
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
            if (_screener.value.busy) return@launch
            _screener.update { it.copy(busy = true, error = null) }
            runCatching { Api.mailScreenerDecisionRemove(t, sender, scope) }
                .onSuccess { r ->
                    _screener.update { it.copy(busy = false) }
                    then(if (r.removed == true) "Forgotten — their next message waits in the queue again."
                    else "No decision was recorded for them, so there was nothing to forget.")
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _screener.update { it.copy(busy = false, error = mailReason(e)) }
                    then(mailReason(e))
                }
        }
    }

    // ── files ────────────────────────────────────────────────────────────────

    data class FileEntry(val attachment: MailAttachmentDto, val fromMessage: String?)

    private val _filesRevision = MutableStateFlow(0)
    val filesRevision = _filesRevision.asStateFlow()

    suspend fun file(id: String): FileEntry? =
        MailStore.indexedAttachment(id)?.let { FileEntry(it.attachment, it.subject) }

    suspend fun files(): List<FileEntry> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        MailStore.indexedAttachments().map { FileEntry(it.attachment, it.subject) }
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
    fun deleteAttachment(id: String, then: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val t = token() ?: run { then(false, "Sign in again to remove this."); return@launch }
            runCatching { Api.mailDeleteAttachment(t, id) }
                .onSuccess { r ->
                    if (r.success) {
                        MailStore.forgetAttachment(id)
                        MailStore.invalidateAllBoxes()
                        loadedBoxes.clear()
                        _thread.update { state ->
                            state.copy(messages = state.messages.map { message ->
                                val remaining = message.attachments.filterNot { it.id == id }
                                if (remaining.size == message.attachments.size) message
                                else message.copy(attachments = remaining, hasAttachments = remaining.isNotEmpty())
                            })
                        }
                        _filesRevision.update { it + 1 }
                        then(true, "Removed.")
                    } else {
                        then(false, r.error ?: "The server refused to remove that file.")
                    }
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    then(false, mailReason(e))
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

