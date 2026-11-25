package com.grapsee.grapmail.ui.screens.mail

import com.grapsee.grapmail.ui.components.GMLayout
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.NotificationSettingsDto
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.logic.NotificationPolicy
import com.grapsee.grapmail.ui.components.DetailSkeleton
import com.grapsee.grapmail.ui.components.ErrorState
import com.grapsee.grapmail.ui.components.OfflineBanner
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * Notification filters: which new mail may interrupt, and when.
 *
 * Every switch here changes a rule the server enforces before it sends a push
 * (backend lib/notification-policy.ts), so it works on every device signed in to
 * the account, not only this one. Each change is saved as it is made; the screen
 * shows what the server stored, never a value it only hoped to save.
 */
@Composable
fun NotificationFiltersDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NotificationFiltersScreen(onBack = onDismiss)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationFiltersScreen(onBack: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<NotificationSettingsDto?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var vipInput by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        loadError = null
        val token = SessionStore.token(context)
        if (token == null) { loadError = context.getString(R.string.mail_notify_sign_in_again); return@LaunchedEffect }
        try {
            val read = Api.profile(token).profile?.settings?.notifications
            if (read == null) loadError = context.getString(R.string.mail_notify_read_failed)
            else { NotificationPolicy.storeForCurrent(context, read); settings = read }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: context.getString(R.string.mail_notify_read_failed)
            // Offline or a failed read: keep the last stored settings on
            // screen (under the offline banner) instead of an error page.
            if (settings == null) settings = NotificationPolicy.cachedForCurrent(context)
        }
    }

    fun change(done: String? = null, edit: (NotificationSettingsDto) -> NotificationSettingsDto) {
        if (saving) return
        saving = true
        note = null
        scope.launch {
            val error = NotificationPolicy.update(context, edit)
            saving = false
            if (error == null) {
                settings = NotificationPolicy.cachedForCurrent(context)
                note = done
            } else note = error
        }
    }

    fun pickTime(current: String, onPicked: (String) -> Unit) {
        val h = current.substringBefore(':').toIntOrNull() ?: 22
        val m = current.substringAfter(':').toIntOrNull() ?: 0
        android.app.TimePickerDialog(context, { _, hour, minute ->
            onPicked("%02d:%02d".format(hour, minute))
        }, h, m, android.text.format.DateFormat.is24HourFormat(context)).show()
    }

    val deviceZone = remember { java.util.TimeZone.getDefault().id }

    MailScaffold(stringResource(R.string.mail_notify_title), onBack, maxContentWidth = GMLayout.Form) {
        OfflineBanner()
        if (settings == null) {
            if (loadError != null) ErrorState(onRetry = { reload++ }, message = loadError)
            else DetailSkeleton(label = stringResource(R.string.mail_notify_checking))
            return@MailScaffold
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 30.dp)) {
            val s = settings ?: return@Column

            SectionLabel(stringResource(R.string.mail_notify_alert_me_about), palette)
            listOf(
                Triple("all", stringResource(R.string.mail_notify_mode_all), stringResource(R.string.mail_notify_mode_all_detail)),
                Triple("priority", stringResource(R.string.mail_notify_mode_priority), stringResource(R.string.mail_notify_mode_priority_detail)),
                Triple("none", stringResource(R.string.mail_notify_mode_none), stringResource(R.string.mail_notify_mode_none_detail)),
            ).forEach { (value, title, detail) ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = !saving, role = Role.RadioButton) { if (s.mode != value) change { it.copy(mode = value) } }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("notify-mode-$value"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.mode == value, onClick = null, enabled = !saving,
                        colors = RadioButtonDefaults.colors(selectedColor = palette.primarySolid))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, fontSize = 15.sp, color = palette.textPrimary)
                        Text(detail, fontSize = 12.sp, color = palette.textSecondary)
                    }
                }
            }

            SectionLabel(stringResource(R.string.mail_notify_lock_screen), palette)
            SwitchRow(stringResource(R.string.mail_notify_show_preview),
                if (s.showPreview) stringResource(R.string.mail_notify_preview_on) else stringResource(R.string.mail_notify_preview_off),
                s.showPreview, !saving, "notify-preview") { on -> change { it.copy(showPreview = on) } }

            SectionLabel(stringResource(R.string.mail_notify_quiet_hours), palette)
            SwitchRow(stringResource(R.string.mail_notify_quiet_pause),
                if (s.quietHours.enabled) {
                    val zone = s.quietHours.timeZone
                    if (zone.isNotBlank()) stringResource(R.string.mail_notify_quiet_range_zone, s.quietHours.start, s.quietHours.end, zone)
                    else stringResource(R.string.mail_notify_quiet_range, s.quietHours.start, s.quietHours.end)
                }
                else stringResource(R.string.mail_common_off),
                s.quietHours.enabled, !saving, "notify-quiet") { on ->
                change { it.copy(quietHours = it.quietHours.copy(enabled = on, timeZone = deviceZone)) }
            }
            if (s.quietHours.enabled) {
                val sameTimes = stringResource(R.string.mail_notify_quiet_same_times)
                // Wraps: at large text the two times stack instead of the
                // second running off a narrow screen.
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeChip(stringResource(R.string.mail_notify_from), stringResource(R.string.mail_notify_change_from),
                        s.quietHours.start, !saving) {
                        pickTime(s.quietHours.start) { t ->
                            if (t == s.quietHours.end) note = sameTimes
                            else change { it.copy(quietHours = it.quietHours.copy(start = t, timeZone = deviceZone)) }
                        }
                    }
                    TimeChip(stringResource(R.string.mail_notify_to), stringResource(R.string.mail_notify_change_to),
                        s.quietHours.end, !saving) {
                        pickTime(s.quietHours.end) { t ->
                            if (t == s.quietHours.start) note = sameTimes
                            else change { it.copy(quietHours = it.quietHours.copy(end = t, timeZone = deviceZone)) }
                        }
                    }
                }
                SwitchRow(stringResource(R.string.mail_notify_vip_through),
                    stringResource(R.string.mail_notify_vip_through_detail),
                    s.quietHours.allowVip, !saving, "notify-quiet-vip") { on ->
                    change { it.copy(quietHours = it.quietHours.copy(allowVip = on)) }
                }
            }

            SectionLabel(stringResource(R.string.mail_notify_vip_senders), palette)
            Text(stringResource(R.string.mail_notify_vip_hint),
                fontSize = 12.sp, color = palette.textSecondary, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = vipInput, onValueChange = { vipInput = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.mail_notify_vip_placeholder)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f).testTag("notify-vip-input"),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(enabled = !saving && vipInput.isNotBlank(), onClick = {
                    val v = vipInput.trim().lowercase().removePrefix("@")
                    val looksRight = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$").matches(v) ||
                        Regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$").matches(v)
                    if (!looksRight) note = context.getString(R.string.mail_notify_vip_invalid, v)
                    else { vipInput = ""; change(context.getString(R.string.mail_notify_vip_added, v)) { it.copy(vipSenders = it.vipSenders - v + v) } }
                }) { Text(stringResource(R.string.mail_common_add)) }
            }
            if (s.vipSenders.isEmpty()) {
                Text(stringResource(R.string.mail_notify_vip_empty), fontSize = 13.sp,
                    color = palette.textTertiary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            s.vipSenders.forEach { v ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(v, fontSize = 14.sp, color = palette.textPrimary, modifier = Modifier.weight(1f))
                    TextButton(enabled = !saving, onClick = { change(context.getString(R.string.mail_notify_vip_removed, v)) { it.copy(vipSenders = it.vipSenders - v) } }) {
                        Text(stringResource(R.string.mail_common_remove), color = palette.error)
                    }
                }
            }

            SectionLabel(stringResource(R.string.mail_notify_conversations), palette)
            val unmutedAll = stringResource(R.string.mail_notify_unmuted_all)
            val unfollowedAll = stringResource(R.string.mail_notify_unfollowed_all)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (val n = s.mutedThreads.size) {
                        0 -> stringResource(R.string.mail_notify_muted_none)
                        else -> pluralStringResource(R.plurals.mail_notify_muted_count, n, n)
                    },
                    fontSize = 14.sp, color = palette.textPrimary, modifier = Modifier.weight(1f),
                )
                if (s.mutedThreads.isNotEmpty()) TextButton(enabled = !saving, onClick = {
                    change(unmutedAll) { it.copy(mutedThreads = emptyList()) }
                }) { Text(stringResource(R.string.mail_notify_unmute_all)) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (val n = s.watchedThreads.size) {
                        0 -> stringResource(R.string.mail_notify_followed_none)
                        else -> pluralStringResource(R.plurals.mail_notify_followed_count, n, n)
                    },
                    fontSize = 14.sp, color = palette.textPrimary, modifier = Modifier.weight(1f),
                )
                if (s.watchedThreads.isNotEmpty()) TextButton(enabled = !saving, onClick = {
                    change(unfollowedAll) { it.copy(watchedThreads = emptyList()) }
                }) { Text(stringResource(R.string.mail_common_clear)) }
            }
            Text(stringResource(R.string.mail_notify_mute_hint), fontSize = 12.sp,
                color = palette.textTertiary, modifier = Modifier.padding(horizontal = 20.dp))

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.mail_notify_security_note),
                fontSize = 12.sp, color = palette.textSecondary, modifier = Modifier.padding(horizontal = 20.dp))
            if (saving) Text(stringResource(R.string.mail_common_saving), fontSize = 13.sp, color = palette.textSecondary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            note?.let {
                Text(it, fontSize = 13.sp, color = palette.textSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).testTag("notify-note"))
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = palette.textPrimary)
            Text(detail, fontSize = 12.sp, color = palette.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = palette.primarySolid))
    }
}

@Composable
private fun TimeChip(label: String, clickLabel: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(palette.card)
            .border(1.dp, palette.border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClickLabel = clickLabel, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 11.sp, color = palette.textSecondary)
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
    }
}
