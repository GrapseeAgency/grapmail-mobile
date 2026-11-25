package com.grapsee.grapmail.ui.screens.security

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.NotificationPrefDto
import com.grapsee.grapmail.data.QuietHoursBody
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
import java.util.Locale
import java.util.TimeZone
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R

/**
 * Push notification preferences — the account-level rows the backend reads
 * when it decides what to send where.
 *
 * The screen edits three scopes and is honest about what each is:
 *  - Mail: a real toggle, plus quiet hours — the only scope a person should
 *    ever want silenced at 3am.
 *  - Security alerts: displayed, never switchable. An alert that says a
 *    stranger signed in is the one notification the product must not be able
 *    to silence, so the row carries the server's state and a note instead of
 *    a dead switch — the project's rule about controls that change nothing.
 *  - Account alerts: a real toggle for the quieter lifecycle notices.
 *
 * Every write PATCHes one row with deviceKey "account", handing back the quiet
 * window that is already stored — a toggle that silently wiped the quiet hours
 * would be a second bug wearing a feature's clothes. Only the Clear action
 * sends `quiet: null`, because that is the one write where null is the point.
 */
@Composable
fun NotificationPrefsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var prefs by remember { mutableStateOf<List<NotificationPrefDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var busyScope by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Draft quiet window in minutes-of-day, applied only on the explicit press.
    var draftStart by remember { mutableStateOf<Int?>(null) }
    var draftEnd by remember { mutableStateOf<Int?>(null) }

    suspend fun load() {
        val session = SessionStore.snapshot(context) ?: run {
            failed = true; loading = false
            return
        }
        loading = true
        failed = false
        try {
            val resp = Api.getNotificationPrefs(session.token)
            if (!SessionStore.isCurrent(context, session.id)) return
            prefs = resp.prefs
            val mail = resp.prefs.firstOrNull { it.deviceKey == "account" && it.scope == "mail" }
            draftStart = mail?.quietStartMin
            draftEnd = mail?.quietEndMin
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (SessionStore.isCurrent(context, session.id)) failed = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    fun prefFor(scopeName: String): NotificationPrefDto? =
        prefs.firstOrNull { it.deviceKey == "account" && it.scope == scopeName }

    fun quietOf(pref: NotificationPrefDto?): QuietHoursBody? {
        // Explicit locals rather than relying on elvis smart-casts through a
        // safe-call chain: what the compiler proves here should be readable
        // too, and the row/start/end split makes each null answer its own.
        val row = pref ?: return null
        val start = row.quietStartMin ?: return null
        val end = row.quietEndMin ?: return null
        return QuietHoursBody(start, end, row.quietTzOffsetMin ?: deviceTzOffsetMin())
    }

    /**
     * One PATCH for one scope row. `quiet` travels as-is: the stored window on
     * a toggle, an explicit null only on the Clear action.
     */
    fun patchPref(prefScope: String, enabled: Boolean, quiet: QuietHoursBody?) {
        scope.launch {
            busyScope = prefScope
            error = null
            try {
                val session = SessionStore.snapshot(context)
                    ?: throw IllegalStateException(context.getString(R.string.acct_sign_in_again))
                val resp = Api.patchNotificationPrefs(
                    session.token,
                    deviceKey = "account",
                    scope = prefScope,
                    eventType = "",
                    enabled = enabled,
                    quiet = quiet,
                )
                if (!SessionStore.isCurrent(context, session.id)) return@launch
                val updated = resp.pref
                if (updated != null) {
                    prefs = prefs.filterNot { it.deviceKey == "account" && it.scope == prefScope } + updated
                    if (prefScope == "mail") {
                        draftStart = updated.quietStartMin
                        draftEnd = updated.quietEndMin
                    }
                }
                // No row back: leave prefs as they are and let the next entry
                // re-read, rather than drawing a row the server never sent.
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                busyScope = null
            }
        }
    }

    val mail = prefFor("mail")
    val account = prefFor("account")
    val security = prefFor("security")

    val storedQuiet = quietOf(mail)
    val storedAccountQuiet = quietOf(account)
    // The draft, materialised. Local copies, not smart casts: delegated state
    // properties never smart-cast, and the !! form reads like an assertion the
    // null check above already made.
    val draftStartValue = draftStart
    val draftEndValue = draftEnd
    val draftQuiet = if (draftStartValue != null && draftEndValue != null) {
        QuietHoursBody(draftStartValue, draftEndValue, deviceTzOffsetMin())
    } else null
    val quietDirty = draftQuiet != storedQuiet

    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_activity",
        illustrationWidth = 150,
        title = stringResource(R.string.acct_notification_preferences),
        body = stringResource(R.string.acct_choose_what_this_account_sends_and),
        footer = {
            Column {
                if (mail != null) {
                    GradientButton(
                        stringResource(R.string.acct_apply_quiet_hours),
                        onClick = { draftQuiet?.let { patchPref("mail", mail.enabled, it) } },
                        enabled = quietDirty && draftQuiet != null && busyScope == null,
                        busy = busyScope == "mail",
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
            InfoCard {
                Text(stringResource(R.string.acct_alerts), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                Spacer(Modifier.height(10.dp))
                PrefSwitchRow(
                    icon = Icons.Rounded.MailOutline,
                    title = stringResource(R.string.acct_mail_notifications),
                    subtitle = stringResource(R.string.acct_new_mail_that_reaches_your_inbox),
                    checked = mail?.enabled == true,
                    enabled = mail != null && busyScope == null,
                    onToggle = { on -> mail?.let { patchPref("mail", on, storedQuiet) } },
                )
                RowDivider()
                PrefSwitchRow(
                    icon = Icons.Rounded.Shield,
                    title = stringResource(R.string.acct_security_alerts),
                    subtitle = stringResource(R.string.acct_sign_ins_password_changes_trusted),
                    checked = security?.enabled != false,
                    enabled = false,
                    onToggle = { },
                )
                RowDivider()
                PrefSwitchRow(
                    icon = Icons.Rounded.Person,
                    title = stringResource(R.string.acct_account_alerts),
                    subtitle = stringResource(R.string.acct_lifecycle_notices_about_your_account),
                    checked = account?.enabled == true,
                    enabled = account != null && busyScope == null,
                    onToggle = { on -> account?.let { patchPref("account", on, storedAccountQuiet) } },
                )
            }
            Spacer(Modifier.height(14.dp))
            // Quiet hours, mail only: the security row above is the answer to
            // "what about alerts?", and this card is the answer to "when?".
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(18.dp))
                    // The title takes the slack and wraps at large font sizes,
                    // so the "Paused" chip is never pushed off the card.
                    Text(stringResource(R.string.acct_quiet_hours_mail_only), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, modifier = Modifier.weight(1f))
                    if (draftQuiet != null) {
                        StatusChip(stringResource(R.string.acct_paused), ChipTone.Success)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.acct_while_quiet_hours_are_on_mail),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = palette.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                QuietTimeRow(stringResource(R.string.acct_quiet_from), draftStart) { picked ->
                    draftStart = picked
                    // A start without an end is a half-window the Apply button
                    // refuses; default the end two hours after the start so
                    // the first pick yields an applyable pair.
                    if (draftEnd == null) draftEnd = (picked + 120) % (24 * 60)
                }
                RowDivider()
                QuietTimeRow(stringResource(R.string.acct_quiet_until), draftEnd) { picked -> draftEnd = picked }
                if (storedQuiet != null && !quietDirty) {
                    Spacer(Modifier.height(8.dp))
                    AccountLink(stringResource(R.string.acct_clear_quiet_hours), {
                        draftStart = null
                        draftEnd = null
                        mail?.let { patchPref("mail", it.enabled, null) }
                    })
                }
            }
            Spacer(Modifier.height(12.dp))
            if (security != null) {
                InfoNoteCard(
                    title = stringResource(R.string.acct_security_alerts_state),
                    body = if (security.enabled) stringResource(R.string.acct_security_alerts_are_on_for_this)
                    else stringResource(R.string.acct_the_account_row_reports_security),
                )
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error!!, fontSize = 12.sp, color = palette.error)
            }
            Spacer(Modifier.height(4.dp))
            FooterNote(text = stringResource(R.string.acct_preferences_apply_to_every_device), divider = false)
        }
    }
}

/** The device's UTC offset in minutes — what the backend's quiet window is judged against. */
private fun deviceTzOffsetMin(): Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

/** Minutes-of-day as "23:30". */
private fun formatMinute(min: Int): String {
    val m = ((min % (24 * 60)) + (24 * 60)) % (24 * 60)
    return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
}

@Composable
private fun PrefSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(palette.softFill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = palette.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = onToggle.takeIf { enabled }, enabled = enabled)
    }
}

/** One quiet-hours edge: the current value, tappable into a platform time picker. */
@Composable
private fun QuietTimeRow(label: String, minute: Int?, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable {
                val current = minute ?: (23 * 60)
                TimePickerDialog(
                    context,
                    { _, hourOfDay, minuteOfHour -> onPick(hourOfDay * 60 + minuteOfHour) },
                    current / 60,
                    current % 60,
                    true,
                ).show()
            }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 14.sp, color = palette.textPrimary, modifier = Modifier.weight(1f))
        Text(
            minute?.let(::formatMinute) ?: stringResource(R.string.acct_not_set),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (minute == null) palette.textTertiary else palette.primarySolid,
        )
    }
}
