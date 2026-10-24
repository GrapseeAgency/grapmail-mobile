package com.grapsee.grapmail.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * The primary CTA: full-width pill-adjacent button with the measured
 * #206BFD→#1560F5 gradient, pressed scale, and the exact disabled tint
 * (#C4DBFD light / #1E3A66 dark) from the terms screen.
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, animationSpec = tween(120), label = "press")
    val fill by animateColorAsState(
        if (enabled) palette.primaryTop else palette.buttonDisabled,
        tween(180), label = "fill"
    )
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .scale(scale),
        shape = RoundedCornerShape(16.dp),
        interactionSource = interaction,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = palette.onPrimary,
            disabledContainerColor = palette.buttonDisabled,
            disabledContentColor = palette.onPrimary,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(
                    if (enabled) Brush.verticalGradient(listOf(palette.primaryTop, palette.primaryBottom))
                    else Brush.verticalGradient(listOf(palette.buttonDisabled, palette.buttonDisabled)),
                    RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = palette.onPrimary,
                    strokeWidth = 2.5.dp,
                )
            } else {
                Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Secondary outline button from the welcome screen. */
@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, palette.border),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
    }
}

/**
 * The design's filled text field: 54dp, radius 14, soft fill, label above,
 * error state with red stroke + note, focus ring in brand blue.
 */
@Composable
fun AppTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    hint: String? = null,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    trailing: @Composable (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    var visible by rememberSaveable { mutableStateOf(!password) }
    val stroke by animateColorAsState(
        when {
            error != null -> palette.error
            focused -> palette.primarySolid
            else -> palette.border
        }, tween(150), label = "stroke"
    )
    Column(modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.textSecondary)
        Spacer(Modifier.height(6.dp))
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .onFocusChanged { focused = it.isFocused },
            shape = RoundedCornerShape(14.dp),
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            placeholder = hint?.let { { Text(it, color = palette.textTertiary, fontSize = 15.sp) } },
            leadingIcon = leading,
            trailingIcon = {
                if (password) {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (visible) "Hide password" else "Show password",
                            tint = palette.textTertiary,
                        )
                    }
                } else trailing?.invoke()
            },
            isError = error != null,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = stroke,
                unfocusedBorderColor = stroke,
                errorBorderColor = palette.error,
                focusedContainerColor = palette.inputFill,
                unfocusedContainerColor = palette.inputFill,
                errorContainerColor = palette.inputFill,
                focusedTextColor = palette.textPrimary,
                unfocusedTextColor = palette.textPrimary,
                cursorColor = palette.primarySolid,
            ),
        )
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error, fontSize = 13.sp, color = palette.error)
        } else if (hint != null && value.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(hint, fontSize = 13.sp, color = palette.textTertiary)
        }
    }
}

/** The 5-segment step indicator, segments filling with an animated sweep. */
@Composable
fun StepProgress(step: Int, total: Int = 5, onBack: (() -> Unit)? = null) {
    val palette = LocalPalette.current
    Column {
        if (onBack != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = palette.textSecondary,
                    )
                }
                Eyebrow("Step $step of $total", color = palette.textTertiary)
            }
            Spacer(Modifier.height(14.dp))
        } else {
            Eyebrow("Step $step of $total", color = palette.textTertiary)
            Spacer(Modifier.height(14.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(total) { i ->
                val active = i < step
                val frac by animateFloatAsState(
                    when {
                        i < step - 1 -> 1f
                        i == step - 1 -> 1f
                        else -> 0f
                    },
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 300f),
                    label = "seg$i",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.stepInactive)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(frac)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Brush.horizontalGradient(listOf(palette.primaryTop, palette.primaryBottom)))
                    )
                }
            }
        }
    }
}

/** Requirement row with animated check state (password checklist). */
@Composable
fun RequirementRow(label: String, met: Boolean) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (met) Icons.Rounded.CheckCircle else Icons.Rounded.Check,
            contentDescription = null,
            tint = if (met) palette.success else palette.textTertiary,
            modifier = Modifier.size(18.dp),
        )
        Text(label, fontSize = 13.sp, color = if (met) palette.success else palette.textSecondary)
    }
}

/** Soft info banner (address summary, verification hints). */
@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, icon: (@Composable () -> Unit)? = null) {
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.softFill)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon?.invoke()
        Text(text, fontSize = 13.sp, color = palette.textSecondary, lineHeight = 18.sp)
    }
}

/** Terms checkbox with a spring. */
@Composable
fun TermsCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        androidx.compose.material3.Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.CheckboxDefaults.colors(
                checkedColor = palette.primarySolid,
                uncheckedColor = palette.textTertiary,
                checkmarkColor = palette.onPrimary,
            ),
        )
        label()
    }
}

/** Detects that the terms box was scrolled to the end (the design's gate). */
@Composable
fun rememberScrolledToEnd(state: ScrollState, enabled: Boolean = true): Boolean {
    var scrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.value, state.maxValue, enabled) {
        if (!enabled || scrolled) return@LaunchedEffect
        if (state.maxValue > 0 && state.value >= state.maxValue - 24) scrolled = true
        if (state.maxValue == 0) scrolled = true
    }
    return scrolled
}
