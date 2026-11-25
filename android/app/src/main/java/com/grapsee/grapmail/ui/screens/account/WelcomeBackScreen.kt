package com.grapsee.grapmail.ui.screens.account

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.RememberedAccounts
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.findActivity
import com.grapsee.grapmail.logic.AccountFlowViewModel
import com.grapsee.grapmail.logic.GMHaptics
import com.grapsee.grapmail.security.PasskeyManager
import com.grapsee.grapmail.security.PasswordCredentials
import com.grapsee.grapmail.ui.artwork.Artwork
import com.grapsee.grapmail.ui.components.AccountLink
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.GMAutofill
import com.grapsee.grapmail.ui.components.GMMotion
import com.grapsee.grapmail.ui.components.GMWidth
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.GrapMailLogo
import com.grapsee.grapmail.ui.components.gmAutofill
import com.grapsee.grapmail.ui.components.gmIsShortWindow
import com.grapsee.grapmail.ui.components.gmMaxWidth
import com.grapsee.grapmail.ui.components.gmWidthClass
import com.grapsee.grapmail.ui.components.rememberMotionReduced
import com.grapsee.grapmail.ui.screens.mail.AccountPhoto
import com.grapsee.grapmail.ui.theme.Body
import com.grapsee.grapmail.ui.theme.BodyMedium
import com.grapsee.grapmail.ui.theme.Caption
import com.grapsee.grapmail.ui.theme.CaptionMedium
import com.grapsee.grapmail.ui.theme.H2
import com.grapsee.grapmail.ui.theme.LocalPalette
import com.grapsee.grapmail.ui.theme.Title
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The personal welcome-back sign-in (round8-spec §2.2–2.5): the account this
 * device remembers, its photo, its name and a masked address, and one button,
 * "Continue as {first name}", that climbs the §2.3 ladder — a passkey (when
 * the record's hint says there is one), else the saved password for exactly
 * this address from the password manager, else the password field in place.
 *
 * Every sign-in it completes goes through [completePasswordSignIn] /
 * [storePasskeySession] in SignInScreen.kt and then [onSignedIn], i.e. the
 * same `MainActivity.signedIn()` every other door uses — so trusted-device
 * enrollment, push registration, the held-password save and the
 * remembered-account write all run exactly as they do for the typed form.
 *
 * The route reads which account from [AccountFlowViewModel.State.welcomeBackAddress]
 * (never a route argument: the back stack is written to disk) and falls back
 * to the newest signed-out remembered account.
 */
@Composable
fun WelcomeBackScreen(
    flow: AccountFlowViewModel,
    onSignedIn: (address: String) -> Unit,
    onNeedSecondFactor: () -> Unit,
    onForgotPassword: () -> Unit,
    onUseAnotherAccount: () -> Unit,
    /** The standard sign-in form, replacing this screen (nothing remembered is left, or the account is blocked). */
    onStandardSignIn: () -> Unit,
    /** The account chooser, replacing this screen (a removal left other remembered accounts). */
    onChooseAccount: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flowState by flow.state.collectAsStateWithLifecycle()
    val accounts by rememberSignedOutAccounts()
    var shownAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }

    val list = accounts
    if (list == null) {
        Box(Modifier.fillMaxSize().background(palette.bg))
        return
    }
    val wanted = shownAddress ?: flowState.welcomeBackAddress?.let(RememberedAccounts::canonical)
    val record = list.firstOrNull { it.address == wanted }
        ?: if (shownAddress == null) list.firstOrNull() else null
    LaunchedEffect(record?.address) {
        if (record != null) shownAddress = record.address
    }
    if (record == null) {
        // Removed elsewhere, pruned, or signed in meanwhile: the plain form.
        if (!leaving) LaunchedEffect(Unit) { onStandardSignIn() }
        Box(Modifier.fillMaxSize().background(palette.bg))
        return
    }

    WelcomeBackContent(
        record = record,
        flow = flow,
        avatarModifier = Modifier,
        animateEntrance = true,
        onSignedIn = onSignedIn,
        onNeedSecondFactor = onNeedSecondFactor,
        onForgotPassword = onForgotPassword,
        onUseAnotherAccount = onUseAnotherAccount,
        onStandardSignIn = onStandardSignIn,
        onRemove = {
            leaving = true
            scope.launch {
                RememberedAccounts.forget(context, record.address)
                val remaining = RememberedAccounts.signedOut(context).size
                // The screen is replaced, so the confirmation rides a toast
                // rather than a snackbar that would leave with it.
                Toast.makeText(context, context.getString(R.string.wb_removed), Toast.LENGTH_SHORT).show()
                if (remaining > 0) onChooseAccount() else onStandardSignIn()
            }
        },
    )
}

/**
 * The welcome-back body for one [record], shared by [WelcomeBackScreen] and
 * the chooser's in-place second stage. [avatarModifier] carries the chooser's
 * shared-element hookup (empty from the route). [onRemove] runs after the
 * person confirmed "Remove from this device"; the caller forgets the record
 * and decides where to go.
 */
@Composable
internal fun WelcomeBackContent(
    record: RememberedAccounts.Record,
    flow: AccountFlowViewModel,
    avatarModifier: Modifier,
    animateEntrance: Boolean,
    onSignedIn: (address: String) -> Unit,
    onNeedSecondFactor: () -> Unit,
    onForgotPassword: () -> Unit,
    onUseAnotherAccount: () -> Unit,
    onStandardSignIn: () -> Unit,
    onRemove: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduced = rememberMotionReduced()
    val online by rememberOnline()
    val keyboard = LocalSoftwareKeyboardController.current

    var busy by remember { mutableStateOf(false) }
    var showPassword by rememberSaveable(record.address) { mutableStateOf(false) }
    var password by remember(record.address) { mutableStateOf("") }
    var fieldError by rememberSaveable(record.address) { mutableStateOf<String?>(null) }
    var notice by rememberSaveable(record.address) { mutableStateOf<String?>(null) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }

    // The entrance plays once per visit, not again after a rotation.
    var entered by rememberSaveable { mutableStateOf(false) }
    val playEntrance = animateEntrance && !entered
    LaunchedEffect(Unit) { entered = true }

    LaunchedEffect(record.address) {
        // The same hygiene the form keeps: nothing from an abandoned attempt
        // (a challenge, a held password) belongs to this visit.
        flow.clearSignIn()
        PasswordCredentials.clearHeld()
    }

    val masked = remember(record.address) { wbMaskAddress(record.address) }
    val spokenAddress = masked.spoken()
    val name = record.displayName.trim().ifEmpty { masked.text }
    val firstName = remember(record.firstName, record.displayName) { wbFirstName(record) }
    val continueLabel = firstName?.let { stringResource(R.string.wb_continue_as, it) }
        ?: stringResource(R.string.wb_continue)

    // §2.7: the field grows in, and focus (with the keyboard) follows once it has.
    LaunchedEffect(showPassword) {
        if (!showPassword) return@LaunchedEffect
        if (!reduced) delay(GMMotion.MediumMs.toLong())
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    fun wrongPassword() {
        password = ""
        fieldError = context.getString(R.string.wb_wrong_password)
        showPassword = true
        GMHaptics.failure()
        if (!reduced) {
            scope.launch {
                shake.snapTo(0f)
                for (x in floatArrayOf(-10f, 10f, -8f, 8f, -4f, 4f, 0f)) shake.animateTo(x, tween(40))
            }
        }
    }

    fun finishSignedIn(address: String) {
        GMHaptics.success()
        // §2.3: a discoverable passkey may sign in a different account than
        // the one shown. Accepted — its session is installed and remembered
        // by SessionStore.save — and said so.
        if (RememberedAccounts.canonical(address) != record.address) {
            Toast.makeText(context, context.getString(R.string.wb_signed_in_as, address), Toast.LENGTH_SHORT).show()
        }
        onSignedIn(address)
    }

    suspend fun signInWithPassword(secret: String, fromSavedLogin: Boolean) {
        try {
            when (val outcome = completePasswordSignIn(context, flow, record.address, secret, fromSavedLogin)) {
                is PasswordSignInOutcome.SignedIn -> {
                    password = ""
                    finishSignedIn(outcome.address)
                }
                PasswordSignInOutcome.SecondFactor -> {
                    password = ""
                    onNeedSecondFactor()
                }
                PasswordSignInOutcome.NoSession -> {
                    fieldError = context.getString(R.string.acct_signin_no_session)
                    showPassword = true
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ApiException) {
            when {
                // The gateway's suspended/inactive answer (a 403 with no code):
                // the record goes (§2.4 rule 4) and the standard form takes over
                // with what the server said.
                e.status == 403 && e.code == null -> {
                    RememberedAccounts.forget(context, record.address)
                    flow.update { copy(signInNotice = e.message) }
                    onStandardSignIn()
                }
                e.code == "TOO_MANY_ATTEMPTS" || e.status == 429 -> {
                    fieldError = e.message?.takeIf(String::isNotBlank)
                        ?: context.getString(R.string.acct_too_many_failed_attempts_try_again)
                    showPassword = true
                    GMHaptics.failure()
                }
                e.status == 401 || e.code == "WRONG_PASSWORD" || e.code == "INVALID_CREDENTIALS" -> wrongPassword()
                else -> {
                    fieldError = e.message ?: context.getString(R.string.acct_those_details_do_not_match_an)
                    showPassword = true
                }
            }
        } catch (_: Exception) {
            // Offline shows the banner on its own; anything else is a network error.
            if (online) fieldError = context.getString(R.string.acct_network_error)
            showPassword = true
        }
    }

    /** §2.3 — the ladder. Every rung that cannot run is skipped; a cancel goes to rung 3 silently. */
    fun onContinue() {
        if (busy || !online) return
        if (showPassword) {
            if (password.isBlank()) {
                runCatching { focus.requestFocus() }
                return
            }
            val secret = password
            busy = true; fieldError = null; notice = null
            scope.launch {
                try {
                    signInWithPassword(secret, fromSavedLogin = false)
                } finally {
                    busy = false
                }
            }
            return
        }
        val host = context.findActivity()
        busy = true; fieldError = null; notice = null
        scope.launch {
            try {
                // Rungs 1 + 2 in one Credential Manager request: the passkey
                // option only with the record's hint (and Android 9+, where
                // Credential Manager passkeys exist), the password option
                // filtered to this address.
                val picked = if (host == null) PasswordCredentials.SavedLogin.None else try {
                    PasswordCredentials.requestSavedLogin(
                        host,
                        stillWanted = { true },
                        forAddress = record.address,
                        includePasskey = record.hasPasskey && android.os.Build.VERSION.SDK_INT >= 28,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    PasswordCredentials.SavedLogin.None
                }
                when (picked) {
                    is PasswordCredentials.SavedLogin.Password ->
                        signInWithPassword(picked.password, fromSavedLogin = true)
                    is PasswordCredentials.SavedLogin.Passkey -> when (val result = picked.result) {
                        is PasskeyManager.SignInResult.Completed ->
                            when (val outcome = storePasskeySession(context, flow, result.response)) {
                                is PasskeySessionOutcome.SignedIn -> finishSignedIn(outcome.address)
                                PasskeySessionOutcome.NoSession -> {
                                    fieldError = context.getString(R.string.acct_signin_no_session)
                                    showPassword = true
                                }
                                PasskeySessionOutcome.NoAccount -> {
                                    fieldError = context.getString(R.string.acct_sign_in_succeeded_but_no_account)
                                    showPassword = true
                                }
                            }
                        is PasskeyManager.SignInResult.Failed -> {
                            notice = context.getString(R.string.acct_the_passkey_could_not_be_used_sign)
                            showPassword = true
                        }
                        // Nothing on this device, or the sheet was closed: rung 3, no error.
                        else -> {
                            showPassword = true
                        }
                    }
                    PasswordCredentials.SavedLogin.None -> {
                        showPassword = true
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    // ── parts ──────────────────────────────────────────────────────────────

    val avatar: @Composable (Dp, Int) -> Unit = { size, index ->
        RingedAvatar(
            record = record,
            size = size,
            label = name,
            spinning = busy,
            reduced = reduced,
            avatarModifier = avatarModifier,
            modifier = Modifier.wbEntrance(index, playEntrance, reduced, scaleFrom = 0.92f),
        )
    }

    val nameText: @Composable (TextStyle) -> Unit = { style ->
        Text(
            name,
            style = style,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().wbEntrance(1, playEntrance, reduced),
        )
    }

    val addressText: @Composable () -> Unit = {
        WbMiddleEllipsisText(
            masked.text,
            style = Body,
            color = palette.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .wbEntrance(2, playEntrance, reduced)
                .clearAndSetSemantics { contentDescription = spokenAddress },
        )
    }

    val banners: @Composable (TextAlign) -> Unit = { align ->
        val reasonText = when (record.reason) {
            RememberedAccounts.SignOutReason.Expired -> stringResource(R.string.wb_reason_expired)
            RememberedAccounts.SignOutReason.Revoked -> stringResource(R.string.wb_reason_revoked)
            else -> null
        }
        Column(
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            if (reasonText != null) {
                Spacer(Modifier.height(8.dp))
                Text(reasonText, style = Caption, color = palette.textSecondary, textAlign = align,
                    modifier = Modifier.fillMaxWidth().wbEntrance(2, playEntrance, reduced))
            }
            if (!online) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.warnChipBg)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.WifiOff, contentDescription = null, tint = palette.warnInk, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.wb_offline), style = Caption, color = palette.warnInk)
                }
            }
            val shownNotice = notice
            if (shownNotice != null) {
                Spacer(Modifier.height(8.dp))
                Text(shownNotice, style = Caption, color = palette.textSecondary, textAlign = align,
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }

    val passwordField: @Composable () -> Unit = {
        AnimatedVisibility(
            visible = showPassword,
            enter = if (reduced) EnterTransition.None
            else expandVertically(tween(GMMotion.MediumMs, easing = GMMotion.Standard)) +
                fadeIn(tween(GMMotion.MediumMs, easing = GMMotion.Standard)),
            exit = if (reduced) ExitTransition.None
            else shrinkVertically(tween(GMMotion.ShortMs)) + fadeOut(tween(GMMotion.ShortMs)),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                // §2.3 rung 3: the address rides along, invisible, so the
                // platform's autofill knows whose password this field wants.
                HiddenUsernameField(record.address)
                AppTextField(
                    label = stringResource(R.string.acct_password),
                    value = password,
                    onValueChange = {
                        password = it
                        if (fieldError != null) fieldError = null
                    },
                    modifier = Modifier
                        .focusRequester(focus)
                        .offset { IntOffset(shake.value.dp.roundToPx(), 0) },
                    error = fieldError,
                    hint = stringResource(R.string.acct_enter_your_password),
                    password = true,
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = { onContinue() }),
                    autofill = GMAutofill.Password,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(Modifier.padding(top = 6.dp)) {
                        AccountLink(stringResource(R.string.acct_forgot_password_2), onClick = {
                            // The existing reset flow, with this address already in it.
                            flow.update { copy(identifier = record.address) }
                            onForgotPassword()
                        })
                    }
                }
            }
        }
    }

    val primaryButton: @Composable () -> Unit = {
        GradientButton(
            text = if (busy) stringResource(R.string.wb_signing_in) else continueLabel,
            onClick = { onContinue() },
            enabled = online && (!showPassword || password.isNotBlank() || busy),
            modifier = Modifier.wbEntrance(3, playEntrance, reduced),
        )
    }

    val useAnother: @Composable () -> Unit = {
        WbTextButton(
            text = stringResource(R.string.wb_use_another_account),
            color = palette.primarySolid,
            style = BodyMedium,
            minHeight = 48.dp,
            enabled = !busy,
            onClick = {
                RememberedAccounts.skipForThisLaunch()
                onUseAnotherAccount()
            },
            modifier = Modifier.wbEntrance(4, playEntrance, reduced),
        )
    }

    val removeButton: @Composable () -> Unit = {
        WbTextButton(
            text = stringResource(R.string.wb_remove_from_device),
            color = palette.textSecondary,
            style = CaptionMedium,
            minHeight = 44.dp,
            icon = Icons.Rounded.PersonRemove,
            enabled = !busy,
            onClick = { confirmRemove = true },
            modifier = Modifier.wbEntrance(5, playEntrance, reduced),
        )
    }

    // ── layouts (§2.2) ─────────────────────────────────────────────────────

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.bg)) {
        val viewport = maxHeight
        val short = gmIsShortWindow()
        // The two-pane form needs the room it draws in, not just a wide window:
        // the graph caps non-home routes' width (see the wiring note), so the
        // phone column stays until the route is really given the width.
        val twoPane = !short && gmWidthClass() != GMWidth.Compact && maxWidth >= 600.dp

        when {
            short -> Row(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(0.4f), horizontalAlignment = Alignment.CenterHorizontally) {
                    avatar(72.dp, 0)
                    Spacer(Modifier.height(12.dp))
                    nameText(Title)
                    addressText()
                }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(0.6f)) {
                    banners(TextAlign.Start)
                    Spacer(Modifier.height(16.dp))
                    passwordField()
                    primaryButton()
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { useAnother() }
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { removeButton() }
                }
            }

            twoPane -> Row(Modifier.fillMaxSize().imePadding()) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Artwork(
                        "welcome",
                        modifier = Modifier
                            .padding(32.dp)
                            .widthIn(max = 420.dp)
                            .fillMaxWidth()
                            .aspectRatio(2f),
                    )
                }
                Box(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            .heightIn(min = viewport)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(
                            Modifier
                                .widthIn(max = 440.dp)
                                .fillMaxWidth()
                                .shadow(1.dp, RoundedCornerShape(20.dp))
                                .clip(RoundedCornerShape(20.dp))
                                .background(palette.card)
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.wbEntrance(0, playEntrance, reduced)) { GrapMailLogo(28.dp, wordmark = true) }
                            Spacer(Modifier.height(32.dp))
                            avatar(88.dp, 0)
                            Spacer(Modifier.height(16.dp))
                            nameText(H2)
                            addressText()
                            banners(TextAlign.Center)
                            Spacer(Modifier.height(32.dp))
                            passwordField()
                            primaryButton()
                            Spacer(Modifier.height(12.dp))
                            useAnother()
                            Spacer(Modifier.height(24.dp))
                            removeButton()
                        }
                    }
                }
            }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    Modifier
                        .heightIn(min = viewport)
                        .padding(horizontal = 24.dp)
                        .gmMaxWidth(420.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.wbEntrance(0, playEntrance, reduced)) { GrapMailLogo(28.dp, wordmark = true) }
                    Spacer(Modifier.height(40.dp))
                    avatar(88.dp, 0)
                    Spacer(Modifier.height(16.dp))
                    nameText(H2)
                    addressText()
                    banners(TextAlign.Center)
                    Spacer(Modifier.height(32.dp))
                    passwordField()
                    primaryButton()
                    Spacer(Modifier.height(12.dp))
                    useAnother()
                    Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
                    removeButton()
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    if (confirmRemove) {
        RemoveConfirmDialog(
            who = firstName ?: masked.text,
            onConfirm = {
                confirmRemove = false
                onRemove()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

// ── shared pieces (welcome-back + chooser) ───────────────────────────────────

/**
 * Remembered accounts with no stored session, newest first; null until the
 * first read. Live: a removal or a prune shows at once.
 */
@Composable
internal fun rememberSignedOutAccounts(): State<List<RememberedAccounts.Record>?> {
    val context = LocalContext.current.applicationContext
    return produceState<List<RememberedAccounts.Record>?>(initialValue = null, context) {
        val signedIn = SessionStore.snapshotAll(context).map { RememberedAccounts.canonical(it.address) }.toSet()
        RememberedAccounts.records(context).collect { all ->
            value = all.filterNot { it.address in signedIn }
        }
    }
}

/**
 * The remembered account's avatar: its cached photo, else the initials disc.
 * Drawn by the app's account avatar ([AccountPhoto], which falls back to the
 * shared avatar in Mail.kt), so the Round 8 identity restyle reaches it with
 * no change here.
 */
@Composable
internal fun RememberedAvatar(record: RememberedAccounts.Record, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val bytes by produceState<ByteArray?>(initialValue = null, record.address, record.avatarCacheRef) {
        value = RememberedAccounts.avatarBytes(context, record)
    }
    Box(modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        AccountPhoto(bytes, record.displayName, record.address, size)
    }
}

/**
 * The 88 hero avatar with its 3 dp gap and 3 dp gradient ring (§2.2 row 3).
 * While signing in the ring turns, 1.2 s a turn, linear; reduce motion keeps
 * it still. [avatarModifier] is the chooser's shared element, on the avatar
 * itself so it travels 48 → 88 without the ring.
 */
@Composable
private fun RingedAvatar(
    record: RememberedAccounts.Record,
    size: Dp,
    label: String,
    spinning: Boolean,
    reduced: Boolean,
    avatarModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    // The signature-gradient family from the palette's own brand tokens
    // (TODO(round8-A): switch to the shared signature brush once
    // Workstream A lands it in the theme).
    val ring = remember(palette.primaryTop, palette.primaryBottom, palette.tabTintSent) {
        Brush.linearGradient(listOf(palette.primaryTop, palette.primaryBottom, palette.tabTintSent))
    }
    val turn: State<Float>? = if (spinning && !reduced) {
        rememberInfiniteTransition(label = "wb-ring").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
            label = "wb-ring-turn",
        )
    } else {
        null
    }
    Box(modifier.size(size + 12.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { rotationZ = turn?.value ?: 0f }
                .border(3.dp, ring, CircleShape),
        )
        RememberedAvatar(
            record,
            size,
            avatarModifier.semantics { contentDescription = label },
        )
    }
}

/** A centred text button with an optional 18 dp leading icon (theme colours only). */
@Composable
internal fun WbTextButton(
    text: String,
    color: Color,
    style: TextStyle,
    minHeight: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = style, color = color, textAlign = TextAlign.Center)
    }
}

/** §2.5 — "Remove {firstName or address} from this device?" */
@Composable
internal fun RemoveConfirmDialog(who: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = palette.card,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textSecondary,
        title = { Text(stringResource(R.string.wb_remove_title, who), style = Title) },
        text = { Text(stringResource(R.string.wb_remove_body), style = Body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.wb_remove_confirm), style = BodyMedium, color = palette.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.wb_cancel), style = BodyMedium, color = palette.textPrimary)
            }
        },
    )
}

/**
 * An invisible, read-only username field holding the address, so an
 * autofill service filling the password field knows which login it is for.
 * Out of the focus order and out of TalkBack.
 */
@Composable
private fun HiddenUsernameField(address: String) {
    BasicTextField(
        value = address,
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusProperties { canFocus = false }
            .gmAutofill(GMAutofill.Username) {}
            .clearAndSetSemantics {},
    )
}

/**
 * The §2.7 entrance: fade + rise 8 (or, with [scaleFrom], fade + scale),
 * 300 ms emphasized-decelerate, staggered 40 ms by [index]. Reduce motion: a
 * 150 ms crossfade. Played only when [play]; otherwise drawn settled.
 */
@Composable
private fun Modifier.wbEntrance(index: Int, play: Boolean, reduced: Boolean, scaleFrom: Float? = null): Modifier {
    val progress = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            progress.animateTo(
                1f,
                if (reduced) tween(GMMotion.ShortMs)
                else tween(GMMotion.LongMs, delayMillis = index * 40, easing = GMMotion.EmphasizedDecelerate),
            )
        }
    }
    val rise = with(LocalDensity.current) { 8.dp.toPx() }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p
        if (!reduced) {
            if (scaleFrom != null) {
                val s = scaleFrom + (1f - scaleFrom) * p
                scaleX = s
                scaleY = s
            } else {
                translationY = (1f - p) * rise
            }
        }
    }
}

/** One line, truncated in the middle when it does not fit (§2.2 row 6). */
@Composable
internal fun WbMiddleEllipsisText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxPx = constraints.maxWidth
        val shown = remember(text, style, maxPx) {
            if (maxPx == Constraints.Infinity) text
            else wbFitMiddle(text, maxPx) { candidate ->
                measurer.measure(candidate, style = style, maxLines = 1, softWrap = false).size.width
            }
        }
        Text(
            shown,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun wbFitMiddle(text: String, maxPx: Int, width: (String) -> Int): String {
    if (width(text) <= maxPx) return text
    val g = wbGraphemes(text)
    fun cut(keep: Int): String {
        val head = (keep + 1) / 2
        val tail = keep / 2
        return g.take(head).joinToString("") + "\u2026" + g.takeLast(tail).joinToString("")
    }
    var lo = 0
    var hi = g.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (width(cut(mid)) <= maxPx) lo = mid else hi = mid - 1
    }
    return cut(lo)
}

/** Connectivity, live: false while the device has no internet-capable default network. */
@Composable
private fun rememberOnline(): State<Boolean> {
    val context = LocalContext.current.applicationContext
    return produceState(initialValue = wbIsOnline(context), context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@produceState
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                value = true
            }

            override fun onLost(network: Network) {
                value = false
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        awaitDispose {
            if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
}

private fun wbIsOnline(context: android.content.Context): Boolean = runCatching {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}.getOrDefault(true)

// ── address + name rules (§2.2) ──────────────────────────────────────────────

/** The masked address and the pieces TalkBack reads instead of it. */
internal class WbMaskedAddress(
    val text: String,
    val visibleStart: List<String>,
    val visibleEnd: String?,
    val domain: String,
) {
    /** "Address beginning o l, ending r at grapmail.com" — never the full address. */
    @Composable
    fun spoken(): String {
        val first = visibleStart.joinToString(" ")
        return if (visibleEnd != null) stringResource(R.string.wb_address_spoken, first, visibleEnd, domain)
        else stringResource(R.string.wb_address_spoken_short, first, domain)
    }
}

/**
 * §2.2 masked-address rule (this file's own copy, matching the spec; the
 * shared helper arrives with Workstream A). Split at the LAST `@`; with n
 * graphemes in the local part: n ≤ 2 → first + •••; 3–4 → first + ••• +
 * last; ≥ 5 → first two + ••• + last. The domain is always whole; the
 * bullets are three U+2022, no spaces. `olivier@grapmail.com` →
 * `ol•••r@grapmail.com`.
 */
internal fun wbMaskAddress(address: String): WbMaskedAddress {
    val at = address.lastIndexOf('@')
    val local = if (at >= 0) address.substring(0, at) else address
    val domain = if (at >= 0) address.substring(at + 1) else ""
    val g = wbGraphemes(local)
    val n = g.size
    val start: List<String>
    val end: String?
    when {
        n == 0 -> { start = emptyList(); end = null }
        n <= 2 -> { start = g.take(1); end = null }
        n <= 4 -> { start = g.take(1); end = g.last() }
        else -> { start = g.take(2); end = g.last() }
    }
    val text = start.joinToString("") + "\u2022\u2022\u2022" + (end ?: "") + (if (at >= 0) "@$domain" else "")
    return WbMaskedAddress(text, start, end, domain)
}

/**
 * §2.2 first-name rule: the profile's first name, else the first word of the
 * display name; null (the button then says "Continue") when neither exists or
 * the result is longer than 16 graphemes.
 */
internal fun wbFirstName(record: RememberedAccounts.Record): String? {
    val candidate = record.firstName.trim().ifEmpty {
        record.displayName.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
    }
    if (candidate.isEmpty()) return null
    return candidate.takeIf { wbGraphemes(it).size <= 16 }
}

/** User-perceived characters (ICU grapheme clusters), so an emoji or a combining mark counts once. */
internal fun wbGraphemes(s: String): List<String> {
    if (s.isEmpty()) return emptyList()
    val it = android.icu.text.BreakIterator.getCharacterInstance()
    it.setText(s)
    val out = ArrayList<String>()
    var start = it.first()
    var end = it.next()
    while (end != android.icu.text.BreakIterator.DONE) {
        out += s.substring(start, end)
        start = end
        end = it.next()
    }
    return out
}
