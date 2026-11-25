package com.grapsee.grapmail.data

/**
 * The last security profile the hub loaded, kept for the process lifetime.
 *
 * The hub re-fetches every time it is opened — leaving for a row and coming
 * back is a fresh composition, so `remember` gives it nothing to hold. A
 * refresh that fails (rate limit, dropped request, a response that never
 * answered the two-step verdict) used to blank a screen that had been showing
 * correct data a second earlier, and the error state disables the two-step
 * switch: one unlucky request made the whole sector unusable until the person
 * found "Try again". The cache is what lets the hub keep the verdict it already
 * had and refresh quietly in the background.
 *
 * Sign-out clears it. A cached security state left behind would be handed to
 * the next account that signs in on this device.
 */
object SecurityProfileCache {

    private data class Entry(val sessionId: String, val profile: ProfileDto)

    @Volatile
    private var entry: Entry? = null

    fun read(sessionId: String): ProfileDto? = entry?.takeIf { it.sessionId == sessionId }?.profile

    fun publish(sessionId: String, profile: ProfileDto) {
        entry = Entry(sessionId, profile)
    }

    fun clear() {
        entry = null
        // Round 8: the Security Center's overview goes with the profile.
        SecurityOverviewCache.clear()
    }
}

// region Round 8 security — the Security Center's overview cache (spec §3.7, §4.3 B)

/**
 * The last `GET /security/overview` per account, kept in memory and on disk.
 *
 * On disk because the spec asks two things a process-lifetime cache cannot give:
 * the Security Center renders the last overview at once on a cold start, before
 * the refetch lands (§3.7 "Loading (with cache)"), and the mailbox nudge may
 * only show from a cache that is at most 24 h old (§4.3 B rule 2). Keyed by an
 * FNV-1a hash of the address, so the file never names the account and one
 * account's overview can never be read as another's.
 *
 * [SecurityProfileCache.clear] (sign-out, account removal) wipes it, memory and
 * disk: a cached security state left behind would outlive the person it
 * described. [version] ticks on every write so composables can observe it.
 */
object SecurityOverviewCache {

    data class Entry(val address: String, val overview: SecurityOverviewResponse, val fetchedAt: Long)

    @kotlinx.serialization.Serializable
    private data class Stored(val fetchedAt: Long, val overview: SecurityOverviewResponse)

    private const val PREFS = "grapmail-security-overview"
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    @Volatile
    private var appContext: android.content.Context? = null

    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version

    private fun prefs(context: android.content.Context) =
        context.applicationContext.also { appContext = it }
            .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    fun read(context: android.content.Context, address: String): Entry? {
        val key = accountKey(address)
        memory[key]?.let { return it }
        val raw = prefs(context).getString(key, null) ?: return null
        val stored = runCatching { json.decodeFromString(Stored.serializer(), raw) }.getOrNull() ?: return null
        return Entry(address, stored.overview, stored.fetchedAt).also { memory[key] = it }
    }

    fun publish(
        context: android.content.Context,
        address: String,
        overview: SecurityOverviewResponse,
        fetchedAt: Long = System.currentTimeMillis(),
    ) {
        val key = accountKey(address)
        memory[key] = Entry(address, overview, fetchedAt)
        runCatching {
            prefs(context).edit()
                .putString(key, json.encodeToString(Stored.serializer(), Stored(fetchedAt, overview)))
                .apply()
        }
        _version.value += 1
    }

    /**
     * The not-me result and a finished password change move the §3.6 flag
     * before the next overview arrives, so the banner appears (or goes) at once
     * rather than a refetch later. The cached fetch time is kept: this is a
     * local correction, not a fresher read.
     */
    fun setPasswordChangeRequired(context: android.content.Context, address: String, required: Boolean) {
        val held = read(context, address) ?: return
        val account = held.overview.account ?: SecurityOverviewAccountDto(address = address)
        if (account.passwordChangeRequired == required) return
        publish(context, address, held.overview.copy(account = account.copy(passwordChangeRequired = required)), held.fetchedAt)
    }

    fun clear() {
        memory.clear()
        appContext?.let { ctx ->
            runCatching {
                ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().clear().apply()
            }
        }
        _version.value += 1
    }

    /**
     * FNV-1a 32-bit over the UTF-8 bytes of the normalized address, as 8 lower
     * hex digits — the same hash and normalization as spec §1.6.2 (ASCII-only
     * lowercasing, so a Turkish-locale device keys the same account the same
     * way). Also the `<fnv1a(address)>` suffix of the §4.3/§4.4 local flags.
     */
    fun accountKey(address: String): String {
        val inner = address.substringAfter('<', address).substringBefore('>')
        val normalized = buildString {
            for (ch in inner.trim()) append(if (ch in 'A'..'Z') ch + 32 else ch)
        }
        var h = 0x811C9DC5.toInt()
        for (b in normalized.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xFF)
            h *= 0x01000193
        }
        return java.lang.Long.toHexString(h.toLong() and 0xFFFFFFFFL).padStart(8, '0')
    }
}

// endregion Round 8 security
