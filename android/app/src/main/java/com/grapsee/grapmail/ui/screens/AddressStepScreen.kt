package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.theme.LocalPalette
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R

/**
 * Step 3 — "Choose your GrapMail address" — founder design verbatim: the
 * "Suggested for you" card with radio rows and green Available pills, the
 * "Create your own" card with the pencil, the handle input with the
 * @grapsee.com suffix, the green "This address is available!" row, and the
 * character/reserved-names rules caption.
 */
@Composable
fun AddressStepScreen(vm: SignUpViewModel, onBack: () -> Unit, onSignIn: () -> Unit, onNext: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    val loadedForKey = remember { mutableStateOf<String?>(null) }
    var domainMenuOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.firstName, state.lastName) {
        val key = "${state.firstName}|${state.lastName}"
        if ((state.firstName.isNotBlank() || state.lastName.isNotBlank()) && loadedForKey.value != key) {
            loadedForKey.value = key
            vm.loadSuggestions()
        }
    }

    WizardScaffold(
        step = 3,
        title = stringResource(R.string.acct_choose_your_grapmail_address),
        subtitle = AnnotatedSubtitle(rest = stringResource(R.string.acct_pick_an_available_address_or_create)),
        onBack = onBack,
        onSignIn = onSignIn,
        bottomBar = {
            GradientButton(
                stringResource(R.string.acct_next),
                onClick = { onNext() },
                enabled = state.chosenHandle.isNotBlank(),
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
        },
    ) {
        // ── Suggested for you ───────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, palette.border, RoundedCornerShape(20.dp))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(stringResource(R.string.acct_suggested_for_you), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                    Text(stringResource(R.string.acct_based_on_your_name_you_can_change_it), fontSize = 12.sp, color = palette.textTertiary)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (state.suggestionsLoading) {
                Text(stringResource(R.string.acct_finding_available_addresses), fontSize = 13.sp, color = palette.textTertiary)
            } else {
                state.suggestions.forEach { handle ->
                    RadioRow(
                        address = "$handle@grapsee.com",
                        selected = state.chosenHandle == handle && !state.customOpen,
                        onClick = { vm.pickSuggestion(handle) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (state.suggestions.isEmpty()) {
                    Text(
                        stringResource(R.string.acct_no_suggestions_from_your_name_create),
                        fontSize = 13.sp, color = palette.textTertiary,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── Create your own ─────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, palette.border, RoundedCornerShape(20.dp))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Edit, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(stringResource(R.string.acct_create_your_own), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                    Text(stringResource(R.string.acct_choose_a_unique_address_3_30), fontSize = 12.sp, color = palette.textTertiary)
                }
            }
            Spacer(Modifier.height(12.dp))
            // Field and domain chip side by side, as designed; at large font
            // sizes or on very narrow windows the chip drops under the field
            // instead of squeezing the handle down to a few characters.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f || maxWidth < 250.dp
                val handleField: @Composable () -> Unit = {
                    AppTextField(
                        label = stringResource(R.string.acct_choose_your_own_address),
                        value = state.customHandle,
                        onValueChange = vm::onCustomHandleChange,
                        hint = stringResource(R.string.acct_yourname),
                        showLabel = false,
                    )
                }
                val domainChip: @Composable () -> Unit = {
                    Box {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, palette.border, RoundedCornerShape(14.dp))
                                .background(palette.inputFill)
                                .clickable { domainMenuOpen = true }
                                .padding(horizontal = 12.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("@grapsee.com", fontSize = 14.sp, color = palette.textSecondary)
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Rounded.ExpandMore,
                                contentDescription = stringResource(R.string.acct_domain),
                                tint = palette.textTertiary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = domainMenuOpen,
                            onDismissRequest = { domainMenuOpen = false },
                            containerColor = palette.card,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("@grapsee.com") },
                                onClick = { domainMenuOpen = false },
                            )
                        }
                    }
                }
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        handleField()
                        domainChip()
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { handleField() }
                        Spacer(Modifier.width(8.dp))
                        domainChip()
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            when (state.handleState.status) {
                SignUpViewModel.HandleState.Status.Available -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(palette.success),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.onPrimary, modifier = Modifier.size(12.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.acct_this_address_is_available), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.success)
                }
                SignUpViewModel.HandleState.Status.Checking ->
                    Text(stringResource(R.string.acct_checking), fontSize = 13.sp, color = palette.textTertiary)
                SignUpViewModel.HandleState.Status.Taken ->
                    Text(
                        state.handleState.suggestion?.let { stringResource(R.string.acct_address_taken_try, "$it@grapsee.com") }
                            ?: stringResource(R.string.acct_address_taken),
                        fontSize = 13.sp, color = palette.error,
                    )
                SignUpViewModel.HandleState.Status.Invalid ->
                    Text(state.handleState.message ?: stringResource(R.string.acct_that_address_cannot_be_used), fontSize = 13.sp, color = palette.error)
                SignUpViewModel.HandleState.Status.Unknown ->
                    Text(stringResource(R.string.acct_could_not_check_right_now_confirmed), fontSize = 13.sp, color = palette.textTertiary)
                SignUpViewModel.HandleState.Status.Idle -> Unit
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.acct_use_letters_numbers_dots_dashes_or),
                fontSize = 12.sp, lineHeight = 17.sp, color = palette.textTertiary,
            )
        }
    }
}

@Composable
private fun RadioRow(address: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(
                    if (selected) 6.dp else 1.5.dp,
                    if (selected) palette.primarySolid else palette.border,
                    CircleShape,
                )
        )
        Spacer(Modifier.width(12.dp))
        Text(address, fontSize = 14.sp, color = palette.textPrimary, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.acct_available),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = palette.success,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(palette.success.copy(alpha = 0.1f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
