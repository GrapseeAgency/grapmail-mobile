# A5 — MailboxViewModel: `attachmentPreviewFile()`

**File:** `android/app/src/main/java/com/grapsee/grapmail/logic/MailboxViewModel.kt`
**Depends on:** nothing new — uses `SessionStore`, `Api.mailAttachmentFile`, both already imported/used in this file (e.g. by `downloadAttachment`).

## Where to insert

Immediately **before** this exact block (line 2130 in the 2026-11-18 snapshot):

```kotlin
    private fun attachmentFilename(raw: String?): String =
        raw.orEmpty().replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"
```

i.e. in the blank line right after `openAttachment`'s closing `}` (line 2128). Keep one blank line on each side.

## Insert exactly this

```kotlin
    /**
     * Local copy of an image attachment for the thumbnail loader (fixpack A2).
     *
     * Same rules as `downloadAttachment`: the app-owned cache, a session check
     * on both sides of the request, and a file that is deleted rather than kept
     * when the account changes mid-flight. One difference — a thumbnail that
     * cannot load returns null so the screen falls back to the type icon,
     * instead of a message about a file the person never asked to save.
     *
     * The cached name is derived from the attachment id, so a second visit is a
     * file-length check rather than a download.
     */
    suspend fun attachmentPreviewFile(a: MailAttachmentDto): java.io.File? {
        val app = getApplication<android.app.Application>()
        val session = SessionStore.snapshot(app) ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val root = java.io.File(app.cacheDir, "attachment-previews").apply { mkdirs() }
            val file = java.io.File(
                root,
                a.id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96) + ".img",
            )
            if (file.length() > 0L) return@withContext file
            runCatching { Api.mailAttachmentFile(session.token, a.id, file) }
                .mapCatching {
                    if (!SessionStore.isCurrent(app, session.id)) {
                        file.delete(); null
                    } else if (file.length() > 0L) file
                    else { file.delete(); null }
                }
                .getOrElse { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    file.delete()
                    null
                }
        }
    }
```

## Notes (all verified against the 2026-11-18 snapshot)

- `SessionStore.snapshot(context)` and `SessionStore.isCurrent(context, id)` are **suspend** functions (SessionStore.kt L202/L250) — calling them from this `suspend fun` and from the `withContext` block is exactly how `downloadAttachment`/`openAttachment` already use them.
- `Api.mailAttachmentFile(token, id, destination)` streams to disk without buffering the body (Api.kt L1544).
- `getApplication<android.app.Application>()` and the fully-qualified `java.io.File` / `kotlinx.coroutines.*` spellings match the style of the two attachment functions above; **no import changes are needed**.
- The `mapCatching` branch deletes an empty file as failure: a zero-byte body is a failed fetch that happened to create a file, and caching it would make the broken state permanent.
- Drift check if the file has moved on: `grep -n "private fun attachmentFilename" logic/MailboxViewModel.kt`.

## Verify

- After A1–A4 + this patch: `./gradlew :app:assembleDebug` compiles.
- Manual: first open of an image attachment downloads once (one network fetch visible); the second open does not. Signing into another account while a fetch is in flight never yields the previous account's image (file deleted on session mismatch).
