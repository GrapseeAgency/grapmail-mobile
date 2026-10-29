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
        title = "Sign Out All Devices",
        body = "This will sign you out of all devices except this one.",
        footer = {
            Column {
                GradientButton(
                    "Sign Out All Devices",
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val token = SessionStore.token(context) ?: throw IllegalStateException("Sign in again.")
                                val resp = Api.signOutEverywhere(token)
                                // The epoch moved, so the token this request was
                                // made with is dead. Keep the replacement or the
                                // very next screen asks the person to sign in
                                // again right after being told they would not.
                                resp.accessToken?.let { fresh ->
                                    SessionStore.save(
                                        context,
                                        fresh,
                                        SessionStore.address(context) ?: "",
                                        SessionStore.name(context) ?: "",
                                        resp.tokenExpiresAtMillis,
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
                    AccountLink("Cancel", onBack)
                }
            }
        },
    ) {
        InfoCard {
            Text("You'll stay signed in here", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Every other phone, tablet and browser is signed out immediately. This device keeps working, so you can carry on.",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = palette.textSecondary,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Use this when a device is lost, borrowed or sold — not instead of changing a leaked password.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = palette.textSecondary,
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, fontSize = 12.sp, color = palette.error)
        }
        Spacer(Modifier.height(10.dp))
        FooterNote(text = "Sessions on other devices end the moment this completes.", divider = false)
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
        title = "Signed Out of All Devices",
        body = "You have been signed out of all devices except this one.",
        footer = {
            Column {
                GradientButton("Continue", onDone)
                Spacer(Modifier.height(6.dp))
                FooterNote(text = "Anyone using this account on another device now needs your password again.", divider = true)
            }
        },
    ) {
        InfoCard {
            Text("What happens next?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "This device stays signed in. Every other device has to sign in again, and anyone who had a session can no longer read this mailbox.",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = palette.textSecondary,
            )
        }
        Spacer(Modifier.height(12.dp))
        StatusChip("Only this device is signed in", ChipTone.Success)
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

    LaunchedEffect(Unit) {
        loading = true
        try {
            val token = SessionStore.token(context) ?: throw IllegalStateException("Sign in again.")
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
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_sessions",
        illustrationWidth = 170,
        title = "Manage Active Sessions",
        body = "Devices currently signed in to this account.",
        footer = {
            Column {
                AccountLink("Sign out all devices", onSignOutAll)
            }
        },
    ) {
        when {
            loading -> Text("Loading sessions…", fontSize = 13.sp, color = palette.textSecondary)
            error != null -> Text(error!!, fontSize = 13.sp, color = palette.error)
            sessions.isEmpty() -> Text("No sessions are recorded yet.", fontSize = 13.sp, color = palette.textSecondary)
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
                    Text("$activeCount Active Sessions", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, modifier = Modifier.weight(1f))
                    AccountLink("Revoke All", onSignOutAll)
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
                title = "Keep Your Account Secure",
                body = "If you see a device you don't recognize, revoke the session immediately. This will prevent unauthorized access.",
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
        title = "Revoke Session Access?",
        body = "This device will be signed out of your GrapMail account and will need to sign in again.",
        footer = {
            Column {
                DestructiveButton(
                    "Revoke Session",
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val token = SessionStore.token(context) ?: throw IllegalStateException("Sign in again.")
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
                    AccountLink("Keep this session", onBack)
                }
            }
        },
    ) {
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Rounded.Devices, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(18.dp))
                Column {
                    Text("Device", fontSize = 12.sp, color = palette.textSecondary)
                    Text(device.ifBlank { "That device" }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
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
        eyebrow = "Suspicious Access Detected",
        title = "Confirm Session Revocation",
        body = "This will immediately sign this session out and no longer have access to your GrapMail account.",
        footer = {
            Column {
                DestructiveButton(
                    "Revoke Session Access",
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val token = SessionStore.token(context) ?: throw IllegalStateException("Sign in again.")
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
                    AccountLink("Back", onBack)
                }
            }
        },
    ) {
        InfoCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = palette.error, modifier = Modifier.size(18.dp))
                Column {
                    Text("Unverified activity", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        (if (device.isNotBlank()) device + " · " else "") + "This session showed activity that did not match your usual sign-in pattern.",
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
        title = "Session Revoked Successfully",
        body = "The session has been revoked and this device can no longer access your account.",
        footer = {
            Column {
                GradientButton("Done", onDone)
                Spacer(Modifier.height(6.dp))
                FooterNote(text = "The device will need your password to sign in again.", divider = true)
            }
        },
    ) {
        InfoCard {
            Text("Revoked Session", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(device.ifBlank { "That device" }, fontSize = 14.sp, color = palette.textSecondary)
            Spacer(Modifier.height(10.dp))
            // No real revocation timestamp is carried to this screen, so it says
            // what is true (the device is out) rather than inventing a time.
            Text("This device is now signed out.", fontSize = 12.sp, color = palette.textTertiary)
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
        title = "Session Revoked Successfully",
        body = "The suspicious session has been revoked and can no longer access your GrapMail account.",
        footer = {
            Column {
                GradientButton("Done", onDone)
                Spacer(Modifier.height(6.dp))
                FooterNote(text = "Change your password too — a revoked session does not change a leaked one.", divider = true)
            }
        },
    ) {
        InfoCard {
            Text("Revoked Session Details", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Security, contentDescription = null, tint = palette.success, modifier = Modifier.size(16.dp))
                Text("This device is signed out", fontSize = 13.sp, color = palette.textSecondary)
            }
            Spacer(Modifier.height(4.dp))
            Text(device.ifBlank { "Unknown device" }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
        }
    }
}
