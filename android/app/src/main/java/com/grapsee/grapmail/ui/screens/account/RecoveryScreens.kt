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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.findActivity
import com.grapsee.grapmail.logic.AccountFlowViewModel
import com.grapsee.grapmail.security.PasswordCredentials
import com.grapsee.grapmail.ui.components.AccountLink
import com.grapsee.grapmail.ui.components.AccountScreen
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.ChipTone
import com.grapsee.grapmail.ui.components.CodeCells
import com.grapsee.grapmail.ui.components.CodeExpiry
import com.grapsee.grapmail.ui.components.FooterNote
import com.grapsee.grapmail.ui.components.GMAutofill
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.InfoPanel
import com.grapsee.grapmail.ui.components.OrDivider
import com.grapsee.grapmail.ui.components.RuleLine
import com.grapsee.grapmail.ui.components.StatusChip
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R

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
    /** The trusted-device path's exit: fires once the server has accepted this
     *  device's signature and the flow holds a recovery grant. */
    onTrustedDeviceVerified: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by flow.state.collectAsStateWithLifecycle()

    var identifier by rememberSaveable { mutableStateOf(state.identifier) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var trustedBusy by remember { mutableStateOf(false) }
    // The option exists only when this installation actually holds a device
    // key — an offer whose next step cannot work is a dead end, not a feature.
    // The probe is keystore IO, so it happens off the first frame.
    var hasDeviceKey by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        hasDeviceKey = com.grapsee.grapmail.security.DeviceKeyManager.hasKey()
    }

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
                        resetChannel = resp.channel,
                        code = "",
                        recoveryGrant = null,
                    )
                }
                // No challenge means no code went out. GrapMail recovers by
                // email, so this is normally the reset link going to the
                // verified recovery address (channel "email"); the pending
                // screen says which.
                if (resp.challengeId != null) onCodeSent() else onNeedsManualReview()
            } catch (e: ApiException) {
                busy = false
                error = e.message ?: context.getString(R.string.acct_we_could_not_start_the_reset_try)
            } catch (_: Exception) {
                busy = false
                error = context.getString(R.string.acct_network_error)
            }
        }
    }

    /**
     * The trusted-device path: no texted code, no second factor — the device
     * itself is the second factor. The server issues a nonce, the keystore
     * key signs the DECODED nonce bytes (signing the base64 text would sign a
     * different message and the verify would fail invisibly), and the grant
     * that comes back is spent by the password screen's reset-with-grant.
     *
     * The signature is biometric-gated: [DeviceKeyManager.signWithBiometricPrompt]
     * shows the platform's prompt — "Confirm to sign the recovery challenge" —
     * and only the Signature its result releases can complete the operation,
     * so proving this device is something the person did, not something the
     * app did. A device without strong biometrics holds a key with no gate
     * (declared on the devices screen) and signs directly.
     */
    fun useTrustedDevice() {
        // AccountLink has no enabled flag, so the double-tap guard lives here:
        // two challenges signed in one instant would spend the first nonce and
        // strand the second verify.
        if (trustedBusy) return
        if (identifier.isBlank()) {
            error = context.getString(R.string.acct_enter_your_grapmail_address_first)
            return
        }
        // Both ceremonies need a FragmentActivity; MainActivity is one, and a
        // host that is not fails visibly rather than fatally.
        val host = context.findActivity() as? androidx.fragment.app.FragmentActivity
        if (host == null) {
            error = context.getString(R.string.acct_this_device_cannot_confirm_the)
            return
        }
        trustedBusy = true; error = null
        scope.launch {
            try {
                val address = identifier.trim()
                val challenge = Api.deviceChallenge(address)
                val challengeId = challenge.challengeId
                val nonceB64 = challenge.nonce
                if (challengeId == null || nonceB64 == null) {
                    throw IllegalStateException("This device cannot prove this account right now.")
                }
                val nonceBytes = android.util.Base64.decode(nonceB64, android.util.Base64.NO_WRAP)
                // The element type cannot be inferred from a callback that only
                // returns Unit, so name it: the signer hands back String?.
                val signature = suspendCancellableCoroutine<String?> { continuation ->
                    com.grapsee.grapmail.security.DeviceKeyManager.signWithBiometricPrompt(
                        host,
                        nonceBytes,
                    ) { signed ->
                        // Leaving the screen while the prompt is up cancels this
                        // scope, and resuming a cancelled continuation would
                        // throw inside the prompt's own callback.
                        if (continuation.isActive) continuation.resume(signed)
                    }
                } ?: throw IllegalStateException("The device key could not sign the challenge.")
                val verified = Api.deviceVerify(
                    address, challengeId, nonceB64,
                    com.grapsee.grapmail.data.DeviceId.value, signature,
                )
                val grant = verified.recoveryGrant
                if (grant.isNullOrBlank()) {
                    throw IllegalStateException("This device is not trusted for this account.")
                }
                flow.update {
                    copy(
                        identifier = address,
                        recoveryGrant = grant,
                        // The texted-code path's fields are dead weight here —
                        // clearing them keeps the password screen honest about
                        // which proof it is spending.
                        challengeId = null,
                        code = "",
                    )
                }
                trustedBusy = false
                onTrustedDeviceVerified()
            } catch (e: ApiException) {
                trustedBusy = false
                error = e.message ?: context.getString(R.string.acct_this_device_could_not_prove_itself)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                trustedBusy = false
                error = context.getString(R.string.acct_this_device_could_not_prove_itself)
            }
        }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "account-forgot",
        eyebrow = stringResource(R.string.acct_forgot_password),
        title = stringResource(R.string.acct_reset_your_password),
        body = stringResource(R.string.acct_enter_your_grapmail_address_and_well),
        footer = {
            GradientButton(
                stringResource(R.string.acct_continue),
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
            if (hasDeviceKey) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink(
                        if (trustedBusy) stringResource(R.string.acct_proving_this_device) else stringResource(R.string.acct_use_a_trusted_device_instead),
                        onClick = { useTrustedDevice() },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                AccountLink(stringResource(R.string.acct_find_your_grapmail_address), onClick = onFindAddress)
            }
            Spacer(Modifier.height(14.dp))
            FooterNote(
                text = stringResource(R.string.acct_need_help_contact_support),
                linkText = stringResource(R.string.acct_support_team),
                onLink = onSupport,
            )
            Spacer(Modifier.height(4.dp))
        },
    ) {
        AppTextField(
            label = stringResource(R.string.acct_grapmail_address),
            value = identifier,
            onValueChange = { identifier = it; error = null },
            hint = stringResource(R.string.acct_grapmail_address),
            showLabel = false,
            keyboard = KeyboardType.Email,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { if (!busy) submit() }),
            autofill = GMAutofill.Username,
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
    val context = LocalContext.current
    val state by flow.state.collectAsStateWithLifecycle()

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
                    notice = context.getString(R.string.acct_a_new_code_is_on_its_way)
                } else {
                    error = context.getString(R.string.acct_we_couldnt_send_another_code_contact)
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
        illustration = "account-recovery-code",
        eyebrow = stringResource(R.string.acct_verify_recovery_code),
        title = stringResource(R.string.acct_enter_the_6_digit_code),
        body = stringResource(R.string.acct_weve_sent_a_6_digit_verification),
        footer = {
            GradientButton(
                stringResource(R.string.acct_continue),
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
                AccountLink(if (resending) stringResource(R.string.acct_sending) else stringResource(R.string.acct_resend_code), onClick = { resend() })
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
            FooterNote(text = stringResource(R.string.acct_didnt_receive_the_code_check_your), divider = false)
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
            autofill = GMAutofill.OneTimeCode,
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
    val context = LocalContext.current
    val state by flow.state.collectAsStateWithLifecycle()

    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Set by the server's VERIFY_FAILED, so the "enter the code again" link does not
    // depend on the English wording of the error.
    var codeRejected by rememberSaveable { mutableStateOf(false) }

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
        val grant = state.recoveryGrant
        val challenge = state.challengeId
        // Two proofs, two endpoints, one screen: the trusted-device grant and
        // the texted code both buy the same password change. The grant path
        // needs no challenge id and no code — the device already proved it.
        if (grant.isNullOrBlank() && challenge == null) {
            error = context.getString(R.string.acct_that_reset_link_expired_start_again)
            return
        }
        busy = true; error = null; codeRejected = false
        scope.launch {
            try {
                // Captured before the call: the field may not change under a
                // request, but what the server accepted is what gets saved.
                val newPassword = password
                if (!grant.isNullOrBlank()) {
                    Api.resetWithGrant(state.identifier, grant, newPassword)
                    busy = false
                    // The grant response carries no address of its own — the
                    // identifier this flow has carried since forgot-password
                    // is the one the grant was minted for.
                    PasswordCredentials.offerToSave(context, state.identifier, newPassword)
                    onReset(state.identifier)
                } else {
                    val resp = Api.resetPassword(challenge!!, state.code, newPassword)
                    busy = false
                    // The server took the new password: offer it to the
                    // password manager (fire-and-forget, never in the way).
                    PasswordCredentials.offerToSave(context, resp.address ?: state.identifier, newPassword)
                    onReset(resp.address)
                }
            } catch (e: ApiException) {
                busy = false
                error = when (e.code) {
                    // The server names the exact rule that failed. Showing the
                    // real message beats a hardcoded "at least 8 characters", which
                    // was wrong for every other rule the policy now enforces.
                    "WEAK_PASSWORD" -> e.message ?: context.getString(R.string.acct_use_at_least_8_characters_with_a)
                    "VERIFY_FAILED" -> {
                        codeRejected = true
                        context.getString(R.string.acct_code_not_correct_or_expired)
                    }
                    "INVALID_GRANT", "GRANT_EXPIRED", "GRANT_USED" ->
                        context.getString(R.string.acct_this_devices_proof_expired_start)
                    else -> e.message ?: context.getString(R.string.acct_we_could_not_reset_the_password_try)
                }
            } catch (_: Exception) {
                busy = false
                error = context.getString(R.string.acct_network_error)
            }
        }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "account-new-password",
        eyebrow = stringResource(R.string.acct_create_new_password),
        title = stringResource(R.string.acct_set_a_new_password),
        body = stringResource(R.string.acct_your_new_password_must_be_strong_and),
        footer = {
            GradientButton(
                stringResource(R.string.acct_reset_password),
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
                if (codeRejected || error?.contains("code") == true) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        AccountLink(stringResource(R.string.acct_enter_the_code_again), onClick = onBackToCode)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            FooterNote(text = stringResource(R.string.acct_your_password_will_be_encrypted_and), divider = false)
            Spacer(Modifier.height(4.dp))
        },
    ) {
        AppTextField(
            label = stringResource(R.string.acct_new_password),
            value = password,
            onValueChange = { password = it; error = null },
            hint = stringResource(R.string.acct_new_password),
            showLabel = false,
            password = true,
            imeAction = ImeAction.Next,
            autofill = GMAutofill.NewPassword,
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
        Text(stringResource(R.string.acct_password_strength), fontSize = 12.sp, color = palette.textTertiary)

        Spacer(Modifier.height(18.dp))
        AppTextField(
            label = stringResource(R.string.acct_confirm_new_password),
            value = confirm,
            onValueChange = { confirm = it },
            hint = stringResource(R.string.acct_confirm_new_password),
            showLabel = false,
            password = true,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { if (ready && !busy) submit() }),
            autofill = GMAutofill.NewPassword,
            leading = {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(20.dp))
            },
        )
        Spacer(Modifier.height(16.dp))
        RuleLine(stringResource(R.string.acct_at_least_8_characters), hasLength)
        RuleLine(stringResource(R.string.acct_include_a_letter), hasLetter)
        RuleLine(stringResource(R.string.acct_include_a_number), hasDigit)
        RuleLine(stringResource(R.string.acct_include_a_special_character), hasSpecial)
        RuleLine(stringResource(R.string.acct_not_commonly_used), isFresh)
        if (confirm.isNotEmpty() && !matches) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.acct_both_passwords_must_match_period), fontSize = 13.sp, color = palette.error)
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
                stringResource(R.string.acct_back_to_sign_in),
                onClick = onBackToSignIn,
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
            Spacer(Modifier.height(18.dp))
            FooterNote(
                text = stringResource(R.string.acct_for_your_security_we_recommend),
            )
            Spacer(Modifier.height(4.dp))
        },
    ) {
        StatusChip(stringResource(R.string.acct_password_reset_successful), ChipTone.Success)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.acct_your_password_has_been_reset),
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = palette.textPrimary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.acct_your_grapmail_account_is_now_secure),
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
    val context = LocalContext.current
    val state by flow.state.collectAsStateWithLifecycle()

    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun retry() {
        if (state.identifier.isBlank()) return
        busy = true; notice = null
        scope.launch {
            try {
                val resp = Api.forgotPassword(state.identifier)
                busy = false
                flow.update { copy(resetChannel = resp.channel) }
                notice = when {
                    resp.challengeId != null -> context.getString(R.string.acct_a_code_is_on_its_way_check_your)
                    resp.channel == "email" -> context.getString(R.string.acct_a_new_reset_link_is_on_its_way_check)
                    else -> context.getString(R.string.acct_still_in_review_well_message_you_as)
                }
            } catch (e: ApiException) {
                busy = false
                notice = e.message ?: context.getString(R.string.acct_we_couldnt_check_again_try_in_a)
            } catch (_: Exception) {
                busy = false
                notice = context.getString(R.string.acct_network_error)
            }
        }
    }

    AccountScreen(
        onBack = null,
        illustration = "account-pending",
        footer = {
            GradientButton(
                if (state.resetChannel == "email") stringResource(R.string.acct_send_again) else stringResource(R.string.acct_try_again_2),
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
                AccountLink(stringResource(R.string.acct_back_to_sign_in), onClick = onBackToSignIn)
            }
            Spacer(Modifier.height(14.dp))
            OrDivider()
            Spacer(Modifier.height(14.dp))
            FooterNote(
                text = stringResource(R.string.acct_need_help_contact_support),
                linkText = stringResource(R.string.acct_support_team),
                onLink = onSupport,
                divider = false,
            )
            Spacer(Modifier.height(4.dp))
        },
    ) {
        val emailed = state.resetChannel == "email"
        StatusChip(if (emailed) stringResource(R.string.acct_reset_link_sent) else stringResource(R.string.acct_account_recovery_pending), if (emailed) ChipTone.Success else ChipTone.Warning)
        Spacer(Modifier.height(16.dp))
        Text(
            if (emailed) stringResource(R.string.acct_check_your_recovery_email) else stringResource(R.string.acct_were_still_working_on_your_recovery),
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = palette.textPrimary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (emailed) stringResource(R.string.acct_we_sent_a_password_reset_link_to_the)
            else stringResource(R.string.acct_there_might_be_a_short_delay_while),
            fontSize = 15.sp,
            lineHeight = 23.sp,
            color = palette.textSecondary,
        )
        Spacer(Modifier.height(18.dp))
        InfoPanel(
            title = if (emailed) stringResource(R.string.acct_didnt_get_it) else stringResource(R.string.acct_whats_happening),
            body = if (emailed) stringResource(R.string.acct_the_link_works_for_1_hour_check_your)
            else stringResource(R.string.acct_were_verifying_your_recovery_request),
            icon = {
                Icon(Icons.Rounded.Schedule, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(18.dp))
            },
        )
        Spacer(Modifier.height(8.dp))
    }
}
