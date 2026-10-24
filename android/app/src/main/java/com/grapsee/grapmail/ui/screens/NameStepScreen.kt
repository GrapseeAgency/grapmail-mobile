package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.logic.Validators
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.GradientButton

/**
 * Step 1 — "What's your name?" Founder design verbatim: illustration,
 * two name fields with live validation, Continue gated on both names.
 */
@Composable
fun NameStepScreen(vm: SignUpViewModel, onBack: () -> Unit, onNext: () -> Unit) {
    val state by vm.state.collectAsState()
    var firstError by rememberSaveable { mutableStateOf<String?>(null) }
    var lastError by rememberSaveable { mutableStateOf<String?>(null) }

    WizardScaffold(
        step = 1,
        title = "What's your name?",
        subtitle = "Tell us your name to create your GrapMail identity.",
        onBack = onBack,
        bottomBar = {
            GradientButton(
                "Continue",
                enabled = state.firstName.isNotBlank() && state.lastName.isNotBlank(),
                onClick = {
                    firstError = Validators.firstName(state.firstName)
                    lastError = Validators.lastName(state.lastName)
                    if (firstError == null && lastError == null) onNext()
                },
            )
        },
    ) {
        Image(
            painter = painterResource(R.drawable.ill_step1_name),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(170.dp),
            contentScale = ContentScale.FillWidth,
        )
        Spacer(Modifier.height(28.dp))
        AppTextField(
            label = "First Name",
            value = state.firstName,
            onValueChange = {
                vm.update { copy(firstName = it) }
                if (firstError != null) firstError = Validators.firstName(it)
            },
            error = firstError,
        )
        Spacer(Modifier.height(16.dp))
        AppTextField(
            label = "Last Name",
            value = state.lastName,
            onValueChange = {
                vm.update { copy(lastName = it) }
                if (lastError != null) lastError = Validators.lastName(it)
            },
            error = lastError,
        )
    }
}
