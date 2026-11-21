package com.grapsee.grapmail.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.ui.theme.LocalPalette

private const val HUMAN_VERIFY_URL = "https://grapsee.com/verify/human?embed=android"

/** The bridge forwards candidate tokens. Only the server response can unlock Continue. */
private class VerifyBridge(private val onToken: (String) -> Unit) {
    @JavascriptInterface
    fun onVerifyToken(token: String) = onToken(token)
}

private fun Uri.isCheckpoint(): Boolean =
    scheme == "https" && host == "grapsee.com" && (port == -1 || port == 443) && path == "/verify/human"

// The image challenge is wider than its checkbox and lives outside the embed's
// scaled wrapper. Give the top-level checkpoint a wide, scrollable viewport so
// the entire provider UI, including its Verify button, fits on narrow phones.
private const val FIT_CHECKPOINT = """
(function () {
    var viewport = document.querySelector('meta[name="viewport"]');
    if (!viewport) {
        viewport = document.createElement('meta');
        viewport.name = 'viewport';
        document.head.appendChild(viewport);
    }
    viewport.content = 'width=440';
    var style = document.createElement('style');
    style.textContent = [
        'html, body, main { background: transparent !important; }',
        'main { padding: 12px !important; box-sizing: border-box !important; }',
        'main > div > div:first-child, main > div > div:first-child + p { display: none; }',
        'main > div > p { color: inherit !important; }',
    ].join(' ');
    if (!document.getElementById('grapmail-android-checkpoint')) {
        style.id = 'grapmail-android-checkpoint';
        document.head.appendChild(style);
    }
    window.dispatchEvent(new Event('resize'));
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HumanVerifyScreen(vm: SignUpViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    val canContinue = state.humanVerified && !state.humanVerificationId.isNullOrBlank()
    val submitToken by rememberUpdatedState(vm::submitHumanVerification)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageFailed by remember { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    Column(
        Modifier.fillMaxSize().background(palette.bg).statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = palette.textPrimary)
            }
            Text("Verify your account", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary, modifier = Modifier.weight(1f).padding(end = 12.dp))
        }

        Box(
            Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(16.dp)).background(palette.card),
            contentAlignment = Alignment.Center,
        ) {
            if (canContinue) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = palette.primarySolid,
                        modifier = Modifier.size(36.dp))
                    Text("Verification complete", color = palette.textPrimary,
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize().testTag("signup-captcha"),
                    factory = { context ->
                        WebView(context).apply {
                            webView = this
                            setBackgroundColor(palette.card.toArgb())
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                    request.isForMainFrame && !request.url.isCheckpoint()

                                override fun onPageFinished(view: WebView, url: String) {
                                    if (Uri.parse(url).isCheckpoint()) view.evaluateJavascript(FIT_CHECKPOINT, null)
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) pageFailed = true
                                }

                                override fun onReceivedHttpError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    response: android.webkit.WebResourceResponse,
                                ) {
                                    if (request.isForMainFrame) pageFailed = true
                                }
                            }
                            addJavascriptInterface(VerifyBridge { token ->
                                mainHandler.post {
                                    if (webView === this && url?.let { Uri.parse(it).isCheckpoint() } == true) {
                                        submitToken(token)
                                    }
                                }
                            }, "GrapMailAndroid")
                            loadUrl(HUMAN_VERIFY_URL)
                        }
                    },
                    update = { it.setBackgroundColor(palette.card.toArgb()) },
                    onRelease = { view ->
                        if (webView === view) webView = null
                        view.removeJavascriptInterface("GrapMailAndroid")
                        view.stopLoading()
                        view.destroy()
                    },
                )
            }
        }

        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val error = state.humanVerifyError
            val status = when {
                canContinue -> "Continue to Terms of Service."
                state.humanVerifying -> "Checking verification…"
                pageFailed -> "The challenge could not load. Try again."
                error != null -> error
                else -> "Complete the reCAPTCHA to continue."
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                if (state.humanVerifying) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(status, fontSize = 13.sp, textAlign = TextAlign.Center,
                    color = if (!canContinue && (pageFailed || error != null)) palette.error else palette.textSecondary)
            }
            if (!canContinue && !state.humanVerifying) {
                TextButton(onClick = {
                    pageFailed = false
                    webView?.loadUrl(HUMAN_VERIFY_URL)
                }) { Text("Get a new challenge") }
            }
            Button(
                onClick = onDone,
                enabled = canContinue,
                colors = ButtonDefaults.buttonColors(containerColor = palette.primarySolid, contentColor = palette.onPrimary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("signup-verify-continue"),
            ) {
                Text("Continue", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
