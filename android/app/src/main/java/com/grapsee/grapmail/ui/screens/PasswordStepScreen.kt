package com.grapsee.grapmail.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.logic.Validators
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.InfoBanner
import com.grapsee.grapmail.ui.components.RequirementRow
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Step 4 — "Create a password": strength meter + the design's requirement
 * checklist, plus the address summary card so nobody forgets what they are
 * signing up under. Founder design verbatim (its dark variant is the
 * reference; the layout is theme-adaptive).
 */
@Composable
fun PasswordStepScreen(vm: SignUpViewModel, onBack: () -> Unit, onNext: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsState()

    val reqs = Validators.passwordReqs(state.password)
    val strength = Validators.passwordStrength(state.password)
    val meterColor by animateColorAsState(
        when {
            strength >= 1f -> palette.success
            strength >= 0.75f -> palette.success
            strength >= 0.5f -> Color(0xFF9E8CFF)
            strength >= 0.25f -> Color(0xFFF5A623)
            else -> palette.error
        }, tween(300), label = "meter",
    )
    var confirmError by rememberSaveable { mutableStateOf<String?>(null) }

    WizardScaffold(
        step = 4,
        title = "Create a password",
        subtitle = "Use 8 characters or more.",
        onBack = onBack,
        bottomBar = {
            GradientButton(
                "Continue",
                enabled = reqs.all { it.met } && state.confirm == state.password && state.password.isNotEmpty(),
                onClick = {
                    confirmError = if (state.confirm != state.password) "The two passwords do not match." else null
                    if (confirmError == null && reqs.all { it.met }) onNext()
                },
            )
        },
    ) {
        AppTextField(
            label = "Password",
            value = state.password,
            onValueChange = { vm.update { copy(password = it) } },
            password = true,
        )
        Spacer(Modifier.height(16.dp))
        AppTextField(
            label = "Confirm password",
            value = state.confirm,
            onValueChange = {
                vm.update { copy(confirm = it) }
                if (confirmError != null) confirmError = if (it != state.password) "The two passwords do not match." else null
            },
            password = true,
            error = confirmError,
        )

        Spacer(Modifier.height(22.dp))

        // Strength meter — 4 segments, animated fill color
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(4) { i ->
                val filled = strength * 4 > i
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (filled) meterColor else palette.stepInactive)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                state.password.isEmpty() -> ""
                strength >= 1f -> "Excellent password"
                strength >= 0.75f -> "Strong password"
                strength >= 0.5f -> "Good password"
                strength >= 0.25f -> "Okay — keep going"
                else -> "Too weak"
            },
            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = meterColor,
        )

        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            reqs.forEach { RequirementRow(it.label, it.met) }
        }

        Spacer(Modifier.height(24.dp))
        InfoBanner(
            "Your GrapMail address: ${state.chosenHandle}@grapsee.com — you'll sign in with this and your password.",
        )
    }
}
