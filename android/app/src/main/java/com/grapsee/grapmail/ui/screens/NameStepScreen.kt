package com.grapsee.grapmail.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.logic.Validators
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.illRes
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Step 1 — "What's your name?" — founder design verbatim: the person
 * illustration, "First name *" / "Last name *" with the person glyph in each
 * field, red "First name is required" / "Last name is required" error rows
 * with the alert icon, "Next →" action.
 */
@Composable
fun NameStepScreen(vm: SignUpViewModel, onBack: () -> Unit, onSignIn: () -> Unit, onNext: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsState()
    var firstError by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var lastError by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Errors stay hidden until Next has been attempted once (mirrors iOS);
    // a fresh screen never opens with red "is required" rows.
    var attempted by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    WizardScaffold(
        step = 1,
        title = "What's your name?",
        subtitle = AnnotatedSubtitle(boldLead = "Let's get started.", rest = "This will be used for your GrapMail account and profile."),
        onBack = onBack,
        onSignIn = onSignIn,
        bottomBar = {
            GradientButton(
                "Next",
                onClick = {
                    attempted = true
                    firstError = Validators.firstName(state.firstName)
                    lastError = Validators.lastName(state.lastName)
                    if (firstError == null && lastError == null) onNext()
                },
                enabled = state.firstName.isNotBlank() && state.lastName.isNotBlank(),
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
        },
    ) {
        Image(
            painter = painterResource(illRes("step1_name")),
            contentDescription = null,
            // No hardcoded aspect ratio: the re-cropped artwork has its own
            // intrinsic size, and Image preserves it.
            modifier = Modifier.fillMaxWidth(0.92f),
        )
        Spacer(Modifier.height(24.dp))

        FieldWithError(
            label = "First name",
            value = state.firstName,
            hint = "Enter your first name",
            onValueChange = {
                vm.update { copy(firstName = it) }
                if (firstError != null) firstError = Validators.firstName(it)
            },
            error = firstError ?: "First name is required",
            showError = attempted && state.firstName.isBlank(),
        )
        Spacer(Modifier.height(18.dp))
        FieldWithError(
            label = "Last name",
            value = state.lastName,
            hint = "Enter your last name",
            onValueChange = {
                vm.update { copy(lastName = it) }
                if (lastError != null) lastError = Validators.lastName(it)
            },
            error = lastError ?: "Last name is required",
            showError = attempted && state.lastName.isBlank(),
        )
    }
}

/**
 * The design's field block: "Label *" with the red asterisk, input with the
 * person glyph, and the red alert row — shown live once the person has typed
 * and cleared, or on Continue.
 */
@Composable
private fun FieldWithError(
    label: String,
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
    error: String?,
    showError: Boolean,
) {
    val palette = LocalPalette.current
    Column {
        Row {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Text(" *", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.error)
        }
        Spacer(Modifier.height(8.dp))
        AppTextField(
            label = label,
            value = value,
            onValueChange = onValueChange,
            hint = hint,
            showLabel = false,
            leading = {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = palette.textTertiary,
                    modifier = Modifier.size(20.dp),
                )
            },
        )
        // The design's motion table: error rows slide down + fade, 200ms.
        AnimatedVisibility(
            visible = showError && error != null,
            enter = slideInVertically(animationSpec = tween(200)) { -it / 2 } + fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(animationSpec = tween(200)) { -it / 2 } + fadeOut(animationSpec = tween(200)),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Error,
                        contentDescription = null,
                        tint = palette.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(error ?: "", fontSize = 13.sp, color = palette.error)
                }
            }
        }
    }
}

