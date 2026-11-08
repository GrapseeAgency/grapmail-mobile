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

private val Context.sessionStore by preferencesDataStore("grapmail_session")

/**
 * Session persistence: the access token and its refresh token are encrypted
 * with an Android Keystore AES-GCM key before they touch disk. The stored form
 * is ciphertext, never plaintext — matching the security posture of the web
 * client's storage.
 *
 * Both tokens are the gateway's JWT pair now, not the `st1` session id older
 * builds stored: the mailbox API verifies the JWT's signature and `jti`, and
 * refuses anything else. The refresh token is kept encrypted for the same
 * reason the access token is — it is a bearer credential that can mint new
 * sessions.
 */
// Not a data class: diagnostics must never print credentials.
class SessionSnapshot(val id: String, val token: String, val address: String)

object SessionStore {
    private val writes = Mutex()
    private val KEY_SESSION = stringPreferencesKey("session_id")

    private fun sessionId(prefs: Preferences): String? =
        if (prefs[KEY_TOKEN] == null) null else prefs[KEY_SESSION] ?: prefs[KEY_IV]

    fun sessionIds(context: Context) = context.sessionStore.data.map(::sessionId).distinctUntilChanged()

    suspend fun snapshot(context: Context): SessionSnapshot? = withContext(Dispatchers.IO) {
        val prefs = context.sessionStore.data.first()
        val id = sessionId(prefs) ?: return@withContext null
        val token = open(prefs[KEY_TOKEN], prefs[KEY_IV]) ?: return@withContext null
        SessionSnapshot(id, token, prefs[KEY_ADDRESS] ?: "")
    }

    suspend fun isCurrent(context: Context, id: String): Boolean =
        sessionId(context.sessionStore.data.first()) == id

    private val KEY_TOKEN = stringPreferencesKey("token_enc")
    private val KEY_IV = stringPreferencesKey("token_iv")
    private val KEY_REFRESH = stringPreferencesKey("refresh_enc")
    private val KEY_REFRESH_IV = stringPreferencesKey("refresh_iv")
    private val KEY_ADDRESS = stringPreferencesKey("address")
    private val KEY_NAME = stringPreferencesKey("name")
    private val KEY_EXPIRES = stringPreferencesKey("expires_at")
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "grapmail_session_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
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
        writes.withLock {
            val (encrypted, iv) = seal(token)
            context.sessionStore.edit { prefs ->
                prefs[KEY_TOKEN] = encrypted
                prefs[KEY_IV] = iv
                // Never inherit a different sign-in's refresh token.
                prefs.remove(KEY_REFRESH)
                prefs.remove(KEY_REFRESH_IV)
                if (refreshToken != null) {
                    val (refreshEnc, refreshIv) = seal(refreshToken)
                    prefs[KEY_REFRESH] = refreshEnc
                    prefs[KEY_REFRESH_IV] = refreshIv
                }
                prefs[KEY_SESSION] = UUID.randomUUID().toString()
                prefs[KEY_ADDRESS] = address
                prefs[KEY_NAME] = name
                prefs[KEY_EXPIRES] = expiresAt.toString()
            }
        }
    }

    /**
     * Replace the pair after a refresh, keeping address and name.
     *
     * A refresh rotates both tokens, and the old refresh token is invalidated
     * server-side, so writing only the access token would strand the session at
     * the next refresh.
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
                if (open(prefs[KEY_TOKEN], prefs[KEY_IV]) != expectedToken) return@edit
                val id = sessionId(prefs) ?: return@edit
                val (encrypted, iv) = seal(token)
                prefs[KEY_SESSION] = id
                prefs[KEY_TOKEN] = encrypted
                prefs[KEY_IV] = iv
                if (refreshToken != null) {
                    val (refreshEnc, refreshIv) = seal(refreshToken)
                    prefs[KEY_REFRESH] = refreshEnc
                    prefs[KEY_REFRESH_IV] = refreshIv
                }
                prefs[KEY_EXPIRES] = expiresAt.toString()
                updated = true
            }
            updated
        }
    }

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

    suspend fun token(context: Context): String? = withContext(Dispatchers.IO) {
        val prefs = context.sessionStore.data.first()
        open(prefs[KEY_TOKEN], prefs[KEY_IV])
    }

    /** The refresh token, or null for a session stored before the migration. */
    suspend fun refreshToken(context: Context): String? = withContext(Dispatchers.IO) {
        val prefs = context.sessionStore.data.first()
        open(prefs[KEY_REFRESH], prefs[KEY_REFRESH_IV])
    }

    suspend fun address(context: Context): String? =
        withContext(Dispatchers.IO) { context.sessionStore.data.first()[KEY_ADDRESS] }

    /** The stored display name, so a token reissued after a password change can
     *  be written back without blanking what the session already knows. */
    suspend fun name(context: Context): String? =
        withContext(Dispatchers.IO) { context.sessionStore.data.first()[KEY_NAME] }

    /** A stored session counts as signed-in only while unexpired. 0 means
     *  the server supplied no expiry — keep the session. Values below the
     *  epoch-milliseconds threshold are seconds, not milliseconds. */
    suspend fun hasValidSession(context: Context): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.sessionStore.data.first()
        if (prefs[KEY_TOKEN] == null) return@withContext false
        val raw = prefs[KEY_EXPIRES]?.toLongOrNull() ?: 0L
        if (raw <= 0L) return@withContext true
        val millis = if (raw < 100_000_000_000L) raw * 1000L else raw
        millis > System.currentTimeMillis()
    }

    suspend fun clear(context: Context) { clearMatching(context) { true } }

    suspend fun clearIfCurrent(context: Context, id: String): Boolean =
        clearMatching(context) { sessionId(it) == id }

    suspend fun clearIfToken(context: Context, token: String): Boolean =
        clearMatching(context) { open(it[KEY_TOKEN], it[KEY_IV]) == token }

    private suspend fun clearMatching(context: Context, matches: (Preferences) -> Boolean): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            writes.withLock {
                var cleared = false
                context.sessionStore.edit { if (matches(it)) { it.clear(); cleared = true } }
                if (cleared) {
                    SecurityProfileCache.clear()
                    MailStore.clear()
                    context.getSystemService(android.app.NotificationManager::class.java)?.cancelAll()
                }
                cleared
            }
        }
}
