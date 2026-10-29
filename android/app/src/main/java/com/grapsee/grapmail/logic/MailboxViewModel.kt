package com.grapsee.grapmail.logic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.MailAction
import com.grapsee.grapmail.data.MailBox
import com.grapsee.grapmail.data.MailDraftRequest
import com.grapsee.grapmail.data.MailFilter
import com.grapsee.grapmail.data.MailLabelDto
import com.grapsee.grapmail.data.MailMessageDto
import com.grapsee.grapmail.data.MailSendRequest
import com.grapsee.grapmail.data.MailSort
import com.grapsee.grapmail.data.MailStore
import com.grapsee.grapmail.data.SessionStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
class MailboxViewModel(app: Application) : AndroidViewModel(app) {

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
        val unreadCount: Int? = null,
        /**
         * The mailbox's own identity from `GET /me`. The greeting is built from
         * these, not from the sign-in account: `/me` answers with the name the
         * mailbox carries, and null means it never said so — in which case the
         * screen shows no name rather than a plausible one.
         */
        val accountName: String? = null,
        val accountAddress: String? = null,
        /**
         * The four extra counts `/stats` answers for. Each is null until the
         * server says it, and a null draws no badge — `0` would claim the folder
         * is empty when nobody was asked. Starred, Archive and All Mail are
         * never in this response, so they have no field here at all.
         */
        val statsSent: Int? = null,
        val statsDrafts: Int? = null,
        val statsSpam: Int? = null,
        val statsTrash: Int? = null,
        val busy: Boolean = false,
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
            val t = token() ?: return@launch
            // The label strip is the reference's only backed substitute for the
            // personal/work chips, so it loads with the list rather than waiting
            // for the labels screen.
            runCatching { Api.mailLabels(t) }
                .onSuccess {
                    MailStore.putLabels(it.labels)
                    _state.update { s -> s.copy(labels = it.labels) }
                }
            // `/me` answers with the mailbox's own name and address. A failure
            // here is not an error screen: the greeting falls back to no name,
            // which is honest, and the list is what matters.
            runCatching { Api.mailMe(t) }.onSuccess { me ->
                MailStore.putMe(me)
                _state.update { it.copy(accountName = me.name, accountAddress = me.address) }
            }
        }
    }

    // ── list ─────────────────────────────────────────────────────────────────

    fun open(box: MailBox) {
        _state.update { it.copy(box = box, selection = emptySet()) }
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
                _state.update { it.copy(loading = false, loadingMore = false, fatal = "Sign in again.") }
                return@launch
            }

            runCatching { Api.mailInbox(t, box, page = page, q = term) }
                .onSuccess { resp ->
                    MailStore.putPage(box, term, resp, replace)
                    if (term == null) runCatching { MailStore.putStats(
                        Api.mailStats(t)) }
                    _state.update { it.copy(loading = false, loadingMore = false, fatal = null,
                        staleError = null) }
                    reproject()
                }
                .onFailure { e ->
                    val noBox = (e as? ApiException)?.code == "NO_MAILBOX"
                    val msg = e.message ?: "Couldn't reach your mailbox."
                    _state.update { cur ->
                        cur.copy(
                            loading = false, loadingMore = false,
                            noMailbox = noBox,
                            // With rows on screen this is a stale list, not an
                            // empty one. Without them it is the error screen.
                            staleError = if (cur.hasRows) msg else null,
                            fatal = if (cur.hasRows || noBox) null else msg,
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
            // Each stays null when the server did not answer it. A null draws no
            // badge; a 0 would claim an empty folder.
            unreadCount = stats?.inbox?.unread,
            statsSent = stats?.sent,
            statsDrafts = stats?.drafts,
            statsSpam = stats?.spam,
            statsTrash = stats?.trash,
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
            for (id in ids) {
                runCatching { Api.mailAction(t, id, action, until) }
                    .onSuccess { applyLocally(id, action) }
                    .onFailure { failure = it.message ?: "That action didn't go through." }
            }
            _state.update { it.copy(busy = false, selection = emptySet(), staleError = failure) }
            invalidateFor(action)
            load(replace = true)
        }
    }

    fun actOn(id: String, action: MailAction) {
        viewModelScope.launch {
            val t = token() ?: return@launch
            runCatching { Api.mailAction(t, id, action, null) }
                .onSuccess { applyLocally(id, action); invalidateFor(action); reproject() }
                .onFailure { _state.update { s -> s.copy(staleError = it.message) } }
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
            MailAction.Snooze -> MailStore.invalidate(MailBox.Inbox)
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
                    .onSuccess { MailStore.removeEverywhere(id) }
                    .onFailure { failure = it.message }
            }
            _state.update { it.copy(busy = false, selection = emptySet(), staleError = failure) }
            load(replace = true)
        }
    }

    fun assignLabel(labelId: String) {
        val ids = _state.value.selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val t = token() ?: return@launch
            _state.update { it.copy(busy = true) }
            var failure: String? = null
            ids.forEach { runCatching { Api.mailLabelAssign(t, it, labelId) }.onFailure { e -> failure = e.message } }
            _state.update { it.copy(busy = false, selection = emptySet(), staleError = failure) }
        }
    }

    // ── compose ──────────────────────────────────────────────────────────────

    data class ComposeState(
        val to: String = "", val cc: String = "", val bcc: String = "",
        val subject: String = "", val body: String = "",
        val inReplyTo: String? = null,
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
    fun send() {
        val s = _compose.value
        if (s.sending) return
        viewModelScope.launch {
            val t = token() ?: run { _compose.update { it.copy(error = "Sign in again.") }; return@launch }
            _compose.update { it.copy(sending = true, error = null, result = null, resultStatus = null) }
            runCatching {
                Api.mailSend(t, MailSendRequest(
                    to = splitAddresses(s.to), cc = splitAddresses(s.cc),
                    bcc = splitAddresses(s.bcc), subject = s.subject,
                    bodyText = s.body, inReplyTo = s.inReplyTo,
                ))
            }.onSuccess { r ->
                _compose.update { it.copy(sending = false,
                    result = r.message ?: r.error, resultStatus = r.status,
                    error = r.deliveryError ?: r.error) }
                MailStore.invalidate(MailBox.Sent)
                MailStore.invalidate(MailBox.Inbox)
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

    // ── thread ───────────────────────────────────────────────────────────────

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
        viewModelScope.launch {
            val t = token() ?: return@launch
            _thread.update { it.copy(loading = true, error = null) }
            runCatching { Api.mailThread(t, threadId) }
                .onSuccess { r ->
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

    fun openMessage(id: String) {
        viewModelScope.launch {
            val t = token() ?: return@launch
            _thread.update { it.copy(loading = true, error = null) }
            val cached = MailStore.body(id)
            if (cached != null) {
                _thread.update { it.copy(messages = listOf(cached), loading = false) }
                return@launch
            }
            runCatching { Api.mailMessage(t, id) }
                .onSuccess { r ->
                    r.message?.let {
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
}
