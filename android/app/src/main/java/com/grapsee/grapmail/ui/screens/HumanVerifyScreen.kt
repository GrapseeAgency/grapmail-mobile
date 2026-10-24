package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.ui.components.Eyebrow
import com.grapsee.grapmail.ui.components.PuzzleSlider
import com.grapsee.grapmail.ui.theme.H2
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Human verification — GrapMail's own slide puzzle, replacing Telegram per
 * the founder's direction. Shown after the account exists (like the web
 * gate's placement); skippable by design, and the account works either way.
 */
@Composable
fun HumanVerifyScreen(vm: SignUpViewModel, onDone: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsState()
    var verified by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        Eyebrow("Security Check")
        Spacer(Modifier.height(10.dp))
        Text("Human Verification", style = H2, color = palette.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Slide the piece into place to prove you're human",
            fontSize = 14.sp,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(36.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(palette.card)
                .padding(20.dp)
        ) {
            Column {
                Text(
                    "Quick security check",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textSecondary,
                )
                Spacer(Modifier.height(14.dp))
                PuzzleSlider(
                    verified = verified || state.humanVerified,
                    onVerified = {
                        verified = true
                        vm.markHumanVerified()
                    },
                )
                Spacer(Modifier.height(14.dp))
                if (!verified) {
                    var seedBump by rememberSaveable { mutableStateOf(0) }
                    Text(
                        "Get a new challenge",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.primarySolid,
                        modifier = Modifier
                            .align(Alignment.Start)
                            .clickable { seedBump++ },
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        if (verified) {
            Text(
                "Verified — you're all set!",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.success,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            com.grapsee.grapmail.ui.components.GradientButton("Continue", onClick = onDone)
        } else {
            Text(
                "Skip for now →",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDone() },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
