package com.grapsee.grapmail.ui.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.logic.AccountFlowViewModel
import com.grapsee.grapmail.ui.components.AccountLink
import com.grapsee.grapmail.ui.components.AccountScreen
import com.grapsee.grapmail.ui.components.CodeCells
import com.grapsee.grapmail.ui.components.CodeExpiry
import com.grapsee.grapmail.ui.components.GMAutofill
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.OrDivider
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R

/**
 * Two-step verification — founder design verbatim: shield artwork, the
 * TWO-STEP VERIFICATION eyebrow, six code cells, the expiry countdown, the
 * arrowed CTA, Resend code, OR, Back to sign in.
 *
 * The same screen carries the second challenge the gateway answers a login
 * with: an account created elsewhere with an unverified email gets a 403
 * naming that address, and finish-sign-in then goes through /verify-otp
 * instead of the factor challenge. Same cells, same buttons; the copy names
 * the address the code was emailed to, and the backup-code door is absent —
 * backup codes belong to the second factor, and this path's password is never
 * held in the flow for them to spend.
 *
 * "Use a backup code" is the one addition. The artwork puts a backup-code
 * screen in the same arc but draws no way into it, and a factor screen with no
 * alternative is a locked door when the phone is gone; the link sits under
 * Resend code in the same 13pt brand style.
 */
@Composable
fun TwoFactorScreen(
    flow: AccountFlowViewModel,
    onSignedIn: (address: String) -> Unit,
    onBack: () -> Unit,
    onBackToSignIn: () -> Unit,
    onUseBackupCode: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by flow.state.collectAsStateWithLifecycle()
    val emailChallenge = state.emailOtpAddress
    val backupOnly = emailChallenge == null && state.challengeId == null

    var code by remember { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resending by remember { mutableStateOf(false) }

    fun verify() {
        if (busy || resending || code.length != 6) return
        val submitted = state
        val submittedCode = code
        if (submitted.emailOtpAddress == null && submitted.challengeId == null) {
            error = context.getString(R.string.acct_sign_in_again_to_request_a_code)
            return
        }
        busy = true; error = null; notice = null
        scope.launch {
            try {
                val resp = if (submitted.emailOtpAddress != null) {
                    Api.verifyEmailOtp(submitted.emailOtpAddress, submittedCode)
                } else {
                    val factor = Api.twoFactorCode(submitted.challengeId!!, submittedCode)
                    com.grapsee.grapmail.data.SignInResponse(
                        sessionToken = factor.sessionToken, accessToken = factor.accessToken,
                        token = factor.token, refreshToken = factor.refreshToken,
                        expiresAt = factor.expiresAt, expiresIn = factor.expiresIn, user = factor.user)
                }
                val token = resp.sessionToken ?: resp.accessToken ?: resp.token
                if (resp.secondFactorRequired || token.isNullOrBlank()) {
                    error = context.getString(R.string.acct_verification_did_not_return_a)
                    return@launch
                }
                val address = resp.signedInAddress(submitted.identifier)
                SessionStore.save(context, token, resp.refreshToken, address,
                    resp.accountOrNull?.name ?: "", resp.expiresAtMillis)
                code = ""
                flow.clearSignIn()
                onSignedIn(address)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ApiException) {
                busy = false
                error = when (e.code) {
                    "CHALLENGE_EXPIRED" -> context.getString(R.string.acct_that_code_has_expired_resend_it_to)
                    "BAD_CODE" -> context.getString(R.string.acct_that_code_is_not_correct)
                    else -> e.message ?: context.getString(R.string.acct_that_code_is_not_correct)
                }
            } catch (_: Exception) {
                busy = false
                error = context.getString(R.string.acct_network_error)
            } finally {
                // Every exit (including the "no session returned" early return
                // above) must release the button, or it spins forever.
                busy = false
            }
        }
    }

    // Resending: on the email gate the address alone is the contract
    // (/resend-otp takes {email}); on the factor challenge it means signing in
    // again — the server issues a fresh challenge and texts a new code, and the
    // old one stops working. The credentials are still in the flow, in memory
    // only.
    fun resend() {
        if (emailChallenge != null) {
            if (emailChallenge.isBlank()) return
            resending = true; error = null; notice = null
            scope.launch {
                try {
                    val sent = Api.resendEmailOtp(emailChallenge)
                    resending = false
                    if (!sent.success) {
                        error = sent.error ?: context.getString(R.string.acct_could_not_resend_the_code_try_again)
                        return@launch
                    }
                    code = ""
                    notice = context.getString(R.string.acct_a_new_code_is_on_its_way)
                } catch (e: ApiException) {
                    resending = false
                    error = e.message ?: context.getString(R.string.acct_could_not_resend_the_code_try_again)
                } catch (_: Exception) {
                    resending = false
                    error = context.getString(R.string.acct_network_error)
                }
            }
            return
        }
        if (state.identifier.isBlank() || state.password.isBlank()) return
        resending = true; error = null; notice = null
        scope.launch {
            try {
                val resp = Api.signIn(state.identifier, state.password)
                resending = false
                if (resp.secondFactorRequired && resp.challengeId != null) {
                    code = ""
                    flow.update { copy(challengeId = resp.challengeId, expiresAt = resp.expiresAt ?: 0L, factorDestination = resp.maskedEmail ?: factorDestination) }
                    notice = context.getString(R.string.acct_a_new_code_is_on_its_way)
                } else {
                    error = context.getString(R.string.acct_that_sign_in_no_longer_needs_a_code)
                }
            } catch (e: ApiException) {
                resending = false
                error = e.message ?: context.getString(R.string.acct_could_not_resend_the_code_try_again)
            } catch (_: Exception) {
                resending = false
                error = context.getString(R.string.acct_network_error)
            }
        }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "account-2fa",
        eyebrow = if (emailChallenge != null) stringResource(R.string.acct_email_verification) else stringResource(R.string.acct_two_step_verification),
        title = if (emailChallenge != null) stringResource(R.string.acct_enter_the_code_sent_to, emailChallenge) else if (backupOnly) stringResource(R.string.acct_use_a_backup_code) else stringResource(R.string.acct_enter_your_6_digit_code),
        body = if (emailChallenge != null)
            stringResource(R.string.acct_finish_signing_in_by_entering_the_6)
        else if (backupOnly)
            stringResource(R.string.acct_a_verification_text_is_unavailable)
        else
            state.factorDestination?.let { stringResource(R.string.acct_weve_emailed_code_to, it) }
                ?: stringResource(R.string.acct_weve_sent_you_a_verification_code),
        footer = {
            GradientButton(
                stringResource(R.string.acct_verify_and_continue),
                onClick = { verify() },
                enabled = !backupOnly && code.length == 6,
                busy = busy,
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                AccountLink(if (resending) stringResource(R.string.acct_sending) else stringResource(R.string.acct_resend_code), onClick = { resend() })
            }
            if (emailChallenge == null) {
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink(stringResource(R.string.acct_use_a_backup_code), onClick = onUseBackupCode)
                }
            }
            Spacer(Modifier.height(12.dp))
            OrDivider()
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                AccountLink(stringResource(R.string.acct_back_to_sign_in_lower), onClick = onBackToSignIn)
            }
            Spacer(Modifier.height(6.dp))
        },
    ) {
        CodeCells(
            length = 6,
            value = code,
            onValueChange = {
                code = it.filter(Char::isDigit).take(6)
                error = null
            },
            autofill = GMAutofill.OneTimeCode,
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CodeExpiry(expiresAt = state.expiresAt, onExpired = { })
        }
        if (notice != null) {
            Spacer(Modifier.height(10.dp))
            Text(notice ?: "", fontSize = 13.sp, color = palette.success, modifier = Modifier.fillMaxWidth())
        }
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error ?: "", fontSize = 13.sp, color = palette.error, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Backup code — founder design language, with the one deliberate correction:
 * GrapMail issues ten-character codes ("ABCDE-FGHIJ"), so the row is two groups
 * of five cells and the note says ten. The artwork drew six cells and said
 * eight, which cannot accept a real code once; a screen that cannot work is
 * worse than a screen that differs by four cells.
 */
@Composable
fun BackupCodeScreen(
    flow: AccountFlowViewModel,
    onSignedIn: (address: String) -> Unit,
    onBack: () -> Unit,
    onTryAnotherMethod: () -> Unit,
    onBackToSignIn: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by flow.state.collectAsStateWithLifecycle()

    var code by remember { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (busy) return
        if (state.identifier.isBlank() || state.password.isBlank()) {
            error = context.getString(R.string.acct_sign_in_again_to_use_a_backup_code)
            return
        }
        busy = true; error = null
        scope.launch {
            try {
                // The dash is part of the stored code; the cells show only the
                // ten characters, so it is put back where GrapMail puts it.
                val typed = code.take(10)
                val normalized = if (typed.length == 10) "${typed.take(5)}-${typed.drop(5)}" else typed
                val resp = Api.twoFactorBackup(state.identifier, state.password, normalized)
                busy = false
                val token = resp.sessionToken ?: resp.accessToken ?: resp.token
                if (token.isNullOrBlank()) {
                    error = context.getString(R.string.acct_backup_code_invalid_or_used)
                    return@launch
                }
                // The same address rule as every other door (SignInResponse.signedInAddress):
                // the account's address, never a blank one, else what was typed.
                val address = resp.user?.let { u ->
                    u.address.takeIf(String::isNotBlank)
                        ?: u.grapmailAddress?.takeIf(String::isNotBlank)
                        ?: u.email?.takeIf(String::isNotBlank)
                } ?: state.identifier
                SessionStore.save(
                    context, token, resp.refreshToken,
                    address,
                    resp.user?.name ?: "",
                    resp.expiresAtMillis,
                )
                code = ""
                // The password was held in the flow only to spend this code; it
                // must not outlive the sign-in.
                flow.clearSignIn()
                onSignedIn(address)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ApiException) {
                busy = false
                error = when (e.code) {
                    "BAD_BACKUP_CODE" -> context.getString(R.string.acct_backup_code_invalid_or_used)
                    "TWO_FACTOR_NOT_ENABLED" -> context.getString(R.string.acct_this_account_does_not_use_two_step)
                    else -> e.message ?: context.getString(R.string.acct_that_backup_code_is_not_valid)
                }
            } catch (_: Exception) {
                busy = false
                error = context.getString(R.string.acct_network_error)
            }
        }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "account-backup",
        eyebrow = stringResource(R.string.acct_use_a_backup_code),
        title = stringResource(R.string.acct_enter_your_backup_code),
        body = stringResource(R.string.acct_if_you_dont_have_access_to_your_2),
        footer = {
            GradientButton(
                stringResource(R.string.acct_continue),
                onClick = { submit() },
                enabled = code.length == 10,
                busy = busy,
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                AccountLink(stringResource(R.string.acct_try_another_method), onClick = onTryAnotherMethod)
            }
            Spacer(Modifier.height(12.dp))
            OrDivider()
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                AccountLink(stringResource(R.string.acct_back_to_sign_in_lower), onClick = onBackToSignIn)
            }
            Spacer(Modifier.height(6.dp))
        },
    ) {
        // Wraps to two rows of five ("ABCDE" over "FGHIJ") when ten cells
        // cannot hold legible glyphs: 320dp phones, 200% font, cover screens.
        CodeCells(
            length = 10,
            groupGap = true,
            alphanumeric = true,
            value = code,
            onValueChange = {
                code = it.filter(Char::isLetterOrDigit).uppercase().take(10)
                error = null
            },
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = null,
                tint = palette.textTertiary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(R.string.acct_backup_codes_are_10_characters_long),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = palette.textSecondary,
            )
        }
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error ?: "", fontSize = 13.sp, color = palette.error)
        }
    }
}
