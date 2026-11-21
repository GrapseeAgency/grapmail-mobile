package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.OutlineButton
import com.grapsee.grapmail.ui.components.WelcomeHero
import com.grapsee.grapmail.ui.theme.H1
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Welcome — the founder's 2026-11 frame: "Welcome to / GrapMail", the tiny
 * promise line, the living-mailbox animation, then Create Account / Sign In
 * with trailing arrows and the legal line in brand blue.
 *
 * The animation is the bundled `GrapMail_Autoplay_Mailbox.html` (see
 * [WelcomeHero]): a 400 x 440 artwork that plays a full product tour by itself.
 * It is handed exactly that aspect ratio and bounded to the column width, and
 * the screen paints the backdrop behind it, so no second background shows.
 *
 * Scrollable first: on short phones and in landscape the hero plus the CTA pair
 * and legal line must stay reachable; fixed spacers replace weighted spacers,
 * which collapse to zero and clip content off-screen.
 */
@Composable
fun WelcomeScreen(onCreateAccount: () -> Unit, onSignIn: () -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))

        Text("Welcome to", style = H1, color = palette.textPrimary, textAlign = TextAlign.Center)
        Text("GrapMail", style = H1, color = palette.primarySolid, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "A faster, safer and more private way\nto stay connected.",
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )

        // The hero is authored at 400 x 440. Bounded to 360dp so it sits inside
        // the text column on wide phones/tablets, then centred by the parent.
        Spacer(Modifier.height(16.dp))
        androidx.compose.runtime.key(palette.primarySolid) {
            WelcomeHero(
                accent = palette.primarySolid,
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(400f / 440f),
            )
        }

        Spacer(Modifier.height(24.dp))

        GradientButton("Create Account", onCreateAccount, trailing = {
            Spacer(Modifier.size(10.dp))
            Icon(Icons.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        })
        // The founder asked for air between the primary and the secondary CTA:
        // 20dp, not the 12dp pair spacing used inside forms.
        Spacer(Modifier.height(20.dp))
        OutlineButton("Sign In", onSignIn, trailing = {
            Spacer(Modifier.size(10.dp))
            Icon(Icons.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp), tint = palette.textPrimary)
        })

        Spacer(Modifier.height(20.dp))
        Text(
            buildAnnotatedString {
                append("By continuing, you agree to our ")
                pushStyle(SpanStyle(color = palette.primarySolid, fontWeight = FontWeight.Medium))
                append("Terms of Service")
                pop()
                append("\nand ")
                pushStyle(SpanStyle(color = palette.primarySolid, fontWeight = FontWeight.Medium))
                append("Privacy Policy")
                pop()
                append(".")
            },
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = palette.textTertiary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
    }
}
