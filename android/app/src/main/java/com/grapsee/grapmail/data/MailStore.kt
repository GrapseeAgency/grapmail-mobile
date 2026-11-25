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
    // Recent reader bodies use a separate, size-limited cache.

    private const val SNAPSHOT_VERSION = 2

    @Serializable
    private data class DiskSnapshot(
        val version: Int,
        val sessionId: String,
        val pages: Map<String, PageCache>,
        val stats: MailStatsResponse? = null,
        val labels: List<MailLabelDto>? = null,
        val aliases: List<MailAliasDto>? = null,
        val me: MailMeResponse? = null,
        val assignments: Map<String, Set<String>>? = null,
        val profile: ProfileDto? = null,
    )

    private const val SNAPSHOT_FILE = "mailbox-snapshot.json"
    private val snapshotJson = Json { ignoreUnknownKeys = true }
    private val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val snapshotWriter = Mutex()
    private var snapshotGeneration = 0L
    private val snapshotWriteScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var appContext: android.content.Context? = null
    private val diskReady = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Wait until the one cold-start snapshot read has either loaded or declined. */
    suspend fun awaitDiskReady() {
        diskReady.await()
    }

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
            try {
                val ctx = appContext ?: return@launch
                val text = try {
                    java.io.File(ctx.filesDir, SNAPSHOT_FILE).takeIf { it.exists() }?.readText()
                } catch (_: Exception) { null } ?: return@launch
                val snap = try {
                    snapshotJson.decodeFromString(DiskSnapshot.serializer(), text)
                } catch (_: Exception) { null } ?: return@launch
                if (snap.version != SNAPSHOT_VERSION) return@launch
                mutex.withLock {
                    if (!SessionStore.isCurrent(ctx, snap.sessionId)) return@withLock
                    if (profile == null) profile = snap.profile
                    if (pages.isEmpty() && stats == null && me == null && assignments == null) {
                        pages = snap.pages
                        stats = snap.stats
                        labels = snap.labels
                        aliases = snap.aliases
                        me = snap.me
                        assignments = snap.assignments
                    }
                }
            } finally {
                diskReady.complete(Unit)
            }
        }
    }

    /**
     * Coalesce write bursts: a page load touches putPage + putStats + putMe;
     * one write 1.5s after the burst settles is one disk tick, not three.
     */
    private fun scheduleSnapshotWrite() {
        if (!snapshotWriteScheduled.compareAndSet(false, true)) return
        snapshotScope.launch {
            try {
                delay(1_500)
            } finally {
                snapshotWriteScheduled.set(false)
            }
            writeSnapshotNow()
        }
    }

    suspend fun flushSnapshot() = kotlinx.coroutines.withContext(Dispatchers.IO) { writeSnapshotNow() }

    private suspend fun writeSnapshotNow() = snapshotWriter.withLock writer@{
        val ctx = appContext ?: return@writer
        val session = SessionStore.snapshot(ctx) ?: return@writer
        val (generation, snap) = mutex.withLock {
            snapshotGeneration to DiskSnapshot(
                version = SNAPSHOT_VERSION,
                sessionId = session.id,
                pages = pages.filterValues { it.query == null }.mapValues { (_, page) ->
                    page.copy(messages = page.messages.take(25), pages = minOf(page.pages, 1))
                },
                stats = stats,
                labels = labels,
                aliases = aliases,
                me = me,
                assignments = assignments,
                profile = profile,
            )
        }
        val encoded = try {
            snapshotJson.encodeToString(DiskSnapshot.serializer(), snap)
        } catch (_: Exception) {
            return@writer
        }
        mutex.withLock commit@{
            if (generation != snapshotGeneration || !SessionStore.isCurrent(ctx, session.id)) return@commit
            try {
                // Atomic by rename: a process death mid-write leaves the
                // previous snapshot intact rather than a truncated file.
                val tmp = java.io.File(ctx.filesDir, "$SNAPSHOT_FILE.tmp")
                tmp.writeText(encoded)
                val dst = java.io.File(ctx.filesDir, SNAPSHOT_FILE)
                if (!tmp.renameTo(dst)) {
                    tmp.delete()
                }
            } catch (_: Exception) {
                // The snapshot is a boost, never a duty: a failed write costs
                // one cold boot its warm start, and nothing else.
            }
        }
    }

    private var profile: ProfileDto? = null

    suspend fun cachedProfile(context: android.content.Context, sessionId: String): ProfileDto? {
        awaitDiskReady()
        return mutex.withLock { profile.takeIf { SessionStore.isCurrent(context, sessionId) } }
    }

    suspend fun putProfile(context: android.content.Context, sessionId: String, value: ProfileDto) {
        val accepted = mutex.withLock {
            if (!SessionStore.isCurrent(context, sessionId)) return@withLock false
            profile = value
            true
        }
        if (accepted) scheduleSnapshotWrite()
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
    // Thread metadata only. The payload remains in [bodies], whose byte-cost
    // limit controls memory. This bounded id index makes switching back to a
    // recent conversation a memory hit without duplicating message bodies.
    @Serializable
    data class ThreadPage(
        val messages: List<MailMessageFullDto>,
        val nextCursor: String?,
        val hasMore: Boolean,
    )
    private data class ThreadIndex(
        val messageIds: List<String>,
        val loadedAtMillis: Long,
        val nextCursor: String?,
        val hasMore: Boolean,
    )
    private val threadMessageIds = linkedMapOf<String, ThreadIndex>()
    private const val MAX_CACHED_THREADS = 24
    private const val THREAD_FRESHNESS_MS = 30_000L

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
    suspend fun putPage(box: MailBox, query: String?, resp: MailInboxResponse, replace: Boolean,
                        sessionContext: android.content.Context? = null, sessionId: String? = null,
                        refreshStartedAt: Long? = null) =
        mutex.withLock {
            if (sessionId != null && (sessionContext == null || !SessionStore.isCurrent(sessionContext, sessionId)))
                return@withLock false
            val k = key(box, query)
            if (refreshStartedAt != null && (pages[k]?.let {
                it.loadedAtMillis >= refreshStartedAt
            } == true)) return@withLock false
            val existing = pages[k]
            if (refreshStartedAt != null && existing != null && existing.pages > 1 &&
                existing.total == resp.total && resp.messages.isNotEmpty() &&
                existing.messages.take(resp.messages.size).map { it.id } == resp.messages.map { it.id }) {
                pages = pages + (k to existing.copy(messages = resp.messages + existing.messages.drop(resp.messages.size),
                    loadedAtMillis = System.currentTimeMillis()))
                return@withLock true
            }
            val prior = if (replace) null else pages[k]
            val merged = ((prior?.messages ?: emptyList()) + resp.messages).distinctBy { it.id }
            pages = pages + (k to PageCache(
                messages = merged,
                total = resp.total,
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
            resp.messages.isNotEmpty()
        }.also { scheduleSnapshotWrite() }

    suspend fun stats(): MailStatsResponse? = mutex.withLock { stats }
    suspend fun putStats(v: MailStatsResponse, sessionContext: android.content.Context? = null, sessionId: String? = null) =
        mutex.withLock {
            if (sessionId != null && (sessionContext == null || !SessionStore.isCurrent(sessionContext, sessionId)))
                return@withLock false
            stats = v
            true
        }.also { if (it) scheduleSnapshotWrite() }

    suspend fun labels(): List<MailLabelDto>? = mutex.withLock { labels }
    suspend fun putLabels(v: List<MailLabelDto>, context: android.content.Context? = null, sessionId: String? = null) = mutex.withLock {
        if (sessionId != null && (context == null || !SessionStore.isCurrent(context, sessionId))) return@withLock false
        labels = v; true
    }.also { if (it) scheduleSnapshotWrite() }

    suspend fun aliases(): List<MailAliasDto>? = mutex.withLock { aliases }
    suspend fun putAliases(v: List<MailAliasDto>, context: android.content.Context? = null, sessionId: String? = null) = mutex.withLock {
        if (sessionId != null && (context == null || !SessionStore.isCurrent(context, sessionId))) return@withLock false
        aliases = v; true
    }.also { if (it) scheduleSnapshotWrite() }

    suspend fun me(): MailMeResponse? = mutex.withLock { me }
    suspend fun putMe(v: MailMeResponse, context: android.content.Context? = null, sessionId: String? = null) = mutex.withLock {
        if (sessionId != null && (context == null || !SessionStore.isCurrent(context, sessionId))) return@withLock false
        me = v; true
    }.also { if (it) scheduleSnapshotWrite() }

    /** Null until the first successful `GET /labels/assignments`. */
    suspend fun assignments(): Map<String, Set<String>>? = mutex.withLock { assignments }

    /** Replace the whole map: the route returns every pair the account has, so a
     *  successful read supersedes the previous answer completely rather than adding
     *  to it. Holding a stale pair alongside a fresh one would show a label the
     *  server has since un-tagged, in another box, on a row the user can see. */
    suspend fun putAssignments(rows: List<MailAssignmentDto>, context: android.content.Context? = null, sessionId: String? = null) = mutex.withLock {
        if (sessionId != null && (context == null || !SessionStore.isCurrent(context, sessionId))) return@withLock false
        val built = mutableMapOf<String, MutableSet<String>>()
        rows.forEach { r ->
            if (r.messageId.isNotEmpty() && r.labelId.isNotEmpty()) {
                built.getOrPut(r.messageId) { mutableSetOf() }.add(r.labelId)
            }
        }
        assignments = built.toMap()
        true
    }.also { if (it) scheduleSnapshotWrite() }

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

    private fun notificationDirectory(context: android.content.Context) =
        java.io.File(context.noBackupFilesDir, "mail-notifications")

    private fun notificationFile(context: android.content.Context, id: String, sessionId: String): java.io.File {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$sessionId:$id".toByteArray())
        return java.io.File(notificationDirectory(context), digest.joinToString("") { "%02x".format(it) })
    }

    suspend fun saveNotificationMessage(
        context: android.content.Context, message: MailMessageFullDto, sessionId: String,
    ) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!SessionStore.isCurrent(context, sessionId)) return@withLock
            cacheBody(message)
            val encoded = snapshotJson.encodeToString(MailMessageFullDto.serializer(), message).toByteArray()
            if (encoded.size > 512 * 1024) return@withLock
            runCatching {
                val directory = notificationDirectory(context)
                directory.mkdirs()
                val file = android.util.AtomicFile(notificationFile(context, message.id, sessionId))
                val stream = file.startWrite()
                try {
                    stream.write(encoded)
                    file.finishWrite(stream)
                } catch (error: java.io.IOException) {
                    file.failWrite(stream)
                    throw error
                }
                directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                    ?.drop(64)?.forEach { it.delete() }
            }.onFailure { android.util.Log.w("GrapMailNotify", "Preview cache write failed: ${it.javaClass.simpleName}") }
        }
    }

    suspend fun notificationMessage(
        context: android.content.Context, id: String, sessionId: String,
    ): MailMessageFullDto? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!SessionStore.isCurrent(context, sessionId)) return@withLock null
            val file = notificationFile(context, id, sessionId)
            if (System.currentTimeMillis() - file.lastModified() > 7 * 24 * 60 * 60 * 1000L) return@withLock null
            runCatching {
                val bytes = android.util.AtomicFile(file).readFully()
                if (bytes.size > 512 * 1024) return@withLock null
                snapshotJson.decodeFromString(MailMessageFullDto.serializer(), bytes.decodeToString())
                    .takeIf { it.id == id }?.also(::cacheBody)
            }.getOrNull()
        }
    }

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

    suspend fun putBody(message: MailMessageFullDto, sessionContext: android.content.Context? = null, sessionId: String? = null) = mutex.withLock {
        if (sessionId != null && (sessionContext == null || !SessionStore.isCurrent(sessionContext, sessionId))) return@withLock
        cacheBody(message)
    }

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

    private suspend fun memoryThread(id: String): ThreadPage? = mutex.withLock {
        val index = threadMessageIds.remove(id) ?: return@withLock null
        val messages = index.messageIds.mapNotNull(bodies::get)
        if (messages.size != index.messageIds.size) return@withLock null
        // LinkedHashMap insertion order is the recency order: remove/reinsert
        // moves the hit to the back without a second data structure.
        threadMessageIds[id] = index
        ThreadPage(messages, index.nextCursor, index.hasMore)
    }

    suspend fun thread(id: String): ThreadPage? {
        memoryThread(id)?.let { return it }
        val context = appContext ?: return null
        val session = SessionStore.snapshot(context) ?: return null
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!SessionStore.isCurrent(context, session.id)) return@withLock null
                val file = threadFile(context, id, session.id)
                if (file.length() !in 1L..524288L) return@withLock null
                runCatching { snapshotJson.decodeFromString(ThreadPage.serializer(), file.readText()) }.getOrNull()
            }
        }
    }

    private fun threadFile(context: android.content.Context, id: String, sessionId: String) =
        java.io.File(java.io.File(context.noBackupFilesDir, "mail-threads"), notificationFile(context, id, sessionId).name)

    suspend fun putThread(
        id: String,
        messages: List<MailMessageFullDto>,
        nextCursor: String?,
        hasMore: Boolean,
        sessionContext: android.content.Context? = null,
        sessionId: String? = null,
    ): ThreadPage = kotlinx.coroutines.withContext(Dispatchers.IO) { mutex.withLock {
            if (sessionId != null && (sessionContext == null || !SessionStore.isCurrent(sessionContext, sessionId)))
                return@withLock ThreadPage(messages, nextCursor, hasMore)
            val merged = messages.map { message ->
                val known = attachmentsByMessage[message.id]
                val row = if (message.hasAttachments && message.attachments.isEmpty() && known != null) {
                    message.copy(attachments = known.map { it.attachment })
                } else message
                cacheBody(row)
                row
            }
            threadMessageIds.remove(id)
            threadMessageIds[id] = ThreadIndex(
                merged.map { it.id }, System.currentTimeMillis(), nextCursor, hasMore,
            )
            while (threadMessageIds.size > MAX_CACHED_THREADS) {
                threadMessageIds.remove(threadMessageIds.keys.first())
            }
            val page = ThreadPage(merged, nextCursor, hasMore)
            if (sessionContext != null && sessionId != null) runCatching {
                val file = threadFile(sessionContext, id, sessionId)
                val data = snapshotJson.encodeToString(ThreadPage.serializer(), page).toByteArray()
                if (data.size <= 512 * 1024) {
                    file.parentFile?.mkdirs()
                    val atomic = android.util.AtomicFile(file)
                    val stream = atomic.startWrite()
                    try { stream.write(data); atomic.finishWrite(stream) }
                    catch (error: java.io.IOException) { atomic.failWrite(stream); throw error }
                    file.parentFile?.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_CACHED_THREADS)?.forEach { it.delete() }
                } else file.delete()
            }
            page
        } }

    suspend fun forgetBody(id: String) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        mutex.withLock {
            bodies.remove(id)
            attachmentsByMessage.remove(id)
            appContext?.let { context ->
                evictDiskEntries(context, { it.id == id }, includeBodies = false)
                SessionStore.snapshot(context)?.let { session ->
                    android.util.AtomicFile(notificationFile(context, id, session.id)).delete()
                }
            }
        }
    }

    private fun evictDiskEntries(context: android.content.Context, matches: (MailMessageFullDto) -> Boolean, includeBodies: Boolean) {
        java.io.File(context.noBackupFilesDir, "mail-threads").listFiles()?.forEach { file ->
            if (runCatching { snapshotJson.decodeFromString(ThreadPage.serializer(), file.readText()).messages.any(matches) }
                    .getOrDefault(true)) file.delete()
        }
        if (includeBodies) notificationDirectory(context).listFiles()?.forEach { file ->
            if (runCatching { matches(snapshotJson.decodeFromString(MailMessageFullDto.serializer(), file.readText())) }
                    .getOrDefault(true)) file.delete()
        }
    }

    suspend fun forgetAttachment(attachmentId: String) = kotlinx.coroutines.withContext(Dispatchers.IO) { mutex.withLock {
        appContext?.let { context ->
            evictDiskEntries(context, { message -> message.attachments.any { it.id == attachmentId } }, includeBodies = true)
        }
        val messages = attachmentsByMessage.iterator()
        while (messages.hasNext()) {
            val entry = messages.next()
            val remaining = entry.value.filterNot { it.attachment.id == attachmentId }
            if (remaining.size == entry.value.size) continue
            bodies.remove(entry.key)
            if (remaining.isEmpty()) messages.remove() else entry.setValue(remaining)
        }
    } }

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
        sessionContext: android.content.Context? = null,
        sessionId: String? = null,
    ) = mutex.withLock {
        if (sessionId != null && (sessionContext == null || !SessionStore.isCurrent(sessionContext, sessionId)))
            return@withLock
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

    /** Remove a filed row only from the box currently being viewed.
     *
     * Archive remains in All Mail and a flagged message remains in Starred, so
     * deleting it from every cached page made those derived views lie. The
     * model invalidates overlapping/destination boxes; this patches the source
     * box immediately and rebases its exact total. */
    suspend fun remove(id: String, from: MailBox,
                       sessionContext: android.content.Context? = null, sessionId: String? = null) = mutex.withLock {
        if (sessionId != null && (sessionContext == null || !SessionStore.isCurrent(sessionContext, sessionId)))
            return@withLock
        val prefix = from.apiValue + "|"
        pages = pages.mapValues { (key, cache) ->
            if (!key.startsWith(prefix)) return@mapValues cache
            val index = cache.messages.indexOfFirst { it.id == id }
            if (index < 0) cache else cache.copy(
                messages = cache.messages.toMutableList().apply { removeAt(index) },
                total = maxOf(0, cache.total - 1),
            )
        }
    }.also { scheduleSnapshotWrite() }

    /** Forget one box so the next visit refetches it. Used after filing, where
     *  the destination's contents genuinely changed. */
    suspend fun invalidate(box: MailBox, sessionContext: android.content.Context? = null, sessionId: String? = null) = mutex.withLock {
        if (sessionId != null && (sessionContext == null || !SessionStore.isCurrent(sessionContext, sessionId)))
            return@withLock
        pages = pages.filterKeys { !it.startsWith(box.apiValue + "|") }
    }.also { scheduleSnapshotWrite() }

    suspend fun invalidateAllBoxes(sessionContext: android.content.Context? = null, sessionId: String? = null) = mutex.withLock {
        if (sessionId != null && (sessionContext == null || !SessionStore.isCurrent(sessionContext, sessionId)))
            return@withLock
        pages = emptyMap()
    }.also { scheduleSnapshotWrite() }

    suspend fun clear() = mutex.withLock {
        snapshotGeneration++
        // Takes the same lock every mutator takes. It did not: `clear()` was a
        // plain function writing seven fields that coroutines on other dispatchers
        // mutate under the mutex, so a sign-out racing an in-flight `putPage` had no
        // happens-before edge with it and could leave a half-cleared cache — some
        // fields emptied, `me` still the previous account. The caller wraps this in
        // `NonCancellable`, which is where the "must not be cancelled" guarantee
        // actually belongs.
        pages = emptyMap()
        profile = null
        stats = null
        labels = null
        aliases = null
        me = null
        bodies.evictAll()
        threadMessageIds.clear()
        attachmentsByMessage.clear()
        // Back to null, not to an empty map: the next account has not been asked
        // anything, and an empty map would claim its mail carries no labels.
        assignments = null
        // The snapshot dies with the session: a cached mailbox on disk is the
        // same cross-account leak the in-memory clear exists to prevent — the
        // first thing the next account on this device would see.
        appContext?.let {
            try {
                java.io.File(it.filesDir, SNAPSHOT_FILE).delete()
                notificationDirectory(it).deleteRecursively()
                java.io.File(it.noBackupFilesDir, "mail-threads").deleteRecursively()
            } catch (_: Exception) {}
        }
    }
}
