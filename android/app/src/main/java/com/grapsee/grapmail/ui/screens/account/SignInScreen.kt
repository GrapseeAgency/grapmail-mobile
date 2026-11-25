package com.grapsee.grapmail.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.RememberedAccounts
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.findActivity
import com.grapsee.grapmail.logic.AccountFlowViewModel
import com.grapsee.grapmail.security.PasskeyManager
import com.grapsee.grapmail.security.PasswordCredentials
import com.grapsee.grapmail.ui.components.AccountHeader
import com.grapsee.grapmail.ui.components.AccountLink
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.DecorativeBlobs
import com.grapsee.grapmail.ui.components.GMAutofill
import com.grapsee.grapmail.ui.components.GMLayout
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.gmIsNarrowWindow
import com.grapsee.grapmail.ui.components.gmMaxWidth
import com.grapsee.grapmail.ui.components.OrDivider
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R

    /**
     * Sign in — founder design verbatim: wordmark header, "Welcome back 👋", the
     * bordered card holding both fields, the reset link, the gradient CTA, OR, and
     * the create-account line.
     *
     * The second factor is its own designed screen now, so a challenged sign-in
     * hands the challenge to [onNeedSecondFactor] instead of unfolding in place.
     */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignInScreen(
    flow: AccountFlowViewModel,
    onSignedIn: (address: String) -> Unit,
    onBack: (() -> Unit)? = null,
    onSignUp: () -> Unit,
    onForgotPassword: () -> Unit,
    onNeedSecondFactor: () -> Unit,
    /**
     * Round 8 — B: the welcome-back door (round8-spec §2.1). When set and a
     * remembered, signed-out account exists, this screen hands over to it
     * before drawing the form. Null (the default) keeps the plain form.
     */
    onRememberedAccount: ((RememberedAccounts.Entry) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flowState by flow.state.collectAsStateWithLifecycle()

    var identifier by rememberSaveable { mutableStateOf(flowState.prefillIdentifier ?: "") }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        flow.clearSignIn()
        // A password held for an abandoned second step is not this visit's.
        PasswordCredentials.clearHeld()
    }
    // Round 8 — B: a sentence handed over by welcome-back (a blocked or
    // deleted account's server message) shows once, as this visit's error.
    val handedNotice = remember { flowState.signInNotice }
    var error by rememberSaveable { mutableStateOf(handedNotice) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (handedNotice != null) flow.update { copy(signInNotice = null) }
    }

    // Round 8 — B: the welcome-back entry. Decided once per visit, before the
    // form is drawn, so a remembered account never sees the empty form flash
    // first. A flow handing the person back (prefill, notice) keeps the form.
    var entryResolved by rememberSaveable { mutableStateOf(onRememberedAccount == null) }
    LaunchedEffect(Unit) {
        if (entryResolved) return@LaunchedEffect
        val open = onRememberedAccount
        val handedBack = flowState.prefillIdentifier != null || handedNotice != null
        val entry = if (open == null || handedBack) {
            RememberedAccounts.Entry.None
        } else {
            try {
                RememberedAccounts.entry(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                RememberedAccounts.Entry.None
            }
        }
        if (open != null && entry != RememberedAccounts.Entry.None) {
            open(entry)
        } else {
            entryResolved = true
        }
    }

    // A finished reset or a found address hands the person back here with the
    // address already in the field.
    LaunchedEffect(flowState.prefillIdentifier) {
        flowState.prefillIdentifier?.let { identifier = it }
    }

    /**
     * [fromSavedLogin] marks a pair the password manager just handed over:
     * it is already saved, so no "Save password?" sheet is offered for it.
     */
    fun submit(fromSavedLogin: Boolean = false) {
        if (busy || identifier.isBlank() || password.isBlank()) return
        val submittedIdentifier = identifier.trim()
        val submittedPassword = password
        busy = true; error = null
        scope.launch {
            try {
                when (val outcome = completePasswordSignIn(context, flow, submittedIdentifier, submittedPassword, fromSavedLogin)) {
                    PasswordSignInOutcome.SecondFactor -> {
                        password = ""
                        onNeedSecondFactor()
                    }
                    PasswordSignInOutcome.NoSession -> {
                        error = context.getString(R.string.acct_signin_no_session)
                        return@launch
                    }
                    is PasswordSignInOutcome.SignedIn -> {
                        password = ""
                        onSignedIn(outcome.address)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ApiException) {
                busy = false
                // round8-spec §2.4 rule 4: a blocked / deleted account answer (the
                // gateway's 403 with no code) clears this device's remembered
                // record for it, exactly as the welcome-back door does.
                if (e.status == 403 && e.code == null) {
                    runCatching { RememberedAccounts.forget(context, submittedIdentifier) }
                }
                error = when {
                    e.code == "TOO_MANY_ATTEMPTS" || e.status == 429 ->
                        context.getString(R.string.acct_too_many_failed_attempts_try_again)
                    else -> e.message ?: context.getString(R.string.acct_those_details_do_not_match_an)
                }
            } catch (_: Exception) {
                busy = false
                error = context.getString(R.string.acct_network_error)
            } finally {
                busy = false
            }
        }
    }

    /**
     * What a passkey ceremony ended in, for both doors that run one: the
     * "Use a passkey" link and a passkey picked from the saved-login sheet.
     */
    suspend fun finishPasskey(result: PasskeyManager.SignInResult) {
        when (result) {
            is PasskeyManager.SignInResult.Completed -> {
                when (val outcome = storePasskeySession(context, flow, result.response)) {
                    PasskeySessionOutcome.NoSession ->
                        error = context.getString(R.string.acct_signin_no_session)
                    PasskeySessionOutcome.NoAccount ->
                        error = context.getString(R.string.acct_sign_in_succeeded_but_no_account)
                    is PasskeySessionOutcome.SignedIn -> {
                        password = ""
                        onSignedIn(outcome.address)
                    }
                }
            }
            PasskeyManager.SignInResult.NoCredential ->
                error = context.getString(R.string.acct_no_passkey_for_this_account_is_on)
            PasskeyManager.SignInResult.Cancelled -> {
                // The person closed the sheet; nothing to report.
            }
            is PasskeyManager.SignInResult.Failed ->
                error = result.message ?: context.getString(R.string.acct_the_passkey_could_not_be_used_sign)
        }
    }

    /**
     * The passkey door. Discoverable credentials carry no username, so this
     * needs nothing from the fields above. On success the response is the
     * same session envelope a password sign-in returns, so it is stored
     * through the very same [SessionStore.save] path and lands on the same
     * [onSignedIn] the password door uses — the two doors share everything
     * after the server has said yes.
     */
    fun usePasskey() {
        if (busy) return
        // The ceremony draws a system sheet, which needs the FragmentActivity;
        // MainActivity is one, and anything else fails visibly, not fatally.
        val host = context.findActivity() as? androidx.fragment.app.FragmentActivity
        if (host == null) {
            error = context.getString(R.string.acct_passkeys_need_app_update)
            return
        }
        busy = true; error = null
        scope.launch {
            try {
                finishPasskey(PasskeyManager.signInWithPasskey(host))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                error = context.getString(R.string.acct_network_error)
            } finally {
                busy = false
            }
        }
    }

    /**
     * The saved-login offer: when this screen opens with both fields empty,
     * Credential Manager is asked once for a saved GrapMail password (and a
     * passkey, when the server hands out options). A picked password fills
     * both fields and signs in through [submit] — the same door, so the
     * second step, the session, onSignedIn and the trusted-device enrollment
     * behind it are exactly the typed path's. Nothing saved, or a cancel,
     * shows nothing; the flag is saveable, so neither a rotation nor a return
     * from the code screen asks again in the same visit.
     */
    var savedLoginAsked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(entryResolved) {
        // Round 8 — B: not while this visit may still hand over to welcome-back.
        if (!entryResolved) return@LaunchedEffect
        if (savedLoginAsked) return@LaunchedEffect
        savedLoginAsked = true
        if (identifier.isNotEmpty() || password.isNotEmpty() || flowState.prefillIdentifier != null) return@LaunchedEffect
        val host = context.findActivity() ?: return@LaunchedEffect
        // Never over a closed app-lock door (its own prompt owns the screen);
        // returns at once when the lock is off.
        com.grapsee.grapmail.logic.AppLock.awaitOpen()
        val untouched = { identifier.isEmpty() && password.isEmpty() && !busy }
        when (val picked = PasswordCredentials.requestSavedLogin(host, untouched)) {
            is PasswordCredentials.SavedLogin.Password -> {
                if (busy) return@LaunchedEffect
                identifier = picked.id
                password = picked.password
                error = null
                submit(fromSavedLogin = true)
            }
            is PasswordCredentials.SavedLogin.Passkey -> {
                if (busy) return@LaunchedEffect
                busy = true; error = null
                try {
                    finishPasskey(picked.result)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = context.getString(R.string.acct_network_error)
                } finally {
                    busy = false
                }
            }
            PasswordCredentials.SavedLogin.None -> Unit
        }
    }

    // Round 8 — B: the canvas alone while the welcome-back entry is decided
    // (one local DataStore read), so the form never flashes before a hand-over.
    if (!entryResolved) {
        Box(Modifier.fillMaxSize().background(palette.bg))
        return
    }

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        Box(Modifier.align(Alignment.TopEnd)) { DecorativeBlobs() }
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (gmIsNarrowWindow()) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // One centred form column on tablets, foldables, landscape and
            // desktop windows; phones in portrait are narrower than the cap.
            Column(Modifier.gmMaxWidth(GMLayout.Form)) {
                AccountHeader(onBack)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.acct_welcome_back),
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    color = palette.textPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.acct_sign_in_to_your_grapmail_account_and),
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    color = palette.textSecondary,
                )
                Spacer(Modifier.height(26.dp))

                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, palette.border, RoundedCornerShape(20.dp))
                        .background(palette.card, RoundedCornerShape(20.dp))
                        .padding(16.dp),
                ) {
                    AppTextField(
                        label = stringResource(R.string.acct_email_address_or_phone_number),
                        value = identifier,
                        onValueChange = { identifier = it },
                        hint = stringResource(R.string.acct_you_grapsee_com_or_8801xxxxxxxx),
                        keyboard = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                        autofill = GMAutofill.Username,
                        leading = {
                            Icon(
                                Icons.Rounded.Mail,
                                contentDescription = null,
                                tint = palette.textTertiary,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    AppTextField(
                        label = stringResource(R.string.acct_password),
                        value = password,
                        onValueChange = { password = it },
                        hint = stringResource(R.string.acct_enter_your_password),
                        password = true,
                        imeAction = ImeAction.Done,
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        autofill = GMAutofill.Password,
                        leading = {
                            Icon(
                                Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = palette.textTertiary,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(Modifier.padding(top = 6.dp)) {
                        AccountLink(stringResource(R.string.acct_forgot_password_2), onClick = onForgotPassword)
                    }
                }

                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error ?: "", fontSize = 13.sp, color = palette.error)
                }

                Spacer(Modifier.height(18.dp))
                GradientButton(
                    stringResource(R.string.acct_sign_in),
                    onClick = { submit() },
                    enabled = identifier.isNotBlank() && password.isNotBlank(),
                    busy = busy,
                    trailing = {
                        Spacer(Modifier.size(10.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                )
                Spacer(Modifier.height(22.dp))
                OrDivider()
                Spacer(Modifier.height(14.dp))
                // The passkey door stays visible whatever the fields hold: a
                // discoverable credential needs neither of them, and a person
                // with a passkey should never have to wonder where it is used.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink(stringResource(R.string.acct_use_a_passkey), onClick = { usePasskey() })
                }
                Spacer(Modifier.height(10.dp))
                // Wraps onto two centred lines at large font sizes instead of
                // squeezing "Create one" against the edge.
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(R.string.acct_dont_have_an_account),
                        fontSize = 13.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    AccountLink(
                        stringResource(R.string.acct_create_one),
                        onClick = onSignUp,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}


// ── Round 8 — B: the sign-in completion every door shares ─────────────────
//
// The typed form above and the welcome-back screen finish a sign-in through
// these two functions, so the second step, SessionStore.save (and with it the
// remembered-account record), the password-manager offer and the hand-off to
// onSignedIn → MainActivity.signedIn (trusted-device enrollment, push, the
// held-password save) are one path, not two copies that can drift.

/** How a password sign-in the server answered went. Errors are thrown as before. */
internal sealed class PasswordSignInOutcome {
    data class SignedIn(val address: String) : PasswordSignInOutcome()

    /** A code is owed; the challenge is in [AccountFlowViewModel]. Navigate to the factor screen. */
    object SecondFactor : PasswordSignInOutcome()

    /** The server said yes but returned no session. */
    object NoSession : PasswordSignInOutcome()
}

/**
 * Sign in with [identifier] + [password]. On a challenge the flow state is
 * filled exactly as the form always did and the password is held for the
 * post-second-step save; on success the session is stored, the flow's
 * secrets cleared and (unless the pair came from the password manager,
 * [fromSavedLogin]) the "Save password?" sheet offered. Throws
 * [ApiException] / IO errors unchanged for the caller's own wording.
 */
internal suspend fun completePasswordSignIn(
    context: android.content.Context,
    flow: AccountFlowViewModel,
    identifier: String,
    password: String,
    fromSavedLogin: Boolean,
): PasswordSignInOutcome {
    val resp = Api.signIn(identifier, password)
    if (resp.secondFactorRequired) {
        flow.update {
            copy(
                identifier = identifier,
                password = if (resp.emailOtpRequired) "" else password,
                emailOtpAddress = if (resp.emailOtpRequired) resp.email else null,
                challengeId = resp.challengeId,
                expiresAt = resp.expiresAt ?: 0L,
                factorDestination = resp.maskedEmail,
                prefillIdentifier = null,
            )
        }
        // The server accepted the password; the sign-in is not
        // complete until the second step is. Held in memory only,
        // offered for saving by the completed sign-in (signedIn).
        if (!fromSavedLogin) PasswordCredentials.holdUntilSignedIn(password)
        return PasswordSignInOutcome.SecondFactor
    }
    val token = resp.sessionToken ?: resp.accessToken ?: resp.token
    if (token.isNullOrBlank()) return PasswordSignInOutcome.NoSession
    val address = resp.signedInAddress(identifier)
    SessionStore.save(context, token, resp.refreshToken, address,
        resp.accountOrNull?.name ?: "", resp.expiresAtMillis)
    flow.clearSignIn()
    // Fire-and-forget: the sheet rides the activity, the
    // sign-in carries straight on into onSignedIn.
    if (!fromSavedLogin) PasswordCredentials.offerToSave(context, address, password)
    return PasswordSignInOutcome.SignedIn(address)
}

/** How storing a server-verified passkey assertion's session went. */
internal sealed class PasskeySessionOutcome {
    data class SignedIn(val address: String) : PasskeySessionOutcome()
    object NoSession : PasskeySessionOutcome()
    object NoAccount : PasskeySessionOutcome()
}

/** Store the session a verified passkey returned, through the same [SessionStore.save]. */
internal suspend fun storePasskeySession(
    context: android.content.Context,
    flow: AccountFlowViewModel,
    resp: com.grapsee.grapmail.data.PasskeyAuthVerifyResponse,
): PasskeySessionOutcome {
    val token = resp.sessionToken ?: resp.accessToken ?: resp.token
    if (token.isNullOrBlank()) return PasskeySessionOutcome.NoSession
    val address = resp.signedInAddress
    if (address.isNullOrBlank()) return PasskeySessionOutcome.NoAccount
    SessionStore.save(
        context, token, resp.refreshToken, address,
        resp.accountOrNull?.name ?: "", resp.expiresAtMillis,
    )
    flow.clearSignIn()
    return PasskeySessionOutcome.SignedIn(address)
}
