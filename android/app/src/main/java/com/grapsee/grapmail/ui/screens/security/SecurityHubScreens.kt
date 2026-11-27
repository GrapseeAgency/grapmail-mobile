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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.HeadsetMic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.automirrored.rounded.Logout
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.grapsee.grapmail.R

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
    onRecoveryEmail: () -> Unit = {},
    onSessions: () -> Unit,
    onActivity: () -> Unit,
    onTrustedDevices: () -> Unit,
    onPasskeys: () -> Unit,
    onRecoveryCodes: () -> Unit,
    onNotificationPrefs: () -> Unit,
    onBackupCodes: () -> Unit,
    onPassword: () -> Unit,
    onSignOutAll: () -> Unit,
    onDeleteAccount: () -> Unit,
    onSupport: () -> Unit,
    /**
     * Round 8 — C (round8-spec §4.3 A): when set, the safety-score header and
     * the "Devices & security" row (which calls this) head the hub. Null
     * (feature off, or the snapshot walk) keeps the hub exactly as it was.
     */
    onSecurityCenter: (() -> Unit)? = null,
    /** A checklist step tapped in that header (two-step, passkeys, …). */
    onSafetyAction: (SafetyAction) -> Unit = {},
    demoProfile: ProfileDto? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<com.grapsee.grapmail.data.ProfileDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    // A password reset someone asked for by email, still pending (lib/reset-hold.ts).
    var resetPending by remember { mutableStateOf<com.grapsee.grapmail.data.PasswordResetStatus?>(null) }
    var resetNote by remember { mutableStateOf<String?>(null) }
    var cancelling by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        SessionStore.snapshot(context)?.let { s ->
            resetPending = runCatching { Api.passwordResetStatus(s.token) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()?.takeIf { it.success && it.pending }
        }
    }

    LaunchedEffect(reload) {
        if (demoProfile != null) {
            profile = demoProfile
            failed = false
            loading = false
            return@LaunchedEffect
        }
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
    // GrapMail resets passwords by email only, so an account without a verified
    // recovery email is one forgotten password from a lock-out. That outranks
    // every other item here, and it is the reminder for people who skipped the
    // step at sign-up.
    val hasRecoveryEmail = !profile?.recoveryEmail.isNullOrBlank()
    val recoveryVerified = hasRecoveryEmail && profile?.recoveryEmailVerified == true
    // Secure means a verified way back in (the recovery email) and a second factor.
    // It used to require a verified phone too, which GrapMail no longer uses.
    val secure = twoFactorOn && recoveryVerified

    AccountScreen(
        onBack = onBack,
        illustration = if (secure) "ill_sec_hub_secure" else "ill_sec_hub",
        illustrationWidth = 120,
        title = stringResource(R.string.acct_account_security),
        body = stringResource(R.string.acct_manage_your_security_settings_to),
        footer = {
            FooterNote(
                text = stringResource(R.string.acct_your_security_matters_were_here_to),
                divider = true,
            )
        },
    ) {
        // Round 8 — C: the account's safety score heads the hub (§4.3 A).
        if (onSecurityCenter != null) {
            SecurityHubSafetyHeader(onOpenCenter = onSecurityCenter, onAction = onSafetyAction)
        }
        resetPending?.let { pending ->
            InfoCard {
                StatusChip(stringResource(R.string.acct_password_reset_requested), ChipTone.Warning)
                Spacer(Modifier.height(10.dp))
                Text(
                    pending.availableAt?.let { at ->
                        val shown = runCatching {
                            java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
                                .format(java.time.Instant.parse(at).atZone(java.time.ZoneId.systemDefault()))
                        }.getOrDefault(at)
                        stringResource(R.string.acct_reset_pending_works_from, shown)
                    } ?: stringResource(R.string.acct_reset_pending_generic),
                    fontSize = 12.sp, lineHeight = 17.sp, color = LocalPalette.current.textSecondary,
                )
                Spacer(Modifier.height(4.dp))
                AccountLink(if (cancelling) stringResource(R.string.acct_cancelling) else stringResource(R.string.acct_cancel_the_reset), {
                    if (!cancelling) {
                        cancelling = true
                        scope.launch {
                            val token = SessionStore.token(context)
                            val result = token?.let {
                                runCatching { Api.cancelPasswordReset(it) }
                                    .onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e }
                                    .getOrNull()
                            }
                            cancelling = false
                            if (result?.success == true) {
                                resetPending = null
                                resetNote = result.message ?: context.getString(R.string.acct_the_password_reset_was_cancelled)
                            } else {
                                resetNote = result?.error ?: context.getString(R.string.acct_the_reset_could_not_be_cancelled_try)
                            }
                        }
                    }
                })
            }
            Spacer(Modifier.height(12.dp))
        }
        resetNote?.let {
            Text(it, fontSize = 12.sp, color = LocalPalette.current.textSecondary)
            Spacer(Modifier.height(12.dp))
        }
        // Rooted-device note (security/RootCheck.kt): local heuristics only,
        // dismissible, never blocking. Play Integrity's verdict is the server's
        // (security/IntegrityClient.kt) and is not re-judged here.
        var rootResult by remember { mutableStateOf<com.grapsee.grapmail.security.RootCheck.Result?>(null) }
        var rootDismissed by remember { mutableStateOf(com.grapsee.grapmail.security.RootCheck.isDismissed(context)) }
        LaunchedEffect(Unit) {
            rootResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.grapsee.grapmail.security.RootCheck.probe() }.getOrNull()
            }
        }
        if (com.grapsee.grapmail.security.RootCheck.shouldWarn(rootResult, rootDismissed)) {
            InfoCard {
                StatusChip(stringResource(R.string.acct_root_warning_title), ChipTone.Warning)
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.acct_root_warning_body),
                    fontSize = 12.sp, lineHeight = 17.sp, color = LocalPalette.current.textSecondary,
                )
                Spacer(Modifier.height(4.dp))
                AccountLink(stringResource(R.string.acct_root_warning_dismiss), {
                    com.grapsee.grapmail.security.RootCheck.dismiss(context)
                    rootDismissed = true
                })
            }
            Spacer(Modifier.height(12.dp))
        }
        // The reference puts the account's verdict above the feature list: it is
        // the answer to "am I safe?", and burying it under the switches makes
        // the person read six rows to learn one thing.
        // Screen states: the offline notice above the verdict (the cached
        // profile, when held, stays on screen under it).
        val online = com.grapsee.grapmail.ui.components.rememberOnline()
        com.grapsee.grapmail.ui.components.OfflineBanner(online = online)
        InfoCard {
            when {
                loading -> com.grapsee.grapmail.ui.components.ListSkeleton(rows = 2, avatar = false,
                    label = stringResource(R.string.acct_checking_your_account))
                failed -> {
                    Text(stringResource(R.string.acct_we_couldnt_load_your_security), fontSize = 13.sp, color = LocalPalette.current.textSecondary)
                    if (online == false) {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.states_error_offline_body), fontSize = 13.sp, color = LocalPalette.current.textSecondary)
                    }
                    Spacer(Modifier.height(4.dp))
                    AccountLink(stringResource(R.string.states_retry), { reload += 1 })
                }
                else -> {
                    StatusChip(
                        when {
                            secure -> stringResource(R.string.acct_your_account_is_secure)
                            !recoveryVerified -> stringResource(R.string.acct_add_a_recovery_email)
                            else -> stringResource(R.string.acct_one_thing_left_to_do)
                        },
                        if (secure) ChipTone.Success else ChipTone.Warning,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when {
                            secure -> stringResource(R.string.acct_all_essential_security_features_are)
                            !recoveryVerified -> stringResource(R.string.acct_without_a_verified_recovery_email_a)
                            else -> stringResource(R.string.acct_turn_on_two_step_verification_to)
                        },
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
                            stringResource(R.string.acct_add_backup_codes_so_losing_access_to),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = LocalPalette.current.warnInk,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionLabel(stringResource(R.string.acct_security_features))
        // The reference shows a real on/off switch on the 2FA row, not just a
        // pill. Toggling routes into the flow that proves the change (password,
        // then code) rather than flipping a flag blind — the switch is the entry
        // point, not the write.
        ToggleRow(
            icon = Icons.Rounded.Security,
            title = stringResource(R.string.acct_two_step_verification_title),
            subtitle = stringResource(R.string.acct_add_an_extra_layer_of_security_to),
            checked = twoFactorOn && !loading && !failed,
            enabled = !loading && !failed,
            chip = if (loading || failed) null else if (twoFactorOn) stringResource(R.string.acct_enabled) else stringResource(R.string.acct_off),
            chipTone = if (twoFactorOn) ChipTone.Success else ChipTone.Warning,
            onToggle = { if (twoFactorOn) onDisableTwoFactor() else onTwoFactor() },
        )
        // GrapMail sends account codes by email only. A phone number is shown
        // only on an older account that still has one, so it can be reviewed.
        if (hasPhone) {
            RowDivider()
            SecurityRow(
                icon = Icons.Rounded.PhoneAndroid,
                title = stringResource(R.string.acct_phone_number_title),
                subtitle = profile?.phone ?: "",
                chip = when {
                    loading || failed -> null
                    phoneVerified -> stringResource(R.string.acct_verified)
                    profile?.phoneVerified == false -> stringResource(R.string.acct_unverified)
                    else -> null
                },
                chipTone = if (phoneVerified) ChipTone.Success else ChipTone.Warning,
                onClick = onPhone,
            )
        }
        RowDivider()
        SecurityRow(
            icon = Icons.Rounded.Email,
            title = stringResource(R.string.acct_recovery_email_title),
            subtitle = profile?.recoveryEmail?.takeIf { it.isNotBlank() }
                ?: if (loading) null else stringResource(R.string.acct_where_we_send_a_link_if_you_forget),
            chip = when {
                loading || failed -> null
                !hasRecoveryEmail -> stringResource(R.string.acct_add)
                recoveryVerified -> stringResource(R.string.acct_verified)
                else -> stringResource(R.string.acct_unverified)
            },
            chipTone = if (recoveryVerified) ChipTone.Success else ChipTone.Warning,
            onClick = onRecoveryEmail,
        )
        SectionLabel(stringResource(R.string.acct_account_management))
        SecurityRow(
            icon = Icons.Rounded.Password,
            title = stringResource(R.string.acct_change_password),
            subtitle = stringResource(R.string.acct_update_your_account_password_for),
            onClick = onPassword,
        )
        RowDivider()
        SecurityRow(
            icon = Icons.Rounded.AlternateEmail,
            title = stringResource(R.string.acct_backup_codes_title),
            subtitle = (profile?.backupCodesRemaining ?: 0).let { remaining ->
                if (remaining > 0) pluralStringResource(R.plurals.acct_backup_codes_unused, remaining, remaining)
                else stringResource(R.string.acct_generate_and_manage_backup_codes)
            },
            onClick = onBackupCodes,
        )
        RowDivider()
        SecurityRow(
            icon = Icons.AutoMirrored.Rounded.Logout,
            title = stringResource(R.string.acct_sign_out_all_devices_title),
            subtitle = stringResource(R.string.acct_log_out_from_all_devices_except_this),
            onClick = onSignOutAll,
        )
        RowDivider()
        // The store-required self-serve deletion. Styled as the danger row it
        // is: the destructive ink of the sign-out rows, but it removes the
        // account itself rather than a session.
        SecurityRow(
            icon = Icons.Rounded.DeleteForever,
            title = stringResource(R.string.acct_delete_account),
            subtitle = stringResource(R.string.acct_permanently_delete_this_account_and),
            tint = LocalPalette.current.error,
            onClick = onDeleteAccount,
        )
        RowDivider()
        // Two rows the reference hub does not draw but the sector needs a
        // reachable entry for; kept here rather than orphaning those screens.
        // Round 8 - C (round8-spec section 3): with the Device & Security Center on,
        // it is the default destination for sessions and activity; the older
        // Sessions / Activity screens stay registered for deep links (and the
        // hardware-key screen stays one tap away inside the center).
        val openSessions: () -> Unit = onSecurityCenter ?: onSessions
        val openActivity: () -> Unit = onSecurityCenter?.let { center ->
            {
                // Arrive scrolled to the timeline (or its first "Review" row).
                SecurityCenterNav.requestFocus(SafetyAction.Activity)
                center()
            }
        } ?: onActivity
        SecurityRow(icon = Icons.Rounded.Devices, title = stringResource(R.string.acct_active_sessions), subtitle = stringResource(R.string.acct_devices_signed_in_to_this_account), onClick = openSessions)
        RowDivider()
        SecurityRow(icon = Icons.Rounded.VerifiedUser, title = stringResource(R.string.acct_recent_security_activity), subtitle = stringResource(R.string.acct_sign_ins_and_changes_to_this_account), onClick = openActivity)
        RowDivider()
        // The device-key sector's entries. Trusted devices are the rows the
        // server holds public keys for; recovery codes and notification
        // preferences hang off the same endpoints, and each gets its own
        // screen rather than being crammed into a hub row.
        SecurityRow(
            icon = Icons.Rounded.PhonelinkLock,
            title = stringResource(R.string.acct_trusted_devices),
            subtitle = stringResource(R.string.acct_devices_whose_hardware_key_can_prove),
            onClick = onTrustedDevices,
        )
        RowDivider()
        // Passkeys hang off the same account: created here (signed in, so the
        // ceremony runs under the bearer), used at sign-in on any screen —
        // this row is the only place a credential is offered or removed.
        SecurityRow(
            icon = Icons.Rounded.Fingerprint,
            title = stringResource(R.string.acct_passkeys),
            subtitle = stringResource(R.string.acct_sign_in_with_a_biometric_credential_2),
            onClick = onPasskeys,
        )
        RowDivider()
        SecurityRow(
            icon = Icons.Rounded.Password,
            title = stringResource(R.string.acct_recovery_codes),
            subtitle = stringResource(R.string.acct_one_time_codes_that_unlock_password_2),
            onClick = onRecoveryCodes,
        )
        RowDivider()
        SecurityRow(
            icon = Icons.Rounded.Timer,
            title = stringResource(R.string.acct_notification_preferences),
            subtitle = stringResource(R.string.acct_which_alerts_this_account_sends_and),
            onClick = onNotificationPrefs,
        )
        RowDivider()
        SecurityRow(
            icon = Icons.Rounded.HeadsetMic,
            title = stringResource(R.string.acct_help_support),
            subtitle = stringResource(R.string.acct_visit_our_support_center_for),
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
        title = stringResource(R.string.acct_enable_two_step_verification),
        body = stringResource(R.string.acct_add_an_extra_layer_of_security_to_2),
        footer = {
            Column {
                GradientButton(
                    stringResource(R.string.acct_continue),
                    trailing = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 6.dp)) },
                    onClick = onContinue,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink(stringResource(R.string.acct_cancel), onBack)
                }
            }
        },
    ) {
        InfoCard {
            NeedRow(Icons.Rounded.Security, stringResource(R.string.acct_more_protection), stringResource(R.string.acct_prevents_unauthorized_access_even_if))
            RowDivider()
            NeedRow(Icons.Rounded.Email, stringResource(R.string.acct_codes_by_email), stringResource(R.string.acct_well_email_a_6_digit_code_to_your))
            RowDivider()
            NeedRow(Icons.Rounded.Lock, stringResource(R.string.acct_easy_to_manage), stringResource(R.string.acct_you_can_turn_it_off_anytime_from))
        }
        Spacer(Modifier.height(14.dp))
        InfoNoteCard(body = stringResource(R.string.acct_youll_need_a_verified_recovery_email))
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
        title = stringResource(R.string.acct_confirm_your_password),
        body = stringResource(R.string.acct_enter_your_password_to_continue),
        footer = {
            Column {
                GradientButton(
                    stringResource(R.string.acct_continue),
                    onClick = {
                        if (password.isBlank()) { error = context.getString(R.string.acct_enter_your_password_period); return@GradientButton }
                        busy = true; error = null
                        scope.launch {
                            try {
                                val token = SessionStore.token(context) ?: throw IllegalStateException(context.getString(R.string.acct_sign_in_again))
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
                    AccountLink(stringResource(R.string.acct_back), onBack)
                }
            }
        },
    ) {
        if (error != null) {
            Text(error!!, fontSize = 12.sp, color = palette.error)
            Spacer(Modifier.height(8.dp))
        }
        com.grapsee.grapmail.ui.components.AppTextField(
            label = stringResource(R.string.acct_password),
            value = password,
            onValueChange = { password = it; error = null },
            password = true,
            hint = stringResource(R.string.acct_enter_your_password),
            autofill = com.grapsee.grapmail.ui.components.GMAutofill.Password,
        )
        Spacer(Modifier.height(14.dp))
        InfoNoteCard(body = stringResource(R.string.acct_your_password_is_never_stored_on_our))
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
        if (number.isNullOrBlank()) { needsPhone = true; error = context.getString(R.string.acct_add_a_phone_number_first); return }
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
        title = stringResource(R.string.acct_verify_your_phone),
        body = profile?.phone?.let { stringResource(R.string.acct_well_text_a_code_to, it) } ?: stringResource(R.string.acct_well_text_a_code_to_account_number),
        footer = {
            Column {
                GradientButton(
                    stringResource(R.string.acct_continue),
                    trailing = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 6.dp)) },
                    onClick = {
                        val id = challengeId ?: return@GradientButton
                        if (code.length < 6) { error = context.getString(R.string.acct_enter_the_6_digit_code_period); return@GradientButton }
                        busy = true; error = null
                        scope.launch {
                            try {
                                val token = SessionStore.token(context) ?: throw IllegalStateException(context.getString(R.string.acct_sign_in_again))
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
                    AccountLink(stringResource(R.string.acct_resend_code), {
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
                    stringResource(R.string.acct_phone_number_2),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textSecondary,
                    // Takes the slack (wraps at large font sizes) so the chip stays on the card.
                    modifier = Modifier.weight(1f),
                )
                StatusChip(stringResource(R.string.acct_verified), ChipTone.Success)
            }
            Spacer(Modifier.height(4.dp))
            Text(profile?.phone ?: "—", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.acct_verification_code_title), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.textSecondary)
            Spacer(Modifier.height(8.dp))
            CodeCells(length = 6, value = code, onValueChange = { code = it; error = null },
                autofill = com.grapsee.grapmail.ui.components.GMAutofill.OneTimeCode)
        }
        Spacer(Modifier.height(10.dp))
        if (expiresAt > 0) CodeExpiry(expiresAt)
        Spacer(Modifier.height(10.dp))
        InfoNoteCard(body = stringResource(R.string.acct_didnt_receive_the_code_check_your_2))
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, fontSize = 12.sp, color = palette.error)
        }
        if (needsPhone) {
            Spacer(Modifier.height(6.dp))
            AccountLink(stringResource(R.string.acct_add_a_phone_number), onAddPhone)
        }
        if (needsProof) {
            Spacer(Modifier.height(6.dp))
            AccountLink(stringResource(R.string.acct_enter_your_password_again), onBack)
        }
        if (needsPhoneProof) {
            Spacer(Modifier.height(6.dp))
            AccountLink(stringResource(R.string.acct_send_me_a_new_code), {
                needsPhoneProof = false
                error = null
                scope.launch { runCatching { send(profile?.phone) }.onFailure { error = it.message } }
            })
        }
    }
}

/**
 * 05 — the code goes to the verified recovery email, and the factor is switched
 * on only after it is entered.
 *
 * GrapMail sends account codes by email only, so this replaces the texted code
 * in the enable flow. It sends a fresh code to the address already on the
 * account (POST /grapsee/auth/account/recovery-email/start), confirms it, and
 * spends the proof that comes back together with the password proof from 04.
 * An account with no verified recovery email is sent to add one first.
 */
@Composable
fun TwoFactorEmailScreen(
    onBack: () -> Unit,
    onAddRecoveryEmail: () -> Unit,
    onVerified: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var address by remember { mutableStateOf<String?>(null) }
    var verificationToken by remember { mutableStateOf<String?>(null) }
    var maskedEmail by remember { mutableStateOf<String?>(null) }
    var expiresAt by remember { mutableStateOf(0L) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var needsEmail by remember { mutableStateOf(false) }
    var needsProof by remember { mutableStateOf(false) }

    suspend fun send() {
        val token = SessionStore.token(context) ?: throw IllegalStateException(context.getString(R.string.acct_sign_in_again))
        val to = address ?: return
        val sent = Api.startRecoveryEmail(token, to)
        verificationToken = sent.token
        maskedEmail = sent.maskedEmail ?: to
        expiresAt = sent.expiresAt ?: 0L
        code = ""
    }

    LaunchedEffect(Unit) {
        runCatching {
            val token = SessionStore.token(context) ?: throw IllegalStateException(context.getString(R.string.acct_sign_in_again))
            val profile = Api.profile(token).profile
            val email = profile?.recoveryEmail?.takeIf { it.isNotBlank() }
            if (email == null || profile?.recoveryEmailVerified != true) {
                needsEmail = true
            } else {
                address = email
                send()
            }
        }.onFailure { error = it.message }
        loading = false
    }

    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_phone",
        illustrationWidth = 170,
        title = if (needsEmail) stringResource(R.string.acct_add_a_recovery_email_2) else stringResource(R.string.acct_check_your_email),
        body = when {
            needsEmail -> stringResource(R.string.acct_two_step_verification_emails_your)
            maskedEmail != null -> stringResource(R.string.acct_weve_emailed_a_6_digit_code_to, maskedEmail.orEmpty())
            else -> stringResource(R.string.acct_well_email_a_code_to_the_recovery)
        },
        footer = {
            Column {
                if (needsEmail) {
                    GradientButton(
                        stringResource(R.string.acct_add_recovery_email),
                        trailing = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 6.dp)) },
                        onClick = onAddRecoveryEmail,
                    )
                } else {
                    GradientButton(
                        stringResource(R.string.acct_turn_on),
                        trailing = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 6.dp)) },
                        onClick = {
                            val vt = verificationToken ?: return@GradientButton
                            if (code.length < 6) { error = context.getString(R.string.acct_enter_the_6_digit_code_period); return@GradientButton }
                            busy = true; error = null
                            scope.launch {
                                try {
                                    val token = SessionStore.token(context) ?: throw IllegalStateException(context.getString(R.string.acct_sign_in_again))
                                    val confirmed = Api.confirmRecoveryEmail(token, vt, code)
                                    Api.enableTwoFactor(token, SecurityFlow.enableProof, null, confirmed.emailProof)
                                    SecurityFlow.enableProof = null
                                    SecurityFlow.phoneProof = null
                                    onVerified()
                                } catch (e: com.grapsee.grapmail.data.ApiException) {
                                    // The password proof is single use and checked
                                    // first, so any refusal of the enable call has
                                    // already spent it: send the person back for a
                                    // fresh one rather than retrying a dead token.
                                    if (e.code == "PROOF_REQUIRED" || e.code == "EMAIL_PROOF_REQUIRED") {
                                        needsProof = true
                                        SecurityFlow.enableProof = null
                                        verificationToken = null
                                        code = ""
                                    }
                                    if (e.code == "TWO_FACTOR_NEEDS_RECOVERY_EMAIL") needsEmail = true
                                    error = e.message
                                } catch (e: Exception) {
                                    error = e.message
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = code.length == 6 && verificationToken != null,
                        busy = busy || loading,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        AccountLink(stringResource(R.string.acct_resend_code), {
                            error = null
                            scope.launch { runCatching { send() }.onFailure { error = it.message } }
                        })
                    }
                }
            }
        },
    ) {
        if (!needsEmail) {
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Email, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.acct_recovery_email_2),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.textSecondary,
                        // Takes the slack (wraps at large font sizes) so the chip stays on the card.
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(stringResource(R.string.acct_verified), ChipTone.Success)
                }
                Spacer(Modifier.height(4.dp))
                Text(address ?: "—", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.acct_verification_code_title), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.textSecondary)
                Spacer(Modifier.height(8.dp))
                CodeCells(length = 6, value = code, onValueChange = { code = it.filter(Char::isDigit).take(6); error = null },
                    autofill = com.grapsee.grapmail.ui.components.GMAutofill.OneTimeCode)
            }
            Spacer(Modifier.height(10.dp))
            if (expiresAt > 0) CodeExpiry(expiresAt)
            Spacer(Modifier.height(10.dp))
            InfoNoteCard(body = stringResource(R.string.acct_didnt_get_it_check_your_spam_folder_2))
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, fontSize = 12.sp, color = palette.error)
        }
        if (needsProof) {
            Spacer(Modifier.height(6.dp))
            AccountLink(stringResource(R.string.acct_enter_your_password_again), onBack)
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
        title = stringResource(R.string.acct_two_step_verification_enabled),
        body = stringResource(R.string.acct_your_account_is_now_protected_with),
        footer = {
            Column {
                GradientButton(stringResource(R.string.acct_continue), onContinue)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink(stringResource(R.string.acct_back_to_security_settings), onBackHome)
                }
            }
        },
    ) {
        StatusChip(stringResource(R.string.acct_2_step_verification_enabled), ChipTone.Success)
        Spacer(Modifier.height(16.dp))
        InfoCard {
            Text(stringResource(R.string.acct_whats_next_title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(10.dp))
            NeedRow(Icons.Rounded.VerifiedUser, stringResource(R.string.acct_keep_backup_codes_safe), stringResource(R.string.acct_use_them_if_you_ever_lose_access_to))
            NeedRow(Icons.Rounded.PhoneAndroid, stringResource(R.string.acct_update_your_phone_number), stringResource(R.string.acct_keep_it_current_so_codes_always))
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.acct_youll_be_asked_for_a_code_each_time), fontSize = 12.sp, color = palette.textSecondary)
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
        title = stringResource(R.string.acct_disable_two_step_verification),
        body = stringResource(R.string.acct_this_will_remove_the_extra_layer_of),
        footer = {
            Column {
                GradientButton(
                    stringResource(R.string.acct_continue),
                    onClick = {
                        if (password.isBlank()) { error = context.getString(R.string.acct_enter_your_password_period); return@GradientButton }
                        busy = true; error = null
                        scope.launch {
                            try {
                                val token = SessionStore.token(context) ?: throw IllegalStateException(context.getString(R.string.acct_sign_in_again))
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
                    AccountLink(stringResource(R.string.acct_back), onBack)
                }
            }
        },
    ) {
        if (error != null) {
            Text(error!!, fontSize = 12.sp, color = palette.error)
            Spacer(Modifier.height(8.dp))
        }
        com.grapsee.grapmail.ui.components.AppTextField(
            label = stringResource(R.string.acct_password),
            value = password,
            onValueChange = { password = it; error = null },
            password = true,
            hint = stringResource(R.string.acct_enter_your_password),
            autofill = com.grapsee.grapmail.ui.components.GMAutofill.Password,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.acct_without_two_step_verification_a),
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
        title = stringResource(R.string.acct_two_step_verification_disabled),
        body = stringResource(R.string.acct_two_step_verification_has_been),
        footer = {
            Column {
                GradientButton(stringResource(R.string.acct_continue), onDone)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink(stringResource(R.string.acct_turn_it_back_on), onSecureNow)
                }
            }
        },
    ) {
        InfoCard {
            Text(stringResource(R.string.acct_what_happens_now), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            NeedRow(Icons.Rounded.Password, stringResource(R.string.acct_password_only), stringResource(R.string.acct_signing_in_no_longer_asks_for_a_code))
            NeedRow(Icons.Rounded.Textsms, stringResource(R.string.acct_backup_codes_retired), stringResource(R.string.acct_the_codes_you_saved_no_longer_apply))
        }
        Spacer(Modifier.height(12.dp))
        FooterNote(
            text = stringResource(R.string.acct_you_can_turn_two_step_verification),
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
    // True when THIS install's key answers signatures with no biometric gate —
    // generated on a device without strong biometrics, or minted before the
    // gate existed. Surfaced below: security never silently weakens.
    var keyWithoutBiometricLock by remember { mutableStateOf(false) }

    suspend fun load() {
        val session = SessionStore.snapshot(context) ?: run {
            failed = true; loading = false
            return
        }
        loading = true
        failed = false
        ownRowId = TrustedDeviceManager.enrolledDeviceRowId(context, session.address)
        hasLocalKey = com.grapsee.grapmail.security.DeviceKeyManager.hasKey()
        keyWithoutBiometricLock = com.grapsee.grapmail.security.DeviceKeyManager.isUnlockedKey()
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
                val active = SessionStore.snapshot(context) ?: throw IllegalStateException(context.getString(R.string.acct_sign_in_again))
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
                val active = SessionStore.snapshot(context) ?: throw IllegalStateException(context.getString(R.string.acct_sign_in_again))
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
        title = stringResource(R.string.acct_trusted_devices),
        body = stringResource(R.string.acct_trusted_devices_body) +
            if (keyWithoutBiometricLock) stringResource(R.string.acct_trusted_devices_no_biometric_suffix) else "",
        footer = {
            Column {
                if (hasLocalKey && devices != null && devices?.none { it.id == ownRowId } == true) {
                    GradientButton(
                        stringResource(R.string.acct_trust_this_device),
                        onClick = { reEnroll() },
                        busy = enrolling,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink(stringResource(R.string.acct_back_to_account_security), onBack)
                }
            }
        },
    ) {
        LoadStateBlock(loading = loading, failed = failed, onRetry = { scope.launch { load() } }) {
            val rows = devices ?: return@LoadStateBlock
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.acct_no_trusted_devices_yet_this_device),
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
            title = stringResource(R.string.acct_what_trusted_means),
            body = stringResource(R.string.acct_a_trusted_device_can_prove_itself),
        )
    }

    revokeTarget?.let { device ->
        AlertDialog(
            onDismissRequest = { if (busyId == null) revokeTarget = null },
            title = { Text(stringResource(R.string.acct_remove_trusted_device)) },
            text = {
                Text(
                    stringResource(
                        if (device.id == ownRowId) R.string.acct_remove_trusted_device_own else R.string.acct_remove_trusted_device_other,
                        device.displayName.ifBlank { stringResource(R.string.acct_this_device) },
                    ),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = busyId == null,
                    onClick = { revoke(device) },
                ) { Text(stringResource(R.string.acct_remove_trust), color = palette.error) }
            },
            dismissButton = {
                TextButton(enabled = busyId == null, onClick = { revokeTarget = null }) { Text(stringResource(R.string.acct_keep)) }
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
                device.displayName.ifBlank { stringResource(R.string.acct_unknown_device) },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isThisDevice) {
                Text(
                    stringResource(R.string.acct_this_device),
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
                // Wraps under its own width at large font sizes instead of being clipped.
                Text(stringResource(R.string.acct_last_seen_dot, relativeTime(it)), fontSize = 12.sp, color = palette.textSecondary, modifier = Modifier.weight(1f, fill = false))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            listOfNotNull(
                device.trustState.replaceFirstChar { it.uppercaseChar() }.takeIf { it.isNotBlank() },
                device.createdAt?.takeIf(String::isNotBlank)?.let { stringResource(R.string.acct_added_at, absoluteDateTime(it)) },
            ).joinToString(" · "),
            fontSize = 11.sp,
            color = palette.textTertiary,
        )
        if (!device.isRevoked) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    // 48dp touch target for a destructive action.
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy, role = androidx.compose.ui.semantics.Role.Button, onClick = onRevoke)
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (busy) stringResource(R.string.acct_removing) else stringResource(R.string.acct_remove_trust),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (busy) palette.textTertiary else palette.error,
                )
            }
        } else {
            Spacer(Modifier.height(10.dp))
            StatusChip(stringResource(R.string.acct_revoked), ChipTone.Warning)
        }
    }
}

/** The row's platform icon; an unknown platform is drawn as a generic device. */
private fun trustedDeviceIcon(platform: String): ImageVector = when (platform.lowercase()) {
    "android" -> Icons.Rounded.PhoneAndroid
    "ios" -> Icons.Rounded.PhoneIphone
    else -> Icons.Rounded.DevicesOther
}

@Composable
private fun trustedPlatformLabel(platform: String): String = when (platform.lowercase()) {
    "android" -> "Android"
    "ios" -> "iPhone / iPad"
    else -> platform.replaceFirstChar { it.uppercaseChar() }.ifBlank { stringResource(R.string.acct_unknown_platform) }
}
