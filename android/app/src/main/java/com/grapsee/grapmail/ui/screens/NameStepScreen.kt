package com.grapsee.grapmail.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.logic.Validators
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.theme.LocalPalette
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R

/**
 * Step 1 — "What's your name?" — founder design verbatim: the person
 * illustration, "First name *" / "Last name *" with the person glyph in each
 * field, red "First name is required" / "Last name is required" error rows
 * with the alert icon, "Next →" action.
 */
@Composable
fun NameStepScreen(vm: SignUpViewModel, onBack: () -> Unit, onSignIn: () -> Unit, onNext: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var firstError by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var lastError by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Errors stay hidden until Next has been attempted once (mirrors iOS);
    // a fresh screen never opens with red "is required" rows.
    var attempted by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    fun advance() {
        attempted = true
        firstError = Validators.firstName(state.firstName)
        lastError = Validators.lastName(state.lastName)
        if (firstError == null && lastError == null) {
            focus.clearFocus()
            onNext()
        }
    }

    WizardScaffold(
        step = 1,
        title = stringResource(R.string.acct_whats_your_name),
        subtitle = AnnotatedSubtitle(rest = stringResource(R.string.acct_this_name_appears_on_your_grapmail)),
        onBack = onBack,
        onSignIn = onSignIn,
        bottomBar = {
            GradientButton(
                stringResource(R.string.acct_next),
                onClick = ::advance,
                enabled = state.firstName.isNotBlank() && state.lastName.isNotBlank(),
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
        },
    ) {
        FieldWithError(
            label = stringResource(R.string.acct_first_name),
            value = state.firstName,
            onValueChange = {
                vm.update { copy(firstName = it) }
                if (firstError != null) firstError = Validators.firstName(it)
            },
            error = firstError ?: stringResource(R.string.acct_first_name_is_required),
            showError = attempted && firstError != null,
            imeAction = ImeAction.Next,
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }),
        )
        Spacer(Modifier.height(12.dp))
        FieldWithError(
            label = stringResource(R.string.acct_last_name),
            value = state.lastName,
            onValueChange = {
                vm.update { copy(lastName = it) }
                if (lastError != null) lastError = Validators.lastName(it)
            },
            error = lastError ?: stringResource(R.string.acct_last_name_is_required),
            showError = attempted && lastError != null,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { advance() }),
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
    onValueChange: (String) -> Unit,
    error: String?,
    showError: Boolean,
    imeAction: ImeAction,
    keyboardActions: KeyboardActions,
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
            showLabel = false,
            imeAction = imeAction,
            keyboardActions = keyboardActions,
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

