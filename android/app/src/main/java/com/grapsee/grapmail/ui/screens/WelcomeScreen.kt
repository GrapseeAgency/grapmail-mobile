package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.ui.components.GrapMailLogo
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.OutlineButton
import com.grapsee.grapmail.ui.components.illRes
import com.grapsee.grapmail.ui.theme.H1
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Welcome — founder design verbatim: logo + "Your world. Your inbox.",
 * "Welcome to / GrapMail" two-line headline, the 3D-envelope hero,
 * Create Account / Sign In with trailing arrows, legal line with the
 * Terms and Privacy names in brand blue.
 */
@Composable
fun WelcomeScreen(onCreateAccount: () -> Unit, onSignIn: () -> Unit) {
    val palette = LocalPalette.current
    // Scrollable first: on short phones and landscape the CTA pair and the
    // legal line must stay reachable; fixed spacers replace the old weighted
    // spacers, which collapse to zero and clip content off-screen.
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        GrapMailLogo(40.dp, wordmark = true)
        Spacer(Modifier.height(6.dp))
        Text("Your world. Your inbox.", fontSize = 13.sp, color = palette.textSecondary)

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

        Spacer(Modifier.height(18.dp))
        Image(
            painter = painterResource(illRes("welcome")),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(0.86f),
        )

        Spacer(Modifier.height(48.dp))

        GradientButton("Create Account", onCreateAccount, trailing = {
            Spacer(Modifier.size(10.dp))
            Icon(Icons.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        })
        Spacer(Modifier.height(12.dp))
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
