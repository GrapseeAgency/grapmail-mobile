package com.grapsee.grapmail.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.GrapMailLogo
import com.grapsee.grapmail.ui.components.themeAnimationHtml
import com.grapsee.grapmail.ui.theme.LocalPalette
import com.grapsee.grapmail.ui.theme.ScreenBackground
import java.io.ByteArrayInputStream

data class OnboardingSceneData(
    val index: Int,
    val eyebrow: String,
    val title: String,
    val description: String,
    val ctaText: String,
    val assetName: String,
)

/**
 * The founder's five scenes, in order. Scene 1 is the required welcome; 2..5 are
 * the discovery scenes, and the screen draws its Skip from that same fact.
 */
val ONBOARDING_SCENES = listOf(
    OnboardingSceneData(
        index = 0,
        eyebrow = "WELCOME",
        title = "Welcome to Your Sanctuary",
        description = "Your new address is ready; welcome to a cleaner way to communicate.",
        ctaText = "Get Started",
        assetName = "file:///android_asset/onboarding/onboarding_scene_1.html",
    ),
    OnboardingSceneData(
        index = 1,
        eyebrow = "SCREENER GATE",
        title = "You Decide Who Reaches You",
        description = "Unknown senders wait in the Screener until you decide.",
        ctaText = "Next",
        assetName = "file:///android_asset/onboarding/onboarding_scene_2.html",
    ),
    OnboardingSceneData(
        index = 2,
        eyebrow = "SUBSCRIPTIONS",
        title = "Tame the Clutter",
        description = "Unsubscribe, snooze, and return to what matters.",
        ctaText = "Next",
        assetName = "file:///android_asset/onboarding/onboarding_scene_3.html",
    ),
    OnboardingSceneData(
        index = 3,
        eyebrow = "QUIET BY DEFAULT",
        title = "Choose Your Rhythm",
        description = "Set your theme and decide when GrapMail can interrupt you.",
        ctaText = "Next",
        assetName = "file:///android_asset/onboarding/onboarding_scene_4.html",
    ),
    OnboardingSceneData(
        index = 4,
        eyebrow = "YOUR SANCTUARY",
        title = "Your Inbox Is Yours Again",
        description = "Welcome home; your new mailbox is ready.",
        ctaText = "Open My Mailbox",
        assetName = "file:///android_asset/onboarding/onboarding_scene_5.html",
    ),
)

/** The artwork's own outer colour; the page around it is this colour so the
 *  frame has no edge — the scene dissolves into the screen instead of sitting
 *  in an invisible box. */
/**
 * The five onboarding pages. The artwork is the founder's standalone HTML,
 * bundled in `assets/onboarding/` and rendered by a WebView. The host supplies
 * the palette, headline and navigation.
 *
 * The one thing the artwork needs from its host is a viewport. A WebView parses
 * the document before Compose has laid it out, so the initial containing block
 * is 0 tall, `height:100%` on `html`/`body` resolves to 0, and the page's own
 * `overflow:hidden` clips the entire scene away — the blank panel this screen
 * used to be. `HOST_FIT` is a single injected style block that anchors the
 * document to the viewport height instead of to its parent (the same
 * `native-host-fit` correction the bundled welcome page carries, and the same
 * `loadDataWithBaseURL` delivery that page uses on device). The palette is
 * applied before loading so each scene starts in the current app colors.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OnboardingHero(
    scene: OnboardingSceneData,
    accent: Color,
    onOpenMailbox: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { arrayOfNulls<WebView>(1) }
    val target = scene.assetName

    Box(
        modifier = modifier.semantics {
            contentDescription = "Animated scene: ${scene.title}"
        }
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { holder[0]?.evaluateJavascript(NATIVE_FIT, null) },
            factory = { ctx ->
                // The scene is read from the APK and handed to the view in one
                // go, so the document never has to resolve a file path and the
                // viewport fit is in place before the first layout.
                val html = ctx.assets
                    .open(target.removePrefix(ASSET_PREFIX))
                    .bufferedReader()
                    .use { themeAnimationHtml(it.readText(), accent) }
                    .let { src ->
                        // Host-mode background removal: the scenes are authored
                        // as full-viewport animations with their own navy field,
                        // which paints a hard-edged plate inside the app. Strip
                        // only those background layers; every illustration rule
                        // (SVG, canvas, gradients on the artwork itself) stays.
                        val stripped = if (src.contains("</head>"))
                            src.replaceFirst("</head>", HOST_BG + "</head>")
                        else src
                        // Before </body> so the body exists when the fit runs;
                        // appended if a scene ever ships without the tag.
                        if (stripped.contains("</body>"))
                            stripped.replaceFirst("</body>", HOST_FIT + "</body>")
                        else stripped + HOST_FIT
                    }

                WebView(ctx).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER

                    settings.apply {
                        javaScriptEnabled = true
                        // One local file, drawn by the app: the scene may not
                        // reach the network, keep storage, or open a link.
                        blockNetworkLoads = true
                        allowFileAccess = true
                        allowContentAccess = false
                        @Suppress("DEPRECATION")
                        allowFileAccessFromFileURLs = false
                        @Suppress("DEPRECATION")
                        allowUniversalAccessFromFileURLs = false
                        domStorageEnabled = false
                        databaseEnabled = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        textZoom = 100
                    }

                    // Scene 5 asks the host to open the mailbox, and scene 4 can
                    // announce a preference. Both are one-way notes; unknown
                    // types are ignored rather than guessed at.
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun postMessage(msg: String) {
                            if (msg.contains("open-mailbox")) post { onOpenMailbox() }
                        }
                    }, "ReactNativeWebView")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(NATIVE_FIT, null)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url?.toString().orEmpty()
                            if (url.startsWith("grapmail:")) {
                                view?.post { onOpenMailbox() }
                                return true
                            }
                            return true
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val url = request?.url?.toString().orEmpty()
                            val allowed = url.startsWith(ASSET_BASE) ||
                                url.startsWith("data:") || url.startsWith("about:")
                            return if (allowed) null else WebResourceResponse(
                                "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)),
                            )
                        }
                    }

                    loadDataWithBaseURL(ASSET_BASE, html, "text/html", "UTF-8", null)
                    holder[0] = this
                }
            },
            update = { },
        )
    }

    // A WebView keeps its timers running while it believes it is visible;
    // pausing with the screen is what makes leaving the app stop the scene
    // instead of animating behind the launcher.
    DisposableEffect(lifecycle, target) {
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
            holder[0]?.destroy()
            holder[0] = null
        }
    }
}

/**
 * Host-mode background removal.
 *
 * Each scene is authored as a standalone full-viewport animation and therefore
 * paints its own navy field (`html`/`body` background, fixed `body` atmosphere
 * layers, and scene 4's `#universe`/`.background` sky). Inside the app that
 * field ends at the WebView's edge and reads as a hard-edged plate around the
 * illustration. The host page paints one continuous dark surface behind all
 * five scenes, so those background layers are removed here. Every illustration
 * geometry and timing still render as authored.
 */
private const val HOST_BG = """
<style id="native-host-bg">
html, body { background: transparent !important; }
body::before, body::after, body:before, body:after { display: none !important; }
#universe, .background { display: none !important; }
</style>
"""

/**
 * The viewport anchor, appended to the document body.
 *
 * A WebView parses the document before Compose has laid it out, so the initial
 * containing block is 0 tall and never grows: `height:100%` on `html`/`body`
 * resolves to 0 (the five scenes' roots all use it), `100vh` resolves to 0 too
 * in this state, and `body{overflow:hidden}` then clips the whole scene away —
 * the blank panel this screen used to be. A pixel height measured from the live
 * viewport is the only value that does resolve, so this pins it, retries for a
 * handful of frames while layout settles, and re-pins on resize.
 *
 * This script only updates the document height.
 */
private const val HOST_FIT = """
<script id="native-host-fit">
(function () {
  var html = document.documentElement;
  function fit() {
    var h = window.innerHeight || html.clientHeight || 0;
    if (!h || !document.body) return false;
    html.style.height = h + 'px';
    document.body.style.height = h + 'px';
    return true;
  }
  var frames = 0;
  (function settle() {
    fit();
    if (++frames < 40) requestAnimationFrame(settle);
  })();
  window.addEventListener('resize', fit, { passive: true });
  window.addEventListener('orientationchange', fit, { passive: true });
})();
</script>
"""

/**
 * Belt and braces for the one case CSS cannot express on older WebViews: a
 * resize (rotation, split screen, keyboard) after the first layout. Pinning the
 * document to the measured viewport in pixels keeps the percentage heights the
 * scenes are authored with resolving against the new size.
 */
private const val NATIVE_FIT = """
(function () {
  var h = window.innerHeight || document.documentElement.clientHeight || 0;
  if (!h) return;
  document.documentElement.style.height = h + 'px';
  document.body.style.height = h + 'px';
  void document.body.offsetHeight;
})();
"""

private const val ASSET_BASE = "file:///android_asset/onboarding/"

/** The APK-relative form of [ASSET_BASE], for opening the same file directly. */
private const val ASSET_PREFIX = "file:///android_asset/"

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    /** Test-only: the snapshot host opens directly on one scene. */
    initialScene: Int = 0,
) {
    val palette = LocalPalette.current
    val heroFraction = if (LocalConfiguration.current.fontScale > 1.15f) 0.38f else 0.54f
    ScreenBackground(palette.bg) {
        var sceneIndex by remember {
            mutableIntStateOf(initialScene.coerceIn(0, ONBOARDING_SCENES.lastIndex))
        }
        val scene = ONBOARDING_SCENES[sceneIndex]

        BoxWithConstraints(
            Modifier.fillMaxSize().background(palette.bg).testTag("onboarding"),
        ) {
            // The artwork is authored in a 440 x 560 box. Keep that exact
            // ratio — taking the full width but capping only the height used
            // to squash the box, which clipped the bottom of scene 2's
            // screener card. Cap the width instead, centre the result, and
            // the whole scene fits with room for the copy and the CTA.
            val heroWidth = minOf(maxWidth, maxHeight * heroFraction * (440f / 560f))
            val heroHeight = heroWidth * (560f / 440f)

            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                OnboardingTopBar(
                    canSkip = sceneIndex > 0,
                    onSkip = { onFinish() },
                )

                // A new scene is a new document: keying the hero is what
                // starts the next page from the authored first frame instead
                // of resuming the previous scene's timeline.
                key(scene.index, palette.primarySolid) {
                    OnboardingHero(
                        scene = scene,
                        accent = palette.primarySolid,
                        onOpenMailbox = onFinish,
                        modifier = Modifier
                            .width(heroWidth)
                            .height(heroHeight)
                            .testTag("onboarding-hero"),
                    )
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        scene.eyebrow,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.8.sp,
                        color = palette.primarySolid,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        scene.title,
                        fontSize = 27.sp,
                        lineHeight = 33.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.4).sp,
                        color = palette.textPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        scene.description,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                PageDots(
                    count = ONBOARDING_SCENES.size,
                    index = sceneIndex,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                )

                GradientButton(
                    text = scene.ctaText,
                    onClick = {
                        if (sceneIndex < ONBOARDING_SCENES.lastIndex) sceneIndex++
                        else onFinish()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .testTag("onboarding-cta"),
                    trailing = {
                        Spacer(Modifier.size(8.dp))
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )

                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun OnboardingTopBar(canSkip: Boolean, onSkip: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GrapMailLogo(size = 28.dp, wordmark = true)
        Spacer(Modifier.width(1.dp))
        Box(Modifier.weight(1f))
        if (canSkip) {
            Text(
                "Skip",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onSkip)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("onboarding-skip"),
            )
        } else {
            // Scene 1 cannot be skipped; the empty box keeps the wordmark in
            // the same place when the control appears on scene 2.
            Spacer(Modifier.size(width = 56.dp, height = 1.dp))
        }
    }
}

@Composable
private fun PageDots(count: Int, index: Int, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val active = i == index
            Box(
                Modifier
                    .size(width = if (active) 22.dp else 6.dp, height = 5.dp)
                    .clip(if (active) RoundedCornerShape(3.dp) else CircleShape)
                    .background(if (active) palette.primarySolid else palette.border)
                    .border(
                        0.5.dp,
                        if (active) Color.Transparent else palette.border,
                        if (active) RoundedCornerShape(3.dp) else CircleShape,
                    )
            )
        }
    }
}

/**
 * "Shown once, on this device."
 *
 * The walkthrough is tied to the device, not to an account: somebody who
 * creates a second mailbox has already met the product, and replaying five
 * scenes at them is an interruption. Stored in plain preferences, cleared by
 * uninstalling the app — the same lifetime the welcome tour has.
 */
object OnboardingGate {
    private const val PREFS = "grapmail.onboarding"
    private const val KEY_DONE = "completed"

    fun isDone(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)

    fun markDone(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()
    }
}
