package com.grapsee.grapmail.ui.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.SessionStore
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
import com.grapsee.grapmail.ui.components.StatusChip
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * Add a recovery email: the only way a GrapMail account gets its password back.
 *
 * GrapMail recovers by email only. Forgot-password sends its reset link to the
 * account's VERIFIED recovery address, so an address is useless until it is
 * proven, and this screen proves it in two steps against the backend:
 *
 *   1. POST /grapsee/auth/account/recovery-email/start   { email }
 *      -> a 6-digit code goes to that address, and a signed token comes back
 *   2. POST /grapsee/auth/account/recovery-email/confirm { token, code }
 *      -> the address is saved as verified
 *
 * Nothing is written to the account before step 2, so backing out halfway
 * leaves the account exactly as it was.
 *
 * Used twice: as the optional step right after sign-up ([onSkip] set), and from
 * Account Security, where the row keeps reminding until an address is verified.
 */
@Composable
fun RecoveryEmailScreen(
    onBack: (() -> Unit)?,
    onDone: () -> Unit,
    onSkip: (() -> Unit)? = null,
    currentEmail: String? = null,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var email by rememberSaveable { mutableStateOf(currentEmail.orEmpty()) }
    // The signed token is not saved: if the process dies, the person asks for a new code.
    var verificationToken by remember { mutableStateOf<String?>(null) }
    var maskedEmail by rememberSaveable { mutableStateOf<String?>(null) }
    var expiresAt by rememberSaveable { mutableStateOf(0L) }
    var code by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val looksLikeEmail = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$").matches(email.trim())

    fun sendCode(again: Boolean) {
        val address = email.trim().lowercase()
        if (!looksLikeEmail || busy) return
        busy = true; error = null; notice = null
        scope.launch {
            val session = SessionStore.snapshot(context)
            if (session == null) {
                busy = false
                error = "Your session has expired. Sign in again."
                return@launch
            }
            try {
                val resp = Api.startRecoveryEmail(session.token, address)
                busy = false
                if (resp.token == null) {
                    error = resp.error ?: "We couldn't send the code. Try again."
                    return@launch
                }
                verificationToken = resp.token
                maskedEmail = resp.maskedEmail ?: address
                expiresAt = resp.expiresAt ?: 0L
                code = ""
                if (again) notice = "A new code is on its way."
            } catch (e: ApiException) {
                busy = false
                error = e.message ?: "We couldn't send the code. Try again."
            } catch (_: Exception) {
                busy = false
                error = "Network error. Please try again."
            }
        }
    }

    fun confirm() {
        val tokenValue = verificationToken ?: return
        if (code.length != 6 || busy) return
        busy = true; error = null; notice = null
        scope.launch {
            val session = SessionStore.snapshot(context)
            if (session == null) {
                busy = false
                error = "Your session has expired. Sign in again."
                return@launch
            }
            try {
                Api.confirmRecoveryEmail(session.token, tokenValue, code)
                busy = false
                verificationToken = null
                confirmed = true
            } catch (e: ApiException) {
                busy = false
                error = e.message ?: "That code is not correct."
                if (e.status == 410) code = ""
            } catch (_: Exception) {
                busy = false
                error = "Network error. Please try again."
            }
        }
    }

    when {
        confirmed -> AccountScreen(
            onBack = null,
            illustration = "account-pending",
            footer = {
                GradientButton("Continue", onClick = onDone)
                Spacer(Modifier.height(4.dp))
            },
        ) {
            StatusChip("Recovery email confirmed", ChipTone.Success)
            Spacer(Modifier.height(16.dp))
            Text(
                "You're covered",
                fontSize = 28.sp,
                lineHeight = 34.sp,
                color = palette.textPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "If you ever forget your password, we'll send a reset link to ${email.trim().lowercase()}.",
                fontSize = 15.sp,
                lineHeight = 23.sp,
                color = palette.textSecondary,
            )
        }

        verificationToken != null -> AccountScreen(
            onBack = { verificationToken = null; code = ""; error = null; notice = null },
            illustration = "account-recovery-code",
            eyebrow = "Verify recovery email",
            title = "Enter the 6-digit code",
            body = "We sent a code to ${maskedEmail ?: "your email"}. Enter it to confirm this address.",
            footer = {
                GradientButton(
                    "Confirm",
                    onClick = { confirm() },
                    enabled = code.length == 6,
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
                Spacer(Modifier.height(14.dp))
                OrDivider()
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink(if (busy) "Sending…" else "Resend code", onClick = { sendCode(again = true) })
                }
                if (notice != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(notice ?: "", fontSize = 12.sp, color = palette.success)
                }
                Spacer(Modifier.height(12.dp))
                FooterNote(text = "Didn't get it? Check your spam folder.", divider = false)
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
                CodeExpiry(expiresAt = expiresAt, onExpired = { })
            }
        }

        else -> AccountScreen(
            onBack = onBack,
            illustration = "account-forgot",
            eyebrow = "Recovery email",
            title = "Add a recovery email",
            body = "If you ever forget your password, we'll send a reset link here. Use an address outside GrapMail.",
            footer = {
                GradientButton(
                    "Send code",
                    onClick = { sendCode(again = false) },
                    enabled = looksLikeEmail,
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
                if (onSkip != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        AccountLink("Skip for now", onClick = onSkip)
                    }
                    Spacer(Modifier.height(8.dp))
                    FooterNote(
                        text = "You can add it later in Settings → Account Security.",
                        divider = false,
                    )
                }
                Spacer(Modifier.height(4.dp))
            },
        ) {
            AppTextField(
                label = "Recovery email",
                value = email,
                onValueChange = { email = it.trim(); error = null },
                hint = "name@example.com",
                showLabel = false,
                keyboard = KeyboardType.Email,
                leading = {
                    Icon(Icons.Rounded.Mail, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(20.dp))
                },
            )
            Spacer(Modifier.height(16.dp))
            InfoPanel(
                title = "Why it matters",
                body = "GrapMail resets passwords by email only. Without a verified recovery email, a forgotten password can lock you out.",
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
