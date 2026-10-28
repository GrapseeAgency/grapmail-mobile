package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.ui.components.GrapMailLogo
import com.grapsee.grapmail.ui.components.StepProgress
import com.grapsee.grapmail.ui.theme.H2
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Wizard chrome — founder design verbatim: back arrow + centered logo header,
 * "CREATE ACCOUNT" eyebrow, step title + subtitle, the numbered stepper,
 * scrollable body, then the footer with the primary action and
 * "Already have an account? Sign In".
 */
@Composable
fun WizardScaffold(
    step: Int,
    title: String,
    subtitle: AnnotatedSubtitle,
    onBack: (() -> Unit)?,
    onSignIn: () -> Unit,
    bottomBar: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .imePadding(),
    ) {
        // Header: back + centered logo
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = palette.textPrimary)
                }
            }
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
            ) { GrapMailLogo(36.dp, wordmark = true) }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            Text("CREATE ACCOUNT", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, color = palette.textTertiary)
            Spacer(Modifier.height(8.dp))
            Text(title, style = H2, color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(subtitle.build(), fontSize = 14.sp, lineHeight = 21.sp, color = palette.textSecondary)
            Spacer(Modifier.height(22.dp))
            StepProgress(step = step)
            Spacer(Modifier.height(22.dp))
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) { body() }
            Spacer(Modifier.height(14.dp))
            bottomBar()
            Spacer(Modifier.height(10.dp))
            AlreadyHaveAccount(onSignIn = onSignIn)
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** "Already have an account? Sign In" — the wizard footer, exactly as designed. */
@Composable
fun AlreadyHaveAccount(onSignIn: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSignIn)
            .padding(vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Already have an account?  ", fontSize = 13.sp, color = palette.textSecondary)
        Text("Sign In", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.primarySolid)
    }
}

/** Subtitle with an optional bold lead sentence, like "Let's get started." */
class AnnotatedSubtitle(val boldLead: String? = null, val rest: String) {
    fun build() = buildAnnotatedString {
        boldLead?.let {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(it) }
            append(" ")
        }
        append(rest)
    }
}
