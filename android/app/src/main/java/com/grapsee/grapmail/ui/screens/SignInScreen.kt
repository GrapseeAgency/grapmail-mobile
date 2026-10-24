package com.grapsee.grapmail.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.GrapMailLogo
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.theme.H2
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * Sign-in — founder design verbatim. Posts to the real /auth/account/signin:
 * address or phone + password. The 2-step branch (requireOtp) is handled
 * inline in the same design language, because real accounts may have the
 * factor on and the app must never fake its way past it.
 */
@Composable
fun SignInScreen(onSignedIn: (address: String) -> Unit, onBack: (() -> Unit)? = null, onSignUp: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var identifier by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // 2-step state
    var challengeId by rememberSaveable { mutableStateOf<String?>(null) }
    var otpCode by rememberSaveable { mutableStateOf("") }
    var otpBusy by remember { mutableStateOf(false) }

    fun finish(token: String?, address: String, name: String?, expires: Long?) {
        if (token != null) {
            SessionStore.save(context, token, address, name ?: "", expires ?: 0L)
            onSignedIn(address)
        } else {
            error = "Sign-in succeeded but no session was returned. Please try again."
        }
    }

    fun submit() {
        if (identifier.isBlank() || password.isBlank()) return
        busy = true; error = null
        scope.launch {
            try {
                val resp = Api.signIn(identifier.trim(), password)
                busy = false
                if (resp.requireOtp && resp.challengeId != null) {
                    challengeId = resp.challengeId
                } else {
                    val token = resp.sessionToken ?: resp.accessToken ?: resp.token
                    finish(token, resp.account?.address ?: identifier.trim(), resp.account?.name, resp.expiresAt)
                }
            } catch (e: ApiException) {
                busy = false
                error = when (e.code) {
                    "TOO_MANY_ATTEMPTS" -> "Too many failed attempts. Try again in about 15 minutes."
                    else -> e.message ?: "Those details do not match an account."
                }
            } catch (_: Exception) {
                busy = false
                error = "Network error. Please try again."
            }
        }
    }

    fun submitOtp() {
        val cid = challengeId ?: return
        otpBusy = true; error = null
        scope.launch {
            try {
                val resp = Api.twoFactorCode(cid, otpCode.trim())
                otpBusy = false
                val token = resp.sessionToken ?: resp.accessToken ?: resp.token
                finish(token, resp.user?.address ?: identifier.trim(), resp.user?.name, resp.expiresAt)
            } catch (e: ApiException) {
                otpBusy = false
                error = e.message ?: "That code is not correct."
            } catch (_: Exception) {
                otpBusy = false
                error = "Network error. Please try again."
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bgTinted)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        GrapMailLogo(44.dp, wordmark = true)
        Spacer(Modifier.height(28.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            if (challengeId == null) {
                Text("Welcome back 👋", style = H2, color = palette.textPrimary)
                Spacer(Modifier.height(6.dp))
                Text("Sign in to continue to GrapMail", fontSize = 14.sp, color = palette.textSecondary)
                Spacer(Modifier.height(30.dp))

                AppTextField(
                    label = "Email or phone number",
                    value = identifier,
                    onValueChange = { identifier = it },
                    keyboard = KeyboardType.Email,
                )
                Spacer(Modifier.height(16.dp))
                AppTextField(
                    label = "Password",
                    value = password,
                    onValueChange = { password = it },
                    password = true,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Forgot password?",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.primarySolid,
                )
                if (error != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(error ?: "", fontSize = 13.sp, color = palette.error)
                }
                Spacer(Modifier.height(26.dp))
                GradientButton(
                    "Sign In",
                    onClick = { submit() },
                    enabled = identifier.isNotBlank() && password.isNotBlank(),
                    busy = busy,
                )
                Spacer(Modifier.height(26.dp))
                Text(
                    "Don't have an account?",
                    fontSize = 13.sp,
                    color = palette.textSecondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Text(
                    "Create one",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.primarySolid,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 2.dp)
                        .clickable { onSignUp() },
                )
                Spacer(Modifier.height(30.dp))
            } else {
                Text("2-step verification", style = H2, color = palette.textPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Enter the 6-digit code sent to your verified number.",
                    fontSize = 14.sp, color = palette.textSecondary,
                )
                Spacer(Modifier.height(30.dp))
                AppTextField(
                    label = "6-digit code",
                    value = otpCode,
                    onValueChange = { otpCode = it.filter(Char::isDigit).take(6) },
                    keyboard = KeyboardType.NumberPassword,
                )
                if (error != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(error ?: "", fontSize = 13.sp, color = palette.error)
                }
                Spacer(Modifier.height(26.dp))
                GradientButton(
                    "Verify",
                    onClick = { submitOtp() },
                    enabled = otpCode.length == 6,
                    busy = otpBusy,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Use a different account",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.primarySolid,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}
