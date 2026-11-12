package com.grapsee.grapmail.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The mailbox's process-lifetime cache: the last page of each box, the last
 * stats, the last labels, the label pairs the account carries, and the message
 * bodies that were opened.
 *
 * WHY THIS EXISTS
 * ---------------
 * The hub's bug this sector inherits if it is not designed out: a screen that
 * re-fetches on every reappearance treats a *failed refresh* as a *failed load*,
 * blanking data it already had and disabling its own controls. On a mailbox the
 * symptom is worse than a spinner — returning from a thread to the list would
 * drop the list you were reading.
 *
 * So the cache is the screen's starting value, and a refresh that fails leaves
 * what is already on screen on screen. The rule each caller follows is
 * "a refresh may not blank data the app already has"; the badge rule
 * (`null` means unanswered, never `0`) lives on [MailStatsResponse].
 *
 * CLEARED ON SIGN-OUT, and only there. A cached mailbox left behind would be the
 * first thing the next account on this device sees — the same cross-account leak
 * [SecurityProfileCache] closes for the security verdicts.
 */
object MailStore {

    /** One box's loaded pages, in arrival order, oldest page first. */
    @Serializable
    data class PageCache(
        val messages: List<MailMessageDto> = emptyList(),
        val total: Int = 0,
        val pages: Int = 0,
        /** The search term the pages belong to, so a search result is never
         *  mistaken for the unfiltered box. */
        val query: String? = null,
        val loadedAtMillis: Long = 0,
    ) {
        val isEmpty: Boolean get() = messages.isEmpty()
    }

    private val mutex = Mutex()
    private const val MAX_CACHED_SEARCHES = 8

    // ── disk snapshot: offline boot ─────────────────────────────────────────
    //
    // The store above is process-lifetime, which made a cold app open a blank
    // screen until the network answered — on a distant server that is seconds
    // of nothing, twice a day, forever. This snapshot writes the same state to
    // one file (atomic rename, debounced bursts) and reloads it at boot, so
    // the first frame of a cold open is the mail that was on screen when the
    // process died. The network still runs after it; the snapshot only decides
    // what the FIRST paint shows, and every refetch rule that guards staleness
    // (reuseRecentFolder, refreshOnResume) keeps working on top of it.
    //
    // Bodies are deliberately not persisted: they are payload-heavy, they
    // warm through prefetch the moment a page lands, and a snapshot that grew
    // unbounded would outlive its welcome on a 32GB device.

    private const val SNAPSHOT_VERSION = 1

    @Serializable
    private data class DiskSnapshot(
        val version: Int,
        val pages: Map<String, PageCache>,
        val stats: MailStatsResponse? = null,
        val labels: List<MailLabelDto>? = null,
        val aliases: List<MailAliasDto>? = null,
        val me: MailMeResponse? = null,
        val assignments: Map<String, Set<String>>? = null,
    )

    private const val SNAPSHOT_FILE = "mailbox-snapshot.json"
    private val snapshotJson = Json { ignoreUnknownKeys = true }
    private val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var snapshotWriteScheduled = false
    @Volatile private var appContext: android.content.Context? = null

    /**
     * Called once from the Application at process start, before any screen.
     * Loads the previous session's snapshot into memory so the first mailbox
     * frame has mail on it. Only an EMPTY store accepts a snapshot: this runs
     * before any network write could have landed, so anything already in
     * memory is by definition fresher than the file.
     */
    fun initDisk(app: android.content.Context) {
        if (appContext != null) return
        appContext = app.applicationContext
        snapshotScope.launch {
            val ctx = appContext ?: return@launch
            val text = try {
                java.io.File(ctx.filesDir, SNAPSHOT_FILE).takeIf { it.exists() }?.readText()
            } catch (_: Exception) { null } ?: return@launch
            val snap = try {
                snapshotJson.decodeFromString(DiskSnapshot.serializer(), text)
            } catch (_: Exception) { null } ?: return@launch
            if (snap.version != SNAPSHOT_VERSION) return@launch
            mutex.withLock {
                if (pages.isEmpty() && stats == null && me == null && assignments == null) {
                    pages = snap.pages
                    stats = snap.stats
                    labels = snap.labels
                    aliases = snap.aliases
                    me = snap.me
                    assignments = snap.assignments
                }
            }
        }
    }

    /**
     * Coalesce write bursts: a page load touches putPage + putStats + putMe;
     * one write 1.5s after the burst settles is one disk tick, not three.
     */
    private fun scheduleSnapshotWrite() {
        if (snapshotWriteScheduled) return
        snapshotWriteScheduled = true
        snapshotScope.launch {
            delay(1_500)
            snapshotWriteScheduled = false
            writeSnapshotNow()
        }
    }

    private suspend fun writeSnapshotNow() {
        val ctx = appContext ?: return
        mutex.withLock {
            val snap = DiskSnapshot(
                version = SNAPSHOT_VERSION,
                pages = pages,
                stats = stats,
                labels = labels,
                aliases = aliases,
                me = me,
                assignments = assignments,
            )
            try {
                // Atomic by rename: a process death mid-write leaves the
                // previous snapshot intact rather than a truncated file.
                val tmp = java.io.File(ctx.filesDir, "$SNAPSHOT_FILE.tmp")
                tmp.writeText(snapshotJson.encodeToString(DiskSnapshot.serializer(), snap))
                val dst = java.io.File(ctx.filesDir, SNAPSHOT_FILE)
                if (!tmp.renameTo(dst)) {
                    dst.writeText(tmp.readText())
                    tmp.delete()
                }
            } catch (_: Exception) {
                // The snapshot is a boost, never a duty: a failed write costs
                // one cold boot its warm start, and nothing else.
            }
        }
    }

    private var pages: Map<String, PageCache> = emptyMap()
    private var stats: MailStatsResponse? = null
    private var labels: List<MailLabelDto>? = null
    private var aliases: List<MailAliasDto>? = null
    private var me: MailMeResponse? = null
    private val bodies = object : android.util.LruCache<String, MailMessageFullDto>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: MailMessageFullDto): Int =
            maxOf(1_024, value.sizeBytes ?: 0, (value.bodyText.orEmpty().length + value.bodyHtml.orEmpty().length) * 2)
    }

    data class IndexedAttachment(val attachment: MailAttachmentDto, val subject: String?)
    private val attachmentsByMessage = mutableMapOf<String, List<IndexedAttachment>>()

    /**
     * messageId → the label ids that message carries. Null means the account has
     * never been asked; an EMPTY map means it was asked and nothing is labelled.
     *
     * The distinction is the whole point of the field. Collapsed into one value,
     * “not yet read” would render as “no labels on this message”, which is a
     * confident statement about a thing nobody was asked — and the Label sheet
     * would then offer to ADD a label the message already has, twice.
     */
    private var assignments: Map<String, Set<String>>? = null

    /** A box+query key. `others` and `archive` are separate keys even though a
     *  different router answers them, because their contents genuinely differ. */
    private fun key(box: MailBox, query: String?): String =
        box.apiValue + "|" + (query?.trim()?.lowercase() ?: "")

    suspend fun page(box: MailBox, query: String? = null): PageCache? = mutex.withLock {
        val hit = pages[key(box, query)] ?: return@withLock null
        // A cached page under a *different* term is not this page. Returning it
        // would show unfiltered mail beneath a search box that says otherwise.
        if ((hit.query?.trim()?.lowercase() ?: "") != (query?.trim()?.lowercase() ?: "")) null else hit
    }

    /**
     * Merge a fetched page. `replace` is true for page 1 (a refresh or a new
     * search), false for a later page appended to what is already shown.
     */
    suspend fun putPage(box: MailBox, query: String?, resp: MailInboxResponse, replace: Boolean) =
        mutex.withLock {
            val k = key(box, query)
            val prior = if (replace) null else pages[k]
            val merged = ((prior?.messages ?: emptyList()) + resp.messages).distinctBy { it.id }
            pages = pages + (k to PageCache(
                messages = merged,
                total = if (resp.total > 0) resp.total else prior?.total ?: 0,
                pages = (prior?.pages ?: 0) + 1,
                query = query?.trim()?.takeIf { it.isNotEmpty() },
                loadedAtMillis = System.currentTimeMillis(),
            ))
            if (!query.isNullOrBlank()) {
                val oldSearches = pages.entries
                    .filter { it.key != k && it.value.query != null }
                    .sortedByDescending { it.value.loadedAtMillis }
                    .drop(MAX_CACHED_SEARCHES - 1)
                if (oldSearches.isNotEmpty()) pages = pages - oldSearches.map { it.key }.toSet()
            }
            merged.size > (prior?.messages?.size ?: 0)
        }.also { scheduleSnapshotWrite() }

    suspend fun stats(): MailStatsResponse? = mutex.withLock { stats }
    suspend fun putStats(v: MailStatsResponse) = mutex.withLock { stats = v }.also { scheduleSnapshotWrite() }

    suspend fun labels(): List<MailLabelDto>? = mutex.withLock { labels }
    suspend fun putLabels(v: List<MailLabelDto>) = mutex.withLock { labels = v }.also { scheduleSnapshotWrite() }

    suspend fun aliases(): List<MailAliasDto>? = mutex.withLock { aliases }
    suspend fun putAliases(v: List<MailAliasDto>) = mutex.withLock { aliases = v }.also { scheduleSnapshotWrite() }

    suspend fun me(): MailMeResponse? = mutex.withLock { me }
    suspend fun putMe(v: MailMeResponse) = mutex.withLock { me = v }.also { scheduleSnapshotWrite() }

    /** Null until the first successful `GET /labels/assignments`. */
    suspend fun assignments(): Map<String, Set<String>>? = mutex.withLock { assignments }

    /** Replace the whole map: the route returns every pair the account has, so a
     *  successful read supersedes the previous answer completely rather than adding
     *  to it. Holding a stale pair alongside a fresh one would show a label the
     *  server has since un-tagged, in another box, on a row the user can see. */
    suspend fun putAssignments(rows: List<MailAssignmentDto>) = mutex.withLock {
        val built = mutableMapOf<String, MutableSet<String>>()
        rows.forEach { r ->
            if (r.messageId.isNotEmpty() && r.labelId.isNotEmpty()) {
                built.getOrPut(r.messageId) { mutableSetOf() }.add(r.labelId)
            }
        }
        assignments = built.toMap()
    }.also { scheduleSnapshotWrite() }

    /**
     * Patch one pair after a write the server accepted.
     *
     * Applied only on success, and only to the pair that was written: the label
     * routes answer with no body of their own (`{success:true}`), so there is
     * nothing to reconcile against, and re-reading the whole map after every tap
     * would make a routine label feel like a page load. If the map was never read,
     * this stays null — patching an unanswered map would invent a baseline.
     */
    suspend fun markAssigned(messageId: String, labelId: String) = mutex.withLock {
        val cur = assignments ?: return@withLock
        val next = cur.toMutableMap()
        next[messageId] = (next[messageId] ?: emptySet()) + labelId
        assignments = next
    }.also { scheduleSnapshotWrite() }

    suspend fun markUnassigned(messageId: String, labelId: String) = mutex.withLock {
        val cur = assignments ?: return@withLock
        val next = cur.toMutableMap()
        next[messageId] = (next[messageId] ?: emptySet()) - labelId
        assignments = next
    }.also { scheduleSnapshotWrite() }

    /** Drop a message's pairs when the message itself goes away, so a permanent
     *  delete cannot leave a ghost label keyed on an id nothing has any more. */
    suspend fun forgetAssignments(messageId: String) = mutex.withLock {
        assignments = assignments?.minus(messageId)
    }.also { scheduleSnapshotWrite() }

    /** Opened bodies, memoised by id. A body is fetched once: the list payload
     *  has none, and re-reading a payload to redraw a row is the over-fetch the
     *  shape was designed to avoid. */
    suspend fun body(id: String): MailMessageFullDto? = mutex.withLock { bodies.get(id) }

    suspend fun indexedAttachments(): List<IndexedAttachment> = mutex.withLock {
        attachmentsByMessage.values.flatten()
    }

    suspend fun indexedAttachment(id: String): IndexedAttachment? = mutex.withLock {
        attachmentsByMessage.values.firstNotNullOfOrNull { entries ->
            entries.firstOrNull { it.attachment.id == id }
        }
    }

    private fun cacheBody(message: MailMessageFullDto) {
        bodies.put(message.id, message)
        if (message.attachments.isEmpty()) {
            attachmentsByMessage.remove(message.id)
        } else {
            val subject = message.subject?.takeIf { it.isNotBlank() }
            attachmentsByMessage[message.id] = message.attachments.map { IndexedAttachment(it, subject) }
        }
    }

    suspend fun putBody(message: MailMessageFullDto) = mutex.withLock { cacheBody(message) }

    suspend fun putBodies(messages: List<MailMessageFullDto>): List<MailMessageFullDto> = mutex.withLock {
        messages.map { message ->
            val known = attachmentsByMessage[message.id]
            val merged = if (message.hasAttachments && message.attachments.isEmpty() && known != null) {
                message.copy(attachments = known.map { it.attachment })
            } else message
            cacheBody(merged)
            merged
        }
    }

    suspend fun forgetBody(id: String) = mutex.withLock {
        bodies.remove(id)
        attachmentsByMessage.remove(id)
        Unit
    }

    suspend fun forgetAttachment(attachmentId: String) = mutex.withLock {
        val messages = attachmentsByMessage.iterator()
        while (messages.hasNext()) {
            val entry = messages.next()
            val remaining = entry.value.filterNot { it.attachment.id == attachmentId }
            if (remaining.size == entry.value.size) continue
            bodies.remove(entry.key)
            if (remaining.isEmpty()) messages.remove() else entry.setValue(remaining)
        }
    }

    /**
     * Forget the alias list so the next visit re-reads it.
     *
     * Used after a create or a delete: the server composes the address from the
     * account's handle and domain (`grapmail-aliases.ts:188`), so the row the app
     * would build and the row the route wrote are two chances to disagree — and the
     * list carries no id to patch by.
     */
    suspend fun invalidateAliases() = mutex.withLock { aliases = null }

    /**
     * Apply a local flag change to every cached page, so starring or reading a
     * message shows immediately and survives a scroll back — without waiting for
     * a refetch that may not happen and may not succeed.
     */
    suspend fun patchFlags(
        id: String,
        read: Boolean? = null,
        starred: Boolean? = null,
    ) = mutex.withLock {
        bodies.get(id)?.let { message ->
            val updated = message.copy(isRead = read ?: message.isRead, isStarred = starred ?: message.isStarred)
            if (updated != message) bodies.put(id, updated)
        }
        pages = pages.mapValues { (_, cache) ->
            val index = cache.messages.indexOfFirst { it.id == id }
            if (index < 0) return@mapValues cache
            val message = cache.messages[index]
            val updated = message.copy(
                isRead = read ?: message.isRead,
                isStarred = starred ?: message.isStarred,
            )
            if (updated == message) cache else cache.copy(
                messages = cache.messages.toMutableList().apply { this[index] = updated },
            )
        }
    }.also { scheduleSnapshotWrite() }

    /** Drop a message from the cached pages after it has been filed elsewhere.
     *  The box it moved *to* is invalidated rather than inserted into, because
     *  the app cannot know that box's ordering without asking the server. */
    suspend fun removeEverywhere(id: String) = mutex.withLock {
        pages = pages.mapValues { (_, cache) ->
            val index = cache.messages.indexOfFirst { it.id == id }
            if (index < 0) cache else cache.copy(
                messages = cache.messages.toMutableList().apply { removeAt(index) },
            )
        }
    }.also { scheduleSnapshotWrite() }

    /** Forget one box so the next visit refetches it. Used after filing, where
     *  the destination's contents genuinely changed. */
    suspend fun invalidate(box: MailBox) = mutex.withLock {
        pages = pages.filterKeys { !it.startsWith(box.apiValue + "|") }
    }.also { scheduleSnapshotWrite() }

    suspend fun invalidateAllBoxes() = mutex.withLock {
        pages = emptyMap()
    }.also { scheduleSnapshotWrite() }

    suspend fun clear() = mutex.withLock {
        // Takes the same lock every mutator takes. It did not: `clear()` was a
        // plain function writing seven fields that coroutines on other dispatchers
        // mutate under the mutex, so a sign-out racing an in-flight `putPage` had no
        // happens-before edge with it and could leave a half-cleared cache — some
        // fields emptied, `me` still the previous account. The caller wraps this in
        // `NonCancellable`, which is where the "must not be cancelled" guarantee
        // actually belongs.
        pages = emptyMap()
        stats = null
        labels = null
        aliases = null
        me = null
        bodies.evictAll()
        attachmentsByMessage.clear()
        // Back to null, not to an empty map: the next account has not been asked
        // anything, and an empty map would claim its mail carries no labels.
        assignments = null
        // The snapshot dies with the session: a cached mailbox on disk is the
        // same cross-account leak the in-memory clear exists to prevent — the
        // first thing the next account on this device would see.
        appContext?.let {
            try { java.io.File(it.filesDir, SNAPSHOT_FILE).delete() } catch (_: Exception) {}
        }
    }
}
