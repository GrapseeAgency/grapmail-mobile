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
          