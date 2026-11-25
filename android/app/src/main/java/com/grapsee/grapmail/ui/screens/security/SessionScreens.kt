package com.grapsee.grapmail.ui.screens.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.SessionDto
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.ui.components.AccountLink
import com.grapsee.grapmail.ui.components.AccountScreen
import com.grapsee.grapmail.ui.components.ChipTone
import com.grapsee.grapmail.ui.components.FooterNote
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.InfoCard
import com.grapsee.grapmail.ui.components.StatusChip
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.grapsee.grapmail.R

/** 13 — sign out everywhere else. Deliberately not instant: it is a lock-out. */
@Composable
fun SignOutAllDevicesScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_signout_all",
        illustrationWidth = 180,
        title = stringResource(R.string.acct_sign_out_all_devices_title),
        body = stringResource(R.string.acct_this_will_sign_you_out_of_all),
        footer = {
            Column {
                GradientButton(
                    stringResource(R.string.acct_sign_out_all_devices_title),
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val session = SessionStore.snapshot(context)
                                    ?: throw IllegalStateException(context.getString(R.string.acct_sign_in_again))
                                val resp = Api.signOutEverywhere(session.token)
                                val active = SessionStore.snapshot(context)
                                if (active == null || active.id != session.id) return@launch
                                resp.accessToken?.let { fresh ->
                                    SessionStore.updateTokens(
                                        context, fresh, refreshToken = resp.refreshToken,
                                        expiresAt = resp.tokenExpiresAtMillis,
                                        expectedToken = active.token,
                                    )
                                }
                                onDone()
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    busy = busy,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink(stringResource(R.string.acct_cancel), onBack)
                }
            }
        },
    ) {
        InfoCard {
            Text(stringResource(R.string.acct_youll_stay_signed_in_here), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.acct_every_other_phone_tablet_and_browser),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = palette.textSecondary,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.acct_use_this_when_a_device_is_lost),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = palette.textSecondary,
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, fontSize = 12.sp, color = palette.error)
        }
        Spacer(Modifier.height(10.dp))
        FooterNote(text = stringResource(R.string.acct_sessions_on_other_devices_end_the), divider = false)
    }
}

/** 14 — done. */
@Composable
fun SignedOutAllDevicesScreen(onDone: () -> Unit) {
    val palette = LocalPalette.current
    AccountScreen(
        onBack = onDone,
        illustration = "ill_sec_signout_all_ok",
        illustrationWidth = 170,
        title = stringResource(R.string.acct_signed_out_of_all_devices_2),
        body = stringResource(R.string.acct_you_have_been_signed_out_of_all),
        footer = {
            Column {
                GradientButton(stringResource(R.string.acct_continue), onDone)
                Spacer(Modifier.height(6.dp))
                FooterNote(text = stringResource(R.string.acct_anyone_using_this_account_on_another), divider = true)
            }
        },
    ) {
        InfoCard {
            Text(stringResource(R.string.acct_what_happens_next), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.acct_this_device_stays_signed_in_every),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = palette.textSecondary,
            )
        }
        Spacer(Modifier.height(12.dp))
        StatusChip(stringResource(R.string.acct_only_this_device_is_signed_in), ChipTone.Success)
    }
}

/** 15 — the real device list, current one marked. */
@Composable
fun ManageSessionsScreen(
    onBack: () -> Unit,
    /** (session id, device label) — the id is what revokes, the label is what
     *  the confirmation has to name, and this list already holds both. */
    onRevoke: (String, String) -> Unit,
    onRevokeAlert: (String, String) -> Unit,
    onSignOutAll: () -> Unit,
) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    var sessions by remember { mutableStateOf<List<SessionDto>>(emptyList()) }
    var activeCount by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // The device behind an open alert gets the stronger confirmation (21) rather
    // than the ordinary one (16): it is the device the person came here to deal
    // with, and the copy names the activity.
    var flaggedDeviceId by remember { mutableStateOf("") }
    // Bumped by Retry; the read below re-runs for each value.
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        loading = true
        error = null
        try {
            val token = SessionStore.token(context) ?: throw IllegalStateException(context.getString(R.string.acct_sign_in_again))
            val resp = Api.sessions(token)
            sessions = resp.sessions
            activeCount = if (resp.activeCount > 0) resp.activeCount else resp.sessions.size
            runCatching { Api.activity(token) }.getOrNull()?.let { activity ->
                // Only an alert the server still considers open marks a device.
                // Re-filtering the whole trail here flagged a dismissed or stale
                // sign-in, so revoking an unrelated device opened the break-in
                // confirmation with a fact the server denies. iOS already gated on
                // `suspicious`; this brings Android level with it.
                flaggedDeviceId = if (activity.suspicious) activity.suspiciousEvent?.deviceId ?: "" else ""
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A null message must not fall through to "no sessions recorded".
            error = e.message ?: context.getString(R.string.states_error_body)
        } finally {
            if (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive != false) loading = false
        }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_sessions",
        illustrationWidth = 170,
        title = stringResource(R.string.acct_manage_active_sessions_2),
        body = stringResource(R.string.acct_devices_currently_signed_in_to_this),
        footer = {
            Column {
                AccountLink(stringResource(R.string.acct_sign_out_all_devices), onSignOutAll)
            }
        },
    ) {
        when {
            loading -> com.grapsee.grapmail.ui.components.ListSkeleton(
                rows = 3, avatar = false, label = stringResource(R.string.acct_loading_sessions))
            error != null -> Column {
                // A failed read is said as a failure with its way back, never
                // left looking like an empty history.
                Text(stringResource(R.string.states_sessions_failed_title), fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(error!!, fontSize = 13.sp, color = palette.error)
                Spacer(Modifier.height(6.dp))
                AccountLink(stringResource(R.string.states_retry), { attempt++ })
            }
            sessions.isEmpty() -> Text(stringResource(R.string.acct_no_sessions_are_recorded_yet), fontSize = 13.sp, color = palette.textSecondary)
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // "N Active Sessions" + Revoke All, as the reference header. The
                // count is the server's true total, not the (paged) list length;
                // Revoke All routes to the confirm screen, since signing out
                // everywhere is a lock-out and is deliberately not one tap.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(palette.success),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(pluralStringResource(R.plurals.acct_active_sessions_count, activeCount, activeCount), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, modifier = Modifier.weight(1f))
                    AccountLink(stringResource(R.string.acct_revoke_all), onSignOutAll)
                }
                sessions.forEach { session ->
                    SessionCard(
                        session,
                        onRevoke = if (session.current) null else ({
                            // Identity, not the label: two Windows machines are both
                            // "Windows · Chrome", and matching on the string picked the
                            // alert variant by chance.
                            val flagged = flaggedDeviceId.isNotEmpty() && session.deviceId == flaggedDeviceId
                            if (flagged) onRevokeAlert(session.id, session.device)
                            else onRevoke(session.id, session.device)
                        }),
                    )
                }
            }
        }
        if (sessions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            InfoNoteCard(
                title = stringResource(R.string.acct_keep_your_account_secure),
                body = stringResource(R.string.acct_if_you_see_a_device_you_dont),
            )
        }
    }
}

/** 16 — a plain confirmation for a session the person picked themselves. */
@Composable
fun RevokeSessionScreen(sessionId: String, device: String, onBack: () -> Unit, onRevoke: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The route carries the id, because that is what revokes, and the device
    // label because the list the person tapped in already had it. Reading the
    // label back from a second sessions call meant the confirmation could only
    // ever say "That device" if that request was slow or failed — and made the
    // screen impossible to render without the network.

    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_session_revoke",
        illustrationWidth = 170,
        title = stringResource(R.string.acct_revoke_session_access),
        body = stringResource(R.string.acct_this_device_will_be_signed_out_of),
        footer = {
            Column {
                DestructiveButton(
                    stringResource(R.string.acct_revoke_session),
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val token = SessionStore.token(context) ?: throw IllegalStateException(context.getString(R.string.acct_sign_in_again))
                                Api.revokeSession(token, sessionId)
                                onRevoke(device)
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    busy = busy,
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink(stringResource(R.string.acct_keep_this_session), onBack)
                }
            }
        },
    ) {
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Rounded.Devices, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(18.dp))
                Column {
                    Text(stringResource(R.string.acct_device), fontSize = 12.sp, color = palette.textSecondary)
                    Text(device.ifBlank { stringResource(R.string.acct_that_device) }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        CannotBeUndoneCard()
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, fontSize = 12.sp, color = palette.error)
        }
    }
}

/** 21 — the same revoke, entered from the alert, so the danger is named. */
@Composable
fun ConfirmSessionRevocationScreen(sessionId: String, device: String, onBack: () -> Unit, onRevoke: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // `device` arrives with the route for the reason spelled out on
    // RevokeSessionScreen: the caller has the label, a second list call does not
    // add information, it only adds a way for the screen to come up empty.

    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_revoke_confirm",
        illustrationWidth = 170,
        eyebrow = stringResource(R.string.acct_suspicious_access_detected),
        title = stringResource(R.string.acct_confirm_session_revocation),
        body = stringResource(R.string.acct_this_will_immediately_sign_this),
        footer = {
            Column {
                DestructiveButton(
                    stringResource(R.string.acct_revoke_session_access_2),
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val token = SessionStore.token(context) ?: throw IllegalStateException(context.getString(R.string.acct_sign_in_again))
                                Api.revokeSession(token, sessionId)
                                onRevoke(device)
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    busy = busy,
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink(stringResource(R.string.acct_back), onBack)
                }
            }
        },
    ) {
        InfoCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = palette.error, modifier = Modifier.size(18.dp))
                Column {
                    Text(stringResource(R.string.acct_unverified_activity), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (device.isNotBlank()) stringResource(R.string.acct_unverified_activity_with_device, device)
                        else stringResource(R.string.acct_unverified_activity_body),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = palette.textSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        CannotBeUndoneCard()
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, fontSize = 12.sp, color = palette.error)
        }
    }
}

/** 17 — revoked, ordinary copy. */
@Composable
fun SessionRevokedScreen(device: String, onDone: () -> Unit) {
    val palette = LocalPalette.current
    AccountScreen(
        onBack = onDone,
        illustration = "ill_sec_session_revoked",
        illustrationWidth = 170,
        title = stringResource(R.string.acct_session_revoked_successfully),
        body = stringResource(R.string.acct_the_session_has_been_revoked_and),
        footer = {
            Column {
                GradientButton(stringResource(R.string.acct_done), onDone)
                Spacer(Modifier.height(6.dp))
                FooterNote(text = stringResource(R.string.acct_the_device_will_need_your_password), divider = true)
            }
        },
    ) {
        InfoCard {
            Text(stringResource(R.string.acct_revoked_session), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(device.ifBlank { stringResource(R.string.acct_that_device) }, fontSize = 14.sp, color = palette.textSecondary)
            Spacer(Modifier.height(10.dp))
            // No real revocation timestamp is carried to this screen, so it says
            // what is true (the device is out) rather than inventing a time.
            Text(stringResource(R.string.acct_this_device_is_now_signed_out), fontSize = 12.sp, color = palette.textTertiary)
        }
    }
}

/** 22 — revoked, entered from the alert. */
@Composable
fun SessionRevokedSuspiciousScreen(device: String, onDone: () -> Unit) {
    val palette = LocalPalette.current
    AccountScreen(
        onBack = onDone,
        illustration = "ill_sec_revoked_suspicious",
        illustrationWidth = 170,
        title = stringResource(R.string.acct_session_revoked_successfully),
        body = stringResource(R.string.acct_the_suspicious_session_has_been),
        footer = {
            Column {
                GradientButton(stringResource(R.string.acct_done), onDone)
                Spacer(Modifier.height(6.dp))
                FooterNote(text = stringResource(R.string.acct_change_your_password_too_a_revoked), divider = true)
            }
        },
    ) {
        InfoCard {
            Text(stringResource(R.string.acct_revoked_session_details), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Security, contentDescription = null, tint = palette.success, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.acct_this_device_is_signed_out), fontSize = 13.sp, color = palette.textSecondary)
            }
            Spacer(Modifier.height(4.dp))
            Text(device.ifBlank { stringResource(R.string.acct_unknown_device) }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
        }
    }
}
