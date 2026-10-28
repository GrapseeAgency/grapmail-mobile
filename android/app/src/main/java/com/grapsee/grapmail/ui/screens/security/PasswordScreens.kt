package com.grapsee.grapmail.ui.screens.security

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.ui.components.AccountLink
import com.grapsee.grapmail.ui.components.AccountScreen
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.FooterNote
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.InfoCard
import com.grapsee.grapmail.ui.components.RuleLine
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * 11 / 23 — change the password.
 *
 * One screen, two entry points: the hub's ordinary "change it" and the alert's
 * "change it now". The fields and the rules are identical; only the framing
 * differs, so the person is never asked to learn a second form under pressure.
 *
 * 23 exists because a password change after suspicious activity has to keep the
 * device that made the change signed in — all the others are cut off by the
 * epoch bump, and the reissued token is written back here.
 */
@Composable
fun ChangePasswordScreen(
    afterSuspicious: Boolean,
    onBack: () -> Unit,
    onForgot: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // These four ticks are exactly the four rules the server now enforces
    // (`passwordProblem`). The previous checklist asked for upper AND lower case,
    // which no route required — a tick that could not fail, next to a rule the
    // API would not honour.
    val longEnough = next.length >= 8
    // ASCII classes, matching `passwordProblem` on the server exactly. Kotlin's
    // isLetter()/isDigit() are Unicode-aware, so a password of `π²²²²²²²²` ticked
    // every box here and was then refused by the route — four green rules above an
    // error the screen could not explain.
    val isLetter = { ch: Char -> ch in 'A'..'Z' || ch in 'a'..'z' }
    val isDigit = { ch: Char -> ch in '0'..'9' }
    val hasLetter = next.any(isLetter)
    val hasNumber = next.any(isDigit)
    val hasSymbol = next.any { !isLetter(it) && !isDigit(it) }
    val matches = next.isNotEmpty() && next == confirm
    val rulesMet = longEnough && hasLetter && hasNumber && hasSymbol

    AccountScreen(
        onBack = onBack,
        illustration = if (afterSuspicious) "ill_sec_password_after" else "ill_sec_password",
        illustrationWidth = if (afterSuspicious) 180 else 150,
        eyebrow = if (afterSuspicious) "After Suspicious Activity" else null,
        title = if (afterSuspicious) "Change Your Password" else "Change Password",
        body = if (afterSuspicious)
            "Your account was accessed from an unfamiliar device. To keep your GrapMail account secure, please update your password now."
        else
            "Choose a strong password you have not used here before.",
        footer = {
            Column {
                GradientButton(
                    if (afterSuspicious) "Update Password" else "Change Password",
                    trailing = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 6.dp)) },
                    onClick = {
                        when {
                            current.isBlank() -> { error = "Enter your current password."; return@GradientButton }
                            !longEnough -> { error = "Use at least 8 characters."; return@GradientButton }
                            !hasLetter -> { error = "Add at least one letter."; return@GradientButton }
                            !hasNumber -> { error = "Add at least one number."; return@GradientButton }
                            !hasSymbol -> { error = "Add at least one symbol."; return@GradientButton }
                            !matches -> { error = "The two new passwords do not match."; return@GradientButton }
                        }
                        busy = true; error = null
                        scope.launch {
                            try {
                                val token = SessionStore.token(context) ?: throw IllegalStateException("Sign in again.")
                                val resp = Api.changePassword(token, current, next)
                                // Every other device is signed out by this change,
                                // this one included: keep the replacement token or
                                // the next screen answers "session expired".
                                resp.accessToken?.let { fresh ->
                                    SessionStore.save(
                                        context,
                                        fresh,
                                        SessionStore.address(context) ?: "",
                                        SessionStore.name(context) ?: "",
                                        // The server says when this token dies. Storing 0
                                        // means "never expires", so the app kept booting
                                        // straight into a home screen whose every call 401s.
                                        resp.tokenExpiresAt ?: 0L,
                                    )
                                }
                                onChanged()
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = current.isNotBlank() && rulesMet && matches,
                    busy = busy,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink("Back", onBack)
                }
            }
        },
    ) {
        if (afterSuspicious) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.error.copy(alpha = 0.08f))
                    .border(1.dp, palette.error.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = palette.error, modifier = Modifier.size(17.dp))
                Text(
                    "Unrecognized activity was found on your account.",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = palette.error,
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        AppTextField("Current Password", current, { current = it; error = null }, password = true, hint = "Enter your current password", leading = { Icon(Icons.Rounded.Lock, contentDescription = null) })
        Spacer(Modifier.height(12.dp))
        AppTextField("New Password", next, { next = it; error = null }, password = true, hint = "Enter your new password", leading = { Icon(Icons.Rounded.Lock, contentDescription = null) })
        Spacer(Modifier.height(12.dp))
        AppTextField(
            "Confirm New Password", confirm, { confirm = it; error = null },
            password = true, hint = "Re-enter your new password", leading = { Icon(Icons.Rounded.Lock, contentDescription = null) },
            error = if (confirm.isNotEmpty() && !matches) "Passwords do not match." else null,
        )
        Spacer(Modifier.height(14.dp))
        InfoCard {
            Text(
                "Password Requirements",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            RuleLine("At least 8 characters", longEnough)
            RuleLine("Contains a letter", hasLetter)
            RuleLine("Contains a number", hasNumber)
            RuleLine("Contains a symbol", hasSymbol)
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, fontSize = 12.sp, color = palette.error)
        }
    }
}

/**
 * 12 / 24 — the confirmation.
 *
 * The copy says what actually happened: every other device is signed out, this
 * one is not. Promising "you are safe" without saying that would leave the
 * person to discover the sign-outs device by device.
 */
@Composable
fun PasswordChangedScreen(
    updated: Boolean,
    onDone: () -> Unit,
    onSupport: () -> Unit,
) {
    val palette = LocalPalette.current
    AccountScreen(
        onBack = onDone,
        illustration = if (updated) "ill_sec_password_updated" else "ill_sec_password_ok",
        illustrationWidth = 170,
        title = if (updated) "Password Updated Successfully" else "Password Changed Successfully",
        body = "Your password has been updated successfully. Other devices were signed out; this one stays signed in.",
        footer = {
            Column {
                GradientButton("Continue", onDone)
                Spacer(Modifier.height(6.dp))
                FooterNote(
                    text = "If you did not make this change, contact the support team immediately.",
                    linkText = "support team",
                    onLink = onSupport,
                )
            }
        },
    ) {
        InfoCard {
            Text("What's Next?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(16.dp))
                Text(
                    "You'll need to sign in again on your other devices, including mobile apps, desktop, and other browsers.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = palette.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        InfoNoteCard(body = "This helps keep your account secure and prevents unauthorized access.")
    }
}
