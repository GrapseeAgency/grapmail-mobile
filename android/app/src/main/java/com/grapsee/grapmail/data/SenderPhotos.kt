package com.grapsee.grapmail.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

/**
 * Sender photos for the list and the reader.
 *
 * Every row used to draw an initial because the message rows carry no picture.
 * The server now says which senders have one (`POST /avatars/resolve`, GrapMail
 * accounts only, and only for people who have written to this mailbox), and this
 * object turns that into bitmaps:
 *
 *  - Requests are batched. A screen of rows asks within a few milliseconds of
 *    each other, so they are collected for 60 ms and sent as one call of up to 100.
 *  - Answers are remembered per signed-in account for 30 minutes (10 for "no
 *    photo"), so scrolling does not re-ask.
 *  - Bytes are cached on disk under the photo's revision. A changed photo has a
 *    new revision, so a recipient sees the new face on the next resolve and an
 *    old file is never mistaken for it.
 *  - Bitmaps are downsampled to avatar size before they are held in memory.
 *
 * Failure is quiet by design: any error leaves the initial in place, which is
 * what the row showed before this existed.
 */
object SenderPhotos {
    private const val BATCH_WINDOW_MS = 60L
    private const val MAX_BATCH = 100
    private const val HIT_TTL_MS = 30 * 60 * 1000L
    private const val MISS_TTL_MS = 10 * 60 * 1000L
    private const val TARGET_PX = 160

    private class Answer(val revision: String?, val at: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val answers = HashMap<String, Answer>()
    private val pending = LinkedHashMap<String, MutableList<CompletableDeferred<String?>>>()
    private var flushScheduled = false
    /** The last revision seen per sender, so a recycled row can draw its photo on the first frame. */
    private val lastKnown = HashMap<String, String>()
    private val bitmaps = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Already-decoded photo for this address, for the first frame. */
    fun cached(address: String?): ImageBitmap? {
        val key = address?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val revision = synchronized(answers) { lastKnown[key] } ?: return null
        return bitmaps.get("$key|$revision")
    }

    /** The sender's photo, or null to keep the initial. Never throws. */
    suspend fun photo(context: Context, address: String): ImageBitmap? = runCatching {
        val key = address.trim().lowercase()
        if (key.isEmpty() || !key.contains("@")) return null
        val token = SessionStore.token(context) ?: return null
        val viewer = SessionStore.address(context)?.lowercase() ?: ""
        val revision = revisionFor(context, viewer, key) ?: return null
        bitmaps.get("$key|$revision")?.let { return it }
        val bytes = readDisk(context, key, revision) ?: run {
            val fetched = try {
                Api.mailAvatarBytes(token, key, revision)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // A 409 means the photo changed: forget the answer so the next ask re-resolves.
                synchronized(answers) { answers.remove("$viewer|$key") }
                return null
            }
            if (fetched.isEmpty()) return null
            writeDisk(context, key, revision, fetched)
            fetched
        }
        decode(bytes)?.also { bitmaps.put("$key|$revision", it) }
    }.getOrNull()

    /** Forget everything, e.g. on sign-out or an account switch. */
    fun clear(context: Context) {
        synchronized(answers) { answers.clear(); lastKnown.clear() }
        bitmaps.evictAll()
        runCatching { dir(context).deleteRecursively() }
    }

    private suspend fun revisionFor(context: Context, viewer: String, key: String): String? {
        val now = System.currentTimeMillis()
        synchronized(answers) {
            answers["$viewer|$key"]?.let { a ->
                val ttl = if (a.revision != null) HIT_TTL_MS else MISS_TTL_MS
                if (now - a.at < ttl) return a.revision
            }
        }
        val waiter = CompletableDeferred<String?>()
        lock.withLock {
            pending.getOrPut(key) { mutableListOf() }.add(waiter)
            if (!flushScheduled) {
                flushScheduled = true
                scope.launch { delay(BATCH_WINDOW_MS); flush(context, viewer) }
            }
        }
        return waiter.await()
    }

    private suspend fun flush(context: Context, viewer: String) {
        val batch: Map<String, List<CompletableDeferred<String?>>> = lock.withLock {
            val taken = LinkedHashMap<String, List<CompletableDeferred<String?>>>()
            val it = pending.entries.iterator()
            while (it.hasNext() && taken.size < MAX_BATCH) {
                val e = it.next(); taken[e.key] = e.value.toList(); it.remove()
            }
            flushScheduled = pending.isNotEmpty()
            if (flushScheduled) scope.launch { flush(context, viewer) }
            taken
        }
        if (batch.isEmpty()) return
        val result = runCatching {
            val token = SessionStore.token(context) ?: error("signed out")
            Api.mailAvatars(token, batch.keys.toList()).avatars
        }
        val now = System.currentTimeMillis()
        for ((key, waiters) in batch) {
            val revision = result.getOrNull()?.get(key)
                ?.takeIf { it.kind == "person" || it.kind == "brand" }
                ?.revision?.takeIf { it.matches(Regex("^[a-f0-9]{32}$")) }
            // Only a real answer is remembered; a failed call is asked again next time.
            if (result.isSuccess) synchronized(answers) {
                answers["$viewer|$key"] = Answer(revision, now)
                if (revision != null) lastKnown[key] = revision else lastKnown.remove(key)
            }
            waiters.forEach { it.complete(revision) }
        }
    }

    private fun dir(context: Context) = File(context.cacheDir, "sender-photos")

    private fun file(context: Context, key: String, revision: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        return File(dir(context), "$digest-$revision")
    }

    private fun readDisk(context: Context, key: String, revision: String): ByteArray? =
        runCatching { file(context, key, revision).takeIf { it.isFile }?.readBytes() }.getOrNull()

    private fun writeDisk(context: Context, key: String, revision: String, bytes: ByteArray) {
        runCatching {
            val target = file(context, key, revision)
            target.parentFile?.mkdirs()
            // Older revisions of this sender are dead weight once a new one lands.
            val prefix = target.name.substringBefore("-") + "-"
            target.parentFile?.listFiles()?.filter { it.name.startsWith(prefix) && it.name != target.name }
                ?.forEach { it.delete() }
            target.writeBytes(bytes)
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
}
