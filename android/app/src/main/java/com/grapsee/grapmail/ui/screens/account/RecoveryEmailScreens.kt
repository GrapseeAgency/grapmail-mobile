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
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R

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
                error = context.getString(R.string.acct_your_session_has_expired_sign_in)
                return@launch
            }
            try {
                val resp = Api.startRecoveryEmail(session.token, address)
                busy = false
                if (resp.token == null) {
                    error = resp.error ?: context.getString(R.string.acct_we_couldnt_send_the_code_try_again)
                    return@launch
                }
                verificationToken = resp.token
                maskedEmail = resp.maskedEmail ?: address
                expiresAt = resp.expiresAt ?: 0L
                code = ""
                if (again) notice = context.getString(R.string.acct_a_new_code_is_on_its_way)
            } catch (e: ApiException) {
                busy = false
                error = e.message ?: context.getString(R.string.acct_we_couldnt_send_the_code_try_again)
            } catch (_: Exception) {
                busy = false
                error = context.getString(R.string.acct_network_error)
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
                error = context.getString(R.string.acct_your_session_has_expired_sign_in)
                return@launch
            }
            try {
                Api.confirmRecoveryEmail(session.token, tokenValue, code)
                busy = false
                verificationToken = null
                confirmed = true
            } catch (e: ApiException) {
                busy = false
                error = e.message ?: context.getString(R.string.acct_that_code_is_not_correct)
                if (e.status == 410) code = ""
            } catch (_: Exception) {
                busy = false
                error = context.getString(R.string.acct_network_error)
            }
        }
    }

    when {
        confirmed -> AccountScreen(
            onBack = null,
            illustration = "account-pending",
            footer = {
                GradientButton(stringResource(R.string.acct_continue), onClick = onDone)
                Spacer(Modifier.height(4.dp))
            },
        ) {
            StatusChip(stringResource(R.string.acct_recovery_email_confirmed), ChipTone.Success)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.acct_youre_covered),
                fontSize = 28.sp,
                lineHeight = 34.sp,
                color = palette.textPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.acct_recovery_email_covered_body, email.trim().lowercase()),
                fontSize = 15.sp,
                lineHeight = 23.sp,
                color = palette.textSecondary,
            )
        }

        verificationToken != null -> AccountScreen(
            onBack = { verificationToken = null; code = ""; error = null; notice = null },
            illustration = "account-recovery-code",
            eyebrow = stringResource(R.string.acct_verify_recovery_email),
            title = stringResource(R.string.acct_enter_the_6_digit_code),
            body = maskedEmail?.let { stringResource(R.string.acct_recovery_email_code_sent_to, it) }
                ?: stringResource(R.string.acct_recovery_email_code_sent_generic),
            footer = {
                GradientButton(
                    stringResource(R.string.acct_confirm),
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
                    AccountLink(if (busy) stringResource(R.string.acct_sending) else stringResource(R.string.acct_resend_code), onClick = { sendCode(again = true) })
                }
                if (notice != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(notice ?: "", fontSize = 12.sp, color = palette.success)
                }
                Spacer(Modifier.height(12.dp))
                FooterNote(text = stringResource(R.string.acct_didnt_get_it_check_your_spam_folder), divider = false)
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
                autofill = com.grapsee.grapmail.ui.components.GMAutofill.OneTimeCode,
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CodeExpiry(expiresAt = expiresAt, onExpired = { })
            }
        }

        else -> AccountScreen(
            onBack = onBack,
            illustration = "account-forgot",
            eyebrow = stringResource(R.string.acct_recovery_email),
            title = stringResource(R.string.acct_add_a_recovery_email),
            body = stringResource(R.string.acct_if_you_ever_forget_your_password),
            footer = {
                GradientButton(
                    stringResource(R.string.acct_send_code),
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
                        AccountLink(stringResource(R.string.acct_skip_for_now), onClick = onSkip)
                    }
                    Spacer(Modifier.height(8.dp))
                    FooterNote(
                        text = stringResource(R.string.acct_you_can_add_it_later_in_settings),
                        divider = false,
                    )
                }
                Spacer(Modifier.height(4.dp))
            },
        ) {
            AppTextField(
                label = stringResource(R.string.acct_recovery_email),
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
                title = stringResource(R.string.acct_why_it_matters),
                body = stringResource(R.string.acct_grapmail_resets_passwords_by_email),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
