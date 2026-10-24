package com.grapsee.grapmail.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.OutlineButton
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Step 3 — "Choose your address": two live-checked suggestions from the name
 * plus a custom field with availability feedback and suggestions. Founder
 * design verbatim; checks hit the real /auth/grapmail/check.
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
        title = "Choose your address",
        subtitle = "Your GrapMail address is your identity.",
        onBack = onBack,
        bottomBar = {
            GradientButton(
                "Continue",
                enabled = state.chosenHandle.isNotBlank(),
                onClick = onNext,
            )
        },
    ) {
        if (state.suggestionsLoading) {
            Text("Finding available addresses…", fontSize = 13.sp, color = palette.textTertiary)
            Spacer(Modifier.height(14.dp))
        }
        state.suggestions.forEach { handle ->
            SuggestionCard(
                address = "$handle@grapsee.com",
                selected = state.chosenHandle == handle && !state.customOpen,
                onClick = { vm.pickSuggestion(handle) },
            )
            Spacer(Modifier.height(10.dp))
        }
        if (!state.suggestionsLoading && state.suggestions.isEmpty()) {
            Text(
                "No suggestions from your name — create your own below.",
                fontSize = 13.sp, color = palette.textTertiary,
            )
            Spacer(Modifier.height(14.dp))
        }

        if (!state.customOpen) {
            OutlineButton("Create your own", onClick = { vm.update { copy(customOpen = true) } })
        } else {
            AppTextField(
                label = "Custom address",
                value = state.customHandle,
                onValueChange = vm::onCustomHandleChange,
                keyboard = KeyboardType.Ascii,
                trailing = {
                    Text("@grapsee.com", fontSize = 13.sp, color = palette.textTertiary)
                },
            )
            Spacer(Modifier.height(8.dp))
            when (state.handleState.status) {
                SignUpViewModel.HandleState.Status.Checking ->
                    Text("Checking…", fontSize = 13.sp, color = palette.textTertiary)
                SignUpViewModel.HandleState.Status.Available ->
                    Text("${state.customHandle}@grapsee.com is free ✓", fontSize = 13.sp, color = palette.success, fontWeight = FontWeight.Medium)
                SignUpViewModel.HandleState.Status.Taken ->
                    Text(
                        buildString {
                            append("That address is taken")
                            state.handleState.suggestion?.let { append(". Try $it@grapsee.com") }
                        },
                        fontSize = 13.sp, color = palette.error,
                    )
                SignUpViewModel.HandleState.Status.Invalid ->
                    Text(
                        state.handleState.message ?: "Use 3–30 characters: letters, numbers, dots, dashes.",
                        fontSize = 13.sp, color = palette.error,
                    )
                SignUpViewModel.HandleState.Status.Unknown ->
                    Text("Could not check right now — confirmed when the account is created.", fontSize = 13.sp, color = palette.textTertiary)
                SignUpViewModel.HandleState.Status.Idle -> Unit
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Back to suggestions",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = palette.primarySolid,
                modifier = Modifier.clickable { vm.update { copy(customOpen = false, customHandle = "", chosenHandle = suggestions.firstOrNull() ?: "") } },
            )
        }
    }
}

@Composable
private fun SuggestionCard(address: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (selected) Modifier.background(palette.primarySolid.copy(alpha = 0.08f))
                else Modifier.background(palette.card)
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            address,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) palette.primarySolid else palette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(20.dp))
        } else {
            Icon(Icons.Rounded.Edit, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(16.dp))
        }
    }
}
