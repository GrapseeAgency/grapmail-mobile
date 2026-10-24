package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

/**
 * Step 3 — "Choose your GrapMail address" — founder design verbatim: the
 * "Suggested for you" card with radio rows and green Available pills, the
 * "Create your own" card with the pencil, the handle input with the
 * @grapsee.com suffix, the green "This address is available!" row, and the
 * character/reserved-names rules caption.
 */
@Composable
fun AddressStepScreen(vm: SignUpViewModel, onBack: () -> Unit, onNext: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsState()
    val loadedForKey = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.firstName, state.lastName) {
        val key = "${state.firstName}|${state.lastName}"
        if ((state.firstName.isNotBlank() || state.lastName.isNotBlank()) && loadedForKey.value != key) {
            loadedForKey.value = key
            vm.loadSuggestions()
        }
    }

    WizardScaffold(
        step = 3,
        title = "Choose your GrapMail address",
        subtitle = AnnotatedSubtitle(rest = "Pick an available address or create your own. You'll use this to sign in and receive emails."),
        onBack = onBack,
        bottomBar = {
            GradientButton(
                "Next",
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
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, palette.border, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Suggested for you", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                    Text("Based on your name. You can change it later.", fontSize = 12.sp, color = palette.textTertiary)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (state.suggestionsLoading) {
                Text("Finding available addresses…", fontSize = 13.sp, color = palette.textTertiary)
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
                        "No suggestions from your name — create your own below.",
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
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, palette.border, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Edit, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Create your own", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                    Text("Choose a unique address (3-30 characters).", fontSize = 12.sp, color = palette.textTertiary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    AppTextField(
                        label = "Address",
                        value = state.customHandle,
                        onValueChange = vm::onCustomHandleChange,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("@grapsee.com", fontSize = 14.sp, color = palette.textSecondary)
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
                    Text("This address is available!", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.success)
                }
                SignUpViewModel.HandleState.Status.Checking ->
                    Text("Checking…", fontSize = 13.sp, color = palette.textTertiary)
                SignUpViewModel.HandleState.Status.Taken ->
                    Text(
                        buildString {
                            append("That address is taken")
                            state.handleState.suggestion?.let { append(" — try $it@grapsee.com") }
                        },
                        fontSize = 13.sp, color = palette.error,
                    )
                SignUpViewModel.HandleState.Status.Invalid ->
                    Text(state.handleState.message ?: "That address cannot be used.", fontSize = 13.sp, color = palette.error)
                SignUpViewModel.HandleState.Status.Unknown ->
                    Text("Could not check right now — confirmed when the account is created.", fontSize = 13.sp, color = palette.textTertiary)
                SignUpViewModel.HandleState.Status.Idle -> Unit
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Use letters, numbers, dots (.), dashes (-) or underscores (_).\nAvoid reserved names like admin, support, postmaster, etc.",
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
            "Available",
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
