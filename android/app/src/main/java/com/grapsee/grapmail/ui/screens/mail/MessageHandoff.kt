package com.grapsee.grapmail.ui.screens.mail

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.MailMessageFullDto
import com.grapsee.grapmail.ui.theme.LocalPalette
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The reader's three handoffs that leave the app, and the one opt-in that lets
 * something into it: the text a message becomes outside (share, print), the
 * images the sender's own server hosts, and the paginated document a print
 * service reads.
 *
 * They live together because they are one subject — what a message is allowed to
 * do to the device once the person asks — and they live outside the screen file
 * because none of them is layout.
 *
 * ── WHY ONE TEXT, NOT TWO ───────────────────────────────────────────────────
 * Share and print would each have built their own copy of the same document.
 * Two builders drift, and the drift is invisible: the shared card would say the
 * mail came from somebody while the printed page said nothing, or one of them
 * would start carrying the HTML part. One function, both handoffs.
 *
 * ── WHY NO HTML IN EITHER ───────────────────────────────────────────────────
 * The message's HTML part is not fetched, not inlined, and not handed over. A
 * receiving app, or a printer's own renderer, would then be doing exactly what
 * the reader refuses to do: render a remote document, with its tracking requests
 * and its script, in a surface this app does not control. What goes out is the
 * header the server holds and the text part — the two things the screen shows.
 */

/** The size the print document sets its text at, in PDF points. One number,
 *  because the layout measurement and the page drawing must agree exactly. */
private const val PRINT_TEXT_SIZE = 12f
private const val PRINT_LINE_HEIGHT = 17f

/**
 * The document a message becomes outside this app: subject, the people it came
 * from and went to, its date, then the text part.
 *
 * Every line is what the server answered with. A message with no text body says
 * that, rather than inventing a sentence for the gap, and a message whose only
 * part is HTML says it was printed as plain text — the same admission the screen
 * makes, one step further out.
 *
 * `res` supplies the labels in the person's language (`R.string.mail_share_*`);
 * the values themselves are still only what the server answered with.
 */
fun messageShareText(
    message: MailMessageFullDto,
    formattedText: String?,
    res: android.content.res.Resources,
): String = buildString {
    val notRecorded = res.getString(R.string.mail_share_not_recorded)
    appendLine(message.subject?.takeIf { it.isNotBlank() } ?: res.getString(R.string.mail_no_subject))
    val from = message.from?.display?.takeIf { it.isNotBlank() }
        ?: message.from?.address?.takeIf { it.isNotBlank() }
    appendLine(res.getString(R.string.mail_share_from, from ?: notRecorded))
    if (message.to.isNotEmpty()) appendLine(res.getString(R.string.mail_share_to, message.to.joinToString(", ")))
    if (message.cc.isNotEmpty()) appendLine(res.getString(R.string.mail_share_cc, message.cc.joinToString(", ")))
    appendLine(res.getString(R.string.mail_share_date, message.createdAt ?: notRecorded))
    appendLine()
    val text = message.bodyText?.takeIf { it.isNotBlank() }
        ?: formattedText?.takeIf { it.isNotBlank() }
    when {
        text != null -> append(text)
        else -> append(res.getString(R.string.mail_share_no_text_body))
    }
}

/**
 * The `src` addresses an HTML part points at, http(s) only.
 *
 * The part itself is never rendered, so this is not a sanitiser and does not
 * pretend to be one: it reads ONE attribute out of ONE tag, then applies a rule
 * that does not depend on the sender's markup being well-formed — a value is
 * kept when its scheme is http or https and it parses as an absolute URI with a
 * host, and everything else is dropped. That is the whole surface. `data:` needs
 * no consent because it asks nothing of the network; `cid:` is an attachment's
 * own address and belongs to the attachment rows; a `javascript:` value reaches
 * no request at all.
 *
 * Duplicates collapse to their first occurrence, because a sender who repeats
 * one tracking pixel three times is asking for one request, not three.
 */
fun remoteImageUrls(html: String?): List<String> {
    if (html.isNullOrEmpty()) return emptyList()
    val found = LinkedHashSet<String>()
    val pattern = Regex("(?is)<img[^>]*?\\bsrc\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
    for (match in pattern.findAll(html)) {
        val raw = match.groupValues[2]
            .ifEmpty { match.groupValues[3] }
            .ifEmpty { match.groupValues[4] }
            .replace("&amp;", "&")
        if (raw.isNotBlank()) absoluteImageUrl(raw)?.let { found.add(it) }
    }
    return found.toList()
}

/**
 * The one rule that decides whether a sender's address is ever contacted: an
 * absolute http(s) URI with a host, or nothing.
 */
private fun absoluteImageUrl(raw: String): String? {
    val trimmed = raw.trim()
    val scheme = trimmed.substringBefore(":", "").lowercase()
    if (scheme != "http" && scheme != "https") return null
    return runCatching {
        val probe = URI(trimmed)
        if (probe.isAbsolute && !probe.host.isNullOrBlank()) trimmed else null
    }.getOrNull()
}

/**
 * The image loader's own client, deliberately not the API's.
 *
 * A sender's server has no business holding a GrapMail session, so this client
 * carries no auth header, no cookie jar and no interceptor that could add
 * either. It talks to a host the sender chose, on a request the person chose,
 * and nothing else travels with it.
 */
private val imageClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

/** What a fetch can end as: bytes that decoded, or the reason they did not. */
private sealed class RemoteImageOutcome {
    data class Ready(val bitmap: ImageBitmap) : RemoteImageOutcome()
    data class Problem(val reason: String) : RemoteImageOutcome()
}

/**
 * One remote image, fetched on this message's own opt-in.
 *
 * Three states are said, because two would be a guess: nothing is requested
 * until this composable first draws (the caller only builds it after the person
 * taps Show images), a load that has not answered is loading, and a load that
 * answered badly names which of the real reasons it did — the host refused it,
 * it was not reachable, it was not an image, or the bytes would not decode. A
 * blank box reads as a broken app; a merged sentence reads as a lie.
 */
@Composable
fun RemoteImageView(url: String, label: String) {
    val palette = LocalPalette.current
    val res = LocalContext.current.resources
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var note by remember(url) { mutableStateOf<String?>(null) }
    var loading by remember(url) { mutableStateOf(true) }

    LaunchedEffect(url) {
        loading = true
        note = null
        bitmap = null
        when (val outcome = withContext(Dispatchers.IO) { fetchRemoteImage(url, res) }) {
            is RemoteImageOutcome.Ready -> { bitmap = outcome.bitmap; loading = false }
            is RemoteImageOutcome.Problem -> { note = outcome.reason; loading = false }
        }
    }

    val shown = bitmap
    when {
        shown != null -> Image(
            painter = BitmapPainter(shown),
            contentDescription = label,
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit,
        )
        loading -> Box(
            Modifier.fillMaxWidth().height(64.dp)
                .background(palette.softFill, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.mail_remote_image_loading), fontSize = 12.sp, color = palette.textTertiary)
        }
        else -> note?.let { reason ->
            Box(
                Modifier.fillMaxWidth()
                    .background(palette.softFill, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(reason, fontSize = 12.sp, color = palette.textSecondary,
                    modifier = Modifier.padding(12.dp).testTag("mail-remote-image-failed"))
            }
        }
    }
}

/** The four answers a fetch can have, named once so the screen can say which. */
private fun fetchRemoteImage(url: String, res: android.content.res.Resources): RemoteImageOutcome {
    val request = runCatching { Request.Builder().url(url).get().build() }
        .getOrElse {
            return RemoteImageOutcome.Problem(res.getString(R.string.mail_remote_image_bad_address))
        }
    return runCatching {
        imageClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                return RemoteImageOutcome.Problem(
                    res.getString(R.string.mail_remote_image_refused, resp.code))
            }
            val type = resp.body?.contentType()?.type ?: ""
            if (!type.startsWith("image/")) {
                // The answer that matters most: the address is not a picture.
                // An <img> tag does not license this app to hand a document to
                // a renderer, so the content type is checked, not trusted.
                return RemoteImageOutcome.Problem(
                    res.getString(R.string.mail_remote_image_not_image,
                        type.ifBlank { res.getString(R.string.mail_remote_image_no_type) }),
                )
            }
            val bytes = resp.body?.bytes()
            // A 6000x4000 newsletter image decoded full-size costs ~90 MB of
            // bitmap RAM for a box that draws at 420dp. Bounds-decode first,
            // then sample down to roughly the display size — the same pattern
            // MailPollWorker's notification image already uses.
            val decoded = bytes?.let { data ->
                val bounds = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                val maxSide = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                var sample = 1
                while (maxSide / (sample * 2) >= 1080) sample *= 2
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    .let { opts -> android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, opts) }
            }
            if (decoded == null) {
                RemoteImageOutcome.Problem(res.getString(R.string.mail_remote_image_unreadable))
            } else {
                RemoteImageOutcome.Ready(decoded.asImageBitmap())
            }
        }
    }.getOrElse {
        if (it is java.io.IOException) {
            RemoteImageOutcome.Problem(res.getString(R.string.mail_remote_image_unreachable))
        } else {
            RemoteImageOutcome.Problem(res.getString(R.string.mail_remote_image_unreadable))
        }
    }
}

/**
 * Print the message as the plain-text document the screen shows, through the
 * device's own print service.
 *
 * There is no browser in this path and there deliberately is not one: the usual
 * Android recipe is an off-screen WebView of the HTML part, which is the exact
 * thing the reader's rule refuses — a remote document rendered with its own
 * requests and its own script. So the adapter paginates measured text into a PDF
 * the print service reads. The cost is a plainer page; the gain is that printing
 * a message cannot make a request the person did not ask for.
 *
 * The outcome is reported, not swallowed: a device with no print service, or
 * with it switched off, is a real state, and a button that appeared to do
 * nothing reads as a broken app.
 */
fun printMessageText(
    context: Context,
    message: MailMessageFullDto,
    formattedText: String? = null,
    onOutcome: (String?) -> Unit,
) {
    val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
    if (manager == null) {
        onOutcome(context.getString(R.string.mail_print_no_service))
        return
    }
    val docName = (message.subject?.takeIf { it.isNotBlank() } ?: context.getString(R.string.mail_print_doc_name))
        .take(60)
    // The attributes the system hands `onLayout` are the printer's own, and the
    // page geometry is read from them. What is NOT asked for is the device's
    // last-used attributes: `print()` takes the attributes to *request*, and the
    // panel lets the person change them, so the layout pass is where the real
    // numbers arrive.
    val adapter = TextPrintAdapter(messageShareText(message, formattedText, context.resources), docName)
    val handed = runCatching {
        manager.print(docName, adapter, PrintAttributes.Builder().build())
    }.isSuccess
    // `null` means the request reached the print service, which is the most this
    // process can witness: what the person then does with the system's print
    // screen — pick a printer, cancel, save as PDF — belongs to that screen.
    onOutcome(if (handed) null else context.getString(R.string.mail_print_no_screen))
}

/**
 * A [PrintDocumentAdapter] for a string.
 *
 * `onLayout` reports how many pages the wrapped text needs at the attributes the
 * system asked for, and remembers those attributes; `onWrite` draws only the
 * pages requested into the descriptor the system opened. Both must wrap the text
 * the same way, which is why the arithmetic lives in the three functions below
 * and nowhere else — a document that lays out one way and writes another prints
 * pages nobody checked.
 */
private class TextPrintAdapter(
    private val text: String,
    private val jobName: String,
) : PrintDocumentAdapter() {

    private val body = Paint().apply {
        color = Color.BLACK
        textSize = PRINT_TEXT_SIZE
        isAntiAlias = true
    }
    private val heading = Paint().apply {
        color = Color.BLACK
        textSize = 15f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }
    private var attrs = PrintAttributes.Builder().build()

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback?,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback?.onLayoutCancelled()
            return
        }
        newAttributes?.let { attrs = it }
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pages(attrs, text))
            .build()
        // "changed" is honest here for the same reason it is always honest for a
        // fresh adapter: there is no previous layout of this job to compare with.
        callback?.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback?,
    ) {
        val ranges = pages ?: emptyArray()
        val descriptor = destination
        if (descriptor == null) {
            callback?.onWriteFailed("The print document had nowhere to be written.")
            return
        }
        if (cancellationSignal?.isCanceled == true) {
            callback?.onWriteCancelled()
            return
        }
        val document = PdfDocument()
        try {
            val lines = layoutLines(attrs, text)
            val perPage = linesPerPage(attrs)
            val lastPage = (lines.size + perPage - 1) / perPage
            for (range in ranges) {
                for (index in range.start..range.end) {
                    val page = document.startPage(
                        PdfDocument.PageInfo.Builder(
                            pageWidth(attrs), pageHeight(attrs), index + 1,
                        ).create(),
                    )
                    drawPage(page.canvas, lines, index, perPage, lastPage)
                    document.finishPage(page)
                }
            }
            FileOutputStream(descriptor.fileDescriptor).use { document.writeTo(it) }
            document.close()
            callback?.onWriteFinished(ranges)
        } catch (e: Exception) {
            runCatching { document.close() }
            callback?.onWriteFailed(e.message ?: "The print document could not be written.")
        }
    }

    private fun drawPage(
        canvas: Canvas, lines: List<String>, index: Int, perPage: Int, lastPage: Int,
    ) {
        val left = contentInset(attrs).toFloat()
        val top = contentInset(attrs).toFloat()
        val start = index * perPage
        for (row in 0 until perPage) {
            val line = lines.getOrNull(start + row) ?: break
            // The document's first line is its subject, and it is set in the
            // bold face the screen uses for it too. Everything after is body.
            val pen = if (index == 0 && row == 0) heading else body
            canvas.drawText(line, left, top + row * PRINT_LINE_HEIGHT + PRINT_TEXT_SIZE, pen)
        }
        // The page number is the only statement this document can make about
        // itself being one of several; without it a printed sheet cannot be put
        // back in order.
        if (lastPage > 1) {
            canvas.drawText("${index + 1} / $lastPage", left,
                top + perPage * PRINT_LINE_HEIGHT + PRINT_TEXT_SIZE + 14f, body)
        }
    }

    override fun onFinish() {
        // Nothing to release: the adapter holds only the text it was given.
    }
}

// ── the adapter's arithmetic, outside the class so it can be read on its own ─
//
// `MediaSize` answers in MILS (thousandths of an inch) and a PDF page is laid
// out in POINTS (72 to an inch), so every number here crosses that unit once,
// in one place. The margins are the document's own 48pt (about 17mm) rather
// than the printer's `minMargins`, for the same reason: `minMargins` is also in
// mils, the print panel applies its own content area to the finished PDF, and
// a page that sets its own generous inset cannot run text off the sheet on any
// printer — while one computed from per-device numbers can be wrong on the only
// device that matters, the one a person actually owns.

/** Mils to points: 1000 mils is one inch, one inch is 72 points. */
private fun milsToPoints(mils: Int): Int = (mils * 72 / 1000.0).toInt()

private const val PRINT_MARGIN = 48

private fun pageWidth(attrs: PrintAttributes): Int =
    milsToPoints(runCatching { attrs.mediaSize?.widthMils ?: 8500 }.getOrNull() ?: 8500)
        .coerceAtLeast(240)

private fun pageHeight(attrs: PrintAttributes): Int =
    milsToPoints(runCatching { attrs.mediaSize?.heightMils ?: 11000 }.getOrNull() ?: 11000)
        .coerceAtLeast(320)

private fun contentInset(attrs: PrintAttributes): Int = PRINT_MARGIN

private fun linesPerPage(attrs: PrintAttributes): Int {
    val usable = pageHeight(attrs) - contentInset(attrs) * 2 - 24
    return (usable / PRINT_LINE_HEIGHT).toInt().coerceAtLeast(1)
}

/** The wrapped lines of the document, in print order. */
private fun layoutLines(attrs: PrintAttributes, text: String): List<String> {
    val measure = Paint().apply { textSize = PRINT_TEXT_SIZE; isAntiAlias = true }
    val width = (pageWidth(attrs) - contentInset(attrs) * 2).toFloat().coerceAtLeast(40f)
    val out = ArrayList<String>()
    for (paragraph in text.split("\n")) {
        if (paragraph.isBlank()) {
            out.add("")
            continue
        }
        var line = ""
        for (word in paragraph.split(" ")) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (measure.measureText(candidate) <= width || line.isEmpty()) {
                line = candidate
            } else {
                out.add(line)
                line = word
            }
        }
        if (line.isNotEmpty()) out.add(line)
    }
    return out
}

private fun pages(attrs: PrintAttributes, text: String): Int {
    val perPage = linesPerPage(attrs)
    val total = layoutLines(attrs, text).size
    return ((total + perPage - 1) / perPage).coerceAtLeast(1)
}
