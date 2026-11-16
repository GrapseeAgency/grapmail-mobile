package com.grapsee.grapmail.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

object AvatarPhoto {
    const val maxBytes = 10 * 1024 * 1024
    private const val maxUploadBytes = 1024 * 1024
    private val cacheLock = kotlinx.coroutines.sync.Mutex()
    private val memory = ConcurrentHashMap<String, ByteArray>()

    private fun cacheKey(session: SessionSnapshot): String? {
        val parts = session.token.split('.', limit = 4)
        if (parts.size != 3 || parts[1].length > 16_384) return null
        // The account claim only namespaces the private cache. The API still authorizes all reads and writes.
        val payload = JSONObject(Base64.decode(parts[1], Base64.URL_SAFE).decodeToString())
        val account = payload.getString(\"sub\")
        return MessageDigest.getInstance(\"SHA-256\").digest(\"$account:${session.id}\".toByteArray())
            .joinToString(\"\") { \"%02x\".format(it.toInt() and 0xff) }
    }

    private fun cacheFile(context: Context, session: SessionSnapshot): AtomicFile? {
        val key = cacheKey(session) ?: return null
        return AtomicFile(File(context.cacheDir, \"account-photos/$key\"))
    }

    // The photo lives in memory for as long as the process does, so every
    // surface (mailbox chip, profile screen, switcher) shows it instantly,
    // even before the disk cache is written or a server read completes.
    fun remember(session: SessionSnapshot, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > maxBytes) return
        cacheKey(session)?.let { memory[it] = bytes }
    }

    // Only an explicit removal may clear the store. Reads that miss, stale
    // profile payloads, or a slow network must never delete the photo.
    suspend fun forget(context: Context, session: SessionSnapshot) {
        val key = cacheKey(session) ?: return
        memory.remove(key)
        withContext(Dispatchers.IO) {
            runCatching { AtomicFile(File(context.cacheDir, \"account-photos/$key\")).delete() }
        }
    }

    suspend fun readCached(context: Context, session: SessionSnapshot): ByteArray? {
        cacheKey(session)?.let { key -> memory[key]?.takeIf(::isImage)?.let { return it } }
        return cacheLock.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    cacheFile(context, session)?.openRead()?.use {
                        if (it.channel.size() !in 1L..maxBytes.toLong()) null
                        else it.readBytes().takeIf(::isImage)
                    }
                }.getOrNull()
            }
        }
    }

    suspend fun cache(context: Context, session: SessionSnapshot, bytes: ByteArray?) {
        // A null payload never deletes anything here: a read that missed is
        // not proof the photo is gone, and competing answers (stale profile
        // JSON, a failed watch poll) used to wipe the user's photo. Removal
        // is handled by forget() alone.
        if (bytes == null) return
        if (bytes.isEmpty() || bytes.size > maxBytes) return
        val key = cacheKey(session) ?: return
        memory[key] = bytes
        cacheLock.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = cacheFile(context, session) ?: return@runCatching
                    val stream = file.startWrite()
                    try {
                        stream.write(bytes)
                        file.finishWrite(stream)
                    } catch (error: Exception) {
                        file.failWrite(stream)
                        throw error
                    }
                }
            }
        }
    }

    fun inline(value: String?): ByteArray? {
        if (value == null || value.length > maxBytes * 4 / 3 + 100) return null
        val type = value.substringBefore(',')
        if (type !in setOf(\"data:image/png;base64\", \"data:image/jpeg;base64\",
                \"data:image/webp;base64\", \"data:image/gif;base64\")) return null
        return runCatching { Base64.decode(value.substringAfter(','), Base64.DEFAULT) }
            .getOrNull()?.takeIf(::isImage)
    }

    fun isImage(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > maxBytes) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    fun thumbnail(bytes: ByteArray, maxPixels: Int = 256): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > maxPixels * 2) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    suspend fun encode(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bitmap = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    val width = info.size.width
                    val height = info.size.height
                    val scale = minOf(1f, 1024f / maxOf(width, height))
                    decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
                var sample = 1
                while (maxOf(options.outWidth, options.outHeight) / sample > 1024) sample *= 2
                options.inJustDecodeBounds = false
                options.inSampleSize = sample
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?: return@runCatching null
            }
            try {
                val longest = maxOf(bitmap.width, bitmap.height)
                for (limit in intArrayOf(1024, 512, 384)) {
                    val scale = minOf(1f, limit.toFloat() / longest)
                    val scaled = if (scale == 1f) bitmap else Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                    try {
                        ByteArrayOutputStream().use { output ->
                            if (scaled.compress(Bitmap.CompressFormat.PNG, 100, output) &&
                                output.size() in 1..maxUploadBytes
                            ) return@runCatching output.toByteArray()
                        }
                    } finally {
                        if (scaled !== bitmap) scaled.recycle()
                    }
                }
                null
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }
}
