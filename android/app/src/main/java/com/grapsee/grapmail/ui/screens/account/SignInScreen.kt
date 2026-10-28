package com.grapsee.grapmail.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.grapsee.grapmail.logic.AccountFlowViewModel
import com.grapsee.grapmail.ui.components.AccountHeader
import com.grapsee.grapmail.ui.components.AccountLink
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.DecorativeBlobs
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.OrDivider
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * Sign in — founder design verbatim: wordmark header, "Welcome back 👋", the
 * bordered card holding both fields, the reset link, the gradient CTA, OR, and
 * the create-account line.
 *
 * The second factor is its own designed screen now, so a challenged sign-in
 * hands the challenge to [onNeedSecondFactor] instead of unfolding in place.
 */
@Composable
fun SignInScreen(
    flow: AccountFlowViewModel,
    onSignedIn: (address: String) -> Unit,
    onBack: (() -> Unit)? = null,
    onSignUp: () -> Unit,
    onForgotPassword: () -> Unit,
    onNeedSecondFactor: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flowState by flow.state.collectAsState()

    var identifier by rememberSaveable { mutableStateOf(flowState.prefillIdentifier ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // A finished reset or a found address hands the person back here with the
    // address already in the field.
    LaunchedEffect(flowState.prefillIdentifier) {
        flowState.prefillIdentifier?.let { identifier = it }
    }

    fun submit() {
        if (identifier.isBlank() || password.isBlank()) return
        busy = true; error = null
        scope.launch {
            try {
                val resp = Api.signIn(identifier.trim(), password)
                busy = false
                if (resp.requireOtp && resp.challengeId != null) {
                    flow.update {
                        copy(
                            identifier = identifier.trim(),
                            password = password,
                            challengeId = resp.challengeId,
                            expiresAt = resp.expiresAt ?: 0L,
                            prefillIdentifier = null,
                        )
                    }
                    onNeedSecondFactor()
                } else {
                    val token = resp.sessionToken ?: resp.accessToken ?: resp.token
                    if (token == null) {
                        error = "Sign-in succeeded but no session was returned. Please try again."
                        return@launch
                    }
                    SessionStore.save(
                        context, token,
                        resp.account?.address ?: identifier.trim(),
                        resp.account?.name ?: "",
                        resp.expiresAt ?: 0L,
                    )
                    onSignedIn(resp.account?.address ?: identifier.trim())
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

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        Box(Modifier.align(Alignment.TopEnd)) { DecorativeBlobs() }
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            AccountHeader(onBack)
            Spacer(Modifier.height(6.dp))
            Text(
                "Welcome back \uD83D\uDC4B",
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                color = palette.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Sign in to your GrapMail account and get back to what matters.",
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
                    label = "Email address or phone number",
                    value = identifier,
                    onValueChange = { identifier = it },
                    hint = "you@grapsee.com or +8801XXXXXXXX",
                    keyboard = KeyboardType.Email,
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
                    label = "Password",
                    value = password,
                    onValueChange = { password = it },
                    hint = "Enter your password",
                    password = true,
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
                    AccountLink("Forgot password?", onClick = onForgotPassword)
                }
            }

            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(error ?: "", fontSize = 13.sp, color = palette.error)
            }

            Spacer(Modifier.height(18.dp))
            GradientButton(
                "Sign In",
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Don't have an account?  ", fontSize = 13.sp, color = palette.textSecondary)
                AccountLink("Create one", onClick = onSignUp)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
