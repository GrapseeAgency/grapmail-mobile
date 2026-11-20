package com.grapsee.grapmail.ui.screens

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.ui.theme.H1
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Human verification — REAL challenge, no bypass (2026-11-17 overhaul).
 *
 * This replaces the decorative puzzle slider AND the "Skip for now" exit with
 * an actual reCAPTCHA challenge hosted on the web checkpoint page
 * (kit file web/04_verify-human-page.tsx → https://<your-web-app>/verify/human).
 *
 * Flow:
 *  1. The card below hosts the checkpoint page in a WebView. The user solves
 *     the real "I'm not a robot" checkbox / image challenge there.
 *  2. On success the page calls the "GrapMailAndroid" JS bridge below with the
 *     single-use reCAPTCHA token.
 *  3. The token is verified by the GrapMail backend (POST /auth/verify-human).
 *     Only a server-confirmed verdict flips `state.humanVerified`.
 *  4. "Continue" is enabled ONLY after that verdict — there is no skip path.
 *
 * The back arrow still goes back a step and must never advance the flow.
 */
// TODO(deploy): point at your deployed web app (must be HTTPS). This is the
// same /verify/human checkpoint page the iOS app loads. Do NOT override the
// WebView User-Agent — reCAPTCHA evaluates it; the default is fine.
private const val HUMAN_VERIFY_URL = "https://grapsee.com/verify/human?embed=android"

/** JS bridge: the checkpoint page calls window.GrapMailAndroid.onVerifyToken(token). */
private class VerifyBridge(private val onToken: (String) -> Unit) {
    @JavascriptInterface
    fun onVerifyToken(token: String) {
        onToken(token)
    }
}

@SuppressLint("SetJavaScriptEnabled") // reCAPTCHA requires JS; the page is our own first-party URL.
@Composable
fun HumanVerifyScreen(vm: SignUpViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Token received from the page bridge; handed to the ViewModel for the
    // server-side exchange. The ViewModel guards against replay/duplicates.
    var pendingToken by rememberSaveable { mutableStateOf<String?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    LaunchedEffect(pendingToken) {
        val token = pendingToken ?: return@LaunchedEffect
        vm.submitHumanVerification(token)
    }

    // The challenge WebView is created once and re-loaded by "Get a new challenge".
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Google's image-grid challenge grows wider than a phone card;
            // without these the WebView clips the challenge's own Verify
            // button and a solved grid can never be submitted.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = WebViewClient() // keep every navigation inside the WebView
            addJavascriptInterface(
                VerifyBridge { token -> mainHandler.post { pendingToken = token } },
                "GrapMailAndroid",
            )
            loadUrl(HUMAN_VERIFY_URL)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            webView.removeJavascriptInterface("GrapMailAndroid")
            webView.destroy()
        }
    }

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header: back + centered logo.
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = palette.textPrimary,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clickable { onBack() }
                        .padding(8.dp),
                )
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    com.grapsee.grapmail.ui.components.GrapMailLogo(36.dp, wordmark = true)
                }
            }

            Spacer(Modifier.height(6.dp))
            com.grapsee.grapmail.ui.artwork.Artwork(
                "verify",
                // Bounded on purpose: a decorative illustration must never push
                // the Continue control out of reach (see the 2026-11 walk bug).
                modifier = Modifier.width(180.dp).aspectRatio(2f)
                    .semantics { contentDescription = "Human verification" },
            )

            Spacer(Modifier.height(8.dp))
            Text("ACCOUNT PROTECTION", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, color = palette.primarySolid)
            Spacer(Modifier.height(8.dp))
            Text("Just one more step!", style = H1.copy(fontSize = 26.sp), color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "To keep GrapMail safe and spam-free, we need to make sure you're a real person. Complete the challenge below to continue.",
                fontSize = 14.sp, lineHeight = 21.sp, color = palette.textSecondary, textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(22.dp))

            // ── Security check card ─────────────────────────────────────────
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, palette.border, RoundedCornerShape(20.dp))
                    .background(palette.card)
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(palette.primarySolid),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("🔒", fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Security check", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                        Text(
                            "Complete the challenge to verify you're a real person.",
                            fontSize = 13.sp, lineHeight = 18.sp, color = palette.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                if (state.humanVerified) {
                    // Server-confirmed verdict only. The WebView is no longer needed.
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 22.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Verified — thank you!", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                    }
                } else {
                    // Height is chosen so the reCAPTCHA image-challenge overlay fits.
                    AndroidView(
                        factory = { webView },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, palette.border, RoundedCornerShape(12.dp)),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (state.humanVerifying) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = palette.primarySolid)
                    Spacer(Modifier.width(10.dp))
                    Text("Checking…", fontSize = 13.sp, color = palette.textSecondary)
                }
            }
            state.humanVerifyError?.let { message ->
                Spacer(Modifier.height(10.dp))
                Text(
                    message,
                    fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center,
                    color = Color(0xFFE5484D), // TODO(theme): swap for the palette error color if one exists
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!state.humanVerified) {
                Spacer(Modifier.height(16.dp))
                // Reloads the checkpoint page → fresh challenge (reCAPTCHA's own
                // refresh button inside the widget does this too).
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, palette.primarySolid.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .clickable { webView.reload() }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Get a new challenge", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.primarySolid)
                }
            }

            Spacer(Modifier.height(18.dp))

            // Continue — the only exit from this screen, enabled only after the
            // server-confirmed verdict. (The old "Skip for now" is deleted.)
            val canContinue = state.humanVerified
            Box(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (canContinue) 1f else 0.45f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.primarySolid)
                    .clickable(enabled = canContinue) { onDone() }
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Continue", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }

            Spacer(Modifier.height(10.dp))
            // Branding disclosure (mobile apps: no badge required, text optional).
            // TODO(branding): linkify "Privacy Policy" / "Terms of Service" per the guidelines.
            Text(
                "This app is protected by reCAPTCHA and the Google Privacy Policy and Terms of Service apply.",
                fontSize = 11.sp, lineHeight = 15.sp, color = palette.textTertiary, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}
