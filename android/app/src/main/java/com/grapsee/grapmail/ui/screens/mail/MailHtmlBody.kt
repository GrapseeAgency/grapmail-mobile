package com.grapsee.grapmail.ui.screens.mail

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.grapsee.grapmail.ui.components.EmailDarkening
import com.grapsee.grapmail.ui.components.GMMotion
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlin.math.roundToInt

/**
 * The reader's formatted-body island — the ONE Android surface allowed to put
 * a message in a WebView, and the reason it can be one at all.
 *
 * ── WHAT MAY REACH THIS VIEW ────────────────────────────────────────────────
 * Only the string the body endpoint answered with (`MailBodyResponse.bodyHtml`):
 * the server sanitized the sender's HTML and filtered its inline styles, so by the time
 * markup gets here there is no script, no iframe, no form, no event handler
 * and no live remote image in it. The raw `message.bodyHtml` the row carries
 * is never passed here — there is no sanitiser in this app's dependencies, and
 * the correct one is not a regex. A failure upstream falls back to the
 * stripped-text branch the reader always had; it never falls back to raw HTML.
 *
 * The engine is belt-and-braces behind the sanitiser, not a substitute for it:
 * page script is off at the engine level, file and content access are off,
 * mixed content is refused, and `blockNetworkImage` mirrors the images gate
 * the person controls — the sanctioned HTML already has no remote `src` until
 * they opt in, so this setting only has something to block if a future
 * sanitizer regression ever ships one.
 *
 * Every navigation is refused INSIDE the view: a link tap is handed to
 * `onOpenLink` (the same open-externally path the unsubscribe offer uses), so
 * the message can never point the WebView at itself or keep the reader open.
 *
 * ── DARK MODE ───────────────────────────────────────────────────────────────
 * A dark reader no longer strips the sender's colours: [EmailDarkening]
 * redraws a light design dark (light fills dark, dark text light, brand fills
 * and buttons kept, table lines kept visible, images untouched, 4.5:1 text).
 * Android WebView has no isolated script world, so in that one case the
 * engine's script switch is ON — solely so `evaluateJavascript` can run the
 * app's own [EmailDarkening.SCRIPT]. Nothing the message carries can run: the
 * document CSP is unchanged (`default-src 'none'`, i.e. no script source, no
 * inline handler, no `javascript:` URL, no eval), no JavaScript interface is
 * ever added, and windows cannot open. Light mode, and a dark reader with
 * darkening switched off ("Show original colours", ThreadScreen.kt, remembered
 * per sender by ReaderColourPrefs.kt), keep the script switch OFF exactly as
 * before. The WebView is painted the reader card colour from the first frame
 * and the body stays invisible until the script finishes (one second at most),
 * so there is no white flash and no flash of the undarkened design.
 *
 * ── TEXT SIZE AND WIDTH ─────────────────────────────────────────────────────
 * Text follows the system font size: `textZoom` is the composition's own
 * `LocalDensity.fontScale` (so a per-app or nonlinear Android 14 scale is the
 * one the rest of the reader uses), capped at 200 % and floored at 50 %, and
 * re-applied whenever it changes; the height is measured again afterwards.
 *
 * The sender's markup is wrapped in one app-owned box (`#gm-own-x`) that
 * scrolls sideways. Layouts are still squeezed to the reader width first (the
 * max-width rules below); whatever cannot shrink further — a fixed-width
 * newsletter table, a wide image row, large text in fixed cells at 200 % —
 * scrolls horizontally inside that box instead of running off the card or
 * being clipped. The page itself never becomes wider than the view. Only the
 * root clips sideways (`html`), never `body`: a body that became a scroll
 * container would, under a sender's `body{height:100%}`, scroll inside itself
 * and the card would measure one screen tall.
 *
 * Reading scale: 16 px on a 1.5 line, the same measure as the plain-text body
 * (ReaderBody.kt `ReaderType.body`), for whatever the sender did not size
 * themselves; the column it sits in is capped at `GMLayout.Reading`.
 *
 * ── LOADING WITHOUT JUMPS ─────────────────────────────────────────────────
 * The body holds [reservedHeight] (the caller's estimate, or the height this
 * same document measured last time — [ReaderBodyHeights]) with placeholder
 * bars over it, while the WebView itself lays out at 1 dp. A WebView never
 * reports a content height smaller than its own height, so laying out tiny is
 * what lets the first measurement be the document's real height, whether
 * shorter or taller than the estimate. Once measured, the view takes that
 * height and the held space animates to it ONCE (a snap under reduced
 * motion); the bars fade out. Images that declare width / height keep their
 * box while loading (Chromium derives the aspect ratio from the attributes
 * under `height:auto`); a late image that grows the document is measured
 * again and the box follows.
 */

/** The single holder that remembers what a WebView instance was last fed. */
private class HtmlFeedState {
    var document: String? = null
    var allowImages: Boolean? = null
    /** Whether the loaded document expects [EmailDarkening.SCRIPT]. */
    var darkening: Boolean = false
    var measurement: Runnable? = null
}

private fun hex(c: androidx.compose.ui.graphics.Color): String =
    String.format("#%06X", c.toArgb() and 0xFFFFFF)

/** The WebView text zoom for a font scale: the system scale, capped at 200 % and floored at 50 %. */
internal fun mailTextZoom(fontScale: Float): Int =
    (fontScale * 100f).roundToInt().coerceIn(50, 200)

/**
 * The document around the sanitized fragment. App-authored, constant except
 * for the palette colours interpolated into it — this is the island's own
 * typography, not something a sender styled.
 */
private fun mailDocument(
    html: String,
    allowImages: Boolean,
    darkening: Boolean,
    surface: String,
    text: String,
    muted: String,
    border: String,
    link: String,
    chip: String,
): String {
    // https only: the server upgrades http images, and a cleartext request
    // would show the network what was opened.
    // data: is only ever an inline image of this same message (the server draws
    // cid: parts from the message's own attachments), so it shows without the
    // remote-image opt-in and tells no sender anything.
    val imageSources = if (allowImages) "data: https:" else "data:"
    val authoredColors = html.contains(" style=\"") || html.contains(" bgcolor=\"")
    // A sender's light design keeps its own white canvas whenever nothing is
    // going to darken it: light mode, or a dark reader with darkening off.
    // Under darkening the canvas is the reader card and the script redraws
    // the sender's fills, so no white box is ever painted.
    val lightDesign = authoredColors && !darkening
    val canvas = if (lightDesign) "#FFFFFF" else "transparent"
    val foreground = if (lightDesign) "#1F2937" else text
    val linkColor = if (lightDesign) "#174EA6" else link
    val mutedColor = if (lightDesign) "#4B5563" else muted
    val chipColor = if (lightDesign) "#F3F4F6" else chip
    return "<!DOCTYPE html><html" + EmailDarkening.rootAttributes(darkening, surface, text) +
        "><head><meta charset=\"utf-8\"/>" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
        "img-src $imageSources; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\">" +
        "<style data-gm-own>" +
        "html,body{margin:0;padding:0;background:$canvas;}" +
        "html{overflow-x:hidden;}" +
        "body{font-family:Roboto,'Segoe UI',sans-serif;font-size:16px;line-height:1.5;letter-spacing:0.1px;color:" + foreground + ";" +
        "-webkit-text-size-adjust:100%;word-wrap:break-word;overflow-wrap:break-word;padding:0 2px;}" +
        // The app's own sideways scroller around the sender's markup (see
        // TEXT SIZE AND WIDTH above). An id, with !important, so no sender
        // style can switch it off.
        "#gm-own-x{display:block!important;width:100%!important;max-width:100%!important;" +
        "overflow-x:auto!important;overflow-y:hidden!important;-webkit-overflow-scrolling:touch;}" +
        "a{color:$linkColor;}" +
        "img{max-width:100%!important;height:auto;}" +
        "table,div{max-width:100%!important;}" +
        "table,div,td,th{min-width:0!important;overflow-wrap:anywhere;}" +
        "pre,code{font-family:monospace;font-size:13px;white-space:pre-wrap;overflow-wrap:anywhere;}" +
        "blockquote{margin:8px 0;padding:2px 12px;border-left:3px solid " + border + ";color:" + mutedColor + ";}" +
        // A blocked remote image has no `src` at all — Chromium draws its alt
        // text as an inline broken image, and this turns that into a
        // deliberate-looking chip instead of a broken-image glyph.
        "img[data-blocked-src],img[data-grapmail-blocked-src],img[data-cid-src]{display:inline-block;min-height:16px;padding:4px 10px;" +
        "border:1px dashed " + border + ";border-radius:8px;background:" + chipColor + ";color:" + mutedColor + ";font-size:12px;}" +
        EmailDarkening.pendingCss(darkening) +
        "</style></head><body><div id=\"gm-own-x\">" + html + "</div></body></html>"
}

// The switch is ON only under dark-mode darkening, for the app's own
// evaluateJavascript call; the document CSP still refuses every script the
// message could carry (see the DARK MODE note above).
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HtmlMailBody(
    html: String,
    allowImages: Boolean,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** false = show the sender's original colours in a dark reader (ThreadScreen.kt's toggle). */
    darkeningEnabled: Boolean = true,
    /** Space held until the document has measured itself (see LOADING WITHOUT JUMPS). */
    reservedHeight: Dp = 180.dp,
    /** This body's identity for [ReaderBodyHeights] (message id + part), or null for none. */
    heightKey: String? = null,
    /** The system "Remove animations" reading: the held space snaps instead of animating. */
    reducedMotion: Boolean = false,
) {
    val palette = LocalPalette.current
    val textZoom = mailTextZoom(LocalDensity.current.fontScale)
    val darkening = EmailDarkening.isActive(palette.card.luminance() < 0.5f, darkeningEnabled)
    val surfaceArgb = palette.card.toArgb()
    val document = remember(html, allowImages, palette, darkening) {
        mailDocument(html, allowImages, darkening, hex(palette.card),
            hex(palette.textPrimary), hex(palette.textSecondary),
            hex(palette.border), hex(palette.primarySolid), hex(palette.bgTinted))
    }
    // The height memory's key: this part, this exact document, this window
    // width and this text size. Any of them changing measures afresh.
    val windowWidthDp = LocalConfiguration.current.screenWidthDp
    val memoryKey = heightKey?.let { "$it|${document.hashCode()}|$windowWidthDp|$textZoom" }
    val currentMemoryKey by rememberUpdatedState(memoryKey)
    // CSS height of the loaded document, in dp. Measured on finish and once
    // again after a beat, because images landing late grow the document
    // without a second finish event.
    var contentHeightDp by remember { mutableFloatStateOf(0f) }
    // The last real measurement survives a re-measure (text size change), so
    // the held space keeps it instead of dropping back to the estimate.
    var lastMeasuredDp by remember { mutableFloatStateOf(0f) }
    val feed = remember { HtmlFeedState() }
    val measureHeight = remember {
        { view: WebView -> Runnable {
            val height = view.contentHeight.toFloat()
            if (height > 0f && height != contentHeightDp) {
                contentHeightDp = height
                lastMeasuredDp = height
                currentMemoryKey?.let { ReaderBodyHeights.store(it, height) }
            }
        } }
    }
    val measured = contentHeightDp > 0f
    val recalled = memoryKey?.let { ReaderBodyHeights.recall(it) }
    val heldHeight = when {
        measured -> contentHeightDp.dp
        recalled != null -> recalled.dp
        lastMeasuredDp > 0f -> lastMeasuredDp.dp
        else -> reservedHeight
    }
    val shownHeight by animateDpAsState(
        targetValue = heldHeight,
        animationSpec = GMMotion.tween(reducedMotion, GMMotion.LongMs, GMMotion.EmphasizedDecelerate),
        label = "mail-body-height",
    )
    val veil by animateFloatAsState(
        targetValue = if (measured || lastMeasuredDp > 0f) 0f else 1f,
        animationSpec = GMMotion.tween(reducedMotion, GMMotion.MediumMs),
        label = "mail-body-veil",
    )

    Box(modifier.fillMaxWidth().height(shownHeight).clipToBounds()) {
    AndroidView(
        // 1 dp until measured: see LOADING WITHOUT JUMPS above.
        modifier = Modifier
            .fillMaxWidth()
            .height(if (measured) contentHeightDp.dp else 1.dp),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(if (darkening) surfaceArgb else android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkImage = !allowImages
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.loadsImagesAutomatically = true
                settings.mediaPlaybackRequiresUserGesture = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.textZoom = textZoom
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.domStorageEnabled = false
                settings.databaseEnabled = false
                settings.setGeolocationEnabled(false)
                settings.saveFormData = false
                // No second window to open: the sanitizer forces target=_blank
                // on links, and this makes that a hand-off, not a popup.
                settings.setSupportMultipleWindows(false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return true
                        // Everything is refused in here. http(s) and mailto go
                        // to the person's own apps; anything else — about:,
                        // intent:, a relative path the null base URL cannot
                        // resolve — goes nowhere at all.
                        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
                            onOpenLink(url)
                        }
                        return true
                    }

                    // A message that crashes the renderer must not take the app
                    // with it: the default answer to a dead renderer is to kill
                    // the process. The dead view is hidden; onRelease still
                    // destroys it when the card leaves.
                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: android.webkit.RenderProcessGoneDetail?,
                    ): Boolean {
                        view?.visibility = android.view.View.GONE
                        return true
                    }

                    // Darkening runs at the first visible commit (the string
                    // document is parsed by then; the body is still invisible)
                    // and once more at finish for anything parsed later — the
                    // script only visits elements it has not seen yet.
                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                        if (feed.darkening) view?.evaluateJavascript(EmailDarkening.SCRIPT, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        view ?: return
                        if (feed.darkening) view.evaluateJavascript(EmailDarkening.SCRIPT, null)
                        feed.measurement?.let { view.removeCallbacks(it) }
                        val measure = measureHeight(view)
                        feed.measurement = measure
                        view.postDelayed(measure, 60)
                        // A second look in every case: the body lays out at
                        // 1 dp until measured, so a first reading that came
                        // too early must not leave it at the placeholder.
                        view.postDelayed(measure, 320)
                        if (feed.allowImages == true) view.postDelayed(measure, 650)
                    }
                }
            }
        },
        update = { webView ->
            if (webView.settings.textZoom != textZoom) {
                webView.settings.textZoom = textZoom
                // A smaller text size has to be able to SHRINK the card: the
                // view's content height never reports less than the view's own
                // height, so the view drops to 1 dp for one measure (the held
                // space keeps its last height meanwhile) and takes the real one.
                contentHeightDp = 0f
                feed.measurement?.let { webView.removeCallbacks(it) }
                val measure = measureHeight(webView)
                feed.measurement = measure
                webView.postDelayed(measure, 120)
                webView.postDelayed(measure, 450)
            }
            // Re-feed only on a real change: update runs on every recomposition,
            // and loadHTML-style reloads would restart rendering mid-scroll.
            if (feed.document != document || feed.allowImages != allowImages) {
                feed.measurement?.let { webView.removeCallbacks(it) }
                feed.document = document
                feed.allowImages = allowImages
                feed.darkening = darkening
                webView.settings.blockNetworkImage = !allowImages
                // Script switch follows darkening, set before the load so the
                // new document is never parsed under the previous setting.
                webView.settings.javaScriptEnabled = darkening
                // Dark from the very first frame: the card colour, not a
                // transparent view the system may fill white before content.
                webView.setBackgroundColor(if (darkening) surfaceArgb else android.graphics.Color.TRANSPARENT)
                webView.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        onRelease = { webView ->
            feed.measurement?.let { webView.removeCallbacks(it) }
            feed.measurement = null
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.destroy()
        },
    )
    if (veil > 0f) {
        ReaderBodySkeleton(Modifier.fillMaxWidth().height(shownHeight).graphicsLayer { alpha = veil })
    }
    } // Box
}
