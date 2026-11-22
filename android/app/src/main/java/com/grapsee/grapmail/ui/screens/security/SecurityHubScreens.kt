package com.grapsee.grapmail.ui.screens.security

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.HeadsetMic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Textsms
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ProfileDto
import com.grapsee.grapmail.data.SecurityProfileCache
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.data.TrustedDeviceDto
import com.grapsee.grapmail.security.TrustedDeviceManager
import com.grapsee.grapmail.ui.components.AccountLink
import com.grapsee.grapmail.ui.components.AccountScreen
import com.grapsee.grapmail.ui.components.ChipTone
import com.grapsee.grapmail.ui.components.CodeCells
import com.grapsee.grapmail.ui.components.CodeExpiry
import com.grapsee.grapmail.ui.components.FooterNote
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.InfoCard
import com.grapsee.grapmail.ui.components.StatusChip
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 01 / 02 — the hub the whole sector hangs from.
 *
 * One screen for both references: 02 is the same page once everything is on, so
 * the copy changes with the state instead of the layout being forked. Every row
 * leads somewhere real; there is no tile that only looks like a button.
 */
@Composable
fun AccountSecurityScreen(
    onBack: () -> Unit,
    onTwoFactor: () -> Unit,
    onDisableTwoFactor: () -> Unit,
    onPhone: () -> Unit,
    onSessions: () -> Unit,
    onActivity: () -> Unit,
    onTrustedDevices: () -> Unit,
    onRecoveryCodes: () -> Unit,
    onNotificationPrefs: () -> Unit,
    onBackupCodes: () -> Unit,
    onPassword: () -> Unit,
    onSignOutAll: () -> Unit,
    onDeleteAccount: () -> Unit,
    onSupport: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<com.grapsee.grapmail.data.ProfileDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        val session = SessionStore.snapshot(context)
        if (session == null) {
            profile = null
            failed = true
            loading = false
            return@LaunchedEffect
        }
        val held = SecurityProfileCache.read(session.id)
        profile = held
        failed = false
        loading = held == null
        try {
            val fresh = Api.profile(session.token).profile
            if (!SessionStore.isCurrent(context, session.id)) return@LaunchedEffect
            if (fresh?.twoFactorState == null) {
                failed = held == null
            } else {
                SecurityProfileCache.publish(session.id, fresh)
                profile = fresh
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            if (SessionStore.isCurrent(context, session.id)) failed = held == null
        } finally {
            if (kotlinx.coroutines.currentCoroutineContext().isActive) loading = false
        }
    }

    val twoFactorOn = profile?.twoFactorState == true
    val hasPhone = !profile?.phone.isNullOrBlank()
    val phoneVerified = hasPhone && profile?.phoneVerified == true
    val secure = twoFactorOn && phoneVerified

    AccountScreen(
        onBack = onBack,
        illustration = if (secure) "ill_sec_hub_secure" else "ill_sec_hub",
        illustrationWidth = 120,
        title = "Account Security",
        body = "Manage your security settings to keep your account safe and secure.",
        footer = {
            FooterNote(
                text = "Your security matters. We're here to help.",
                divider = true,
            )
        },
    ) {
        // The reference puts the account's verdict above the feature list: it is
        // the answer to "am I safe?", and burying it under the switches makes
        // the person read six rows to learn one thing.
        InfoCard {
            when {
                loading -> Text("Checking your account…", fontSize = 13.sp, color = LocalPalette.current.textSecondary)
                failed -> {
                    Text("We couldn't load your security settings.", fontSize = 13.sp, color = LocalPalette.current.textSecondary)
                    Spacer(Modifier.height(4.dp))
                    AccountLink("Try again", { reload += 1 })
                }
                else -> {
                    StatusChip(
                        if (secure) "Your account is secure" else "One thing left to do",
                        if (secure) ChipTone.Success else ChipTone.Warning,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (secure) "All essential security features are active and your account is protected."
                        else "Turn on two-step verification to protect your account even if your password is ever exposed.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = LocalPalette.current.textSecondary,
                    )
                    // Two-step on with no codes is one lost phone away from a
                    // lock-out, and the badge above would still call it secure.
                    // Only say so when the server actually answered with a
                    // count: an absent field is unknown, not zero.
                    if (twoFactorOn && profile?.backupCodesRemaining == 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Add backup codes so losing your phone does not lock you out.",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = LocalPalette.current.warnInk,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionLabel("Security Features")
        // The reference shows a real on/off switch on the 2FA row, not just a
        // pill. Toggling routes into the flow that proves the change (password,
        // then code) rather than flipping a flag blind — the switch is the entry
        // point, not the write.
        ToggleRow(
            icon = Icons.Rounded.Security,
            title = "Two-Step Verification",
            subtitle = "Add an extra layer of security to your account with a verification code.",
            checked = twoFactorOn && !loading && !failed,
            enabled = !loading && !failed,
            chip = if (loading || failed) null else if (twoFactorOn) "Enabled" else "Off",
            chipTone = if (twoFactorOn) ChipTone.Success else ChipTone.Warning,
            onToggle = { if (twoFactorOn) onDisableTwoFactor() else onTwoFactor() },
        )
        RowDivider()
        SecurityRow(
            icon = Icons.Rounded.PhoneAndroid,
            title = "Phone Number",
            subtitle = profile?.phone ?: if (loading) null else "Used for account recovery and two-step verification.",
            chip = when {
                loading || failed -> null
                !hasPhone -> "Add"
                phoneVerified -> "Verified"
                profile?.phoneVerified == false -> "Unverified"
                else -> null
            },
            chipTone = if (phoneVerified) ChipTone.Success else ChipTone.Warning,
            onClick = onPhone,
        )
        SectionLabel("Account Management")
        SecurityRow(
            icon = Icons.Rounded.Password,
            title = "Change Password",
            subtitle = "Update your account password for better security.",
            onClick = onPassword,
        )
        RowDivider()
        SecurityRow(
            icon = Icons.Rounded.AlternateEmail,
            title = "Backup Codes",
            subtitle = if ((profile?.backupCodesRemaining ?: 0) > 0) "${profile!!.backupCodesRemaining} codes unused." else "Generate and manage backup codes for 2FA.",
            onClick = onBackupCodes,
        )
        RowDivider()
        SecurityRow(
            icon = Icons.Rounded.Logout,
            title = "Sign Out All Devices",
            subtitle = "Log out from all devices except this one.",
            onClick = onSignOutAll,
        )
        RowDivider()
        // The store-required self-serve deletion. Styled as the danger row it
        // is: the destructive ink of the sign-out rows, but it removes the
        // account itself rather than a session.
        SecurityRow(
            icon = Icons.Rounded.DeleteForever,
            title = "Delete Account",
            subtitle = "Permanently delete this account and its mailbox.",
            tint = LocalPalette.current.error,
            onClick = onDeleteAccount,
        )
        RowDivider()
        // Two rows the reference hub does not draw but the sector needs a
        // reachable entry for; kept here rather than orphaning those screens.
        SecurityRow(icon = Icons.Rounded.Devices, title = "Active Sessions", subtitle = "Devices signed in to this account.", onClick = onSessions)
        RowDivider()
        SecurityRow(icon = Icons.Rounded.VerifiedUser, title = "Recent Security Activity", subtitle = "Sign-ins and changes to this account.", onClick = onActivity)
        RowDivider()
        // The device-key sector's entries. Trusted devices are the rows the
        // server holds public keys for; recovery codes and notification
        // preferences hang off the same endpoints, and each gets its own
        // screen rather than being crammed into a hub row.
        SecurityRow(
            icon = Icons.Rounded.PhonelinkLock,
            title = "Trusted Devices",
            subtitle = "Devices whose hardware key can prove this account.",
            onClick = onTrustedDevices,
        )
        RowDivider()
        SecurityRow(
            icon = Icons.Rounded.Password,
            title = "Recovery Codes",
            subtitle = "One-time codes that unlock password recovery.",
            onClick = onRecoveryCodes,
        )
        RowDivider()
        SecurityRow(
            icon = Icons.Rounded.Timer,
            title = "Notification Preferences",
            subtitle = "Which alerts this account sends, and when.",
            onClick = onNotificationPrefs,
        )
        RowDivider()
        SecurityRow(
            icon = Icons.Rounded.HeadsetMic,
            title = "Help & Support",
            subtitle = "Visit our support center for security assistance.",
            onClick = onSupport,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = LocalPalette.current.textTertiary,
        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp),
    )
}

/** 03 — what turning the factor on will ask for, before it asks for it. */
@Composable
fun TwoFactorEnableScreen(onBack: () -> Unit, onContinue: () -> Unit) {
    val palette = LocalPalette.current
    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_2fa",
        illustrationWidth = 180,
        title = "Enable Two-Step Verification",
        body = "Add an extra layer of security to your account.",
        footer = {
            Column {
                GradientButton(
                    "Continue",
                    trailing = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 6.dp)) },
                    onClick = onContinue,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink("Cancel", onBack)
                }
            }
        },
    ) {
        InfoCard {
            NeedRow(Icons.Rounded.Security, "More Protection", "Prevents unauthorized access, even if your password is compromised.")
            RowDivider()
            NeedRow(Icons.Rounded.PhoneAndroid, "Use Your Phone", "We'll send a 6-digit code to your verified phone number.")
            RowDivider()
            NeedRow(Icons.Rounded.Lock, "Easy to Manage", "You can turn it off anytime from account security settings.")
        }
        Spacer(Modifier.height(14.dp))
        InfoNoteCard(body = "You'll need a verified phone number to enable two-step verification.")
    }
}

@Composable
private fun NeedRow(icon: ImageVector, title: String, body: String) {
    val palette = LocalPalette.current
    Row(Modifier.padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(18.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Text(body, fontSize = 12.sp, color = palette.textSecondary)
        }
    }
}

/**
 * 04 — the password is proved against the server, not compared in the client.
 *
 * The endpoint behind this verifies and changes nothing, so a pass here cannot
 * be mistaken for an 2FA state change; the factor is only switched on after the
 * phone answers.
 */
@Composable
fun ConfirmPasswordScreen(
    onBack: () -> Unit,
    onForgot: () -> Unit,
    onVerified: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_password_confirm",
        illustrationWidth = 150,
        title = "Confirm Your Password",
        body = "Enter your password to continue.",
        footer = {
            Column {
                GradientButton(
                    "Continue",
                    onClick = {
                        if (password.isBlank()) { error = "Enter your password."; return@GradientButton }
                        busy = true; error = null
                        scope.launch {
                            try {
                                val token = SessionStore.token(context) ?: throw IllegalStateException("Sign in again.")
                                val resp = Api.verifyPassword(token, password)
                                // The server hands back a short-lived proof; the
                                // enable call spends it. Keeping it here is what
                                // makes this screen more than decoration.
                                SecurityFlow.enableProof = resp.proof
                                onVerified()
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = password.isNotBlank(),
                    busy = busy,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink("Back", onBack)
                }
            }
        },
    ) {
        if (error != null) {
            Text(error!!, fontSize = 12.sp, color = palette.error)
            Spacer(Modifier.height(8.dp))
        }
        com.grapsee.grapmail.ui.components.AppTextField(
            label = "Password",
            value = password,
            onValueChange = { password = it; error = null },
            password = true,
            hint = "Enter your password",
        )
        Spacer(Modifier.height(14.dp))
        InfoNoteCard(body = "Your password is never stored on our servers and is only used for verification.")
    }
}

/**
 * 05 — the code goes to the number already on the account, and the factor is
 * switched on only after it is redeemed.
 */
@Composable
fun VerifyPhoneScreen(
    onBack: () -> Unit,
    onAddPhone: () -> Unit,
    onVerified: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var profile by remember { mutableStateOf<ProfileDto?>(null) }
    var challengeId by remember { mutableStateOf<String?>(null) }
    var expiresAt by remember { mutableStateOf(0L) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Two dead ends the first build left open: an account with no number could
    // reach this screen and be told to add one with nowhere to go, and a proof
    // that expired while the person typed left them with an error and no path
    // back to the password step.
    var needsPhone by remember { mutableStateOf(false) }
    var needsProof by remember { mutableStateOf(false) }
    var needsPhoneProof by remember { mutableStateOf(false) }

    suspend fun send(number: String?) {
        // The number is passed in, not re-fetched: this screen already loaded the
        // profile on entry, and a second GET on every send and every resend was
        // two round trips to read a field already in hand.
        val token = SessionStore.token(context) ?: return
        if (number.isNullOrBlank()) { needsPhone = true; error = "Add a phone number first."; return }
        val sent = Api.startPhoneVerification(number)
        challengeId = sent.challengeId
        expiresAt = sent.expiresAt ?: 0L
    }

    LaunchedEffect(Unit) {
        runCatching {
            val token = SessionStore.token(context) ?: return@runCatching
            profile = Api.profile(token).profile
            send(profile?.phone)
        }.onFailure { error = it.message }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_phone",
        illustrationWidth = 170,
        title = "Verify Your Phone",
        body = profile?.phone?.let { "We'll text a code to $it." } ?: "We'll text a code to the number on this account.",
        footer = {
            Column {
                GradientButton(
                    "Continue",
                    trailing = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 6.dp)) },
                    onClick = {
                        val id = challengeId ?: return@GradientButton
                        if (code.length < 6) { error = "Enter the 6-digit code."; return@GradientButton }
                        busy = true; error = null
                        scope.launch {
                            try {
                                val token = SessionStore.token(context) ?: throw IllegalStateException("Sign in again.")
                                val verified = Api.verifyPhone(id, code, token)
                                // The code's own proof, kept for the enable call.
                                // Without it the server refuses the change, which is
                                // the point: the phone step is now part of the deal
                                // rather than part of the choreography.
                                SecurityFlow.phoneProof = verified.phoneProof
                                Api.enableTwoFactor(token, SecurityFlow.enableProof, SecurityFlow.phoneProof)
                                SecurityFlow.enableProof = null
                                SecurityFlow.phoneProof = null
                                onVerified()
                            } catch (e: com.grapsee.grapmail.data.ApiException) {
                                // Both proofs are single-use and the server checks
                                // the password proof first, so any failure here has
                                // already spent it. Dropping only the proof the
                                // branch happened to name left a dead enable-2fa
                                // token on SecurityFlow, which the next retry re-sent
                                // and failed identically. Clear both and send the
                                // person back to the step that mints a fresh one.
                                if (e.code == "PROOF_REQUIRED") {
                                    needsProof = true
                                    SecurityFlow.enableProof = null
                                    SecurityFlow.phoneProof = null
                                }
                                if (e.code == "PHONE_PROOF_REQUIRED") {
                                    needsProof = true
                                    challengeId = null
                                    code = ""
                                    SecurityFlow.enableProof = null
                                    SecurityFlow.phoneProof = null
                                }
                                error = e.message
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = code.length == 6 && challengeId != null,
                    busy = busy,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink("Resend code", {
                        error = null
                        scope.launch { runCatching { send(profile?.phone) }.onFailure { error = it.message } }
                    })
                }
            }
        },
    ) {
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(18.dp))
                Text(
                    "  Phone Number",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textSecondary,
                )
                Spacer(Modifier.weight(1f))
                StatusChip("Verified", ChipTone.Success)
            }
            Spacer(Modifier.height(4.dp))
            Text(profile?.phone ?: "—", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(16.dp))
            Text("Verification Code", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.textSecondary)
            Spacer(Modifier.height(8.dp))
            CodeCells(length = 6, value = code, onValueChange = { code = it; error = null })
        }
        Spacer(Modifier.height(10.dp))
        if (expiresAt > 0) CodeExpiry(expiresAt)
        Spacer(Modifier.height(10.dp))
        InfoNoteCard(body = "Didn't receive the code? Check your spam folder or make sure your phone is on and has signal.")
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, fontSize = 12.sp, color = palette.error)
        }
        if (needsPhone) {
            Spacer(Modifier.height(6.dp))
            AccountLink("Add a phone number", onAddPhone)
        }
        if (needsProof) {
            Spacer(Modifier.height(6.dp))
            AccountLink("Enter your password again", onBack)
        }
        if (needsPhoneProof) {
            Spacer(Modifier.height(6.dp))
            AccountLink("Send me a new code", {
                needsPhoneProof = false
                error = null
                scope.launch { runCatching { send(profile?.phone) }.onFailure { error = it.message } }
            })
        }
    }
}

/** 06 — the state the person just earned, with what to do next. */
@Composable
fun TwoFactorEnabledScreen(onContinue: () -> Unit, onBackHome: () -> Unit) {
    val palette = LocalPalette.current
    AccountScreen(
        onBack = onContinue,
        illustration = "ill_sec_2fa_on",
        illustrationWidth = 160,
        title = "Two-Step Verification Enabled",
        body = "Your account is now protected with an extra layer of security.",
        footer = {
            Column {
                GradientButton("Continue", onContinue)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink("Back to Security Settings", onBackHome)
                }
            }
        },
    ) {
        StatusChip("2-Step Verification Enabled", ChipTone.Success)
        Spacer(Modifier.height(16.dp))
        InfoCard {
            Text("What's Next?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(10.dp))
            NeedRow(Icons.Rounded.VerifiedUser, "Keep Backup Codes Safe", "Use them if you ever lose access to your phone.")
            NeedRow(Icons.Rounded.PhoneAndroid, "Update Your Phone Number", "Keep it current so codes always reach you.")
        }
        Spacer(Modifier.height(12.dp))
        Text("You'll be asked for a code each time you sign in on a new device.", fontSize = 12.sp, color = palette.textSecondary)
    }
}

/** 09 — weakening the account needs the same proof as strengthening it. */
@Composable
fun DisableTwoFactorScreen(onBack: () -> Unit, onDisabled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_2fa_off_confirm",
        illustrationWidth = 150,
        title = "Disable Two-Step Verification?",
        body = "This will remove the extra layer of security from your account.",
        footer = {
            Column {
                GradientButton(
                    "Continue",
                    onClick = {
                        if (password.isBlank()) { error = "Enter your password."; return@GradientButton }
                        busy = true; error = null
                        scope.launch {
                            try {
                                val token = SessionStore.token(context) ?: throw IllegalStateException("Sign in again.")
                                Api.disableTwoFactor(token, password)
                                onDisabled()
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = password.isNotBlank(),
                    busy = busy,
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink("Back", onBack)
                }
            }
        },
    ) {
        if (error != null) {
            Text(error!!, fontSize = 12.sp, color = palette.error)
            Spacer(Modifier.height(8.dp))
        }
        com.grapsee.grapmail.ui.components.AppTextField(
            label = "Password",
            value = password,
            onValueChange = { password = it; error = null },
            password = true,
            hint = "Enter your password",
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Without two-step verification, a password alone is enough to sign in to this account.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = palette.textSecondary,
        )
    }
}

/** 10 — the confirmation, with an honest explanation of the new posture. */
@Composable
fun TwoFactorDisabledScreen(onDone: () -> Unit, onSecureNow: () -> Unit) {
    val palette = LocalPalette.current
    AccountScreen(
        onBack = onDone,
        illustration = "ill_sec_2fa_off",
        illustrationWidth = 160,
        title = "Two-Step Verification Disabled",
        body = "Two-step verification has been disabled. Your account is now protected by your password only.",
        footer = {
            Column {
                GradientButton("Continue", onDone)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink("Turn it back on", onSecureNow)
                }
            }
        },
    ) {
        InfoCard {
            Text("What happens now?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            NeedRow(Icons.Rounded.Password, "Password Only", "Signing in no longer asks for a code.")
            NeedRow(Icons.Rounded.Textsms, "Backup Codes Retired", "The codes you saved no longer apply.")
        }
        Spacer(Modifier.height(12.dp))
        FooterNote(
            text = "You can turn two-step verification back on at any time from Account Security.",
            divider = false,
        )
    }
}

/**
 * The account's trusted devices — the rows the server holds a public key for.
 *
 * The list is read fresh on entry and after every revoke: a device that was
 * revoked from another surface must not survive here as a cached row. Each row
 * carries the name the enrollment sent, a platform icon, the server's own
 * `trustState`, and the last time the device proved itself. Revoking needs a
 * confirm dialog because the consequence is one-way: the key on that device
 * stops being a credential, and the device cannot re-trust itself without
 * being signed in again.
 *
 * This handset's own row is marked, from the row id the enrollment stored —
 * so "Remove trust" on it says what it costs (the recovery path on THIS
 * device goes dark too) and clears the local enrollment flag afterwards, so
 * the next sign-in re-offers the key instead of assuming the old grant stands.
 */
@Composable
fun TrustedDevicesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var devices by remember { mutableStateOf<List<TrustedDeviceDto>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var revokeTarget by remember { mutableStateOf<TrustedDeviceDto?>(null) }
    var enrolling by remember { mutableStateOf(false) }
    // This device's server row, known only after a completed enrollment on
    // this install, and whether a local key exists at all — both read in the
    // load below, never in composition: a keystore probe on the render path is
    // the kind of blocking the list screens here avoid.
    var ownRowId by remember { mutableStateOf<String?>(null) }
    var hasLocalKey by remember { mutableStateOf(false) }

    suspend fun load() {
        val session = SessionStore.snapshot(context) ?: run {
            failed = true; loading = false
            return
        }
        loading = true
        failed = false
        ownRowId = TrustedDeviceManager.enrolledDeviceRowId(context, session.address)
        hasLocalKey = com.grapsee.grapmail.security.DeviceKeyManager.hasKey()
        try {
            val resp = Api.listDevices(session.token)
            if (!SessionStore.isCurrent(context, session.id)) return
            devices = resp.devices
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (SessionStore.isCurrent(context, session.id)) failed = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    fun revoke(device: TrustedDeviceDto) {
        scope.launch {
            busyId = device.id
            try {
                val active = SessionStore.snapshot(context) ?: throw IllegalStateException("Sign in again.")
                Api.revokeDevice(active.token, device.id)
                if (!SessionStore.isCurrent(context, active.id)) return@launch
                if (device.id == ownRowId) {
                    // Revoked ourselves: drop the local enrollment record, or
                    // the next sign-in would assume the server still trusts a
                    // row it just removed.
                    TrustedDeviceManager.requestReEnroll(context, active.address)
                }
                devices = devices?.filterNot { it.id == device.id }
                revokeTarget = null
            } catch (e: Exception) {
                error = e.message
            } finally {
                busyId = null
            }
        }
    }

    fun reEnroll() {
        scope.launch {
            enrolling = true
            error = null
            try {
                val active = SessionStore.snapshot(context) ?: throw IllegalStateException("Sign in again.")
                TrustedDeviceManager.enroll(context, active.token, active.address)
                load()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                enrolling = false
            }
        }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_sessions",
        illustrationWidth = 150,
        title = "Trusted Devices",
        body = "Devices whose hardware-backed key can prove this account and unlock password recovery.",
        footer = {
            Column {
                if (hasLocalKey && devices != null && devices?.none { it.id == ownRowId } == true) {
                    GradientButton(
                        "Trust this device",
                        onClick = { reEnroll() },
                        busy = enrolling,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink("Back to Account Security", onBack)
                }
            }
        },
    ) {
        LoadStateBlock(loading = loading, failed = failed, onRetry = { scope.launch { load() } }) {
            val rows = devices ?: return@LoadStateBlock
            if (rows.isEmpty()) {
                Text(
                    "No trusted devices yet. This device enrolls itself the next time you sign in.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = palette.textSecondary,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEach { device ->
                        TrustedDeviceCard(
                            device = device,
                            isThisDevice = device.id == ownRowId,
                            busy = busyId == device.id,
                            onRevoke = { revokeTarget = device },
                        )
                    }
                }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, fontSize = 12.sp, color = palette.error)
        }
        Spacer(Modifier.height(12.dp))
        InfoNoteCard(
            title = "What trusted means",
            body = "A trusted device can prove itself with a key stored in its hardware — it never sends the key, only a signature. Revoke a device the moment you stop holding it.",
        )
    }

    revokeTarget?.let { device ->
        AlertDialog(
            onDismissRequest = { if (busyId == null) revokeTarget = null },
            title = { Text("Remove trusted device?") },
            text = {
                Text(
                    "“" + device.displayName.ifBlank { "This device" } + "” will lose its key as a credential. " +
                        if (device.id == ownRowId) "This is the device you are holding — password recovery from it will stop working until you sign in again."
                        else "It will need to sign in again before it can be trusted once more.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = busyId == null,
                    onClick = { revoke(device) },
                ) { Text("Remove trust", color = palette.error) }
            },
            dismissButton = {
                TextButton(enabled = busyId == null, onClick = { revokeTarget = null }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun TrustedDeviceCard(
    device: TrustedDeviceDto,
    isThisDevice: Boolean,
    busy: Boolean,
    onRevoke: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, palette.border, RoundedCornerShape(16.dp))
            .background(palette.card)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                device.displayName.ifBlank { "Unknown device" },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isThisDevice) {
                Text(
                    "This device",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.successInk,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(palette.successChipBg)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(trustedDeviceIcon(device.platform), contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(13.dp))
            Text(
                trustedPlatformLabel(device.platform),
                fontSize = 12.sp,
                color = palette.textSecondary,
            )
            device.lastSeenAt?.takeIf { it.isNotBlank() }?.let {
                Text("·  Last seen ${relativeTime(it)}", fontSize = 12.sp, color = palette.textSecondary)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            listOfNotNull(
                device.trustState.replaceFirstChar { it.uppercaseChar() }.takeIf { it.isNotBlank() },
                device.createdAt?.takeIf(String::isNotBlank)?.let { "Added ${absoluteDateTime(it)}" },
            ).joinToString(" · "),
            fontSize = 11.sp,
            color = palette.textTertiary,
        )
        if (!device.isRevoked) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy, onClick = onRevoke)
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (busy) "Removing…" else "Remove trust",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (busy) palette.textTertiary else palette.error,
                )
            }
        } else {
            Spacer(Modifier.height(10.dp))
            StatusChip("Revoked", ChipTone.Warning)
        }
    }
}

/** The row's platform icon; an unknown platform is drawn as a generic device. */
private fun trustedDeviceIcon(platform: String): ImageVector = when (platform.lowercase()) {
    "android" -> Icons.Rounded.PhoneAndroid
    "ios" -> Icons.Rounded.PhoneIphone
    else -> Icons.Rounded.DevicesOther
}

private fun trustedPlatformLabel(platform: String): String = when (platform.lowercase()) {
    "android" -> "Android"
    "ios" -> "iPhone / iPad"
    else -> platform.replaceFirstChar { it.uppercaseChar() }.ifBlank { "Unknown platform" }
}
