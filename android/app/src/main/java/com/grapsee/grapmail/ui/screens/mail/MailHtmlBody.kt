package com.grapsee.grapmail.ui.screens.mail

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * The reader's formatted-body island — the ONE Android surface allowed to put
 * a message in a WebView, and the reason it can be one at all.
 *
 * ── WHAT MAY REACH THIS VIEW ────────────────────────────────────────────────
 * Only the string the body endpoint answered with (`MailBodyResponse.bodyHtml`):
 * the server sanitized the sender's HTML part with the web's
 * `sanitizeEmailHtml` profile before it left the app's origin, so by the time
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
 */

/** The single holder that remembers what a WebView instance was last fed. */
private class HtmlFeedState {
    var html: String? = null
    var allowImages: Boolean? = null
    var measurement: Runnable? = null
}

private fun hex(c: androidx.compose.ui.graphics.Color): String =
    String.format("#%06X", c.toArgb() and 0xFFFFFF)

/**
 * The document around the sanitized fragment. App-authored, constant except
 * for the palette colours interpolated into it — this is the island's own
 * typography, not something a sender styled.
 */
private fun mailDocument(html: String, text: String, muted: String, border: String, link: String, chip: String): String =
    "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>" +
        "<style>" +
        "html,body{margin:0;padding:0;background:transparent;}" +
        "body{font-family:Roboto,'Segoe UI',sans-serif;font-size:15px;line-height:1.55;color:" + text + ";" +
        "-webkit-text-size-adjust:100%;word-wrap:break-word;overflow-wrap:break-word;padding:0 2px;}" +
        "a{color:" + link + ";}" +
        "img{max-width:100%;height:auto;}" +
        "table{max-width:100%;border-collapse:collapse;}" +
        "td,th{word-wrap:break-word;}" +
        "pre,code{font-family:monospace;font-size:13px;white-space:pre-wrap;}" +
        "blockquote{margin:8px 0;padding:2px 12px;border-left:3px solid " + border + ";color:" + muted + ";}" +
        // A blocked remote image has no `src` at all — Chromium draws its alt
        // text as an inline broken image, and this turns that into a
        // deliberate-looking chip instead of a broken-image glyph.
        "img[data-grapmail-blocked-src]{display:inline-block;min-height:16px;padding:4px 10px;" +
        "border:1px dashed " + border + ";border-radius:8px;background:" + chip + ";color:" + muted + ";font-size:12px;}" +
        "</style></head><body>" + html + "</body></html>"

@SuppressLint("SetJavaScriptEnabled") // the setting is set to FALSE; the lint name is the inverse of the call
@Composable
internal fun HtmlMailBody(
    html: String,
    allowImages: Boolean,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    // CSS height of the loaded document, in dp. Measured on finish and once
    // again after a beat, because images landing late grow the document
    // without a second finish event.
    var contentHeightDp by remember { mutableFloatStateOf(0f) }
    val feed = remember { HtmlFeedState() }
    val measureHeight = remember {
        { view: WebView -> Runnable {
            val height = view.contentHeight.toFloat()
            if (height > 0f && height != contentHeightDp) contentHeightDp = height
        } }
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) {
                (if (contentHeightDp > 0f) contentHeightDp else 180f).dp
            }),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkImage = !allowImages
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mediaPlaybackRequiresUserGesture = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.textZoom = 100
                settings.javaScriptCanOpenWindowsAutomatically = false
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

                    override fun onPageFinished(view: WebView?, url: String?) {
                        view ?: return
                        feed.measurement?.let { view.removeCallbacks(it) }
                        val measure = measureHeight(view)
                        feed.measurement = measure
                        view.postDelayed(measure, 60)
                        if (feed.allowImages == true) view.postDelayed(measure, 650)
                    }
                }
            }
        },
        update = { webView ->
            // Re-feed only on a real change: update runs on every recomposition,
            // and loadHTML-style reloads would restart rendering mid-scroll.
            if (feed.html != html || feed.allowImages != allowImages) {
                feed.measurement?.let { webView.removeCallbacks(it) }
                feed.html = html
                feed.allowImages = allowImages
                webView.settings.blockNetworkImage = !allowImages
                webView.loadDataWithBaseURL(
                    null,
                    mailDocument(
                        html,
                        hex(palette.textPrimary),
                        hex(palette.textSecondary),
                        hex(palette.border),
                        hex(palette.primarySolid),
                        hex(palette.bgTinted),
                    ),
                    "text/html",
                    "utf-8",
                    null,
                )
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
}
