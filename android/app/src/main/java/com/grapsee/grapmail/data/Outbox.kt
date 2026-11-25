package com.grapsee.grapmail.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Mail that was sent while the device had no connection.
 *
 * Before this, Send with no network failed in the composer and the message
 * stayed there: close the app and the only copy was whatever the last autosave
 * had reached. Now a send that fails for lack of a connection (never one the
 * server refused) is written here and delivered by [com.grapsee.grapmail.logic.OutboxWorker]
 * the moment the device is back online, even if the app is closed by then.
 *
 * ── WHY IT CANNOT SEND TWICE ───────────────────────────────────────────────
 * Each item carries its own `Idempotency-Key`, minted once when the item is
 * created and stored with it. Every retry, from any process, after any reboot,
 * presents the same key, so a send whose answer was lost is replayed by the
 * server instead of delivered a second time (backend lib/send-idempotency.ts).
 *
 * ── WHY IT IS ENCRYPTED ────────────────────────────────────────────────────
 * These are unsent emails: recipients, subjects and bodies. They are kept in
 * no-backup storage under an AndroidKeyStore AES-256-GCM key, the same pattern
 * as the session tokens (MASVS-STORAGE-1), so neither a backup nor a copy of
 * the app's files reveals them.
 */
@Serializable
data class OutboxItem(
    val id: String,
    /** The SessionStore account it was sent from. A switch of account never sends it as somebody else. */
    val sessionId: String,
    val request: MailSendRequest,
    /** Server-side draft to delete once the send is accepted, or "". */
    val draftId: String = "",
    val idempotencyKey: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)

object Outbox {
    private const val FILE = "grapmail-outbox.bin"
    private const val KEY_ALIAS = "grapmail.outbox.v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    /** An item older than this is given up on and handed back as a failure: mail is time-sensitive. */
    const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private val serializer = ListSerializer(OutboxItem.serializer())

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun readAll(context: Context): List<OutboxItem> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val (ivB64, dataB64) = f.readText().split(':', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            val plain = cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)).toString(Charsets.UTF_8)
            json.decodeFromString(serializer, plain)
        } catch (e: Exception) {
            // A file that cannot be decrypted (key invalidated by a device reset of
            // the keystore) cannot be read by anyone; keeping it helps nobody.
            android.util.Log.w("GrapMailOutbox", "Outbox unreadable; cleared")
            f.delete()
            emptyList()
        }
    }

    private fun writeAll(context: Context, items: List<OutboxItem>) {
        val f = file(context)
        if (items.isEmpty()) { f.delete(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(json.encodeToString(serializer, items).toByteArray(Charsets.UTF_8))
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Queue one message. Returns the item, whose key every retry will reuse. */
    suspend fun enqueue(context: Context, sessionId: String, request: MailSendRequest, draftId: String): OutboxItem =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val item = OutboxItem(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    request = request,
                    draftId = draftId,
                    idempotencyKey = "o-" + UUID.randomUUID().toString().replace("-", ""),
                    createdAt = System.currentTimeMillis(),
                )
                writeAll(context, readAll(context) + item)
                item
            }
        }

    suspend fun items(context: Context): List<OutboxItem> = withContext(Dispatchers.IO) { lock.withLock { readAll(context) } }

    suspend fun count(context: Context): Int = items(context).size

    suspend fun remove(context: Context, id: String) = withContext(Dispatchers.IO) {
        lock.withLock { writeAll(context, readAll(context).filterNot { it.id == id }) }
    }

    suspend fun recordAttempt(context: Context, id: String, error: String?) = withContext(Dispatchers.IO) {
        lock.withLock {
            writeAll(context, readAll(context).map {
                if (it.id == id) it.copy(attempts = it.attempts + 1, lastError = error) else it
            })
        }
    }

    /** Signing out of an account drops its unsent mail with it: nobody else may send it. */
    suspend fun removeForSession(context: Context, sessionId: String) = withContext(Dispatchers.IO) {
        lock.withLock { writeAll(context, readAll(context).filterNot { it.sessionId == sessionId }) }
    }
}
