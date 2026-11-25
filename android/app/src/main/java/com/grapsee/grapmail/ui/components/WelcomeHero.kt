package com.grapsee.grapmail.ui.components

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayInputStream

/**
 * The welcome illustration: the founder's `GrapMail_Autoplay_Mailbox.html`,
 * bundled in `assets/` and drawn by a WebView.
 *
 * It is pure illustration: a phone in an envelope playing a short product tour
 * inside its own mini screen. It carries NO headline or tagline — "Welcome to
 * GrapMail" and the promise line are real, localized Compose text drawn by
 * [com.grapsee.grapmail.ui.screens.WelcomeScreen] below it, so they follow the
 * font-size setting and never appear twice.
 *
 * It is a picture, not a page. The file is ours and travels in the APK; the view
 * has no navigation (every URL except the asset itself is refused), no network
 * (`blockNetworkLoads`), no storage, no zoom, no scrollbars, no text selection
 * and no touch target — the surrounding screen's scroll keeps the touches, so the
 * envelope can never be dragged or the page panned out of its frame.
 *
 * Sizing is the caller's job: the artwork is authored in a 400 x 440 box
 * (`getFrame()` in the page) and the HTML mini-screen is positioned in
 * percentages of that box, so the host MUST hand it exactly that aspect ratio
 * (width = height * 400 / 440). Inside that box the whole drawing — orbit,
 * badges, glow — fits without cropping. The page carries a small host-mode
 * style block that makes the stage fill the view exactly; the welcome screen
 * paints the backdrop, so there is never a second rectangle around the envelope.
 *
 * [still] is the "Remove animations" reading ([rememberMotionReduced]): the
 * page is shown as one settled inbox frame — every CSS animation and
 * transition is switched off and the scripted tour is never started. The
 * caller keys the composable on it, so flipping the switch rebuilds the view.
 *
 * The animation is JS + CSS compositor work inside the WebView, so it stays off
 * the Compose recomposition path entirely.
 */
@Composable
fun WelcomeHero(
    accent: Color,
    backdrop: Color,
    modifier: Modifier = Modifier,
    still: Boolean = false,
) {
    BundledAnimation(
        asset = "GrapMail_Autoplay_Mailbox.html",
        accent = accent,
        backdrop = backdrop,
        description = stringResource(R.string.mail_welcome_hero_description),
        modifier = modifier,
        still = still,
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BundledAnimation(
    asset: String,
    accent: Color,
    backdrop: Color,
    description: String,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    onOpenMailbox: (() -> Unit)? = null,
    still: Boolean = false,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // A holder, not state: this must not key the effect below. Keying it made
    // the first effect's dispose clear the state, which recomposed, disposed the
    // effect that owned the WebView, and destroyed the view one frame after it
    // was created (blank hero, "destroy() called while still attached" in logcat).
    val holder = remember { arrayOfNulls<WebView>(1) }

    Box(
        modifier = modifier.semantics {
            contentDescription = description
        }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                // 51 KB read once, on the main thread: the same call the resource
                // system would make to open a drawable, and cheaper than a
                // temporary thread hand-off that would flash an empty frame.
                val html = viewContext.assets
                    .open(asset)
                    .bufferedReader()
                    .use { themeAnimationHtml(it.readText(), accent) }
                    .let { document ->
                        val background = (backdrop.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')
                        val style = "<style>:root { --grapmail-native-background: #$background; color-scheme: only light; }</style>"
                        val stillStyle = if (still) STILL_STYLE else ""
                        val themed = document.replaceFirst("</head>", "$style$stillStyle</head>")
                        // Reduced motion: never start the scripted tour. The page
                        // has already drawn its first inbox frame synchronously
                        // (`init();inbox();`) before this call, so a settled
                        // picture remains. A page without the call is unchanged.
                        if (still) themed.replaceFirst(TOUR_START, TOUR_SKIPPED) else themed
                    }

                WebView(viewContext).apply {
                    // Opaque, not transparent. A transparent WebView forces
                    // Chromium to composite the whole document into an
                    // alpha-blended layer; on several Android WebView builds
                    // (and every emulator using SwiftShader) that layer is
                    // drawn at a few percent alpha, so the artwork is there in
                    // the DOM — DevTools shows it — but the screen shows an
                    // empty box. Painting the screen's own backdrop colour
                    // instead costs nothing visually (the welcome page is
                    // transparent by design and the five scenes paint their own
                    // gradient over it) and keeps the view on the ordinary,
                    // fully opaque compositing path.
                    setBackgroundColor(backdrop.toArgb())
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    if (!interactive) {
                        isFocusable = false
                        isFocusableInTouchMode = false
                        isLongClickable = false
                        isHapticFeedbackEnabled = false
                        isEnabled = false
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    }

                    if (onOpenMailbox != null) {
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun postMessage(message: String) {
                                if (runCatching { org.json.JSONObject(message).optString("type") }.getOrNull()
                                    == "grapmail:open-mailbox") post { onOpenMailbox() }
                            }
                        }, "ReactNativeWebView")
                    }

                    settings.apply {
                        javaScriptEnabled = true
                        // The page is one local file; nothing may leave the APK.
                        blockNetworkLoads = true
                        allowFileAccess = true
                        allowContentAccess = false
                        domStorageEnabled = false
                        databaseEnabled = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        textZoom = 100
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean = true // the animation never navigates

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val url = request?.url?.toString().orEmpty()
                            val allowed = url.startsWith(ASSET_BASE) ||
                                url.startsWith("data:") ||
                                url.startsWith("about:")
                            return if (allowed) {
                                null
                            } else {
                                WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    ByteArrayInputStream(ByteArray(0)),
                                )
                            }
                        }
                    }

                    loadDataWithBaseURL(ASSET_BASE, html, "text/html", "UTF-8", null)
                    holder[0] = this
                }
            },
            onRelease = { view ->
                if (holder[0] === view) holder[0] = null
                view.removeJavascriptInterface("ReactNativeWebView")
                view.stopLoading()
                view.destroy()
            },
        )
    }

    // A WebView keeps running timers while it believes it is visible; pausing it
    // with the screen is what makes leaving the app actually stop the tour
    // instead of animating behind the launcher.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> holder[0]?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }
}

private const val ASSET_BASE = "file:///android_asset/"

/** "Remove animations": every CSS animation and transition off, end states shown. */
private const val STILL_STYLE =
    "<style id=\"native-host-still\">*,*::before,*::after{animation:none!important;transition:none!important;scroll-behavior:auto!important}</style>"

/** The welcome tour's start call, and its reduced-motion replacement (no tour, same error path). */
private const val TOUR_START = "autoStory().catch("
private const val TOUR_SKIPPED = "Promise.resolve().catch("
