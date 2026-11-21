package com.grapsee.grapmail.ui.components

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.graphics.Color as AndroidColor
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayInputStream

/**
 * The welcome animation: the founder's `GrapMail_Autoplay_Mailbox.html`, bundled
 * in `assets/` and drawn by a WebView.
 *
 * It is a picture, not a page. The file is ours and travels in the APK; the view
 * has no navigation (every URL except the asset itself is refused), no network
 * (`blockNetworkLoads`), no storage, no zoom, no scrollbars, no text selection
 * and no touch target — the surrounding screen's scroll keeps the touches, so the
 * envelope can never be dragged or the page panned out of its frame.
 *
 * Sizing is the caller's job: the artwork is authored in a 400 x 440 box
 * (`getFrame()` in the page), so the host must hand it that aspect ratio. The
 * page carries a small host-mode style block that makes the stage fill the view
 * exactly and keeps the page background transparent — the welcome screen paints
 * the backdrop, so there is never a second navy rectangle around the envelope.
 *
 * The animation is JS + CSS compositor work inside the WebView, so it stays off
 * the Compose recomposition path entirely.
 */
@Composable
fun WelcomeHero(accent: Color, modifier: Modifier = Modifier) {
    BundledAnimation(
        asset = "GrapMail_Autoplay_Mailbox.html",
        accent = accent,
        description = "Animated preview: a GrapMail inbox on a phone rising out of an envelope",
        modifier = modifier,
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BundledAnimation(
    asset: String,
    accent: Color,
    description: String,
    modifier: Modifier = Modifier,
    hostStyles: String = "",
    interactive: Boolean = false,
    onOpenMailbox: (() -> Unit)? = null,
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
                    .let { if (hostStyles.isEmpty()) it else it.replaceFirst("</head>", "$hostStyles</head>") }

                WebView(viewContext).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
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
