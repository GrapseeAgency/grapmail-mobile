package com.grapsee.grapmail.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.sessionStore by preferencesDataStore("grapmail_session")

/**
 * Session persistence: the st1 token is encrypted with an Android Keystore
 * AES-GCM key before it touches disk. The stored form is ciphertext, never
 * plaintext — matching the security posture of the web client's storage.
 */
object SessionStore {

    private val KEY_TOKEN = stringPreferencesKey("token_enc")
    private val KEY_IV = stringPreferencesKey("token_iv")
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
    suspend fun save(context: Context, token: String, address: String, name: String, expiresAt: Long) =
        withContext(Dispatchers.IO) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(token.toByteArray())
            val iv = cipher.iv
            context.sessionStore.edit { prefs ->
                prefs[KEY_TOKEN] = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
                prefs[KEY_IV] = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
                prefs[KEY_ADDRESS] = address
                prefs[KEY_NAME] = name
                prefs[KEY_EXPIRES] = expiresAt.toString()
            }
        }

    suspend fun token(context: Context): String? = withContext(Dispatchers.IO) {
        val prefs = context.sessionStore.data.first()
        val enc = prefs[KEY_TOKEN] ?: return@withContext null
        val ivB64 = prefs[KEY_IV] ?: return@withContext null
        try {
            val encrypted = android.util.Base64.decode(enc, android.util.Base64.NO_WRAP)
            val iv = android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted))
        } catch (_: Exception) { null }
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

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        context.sessionStore.edit { it.clear() }
        // The cached security verdict belongs to the account being signed out
        // of. Left in memory it would be the first thing the next account's
        // hub renders.
        SecurityProfileCache.clear()
    }
}
