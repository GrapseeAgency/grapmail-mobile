package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.logic.Validators
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Step 4 — "Create a strong password" — founder design verbatim: the address
 * summary card with Edit, "Password *" with the 5-segment strength meter and
 * the green "Strong" label, "Confirm password *", and the "Password
 * requirements" card with its three checks.
 */
@Composable
fun PasswordStepScreen(vm: SignUpViewModel, onBack: () -> Unit, onSignIn: () -> Unit, onNext: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsState()

    val strength = Validators.passwordStrength(state.password)
    val matches = state.confirm.isNotEmpty() && state.confirm == state.password
    // One policy, in one place. `passwordAcceptable` is the four rules the server
    // enforces (`passwordProblem`), in ASCII classes — so the gate cannot
    // green-light a password the route would reject (a non-ASCII letter passes
    // Kotlin's Unicode `isLetter()` but fails the server's `[A-Za-z]`).
    val acceptable = Validators.passwordAcceptable(state.password)
    val requirementsMet = acceptable && matches
    var confirmError by rememberSaveable { mutableStateOf<String?>(null) }

    WizardScaffold(
        step = 4,
        title = "Create a strong password",
        subtitle = AnnotatedSubtitle(rest = "Your password keeps your account safe. Make it unique, strong and easy for you to remember."),
        onBack = onBack,
        onSignIn = onSignIn,
        bottomBar = {
            GradientButton(
                "Next",
                onClick = {
                    confirmError = if (!matches) "Both passwords must match." else null
                    if (requirementsMet) onNext()
                },
                enabled = requirementsMet,
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
        },
    ) {
        // ── Address summary card ────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, palette.border, RoundedCornerShape(20.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.softFill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Mail, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Your GrapMail address", fontSize = 13.sp, color = palette.textSecondary)
                Text("${state.chosenHandle}@grapsee.com", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            }
            Text(
                "Edit",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = palette.primarySolid,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onBack() } // back to the address step
                    .padding(8.dp),
            )
        }

        Spacer(Modifier.height(20.dp))

        Row {
            Text("Password ", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Text("*", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.error)
        }
        Spacer(Modifier.height(8.dp))
        AppTextField(
            label = "Password",
            value = state.password,
            onValueChange = { vm.update { copy(password = it) } },
            hint = "Create a strong password",
            showLabel = false,
            password = true,
        )
        Spacer(Modifier.height(10.dp))

        // 5-segment strength meter + "Strong" label, exactly as designed
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat(5) { i ->
                    val filled = (strength * 5).toInt() > i
                    Box(
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (filled) palette.success else palette.stepInactive)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    state.password.isEmpty() -> ""
                    strength >= 1.0 -> "Strong"
                    strength >= 0.75 -> "Good"
                    strength >= 0.5 -> "Fair"
                    else -> "Weak"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (strength >= 0.75) palette.success else palette.textTertiary,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row {
            Text("Confirm password ", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Text("*", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.error)
        }
        Spacer(Modifier.height(8.dp))
        AppTextField(
            label = "Confirm password",
            value = state.confirm,
            onValueChange = {
                vm.update { copy(confirm = it) }
                confirmError = null
            },
            hint = "Re-enter your password",
            showLabel = false,
            password = true,
            error = confirmError,
        )

        Spacer(Modifier.height(18.dp))

        // ── Password requirements card ──────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, palette.border, RoundedCornerShape(20.dp))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Password requirements", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            }
            Spacer(Modifier.height(10.dp))
            RequirementLine("At least 8 characters", state.password.length >= 8)
            RequirementLine("A mix of letters, numbers and symbols", acceptable)
            RequirementLine("Both passwords must match", matches)
        }
    }
}

@Composable
private fun RequirementLine(label: String, met: Boolean) {
    val palette = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Icon(
            Icons.Rounded.Check,
            contentDescription = null,
            tint = if (met) palette.success else palette.textTertiary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, color = if (met) palette.textPrimary else palette.textSecondary)
    }
}
