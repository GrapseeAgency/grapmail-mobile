package com.grapsee.grapmail.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The mailbox's process-lifetime cache: the last page of each box, the last
 * stats, the last labels, and the message bodies that were opened.
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

    private var pages: Map<String, PageCache> = emptyMap()
    private var stats: MailStatsResponse? = null
    private var labels: List<MailLabelDto>? = null
    private var aliases: List<MailAliasDto>? = null
    private var me: MailMeResponse? = null
    private var bodies: Map<String, MailMessageFullDto> = emptyMap()

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
            val merged = (prior?.messages ?: listOf()) + resp.messages
            pages = pages + (k to PageCache(
                messages = merged.distinctBy { it.id },
                total = if (resp.total > 0) resp.total else prior?.total ?: 0,
                pages = (prior?.pages ?: 0) + 1,
                query = query?.trim()?.takeIf { it.isNotEmpty() },
                loadedAtMillis = System.currentTimeMillis(),
            ))
        }

    suspend fun stats(): MailStatsResponse? = mutex.withLock { stats }
    suspend fun putStats(v: MailStatsResponse) = mutex.withLock { stats = v }

    suspend fun labels(): List<MailLabelDto>? = mutex.withLock { labels }
    suspend fun putLabels(v: List<MailLabelDto>) = mutex.withLock { labels = v }

    suspend fun aliases(): List<MailAliasDto>? = mutex.withLock { aliases }
    suspend fun putAliases(v: List<MailAliasDto>) = mutex.withLock { aliases = v }

    suspend fun me(): MailMeResponse? = mutex.withLock { me }
    suspend fun putMe(v: MailMeResponse) = mutex.withLock { me = v }

    /** Opened bodies, memoised by id. A body is fetched once: the list payload
     *  has none, and re-reading a payload to redraw a row is the over-fetch the
     *  shape was designed to avoid. */
    suspend fun body(id: String): MailMessageFullDto? = mutex.withLock { bodies[id] }

    suspend fun putBody(v: MailMessageFullDto) = mutex.withLock {
        bodies = bodies + (v.id to v)
    }

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
        pages = pages.mapValues { (_, cache) ->
            cache.copy(messages = cache.messages.map { m ->
                if (m.id != id) m else m.copy(
                    isRead = read ?: m.isRead,
                    isStarred = starred ?: m.isStarred,
                )
            })
        }
    }

    /** Drop a message from the cached pages after it has been filed elsewhere.
     *  The box it moved *to* is invalidated rather than inserted into, because
     *  the app cannot know that box's ordering without asking the server. */
    suspend fun removeEverywhere(id: String) = mutex.withLock {
        pages = pages.mapValues { (_, cache) ->
            cache.copy(messages = cache.messages.filterNot { it.id == id })
        }
    }

    /** Forget one box so the next visit refetches it. Used after filing, where
     *  the destination's contents genuinely changed. */
    suspend fun invalidate(box: MailBox) = mutex.withLock {
        pages = pages.filterKeys { !it.startsWith(box.apiValue + "|") }
    }

    suspend fun invalidateAllBoxes() = mutex.withLock {
        pages = emptyMap()
        stats = null
        bodies = emptyMap()
    }

    fun clear() {
        // Not a suspend: SessionStore.clear() is on the sign-out path and a
        // cancelled coroutine there would leave the next account reading this
        // one's mail.
        pages = emptyMap()
        stats = null
        labels = null
        aliases = null
        me = null
        bodies = emptyMap()
    }
}
