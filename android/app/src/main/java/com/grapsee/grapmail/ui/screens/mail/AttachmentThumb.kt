package com.grapsee.grapmail.ui.screens.mail

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 */
@Composable
fun AttachmentThumb(
    vm: MailboxViewModel,
    attachment: MailAttachmentDto,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val mime = attachment.mimeType?.lowercase().orEmpty()
    // SVG has no decoder on stock Android; promising a thumbnail for it would
    // be the broken box the old comment refused to draw.
    val previewable = mime.startsWith("image/") && !mime.startsWith("image/svg")

    if (!previewable) {
        Box(modifier, contentAlignment = Alignment.Center) { fallback() }
        return
    }

    val file by produceState<java.io.File?>(initialValue = null, attachment.id) {
        value = vm.attachmentPreviewFile(attachment)
    }
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
            modifier = modifier,
        )
    }
}
