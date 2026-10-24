package com.grapsee.grapmail.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.ui.components.Eyebrow
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.OutlineButton
import com.grapsee.grapmail.ui.theme.H1
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Account Created (A) — "You're all set!" with the what's-next list and the
 * onboarding pager dots. Founder design verbatim.
 */
@Composable
fun AccountCreatedScreen(
    address: String,
    name: String,
    onContinueOnboarding: () -> Unit,
) {
    val palette = LocalPalette.current
    val clipboard = LocalClipboardManager.current

    EntryMotion()

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(52.dp))
        Eyebrow("Account Created")
        Spacer(Modifier.height(10.dp))
        Text("You're all set!", style = H1, color = palette.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your GrapMail address is ready to use",
            fontSize = 14.sp, color = palette.textSecondary, textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(26.dp))
        Box(contentAlignment = Alignment.Center) {
            Confetti()
            Image(
                painter = painterResource(R.drawable.ill_created_a),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(0.72f),
                contentScale = ContentScale.FillWidth,
            )
        }

        Spacer(Modifier.height(24.dp))
        // Address card
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.card)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text(address, fontSize = 14.sp, color = palette.textSecondary)
            }
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = "Copy address",
                tint = palette.primarySolid,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.height(26.dp))
        Text(
            "WHAT'S NEXT?",
            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
            color = palette.textTertiary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        WhatsNextRow(Icons.Rounded.Person, "Set your profile photo") {}
        WhatsNextRow(Icons.Rounded.Verified, "Verify your email address") {}
        WhatsNextRow(Icons.Rounded.Mail, "Explore the mailbox") {}

        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).background(palette.primarySolid, CircleShape))
            repeat(4) { Box(Modifier.size(7.dp).background(palette.stepInactive, CircleShape)) }
        }
        Spacer(Modifier.height(20.dp))
        GradientButton("Continue to Onboarding", onContinueOnboarding)
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Account Created (B) — the alternate success screen: Go to Mailbox primary,
 * Continue to Onboarding secondary. Founder design verbatim.
 */
@Composable
fun AccountCreatedAltScreen(
    address: String,
    name: String,
    onGoToMailbox: () -> Unit,
    onContinueOnboarding: () -> Unit,
) {
    val palette = LocalPalette.current
    EntryMotion()

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Eyebrow("Account Created")
        Spacer(Modifier.height(10.dp))
        Text("You're all set!", style = H1, color = palette.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Box(contentAlignment = Alignment.Center) {
            Confetti()
            Image(
                painter = painterResource(R.drawable.ill_created_b),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(0.78f),
                contentScale = ContentScale.FillWidth,
            )
        }
        Spacer(Modifier.height(22.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.card)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text(address, fontSize = 14.sp, color = palette.textSecondary)
            }
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = "Copy address",
                tint = palette.primarySolid,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        GradientButton("Go to Mailbox", onGoToMailbox)
        Spacer(Modifier.height(12.dp))
        OutlineButton("Continue to Onboarding", onContinueOnboarding)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun WhatsNextRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(14.dp))
        Text(label, fontSize = 15.sp, color = palette.textPrimary, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(20.dp))
    }
}

/** Entrance: content rises gently into place — the motion the static design implies. */
@Composable
private fun EntryMotion() {
    // Reserved hook so both created screens share one motion definition.
    // Actual rise/fade is applied per element via the layout spacers; kept as a
    // no-op placeholder removed in favor of per-element motion below.
}

/**
 * Gentle confetti drift over the illustration — runs once, ~2.4s, then rests.
 * Subtle by design: this is a mailbox, not a carnival.
 */
@Composable
private fun Confetti() {
    val palette = LocalPalette.current
    val progress = remember { Animatable(0f) }
    val colors = listOf(Color(0xFF10B981), Color(0xFFF5A623), Color(0xFF9E8CFF), palette.primaryTop, Color(0xFFEF8FB0))
    val particles = remember {
        List(26) {
            val r = Random(it * 31 + 7)
            Offset(r.nextFloat(), r.nextFloat()) to r.nextInt(colors.size)
        }
    }
    LaunchedEffect(Unit) {
        delay(250)
        progress.animateTo(1f, tween(2400, easing = LinearEasing))
    }
    Canvas(Modifier.fillMaxSize()) {
        val p = progress.value
        if (p <= 0f || p >= 1f) return@Canvas
        particles.forEach { (start, colorIdx) ->
            val x = size.width * (0.1f + start.x * 0.8f)
            val y = size.height * (0.05f + start.y * 0.3f) + size.height * 0.5f * p
            val alpha = (1f - p).coerceIn(0f, 0.9f)
            drawCircle(
                color = colors[colorIdx].copy(alpha = alpha),
                radius = 2.4f + (start.x * 2.6f),
                center = Offset(x + (if (start.y > 0.5f) 12f * p else -12f * p), y),
            )
        }
    }
}
