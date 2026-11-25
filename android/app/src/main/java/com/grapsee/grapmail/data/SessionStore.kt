package com.grapsee.grapmail.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.sessionStore by preferencesDataStore("grapmail_session")

/**
 * Session persistence: every signed-in account's tokens are encrypted with an
 * Android Keystore AES-GCM key before they touch disk, as ONE sealed blob that
 * carries all of them plus which address is active. Multi-account is the
 * point: switching accounts swaps the active record without touching the
 * others, and a sign-in upserts into the same blob.
 *
 * Both tokens are the gateway's JWT pair now, not the `st1` session id older
 * builds stored: the mailbox API verifies the JWT's signature and `jti`, and
 * refuses anything else. Tokens are ciphertext on disk, never plaintext —
 * matching the security posture of the web client's storage.
 */
// Not a data class: diagnostics must never print credentials. Expiry stays as
// the instant, not a boolean captured at read time, so a process-lifetime cache
// cannot keep saying an hour-old access token is fresh after the hour passes.
class SessionSnapshot(
    val id: String,
    val token: String,
    val address: String,
    private val expiresAtMillis: Long,
    /** This account's own refresh token — switching accounts refreshes from it. */
    val refreshToken: String? = null,
) {
    val unexpired: Boolean get() = expiresAtMillis <= 0L || expiresAtMillis > System.currentTimeMillis()

    /** Credentials must never surface through an accidental toString. */
    override fun toString(): String = "SessionSnapshot(id=$id, address=$address)"
}

object SessionStore {
    private val writes = Mutex()
    private val KEY_SESSION = stringPreferencesKey("session_id")

    // Every authenticated request reads this instead of re-decrypting DataStore.
    // Every writer below updates or clears it while holding [writes], so a
    // sign-out cannot leave a usable cached bearer behind.
    @Volatile private var cachedSession: SessionSnapshot? = null
    @Volatile private var sessionCacheKnown = false

    private fun sessionId(prefs: Preferences): String? =
        if (prefs[KEY_TOKEN] == null) null else prefs[KEY_SESSION] ?: prefs[KEY_IV]

    fun sessionIds(context: Context) = context.sessionStore.data.map(::sessionId).distinctUntilChanged()

    // ── multi-account blob ──────────────────────────────────────────────────

    @Serializable
    private data class StoredSession(
        val id: String,
        val address: String,
        val name: String,
        val expiresAt: Long,
        val token: String,
        val refreshToken: String? = null,
    )

    @Serializable
    private data class SessionBlob(
        val sessions: List<StoredSession>,
        val activeAddress: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val KEY_BLOB = stringPreferencesKey("sessions_blob_enc")
    private val KEY_BLOB_IV = stringPreferencesKey("sessions_blob_iv")
    private val KEY_ACTIVE = stringPreferencesKey("active_address")

    // Legacy single-session keys — the migration source on first access, and
    // removed whenever the blob is written back.
    private val KEY_TOKEN = stringPreferencesKey("token_enc")
    private val KEY_IV = stringPreferencesKey("token_iv")
    private val KEY_REFRESH = stringPreferencesKey("refresh_enc")
    private val KEY_REFRESH_IV = stringPreferencesKey("refresh_iv")
    private val KEY_ADDRESS = stringPreferencesKey("address")
    private val KEY_NAME = stringPreferencesKey("name")
    private val KEY_EXPIRES = stringPreferencesKey("expires_at")
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "grapmail_session_key"

    private var cachedKey: SecretKey? = null

    @Synchronized
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let {
            cachedKey = it
            return it
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey().also { cachedKey = it }
    }

    // AndroidKeyStore AES-GCM keys are random-IV by default: the cipher must
    // generate the IV itself (init without a spec) and it is read back with
    // cipher.iv. Supplying a caller-generated IV to ENCRYPT_MODE throws
    // InvalidAlgorithmParameterException on every save — the crash that took
    // down sign-in.
    private fun seal(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray())
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP) to
            android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP)
    }

    private fun open(encB64: String?, ivB64: String?): String? {
        if (encB64 == null || ivB64 == null) return null
        return try {
            val encrypted = android.util.Base64.decode(encB64, android.util.Base64.NO_WRAP)
            val iv = android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted))
        } catch (_: Exception) {
            null
        }
    }

    /** Decode the blob, migrating the legacy single-session fields on first use. */
    private fun readBlob(prefs: Preferences): SessionBlob? {
        val decoded = open(prefs[KEY_BLOB], prefs[KEY_BLOB_IV])
        if (decoded != null) {
            return runCatching { json.decodeFromString(SessionBlob.serializer(), decoded) }.getOrNull()
        }
        val legacyToken = open(prefs[KEY_TOKEN], prefs[KEY_IV]) ?: return null
        val address = prefs[KEY_ADDRESS] ?: return null
        val record = StoredSession(
            id = prefs[KEY_SESSION] ?: UUID.randomUUID().toString(),
            address = address,
            name = prefs[KEY_NAME] ?: "",
            expiresAt = normaliseExpiry(prefs[KEY_EXPIRES]?.toLongOrNull() ?: 0L),
            token = legacyToken,
            refreshToken = open(prefs[KEY_REFRESH], prefs[KEY_REFRESH_IV]),
        )
        return SessionBlob(listOf(record), record.address)
    }

    private fun writeBlob(prefs: androidx.datastore.preferences.core.MutablePreferences, blob: SessionBlob) {
        val (encrypted, iv) = seal(json.encodeToString(SessionBlob.serializer(), blob))
        prefs[KEY_BLOB] = encrypted
        prefs[KEY_BLOB_IV] = iv
        prefs[KEY_ACTIVE] = blob.activeAddress ?: ""
        // The legacy single-session fields are superseded: leaving them behind
        // would let a stale token resurrect through readBlob's migration path.
        prefs.remove(KEY_TOKEN)
        prefs.remove(KEY_IV)
        prefs.remove(KEY_REFRESH)
        prefs.remove(KEY_REFRESH_IV)
        prefs.remove(KEY_ADDRESS)
        prefs.remove(KEY_NAME)
        prefs.remove(KEY_EXPIRES)
    }

    private fun activeFrom(blob: SessionBlob): SessionSnapshot? {
        val active = blob.sessions.firstOrNull {
            it.address.equals(blob.activeAddress ?: "", ignoreCase = true)
        } ?: blob.sessions.firstOrNull() ?: return null
        return SessionSnapshot(
            active.id, active.token, active.address,
            normaliseExpiry(active.expiresAt), active.refreshToken,
        )
    }

    suspend fun snapshot(context: Context): SessionSnapshot? {
        if (sessionCacheKnown) return cachedSession
        return withContext(Dispatchers.IO) {
            writes.withLock {
                if (sessionCacheKnown) return@withLock cachedSession
                val prefs = context.sessionStore.data.first()
                cachedSession = readBlob(prefs)?.let(::activeFrom)
                sessionCacheKnown = true
                cachedSession
            }
        }
    }

    /** Every signed-in account, active first — the account switcher's rows. */
    suspend fun snapshotAll(context: Context): List<SessionSnapshot> = withContext(Dispatchers.IO) {
        writes.withLock {
            val prefs = context.sessionStore.data.first()
            val blob = readBlob(prefs) ?: return@withLock emptyList()
            blob.sessions
                .sortedByDescending { it.address.equals(blob.activeAddress ?: "", ignoreCase = true) }
                .map { SessionSnapshot(it.id, it.token, it.address, normaliseExpiry(it.expiresAt), it.refreshToken) }
        }
    }

    /**
     * Make another stored account active. Clears the mailbox caches — the
     * list, the thread cache and the disk snapshot are per-account — and
     * returns false when the address is not among the stored sessions.
     */
    suspend fun switchTo(context: Context, address: String): Boolean = withContext(Dispatchers.IO) {
        writes.withLock {
            var switched = false
            context.sessionStore.edit { prefs ->
                val blob = readBlob(prefs) ?: return@edit
                if (blob.sessions.none { it.address.equals(address, ignoreCase = true) }) return@edit
                writeBlob(prefs, blob.copy(activeAddress = address))
                switched = true
            }
            if (switched) {
                cachedSession = readBlob(context.sessionStore.data.first())?.let(::activeFrom)
                sessionCacheKnown = true
                SecurityProfileCache.clear()
                MailStore.clear()
                // Round 8 — B: a switched-to account is in the foreground.
                RememberedAccounts.touchAsync(context, address)
            }
            switched
        }
    }

    suspend fun isCurrent(context: Context, id: String): Boolean {
        if (sessionCacheKnown) return cachedSession?.id == id
        // Do not acquire [writes] here. MailStore validates its disk snapshot
        // while holding its own mutex; sign-in/sign-out hold [writes] and then
        // clear MailStore. Taking the locks in both orders would deadlock a
        // launch racing a session change. This cold-path DataStore read is
        // safe: edit is atomic, and a stale answer only discards a cache file.
        val prefs = context.sessionStore.data.first()
        val blob = readBlob(prefs) ?: return false
        val active = blob.sessions.firstOrNull {
            it.address.equals(blob.activeAddress ?: "", ignoreCase = true)
        } ?: blob.sessions.firstOrNull() ?: return false
        return active.id == id
    }

    // AndroidKeyStore AES-GCM keys are random-IV by default: the cipher must
    // generate the IV itself (init without a spec) and it is read back with
    // cipher.iv. Supplying a caller-generated IV to ENCRYPT_MODE throws
    // InvalidAlgorithmParameterException on every save — the crash that took
    // down sign-in.
    suspend fun save(
        context: Context,
        token: String,
        refreshToken: String?,
        address: String,
        name: String,
        expiresAt: Long,
    ) = withContext(Dispatchers.IO) {
        val saved = writes.withLock {
            // The active address BEFORE this save: if it differs, the mailbox
            // caches belong to the previous account and must be cleared.
            val previousActive = context.sessionStore.data.first().let {
                it[KEY_ACTIVE] ?: it[KEY_ADDRESS]
            }
            cachedSession = null
            sessionCacheKnown = true
            var committed = false
            try {
                context.sessionStore.edit { prefs ->
                    val blob = readBlob(prefs) ?: SessionBlob(emptyList(), null)
                    val record = StoredSession(
                        id = blob.sessions.firstOrNull {
                            it.address.equals(address, ignoreCase = true)
                        }?.id ?: UUID.randomUUID().toString(),
                        address = address,
                        name = name,
                        expiresAt = normaliseExpiry(expiresAt),
                        token = token,
                        refreshToken = refreshToken,
                    )
                    // Never inherit a different sign-in's refresh token: the
                    // record carries its own.
                    val sessions = blob.sessions.filterNot {
                        it.address.equals(address, ignoreCase = true)
                    } + record
                    writeBlob(prefs, SessionBlob(sessions, address))
                }
                val prefs = context.sessionStore.data.first()
                cachedSession = readBlob(prefs)?.let(::activeFrom)
                sessionCacheKnown = true
                committed = true
                if (!previousActive.equals(address, ignoreCase = true)) {
                    // Switching the active account invalidates its caches so
                    // the mailbox reloads with the new account's mail.
                    SecurityProfileCache.clear()
                    MailStore.clear()
                }
            } finally {
                // A failed or cancelled save leaves DataStore authoritative.
                if (!committed) sessionCacheKnown = false
            }
            committed
        }
        // Round 8 — B: every completed sign-in, whichever door stored it,
        // writes the remembered-account record (display data only, never a
        // credential). Outside the lock — it reads this store again for the
        // profile refresh — and never able to fail the sign-in itself.
        if (saved) {
            try {
                RememberedAccounts.recordSignIn(context, address, name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        Unit
    }

    /**
     * Replace the pair after a refresh, keeping address and name. A refresh
     * rotates both tokens, and the old refresh token is invalidated
     * server-side, so writing only the access token would strand the session
     * at the next refresh. Matches the record by its previous access token.
     */
    suspend fun updateTokens(
        context: Context,
        token: String,
        refreshToken: String?,
        expiresAt: Long,
        expectedToken: String,
    ): Boolean = withContext(Dispatchers.IO) {
        writes.withLock {
            var updated = false
            context.sessionStore.edit { prefs ->
                val blob = readBlob(prefs) ?: return@edit
                val sessions = blob.sessions.map {
                    if (it.token == expectedToken) it.copy(
                        token = token,
                        refreshToken = refreshToken ?: it.refreshToken,
                        expiresAt = normaliseExpiry(expiresAt),
                    ) else it
                }
                if (sessions == blob.sessions) return@edit
                writeBlob(prefs, blob.copy(sessions = sessions))
                cachedSession = activeFrom(blob.copy(sessions = sessions))
                sessionCacheKnown = true
                updated = true
            }
            updated
        }
    }

    suspend fun token(context: Context): String? = snapshot(context)?.token

    /** The ACTIVE account's refresh token. */
    suspend fun refreshToken(context: Context): String? = snapshot(context)?.refreshToken

    suspend fun address(context: Context): String? = snapshot(context)?.address

    /** The stored display name of the ACTIVE account. */
    suspend fun name(context: Context): String? = withContext(Dispatchers.IO) {
        val prefs = context.sessionStore.data.first()
        val blob = readBlob(prefs) ?: return@withContext null
        val activeAddress = blob.activeAddress
        blob.sessions.firstOrNull { it.address.equals(activeAddress ?: "", ignoreCase = true) }
            ?: blob.sessions.firstOrNull()
    }?.name

    /** A stored session counts as signed-in only while unexpired. 0 means
     *  the server supplied no expiry — keep the session. Values below the
     *  epoch-milliseconds threshold are seconds, not milliseconds. */
    suspend fun hasValidSession(context: Context): Boolean = snapshot(context)?.unexpired == true

    private fun normaliseExpiry(raw: Long): Long =
        if (raw in 1 until 100_000_000_000L) raw * 1000L else raw

    suspend fun clear(context: Context) = removeSessions(context, { RememberedAccounts.Ending.SignedOut }) { true }

    /** The person signed out (or deleted the account — the caller then forgets the record). */
    suspend fun clearIfCurrent(context: Context, id: String): Boolean =
        removeSessions(context, { RememberedAccounts.Ending.SignedOut }) { it.id == id }

    /**
     * The server refused [token]. Why it ended is whatever the refresh layer
     * noted against it ([SessionEvents.takeEnding]) or, with nothing noted,
     * an expiry — the remembered record's welcome-back banner reads it.
     */
    suspend fun clearIfToken(
        context: Context,
        token: String,
        ending: RememberedAccounts.Ending? = null,
    ): Boolean {
        val reason = ending ?: SessionEvents.takeEnding(token) ?: RememberedAccounts.Ending.Expired
        return removeSessions(context, { reason }) { it.token == token }
    }

    /** Sign out one account, keeping the others; the next stored account, if
     *  any, becomes active. */
    suspend fun removeAccount(context: Context, address: String): Boolean =
        removeSessions(context, { RememberedAccounts.Ending.SignedOut }) {
            it.address.equals(address, ignoreCase = true)
        }

    /**
     * Remove every session matching the predicate; when nothing remains the
     * store is emptied entirely. Each removal that changes the active account
     * invalidates the mailbox caches and cancels the notifications — the
     * removed account's mail must not outlive its sign-out.
     */
    private suspend fun removeSessions(
        context: Context,
        ending: (StoredSession) -> RememberedAccounts.Ending,
        matches: (StoredSession) -> Boolean,
    ): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            // Round 8 — B: who left, and why, for the remembered-account records.
            var ended = emptyList<Pair<StoredSession, RememberedAccounts.Ending>>()
            val removed = writes.withLock {
                var cleared = false
                var removedIds = emptySet<String>()
                context.sessionStore.edit { prefs ->
                    val blob = readBlob(prefs) ?: return@edit
                    if (blob.sessions.none(matches)) return@edit
                    val remaining = blob.sessions.filterNot(matches)
                    removedIds = blob.sessions.filter(matches).map { it.id }.toSet()
                    ended = blob.sessions.filter(matches).map { it to ending(it) }
                    cleared = true
                    if (remaining.isEmpty()) {
                        prefs.clear()
                    } else {
                        val wasActive = blob.sessions.any {
                            it.address.equals(blob.activeAddress ?: "", ignoreCase = true) && matches(it)
                        }
                        val nextActive = if (wasActive) remaining.first().address else blob.activeAddress
                        writeBlob(prefs, SessionBlob(remaining, nextActive))
                    }
                }
                if (cleared) {
                    cachedSession = readBlob(context.sessionStore.data.first())?.let(::activeFrom)
                    sessionCacheKnown = true
                    SecurityProfileCache.clear()
                    MailStore.clear()
                    val manager = context.getSystemService(android.app.NotificationManager::class.java)
                    manager?.activeNotifications?.forEach {
                        if (it.notification.extras.getString("grapmail.session") in removedIds) manager.cancel(it.tag, it.id)
                    }
                }
                cleared
            }
            // Outside the lock, and best-effort: a record that could not be
            // updated must never keep a session from being removed.
            if (removed) {
                for ((session, why) in ended) {
                    try {
                        RememberedAccounts.markEnded(context, session.address, session.name, why)
                    } catch (_: Exception) {
                    }
                }
            }
            removed
        }
}
