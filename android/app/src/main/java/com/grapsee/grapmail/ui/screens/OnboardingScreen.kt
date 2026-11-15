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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.grapsee.grapmail.ui.theme.H1
import com.grapsee.grapmail.ui.theme.LocalPalette
import java.io.ByteArrayInputStream

data class OnboardingSceneData(
    val index: Int,
    val eyebrow: String,
    val title: String,
    val description: String,
    val ctaText: String,
    val assetName: String,
)

val ONBOARDING_SCENES = listOf(
    OnboardingSceneData(
        index = 0,
        eyebrow = "WELCOME",
        title = "Welcome to Your Sanctuary",
        description = "Your new address is ready; welcome to a cleaner way to communicate.",
        ctaText = "Get Started →",
        assetName = "onboarding/onboarding_scene_1.html",
    ),
    OnboardingSceneData(
        index = 1,
        eyebrow = "SCREENER GATE",
        title = "You Decide Who Reaches You",
        description = "Unknown senders wait in the Screener until you decide.",
        ctaText = "Next →",
        assetName = "onboarding/onboarding_scene_2.html",
    ),
    OnboardingSceneData(
        index = 2,
        eyebrow = "SUBSCRIPTIONS",
        title = "Tame the Clutter",
        description = "Unsubscribe, snooze, and return to what matters.",
        ctaText = "Next →",
        assetName = "onboarding/onboarding_scene_3.html",
    ),
    OnboardingSceneData(
        index = 3,
        eyebrow = "QUIET BY DEFAULT",
        title = "Choose Your Rhythm",
        description = "Set your theme and decide when GrapMail can interrupt you.",
        ctaText = "Next →",
        assetName = "onboarding/onboarding_scene_4.html",
    ),
    OnboardingSceneData(
        index = 4,
        eyebrow = "YOUR SANCTUARY",
        title = "Your Inbox Is Yours Again",
        description = "Welcome home; your new mailbox is ready.",
        ctaText = "Open My Mailbox →",
        assetName = "onboarding/onboarding_scene_5.html",
    ),
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OnboardingHero(
    assetName: String,
    onOpenMailbox: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { arrayOfNulls<WebView>(1) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val html = ctx.assets.open(assetName).bufferedReader().use { it.readText() }
                WebView(ctx).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    settings.apply {
                        javaScriptEnabled = true
                        blockNetworkLoads = true
                        allowFileAccess = true
                        allowContentAccess = false
                        domStorageEnabled = true
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        textZoom = 100
                    }
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun postMessage(msg: String) {
                            if (msg.contains("open-mailbox")) {
                                post { onOpenMailbox() }
                            }
                        }
                    }, "ReactNativeWebView")
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString().orEmpty()
                            if (url.startsWith("grapmail:open-mailbox")) {
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
                            val allowed = url.startsWith("file:///android_asset/") ||
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
                    loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
                    holder[0] = this
                }
            },
            update = { wv ->
                val html = wv.context.assets.open(assetName).bufferedReader().use { it.readText() }
                wv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
            }
        )
    }

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
            holder[0]?.destroy()
            holder[0] = null
        }
    }
}

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
) {
    val palette = LocalPalette.current
    var sceneIndex by remember { mutableIntStateOf(0) }
    val currentScene = ONBOARDING_SCENES[sceneIndex]

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))

        // Top bar
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GrapMailLogo(size = 32.dp, wordmark = true)

            if (sceneIndex > 0) {
                Text(
                    text = "Skip",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primarySolid,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onFinish() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            } else {
                Spacer(Modifier.size(32.dp))
            }
        }

        Spacer(Modifier.height(12.dp))

        // Center Animated Artifact
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .aspectRatio(440f / 560f)
                .clip(RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            OnboardingHero(
                assetName = currentScene.assetName,
                onOpenMailbox = onFinish,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Spacer(Modifier.height(16.dp))

        // Eyebrow
        Text(
            text = currentScene.eyebrow,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
            color = palette.primarySolid,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(6.dp))

        // Heading
        Text(
            text = currentScene.title,
            style = H1.copy(fontSize = 24.sp, lineHeight = 30.sp),
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        // Description
        Text(
            text = currentScene.description,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        // 5-dot page indicator
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(5) { i ->
                val isActive = i == sceneIndex
                Box(
                    Modifier
                        .size(width = if (isActive) 22.dp else 7.dp, height = 7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isActive) palette.primarySolid else palette.border)
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Primary CTA Button
        GradientButton(
            text = currentScene.ctaText,
            onClick = {
                if (sceneIndex < 4) {
                    sceneIndex++
                } else {
                    onFinish()
                }
            },
        )

        Spacer(Modifier.height(24.dp))
    }
}
