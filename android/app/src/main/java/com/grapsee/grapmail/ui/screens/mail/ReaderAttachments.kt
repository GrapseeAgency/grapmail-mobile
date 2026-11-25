package com.grapsee.grapmail.ui.screens.mail

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.MailAttachmentDto
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.logic.MailboxViewModel
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The reader's attachments: pictures as a thumbnail grid that opens a
 * full-screen viewer (swipe between pictures, pinch / double-tap to zoom),
 * every other file as a card with its type, name, size, and Save / Share. A
 * PDF that is already on this device (saved, opened or shared before) shows
 * its first page instead of the type icon; nothing is downloaded just to draw
 * a thumbnail of a PDF.
 *
 * Kept from the chips this replaces: Save is the same `downloadAttachment`
 * (app-owned storage, the row's own outcome line), tapping a file opens it in
 * another app through `openAttachment`, and long-press drag-out keeps the
 * AttachmentThumb.kt rule — only images whose bytes are in the preview cache
 * can be dragged; any other file has no drag.
 *
 * Colours are palette tokens only. The one exception is the PDF page itself,
 * which is rendered onto paper white: a page is a document, not app chrome,
 * and dark text on a transparent page would vanish in the dark theme.
 */
@Composable
internal fun ReaderAttachments(
    vm: MailboxViewModel,
    attachments: List<MailAttachmentDto>,
    busy: MutableMap<String, Boolean>,
    notices: MutableMap<String, String>,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.reader_attach_share_chooser)
    val images = remember(attachments) { attachments.filter { it.isPreviewableImage() } }
    val files = remember(attachments) { attachments.filterNot { it.isPreviewableImage() } }
    var viewerIndex by remember(attachments) { mutableIntStateOf(-1) }

    val save: (MailAttachmentDto) -> Unit = { a ->
        if (busy[a.id] != true) {
            busy[a.id] = true
            vm.downloadAttachment(a) { outcome ->
                busy.remove(a.id)
                notices[a.id] = outcome
            }
        }
    }
    val share: (MailAttachmentDto) -> Unit = { a ->
        val key = "share:${a.id}"
        if (busy[key] != true) {
            busy[key] = true
            vm.shareAttachment(context.applicationContext, a, shareTitle) { outcome ->
                busy.remove(key)
                if (outcome != null) notices[a.id] = outcome else notices.remove(a.id)
            }
        }
    }
    val open: (MailAttachmentDto) -> Unit = { a ->
        val key = "open:${a.id}"
        if (busy[key] != true) {
            busy[key] = true
            vm.openAttachment(a) { outcome ->
                busy.remove(key)
                notices[a.id] = outcome
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        Text(pluralStringResource(R.plurals.mail_thread_attachment_count, attachments.size, attachments.size),
            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textSecondary)
        Spacer(Modifier.height(8.dp))

        if (images.isNotEmpty()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 6.dp
                val columns = if (images.size == 1) 1
                    else ((maxWidth + gap) / (112.dp + gap)).toInt().coerceIn(2, 5)
                AttachmentGrid(columns = columns, modifier = Modifier.fillMaxWidth(),
                    horizontalGap = gap, verticalGap = gap) {
                    images.forEachIndexed { index, a ->
                        androidx.compose.runtime.key(a.id) {
                            ImageAttachmentTile(vm, a, single = images.size == 1, reducedMotion = reducedMotion,
                                onOpen = { viewerIndex = index })
                        }
                    }
                }
            }
            images.forEach { a ->
                notices[a.id]?.let { note ->
                    Text(note, fontSize = 11.sp, color = palette.textSecondary, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (files.isNotEmpty()) Spacer(Modifier.height(10.dp))
        }

        if (files.isNotEmpty()) {
            // One column on a phone, more as the card widens; fewer as the font grows.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = attachmentGridColumns(maxWidth, LocalDensity.current.fontScale)
                AttachmentGrid(columns = columns, modifier = Modifier.fillMaxWidth(),
                    horizontalGap = 8.dp, verticalGap = 8.dp) {
                    files.forEach { a ->
                        androidx.compose.runtime.key(a.id) {
                            FileAttachmentCard(
                                vm = vm,
                                a = a,
                                saving = busy[a.id] == true,
                                sharing = busy["share:${a.id}"] == true,
                                notice = notices[a.id],
                                onOpen = { open(a) },
                                onSave = { save(a) },
                                onShare = { share(a) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (viewerIndex in images.indices) {
        AttachmentImageViewer(
            vm = vm,
            images = images,
            startIndex = viewerIndex,
            notices = notices,
            saving = { id -> busy[id] == true },
            sharing = { id -> busy["share:$id"] == true },
            onSave = save,
            onShare = share,
            onOpenElsewhere = open,
            reducedMotion = reducedMotion,
            onDismiss = { viewerIndex = -1 },
        )
    }
}

/** A rounded picture tile: the cached thumbnail, or the type icon and name while there is none. */
@Composable
private fun ImageAttachmentTile(
    vm: MailboxViewModel,
    a: MailAttachmentDto,
    single: Boolean,
    reducedMotion: Boolean,
    onOpen: () -> Unit,
) {
    val palette = LocalPalette.current
    val file = rememberAttachmentPreviewFile(vm, a)
    var failed by remember(a.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val name = a.name ?: stringResource(R.string.mail_attachment)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(if (single) 16f / 9f else 1f)
            .clip(shape)
            .background(palette.softFill)
            .border(1.dp, palette.border, shape)
            .clickable(role = Role.Image, onClickLabel = stringResource(R.string.reader_attach_view_image), onClick = onOpen)
            .attachmentDragSource(a, file)
            .testTag("mail-attachment-image"),
        contentAlignment = Alignment.Center,
    ) {
        if (file != null && !failed) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(file).crossfade(!reducedMotion).build(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.Image, contentDescription = null, tint = palette.primarySolid,
                    modifier = Modifier.size(24.dp))
                Text(name, fontSize = 11.sp, color = palette.textSecondary, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A non-image file: type tile (or PDF first page), name, type · size, Save and Share. Tap opens it elsewhere. */
@Composable
private fun FileAttachmentCard(
    vm: MailboxViewModel,
    a: MailAttachmentDto,
    saving: Boolean,
    sharing: Boolean,
    notice: String?,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    val name = a.name ?: stringResource(R.string.mail_attachment)
    // Same rule as before: only a cached previewable image can be dragged, so
    // for these files this is null and the card has no drag.
    val dragFile = rememberAttachmentPreviewFile(vm, a)
    val thumbWidthPx = with(LocalDensity.current) { 44.dp.roundToPx() * 2 }
    // Re-asked after Save / Open / Share report back, so a PDF that has just
    // landed on the device shows its page.
    val pdfPage = rememberDownloadedPdfPage(a, thumbWidthPx, refresh = notice)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(shape)
                .background(palette.card)
                .border(1.dp, palette.border, shape)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.mail_attach_open_another_app), onClick = onOpen)
                .attachmentDragSource(a, dragFile)
                .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp)
                .testTag("mail-attachment-file"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(width = 44.dp, height = 52.dp).clip(RoundedCornerShape(8.dp))
                    .background(palette.softFill),
                contentAlignment = Alignment.Center,
            ) {
                if (pdfPage != null) {
                    Image(pdfPage, contentDescription = null, contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(fileTypeIcon(a), contentDescription = null, tint = palette.primarySolid,
                        modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.textPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(fileTypeLabel(a), a.sizeBytes?.let { humanBytes(it) })
                        .joinToString(" \u00b7 ").ifBlank { stringResource(R.string.mail_attachment_type_unknown) },
                    fontSize = 12.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            AttachmentAction(Icons.Rounded.Download, stringResource(R.string.reader_attach_save_named, name),
                busy = saving, tag = "mail-attachment-save", onClick = onSave)
            AttachmentAction(Icons.Rounded.Share, stringResource(R.string.reader_attach_share_named, name),
                busy = sharing, tag = "mail-attachment-share", onClick = onShare)
        }
        // The outcome is the app's own words about what happened, shown against
        // the card that asked. A silent failure would look like a download.
        if (notice != null) {
            Text(notice, fontSize = 11.sp, color = palette.textSecondary, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 8.dp))
        }
    }
}

@Composable
private fun AttachmentAction(icon: ImageVector, label: String, busy: Boolean, tag: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    IconButton(onClick = onClick, enabled = !busy, modifier = Modifier.testTag(tag)) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = palette.primarySolid, strokeWidth = 2.dp)
        } else {
            Icon(icon, label, tint = palette.primarySolid, modifier = Modifier.size(20.dp))
        }
    }
}

private fun MailAttachmentDto.extension(): String =
    name.orEmpty().substringAfterLast('.', "").lowercase().take(6)

internal fun MailAttachmentDto.isPdf(): Boolean =
    mimeType?.lowercase()?.startsWith("application/pdf") == true || extension() == "pdf"

/** The file type, as the person knows it: the extension ("PDF", "DOCX"), else the MIME subtype. */
private fun fileTypeLabel(a: MailAttachmentDto): String? =
    a.extension().takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }?.uppercase()
        ?: a.mimeType?.substringAfter('/')?.substringBefore(';')?.takeIf { it.isNotBlank() }?.uppercase()?.take(12)

private fun fileTypeIcon(a: MailAttachmentDto): ImageVector {
    val mime = a.mimeType?.lowercase().orEmpty()
    val ext = a.extension()
    return when {
        a.isPdf() -> Icons.Rounded.PictureAsPdf
        mime.startsWith("image/") -> Icons.Rounded.Image
        mime.startsWith("video/") -> Icons.Rounded.VideoFile
        mime.startsWith("audio/") -> Icons.Rounded.AudioFile
        mime == "text/calendar" || ext == "ics" -> Icons.Rounded.Event
        ext in setOf("zip", "rar", "7z", "gz", "tgz", "tar") || mime.contains("zip") || mime.contains("compressed") ->
            Icons.Rounded.FolderZip
        ext in setOf("xls", "xlsx", "csv", "ods", "numbers") || mime.contains("spreadsheet") || mime.contains("excel") ->
            Icons.Rounded.TableChart
        ext in setOf("ppt", "pptx", "key", "odp") || mime.contains("presentation") || mime.contains("powerpoint") ->
            Icons.Rounded.Slideshow
        ext in setOf("doc", "docx", "odt", "rtf", "txt", "md", "pages") || mime.startsWith("text/") ||
            mime.contains("word") -> Icons.Rounded.Description
        else -> Icons.Rounded.AttachFile
    }
}

// ── PDF first page, for PDFs already on this device ───────────────────────

/** The same file-name rule MailboxViewModel uses when it writes an attachment. */
private fun storedAttachmentName(raw: String?): String =
    raw.orEmpty().replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(120).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"

private object PdfFirstPages {
    private val cache = android.util.LruCache<String, Bitmap>(12)

    /**
     * A copy of [a] that is already on this device: a Save (`<uuid>-<name>` in
     * the app's files directory) or an Open / Share / drag copy (`<name>` in a
     * directory under `cache/attachments`). Matched on name AND byte size, so
     * two different "invoice.pdf" files are not confused; without a recorded
     * size nothing is matched.
     */
    fun localCopy(context: android.content.Context, a: MailAttachmentDto): java.io.File? {
        val size = a.sizeBytes?.toLong()?.takeIf { it > 0L } ?: return null
        val name = storedAttachmentName(a.name)
        context.getExternalFilesDir(null)?.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith("-$name") && f.length() == size) return f
        }
        java.io.File(context.cacheDir, "attachments").listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val f = java.io.File(dir, name)
                if (f.isFile && f.length() == size) return f
            }
        }
        return null
    }

    fun page(context: android.content.Context, a: MailAttachmentDto, widthPx: Int): Bitmap? {
        cache.get(a.id)?.let { return it }
        val file = localCopy(context, a) ?: return null
        val bitmap = runCatching { render(file, widthPx) }.getOrNull() ?: return null
        cache.put(a.id, bitmap)
        return bitmap
    }

    private fun render(file: java.io.File, widthPx: Int): Bitmap? {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val renderer = PdfRenderer(descriptor)
            try {
                if (renderer.pageCount < 1) return null
                val page = renderer.openPage(0)
                try {
                    val w = widthPx.coerceIn(32, 512)
                    val h = (w * page.height / page.width.coerceAtLeast(1).toFloat()).toInt().coerceIn(1, w * 2)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    // Paper, not chrome: see the file comment.
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bitmap
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        } finally {
            descriptor.close()
        }
    }
}

/** The first page of [a] when it is a PDF already on this device, else null (and nothing is downloaded). */
@Composable
private fun rememberDownloadedPdfPage(a: MailAttachmentDto, widthPx: Int, refresh: Any?): ImageBitmap? {
    if (!a.isPdf()) return null
    val context = LocalContext.current.applicationContext
    val page by produceState<ImageBitmap?>(initialValue = null, a.id, widthPx, refresh) {
        value = withContext(Dispatchers.IO) { PdfFirstPages.page(context, a, widthPx)?.asImageBitmap() }
    }
    return page
}

// ── Share ─────────────────────────────────────────────────────────────────

/**
 * Hand the attachment to another app with ACTION_SEND. Same rules as
 * `openAttachment`: the bytes go into a fresh directory under the
 * FileProvider's one exposed root (`cache/attachments`), the URI carries a
 * read grant for that one file, the server's MIME type wins, and the session
 * is checked on both sides of the download. [then] gets null when the chooser
 * opened, or the sentence to show when it could not.
 */
internal fun MailboxViewModel.shareAttachment(
    app: android.content.Context,
    a: MailAttachmentDto,
    chooserTitle: String,
    then: (String?) -> Unit,
) {
    viewModelScope.launch {
        val session = SessionStore.snapshot(app)
            ?: run { then(app.getString(R.string.core_sign_in_to_open)); return@launch }
        val outcome: String? = try {
            val name = storedAttachmentName(a.name)
            val file = withContext(Dispatchers.IO) {
                val root = java.io.File(app.cacheDir, "attachments").apply { mkdirs() }
                val dir = java.nio.file.Files.createTempDirectory(root.toPath(), "share-").toFile()
                java.io.File(dir, name)
            }
            Api.mailAttachmentFile(session.token, a.id, file)
            if (!SessionStore.isCurrent(app, session.id)) {
                withContext(Dispatchers.IO) { file.delete() }
                app.getString(R.string.core_attachment_account_changed_open)
            } else {
                val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                val mime = a.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // ClipData carries the grant through the chooser to the app picked.
                send.clipData = android.content.ClipData.newRawUri(name, uri)
                val chooser = android.content.Intent.createChooser(send, chooserTitle)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    app.startActivity(chooser)
                    null
                } catch (_: android.content.ActivityNotFoundException) {
                    app.getString(R.string.reader_attach_share_nothing)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            e.message ?: app.getString(R.string.core_attachment_download_failed)
        } catch (e: Exception) {
            app.getString(R.string.core_attachment_handoff_failed)
        }
        then(outcome)
    }
}

// ── Full-screen viewer ────────────────────────────────────────────────────

/**
 * The pictures of one message, full screen: swipe between them, pinch or
 * double-tap to zoom, drag to look around a zoomed picture (paging pauses
 * while zoomed). A picture too large for the preview cache offers "Open in
 * another app" instead of downloading here. The window inherits the app's
 * secure-window policy (DialogProperties default), so a screen-privacy setting
 * still covers it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentImageViewer(
    vm: MailboxViewModel,
    images: List<MailAttachmentDto>,
    startIndex: Int,
    notices: Map<String, String>,
    saving: (String) -> Boolean,
    sharing: (String) -> Boolean,
    onSave: (MailAttachmentDto) -> Unit,
    onShare: (MailAttachmentDto) -> Unit,
    onOpenElsewhere: (MailAttachmentDto) -> Unit,
    reducedMotion: Boolean,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, images.lastIndex)) { images.size }
        var zoomed by remember { mutableStateOf(false) }
        val current = images[pager.currentPage.coerceIn(0, images.lastIndex)]
        Box(Modifier.fillMaxSize().background(palette.bg).testTag("mail-image-viewer")) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 12.dp,
                userScrollEnabled = !zoomed,
                key = { images[it].id },
            ) { page ->
                ZoomableAttachmentPage(
                    vm = vm,
                    a = images[page],
                    reducedMotion = reducedMotion,
                    onZoomChanged = { z -> if (page == pager.currentPage) zoomed = z },
                    onOpenElsewhere = { onOpenElsewhere(images[page]) },
                )
            }
            Row(
                Modifier.fillMaxWidth().background(palette.bg.copy(alpha = 0.86f))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("mail-image-viewer-close")) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.mail_common_close), tint = palette.textPrimary)
                }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(current.name ?: stringResource(R.string.mail_attachment), fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, color = palette.textPrimary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (images.size > 1) {
                        Text(stringResource(R.string.reader_viewer_position, pager.currentPage + 1, images.size),
                            fontSize = 12.sp, color = palette.textSecondary)
                    }
                }
                val name = current.name ?: stringResource(R.string.mail_attachment)
                AttachmentAction(Icons.Rounded.Download, stringResource(R.string.reader_attach_save_named, name),
                    busy = saving(current.id), tag = "mail-image-viewer-save") { onSave(current) }
                AttachmentAction(Icons.Rounded.Share, stringResource(R.string.reader_attach_share_named, name),
                    busy = sharing(current.id), tag = "mail-image-viewer-share") { onShare(current) }
            }
            notices[current.id]?.let { note ->
                Text(note, fontSize = 12.sp, color = palette.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(palette.card.copy(alpha = 0.92f)).padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun ZoomableAttachmentPage(
    vm: MailboxViewModel,
    a: MailAttachmentDto,
    reducedMotion: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onOpenElsewhere: () -> Unit,
) {
    val palette = LocalPalette.current
    val tooLarge = (a.sizeBytes ?: 0) > 2_000_000
    val file = rememberAttachmentPreviewFile(vm, a)
    var failed by remember(a.id) { mutableStateOf(false) }
    if (file == null || failed) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            if (tooLarge || failed) {
                Icon(Icons.Rounded.Image, null, tint = palette.primarySolid, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.reader_viewer_too_large), fontSize = 14.sp, color = palette.textSecondary)
                TextButton(onClick = onOpenElsewhere) { Text(stringResource(R.string.mail_attach_open_another_app)) }
            } else {
                CircularProgressIndicator(color = palette.primarySolid)
            }
        }
        return
    }
    val scope = rememberCoroutineScope()
    var scale by remember(a.id) { mutableFloatStateOf(1f) }
    var offset by remember(a.id) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    fun zoomTo(target: Float, focus: Offset) {
        val startScale = scale
        val startOffset = offset
        val center = Offset(size.width / 2f, size.height / 2f)
        val endOffset = if (target <= 1f) Offset.Zero else clamp((center - focus) * (target - 1f), target)
        onZoomChanged(target > 1f)
        scope.launch {
            animate(0f, 1f, animationSpec = if (reducedMotion) snap() else tween(220)) { t, _ ->
                scale = startScale + (target - startScale) * t
                offset = startOffset + (endOffset - startOffset) * t
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(a.id) {
                detectTapGestures(onDoubleTap = { tap -> zoomTo(if (scale > 1.05f) 1f else 2.5f, tap) })
            }
            .pointerInput(a.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        // One finger on an unzoomed picture belongs to the pager.
                        if (fingers >= 2 || scale > 1.01f) {
                            val next = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            scale = next
                            offset = if (next <= 1f) Offset.Zero else clamp(offset + event.calculatePan(), next)
                            onZoomChanged(next > 1.01f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(file).crossfade(!reducedMotion).build(),
            contentDescription = a.name,
            contentScale = ContentScale.Fit,
            onError = { failed = true },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
