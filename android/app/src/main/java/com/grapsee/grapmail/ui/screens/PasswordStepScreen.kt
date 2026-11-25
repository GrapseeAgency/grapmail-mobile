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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.grapsee.grapmail.ui.components.GMAutofill
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.theme.LocalPalette
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R

/**
 * Step 4 — "Create a strong password" — founder design verbatim: the address
 * summary card with Edit, "Password *" with the 5-segment strength meter and
 * the green "Strong" label, "Confirm password *", and the "Password
 * requirements" card with its three checks.
 */
@Composable
fun PasswordStepScreen(vm: SignUpViewModel, onBack: () -> Unit, onSignIn: () -> Unit, onNext: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()

    val strength = Validators.passwordStrength(state.password)
    val matches = state.confirm.isNotEmpty() && state.confirm == state.password
    // One policy, in one place. `passwordAcceptable` is the four rules the server
    // enforces (`passwordProblem`), in ASCII classes — so the gate cannot
    // green-light a password the route would reject (a non-ASCII letter passes
    // Kotlin's Unicode `isLetter()` but fails the server's `[A-Za-z]`).
    val acceptable = Validators.passwordAcceptable(state.password)
    val requirementsMet = acceptable && matches
    var confirmError by rememberSaveable { mutableStateOf<String?>(null) }
    val bothMustMatchMessage = stringResource(R.string.acct_both_passwords_must_match_period)

    WizardScaffold(
        step = 4,
        title = stringResource(R.string.acct_create_a_strong_password),
        subtitle = AnnotatedSubtitle(rest = stringResource(R.string.acct_your_password_keeps_your_account)),
        onBack = onBack,
        onSignIn = onSignIn,
        bottomBar = {
            GradientButton(
                stringResource(R.string.acct_next),
                onClick = {
                    confirmError = if (!matches) bothMustMatchMessage else null
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
                Text(stringResource(R.string.acct_your_grapmail_address), fontSize = 13.sp, color = palette.textSecondary)
                Text("${state.chosenHandle}@grapsee.com", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            }
            Text(
                stringResource(R.string.acct_edit),
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
            Text(stringResource(R.string.acct_password_label), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Text("*", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.error)
        }
        Spacer(Modifier.height(8.dp))
        AppTextField(
            label = stringResource(R.string.acct_password),
            value = state.password,
            onValueChange = { vm.update { copy(password = it) } },
            hint = stringResource(R.string.acct_create_a_strong_password),
            showLabel = false,
            password = true,
            autofill = GMAutofill.NewPassword,
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
                    strength >= 1.0 -> stringResource(R.string.acct_strong)
                    strength >= 0.75 -> stringResource(R.string.acct_good)
                    strength >= 0.5 -> stringResource(R.string.acct_fair)
                    else -> stringResource(R.string.acct_weak)
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (strength >= 0.75) palette.success else palette.textTertiary,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row {
            Text(stringResource(R.string.acct_confirm_password_label), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Text("*", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.error)
        }
        Spacer(Modifier.height(8.dp))
        AppTextField(
            label = stringResource(R.string.acct_confirm_password),
            value = state.confirm,
            onValueChange = {
                vm.update { copy(confirm = it) }
                confirmError = null
            },
            hint = stringResource(R.string.acct_re_enter_your_password),
            showLabel = false,
            password = true,
            error = confirmError,
            autofill = GMAutofill.NewPassword,
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
                Text(stringResource(R.string.acct_password_requirements), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            }
            Spacer(Modifier.height(10.dp))
            RequirementLine(stringResource(R.string.acct_at_least_8_characters), state.password.length >= 8)
            RequirementLine(stringResource(R.string.acct_a_mix_of_letters_numbers_and_symbols), acceptable)
            RequirementLine(stringResource(R.string.acct_both_passwords_must_match), matches)
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
