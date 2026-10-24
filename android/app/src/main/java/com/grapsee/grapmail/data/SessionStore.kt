package com.grapsee.grapmail.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import java.security.SecureRandom
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

    fun save(context: Context, token: String, address: String, name: String, expiresAt: Long) {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(token.toByteArray())
        runBlocking {
            context.sessionStore.edit { prefs ->
                prefs[KEY_TOKEN] = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
                prefs[KEY_IV] = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
                prefs[KEY_ADDRESS] = address
                prefs[KEY_NAME] = name
                prefs[KEY_EXPIRES] = expiresAt.toString()
            }
        }
    }

    fun token(context: Context): String? = runBlocking {
        val prefs = context.sessionStore.data.first()
        val enc = prefs[KEY_TOKEN] ?: return@runBlocking null
        val ivB64 = prefs[KEY_IV] ?: return@runBlocking null
        return@runBlocking try {
            val encrypted = android.util.Base64.decode(enc, android.util.Base64.NO_WRAP)
            val iv = android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted))
        } catch (_: Exception) { null }
    }

    fun address(context: Context): String? = runBlocking { context.sessionStore.data.first()[KEY_ADDRESS] }

    fun clear(context: Context) {
        runBlocking { context.sessionStore.edit { it.clear() } }
    }
}
