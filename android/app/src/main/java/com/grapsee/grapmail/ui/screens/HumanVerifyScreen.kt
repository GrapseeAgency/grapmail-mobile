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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R

private const val CHECKPOINT_PATH = "/verify/grapmail-mobile.html"
private const val CHECKPOINT_URL = "https://grapsee.com$CHECKPOINT_PATH"

private class VerifyBridge(
    private val onToken: (String) -> Unit,
    private val onError: () -> Unit,
) {
    @JavascriptInterface
    fun onVerifyToken(token: String) = onToken(token)

    @JavascriptInterface
    fun onVerificationError() = onError()
}

private fun Uri.isCheckpoint(): Boolean =
    scheme == "https" && host == "grapsee.com" && (port == -1 || port == 443) && path == CHECKPOINT_PATH

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HumanVerifyScreen(vm: SignUpViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    val compact = LocalConfiguration.current.screenHeightDp < 700 || LocalConfiguration.current.fontScale > 1.15f
    val verified = state.humanVerified && !state.humanVerificationId.isNullOrBlank()
    val submitToken by rememberUpdatedState(vm::submitHumanVerification)
    val handler = remember { Handler(Looper.getMainLooper()) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showChallenge by remember { mutableStateOf(false) }
    var challengeError by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(palette.bg).statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 8.dp)) {
            IconButton(onClick = {
                if (showChallenge) showChallenge = false else onBack()
            }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.acct_back), tint = palette.textPrimary)
            }
        }

        if (showChallenge) {
            AndroidView(
                modifier = Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth().testTag("signup-captcha"),
                factory = { context ->
                    WebView(context).apply {
                        webView = this
                        setBackgroundColor(palette.bg.toArgb())
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        fun fail() {
                            if (webView === this) {
                                challengeError = true
                                showChallenge = false
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                request.isForMainFrame && !request.url.isCheckpoint()

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) fail()
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                response: android.webkit.WebResourceResponse,
                            ) {
                                if (request.isForMainFrame) fail()
                            }
                        }
                        addJavascriptInterface(VerifyBridge(
                            onToken = { token ->
                                handler.post {
                                    if (webView === this && url?.let { Uri.parse(it).isCheckpoint() } == true && token.isNotBlank()) {
                                        submitToken(token)
                                        showChallenge = false
                                    }
                                }
                            },
                            onError = { handler.post { fail() } },
                        ), "GrapMailAndroid")
                        loadUrl(CHECKPOINT_URL)
                    }
                },
                onRelease = { view ->
                    if (webView === view) webView = null
                    view.removeJavascriptInterface("GrapMailAndroid")
                    view.stopLoading()
                    view.destroy()
                },
            )
            TextButton(onClick = { showChallenge = false }) { Text(stringResource(R.string.acct_cancel_verification)) }
        } else {
            Column(
                Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
            ) {
                Text(stringResource(R.string.acct_verify_your_account), color = palette.textPrimary, fontSize = 30.sp,
                    lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.acct_complete_a_quick_security_check_to), color = palette.textSecondary,
                    fontSize = 15.sp, lineHeight = 22.sp)
                Column(
                    Modifier.fillMaxWidth().background(palette.card, RoundedCornerShape(24.dp))
                        .border(1.dp, palette.textSecondary.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
                        .padding(if (compact) 14.dp else 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    VerificationShield(if (compact) 62.dp else 84.dp)
                    Text(if (verified) stringResource(R.string.acct_verification_complete) else stringResource(R.string.acct_security_check),
                        color = palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (!verified) {
                        Button(
                            onClick = { challengeError = false; showChallenge = true },
                            enabled = !state.humanVerifying,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            if (state.humanVerifying) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else Text(if (challengeError || state.humanVerifyError != null) stringResource(R.string.acct_try_again) else stringResource(R.string.acct_verify_im_human))
                        }
                        val error = if (challengeError) stringResource(R.string.acct_the_challenge_couldnt_load_please) else state.humanVerifyError
                        if (error != null || state.humanVerifying) {
                            Text(error ?: stringResource(R.string.acct_checking_verification),
                                color = if (error == null) palette.textSecondary else palette.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    }
                }
            }

            Button(
                onClick = onDone,
                enabled = verified,
                colors = ButtonDefaults.buttonColors(containerColor = palette.primarySolid, contentColor = palette.onPrimary),
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
                    .heightIn(min = 48.dp).testTag("signup-verify-continue"),
            ) { Text(stringResource(R.string.acct_continue), fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun VerificationShield(diameter: androidx.compose.ui.unit.Dp) {
    val palette = LocalPalette.current
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val shield = Path().apply {
                moveTo(w * .5f, h * .04f)
                quadraticTo(w * .7f, h * .18f, w * .88f, h * .21f)
                lineTo(w * .86f, h * .51f)
                quadraticTo(w * .84f, h * .8f, w * .5f, h * .96f)
                quadraticTo(w * .16f, h * .8f, w * .14f, h * .51f)
                lineTo(w * .12f, h * .21f)
                quadraticTo(w * .3f, h * .18f, w * .5f, h * .04f)
                close()
            }
            drawPath(shield, Brush.verticalGradient(listOf(palette.primarySolid, palette.primarySolid.copy(alpha = .72f))))
        }
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = palette.onPrimary, modifier = Modifier.size(32.dp))
    }
}
