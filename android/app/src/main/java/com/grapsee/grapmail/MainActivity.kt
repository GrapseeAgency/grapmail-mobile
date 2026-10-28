package com.grapsee.grapmail

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.navigation.compose.rememberNavController
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.logic.AccountFlowViewModel
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.signedInHome
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                    Modifier.fillMaxSize().background(bg),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                            .widthIn(max = 520.dp)
                    ) { GrapMailNav() }
                }
            }
        }
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
    const val SEC_SESSIONS = "security/sessions"
    const val SEC_REVOKE = "security/sessions/revoke/{id}"
    const val SEC_REVOKED = "security/sessions/revoked/{device}"
    const val SEC_REVOKE_ALERT = "security/alert/revoke/{id}"
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

    fun created(address: String, name: String) = "signup/created/${encode(address)}/${encode(name)}"
    fun createdAlt(address: String, name: String) = "signup/created-alt/${encode(address)}/${encode(name)}"
    fun revoke(id: String) = "security/sessions/revoke/${encode(id)}"
    fun revoked(device: String) = "security/sessions/revoked/${encode(device)}"
    fun revokeFromAlert(id: String) = "security/alert/revoke/${encode(id)}"
    fun revokedFromAlert(device: String) = "security/alert/revoked/${encode(device)}"

    /** The help link the footer notes carry. Opens the real support page. */
    const val SUPPORT_URL = "https://grapsee.com/contact"

    private fun encode(v: String) = android.net.Uri.encode(v)
}

@Composable
fun GrapMailNav() {
    val nav = rememberNavController()
    val vm: SignUpViewModel = viewModel()
    val flow: AccountFlowViewModel = viewModel()
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

    // A stored, unexpired session opens the app signed-in — the encrypted
    // token has to be read at launch, not just written.
    var startDest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val ctx = nav.context.applicationContext
        val valid = SessionStore.token(ctx) != null && SessionStore.hasValidSession(ctx)
        startDest = if (valid) Routes.HOME else Routes.WELCOME
    }
    val dest = startDest ?: return

    // The real auto sign-in the website performs the moment the account
    // exists: same endpoint, same session storage. The result is reported to the
    // caller because a failure here must NOT walk the person into screens that
    // assume a token — every one of them would answer "sign in again" on a
    // session that was never written.
    fun autoSignIn(address: String, name: String, then: (Boolean) -> Unit) {
        scope.launch(Dispatchers.Main) {
            var signedIn = false
            try {
                val resp = Api.signIn(address, vm.state.value.password)
                val token = resp.sessionToken ?: resp.accessToken ?: resp.token
                if (token != null) {
                    SessionStore.save(
                        nav.context.applicationContext,
                        token, address, name, resp.expiresAt ?: 0L,
                    )
                    signedIn = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // The account exists; signing in by hand is the fallback.
            }
            then(signedIn)
        }
    }

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
                onSignedIn = { nav.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) } },
                onBack = { nav.popBackStack() },
                onSignUp = { nav.navigate(Routes.STEP_NAME) },
                onForgotPassword = { nav.navigate(Routes.FORGOT_PASSWORD) },
                onNeedSecondFactor = { nav.navigate(Routes.TWO_FACTOR) },
            )
        }
        composable(Routes.TWO_FACTOR) {
            TwoFactorScreen(
                flow = flow,
                onSignedIn = { nav.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) } },
                onBack = { nav.popBackStack() },
                onBackToSignIn = { backToSignIn() },
                onUseBackupCode = { nav.navigate(Routes.BACKUP_CODE) },
            )
        }
        composable(Routes.BACKUP_CODE) {
            BackupCodeScreen(
                flow = flow,
                onSignedIn = { nav.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) } },
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
        composable(Routes.SEC_SIGNOUT_ALL_DONE) {
            SignedOutAllDevicesScreen(
                onDone = { nav.navigate(Routes.SECURITY) { popUpTo(Routes.SECURITY) { inclusive = true } } },
            )
        }
        composable(Routes.SEC_SESSIONS) {
            ManageSessionsScreen(
                onBack = { nav.popBackStack() },
                onRevoke = { id -> nav.navigate(Routes.revoke(id)) },
                onRevokeAlert = { id -> nav.navigate(Routes.revokeFromAlert(id)) },
                onSignOutAll = { nav.navigate(Routes.SEC_SIGNOUT_ALL) },
            )
        }
        composable(Routes.SEC_REVOKE) { entry ->
            val id = entry.arguments?.getString("id")?.let(android.net.Uri::decode) ?: ""
            RevokeSessionScreen(
                sessionId = id,
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
            ConfirmSessionRevocationScreen(
                sessionId = id,
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
            signedInHome(
                onSignOut = {
                    // The token leaves, and so does everything the security sector
                    // was holding in memory: ten plaintext recovery codes and a live
                    // action proof must not outlive the person they belong to.
                    com.grapsee.grapmail.ui.screens.security.SecurityFlow.reset()
                    scope.launch { SessionStore.clear(nav.context.applicationContext) }
                    nav.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                },
                onAccountSecurity = { nav.navigate(Routes.SECURITY) },
            )
        }
    }
}
