package com.grapsee.grapmail.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.ui.components.StepProgress
import com.grapsee.grapmail.ui.theme.H2
import com.grapsee.grapmail.ui.theme.LocalPalette
import androidx.compose.material3.Text

/**
 * The shared wizard chrome: back-affordance + "Step n of 5" + animated
 * segment bar + step title + subtitle, then the step body, then the bottom
 * action row. Step transitions slide horizontally, direction-aware.
 */
@Composable
fun WizardScaffold(
    step: Int,
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    bottomBar: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = 24.dp)
            .imePadding(),
    ) {
        Spacer(Modifier.height(20.dp))
        StepProgress(step = step, onBack = onBack)
        Spacer(Modifier.height(24.dp))
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(320))) togetherWith
                        (slideOutHorizontally(tween(320)) { -it / 3 } + fadeOut(tween(220)))
                } else {
                    (slideInHorizontally(tween(320)) { -it / 3 } + fadeIn(tween(320))) togetherWith
                        (slideOutHorizontally(tween(320)) { it / 3 } + fadeOut(tween(220)))
                }
            },
            label = "step",
        ) { s ->
            Column(Modifier.fillMaxSize()) {
                Text(title, style = H2, color = palette.textPrimary)
                Spacer(Modifier.height(6.dp))
                Text(subtitle, fontSize = 14.sp, lineHeight = 20.sp, color = palette.textSecondary)
                Spacer(Modifier.height(26.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) { body() }
                Spacer(Modifier.height(16.dp))
                bottomBar()
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
