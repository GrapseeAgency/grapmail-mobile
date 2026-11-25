package com.grapsee.grapmail.ui.screens.mail

import android.content.ClipData
import android.content.ClipDescription
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.grapsee.grapmail.data.MailAttachmentDto
import com.grapsee.grapmail.logic.MailboxViewModel

/**
 * Attachment thumbnails — unit 79's unfinished half.
 *
 * The old doc comment on `AttachmentPreviewScreen` was explicit: an image was
 * offered as a download, not a thumbnail, "because there is no image loader in
 * this build". This is that loader — Coil, fetching the bytes through the same
 * authenticated path the download button uses (`MailboxViewModel.attachmentPreviewFile`),
 * so the request carries the session's bearer header without the image library
 * ever seeing a token.
 *
 * The fallback is the caller's own icon, not a broken box: non-image types, a
 * file the person cannot access, and a decode that fails all land on the same
 * visual the screens shipped with before. While bytes arrive the row shows that
 * icon rather than a spinner — a loading state that flashes on every cache hit
 * is noise, and for already-downloaded rows the fetch is a file copy.
 *
 * One recorded limit: this downloads the whole attachment once into the app
 * cache. A 20 MB scanned page is fetched in full to draw a 38dp square. A
 * server-side thumbnail route would fix that properly; noted here rather than
 * pretended away.
 *
 * Placement: drop this file next to `MailSecondaryScreens.kt` in
 * `ui/screens/mail/` — same package, so no import changes in the screens.
 *
 * Drag out: a loaded image thumbnail can be long-pressed and dragged into
 * another app (split screen, DeX, ChromeOS). The handoff is `openAttachment`'s:
 * a fresh directory under the FileProvider's one exposed root
 * (`cacheDir/attachments`), a `content://` URI, a read grant for that one URI
 * only, and the server's MIME type. Only images whose bytes are already on the
 * device can be dragged — a drag has to start inside the long-press, and a
 * download cannot happen there; other files keep Open and Save. The reader's
 * attachment chips (ThreadScreen.kt `AttachmentRow`) use the same
 * [attachmentDragSource] / [rememberAttachmentPreviewFile] pair.
 */
@Composable
fun AttachmentThumb(
    vm: MailboxViewModel,
    attachment: MailAttachmentDto,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    if (!attachment.isPreviewableImage()) {
        Box(modifier, contentAlignment = Alignment.Center) { fallback() }
        return
    }

    val file = rememberAttachmentPreviewFile(vm, attachment)
    var failed by remember(attachment.id) { mutableStateOf(false) }
    val loaded = file

    if (loaded == null || failed) {
        Box(modifier, contentAlignment = Alignment.Center) { fallback() }
    } else {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(loaded)
                .crossfade(true)
                .build(),
            contentDescription = attachment.name,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = modifier.attachmentDragSource(attachment, loaded),
        )
    }
}

/**
 * The image types a thumbnail (and a drag-out) is offered for. SVG has no
 * decoder on stock Android; promising a thumbnail for it would be the broken
 * box the old comment refused to draw.
 */
internal fun MailAttachmentDto.isPreviewableImage(): Boolean {
    val mime = mimeType?.lowercase().orEmpty()
    return mime.startsWith("image/") && !mime.startsWith("image/svg")
}

/**
 * The attachment's bytes on this device, for a previewable image: fetched
 * through `MailboxViewModel.attachmentPreviewFile` (authenticated, capped at
 * 2 MB, cached by attachment id, so a second visit is a length check), or null
 * for anything else, anything larger, and any fetch that fails.
 */
@Composable
internal fun rememberAttachmentPreviewFile(vm: MailboxViewModel, attachment: MailAttachmentDto): java.io.File? {
    if (!attachment.isPreviewableImage()) return null
    val file by produceState<java.io.File?>(initialValue = null, attachment.id) {
        value = vm.attachmentPreviewFile(attachment)
    }
    return file
}

/**
 * Long-press, then drag the attachment out to another app. Shared by the Files
 * thumbnails and the reader's attachment chips, so both follow one rule: a
 * [file] already on the device (see [rememberAttachmentPreviewFile]) or no drag
 * at all. A plain tap is not consumed here, so the element's own click (open)
 * still works.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.attachmentDragSource(attachment: MailAttachmentDto, file: java.io.File?): Modifier {
    if (file == null) return this
    val context = LocalContext.current
    return dragAndDropSource {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                val clip = attachmentDragClip(context, attachment, file)
                if (clip != null) {
                    startTransfer(
                        DragAndDropTransferData(
                            clipData = clip,
                            flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ,
                        ),
                    )
                }
            },
            onDrag = { change, _ -> change.consume() },
        )
    }
}

/**
 * Copy the cached bytes into a new directory under the provider's root and
 * describe them as a drag clip, or null when that is not possible. The copy is
 * at most the 2 MB `attachmentPreviewFile` keeps, so it is quick enough to make
 * inside the gesture.
 */
private fun attachmentDragClip(
    context: android.content.Context,
    attachment: MailAttachmentDto,
    source: java.io.File,
): ClipData? = runCatching {
    val name = attachment.name.orEmpty().replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(120).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"
    val root = java.io.File(context.cacheDir, "attachments").apply { mkdirs() }
    val dir = java.nio.file.Files.createTempDirectory(root.toPath(), "drag-").toFile()
    val file = java.io.File(dir, name)
    source.copyTo(file, overwrite = true)
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file)
    val mime = attachment.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
    ClipData(ClipDescription(name, arrayOf(mime)), ClipData.Item(uri))
}.getOrNull()
