package com.grapsee.grapmail

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.signedInHome
import com.grapsee.grapmail.ui.screens.AccountCreatedAltScreen
import com.grapsee.grapmail.ui.screens.AccountCreatedScreen
import com.grapsee.grapmail.ui.screens.AddressStepScreen
import com.grapsee.grapmail.ui.screens.BasicInfoStepScreen
import com.grapsee.grapmail.ui.screens.HumanVerifyScreen
import com.grapsee.grapmail.ui.screens.NameStepScreen
import com.grapsee.grapmail.ui.screens.PasswordStepScreen
import com.grapsee.grapmail.ui.screens.SignInScreen
import com.grapsee.grapmail.ui.screens.TermsStepScreen
import com.grapsee.grapmail.ui.screens.WelcomeScreen
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
                val bg = com.grapsee.grapmail.ui.theme.LocalPalette.current.bg
                Box(Modifier.fillMaxSize().background(bg)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .widthIn(max = 520.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally)
                    ) { GrapMailNav() }
                }
            }
        }
    }
}

object Routes {
    const val WELCOME = "welcome"
    const val SIGN_IN = "signin"
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

    private fun encode(v: String) = android.net.Uri.encode(v)
}

@Composable
fun GrapMailNav() {
    val nav = rememberNavController()
    val vm: SignUpViewModel = viewModel()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // The real auto sign-in the website performs the moment the account
    // exists: same endpoint, same session storage.
    fun autoSignIn(address: String, name: String, then: () -> Unit) {
        scope.launch(Dispatchers.Main) {
            try {
                val resp = Api.signIn(address, vm.state.value.password)
                val token = resp.sessionToken ?: resp.accessToken ?: resp.token
                if (token != null) {
                    SessionStore.save(
                        nav.context.applicationContext,
                        token, address, name, resp.expiresAt ?: 0L,
                    )
                }
            } catch (_: Exception) { /* account exists; sign-in can be done by hand */ }
            then()
        }
    }

    NavHost(navController = nav, startDestination = Routes.WELCOME) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onCreateAccount = { nav.navigate(Routes.STEP_NAME) },
                onSignIn = { nav.navigate(Routes.SIGN_IN) },
            )
        }
        composable(Routes.SIGN_IN) {
            SignInScreen(
                onSignedIn = { nav.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) } },
                onSignUp = { nav.navigate(Routes.STEP_NAME) },
            )
        }
        composable(Routes.STEP_NAME) {
            NameStepScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
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
                onCreated = { address, name ->
                    autoSignIn(address, name) {
                        nav.navigate(Routes.HUMAN_VERIFY) { popUpTo(Routes.STEP_NAME) { inclusive = true } }
                    }
                },
            )
        }
        composable(Routes.HUMAN_VERIFY) {
            HumanVerifyScreen(
                vm = vm,
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
                    SessionStore.clear(nav.context.applicationContext)
                    nav.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                },
            )
        }
    }
}
