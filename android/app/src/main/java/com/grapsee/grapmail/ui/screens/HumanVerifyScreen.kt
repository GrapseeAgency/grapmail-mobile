package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.PuzzleSlider
import com.grapsee.grapmail.ui.components.illRes
import com.grapsee.grapmail.ui.theme.H1
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Human verification — founder design verbatim: the shield + "Human
 * Verification" badge illustration, ACCOUNT PROTECTION eyebrow, "Just one
 * more step!" headline, the "Solve the puzzle" card with the photo and
 * slider, the OR divider, "Get a new challenge", and "Skip for now" with
 * its caption. GrapMail's own test — Telegram removed per the founder.
 */
@Composable
fun HumanVerifyScreen(vm: SignUpViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsState()
    var verified by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var challenge by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(0) }

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header: back + centered logo. The arrow goes back a step — it
            // must never advance the flow (onDone is Skip's callback only).
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
            Image(
                painter = painterResource(illRes("verify")),
                contentDescription = "Human verification",
                modifier = Modifier.fillMaxWidth(0.82f),
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

            // ── Solve the puzzle card ───────────────────────────────────────
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
                        Text("🧩", fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Solve the puzzle", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                        Text(
                            "Drag the puzzle piece to the correct position to complete the image.",
                            fontSize = 13.sp, lineHeight = 18.sp, color = palette.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                key(challenge) {
                    PuzzleSlider(
                        verified = verified || state.humanVerified,
                        onVerified = { verified = true; vm.markHumanVerified() },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // OR divider
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f).height(1.dp).background(palette.border))
                Text("  OR  ", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = palette.textTertiary)
                Box(Modifier.weight(1f).height(1.dp).background(palette.border))
            }

            Spacer(Modifier.height(16.dp))

            // Get a new challenge
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, palette.primarySolid.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .clickable { verified = false; challenge++ }
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("Get a new challenge", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.primarySolid)
            }

            Spacer(Modifier.height(18.dp))
            Text("Skip for now", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primarySolid,
                modifier = Modifier.clickable { onDone() })
            Spacer(Modifier.height(6.dp))
            Text(
                "You can always complete verification later\nfrom your account settings.",
                fontSize = 12.sp, lineHeight = 17.sp, color = palette.textTertiary, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}


@Composable
private fun <K> key(key: K, content: @Composable () -> Unit) =
    androidx.compose.runtime.key(key) { content() }
