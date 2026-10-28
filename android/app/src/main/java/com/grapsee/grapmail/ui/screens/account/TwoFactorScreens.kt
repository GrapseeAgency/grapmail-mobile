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
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.OrDivider
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * Two-step verification — founder design verbatim: shield artwork, the
 * TWO-STEP VERIFICATION eyebrow, six code cells, the expiry countdown, the
 * arrowed CTA, Resend code, OR, Back to sign in.
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
    val state by flow.state.collectAsState()

    var code by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resending by remember { mutableStateOf(false) }

    fun verify() {
        val challenge = state.challengeId ?: return
        busy = true; error = null; notice = null
        scope.launch {
            try {
                val resp = Api.twoFactorCode(challenge, code)
                busy = false
                val token = resp.sessionToken ?: resp.accessToken ?: resp.token
                if (token == null) {
                    error = "That code is not correct."
                    return@launch
                }
                SessionStore.save(
                    context, token,
                    resp.user?.address ?: state.identifier,
                    resp.user?.name ?: "",
                    resp.expiresAt ?: 0L,
                )
                onSignedIn(resp.user?.address ?: state.identifier)
            } catch (e: ApiException) {
                busy = false
                error = when (e.code) {
                    "CHALLENGE_EXPIRED" -> "That code has expired. Resend it to get a new one."
                    "BAD_CODE" -> "That code is not correct."
                    else -> e.message ?: "That code is not correct."
                }
            } catch (_: Exception) {
                busy = false
                error = "Network error. Please try again."
            }
        }
    }

    // Resending means signing in again: the server issues a fresh challenge and
    // texts a new code, and the old one stops working. The credentials are
    // still in the flow, in memory only.
    fun resend() {
        if (state.identifier.isBlank() || state.password.isBlank()) return
        resending = true; error = null; notice = null
        scope.launch {
            try {
                val resp = Api.signIn(state.identifier, state.password)
                resending = false
                if (resp.requireOtp && resp.challengeId != null) {
                    code = ""
                    flow.update { copy(challengeId = resp.challengeId, expiresAt = resp.expiresAt ?: 0L) }
                    notice = "A new code is on its way."
                } else {
                    error = "That sign-in no longer needs a code. Start again."
                }
            } catch (e: ApiException) {
                resending = false
                error = e.message ?: "Could not resend the code. Try again."
            } catch (_: Exception) {
                resending = false
                error = "Network error. Please try again."
            }
        }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "account-2fa",
        eyebrow = "Two-step verification",
        title = "Enter your 6-digit code",
        body = "We've sent a verification code to your GrapMail account. Please enter the 6-digit code to continue.",
        footer = {
            GradientButton(
                "Verify & Continue",
                onClick = { verify() },
                enabled = code.length == 6,
                busy = busy,
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                AccountLink(if (resending) "Sending…" else "Resend code", onClick = { resend() })
            }
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                AccountLink("Use a backup code", onClick = onUseBackupCode)
            }
            Spacer(Modifier.height(12.dp))
            OrDivider()
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                AccountLink("Back to sign in", onClick = onBackToSignIn)
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
    val state by flow.state.collectAsState()

    var code by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (state.identifier.isBlank() || state.password.isBlank()) {
            error = "Sign in again to use a backup code."
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
                if (token == null) {
                    error = "That backup code is not valid, or it has already been used."
                    return@launch
                }
                SessionStore.save(
                    context, token,
                    resp.user?.address ?: state.identifier,
                    resp.user?.name ?: "",
                    resp.expiresAt ?: 0L,
                )
                onSignedIn(resp.user?.address ?: state.identifier)
            } catch (e: ApiException) {
                busy = false
                error = when (e.code) {
                    "BAD_BACKUP_CODE" -> "That backup code is not valid, or it has already been used."
                    "TWO_FACTOR_NOT_ENABLED" -> "This account does not use two-step verification."
                    else -> e.message ?: "That backup code is not valid."
                }
            } catch (_: Exception) {
                busy = false
                error = "Network error. Please try again."
            }
        }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "account-backup",
        eyebrow = "Use a backup code",
        title = "Enter your backup code",
        body = "If you don't have access to your 2-step verification method, you can use a backup code to get back into your account.",
        footer = {
            GradientButton(
                "Continue",
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
                AccountLink("Try another method", onClick = onTryAnotherMethod)
            }
            Spacer(Modifier.height(12.dp))
            OrDivider()
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                AccountLink("Back to sign in", onClick = onBackToSignIn)
            }
            Spacer(Modifier.height(6.dp))
        },
    ) {
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
                "Backup codes are 10 characters long and can only be used once.",
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
