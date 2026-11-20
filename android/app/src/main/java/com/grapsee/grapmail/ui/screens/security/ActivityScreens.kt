package com.grapsee.grapmail.ui.screens.security

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
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
import com.grapsee.grapmail.data.ActivityDto
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ProfileDto
import com.grapsee.grapmail.data.SessionDto
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.ui.components.AccountLink
import com.grapsee.grapmail.ui.components.AccountScreen
import com.grapsee.grapmail.ui.components.ChipTone
import com.grapsee.grapmail.ui.components.FooterNote
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.InfoCard
import com.grapsee.grapmail.ui.components.OutlineButton
import com.grapsee.grapmail.ui.components.StatusChip
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * 18 — the account's own history.
 *
 * The rows come from events written when they happened, so an empty list is a
 * new account and never a failed query. A recent warning surfaces the alert
 * screen at the top rather than being buried in row four.
 */
@Composable
fun SecurityActivityScreen(
    onBack: () -> Unit,
    onOpenAlert: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    var events by remember { mutableStateOf<List<ActivityDto>>(emptyList()) }
    var suspicious by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val token = SessionStore.token(context) ?: throw IllegalStateException("Sign in again.")
            val resp = Api.activity(token)
            events = resp.events
            suspicious = resp.suspicious
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_activity",
        illustrationWidth = 150,
        title = "Recent Security Activity",
        body = "Sign-ins and changes to your account, newest first.",
        footer = {
            Column {
                AccountLink("Manage active sessions", onOpenSessions)
            }
        },
    ) {
        if (suspicious) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.error.copy(alpha = 0.08f))
                    .border(1.dp, palette.error.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = palette.error, modifier = Modifier.size(17.dp))
                Column(Modifier.weight(1f)) {
                    Text("Unrecognized activity", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.error)
                    Spacer(Modifier.height(2.dp))
                    Text("A device signed in that this account had not used before.", fontSize = 12.sp, lineHeight = 17.sp, color = palette.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    AccountLink("Review it now", onOpenAlert)
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        when {
            loading -> Text("Loading activity…", fontSize = 13.sp, color = palette.textSecondary)
            error != null -> Text(error!!, fontSize = 13.sp, color = palette.error)
            events.isEmpty() -> Text("Nothing has happened on this account yet.", fontSize = 13.sp, color = palette.textSecondary)
            else -> InfoCard {
                events.forEachIndexed { index, event ->
                    ActivityRow(event)
                    if (index < events.lastIndex) RowDivider()
                }
            }
        }
    }
}

/**
 * 19 — the alert itself.
 *
 * It names what happened, on which device, and offers exactly two honest ways
 * out: secure the account, or say it was you. Saying "this was me" records
 * nothing false — it simply leaves the warning in the history as reviewed by
 * returning, because the event did happen.
 */
@Composable
fun SuspiciousSignInAlertScreen(
    onBack: () -> Unit,
    onSecure: () -> Unit,
    onThisWasMe: () -> Unit,
) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var event by remember { mutableStateOf<ActivityDto?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var ackError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            val token = SessionStore.token(context) ?: return@runCatching
            // The server already picked the one sign-in it considers suspicious;
            // re-filtering the whole trail here flagged dismissed or stale sign-ins
            // as a break-in. Read the named event, not a guess.
            event = Api.activity(token).suspiciousEvent
        }
        loaded = true
    }

    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_alert",
        illustrationWidth = 170,
        eyebrow = "Security Alert",
        title = "Suspicious Sign-In Alert",
        body = "We noticed a sign-in to your account from a device we do not recognise.",
        footer = {
            Column {
                GradientButton("Secure My Account", onSecure)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink("This was me", {
                        // Dismissing is a write, not a navigation. The old handler
                        // popped back whether the write landed or not, so a failed
                        // acknowledgement looked like success and the warning stayed
                        // lit on the next load. Navigate only when the server agreed.
                        scope.launch {
                            val token = SessionStore.token(context)
                            if (token == null) { ackError = "Sign in again to record that."; return@launch }
                            runCatching { Api.acknowledgeSignIn(token) }
                                .onSuccess { onThisWasMe() }
                                .onFailure { ackError = it.message ?: "We couldn't record that. Try again." }
                        }
                    })
                }
                if (ackError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(ackError!!, fontSize = 12.sp, color = palette.error)
                }
            }
        },
    ) {
        InfoCard {
            Text("Sign-in details", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            // Never a guess. Before the fetch lands, or when there is no open
            // alert, the honest answer is "not available" — not "Just now", which
            // could push someone to revoke their own current device.
            if (!loaded) {
                Text("Checking your account…", fontSize = 13.sp, color = palette.textSecondary)
            } else {
                Detail("Device", event?.device?.ifBlank { "Not recorded" } ?: "Not recorded")
                Detail("When", event?.at?.let { absoluteDateTime(it) } ?: "Not available")
                Detail("Address", event?.ip?.ifBlank { "Not recorded" } ?: "Not recorded")
                Spacer(Modifier.height(10.dp))
                StatusChip("Not recognised", ChipTone.Warning)
            }
        }
        Spacer(Modifier.height(12.dp))
        CannotBeUndoneCard(
            "This wasn't you? Someone may have gained access to your account. Take action now to keep your data safe.",
        )
    }
}

@Composable
private fun Detail(label: String, value: String) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = palette.textSecondary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
    }
}

/**
 * 20 — Secure My Account, per the reference: the suspicious sign-in spelled out
 * (Location / Device / Time), a red "this looks suspicious" warning, and the two
 * actions that end the break-in — Revoke This Session and Change Password — with
 * a way back to the activity trail. The headline purpose is cutting the intruder
 * off from here, so Revoke is on-screen, not buried behind the device list.
 */
@Composable
fun SecureMyAccountScreen(
    onBack: () -> Unit,
    onChangePassword: () -> Unit,
    onBackToActivity: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<SessionDto>>(emptyList()) }
    var alertAt by remember { mutableStateOf<String?>(null) }
    var alertDevice by remember { mutableStateOf("") }
    var alertIp by remember { mutableStateOf("") }
    var flaggedDeviceId by remember { mutableStateOf("") }
    var revokeBusy by remember { mutableStateOf(false) }
    var revokeError by remember { mutableStateOf<String?>(null) }
    var sessionsLoaded by remember { mutableStateOf(false) }
    var alertKnown by remember { mutableStateOf(false) }

    suspend fun reload() {
        val token = SessionStore.token(context) ?: return
        runCatching { sessions = Api.sessions(token).sessions }
        // Settled either way. Gating the flag on success left a failed call
        // meaning "Checking your account…" forever, with no retry and no error,
        // while the rows below already answer a missing value honestly ("Not
        // recorded" / "Not available"). A failed fetch must land there.
        sessionsLoaded = true
        runCatching {
            val activity = Api.activity(token)
            // The server names the suspicious sign-in; re-picking the newest
            // warning here would flag a dismissed or stale one as a break-in.
            val alert = activity.suspiciousEvent
            alertAt = alert?.at
            alertDevice = alert?.device ?: ""
            alertIp = alert?.ip ?: ""
            flaggedDeviceId = alert?.deviceId ?: ""
        }
        alertKnown = true
    }

    LaunchedEffect(Unit) { reload() }

    val checking = !sessionsLoaded || !alertKnown
    // The live session on the flagged device, if any: what makes "Revoke This
    // Session" cut the intruder off now rather than being a second route to the list.
    val flaggedSession = sessions.firstOrNull { !it.current && flaggedDeviceId.isNotEmpty() && it.deviceId == flaggedDeviceId }

    AccountScreen(
        onBack = onBackToActivity,
        illustration = "ill_sec_secure",
        illustrationWidth = 170,
        title = "Secure My Account",
        body = "We found a suspicious sign-in to your account. Review the details, revoke the access if it wasn't you, and change your password.",
        footer = {
            Column {
                if (flaggedSession != null) {
                    GradientButton(
                        "Revoke This Session",
                        onClick = {
                            revokeBusy = true; revokeError = null
                            scope.launch {
                                val token = SessionStore.token(context)
                                if (token == null) { revokeError = "Sign in again to continue."; revokeBusy = false; return@launch }
                                runCatching { Api.revokeSession(token, flaggedSession.id) }
                                    .onSuccess { reload() }
                                    .onFailure { revokeError = it.message ?: "We couldn't sign that device out." }
                                revokeBusy = false
                            }
                        },
                        busy = revokeBusy,
                    )
                    if (revokeError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(revokeError!!, fontSize = 12.sp, color = palette.error)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlineButton("Change Password", onClick = onChangePassword)
                } else if (!checking) {
                    // No live session on the flagged device: say so honestly rather
                    // than offer a revoke that would do nothing.
                    Text(
                        "That device is already signed out. Change your password to end any other session.",
                        fontSize = 12.sp, lineHeight = 17.sp, color = palette.textSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    GradientButton("Change Password", onClick = onChangePassword)
                }
                Spacer(Modifier.height(6.dp))
                BackLink("Back to Security Activity", onBackToActivity)
            }
        },
    ) {
        if (checking) {
            Text("Checking your account…", fontSize = 13.sp, color = palette.textSecondary)
            return@AccountScreen
        }
        InfoCard {
            Text("Suspicious Sign-In Details", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            // Location is the masked IP only — there is no GeoIP source, so no city
            // is invented. The caption says so plainly.
            DetailRow(Icons.Rounded.Place, "Location", alertIp.ifBlank { "Not recorded" }, "Approximate location based on IP")
            DetailRow(Icons.Rounded.Devices, "Device", alertDevice.ifBlank { "Not recorded" })
            DetailRow(Icons.Rounded.Schedule, "Time", alertAt?.let { absoluteDateTime(it) } ?: "Not available", "Your local time")
        }
        Spacer(Modifier.height(12.dp))
        CannotBeUndoneCard(
            "This looks suspicious. If this wasn't you, we recommend revoking the access and changing your password immediately.",
        )
    }
}
