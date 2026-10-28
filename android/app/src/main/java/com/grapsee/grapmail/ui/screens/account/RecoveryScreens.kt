package com.grapsee.grapmail.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.logic.AccountFlowViewModel
import com.grapsee.grapmail.ui.components.AccountLink
import com.grapsee.grapmail.ui.components.AccountScreen
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.ChipTone
import com.grapsee.grapmail.ui.components.CodeCells
import com.grapsee.grapmail.ui.components.CodeExpiry
import com.grapsee.grapmail.ui.components.FooterNote
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.InfoPanel
import com.grapsee.grapmail.ui.components.OrDivider
import com.grapsee.grapmail.ui.components.RuleLine
import com.grapsee.grapmail.ui.components.StatusChip
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * Forgot password — founder design verbatim: envelope-and-lock artwork,
 * FORGOT PASSWORD eyebrow, the single identifier field, the arrowed CTA and the
 * support note.
 *
 * The one added line is "Find your GrapMail address": the artwork in this
 * sector ships a lookup screen but no way into it, and the person who forgets
 * which address they own is exactly the person on this screen.
 */
@Composable
fun ForgotPasswordScreen(
    flow: AccountFlowViewModel,
    onBack: () -> Unit,
    onCodeSent: () -> Unit,
    onNeedsManualReview: () -> Unit,
    onFindAddress: () -> Unit,
    onSupport: () -> Unit,
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val state by flow.state.collectAsState()

    var identifier by rememberSaveable { mutableStateOf(state.identifier) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (identifier.isBlank()) return
        busy = true; error = null
        scope.launch {
            try {
                val resp = Api.forgotPassword(identifier.trim())
                busy = false
                flow.update {
                    copy(
                        identifier = identifier.trim(),
                        challengeId = resp.challengeId,
                        expiresAt = resp.expiresAt ?: 0L,
                        maskedPhone = resp.maskedPhone,
                        code = "",
                    )
                }
                // No challenge means no code went out — the account has no
                // number to text, or has none registered at all. That is the
                // manual-review path, not a failure to report as an error.
                if (resp.challengeId != null) onCodeSent() else onNeedsManualReview()
            } catch (e: ApiException) {
                busy = false
                error = e.message ?: "We could not start the reset. Try again."
            } catch (_: Exception) {
                busy = false
                error = "Network error. Please try again."
            }
        }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "account-forgot",
        eyebrow = "Forgot password",
        title = "Reset your password",
        body = "Enter your GrapMail address or phone number and we'll send you instructions to reset your password securely.",
        footer = {
            GradientButton(
                "Continue",
                onClick = { submit() },
                enabled = identifier.isNotBlank(),
                busy = busy,
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error ?: "", fontSize = 13.sp, color = palette.error)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                AccountLink("Find your GrapMail address", onClick = onFindAddress)
            }
            Spacer(Modifier.height(14.dp))
            FooterNote(
                text = "Need help? Contact our support team.",
                linkText = "support team",
                onLink = onSupport,
            )
            Spacer(Modifier.height(4.dp))
        },
    ) {
        AppTextField(
            label = "GrapMail address or phone number",
            value = identifier,
            onValueChange = { identifier = it; error = null },
            hint = "GrapMail address or phone number",
            showLabel = false,
            keyboard = KeyboardType.Email,
            leading = {
                Icon(Icons.Rounded.Mail, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(20.dp))
            },
        )
        Spacer(Modifier.height(22.dp))
    }
}

/**
 * Verify recovery code — the code screen of the reset arc. Continue carries the
 * code forward; the backend checks it together with the new password, so a
 * wrong code surfaces on the password screen and sends the person back here.
 */
@Composable
fun RecoveryCodeScreen(
    flow: AccountFlowViewModel,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onBackToSignIn: () -> Unit,
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val state by flow.state.collectAsState()

    var code by rememberSaveable { mutableStateOf(state.code) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var resending by remember { mutableStateOf(false) }

    fun resend() {
        if (state.identifier.isBlank()) return
        resending = true; error = null; notice = null
        scope.launch {
            try {
                val resp = Api.forgotPassword(state.identifier)
                resending = false
                if (resp.challengeId != null) {
                    code = ""
                    flow.update { copy(challengeId = resp.challengeId, expiresAt = resp.expiresAt ?: 0L) }
                    notice = "A new code is on its way."
                } else {
                    error = "We couldn't send another code. Contact support and we'll help."
                }
            } catch (e: ApiException) {
                resending = false
                error = e.message ?: "Could not resend the code. Try again."
            } catch (_: Exception) {
                resending = false
                error = "Network error. Please try again."
            }
        }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "account-recovery-code",
        eyebrow = "Verify recovery code",
        title = "Enter the 6-digit code",
        body = "We've sent a 6-digit verification code to your verified phone number. Please enter the code to continue.",
        footer = {
            GradientButton(
                "Continue",
                onClick = {
                    flow.update { copy(code = code) }
                    onContinue()
                },
                enabled = code.length == 6,
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
            Spacer(Modifier.height(14.dp))
            OrDivider()
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                AccountLink(if (resending) "Sending…" else "Resend code", onClick = { resend() })
            }
            if (notice != null) {
                Spacer(Modifier.height(6.dp))
                Text(notice ?: "", fontSize = 12.sp, color = palette.success)
            }
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(error ?: "", fontSize = 12.sp, color = palette.error)
            }
            Spacer(Modifier.height(12.dp))
            FooterNote(text = "Didn't receive the code? Check your spam folder.", divider = false)
            Spacer(Modifier.height(4.dp))
        },
    ) {
        CodeCells(
            length = 6,
            value = code,
            onValueChange = {
                code = it.filter(Char::isDigit).take(6)
                error = null
            },
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CodeExpiry(expiresAt = state.expiresAt, onExpired = { })
        }
    }
}

private val COMMON_PASSWORDS = setOf(
    "password", "password1", "password123", "12345678", "123456789", "qwerty123",
    "letmein1", "iloveyou", "welcome1", "admin123", "grapmail", "grapmail1",
)

private fun notCommon(pw: String): Boolean =
    pw.length >= 8 && pw.lowercase() !in COMMON_PASSWORDS

/**
 * Set a new password — founder design verbatim: shield artwork, the two
 * password fields, the five-segment strength meter and the four rules.
 *
 * The four rules shown are exactly the four `passwordProblem` enforces on the
 * server (length, a letter, a number, a symbol), in ASCII classes so the check
 * cannot pass a password the route would refuse. The reset endpoint now applies
 * the full policy, not length alone, so the client gate must too — otherwise a
 * no-letter password ticks every box here and comes back rejected.
 */
@Composable
fun NewPasswordScreen(
    flow: AccountFlowViewModel,
    onBack: () -> Unit,
    onBackToCode: () -> Unit,
    onReset: (address: String?) -> Unit,
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val state by flow.state.collectAsState()

    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val hasLength = password.length >= 8
    // ASCII classes, matching `passwordProblem` on the server exactly. The old
    // gate checked a digit and a symbol but no letter, so `1234567!` cleared every
    // rule on screen and was then refused by the reset route for having no letter.
    val hasLetter = password.any { it in 'A'..'Z' || it in 'a'..'z' }
    val hasDigit = password.any { it in '0'..'9' }
    // A symbol is any character the server's `[^A-Za-z0-9]` would match — space
    // included, exactly as the route counts it. Excluding space here would reject
    // on screen a password the API would accept.
    val hasSpecial = password.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9') }
    val isFresh = notCommon(password)
    val matches = confirm.isNotEmpty() && confirm == password
    val ready = hasLength && hasLetter && hasDigit && hasSpecial && isFresh && matches
    val filled = listOf(hasLength, hasLetter, hasDigit, hasSpecial).count { it }

    fun submit() {
        val challenge = state.challengeId ?: run {
            error = "That reset link expired. Start again."
            return
        }
        busy = true; error = null
        scope.launch {
            try {
                val resp = Api.resetPassword(challenge, state.code, password)
                busy = false
                onReset(resp.address)
            } catch (e: ApiException) {
                busy = false
                error = when (e.code) {
                    // The server names the exact rule that failed. Showing the
                    // real message beats a hardcoded "at least 8 characters", which
                    // was wrong for every other rule the policy now enforces.
                    "WEAK_PASSWORD" -> e.message ?: "Use at least 8 characters with a letter, a number and a symbol."
                    "VERIFY_FAILED" -> "That code is not correct, or it has expired."
                    else -> e.message ?: "We could not reset the password. Try again."
                }
            } catch (_: Exception) {
                busy = false
                error = "Network error. Please try again."
            }
        }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "account-new-password",
        eyebrow = "Create new password",
        title = "Set a new password",
        body = "Your new password must be strong and secure. Make sure it's something you haven't used before.",
        footer = {
            GradientButton(
                "Reset Password",
                onClick = { submit() },
                enabled = ready,
                busy = busy,
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error ?: "", fontSize = 13.sp, color = palette.error)
                if (error?.contains("code") == true) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        AccountLink("Enter the code again", onClick = onBackToCode)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            FooterNote(text = "Your password will be encrypted and kept secure.", divider = false)
            Spacer(Modifier.height(4.dp))
        },
    ) {
        AppTextField(
            label = "New password",
            value = password,
            onValueChange = { password = it; error = null },
            hint = "New password",
            showLabel = false,
            password = true,
            leading = {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(20.dp))
            },
        )
        Spacer(Modifier.height(12.dp))

        // Five-segment meter, then the "Password strength" caption underneath,
        // exactly as the artwork stacks them.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(5) { i ->
                val lit = filled > i
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (lit) palette.primarySolid else palette.stepInactive),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Password strength", fontSize = 12.sp, color = palette.textTertiary)

        Spacer(Modifier.height(18.dp))
        AppTextField(
            label = "Confirm new password",
            value = confirm,
            onValueChange = { confirm = it },
            hint = "Confirm new password",
            showLabel = false,
            password = true,
            leading = {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(20.dp))
            },
        )
        Spacer(Modifier.height(16.dp))
        RuleLine("At least 8 characters", hasLength)
        RuleLine("Include a letter", hasLetter)
        RuleLine("Include a number", hasDigit)
        RuleLine("Include a special character", hasSpecial)
        RuleLine("Not commonly used", isFresh)
        if (confirm.isNotEmpty() && !matches) {
            Spacer(Modifier.height(8.dp))
            Text("Both passwords must match.", fontSize = 13.sp, color = palette.error)
        }
    }
}

/** Password reset successful — chip, headline, the way back to sign-in. */
@Composable
fun ResetDoneScreen(onBackToSignIn: () -> Unit) {
    val palette = LocalPalette.current
    AccountScreen(
        onBack = null,
        illustration = "account-reset-ok",
        footer = {
            GradientButton(
                "Back to Sign In",
                onClick = onBackToSignIn,
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
            Spacer(Modifier.height(18.dp))
            FooterNote(
                text = "For your security, we recommend keeping your password private and using two-step verification.",
            )
            Spacer(Modifier.height(4.dp))
        },
    ) {
        StatusChip("Password Reset Successful", ChipTone.Success)
        Spacer(Modifier.height(16.dp))
        Text(
            "Your password\nhas been reset",
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = palette.textPrimary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Your GrapMail account is now secure with your new password. You can now sign in and continue using all the features.",
            fontSize = 15.sp,
            lineHeight = 23.sp,
            color = palette.textSecondary,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Account recovery pending — the honest outcome when no code could be sent:
 * no number on file, or a request that needs a person to look at it.
 */
@Composable
fun RecoveryPendingScreen(
    flow: AccountFlowViewModel,
    onBackToSignIn: () -> Unit,
    onSupport: () -> Unit,
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val state by flow.state.collectAsState()

    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun retry() {
        if (state.identifier.isBlank()) return
        busy = true; notice = null
        scope.launch {
            try {
                val resp = Api.forgotPassword(state.identifier)
                busy = false
                notice = if (resp.challengeId != null) {
                    "A code is on its way. Check your phone."
                } else {
                    "Still in review. We'll message you as soon as it clears."
                }
            } catch (e: ApiException) {
                busy = false
                notice = e.message ?: "We couldn't check again. Try in a minute."
            } catch (_: Exception) {
                busy = false
                notice = "Network error. Please try again."
            }
        }
    }

    AccountScreen(
        onBack = null,
        illustration = "account-pending",
        footer = {
            GradientButton(
                "Try Again",
                onClick = { retry() },
                busy = busy,
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
            if (notice != null) {
                Spacer(Modifier.height(10.dp))
                Text(notice ?: "", fontSize = 13.sp, color = palette.textSecondary)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                AccountLink("Back to Sign In", onClick = onBackToSignIn)
            }
            Spacer(Modifier.height(14.dp))
            OrDivider()
            Spacer(Modifier.height(14.dp))
            FooterNote(
                text = "Need help? Contact our support team.",
                linkText = "support team",
                onLink = onSupport,
                divider = false,
            )
            Spacer(Modifier.height(4.dp))
        },
    ) {
        StatusChip("Account Recovery Pending", ChipTone.Warning)
        Spacer(Modifier.height(16.dp))
        Text(
            "We're still working\non your recovery",
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = palette.textPrimary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "There might be a short delay while we verify your information. You'll get an update soon at your verified phone number or email.",
            fontSize = 15.sp,
            lineHeight = 23.sp,
            color = palette.textSecondary,
        )
        Spacer(Modifier.height(18.dp))
        InfoPanel(
            title = "What's happening?",
            body = "We're verifying your recovery request. This usually takes a few minutes, but can take up to 24 hours in some cases.",
            icon = {
                Icon(Icons.Rounded.Schedule, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(18.dp))
            },
        )
        Spacer(Modifier.height(8.dp))
    }
}
