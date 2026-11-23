package com.grapsee.grapmail.ui.screens.mail

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
        if (token == null) { loadError = "Sign in again."; return@LaunchedEffect }
        try {
            val read = Api.profile(token).profile?.settings?.notifications
            if (read == null) loadError = "Your notification settings could not be read."
            else { NotificationPolicy.storeForCurrent(context, read); settings = read }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: "Your notification settings could not be read."
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

    MailScaffold("Notification filters", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 30.dp)) {
            val s = settings
            if (s == null) {
                Text(loadError ?: "Checking your settings…", fontSize = 14.sp,
                    color = if (loadError != null) palette.error else palette.textSecondary,
                    modifier = Modifier.padding(20.dp))
                if (loadError != null) TextButton(onClick = { reload++ }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("Try again")
                }
                return@Column
            }

            SectionLabel("Alert me about", palette)
            listOf(
                Triple("all", "All new mail", "Every message that reaches the inbox."),
                Triple("priority", "Priority only", "VIP senders and conversations you follow."),
                Triple("none", "Nothing", "Only conversations you follow, and security alerts."),
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

            SectionLabel("On the lock screen", palette)
            SwitchRow("Show sender and subject",
                if (s.showPreview) "Alerts name who wrote and what about." else "Alerts say only \"New mail\".",
                s.showPreview, !saving, "notify-preview") { on -> change { it.copy(showPreview = on) } }

            SectionLabel("Quiet hours", palette)
            SwitchRow("Pause alerts every day",
                if (s.quietHours.enabled) "From ${s.quietHours.start} to ${s.quietHours.end}" +
                    (s.quietHours.timeZone.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
                else "Off",
                s.quietHours.enabled, !saving, "notify-quiet") { on ->
                change { it.copy(quietHours = it.quietHours.copy(enabled = on, timeZone = deviceZone)) }
            }
            if (s.quietHours.enabled) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeChip("From", s.quietHours.start, !saving) {
                        pickTime(s.quietHours.start) { t ->
                            if (t == s.quietHours.end) note = "Quiet hours need to start and end at different times."
                            else change { it.copy(quietHours = it.quietHours.copy(start = t, timeZone = deviceZone)) }
                        }
                    }
                    TimeChip("To", s.quietHours.end, !saving) {
                        pickTime(s.quietHours.end) { t ->
                            if (t == s.quietHours.start) note = "Quiet hours need to start and end at different times."
                            else change { it.copy(quietHours = it.quietHours.copy(end = t, timeZone = deviceZone)) }
                        }
                    }
                }
                SwitchRow("Let VIPs through",
                    "VIP senders and conversations you follow still alert.",
                    s.quietHours.allowVip, !saving, "notify-quiet-vip") { on ->
                    change { it.copy(quietHours = it.quietHours.copy(allowVip = on)) }
                }
            }

            SectionLabel("VIP senders", palette)
            Text("An address (name@example.com) or a whole domain (example.com).",
                fontSize = 12.sp, color = palette.textSecondary, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = vipInput, onValueChange = { vipInput = it }, singleLine = true,
                    placeholder = { Text("Add a VIP") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f).testTag("notify-vip-input"),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(enabled = !saving && vipInput.isNotBlank(), onClick = {
                    val v = vipInput.trim().lowercase().removePrefix("@")
                    val looksRight = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$").matches(v) ||
                        Regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$").matches(v)
                    if (!looksRight) note = "\"$v\" is not an address or a domain."
                    else { vipInput = ""; change("Added $v.") { it.copy(vipSenders = it.vipSenders - v + v) } }
                }) { Text("Add") }
            }
            if (s.vipSenders.isEmpty()) {
                Text("No VIPs yet. You can also add one from a message's menu.", fontSize = 13.sp,
                    color = palette.textTertiary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            s.vipSenders.forEach { v ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(v, fontSize = 14.sp, color = palette.textPrimary, modifier = Modifier.weight(1f))
                    TextButton(enabled = !saving, onClick = { change("Removed $v.") { it.copy(vipSenders = it.vipSenders - v) } }) {
                        Text("Remove", color = palette.error)
                    }
                }
            }

            SectionLabel("Conversations", palette)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (val n = s.mutedThreads.size) { 0 -> "No muted conversations"; 1 -> "1 muted conversation"; else -> "$n muted conversations" },
                    fontSize = 14.sp, color = palette.textPrimary, modifier = Modifier.weight(1f),
                )
                if (s.mutedThreads.isNotEmpty()) TextButton(enabled = !saving, onClick = {
                    change("Every conversation can alert again.") { it.copy(mutedThreads = emptyList()) }
                }) { Text("Unmute all") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (val n = s.watchedThreads.size) { 0 -> "No followed conversations"; 1 -> "1 followed conversation"; else -> "$n followed conversations" },
                    fontSize = 14.sp, color = palette.textPrimary, modifier = Modifier.weight(1f),
                )
                if (s.watchedThreads.isNotEmpty()) TextButton(enabled = !saving, onClick = {
                    change("Stopped following every conversation.") { it.copy(watchedThreads = emptyList()) }
                }) { Text("Clear") }
            }
            Text("Mute or follow a conversation from a message's menu.", fontSize = 12.sp,
                color = palette.textTertiary, modifier = Modifier.padding(horizontal = 20.dp))

            Spacer(Modifier.height(16.dp))
            Text("Security alerts (new sign-ins, password and two-step changes) always come through.",
                fontSize = 12.sp, color = palette.textSecondary, modifier = Modifier.padding(horizontal = 20.dp))
            if (saving) Text("Saving…", fontSize = 13.sp, color = palette.textSecondary,
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
private fun TimeChip(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(palette.card)
            .border(1.dp, palette.border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClickLabel = "Change $label time", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 11.sp, color = palette.textSecondary)
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
    }
}
