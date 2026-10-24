package com.grapsee.grapmail.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.ui.components.GrapMailLogo
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.OutlineButton
import com.grapsee.grapmail.ui.theme.H1
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * The app's face: "Better Email. A Brighter You." — founder design verbatim,
 * with the entrance motion the static PNG implies: the illustration rises
 * into place, then the text settles beneath it.
 */
@Composable
fun WelcomeScreen(onCreateAccount: () -> Unit, onSignIn: () -> Unit) {
    val palette = LocalPalette.current
    val rise = remember { Animatable(24f) }
    val fade = remember { Animatable(0f) }
    val textFade = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        fade.animateTo(1f, tween(420))
        rise.animateTo(0f, tween(480))
        textFade.animateTo(1f, tween(420))
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        GrapMailLogo(40.dp, wordmark = true)
        Spacer(Modifier.weight(0.7f))

        Image(
            painter = painterResource(R.drawable.ill_welcome),
            contentDescription = "GrapMail — secure mail illustration",
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = rise.value.dp.toPx() }
                .alpha(fade.value),
            contentScale = ContentScale.FillWidth,
        )

        Spacer(Modifier.weight(0.5f))

        Column(
            Modifier.alpha(textFade.value),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Better Email.", style = H1, color = palette.textPrimary, textAlign = TextAlign.Center)
            Text("A Brighter You.", style = H1.copy(color = palette.primarySolid), textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                "Secure · Private · Yours",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = palette.textSecondary,
            )
        }

        Spacer(Modifier.weight(1f))

        GradientButton("Create Account", onCreateAccount)
        Spacer(Modifier.height(12.dp))
        OutlineButton("Sign In", onSignIn)

        Spacer(Modifier.height(22.dp))
        Text("Or", fontSize = 12.sp, color = palette.textTertiary, fontWeight = FontWeight.Medium)

        Spacer(Modifier.height(18.dp))
        Text(
            "By creating an account you agree to our Terms of Service and Privacy Policy",
            fontSize = 12.sp,
            color = palette.textTertiary,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp,
        )

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).background(palette.primarySolid, CircleShape))
            Box(Modifier.size(7.dp).background(palette.stepInactive, CircleShape))
            Box(Modifier.size(7.dp).background(palette.stepInactive, CircleShape))
        }
        Spacer(Modifier.height(24.dp))
    }
}
