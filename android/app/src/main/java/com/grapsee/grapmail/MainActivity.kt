package com.grapsee.grapmail

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.waterfall
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.SessionEvents
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.ui.screens.security.SecuritySnapshotScreen
import com.grapsee.grapmail.logic.AccountFlowViewModel
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.data.MailBox
import com.grapsee.grapmail.logic.MailboxViewModel
import com.grapsee.grapmail.ui.screens.mail.AttachmentPreviewScreen
import com.grapsee.grapmail.ui.screens.mail.ComposeScreen
import com.grapsee.grapmail.ui.screens.mail.ContactDetailScreen
import com.grapsee.grapmail.ui.screens.mail.ContactsScreen
import com.grapsee.grapmail.ui.screens.mail.FilesScreen
import com.grapsee.grapmail.ui.screens.mail.LabelCreateScreen
import com.grapsee.grapmail.ui.screens.mail.LabelsScreen
import com.grapsee.grapmail.ui.screens.mail.MailSettingsScreen
import com.grapsee.grapmail.ui.screens.mail.MailboxScreen
import com.grapsee.grapmail.ui.screens.mail.MailSnapshotScreen
import com.grapsee.grapmail.ui.screens.mail.ScreenerScreen
import com.grapsee.grapmail.ui.screens.mail.SubscriptionsScreen
import com.grapsee.grapmail.ui.screens.mail.ThreadScreen
import com.grapsee.grapmail.ui.screens.AccountCreatedAltScreen
import com.grapsee.grapmail.ui.screens.AccountCreatedScreen
import com.grapsee.grapmail.ui.screens.AddressStepScreen
import com.grapsee.grapmail.ui.screens.BasicInfoStepScreen
import com.grapsee.grapmail.ui.screens.HumanVerifyScreen
import com.grapsee.grapmail.ui.screens.NameStepScreen
import com.grapsee.grapmail.ui.screens.PasswordStepScreen
import com.grapsee.grapmail.ui.screens.TermsStepScreen
import com.grapsee.grapmail.ui.screens.WelcomeScreen
import com.grapsee.grapmail.ui.screens.account.AddressFoundScreen
import com.grapsee.grapmail.ui.screens.account.BackupCodeScreen
import com.grapsee.grapmail.ui.screens.account.FindAddressScreen
import com.grapsee.grapmail.ui.screens.account.ForgotPasswordScreen
import com.grapsee.grapmail.ui.screens.account.NewPasswordScreen
import com.grapsee.grapmail.ui.screens.account.PhoneVerificationScreen
import com.grapsee.grapmail.ui.screens.account.PhoneVerifiedScreen
import com.grapsee.grapmail.ui.screens.account.RecoveryCodeScreen
import com.grapsee.grapmail.ui.screens.account.RecoveryPendingScreen
import com.grapsee.grapmail.ui.screens.account.ResetDoneScreen
import com.grapsee.grapmail.ui.screens.account.SignInScreen
import com.grapsee.grapmail.ui.screens.account.TwoFactorScreen
import com.grapsee.grapmail.ui.screens.security.AccountSecurityScreen
import com.grapsee.grapmail.ui.screens.security.BackupCodesSavedScreen
import com.grapsee.grapmail.ui.screens.security.ChangePasswordScreen
import com.grapsee.grapmail.ui.screens.security.ConfirmPasswordScreen
import com.grapsee.grapmail.ui.screens.security.ConfirmSessionRevocationScreen
import com.grapsee.grapmail.ui.screens.security.DisableTwoFactorScreen
import com.grapsee.grapmail.ui.screens.security.GenerateBackupCodesScreen
import com.grapsee.grapmail.ui.screens.security.ManageSessionsScreen
import com.grapsee.grapmail.ui.screens.security.PasswordChangedScreen
import com.grapsee.grapmail.ui.screens.security.RevokeSessionScreen
import com.grapsee.grapmail.ui.screens.security.SecureMyAccountScreen
import com.grapsee.grapmail.ui.screens.security.SecurityActivityScreen
import com.grapsee.grapmail.ui.screens.security.SecurityFlow
import com.grapsee.grapmail.ui.screens.security.SessionRevokedScreen
import com.grapsee.grapmail.ui.screens.security.SessionRevokedSuspiciousScreen
import com.grapsee.grapmail.ui.screens.security.SignOutAllDevicesScreen
import com.grapsee.grapmail.ui.screens.security.SignedOutAllDevicesScreen
import com.grapsee.grapmail.ui.screens.security.SuspiciousSignInAlertScreen
import com.grapsee.grapmail.ui.screens.security.TwoFactorDisabledScreen
import com.grapsee.grapmail.ui.screens.security.TwoFactorEnableScreen
import com.grapsee.grapmail.ui.screens.security.TwoFactorEnabledScreen
import com.grapsee.grapmail.ui.screens.security.VerifyPhoneScreen
import com.grapsee.grapmail.ui.theme.GrapMailTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : ComponentActivity() {
    /**
     * The home-screen "Compose mail" request, held here because the intent that
     * carries it arrives before any composable exists to act on it.
     *
     * It is a request, not a route: the navigation graph may not have a start
     * destination yet when it lands, and a shortcut that cold-starts the app
     * signed-out must not open a composer whose every send would fail. The
     * graph consumes it once the start destination is real, and consumes means
     * clearing it — a pending request left set would replay on the next
     * recomposition and open a composer nobody asked for.
     */
    private val composeRequest = androidx.compose.runtime.mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash, before the window content exists at all. The
        // library's own rule: installing after `super.onCreate` has the system
        // draw its blank starting window instead, which is the thing the splash
        // is here to replace. It is an extension on Activity imported from the
        // companion — `SplashScreen.installSplashScreen(this)` is not a call
        // this library has.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        takeComposeRequest(intent)
        setContent {
            GrapMailTheme {
                // Edge-to-edge with the design's background behind the system
                // bars, and content inside the safe area: the status bar and
                // gesture bar never collide with the UI, on every device size.
                // Forms cap at 520dp and center on tablets.
                // The root pads only the true system UI (bars + cutout); the
                // keyboard (IME) is owned per-screen (WizardScaffold.imePadding,
                // SignInScreen.imePadding) so it is never counted twice.
                val bg = com.grapsee.grapmail.ui.theme.LocalPalette.current.bg
                Box(
                    Modifier.fillMaxSize().background(bg)
                        // Every `testTag` in the app is invisible to a uiautomator
                        // dump unless this is set. It surfaces each one as the node's
                        // resource-id, which is what `uiautomator dump` reports and
                        // therefore what the CI walk can query. Without it the walk
                        // has to anchor on whatever sentence a screen happens to
                        // print, so a copy change breaks the walk for a reason that
                        // has nothing to do with the app being wrong — which is
                        // exactly what happened six times over when the placeholder
                        // home's "Signed in" was replaced.
                        //
                        // The property is in `androidx.compose.ui.semantics`. It is
                        // NOT in `ui.platform`; importing it from there fails to
                        // resolve, and reading that as "this Compose version does
                        // not have it" cost a whole attempt to migrate the anchors.
                        //
                        // It is also @ExperimentalComposeUiApi, and this build
                        // promotes that warning to an error — hence the @OptIn on
                        // the class. The annotation is the honest label, not an
                        // obstacle: if a future Compose changes the property, the
                        // build says so loudly instead of the walk quietly losing
                        // every identifier anchor and falling back to copy.
                        .semantics { testTagsAsResourceId = true },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier
                            .windowInsetsPadding(WindowInsets.systemBars
                                .union(WindowInsets.displayCutout).union(WindowInsets.waterfall))
                            .widthIn(max = 520.dp)
                            .fillMaxSize()
                    ) {
                        GrapMailNav(
                            composeRequest = composeRequest.value,
                            onComposeRequestConsumed = { composeRequest.value = false },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // The shortcut can arrive into a live activity. Set it as the current
        // intent so a configuration change replays the same request the person
        // actually made rather than the launch that happened first.
        setIntent(intent)
        takeComposeRequest(intent)
    }

    private fun takeComposeRequest(intent: android.content.Intent?) {
        if (intent?.action == COMPOSE_ACTION) composeRequest.value = true
    }

    companion object {
        /** The action `res/xml/shortcuts.xml` fires. Declared once, matched in
         *  the manifest's intent filter and here. */
        const val COMPOSE_ACTION = "com.grapsee.grapmail.COMPOSE"
    }
}

object Routes {
    const val WELCOME = "welcome"
    const val SIGN_IN = "signin"
    const val TWO_FACTOR = "account/twofactor"
    const val BACKUP_CODE = "account/backup-code"
    const val FORGOT_PASSWORD = "account/forgot"
    const val RECOVERY_CODE = "account/recovery-code"
    const val NEW_PASSWORD = "account/new-password"
    const val RESET_DONE = "account/reset-done"
    const val RECOVERY_PENDING = "account/recovery-pending"
    const val FIND_ADDRESS = "account/find-address"
    const val ADDRESS_FOUND = "account/address-found"
    const val PHONE_VERIFY = "account/phone"
    const val PHONE_VERIFIED = "account/phone/verified"
    // The security sector — the 24-screen batch. Everything hangs off SECURITY;
    // the hub is the only entry point, so a deep link can never leave somebody
    // on a confirmation screen with no parent.
    const val SECURITY = "security"
    const val SEC_2FA_ENABLE = "security/2fa/enable"
    const val SEC_CONFIRM_PASSWORD = "security/2fa/password"
    const val SEC_VERIFY_PHONE = "security/2fa/phone"
    const val SEC_2FA_ON = "security/2fa/on"
    const val SEC_2FA_OFF = "security/2fa/off"
    const val SEC_2FA_OFF_DONE = "security/2fa/off/done"
    const val SEC_BACKUP = "security/backup"
    const val SEC_BACKUP_SAVED = "security/backup/saved"
    const val SEC_PASSWORD = "security/password"
    const val SEC_PASSWORD_DONE = "security/password/done"
    const val SEC_PASSWORD_AFTER = "security/password/after"
    const val SEC_PASSWORD_UPDATED = "security/password/updated"
    const val SEC_SIGNOUT_ALL = "security/signout-all"
    const val SEC_SIGNOUT_ALL_DONE = "security/signout-all/done"
    const val SEC_DELETE_ACCOUNT = "security/delete-account"
    const val SEC_SESSIONS = "security/sessions"
    const val SEC_REVOKE = "security/sessions/revoke/{id}/{device}"
    const val SEC_REVOKED = "security/sessions/revoked/{device}"
    const val SEC_REVOKE_ALERT = "security/alert/revoke/{id}/{device}"
    const val SEC_REVOKED_ALERT = "security/alert/revoked/{device}"
    const val SEC_ACTIVITY = "security/activity"
    const val SEC_ALERT = "security/alert"
    const val SEC_SECURE = "security/secure"
    const val STEP_NAME = "signup/name"
    const val STEP_INFO = "signup/info"
    const val STEP_ADDRESS = "signup/address"
    const val STEP_PASSWORD = "signup/password"
    const val STEP_TERMS = "signup/terms"
    const val HUMAN_VERIFY = "signup/verify"
    const val CREATED = "signup/created/{address}/{name}"
    const val CREATED_ALT = "signup/created-alt/{address}/{name}"
    const val HOME = "home"

    // ── mailbox sector ───────────────────────────────────────────────────────
    // The ids ride the route the way the session ids do — encoded, then decoded
    // at the destination — so a thread opened from a list survives a recreate
    // with the conversation it was opened for.
    const val MAIL_THREAD = "mail/thread/{id}/{thread}"
    // Compose carries an optional reply target and mode: "mail/compose" opens
    // blank, "mail/compose?reply=<id>&mode=reply|all|forward" opens prepared.
    // The quote and the addressing are built from the FULL row at the
    // destination, not from what the list snippet happens to show.
    const val MAIL_COMPOSE = "mail/compose?reply={reply}&mode={mode}"

    fun mailCompose(reply: String, mode: String) =
        "mail/compose?reply=${encode(reply)}&mode=$mode"
    const val MAIL_LABELS = "mail/labels"
    const val MAIL_LABEL_CREATE = "mail/labels/create/{id}"

    /** "" means a new label — one screen for creating and editing, because the
     *  fields, the ramp and the validation are the same control, and a second
     *  copy of them is a second place for the two to disagree. */
    fun labelCreate(id: String) = "mail/labels/create/${encode(id)}"
    const val MAIL_CONTACTS = "mail/contacts"
    const val MAIL_CONTACT = "mail/contact/{address}"
    const val MAIL_FILES = "mail/files"
    const val MAIL_ATTACHMENT = "mail/attachment/{id}"
    const val MAIL_SETTINGS = "mail/settings"
    const val MAIL_SCREENER = "mail/screener"
    const val MAIL_SUBSCRIPTIONS = "mail/subscriptions"

    // ── account-record band ─────────────────────────────────────────────────
    // The web client's `/mail/notes`, `/mail/tasks`, `/mail/settings`,
    // `/mail/account/{profile,privacy,addresses,connections,support}` — one
    // profile document, eight screens over it. They hang off MAIL_SETTINGS the
    // way the security screens hang off SECURITY: every one has a parent, so a
    // deep link can never strand somebody on a child with no way back.
    const val MAIL_NOTES = "mail/notes"
    const val MAIL_TASKS = "mail/tasks"
    const val MAIL_PREFS = "mail/settings/prefs"
    const val MAIL_ADDRESSES = "mail/settings/addresses"
    const val MAIL_CONNECTIONS = "mail/settings/connections"
    const val MAIL_CONNECTION_DETAIL = "mail/settings/connections/{product}"
    const val ACCOUNT_PROFILE = "mail/settings/profile"
    const val ACCOUNT_PRIVACY = "mail/settings/privacy"
    const val MAIL_SUPPORT = "mail/support"

    // ── encryption band ──────────────────────────────────────────────────────
    // The web's `/mail/account/encryption` split into the screens a phone
    // needs. They hang off MAIL_SETTINGS like the account band; every one
    // reads and writes the device's key through `E2eViewModel`.
    const val E2E_STATUS = "mail/encryption"
    const val E2E_SETUP = "mail/encryption/setup"
    const val E2E_UNLOCK = "mail/encryption/unlock"
    const val E2E_DETAILS = "mail/encryption/details"
    const val E2E_PUBLISH = "mail/encryption/publish"
    const val E2E_PASSPHRASE = "mail/encryption/passphrase"
    const val E2E_REMOVE = "mail/encryption/remove"
    const val E2E_RECIPIENTS = "mail/encryption/recipients"

    fun mailThread(id: String, isThread: Boolean) =
        "mail/thread/${encode(id)}/${if (isThread) "1" else "0"}"
    fun mailContact(address: String) = "mail/contact/${encode(address)}"
    fun mailAttachment(id: String) = "mail/attachment/${encode(id)}"

    fun created(address: String, name: String) = "signup/created/${encode(address)}/${encode(name)}"
    fun createdAlt(address: String, name: String) = "signup/created-alt/${encode(address)}/${encode(name)}"
    /** The device label rides the route beside the id: the confirmation names
     *  the session it is about, and the list the person tapped already knew it.
     *  Fetching the label a second time only added a way for the screen to open
     *  with nothing to show. */
    fun revoke(id: String, device: String) = "security/sessions/revoke/${encode(id)}/${encode(device)}"
    fun revoked(device: String) = "security/sessions/revoked/${encode(device)}"
    fun revokeFromAlert(id: String, device: String) = "security/alert/revoke/${encode(id)}/${encode(device)}"
    fun revokedFromAlert(device: String) = "security/alert/revoked/${encode(device)}"

    /** The help link the footer notes carry. Opens the real support page. */
    const val SUPPORT_URL = "https://grapsee.com/contact"

    private fun encode(v: String) = android.net.Uri.encode(v)
}

@Composable
fun GrapMailNav(
    composeRequest: Boolean = false,
    onComposeRequestConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    val vm: SignUpViewModel = viewModel()
    val flow: AccountFlowViewModel = viewModel()
    /// One mailbox for the whole signed-in stack, held at the activity scope so
    /// the list, the thread and the secondary screens read and write the same
    /// pages rather than each fetching its own copy of the same mailbox.
    val mail: MailboxViewModel = viewModel()
    // The account record every Phase-1 screen reads and writes. One instance for
    // the activity: the screens are eight views of one document, and eight
    // separate copies of it is eight chances to disagree about the same account.
    val profile: com.grapsee.grapmail.logic.ProfileViewModel = viewModel()
    // The encryption sector's state: the device's identity, the in-memory
    // unlock session, and the directory's published answer. One instance for
    // the stack -- the status card and the compose lookup must never disagree
    // about whether this device can seal.
    val e2e: com.grapsee.grapmail.logic.E2eViewModel = viewModel()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun openSupport() {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(Routes.SUPPORT_URL),
        )
        runCatching { nav.context.startActivity(intent) }
    }

    // Returning to sign-in from a finished flow has to land on the existing
    // sign-in screen, not stack a second copy behind it.
    fun backToSignIn() {
        if (!nav.popBackStack(Routes.SIGN_IN, false)) {
            nav.navigate(Routes.SIGN_IN) { popUpTo(0) }
        }
    }

    // One place for a completed sign-in, whichever door finished it (password,
    // factor code, email code, backup code, the wizard's auto sign-in): the
    // session is stored by the door itself; this announces the device to the
    // push service with the bearer that was just stored. Without the Firebase
    // client config this is a quiet no-op and the poll remains the delivery.
    fun signedIn(address: String) {
        com.grapsee.grapmail.logic.PushSync.registerAsync(nav.context.applicationContext)
        nav.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) }
    }

    // Sign-out and deletion share the same teardown: the push registration is
    // retired while the bearer it needs is still stored, the security sector's
    // in-memory material dies with the person it belonged to, and the mailbox
    // cache goes so the next account on this device never reads this one's
    // mail. The unregister is capped, so a slow server never delays the
    // sign-out itself — the navigation below runs regardless.
    fun leaveSignedInState() {
        com.grapsee.grapmail.ui.screens.security.SecurityFlow.reset()
        e2e.clearForSignOut()
        scope.launch {
            val context = nav.context.applicationContext
            val session = SessionStore.snapshot(context) ?: return@launch
            kotlinx.coroutines.withTimeoutOrNull(4_000) {
                com.grapsee.grapmail.logic.PushSync.unregister(context, session)
            }
            SessionStore.clearIfCurrent(context, session.id)
        }
    }

    // A stored, unexpired session opens the app signed-in — the encrypted
    // token has to be read at launch, not just written.
    var startDest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val ctx = nav.context.applicationContext
        val stored = SessionStore.snapshot(ctx)
        val unexpired = stored?.unexpired == true
        // The gateway's access token is an hour old at most; the refresh token
        // is good for thirty days. A stored pair whose access token has aged out
        // is a session to renew silently, not a person to send back to sign-in —
        // otherwise every launch the morning after a sign-in looks signed out.
        val renewed = if (!unexpired && stored != null) {
            com.grapsee.grapmail.data.SessionRefresh.refresh(ctx)
        } else false
        startDest = if (unexpired || renewed) Routes.HOME else Routes.WELCOME
    }
    val dest = startDest ?: return

    // The home-screen shortcut, once the graph actually has a start
    // destination. Signed in, it means the composer; signed out, it means
    // sign-in, because a blank composer that cannot send is a screen that
    // pretends. Consumed on the way out either way.
    LaunchedEffect(composeRequest, dest) {
        if (!composeRequest) return@LaunchedEffect
        onComposeRequestConsumed()
        if (SessionStore.snapshot(nav.context.applicationContext)?.unexpired == true) {
            nav.navigate(Routes.mailCompose("", "reply"))
        } else {
            nav.navigate(Routes.SIGN_IN) { popUpTo(Routes.WELCOME) }
        }
    }

    // The device's feel of an action landing. The model publishes the result
    // (see `GMHaptics`) and it is played here, on the view Compose actually
    // owns, because `performHapticFeedback` needs one. The constants degrade
    // honestly: CONFIRM and REJECT are Android 11+, and below that the nearest
    // long-standing tap is used rather than nothing — the event happened, and
    // a device that cannot play it should stay quiet rather than play a lie.
    val hapticView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(Unit) {
        com.grapsee.grapmail.logic.GMHaptics.events.collect { event ->
            val constant = when (event) {
                com.grapsee.grapmail.logic.GMHaptics.Event.Filing ->
                    android.view.HapticFeedbackConstants.KEYBOARD_TAP
                com.grapsee.grapmail.logic.GMHaptics.Event.Marking ->
                    android.view.HapticFeedbackConstants.VIRTUAL_KEY
                com.grapsee.grapmail.logic.GMHaptics.Event.Success ->
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                        android.view.HapticFeedbackConstants.CONFIRM
                    else android.view.HapticFeedbackConstants.KEYBOARD_TAP
                com.grapsee.grapmail.logic.GMHaptics.Event.Failure ->
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                        android.view.HapticFeedbackConstants.REJECT
                    else android.view.HapticFeedbackConstants.LONG_PRESS
            }
            // Asked of the view once, and the view's own answer reported.
            // `performHapticFeedback` returns false when there is no actuator
            // to play on, so the line ties the model's event to what the view
            // actually did with it — and calling it twice to print that answer
            // would be two vibrations for one action, which is the kind of
            // "observability" that changes the thing it observes.
            //
            // CI greps this tag: a haptic that only exists in a source file is
            // not observable any other way, because no screenshot can show a
            // vibration and no emulator has a motor.
            val accepted = hapticView.performHapticFeedback(constant)
            android.util.Log.i(
                com.grapsee.grapmail.logic.GMHaptics.LOG_TAG,
                "played $event constant=$constant accepted=$accepted",
            )
        }
    }

    // A dead token is discovered by the server, never by us: the stored copy
    // still looks valid, so hasValidSession keeps saying yes while every screen
    // fails to load. The API interceptor raises this on an authenticated 401;
    // dropping the session and landing on sign-in here is the only way back in
    // that does not require reinstalling the app.
    LaunchedEffect(Unit) {
        SessionEvents.expired.collect { rejectedToken ->
            // The activity-scoped E2E view model outlives the nav graph: a
            // dead token must drop the unlocked private halves exactly like
            // a deliberate sign-out, or the next account inherits them.
            if (SessionStore.clearIfToken(nav.context.applicationContext, rejectedToken)) {
                e2e.clearForSignOut()
                SecurityFlow.reset()
                // The bearer is dead, so the backend unregister has nothing to
                // authenticate with; the local push record goes, and the next
                // sign-in re-registers the same device token.
                com.grapsee.grapmail.logic.PushSync.forget(nav.context.applicationContext)
                nav.navigate(Routes.SIGN_IN) { popUpTo(0) { inclusive = true } }
            }
            // The flow replays its one pending signal to a fresh subscriber;
            // we have just acted on it, so take it out of the cache — the
            // next subscribe (a recomposition, a config change) must not
            // re-fire an expiry that is already answered.
            SessionEvents.resetExpired()
        }
    }

    // The real auto sign-in the website performs the moment the account
    // exists: same endpoint, same session storage. The result is reported to the
    // caller because a failure here must NOT walk the person into screens that
    // assume a token — every one of them would answer "sign in again" on a
    // session that was never written.
    fun autoSignIn(address: String, name: String, then: (Boolean) -> Unit) {
        scope.launch(Dispatchers.Main) {
            var signedIn = false
            // The freshly-created credential can take a beat to become usable,
            // so retry with a short backoff before giving up. iOS does the same
            // (SignUpModel): without it the wizard strands a new user on the
            // sign-in screen instead of the human-verify -> created flow the
            // design shows, and the CI walk loses those screens.
            for (attempt in 0 until 4) {
                if (attempt > 0) { kotlinx.coroutines.delay(1_200) }
                try {
                    val resp = Api.signIn(address, vm.state.value.password)
                    val token = resp.sessionToken ?: resp.accessToken ?: resp.token
                    if (token != null) {
                        SessionStore.save(
                            nav.context.applicationContext,
                            token, resp.refreshToken, address, name, resp.expiresAtMillis,
                        )
                        com.grapsee.grapmail.logic.PushSync.registerAsync(nav.context.applicationContext)
                        signedIn = true
                        break
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The account exists; the next attempt, or signing in by
                    // hand, is the fallback.
                }
            }
            then(signedIn)
        }
    }

    // Test-only entry for the screens that exist only AFTER a write the CI walk
    // must not perform: signing out everywhere ends the session the restore leg
    // proves, and a password change breaks the sign-in leg. The walk starts the
    // activity with `--es gmSnapshot <name>` and this renders that one screen,
    // offline, with closures that do nothing. No real launch carries the extra.
    val snapshot = (nav.context as? android.app.Activity)?.intent?.getStringExtra("gmSnapshot")
    if (snapshot != null) {
        if (snapshot.startsWith("mail-") || snapshot.startsWith("account-") ||
            snapshot.startsWith("e2e-")) MailSnapshotScreen(snapshot)
        else SecuritySnapshotScreen(snapshot)
        return
    }

    // System Back on the mailbox home must not pop it. The home is the whole
    // stack while a session is alive, so popping it leaves an empty back stack and
    // the welcome screen renders underneath — telling a person who is still signed
    // in, with their mail one tap away, to create an account. The CI walk found
    // this by pressing Back to close a sheet that was already shut; anyone finds
    // it by pressing Back once from the inbox. It now does what it does in every
    // other mail app on Android: sends the app to the background, leaving the
    // session and the stack exactly as they were.
    val backStack by nav.currentBackStackEntryAsState()
    val onHome = backStack?.destination?.route == Routes.HOME ||
        (backStack == null && dest == Routes.HOME)
    val hostActivity = nav.context as? android.app.Activity
    BackHandler(enabled = onHome) { hostActivity?.moveTaskToBack(true) }

    NavHost(
        navController = nav,
        startDestination = dest,
        // The design's motion contract: 320ms slide + fade between wizard
        // steps (forward slides in from the right, back from the left).
        enterTransition = {
            slideInHorizontally(
                animationSpec = androidx.compose.animation.core.tween(320),
            ) { it / 4 } + fadeIn(androidx.compose.animation.core.tween(320))
        },
        exitTransition = {
            slideOutHorizontally(
                animationSpec = androidx.compose.animation.core.tween(320),
            ) { -it / 4 } + fadeOut(androidx.compose.animation.core.tween(320))
        },
        popEnterTransition = {
            slideInHorizontally(
                animationSpec = androidx.compose.animation.core.tween(320),
            ) { -it / 4 } + fadeIn(androidx.compose.animation.core.tween(320))
        },
        popExitTransition = {
            slideOutHorizontally(
                animationSpec = androidx.compose.animation.core.tween(320),
            ) { it / 4 } + fadeOut(androidx.compose.animation.core.tween(320))
        },
    ) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onCreateAccount = { nav.navigate(Routes.STEP_NAME) },
                onSignIn = { nav.navigate(Routes.SIGN_IN) },
            )
        }
        composable(Routes.SIGN_IN) {
            SignInScreen(
                flow = flow,
                onSignedIn = { signedIn(it) },
                onBack = { nav.popBackStack() },
                onSignUp = { nav.navigate(Routes.STEP_NAME) },
                onForgotPassword = { nav.navigate(Routes.FORGOT_PASSWORD) },
                onNeedSecondFactor = { nav.navigate(Routes.TWO_FACTOR) },
            )
        }
        composable(Routes.TWO_FACTOR) {
            TwoFactorScreen(
                flow = flow,
                onSignedIn = { signedIn(it) },
                onBack = { nav.popBackStack() },
                onBackToSignIn = { backToSignIn() },
                onUseBackupCode = { nav.navigate(Routes.BACKUP_CODE) },
            )
        }
        composable(Routes.BACKUP_CODE) {
            BackupCodeScreen(
                flow = flow,
                onSignedIn = { signedIn(it) },
                onBack = { nav.popBackStack() },
                onTryAnotherMethod = { nav.popBackStack() },
                onBackToSignIn = { backToSignIn() },
            )
        }
        composable(Routes.FORGOT_PASSWORD) {
            ForgotPasswordScreen(
                flow = flow,
                onBack = { nav.popBackStack() },
                onCodeSent = { nav.navigate(Routes.RECOVERY_CODE) },
                onNeedsManualReview = { nav.navigate(Routes.RECOVERY_PENDING) },
                onFindAddress = { nav.navigate(Routes.FIND_ADDRESS) },
                onSupport = { openSupport() },
            )
        }
        composable(Routes.RECOVERY_CODE) {
            RecoveryCodeScreen(
                flow = flow,
                onBack = { nav.popBackStack() },
                onContinue = { nav.navigate(Routes.NEW_PASSWORD) },
                onBackToSignIn = { backToSignIn() },
            )
        }
        composable(Routes.NEW_PASSWORD) {
            NewPasswordScreen(
                flow = flow,
                onBack = { nav.popBackStack() },
                onBackToCode = { nav.popBackStack() },
                onReset = { address ->
                    flow.update { copy(resetAddress = address ?: identifier) }
                    // The recovery stack is spent once the password changes: a
                    // back gesture must not land on a code screen whose
                    // challenge was already consumed by the reset.
                    nav.navigate(Routes.RESET_DONE) { popUpTo(Routes.FORGOT_PASSWORD) { inclusive = true } }
                },
            )
        }
        composable(Routes.RESET_DONE) {
            ResetDoneScreen(
                onBackToSignIn = {
                    val s = flow.state.value
                    flow.update { copy(prefillIdentifier = s.resetAddress ?: s.identifier) }
                    backToSignIn()
                },
            )
        }
        composable(Routes.RECOVERY_PENDING) {
            RecoveryPendingScreen(
                flow = flow,
                onBackToSignIn = { backToSignIn() },
                onSupport = { openSupport() },
            )
        }
        composable(Routes.FIND_ADDRESS) {
            FindAddressScreen(
                flow = flow,
                onBack = { nav.popBackStack() },
                onFound = { nav.navigate(Routes.ADDRESS_FOUND) },
                onSupport = { openSupport() },
            )
        }
        composable(Routes.ADDRESS_FOUND) {
            AddressFoundScreen(
                flow = flow,
                onBackToSignIn = { address ->
                    flow.update { copy(prefillIdentifier = address ?: foundAddressFull) }
                    backToSignIn()
                },
            )
        }
        composable(Routes.PHONE_VERIFY) {
            PhoneVerificationScreen(
                flow = flow,
                onBack = { nav.popBackStack() },
                onVerified = { nav.navigate(Routes.PHONE_VERIFIED) { popUpTo(Routes.PHONE_VERIFY) { inclusive = true } } },
            )
        }
        composable(Routes.PHONE_VERIFIED) {
            PhoneVerifiedScreen(
                flow = flow,
                onGoToSecurity = { nav.navigate(Routes.SECURITY) { popUpTo(Routes.HOME) } },
                onBackHome = { nav.popBackStack(Routes.HOME, false) },
            )
        }
        composable(Routes.SECURITY) {
            AccountSecurityScreen(
                onBack = { nav.popBackStack() },
                onTwoFactor = { nav.navigate(Routes.SEC_2FA_ENABLE) },
                onDisableTwoFactor = { nav.navigate(Routes.SEC_2FA_OFF) },
                onPhone = { nav.navigate(Routes.PHONE_VERIFY) },
                onSessions = { nav.navigate(Routes.SEC_SESSIONS) },
                onActivity = { nav.navigate(Routes.SEC_ACTIVITY) },
                onBackupCodes = { nav.navigate(Routes.SEC_BACKUP) },
                onPassword = { nav.navigate(Routes.SEC_PASSWORD) },
                onSignOutAll = { nav.navigate(Routes.SEC_SIGNOUT_ALL) },
                onDeleteAccount = { nav.navigate(Routes.SEC_DELETE_ACCOUNT) },
                onSupport = { openSupport() },
            )
        }
        composable(Routes.SEC_2FA_ENABLE) {
            TwoFactorEnableScreen(
                onBack = { nav.popBackStack() },
                onContinue = { nav.navigate(Routes.SEC_CONFIRM_PASSWORD) },
            )
        }
        composable(Routes.SEC_CONFIRM_PASSWORD) {
            ConfirmPasswordScreen(
                onBack = { nav.popBackStack() },
                onForgot = { nav.navigate(Routes.FORGOT_PASSWORD) },
                onVerified = { nav.navigate(Routes.SEC_VERIFY_PHONE) },
            )
        }
        composable(Routes.SEC_VERIFY_PHONE) {
            VerifyPhoneScreen(
                onBack = { nav.popBackStack() },
                onAddPhone = { nav.navigate(Routes.PHONE_VERIFY) },
                onVerified = { nav.navigate(Routes.SEC_2FA_ON) },
            )
        }
        composable(Routes.SEC_2FA_ON) {
            TwoFactorEnabledScreen(
                onContinue = { nav.navigate(Routes.SECURITY) { popUpTo(Routes.SECURITY) { inclusive = true } } },
                onBackHome = { nav.navigate(Routes.SECURITY) { popUpTo(Routes.SECURITY) { inclusive = true } } },
            )
        }
        composable(Routes.SEC_2FA_OFF) {
            DisableTwoFactorScreen(
                onBack = { nav.popBackStack() },
                onDisabled = { nav.navigate(Routes.SEC_2FA_OFF_DONE) },
            )
        }
        composable(Routes.SEC_2FA_OFF_DONE) {
            TwoFactorDisabledScreen(
                onDone = { nav.navigate(Routes.SECURITY) { popUpTo(Routes.SECURITY) { inclusive = true } } },
                onSecureNow = { nav.navigate(Routes.SEC_2FA_ENABLE) },
            )
        }
        composable(Routes.SEC_BACKUP) {
            GenerateBackupCodesScreen(
                onBack = { nav.popBackStack() },
                onGenerated = { codes ->
                    SecurityFlow.backupCodes = codes
                    nav.navigate(Routes.SEC_BACKUP_SAVED)
                },
            )
        }
        composable(Routes.SEC_BACKUP_SAVED) {
            BackupCodesSavedScreen(
                codes = SecurityFlow.backupCodes,
                onDone = { nav.navigate(Routes.SECURITY) { popUpTo(Routes.SECURITY) { inclusive = true } } },
            )
        }
        composable(Routes.SEC_PASSWORD) {
            ChangePasswordScreen(
                afterSuspicious = false,
                onBack = { nav.popBackStack() },
                onForgot = { nav.navigate(Routes.FORGOT_PASSWORD) },
                onChanged = { nav.navigate(Routes.SEC_PASSWORD_DONE) },
            )
        }
        composable(Routes.SEC_PASSWORD_DONE) {
            PasswordChangedScreen(
                updated = false,
                onDone = { nav.navigate(Routes.SECURITY) { popUpTo(Routes.SECURITY) { inclusive = true } } },
                onSupport = { openSupport() },
            )
        }
        composable(Routes.SEC_PASSWORD_AFTER) {
            ChangePasswordScreen(
                afterSuspicious = true,
                onBack = { nav.popBackStack() },
                onForgot = { nav.navigate(Routes.FORGOT_PASSWORD) },
                onChanged = { nav.navigate(Routes.SEC_PASSWORD_UPDATED) },
            )
        }
        composable(Routes.SEC_PASSWORD_UPDATED) {
            PasswordChangedScreen(
                updated = true,
                onDone = { nav.navigate(Routes.SECURITY) { popUpTo(Routes.SECURITY) { inclusive = true } } },
                onSupport = { openSupport() },
            )
        }
        composable(Routes.SEC_SIGNOUT_ALL) {
            SignOutAllDevicesScreen(
                onBack = { nav.popBackStack() },
                onDone = { nav.navigate(Routes.SEC_SIGNOUT_ALL_DONE) },
            )
        }
        composable(Routes.SEC_DELETE_ACCOUNT) {
            com.grapsee.grapmail.ui.screens.security.DeleteAccountScreen(
                onBack = { nav.popBackStack() },
                onDeleted = {
                    // The server confirmed the deletion; the same teardown a
                    // sign-out runs, because everything the device still holds
                    // for this account is now material about an account that
                    // no longer exists.
                    leaveSignedInState()
                    nav.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.SEC_SIGNOUT_ALL_DONE) {
            SignedOutAllDevicesScreen(
                onDone = { nav.navigate(Routes.SECURITY) { popUpTo(Routes.SECURITY) { inclusive = true } } },
            )
        }
        composable(Routes.SEC_SESSIONS) {
            ManageSessionsScreen(
                onBack = { nav.popBackStack() },
                onRevoke = { id, device -> nav.navigate(Routes.revoke(id, device)) },
                onRevokeAlert = { id, device -> nav.navigate(Routes.revokeFromAlert(id, device)) },
                onSignOutAll = { nav.navigate(Routes.SEC_SIGNOUT_ALL) },
            )
        }
        composable(Routes.SEC_REVOKE) { entry ->
            val id = entry.arguments?.getString("id")?.let(android.net.Uri::decode) ?: ""
            val device = entry.arguments?.getString("device")?.let(android.net.Uri::decode) ?: ""
            RevokeSessionScreen(
                sessionId = id,
                device = device,
                onBack = { nav.popBackStack() },
                onRevoke = { label ->
                    nav.navigate(Routes.revoked(label)) { popUpTo(Routes.SEC_SESSIONS) }
                },
            )
        }
        composable(Routes.SEC_REVOKED) { entry ->
            val device = entry.arguments?.getString("device")?.let(android.net.Uri::decode) ?: ""
            SessionRevokedScreen(
                device = device,
                onDone = { nav.navigate(Routes.SECURITY) { popUpTo(Routes.SECURITY) { inclusive = true } } },
            )
        }
        composable(Routes.SEC_REVOKE_ALERT) { entry ->
            val id = entry.arguments?.getString("id")?.let(android.net.Uri::decode) ?: ""
            val device = entry.arguments?.getString("device")?.let(android.net.Uri::decode) ?: ""
            ConfirmSessionRevocationScreen(
                sessionId = id,
                device = device,
                onBack = { nav.popBackStack() },
                onRevoke = { label ->
                    nav.navigate(Routes.revokedFromAlert(label)) { popUpTo(Routes.SEC_SESSIONS) }
                },
            )
        }
        composable(Routes.SEC_REVOKED_ALERT) { entry ->
            val device = entry.arguments?.getString("device")?.let(android.net.Uri::decode) ?: ""
            SessionRevokedSuspiciousScreen(
                device = device,
                onDone = { nav.navigate(Routes.SECURITY) { popUpTo(Routes.SECURITY) { inclusive = true } } },
            )
        }
        composable(Routes.SEC_ACTIVITY) {
            SecurityActivityScreen(
                onBack = { nav.popBackStack() },
                onOpenAlert = { nav.navigate(Routes.SEC_ALERT) },
                onOpenSessions = { nav.navigate(Routes.SEC_SESSIONS) },
            )
        }
        composable(Routes.SEC_ALERT) {
            SuspiciousSignInAlertScreen(
                onBack = { nav.popBackStack() },
                onSecure = { nav.navigate(Routes.SEC_SECURE) },
                onThisWasMe = { nav.popBackStack() },
            )
        }
        composable(Routes.SEC_SECURE) {
            SecureMyAccountScreen(
                onBack = { nav.popBackStack() },
                onChangePassword = { nav.navigate(Routes.SEC_PASSWORD_AFTER) },
                onBackToActivity = { nav.navigate(Routes.SEC_ACTIVITY) { popUpTo(Routes.SEC_ACTIVITY) } },
            )
        }
        composable(Routes.STEP_NAME) {
            NameStepScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onSignIn = { nav.navigate(Routes.SIGN_IN) },
                onNext = {
                    vm.update { copy(step = 2) }
                    nav.navigate(Routes.STEP_INFO)
                },
            )
        }
        composable(Routes.STEP_INFO) {
            BasicInfoStepScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onSignIn = { nav.navigate(Routes.SIGN_IN) },
                onNext = {
                    vm.update { copy(step = 3) }
                    nav.navigate(Routes.STEP_ADDRESS)
                },
            )
        }
        composable(Routes.STEP_ADDRESS) {
            AddressStepScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onSignIn = { nav.navigate(Routes.SIGN_IN) },
                onNext = {
                    vm.update { copy(step = 4) }
                    nav.navigate(Routes.STEP_PASSWORD)
                },
            )
        }
        composable(Routes.STEP_PASSWORD) {
            PasswordStepScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onSignIn = { nav.navigate(Routes.SIGN_IN) },
                onNext = {
                    vm.update { copy(step = 5) }
                    nav.navigate(Routes.STEP_TERMS)
                },
            )
        }
        composable(Routes.STEP_TERMS) {
            TermsStepScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onSignIn = { nav.navigate(Routes.SIGN_IN) },
                onCreated = { address, name ->
                    autoSignIn(address, name) { signedIn ->
                        if (signedIn) {
                            nav.navigate(Routes.HUMAN_VERIFY) { popUpTo(Routes.STEP_NAME) { inclusive = true } }
                        } else {
                            // Account exists, session does not. Land on sign-in with
                            // the address filled in rather than in a flow that has no
                            // token to use.
                            flow.update { copy(prefillIdentifier = address) }
                            nav.navigate(Routes.SIGN_IN) { popUpTo(Routes.STEP_NAME) { inclusive = true } }
                        }
                    }
                },
            )
        }
        composable(Routes.HUMAN_VERIFY) {
            HumanVerifyScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onDone = {
                    val s = vm.state.value
                    val address = s.createdAddress ?: ""
                    val name = s.createdName ?: s.firstName
                    nav.navigate(Routes.created(address, name)) { popUpTo(Routes.HUMAN_VERIFY) { inclusive = true } }
                },
            )
        }
        composable(Routes.CREATED) { entry ->
            val address = entry.arguments?.getString("address")?.let(android.net.Uri::decode) ?: ""
            val name = entry.arguments?.getString("name")?.let(android.net.Uri::decode) ?: ""
            AccountCreatedScreen(
                address = address,
                name = name,
                onContinueOnboarding = { nav.navigate(Routes.createdAlt(address, name)) },
            )
        }
        composable(Routes.CREATED_ALT) { entry ->
            val address = entry.arguments?.getString("address")?.let(android.net.Uri::decode) ?: ""
            val name = entry.arguments?.getString("name")?.let(android.net.Uri::decode) ?: ""
            AccountCreatedAltScreen(
                address = address,
                name = name,
                onGoToMailbox = { nav.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) } },
                onContinueOnboarding = { nav.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) } },
            )
        }
        composable(Routes.HOME) {
            MailboxScreen(
                vm = mail,
                onOpenThread = { id, isThread ->
                    nav.navigate(Routes.mailThread(id, isThread))
                },
                onCompose = { nav.navigate(Routes.MAIL_COMPOSE) },
                // A draft opens in the composer, not the reader: the row is
                // the person's own unfinished mail, and the only useful tap
                // is the one that lets them finish it.
                onOpenDraft = { id -> nav.navigate(Routes.mailCompose(id, "draft")) },
                onSettings = { nav.navigate(Routes.MAIL_SETTINGS) },
                onSecurity = { nav.navigate(Routes.SECURITY) },
                onHelp = { openSupport() },
                onLabels = { nav.navigate(Routes.MAIL_LABELS) },
                onContacts = { nav.navigate(Routes.MAIL_CONTACTS) },
                onFiles = { nav.navigate(Routes.MAIL_FILES) },
                onScreener = { nav.navigate(Routes.MAIL_SCREENER) },
                onSubscriptions = { nav.navigate(Routes.MAIL_SUBSCRIPTIONS) },
                onSignOut = {
                    // The unlocked E2E halves drop HERE, visibly at the door,
                    // not only inside the shared teardown: a sign-out that
                    // leaves the key material reachable in memory is the
                    // lifetime defect the §-pin counts at every door.
                    e2e.clearForSignOut()
                    leaveSignedInState()
                    nav.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.MAIL_THREAD) { entry ->
            val id = entry.arguments?.getString("id")?.let(android.net.Uri::decode) ?: ""
            // A row that has no thread id is opened through the message route;
            // the thread route answers a message id with a 404, and a tap that
            // opens nothing is the bug the flag exists to prevent.
            val isThread = entry.arguments?.getString("thread") != "0"
            ThreadScreen(
                vm = mail,
                e2e = e2e,
                threadId = id,
                isThread = isThread,
                onBack = { nav.popBackStack() },
                onReply = { m, mode -> nav.navigate(Routes.mailCompose(m.id, mode)) },
                // Stepping replaces the reader rather than pushing another
                // copy of it: five nexts must not bury the list under five
                // identical screens the back button has to unwind one at a
                // time. The target's own flag decides the route, exactly as the
                // list's tap does — a neighbour that is a single message would
                // otherwise be opened on the thread route and 404.
                onStepPrev = { step ->
                    nav.navigate(Routes.mailThread(step.id, step.isThread)) {
                        popUpTo(Routes.MAIL_THREAD) { inclusive = true }
                    }
                },
                onStepNext = { step ->
                    nav.navigate(Routes.mailThread(step.id, step.isThread)) {
                        popUpTo(Routes.MAIL_THREAD) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.MAIL_COMPOSE) { entry ->
            val reply = entry.arguments?.getString("reply")?.let(android.net.Uri::decode) ?: ""
            val mode = entry.arguments?.getString("mode") ?: "reply"
            ComposeScreen(vm = mail, profile = profile, e2e = e2e, replyId = reply, mode = mode,
                onBack = { nav.popBackStack() })
        }
        composable(Routes.MAIL_LABELS) {
            LabelsScreen(
                vm = mail,
                onBack = { nav.popBackStack() },
                onCreate = { nav.navigate(Routes.labelCreate("")) },
                onEdit = { nav.navigate(Routes.labelCreate(it.id)) },
            )
        }
        composable(Routes.MAIL_LABEL_CREATE) { entry ->
            val id = entry.arguments?.getString("id")?.let(android.net.Uri::decode) ?: ""
            // The row that opened this screen owns the label. If the list has not
            // loaded it, the screen opens as a new one rather than inventing a
            // name or a colour to show.
            val editing = if (id.isEmpty()) null
                else mail.state.value.labels.firstOrNull { it.id == id }
            LabelCreateScreen(vm = mail, onDone = { nav.popBackStack() }, editing = editing)
        }
        composable(Routes.MAIL_CONTACTS) {
            ContactsScreen(
                vm = mail,
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate(Routes.mailContact(it)) },
            )
        }
        composable(Routes.MAIL_CONTACT) { entry ->
            val address = entry.arguments?.getString("address")?.let(android.net.Uri::decode) ?: ""
            ContactDetailScreen(
                vm = mail,
                address = address,
                onBack = { nav.popBackStack() },
                onWrite = { nav.navigate(Routes.MAIL_COMPOSE) },
            )
        }
        composable(Routes.MAIL_FILES) {
            FilesScreen(
                vm = mail,
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate(Routes.mailAttachment(it)) },
            )
        }
        composable(Routes.MAIL_ATTACHMENT) { entry ->
            val id = entry.arguments?.getString("id")?.let(android.net.Uri::decode) ?: ""
            AttachmentPreviewScreen(vm = mail, attachmentId = id, onBack = { nav.popBackStack() })
        }
        composable(Routes.MAIL_SETTINGS) {
            MailSettingsScreen(
                vm = mail,
                onBack = { nav.popBackStack() },
                onSecurity = { nav.navigate(Routes.SECURITY) },
                onScreener = { nav.navigate(Routes.MAIL_SCREENER) },
                onSubscriptions = { nav.navigate(Routes.MAIL_SUBSCRIPTIONS) },
                onLabels = { nav.navigate(Routes.MAIL_LABELS) },
                onNotes = { nav.navigate(Routes.MAIL_NOTES) },
                onTasks = { nav.navigate(Routes.MAIL_TASKS) },
                onPrefs = { nav.navigate(Routes.MAIL_PREFS) },
                onAddresses = { nav.navigate(Routes.MAIL_ADDRESSES) },
                onConnections = { nav.navigate(Routes.MAIL_CONNECTIONS) },
                onProfile = { nav.navigate(Routes.ACCOUNT_PROFILE) },
                onPrivacy = { nav.navigate(Routes.ACCOUNT_PRIVACY) },
                onEncryption = { nav.navigate(Routes.E2E_STATUS) },
                onHelp = { nav.navigate(Routes.MAIL_SUPPORT) },
                onSignOut = {
                    // Same visible-at-the-door drop as the drawer's sign-out.
                    e2e.clearForSignOut()
                    leaveSignedInState()
                    nav.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.MAIL_NOTES) {
            com.grapsee.grapmail.ui.screens.mail.NotesScreen(vm = profile, onBack = { nav.popBackStack() })
        }
        composable(Routes.MAIL_TASKS) {
            com.grapsee.grapmail.ui.screens.mail.TasksScreen(vm = profile, onBack = { nav.popBackStack() })
        }
        composable(Routes.MAIL_PREFS) {
            com.grapsee.grapmail.ui.screens.mail.MailPrefsScreen(vm = profile, onBack = { nav.popBackStack() })
        }
        composable(Routes.MAIL_ADDRESSES) {
            com.grapsee.grapmail.ui.screens.mail.AddressesScreen(vm = profile, onBack = { nav.popBackStack() })
        }
        composable(Routes.MAIL_CONNECTIONS) {
            com.grapsee.grapmail.ui.screens.mail.ConnectionsScreen(
                vm = profile, onBack = { nav.popBackStack() },
                onOpen = { nav.navigate("mail/settings/connections/${android.net.Uri.encode(it)}") },
            )
        }
        // The web links the same detail from its list; the product rides as an
        // encoded path segment so the screen is a real destination with a real
        // back stack, and an unknown name opens the honest "not connected"
        // answer rather than a redirect.
        composable(Routes.MAIL_CONNECTION_DETAIL) { entry ->
            val product = entry.arguments?.getString("product")?.let(android.net.Uri::decode) ?: ""
            com.grapsee.grapmail.ui.screens.mail.ConnectionDetailScreen(
                vm = profile, product = product,
                onBack = { nav.popBackStack() },
                onSupport = { nav.navigate(Routes.MAIL_SUPPORT) },
            )
        }
        composable(Routes.ACCOUNT_PROFILE) {
            com.grapsee.grapmail.ui.screens.mail.ProfileEditScreen(vm = profile, onBack = { nav.popBackStack() })
        }
        composable(Routes.ACCOUNT_PRIVACY) {
            com.grapsee.grapmail.ui.screens.mail.PrivacyScreen(
                vm = profile,
                onBack = { nav.popBackStack() },
                onProfile = { nav.navigate(Routes.ACCOUNT_PROFILE) },
                onPhone = { nav.navigate(Routes.PHONE_VERIFY) },
                onAddresses = { nav.navigate(Routes.MAIL_ADDRESSES) },
                onPrefs = { nav.navigate(Routes.MAIL_PREFS) },
                onDeleteAccount = { nav.navigate(Routes.SEC_DELETE_ACCOUNT) },
                onSupport = { nav.navigate(Routes.MAIL_SUPPORT) },
            )
        }
        composable(Routes.MAIL_SUPPORT) {
            com.grapsee.grapmail.ui.screens.mail.SupportScreen(
                vm = profile,
                onBack = { nav.popBackStack() },
                onForgot = { nav.navigate(Routes.FORGOT_PASSWORD) },
                onPhone = { nav.navigate(Routes.PHONE_VERIFY) },
                onAddresses = { nav.navigate(Routes.MAIL_ADDRESSES) },
            )
        }
        // ── encryption band destinations ──────────────────────────────────
        composable(Routes.E2E_STATUS) {
            com.grapsee.grapmail.ui.screens.mail.E2eStatusScreen(
                vm = e2e, onBack = { nav.popBackStack() },
                onSetup = { nav.navigate(Routes.E2E_SETUP) },
                onUnlock = { nav.navigate(Routes.E2E_UNLOCK) },
                onDetails = { nav.navigate(Routes.E2E_DETAILS) },
                onPublish = { nav.navigate(Routes.E2E_PUBLISH) },
                onChangePassphrase = { nav.navigate(Routes.E2E_PASSPHRASE) },
                onRemove = { nav.navigate(Routes.E2E_REMOVE) },
                onRecipients = { nav.navigate(Routes.E2E_RECIPIENTS) },
            )
        }
        composable(Routes.E2E_SETUP) {
            com.grapsee.grapmail.ui.screens.mail.E2eSetupScreen(vm = e2e,
                onBack = { nav.popBackStack() })
        }
        composable(Routes.E2E_UNLOCK) {
            com.grapsee.grapmail.ui.screens.mail.E2eUnlockScreen(vm = e2e,
                onBack = { nav.popBackStack() })
        }
        composable(Routes.E2E_DETAILS) {
            com.grapsee.grapmail.ui.screens.mail.E2eDetailsScreen(vm = e2e,
                onBack = { nav.popBackStack() })
        }
        composable(Routes.E2E_PUBLISH) {
            com.grapsee.grapmail.ui.screens.mail.E2ePublishScreen(vm = e2e,
                onBack = { nav.popBackStack() })
        }
        composable(Routes.E2E_PASSPHRASE) {
            com.grapsee.grapmail.ui.screens.mail.E2ePassphraseScreen(vm = e2e,
                onBack = { nav.popBackStack() })
        }
        composable(Routes.E2E_REMOVE) {
            com.grapsee.grapmail.ui.screens.mail.E2eRemoveScreen(vm = e2e,
                onBack = { nav.popBackStack() })
        }
        composable(Routes.E2E_RECIPIENTS) {
            com.grapsee.grapmail.ui.screens.mail.E2eRecipientsScreen(vm = e2e,
                onBack = { nav.popBackStack() },
                onCompose = { nav.navigate(Routes.MAIL_COMPOSE) })
        }
        composable(Routes.MAIL_SCREENER) {
            ScreenerScreen(
                vm = mail,
                onBack = { nav.popBackStack() },
                onOpenQueue = {
                    mail.open(MailBox.Screener)
                    nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) }
                },
            )
        }
        // The subscriptions band sits with the other doors off Settings and
        // the drawer: one screen over the mail this device has loaded, no
        // state of its own the mailbox does not already hold.
        composable(Routes.MAIL_SUBSCRIPTIONS) {
            SubscriptionsScreen(vm = mail, onBack = { nav.popBackStack() })
        }
    }
}
