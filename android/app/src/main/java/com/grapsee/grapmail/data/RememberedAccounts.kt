package com.grapsee.grapmail.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AtomicFile
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.grapsee.grapmail.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.rememberedStore by preferencesDataStore("grapmail_remembered")

/**
 * The accounts this device remembers after they sign out, for the personal
 * welcome-back sign-in (round8-spec §2.4).
 *
 * ── WHAT IS STORED ─────────────────────────────────────────────────────────
 * Preferences DataStore `grapmail_remembered`, key `accounts`: a JSON array
 * of [Record] (version 1) — the canonical address, the display name, the
 * first name, the server avatar URL (an http URL only, for refreshing the
 * cache while signed in), the local avatar file name, when the account was
 * last used, whether it has a passkey (a HINT), and why it last left.
 * The cached avatar is a 176×176 JPEG (q80) in
 * `noBackupFilesDir/remembered_avatars/<sha256(address)>.jpg`.
 *
 * NEVER stored here: the password, the access or refresh token (those stay
 * sealed in [SessionStore]), the phone number, the recovery email. Nothing in
 * this file reads a credential except to call the profile / passkey / avatar
 * routes with the bearer the session store already holds.
 *
 * ── LIFETIME ───────────────────────────────────────────────────────────────
 * Written on every successful sign-in ([SessionStore.save] calls
 * [recordSignIn]); its sign-out reason is set when a session ends
 * ([SessionStore]'s removal path calls [markEnded]); cleared by "Remove from
 * this device", the sign-out sheet's "Also remove from this device", account
 * deletion, a blocked/deleted account answer, the 90-day prune, and clearing
 * the app's data. `android:allowBackup="false"` and the no-backup directory
 * mean nothing here survives an uninstall or travels to another device, so
 * Android needs no reinstall marker (iOS does, because its Keychain survives).
 * At most [MAX_RECORDS] records, newest first.
 */
object RememberedAccounts {

    const val MAX_RECORDS = 5
    private const val PRUNE_AFTER_MILLIS = 90L * 24 * 60 * 60 * 1000
    private const val AVATAR_PX = 176
    private const val AVATAR_QUALITY = 80
    private const val AVATAR_DIR = "remembered_avatars"
    /** A signed-in account's profile, passkeys and photo are re-read at most this often on foreground. */
    private const val ENRICH_EVERY_MILLIS = 6L * 60 * 60 * 1000

    private val KEY_ACCOUNTS = stringPreferencesKey("accounts")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(Record.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastEnriched = ConcurrentHashMap<String, Long>()
    @Volatile private var pruned = false

    /** Why a remembered account is signed out (§2.1). The wire value is what the record stores. */
    enum class SignOutReason(val wire: String) {
        Expired("expired"),
        SignedOut("signed_out"),
        Revoked("revoked");

        companion object {
            fun fromWire(value: String?): SignOutReason? = entries.firstOrNull { it.wire == value }
        }
    }

    /**
     * How a session ended, as the session layer sees it. [Blocked] (the
     * server answered an account-inactive / deleted state) is never stored:
     * it removes the record (§2.4 clearing rule 4).
     */
    enum class Ending { Expired, SignedOut, Revoked, Blocked }

    /** One remembered account, version 1 (§2.4). No credential, ever. */
    @Serializable
    data class Record(
        val v: Int = 1,
        val address: String,
        val displayName: String = "",
        val firstName: String = "",
        val avatarUrl: String? = null,
        val avatarCacheRef: String? = null,
        val lastUsedAt: String = "",
        val hasPasskey: Boolean = false,
        val lastSignOutReason: String? = null,
    ) {
        val reason: SignOutReason? get() = SignOutReason.fromWire(lastSignOutReason)
        val lastUsedMillis: Long
            get() = runCatching { Instant.parse(lastUsedAt).toEpochMilli() }.getOrDefault(0L)
    }

    /** What the sign-in entry should show (§2.1). */
    sealed class Entry {
        /** Nothing remembered (or the feature is off, or "Use another account" was tapped). */
        object None : Entry()

        /** Exactly one signed-out remembered account: straight to welcome-back. */
        data class One(val address: String) : Entry()

        /** Two or more: the account chooser first. */
        object Chooser : Entry()
    }

    // ── switches ───────────────────────────────────────────────────────────

    /** RemoteConfig `welcome_back`: on in debug, off in release until QA (§7). */
    fun enabled(): Boolean =
        com.grapsee.grapmail.logic.RemoteConfig.flag("welcome_back", BuildConfig.DEBUG)

    /** §2.1 condition 3: "Use another account" was tapped in this launch (process lifetime). */
    @Volatile
    var skippedThisLaunch: Boolean = false
        private set

    fun skipForThisLaunch() {
        skippedThisLaunch = true
    }

    // ── addresses ──────────────────────────────────────────────────────────

    /**
     * Canonical address (§1.6.2 normalize): the part inside `<…>` when
     * present, ASCII whitespace trimmed, ASCII A–Z lowercased only — never the
     * locale's lowercasing, so a Turkish-locale device keys the same account
     * the same way.
     */
    fun canonical(raw: String): String {
        var s = raw
        val open = s.indexOf('<')
        val close = s.lastIndexOf('>')
        if (open >= 0 && close > open) s = s.substring(open + 1, close)
        s = s.trim { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == '\u000C' }
        val out = StringBuilder(s.length)
        for (ch in s) out.append(if (ch in 'A'..'Z') (ch + 32) else ch)
        return out.toString()
    }

    // ── reads ──────────────────────────────────────────────────────────────

    /** Every remembered record, newest first. Prunes once per process before the first read. */
    fun records(context: Context): Flow<List<Record>> {
        val app = context.applicationContext
        return flow {
            ensurePruned(app)
            emitAll(app.rememberedStore.data.map { decode(it[KEY_ACCOUNTS]) }.distinctUntilChanged())
        }
    }

    suspend fun load(context: Context): List<Record> = records(context).first()

    /**
     * Remembered accounts with no stored session. Signed-in accounts stay in
     * the account switcher and never appear on the welcome-back screens.
     */
    suspend fun signedOut(context: Context): List<Record> {
        val signedIn = SessionStore.snapshotAll(context.applicationContext)
            .map { canonical(it.address) }.toSet()
        return load(context).filterNot { it.address in signedIn }
    }

    /** §2.1: which welcome-back screen, if any, a cold start or a route to sign-in shows. */
    suspend fun entry(context: Context): Entry {
        if (!enabled() || skippedThisLaunch) return Entry.None
        val candidates = signedOut(context)
        return when (candidates.size) {
            0 -> Entry.None
            1 -> Entry.One(candidates.first().address)
            else -> Entry.Chooser
        }
    }

    /** The cached avatar bytes for [record], or null to draw the initials disc. */
    suspend fun avatarBytes(context: Context, record: Record): ByteArray? {
        val ref = record.avatarCacheRef ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = File(avatarDir(context.applicationContext), ref)
                if (!file.isFile || file.length() !in 1L..(512L * 1024)) null
                else AtomicFile(file).readFully().takeIf(AvatarPhoto::isImage)
            }.getOrNull()
        }
    }

    // ── writes ─────────────────────────────────────────────────────────────

    /**
     * A sign-in completed for [address]: upsert the record (keeping what an
     * earlier sign-in learned), stamp it used now, clear the sign-out reason,
     * then refresh the profile, passkey hint and photo in the background.
     */
    suspend fun recordSignIn(context: Context, address: String, name: String) {
        val app = context.applicationContext
        val key = canonical(address)
        if (key.isEmpty()) return
        update(app) { list ->
            val existing = list.firstOrNull { it.address == key }
            val record = (existing ?: Record(address = key)).copy(
                displayName = name.trim().ifEmpty { existing?.displayName.orEmpty() },
                lastUsedAt = now(),
                lastSignOutReason = null,
            )
            listOf(record) + list.filterNot { it.address == key }
        }
        enrichAsync(app, key, force = true)
    }

    /**
     * A session for [address] ended. The record keeps its display data and
     * gains the reason the welcome-back banner reads; [Ending.Blocked] removes
     * it. An account signed in before this feature shipped has no record yet:
     * one is created from what the session store held ([name]), so its first
     * expiry still lands on a personal screen.
     */
    suspend fun markEnded(context: Context, address: String, name: String, ending: Ending) {
        val app = context.applicationContext
        val key = canonical(address)
        if (key.isEmpty()) return
        if (ending == Ending.Blocked) {
            forget(app, key)
            return
        }
        val reason = when (ending) {
            Ending.Expired -> SignOutReason.Expired
            Ending.Revoked -> SignOutReason.Revoked
            else -> SignOutReason.SignedOut
        }
        update(app) { list ->
            val existing = list.firstOrNull { it.address == key }
            val record = (existing ?: Record(address = key, displayName = name.trim(), lastUsedAt = now()))
                .copy(lastSignOutReason = reason.wire)
            if (existing == null) listOf(record) + list
            else list.map { if (it.address == key) record else it }
        }
    }

    /** "Remove from this device", the sign-out sheet's removal, account deletion: record and photo go. */
    suspend fun forget(context: Context, address: String) {
        val app = context.applicationContext
        val key = canonical(address)
        update(app) { list -> list.filterNot { it.address == key } }
        deleteAvatar(app, key)
        lastEnriched.remove(key)
    }

    /** The passkey hint, set after the Passkeys screen changes the list. */
    suspend fun setHasPasskey(context: Context, address: String, hasPasskey: Boolean) {
        val key = canonical(address)
        update(context.applicationContext) { list ->
            list.map { if (it.address == key) it.copy(hasPasskey = hasPasskey) else it }
        }
    }

    /** The account was foregrounded (switched to, app resumed): stamp it and refresh if stale. */
    fun touchAsync(context: Context, address: String) {
        val app = context.applicationContext
        val key = canonical(address)
        if (key.isEmpty()) return
        scope.launch {
            runCatching {
                update(app) { list ->
                    list.map { if (it.address == key) it.copy(lastUsedAt = now(), lastSignOutReason = null) else it }
                }
            }
            enrichAsync(app, key, force = false)
        }
    }

    /** [touchAsync] for whichever account is active now. Cheap; safe to call on every resume. */
    fun touchActiveAsync(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val active = SessionStore.snapshot(app) ?: return@launch
            touchAsync(app, active.address)
        }
    }

    /** Profile name or photo changed while signed in: refresh the record now. */
    fun refreshAsync(context: Context, address: String) = enrichAsync(context.applicationContext, canonical(address), force = true)

    // ── enrichment (signed in only) ────────────────────────────────────────

    private fun enrichAsync(app: Context, key: String, force: Boolean) {
        val nowMs = System.currentTimeMillis()
        val last = lastEnriched[key]
        if (!force && last != null && nowMs - last < ENRICH_EVERY_MILLIS) return
        lastEnriched[key] = nowMs
        scope.launch {
            try {
                enrich(app, key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort: the record keeps what it had; the next sign-in retries.
                lastEnriched.remove(key)
            }
        }
    }

    private suspend fun enrich(app: Context, key: String) {
        val session = SessionStore.snapshotAll(app).firstOrNull { canonical(it.address) == key } ?: return
        // A stale bearer would only start a refresh race from a background
        // task; the next foreground after rotation does this instead.
        if (!session.unexpired) return
        val token = session.token
        val profile = try {
            Api.profile(token).profile
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val passkeys = try {
            Api.listPasskeys(token).passkeys.size
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val current = load(app).firstOrNull { it.address == key } ?: return

        var avatarRef = current.avatarCacheRef
        var avatarUrl = current.avatarUrl
        if (profile != null) {
            val remote = profile.avatarUrl?.takeUnless { it.isBlank() }
            if (remote == null) {
                // The profile says there is no photo: the disc is the truth now.
                deleteAvatar(app, key)
                avatarRef = null
                avatarUrl = null
            } else {
                val storable = remote.takeIf { it.startsWith("http", ignoreCase = true) && it.length <= 2048 }
                val cached = avatarRef?.let { File(avatarDir(app), it).isFile } == true
                if (!cached || storable == null || storable != current.avatarUrl) {
                    val bytes = AvatarPhoto.readCached(app, session)
                        ?: AvatarPhoto.inline(remote)
                        ?: try {
                            Api.avatarBytes(token)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    val written = bytes?.let { writeAvatar(app, key, it) }
                    if (written != null) avatarRef = written
                }
                avatarUrl = storable
            }
        }
        val display = profile?.let { it.displayName?.trim()?.takeIf(String::isNotEmpty) ?: it.name.trim() }
            ?.takeIf(String::isNotEmpty)
        val first = profile?.firstName?.trim()
        update(app) { list ->
            list.map {
                if (it.address != key) it
                else it.copy(
                    displayName = display ?: it.displayName,
                    firstName = first ?: it.firstName,
                    avatarUrl = avatarUrl,
                    avatarCacheRef = avatarRef,
                    hasPasskey = passkeys?.let { count -> count > 0 } ?: it.hasPasskey,
                )
            }
        }
    }

    // ── storage ────────────────────────────────────────────────────────────

    private fun decode(raw: String?): List<Record> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
            .filter { it.v == 1 && it.address.isNotBlank() }
            .sortedByDescending { it.lastUsedMillis }
    }

    /**
     * Read-modify-write in one DataStore transaction, then the §2.4 limits:
     * newest first, at most [MAX_RECORDS]; evicted records lose their photo.
     */
    private suspend fun update(app: Context, change: (List<Record>) -> List<Record>) {
        var evicted = emptyList<String>()
        app.rememberedStore.edit { prefs ->
            val before = decode(prefs[KEY_ACCOUNTS])
            val changed = change(before)
                .distinctBy { it.address }
                .sortedByDescending { it.lastUsedMillis }
            val kept = changed.take(MAX_RECORDS)
            evicted = (before.map { it.address } + changed.map { it.address })
                .distinct()
                .filter { address -> kept.none { it.address == address } }
            if (kept.isEmpty()) prefs.remove(KEY_ACCOUNTS)
            else prefs[KEY_ACCOUNTS] = json.encodeToString(listSerializer, kept)
        }
        evicted.forEach { deleteAvatar(app, it) }
    }

    /** §2.4: a record unused for 90 days is pruned at launch; orphaned photos go with it. */
    private suspend fun ensurePruned(app: Context) {
        if (pruned) return
        pruned = true
        runCatching {
            val cutoff = System.currentTimeMillis() - PRUNE_AFTER_MILLIS
            update(app) { list -> list.filter { it.lastUsedMillis >= cutoff } }
            val keep = decode(app.rememberedStore.data.first()[KEY_ACCOUNTS])
                .mapNotNull { it.avatarCacheRef }.toSet()
            withContext(Dispatchers.IO) {
                avatarDir(app).listFiles()?.forEach { if (it.name !in keep) it.delete() }
            }
        }
    }

    private fun now(): String = Instant.now().toString()

    private fun avatarDir(app: Context) = File(app.noBackupFilesDir, AVATAR_DIR)

    private fun avatarName(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".jpg"

    private suspend fun deleteAvatar(app: Context, key: String) = withContext(Dispatchers.IO) {
        runCatching { File(avatarDir(app), avatarName(key)).delete() }
    }

    /** Square-crop, scale to 176×176, flatten onto white (JPEG has no alpha), q80. Returns the file name. */
    private suspend fun writeAvatar(app: Context, key: String, bytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val jpeg = squareJpeg(bytes) ?: return@runCatching null
                val dir = avatarDir(app).apply { mkdirs() }
                val name = avatarName(key)
                val file = AtomicFile(File(dir, name))
                val stream = file.startWrite()
                try {
                    stream.write(jpeg)
                    file.finishWrite(stream)
                } catch (e: Exception) {
                    file.failWrite(stream)
                    throw e
                }
                name
            }.getOrNull()
        }

    private fun squareJpeg(bytes: ByteArray): ByteArray? {
        val decoded = AvatarPhoto.thumbnail(bytes, AVATAR_PX) ?: return null
        val out = Bitmap.createBitmap(AVATAR_PX, AVATAR_PX, Bitmap.Config.ARGB_8888)
        try {
            val side = minOf(decoded.width, decoded.height)
            val x = (decoded.width - side) / 2
            val y = (decoded.height - side) / 2
            Canvas(out).apply {
                drawColor(android.graphics.Color.WHITE)
                drawBitmap(
                    decoded,
                    Rect(x, y, x + side, y + side),
                    Rect(0, 0, AVATAR_PX, AVATAR_PX),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                )
            }
            return ByteArrayOutputStream().use { stream ->
                if (!out.compress(Bitmap.CompressFormat.JPEG, AVATAR_QUALITY, stream)) null
                else stream.toByteArray()
            }
        } finally {
            out.recycle()
            decoded.recycle()
        }
    }
}
