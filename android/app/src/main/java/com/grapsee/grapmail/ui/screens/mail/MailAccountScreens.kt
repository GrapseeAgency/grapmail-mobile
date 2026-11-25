package com.grapsee.grapmail.ui.screens.mail

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.LocaleDto
import com.grapsee.grapmail.data.ProfileConnectionDto
import com.grapsee.grapmail.data.ProfilePatch
import com.grapsee.grapmail.data.ProfileSettingsPatch
import com.grapsee.grapmail.logic.GenderOption
import com.grapsee.grapmail.logic.ProfileViewModel
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.GMLayout
import com.grapsee.grapmail.ui.components.gmMaxWidth
import com.grapsee.grapmail.ui.theme.LocalPalette
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import com.grapsee.grapmail.ui.i18n.CoreStrings

/**
 * The account-record screens — the mobile form of the web client's
 * `/mail/notes`, `/mail/tasks`, `/mail/settings`, `/mail/account/addresses`,
 * `/mail/account/connections`, `/mail/account/profile`,
 * `/mail/account/privacy` and `/mail/support`.
 *
 * Every value on every screen comes from the one profile document
 * (`ProfileViewModel`); nothing here has a local store, a default that stands in
 * for an unread value, or a control the server cannot honour. The web pages are
 * the reference for wording and behaviour, including the parts where the web
 * REFUSES to offer a control (no grant button on Connections, no delete button on
 * Privacy) because offering one would be a button that lies.
 */

// ── shared little pieces ──────────────────────────────────────────────────────

/** The save outcome. Bad in error ink, good in success ink, never silent. */
@Composable
private fun NoteLine(text: String, bad: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Text(text, fontSize = 13.sp,
        color = if (bad) palette.error else palette.success,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 6.dp))
}

@Composable
private fun ListRow(text: String, onRemove: () -> Unit, testTag: String) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().testTag(testTag).padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text, fontSize = 15.sp, color = palette.textPrimary,
            modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.Delete, stringResource(R.string.mail_common_remove), tint = palette.textTertiary,
            modifier = Modifier.size(20.dp).clickable(onClick = onRemove))
    }
}

/** A labelled radio row: one option of a fixed set, written the moment it is picked. */
@Composable
private fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape)
            .border(1.5.dp, if (selected) palette.primarySolid else palette.border, CircleShape)
            .background(if (selected) palette.primarySolid else palette.card),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Rounded.Check, null, tint = palette.onPrimary,
                modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 15.sp, color = palette.textPrimary, modifier = Modifier.weight(1f))
    }
}

/** The add-a-row editor every list screen shares: field, button, empty guard. */
@Composable
private fun AddRow(
    hint: String,
    value: String,
    onChange: (String) -> Unit,
    onAdd: () -> Unit,
    busy: Boolean,
    tag: String,
) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(Modifier.weight(1f)) {
            AppTextField("", value, onChange, hint = hint, showLabel = false)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(44.dp).clip(CircleShape)
            .background(if (value.isNotBlank() && !busy) palette.primarySolid else palette.buttonDisabled)
            .then(if (value.isNotBlank() && !busy) Modifier.clickable(onClick = onAdd) else Modifier)
            .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = palette.onPrimary,
                strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Add, stringResource(R.string.mail_common_add), tint = palette.onPrimary)
        }
    }
}

// ── 92 · notes ───────────────────────────────────────────────────────────────

/**
 * Short notes kept on the account, beside the mailbox they belong to — the web's
 * `/mail/notes` (`notes/page.tsx`), stored in `settings.notes`.
 */
@Composable
fun NotesScreen(vm: ProfileViewModel, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.load() }
    MailScaffold(stringResource(R.string.mail_notes_notes), onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            // Phones: no change. Tablets, foldables and desktop windows: the
            // form keeps a readable width, centred (ui/components/Adaptive.kt).
            .gmMaxWidth(GMLayout.Form)
            .padding(horizontal = 20.dp)) {
            state.note?.let { NoteLine(it, state.noteIsBad) }
            AddRow(stringResource(R.string.mail_notes_write_note), draft, { draft = it }, {
                val list = state.profile?.settings?.notes ?: return@AddRow
                vm.saveSettings(ProfileSettingsPatch(notes = listOf(draft.trim()) + list))
                draft = ""
            }, state.busy, "gm-add-note")
            Text(stringResource(R.string.mail_notes_notes_stored_account_not), fontSize = 12.sp, color = palette.textTertiary,
                modifier = Modifier.padding(vertical = 6.dp))
            val notes = state.profile?.settings?.notes
            when {
                state.profile == null && state.loading ->
                    com.grapsee.grapmail.ui.components.ListSkeleton(rows = 3, avatar = false,
                        label = stringResource(R.string.mail_common_loading))
                state.profile == null && state.fatal != null -> Column {
                    // `fatal` is a delegated property, so it cannot be smart-cast;
                    // the branch already proved it is non-null.
                    Text(stringResource(R.string.mail_notes_notes_could_not_read, state.fatal ?: ""), fontSize = 14.sp,
                        color = palette.textSecondary, modifier = Modifier.padding(top = 16.dp))
                    // The read that failed, asked again.
                    androidx.compose.material3.TextButton(onClick = { vm.load(force = true) }) {
                        Text(stringResource(R.string.states_retry))
                    }
                }
                notes != null && notes.isEmpty() ->
                    Text(stringResource(R.string.mail_notes_nothing_kept_here_yet), fontSize = 14.sp, color = palette.textTertiary,
                        modifier = Modifier.padding(vertical = 16.dp))
                notes != null -> notes.forEach { note ->
                    ListRow(note, {
                        vm.saveSettings(ProfileSettingsPatch(
                            notes = notes.filter { it != note }))
                    }, "gm-note-row")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 93 · tasks ───────────────────────────────────────────────────────────────

/**
 * The short list beside the mailbox — the web's `/mail/tasks`. Ticking a task
 * removes it, and the screen says so: there is no archive, and a checkmark that
 * implied one would promise a place the product does not have.
 */
@Composable
fun TasksScreen(vm: ProfileViewModel, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.load() }
    MailScaffold(stringResource(R.string.mail_tasks_tasks), onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            // Phones: no change. Tablets, foldables and desktop windows: the
            // form keeps a readable width, centred (ui/components/Adaptive.kt).
            .gmMaxWidth(GMLayout.Form)
            .padding(horizontal = 20.dp)) {
            state.note?.let { NoteLine(it, state.noteIsBad) }
            AddRow(stringResource(R.string.mail_tasks_add_task), draft, { draft = it }, {
                val list = state.profile?.settings?.tasks ?: return@AddRow
                vm.saveSettings(ProfileSettingsPatch(tasks = list + draft.trim()))
                draft = ""
            }, state.busy, "gm-add-task")
            Text(stringResource(R.string.mail_tasks_ticking_task_removes_list),
                fontSize = 12.sp, color = palette.textTertiary,
                modifier = Modifier.padding(vertical = 6.dp))
            val tasks = state.profile?.settings?.tasks
            when {
                state.profile == null && state.loading ->
                    com.grapsee.grapmail.ui.components.ListSkeleton(rows = 3, avatar = false,
                        label = stringResource(R.string.mail_common_loading))
                state.profile == null && state.fatal != null -> Column {
                    Text(stringResource(R.string.mail_tasks_tasks_could_not_read, state.fatal ?: ""), fontSize = 14.sp,
                        color = palette.textSecondary, modifier = Modifier.padding(top = 16.dp))
                    // The read that failed, asked again.
                    androidx.compose.material3.TextButton(onClick = { vm.load(force = true) }) {
                        Text(stringResource(R.string.states_retry))
                    }
                }
                tasks != null && tasks.isEmpty() ->
                    Text(stringResource(R.string.mail_tasks_no_tasks_account), fontSize = 14.sp, color = palette.textTertiary,
                        modifier = Modifier.padding(vertical = 16.dp))
                tasks != null -> tasks.forEach { task ->
                    Row(Modifier.fillMaxWidth().testTag("gm-task-row")
                        .clickable {
                            vm.saveSettings(ProfileSettingsPatch(
                                tasks = tasks.filter { it != task }))
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                            .border(1.5.dp, palette.border, RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(12.dp))
                        Text(task, fontSize = 15.sp, color = palette.textPrimary)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 95 · mail settings ───────────────────────────────────────────────────────

/**
 * How the mailbox behaves for this account — the web's `/mail/settings`. Only
 * settings that do something are here: the signature the app appends when it
 * sends, the density the list draws at, the locale every timestamp is rendered
 * in, and the sender lists the screener reads.
 *
 * Vacation and forwarding are deliberately absent. The profile stores both and
 * the sieve interpreter supports the actions, but no code turns these settings
 * into sieve rules — so a switch here would change a stored value with no effect
 * on any message. The web page's own promise ("only settings that do something")
 * is kept by not shipping the switch until the pipeline reads it.
 */
@Composable
fun MailPrefsScreen(vm: ProfileViewModel, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.profile?.settings
    var signature by remember(s?.signature) { mutableStateOf(s?.signature ?: "") }
    var timezone by remember(s?.locale) { mutableStateOf(s?.locale?.timezone ?: "") }
    var zoneError by remember { mutableStateOf<String?>(null) }
    var awayOn by remember(s?.vacation) { mutableStateOf(s?.vacation?.enabled ?: false) }
    var awayMessage by remember(s?.vacation) { mutableStateOf(s?.vacation?.message ?: "") }
    var awayFrom by remember(s?.vacation) { mutableStateOf(s?.vacation?.from ?: "") }
    var awayTo by remember(s?.vacation) { mutableStateOf(s?.vacation?.to ?: "") }
    var awayError by remember { mutableStateOf<String?>(null) }
    var forwardOn by remember(s?.forwarding) { mutableStateOf(s?.forwarding?.enabled ?: false) }
    var forwardTo by remember(s?.forwarding) { mutableStateOf(s?.forwarding?.address ?: "") }
    var forwardError by remember { mutableStateOf<String?>(null) }
    var blockedDraft by remember { mutableStateOf("") }
    var safeDraft by remember { mutableStateOf("") }
    var blockedError by remember { mutableStateOf<String?>(null) }
    var safeError by remember { mutableStateOf<String?>(null) }
    // The per-address overrides being edited, seeded from the profile. The
    // web's "Signature per address" section is the reference: one box per
    // address compose can send as, empty means "use the main one".
    var overrides by remember(s?.signatures) { mutableStateOf(s?.signatures ?: emptyMap()) }
    LaunchedEffect(Unit) { vm.load() }
    MailScaffold(stringResource(R.string.mail_prefs_mail_settings), onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            // Phones: no change. Tablets, foldables and desktop windows: the
            // form keeps a readable width, centred (ui/components/Adaptive.kt).
            .gmMaxWidth(GMLayout.Form)
            .padding(horizontal = 20.dp)) {
            state.note?.let { NoteLine(it, state.noteIsBad) }
            if (s == null && state.fatal != null) {
                Text(stringResource(R.string.mail_prefs_these_settings_could_not, state.fatal ?: ""), fontSize = 14.sp,
                    color = palette.textSecondary, modifier = Modifier.padding(vertical = 16.dp))
                return@Column
            }
            if (s == null) {
                Text(stringResource(R.string.mail_common_loading), fontSize = 14.sp, color = palette.textTertiary,
                    modifier = Modifier.padding(vertical = 16.dp))
                return@Column
            }

            SectionLabel(stringResource(R.string.mail_prefs_signature), palette)
            Text(stringResource(R.string.mail_prefs_appended_what_send_every), fontSize = 12.sp, color = palette.textTertiary)
            Box(Modifier.fillMaxWidth().heightIn(min = 80.dp)
                .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                .padding(12.dp)
                .testTag("gm-signature-field")) {
                BasicTextField(signature, { signature = it },
                    textStyle = TextStyle(fontSize = 15.sp, color = palette.textPrimary,
                        lineHeight = 22.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalPalette.current.cursor),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    decorationBox = { inner ->
                        if (signature.isEmpty()) Text(stringResource(R.string.mail_prefs_none), fontSize = 15.sp,
                            color = palette.textTertiary)
                        inner()
                    })
            }
            Text(stringResource(R.string.mail_prefs_save_signature), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (signature != s.signature) palette.primarySolid else palette.textTertiary,
                modifier = Modifier.clickable(enabled = signature != s.signature && !state.busy) {
                    vm.saveSettings(ProfileSettingsPatch(signature = signature))
                }.padding(vertical = 8.dp))

            // Compose reads `signatures[address]` for every send, so a phone
            // that only edited the default offered an override it never let
            // anyone set — the promise in the line above and the behaviour
            // disagreed until this section landed. The address list is the
            // profile's own (primary + aliases), the same source the Addresses
            // screen renders, so the two can never disagree about the account.
            SectionLabel(stringResource(R.string.mail_prefs_signature_per_address), palette)
            Text(stringResource(R.string.mail_prefs_when_send_alias_what), fontSize = 12.sp, color = palette.textTertiary)
            val signatureAddresses =
                listOfNotNull(state.profile?.address?.takeIf { it.isNotBlank() }) +
                    (state.profile?.aliases ?: emptyList()).map { it.address }
            signatureAddresses.forEach { address ->
                Text(address, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = palette.textSecondary, modifier = Modifier.padding(top = 10.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(top = 4.dp)
                    .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .semantics { contentDescription = CoreStrings.get(R.string.mail_prefs_signature_for, address) { "Signature for $address" } }
                    .testTag("gm-sig-override-field")) {
                    BasicTextField(overrides[address] ?: "",
                        { v -> overrides = overrides + (address to v) },
                        textStyle = TextStyle(fontSize = 14.sp, color = palette.textPrimary,
                            lineHeight = 20.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalPalette.current.cursor),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        decorationBox = { inner ->
                            if (overrides[address].isNullOrEmpty()) Text(stringResource(R.string.mail_prefs_uses_main_signature),
                                fontSize = 14.sp, color = palette.textTertiary)
                            inner()
                        })
                }
            }
            // Blanks are dropped, not stored: an empty entry and no entry read
            // the same at compose, but the profile should not accumulate keys
            // for nothing.
            val toSave = overrides.filterValues { it.isNotBlank() }
            val dirty = toSave != s.signatures
            Text(stringResource(R.string.mail_prefs_save_per_address_signatures), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (dirty) palette.primarySolid else palette.textTertiary,
                modifier = Modifier.clickable(enabled = dirty && !state.busy) {
                    vm.saveSettings(ProfileSettingsPatch(signatures = toSave))
                }.padding(vertical = 8.dp))

            SectionLabel(stringResource(R.string.mail_prefs_auto_reply), palette)
            Text(stringResource(R.string.mail_prefs_while_people_who_write),
                fontSize = 12.sp, color = palette.textTertiary)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mail_prefs_send_auto_replies), fontSize = 15.sp, color = palette.textPrimary, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = awayOn, onCheckedChange = { awayOn = it; awayError = null })
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 80.dp)
                .border(1.dp, if (awayError != null) palette.error else palette.border, RoundedCornerShape(12.dp))
                .padding(12.dp)
                .semantics { contentDescription = CoreStrings.get(R.string.mail_prefs_auto_reply_message) { "Auto-reply message" } }) {
                BasicTextField(awayMessage, { awayMessage = it; awayError = null },
                    textStyle = TextStyle(fontSize = 15.sp, color = palette.textPrimary, lineHeight = 22.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalPalette.current.cursor),
                    decorationBox = { inner ->
                        if (awayMessage.isEmpty()) Text(stringResource(R.string.mail_prefs_im_away_until_monday),
                            fontSize = 15.sp, color = palette.textTertiary)
                        inner()
                    })
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Box(Modifier.weight(1f)) {
                    AppTextField(stringResource(R.string.mail_prefs_first_day), awayFrom, { awayFrom = it.trim(); awayError = null }, hint = stringResource(R.string.mail_prefs_yyyy_mm_dd_optional))
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    AppTextField(stringResource(R.string.mail_prefs_last_day), awayTo, { awayTo = it.trim(); awayError = null }, hint = stringResource(R.string.mail_prefs_yyyy_mm_dd_optional))
                }
            }
            awayError?.let { Text(it, fontSize = 12.sp, color = palette.error, modifier = Modifier.padding(top = 4.dp)) }
            val awayDirty = s.vacation.enabled != awayOn || s.vacation.message != awayMessage ||
                s.vacation.from != awayFrom || s.vacation.to != awayTo
            Text(stringResource(R.string.mail_prefs_save_auto_reply), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (awayDirty && !state.busy) palette.primarySolid else palette.textTertiary,
                modifier = Modifier.clickable(enabled = awayDirty && !state.busy) {
                    awayError = vacationProblem(awayOn, awayMessage, awayFrom, awayTo)
                    if (awayError == null) vm.saveSettings(ProfileSettingsPatch(vacation =
                        com.grapsee.grapmail.data.VacationDto(awayOn, awayFrom, awayTo, awayMessage)))
                }.padding(vertical = 8.dp))

            SectionLabel(stringResource(R.string.mail_prefs_forwarding), palette)
            Text(stringResource(R.string.mail_prefs_send_copy_every_message),
                fontSize = 12.sp, color = palette.textTertiary)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mail_prefs_forward_my_mail), fontSize = 15.sp, color = palette.textPrimary, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = forwardOn, onCheckedChange = { forwardOn = it; forwardError = null })
            }
            AppTextField(stringResource(R.string.mail_prefs_forward), forwardTo, { forwardTo = it.trim(); forwardError = null },
                hint = "name@example.com", keyboard = KeyboardType.Email, error = forwardError)
            val ownAddresses = (listOfNotNull(state.profile?.address) + (state.profile?.aliases ?: emptyList()).map { it.address })
                .map { it.lowercase() }.toSet()
            val forwardDirty = s.forwarding.enabled != forwardOn || s.forwarding.address != forwardTo
            Text(stringResource(R.string.mail_prefs_save_forwarding), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (forwardDirty && !state.busy) palette.primarySolid else palette.textTertiary,
                modifier = Modifier.clickable(enabled = forwardDirty && !state.busy) {
                    val address = forwardTo.lowercase()
                    forwardError = when {
                        forwardOn && address.isEmpty() -> CoreStrings.get(R.string.mail_prefs_add_address_forward) { "Add the address to forward to." }
                        address.isNotEmpty() && !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$").matches(address) ->
                            CoreStrings.get(R.string.mail_prefs_forward_full_email_address) { "Forward to a full email address." }
                        address in ownAddresses -> CoreStrings.get(R.string.mail_prefs_forwarding_own_address_would) { "Forwarding to your own address would loop." }
                        else -> null
                    }
                    if (forwardError == null) vm.saveSettings(ProfileSettingsPatch(forwarding =
                        com.grapsee.grapmail.data.ForwardingDto(forwardOn, address, true)))
                }.padding(vertical = 8.dp))

            SectionLabel(stringResource(R.string.mail_prefs_list_density), palette)
            RadioRow(stringResource(R.string.mail_prefs_comfortable_roomier_list), s.density != "compact", {
                if (s.density != "comfortable") vm.saveSettings(ProfileSettingsPatch(density = "comfortable"))
            })
            RadioRow(stringResource(R.string.mail_prefs_compact_more_messages_per), s.density == "compact", {
                if (s.density != "compact") vm.saveSettings(ProfileSettingsPatch(density = "compact"))
            })

            // Choose your swipes: kept on this device (logic/MailSwipePrefs.kt),
            // so it applies at once and needs no save.
            SwipeActionsSetting()

            // The app's own language is the system's per-app setting (Settings →
            // App language), so it is not repeated here as a free-text field that
            // nothing on this device read.
            SectionLabel(stringResource(R.string.mail_prefs_time_zone_dates), palette)
            Text(stringResource(R.string.mail_prefs_how_times_dates_appear), fontSize = 12.sp,
                color = palette.textTertiary)
            val deviceZone = java.time.ZoneId.systemDefault().id
            RadioRow(stringResource(R.string.mail_prefs_devices_time_zone, deviceZone), s.locale.timezone.isBlank(), {
                zoneError = null
                if (s.locale.timezone.isNotBlank()) vm.saveSettings(ProfileSettingsPatch(
                    locale = s.locale.copy(timezone = "")))
            })
            AppTextField(stringResource(R.string.mail_prefs_another_time_zone), timezone, { timezone = it.trim(); zoneError = null },
                hint = stringResource(R.string.mail_prefs_e_g_europe_london), error = zoneError, modifier = Modifier.padding(top = 4.dp))
            val zoneDirty = timezone.isNotBlank() && timezone != s.locale.timezone
            Text(stringResource(R.string.mail_prefs_use_time_zone), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (zoneDirty && !state.busy) palette.primarySolid else palette.textTertiary,
                modifier = Modifier.clickable(enabled = zoneDirty && !state.busy) {
                    val zone = com.grapsee.grapmail.data.MailDisplayPrefs.zoneOrNull(timezone)
                    if (zone == null) {
                        zoneError = CoreStrings.get(R.string.mail_prefs_isnt_time_zone_use) { "That isn't a time zone. Use a name like Europe/London or America/New_York." }
                    } else {
                        vm.saveSettings(ProfileSettingsPatch(locale = s.locale.copy(timezone = zone.id)))
                    }
                }.padding(vertical = 8.dp))
            Text(stringResource(R.string.mail_prefs_dates_mailbox_list), fontSize = 13.sp,
                color = palette.textSecondary)
            listOf("" to stringResource(R.string.mail_prefs_automatic_follows_device), "dmy" to stringResource(R.string.mail_prefs_day_first_25_10),
                "mdy" to stringResource(R.string.mail_prefs_month_first_10_25), "ymd" to stringResource(R.string.mail_prefs_year_first_2026_10)).forEach { (code, label) ->
                RadioRow(label, s.locale.dateFormat == code, {
                    if (s.locale.dateFormat != code) vm.saveSettings(ProfileSettingsPatch(
                        locale = s.locale.copy(dateFormat = code)))
                })
            }

            // These two lists are enforced on delivery: the server turns each entry
            // into a Screener decision (safe = approved, blocked = blocked), and a
            // block applies whether or not the Screener is on. A sender can be on
            // one list only, so adding to one takes it off the other.
            SectionLabel(stringResource(R.string.mail_prefs_senders_always_accept), palette)
            Text(stringResource(R.string.mail_prefs_mail_from_these_senders),
                fontSize = 12.sp, color = palette.textTertiary)
            AddRow(stringResource(R.string.mail_prefs_name_example_com_example), safeDraft, { safeDraft = it; safeError = null }, {
                val sender = normalizedSender(safeDraft)
                if (sender == null) {
                    safeError = CoreStrings.get(R.string.mail_prefs_enter_address_like_name) { "Enter an address like name@example.com, or a domain like example.com." }
                } else {
                    vm.saveSettings(ProfileSettingsPatch(
                        safeSenders = (s.safeSenders + sender).distinct(),
                        blockedSenders = s.blockedSenders.filter { it != sender }))
                    safeDraft = ""
                }
            }, state.busy, "gm-add-safe")
            safeError?.let { Text(it, fontSize = 12.sp, color = palette.error) }
            s.safeSenders.forEach { e ->
                ListRow(e, { vm.saveSettings(ProfileSettingsPatch(
                    safeSenders = s.safeSenders.filter { it != e })) }, "gm-safe-row")
            }

            SectionLabel(stringResource(R.string.mail_prefs_senders_block), palette)
            Text(stringResource(R.string.mail_prefs_mail_from_blocked_senders),
                fontSize = 12.sp, color = palette.textTertiary)
            AddRow(stringResource(R.string.mail_prefs_name_example_com_example), blockedDraft, { blockedDraft = it; blockedError = null }, {
                val sender = normalizedSender(blockedDraft)
                if (sender == null) {
                    blockedError = CoreStrings.get(R.string.mail_prefs_enter_address_like_name) { "Enter an address like name@example.com, or a domain like example.com." }
                } else {
                    vm.saveSettings(ProfileSettingsPatch(
                        blockedSenders = (s.blockedSenders + sender).distinct(),
                        safeSenders = s.safeSenders.filter { it != sender }))
                    blockedDraft = ""
                }
            }, state.busy, "gm-add-blocked")
            blockedError?.let { Text(it, fontSize = 12.sp, color = palette.error) }
            s.blockedSenders.forEach { e ->
                ListRow(e, { vm.saveSettings(ProfileSettingsPatch(
                    blockedSenders = s.blockedSenders.filter { it != e })) }, "gm-blocked-row")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A sender the safe and blocked lists accept: an address, or a bare domain. */
private fun normalizedSender(raw: String): String? {
    val value = raw.trim().lowercase().removePrefix("@")
    val address = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")
    val domain = Regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$")
    return value.takeIf { address.matches(it) || domain.matches(it) }
}

/** The rules the server and the delivery path apply to an auto-reply. */
private fun vacationProblem(on: Boolean, message: String, from: String, to: String): String? {
    fun date(v: String) = if (v.isEmpty()) null else runCatching { java.time.LocalDate.parse(v) }.getOrNull()
    if (from.isNotEmpty() && date(from) == null) return CoreStrings.get(R.string.mail_prefs_write_first_day_yyyy) { "Write the first day as YYYY-MM-DD." }
    if (to.isNotEmpty() && date(to) == null) return CoreStrings.get(R.string.mail_prefs_write_last_day_yyyy) { "Write the last day as YYYY-MM-DD." }
    val start = date(from); val end = date(to)
    if (start != null && end != null && end.isBefore(start)) return CoreStrings.get(R.string.mail_prefs_last_day_before_first) { "The last day is before the first day." }
    if (on && message.isBlank()) return CoreStrings.get(R.string.mail_prefs_write_auto_reply_before) { "Write the auto-reply before turning it on." }
    return null
}

/** The same birthday rule sign-up applies: a real date, not in the future, 13 or older. */
private fun birthdayProblem(value: String): String? {
    val text = value.trim()
    if (text.isEmpty()) return null
    val date = runCatching { java.time.LocalDate.parse(text) }.getOrNull()
        ?: return CoreStrings.get(R.string.mail_profile_enter_birthday_yyyy_mm) { "Enter your birthday as YYYY-MM-DD." }
    val today = java.time.LocalDate.now()
    if (date.isAfter(today) || date.year < 1900) return CoreStrings.get(R.string.mail_profile_enter_real_birthday) { "Enter a real birthday." }
    if (java.time.Period.between(date, today).years < 13) return CoreStrings.get(R.string.mail_profile_grapmail_account_needs_least) { "A GrapMail account needs you to be at least 13." }
    return null
}

// ── 96 · addresses ───────────────────────────────────────────────────────────

/**
 * The GrapMail address that IS the account, plus the aliases — the web's
 * `/mail/account/addresses`. Create and delete hit the alias routes the mailbox
 * API already declares; the list itself rides the profile document so this
 * screen and the settings rows can never disagree about how many addresses an
 * account has.
 */
@Composable
fun AddressesScreen(vm: ProfileViewModel, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    var label by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.load() }
    MailScaffold(stringResource(R.string.mail_addr_addresses), onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            // Phones: no change. Tablets, foldables and desktop windows: the
            // form keeps a readable width, centred (ui/components/Adaptive.kt).
            .gmMaxWidth(GMLayout.Form)
            .padding(horizontal = 20.dp)) {
            state.note?.let { NoteLine(it, state.noteIsBad) }
            SectionLabel(stringResource(R.string.mail_addr_grapmail_address), palette)
            Text(state.profile?.address ?: (if (state.fatal != null) stringResource(R.string.mail_addr_could_not_read) else stringResource(R.string.mail_common_loading)),
                fontSize = 16.sp, fontWeight = FontWeight.Medium, color = palette.textPrimary,
                modifier = Modifier.fillMaxWidth().testTag("gm-primary-address")
                    .background(palette.bgTinted, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp))
            SectionLabel(stringResource(R.string.mail_addr_email_aliases), palette)
            Text(stringResource(R.string.mail_addr_alias_delivers_into_same), fontSize = 12.sp, color = palette.textTertiary)
            AddRow(stringResource(R.string.mail_addr_label_business_family), label, { label = it }, {
                // `POST /aliases` mints the address itself; the label is what the
                // person calls it by. The screen reloads from the profile after.
                vmAliasCreate(vm, label) { label = "" }
            }, state.busy, "gm-add-alias")
            val aliases = state.profile?.aliases
            when {
                aliases == null && state.fatal != null ->
                    Text(stringResource(R.string.mail_addr_aliases_could_not_read), fontSize = 14.sp,
                        color = palette.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
                aliases != null && aliases.isEmpty() ->
                    Text(stringResource(R.string.mail_addr_no_aliases_yet), fontSize = 14.sp, color = palette.textTertiary,
                        modifier = Modifier.padding(vertical = 12.dp))
                aliases != null -> aliases.forEach { a ->
                    Row(Modifier.fillMaxWidth().testTag("gm-alias-row").padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.label.ifBlank { a.address }, fontSize = 15.sp,
                                fontWeight = FontWeight.Medium, color = palette.textPrimary)
                            Text(a.address, fontSize = 13.sp, color = palette.textSecondary)
                            if (!a.isActive) Text(stringResource(R.string.mail_addr_paused_nothing_new_arrives),
                                fontSize = 12.sp, color = palette.warnInk)
                        }
                        Icon(Icons.Rounded.Delete, stringResource(R.string.mail_addr_remove, a.address),
                            tint = palette.error,
                            modifier = Modifier.size(20.dp)
                                .clickable { pendingDelete = a.address })
                    }
                }
            }
            pendingDelete?.let { address ->
                Column(Modifier.fillMaxWidth().background(palette.warnChipBg, RoundedCornerShape(12.dp))
                    .padding(16.dp).testTag("gm-alias-confirm")) {
                    Text(stringResource(R.string.mail_addr_stop_receiving_mail_already, address),
                        fontSize = 13.sp, color = palette.textPrimary)
                    Row(Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.mail_common_cancel), fontSize = 14.sp, color = palette.textSecondary,
                            modifier = Modifier.weight(1f, fill = false).clickable { pendingDelete = null })
                        Spacer(Modifier.width(24.dp))
                        Text(stringResource(R.string.mail_addr_remove_address), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = palette.error,
                            modifier = Modifier.weight(1f, fill = false).clickable {
                                pendingDelete = null
                                vmAliasDelete(vm, address)
                            })
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The alias routes live on the mailbox API; these two helpers keep the screen
 *  from caring, and both settle the shared note from the server's own words. */
private fun vmAliasCreate(vm: ProfileViewModel, label: String, done: () -> Unit) {
    vm.aliasWrite(label, remove = false) { done() }
}

private fun vmAliasDelete(vm: ProfileViewModel, address: String) {
    vm.aliasWrite(address, remove = true) { }
}

// ── 94 · connections ─────────────────────────────────────────────────────────

/**
 * Connected accounts — the web's `/mail/account/connections`, modelled on the
 * page's own rule: access is never granted from this screen, so there is no
 * grant control. It reports what already happened and lets the person cut it
 * off. A product we run has no domain, so it gets a monogram of its initials;
 * a third-party app shows its real mark.
 */
@Composable
fun ConnectionsScreen(vm: ProfileViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var view by remember { mutableStateOf("all") }
    var pendingRemove by remember { mutableStateOf<ProfileConnectionDto?>(null) }
    LaunchedEffect(Unit) { vm.load() }
    val connected = state.profile?.connections ?: emptyList()
    val shown = connected.filter { c ->
        (view == "all" ||
            (view == "mailbox" && c.scopes.contains("mailbox")) ||
            (view == "products" && (c.host ?: "").contains('.').not())) &&
        (query.isBlank() || c.product.lowercase().contains(query.trim().lowercase()) ||
            (c.host ?: "").contains(query.trim().lowercase()))
    }
    MailScaffold(stringResource(R.string.mail_conn_connected_accounts), onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            // Phones: no change. Tablets, foldables and desktop windows: the
            // form keeps a readable width, centred (ui/components/Adaptive.kt).
            .gmMaxWidth(GMLayout.Form)
            .padding(horizontal = 20.dp)) {
            state.note?.let { NoteLine(it, state.noteIsBad) }
            Text(stringResource(R.string.mail_conn_when_use_account_sign),
                fontSize = 13.sp, color = palette.textSecondary,
                modifier = Modifier.padding(vertical = 8.dp))
            AppTextField("", query, { query = it }, hint = stringResource(R.string.mail_conn_search_connected_apps_name),
                showLabel = false, modifier = Modifier.testTag("gm-connections-search"))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
                listOf("all" to stringResource(R.string.mail_conn_all), "mailbox" to stringResource(R.string.mail_conn_mail), "products" to stringResource(R.string.mail_conn_grapsee_apps))
                    .forEach { (key, label) ->
                        val count = connected.count { c ->
                            when (key) {
                                "mailbox" -> c.scopes.contains("mailbox")
                                "products" -> (c.host ?: "").contains('.').not()
                                else -> true
                            }
                        }
                        Text("$label ($count)", fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (view == key) palette.primarySolid else palette.textSecondary,
                            modifier = Modifier.testTag("gm-view-$key")
                                .clickable { view = key }
                                .padding(horizontal = 10.dp, vertical = 6.dp))
                    }
            }
            if (shown.isEmpty()) {
                Text(if (connected.isEmpty()) stringResource(R.string.mail_conn_no_app_has_access)
                    else stringResource(R.string.mail_conn_nothing_matches),
                    fontSize = 14.sp, color = palette.textTertiary,
                    modifier = Modifier.padding(vertical = 16.dp))
            }
            shown.forEach { c ->
                Row(Modifier.fillMaxWidth().testTag("gm-connection-row")
                    .padding(vertical = 12.dp)
                    // The row opens the per-app page the web links from the same
                    // list (`/mail/account/connections/<product>`); the Remove
                    // control stays on the row because it is the one action a
                    // person takes without wanting the detail.
                    .clickable { onOpen(c.product) },
                    verticalAlignment = Alignment.CenterVertically) {
                    val mono = c.product.replace(Regex("[^A-Za-z0-9 ]"), "")
                        .split(" ").filter { it.isNotBlank() }
                        .map { it.first() }.joinToString("").take(2).uppercase()
                        .ifBlank { "?" }
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                        .background(palette.softFill), contentAlignment = Alignment.Center) {
                        Text(mono, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = palette.primarySolid)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.product, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            color = palette.textPrimary)
                        val scopes = c.scopes.joinToString(", ") { scopeWord(it) }
                        Text(if (scopes.isBlank()) stringResource(R.string.mail_conn_signed_here) else stringResource(R.string.mail_conn_sees, scopes),
                            fontSize = 12.sp, color = palette.textSecondary)
                    }
                    Text(stringResource(R.string.mail_common_remove), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = palette.error,
                        modifier = Modifier.clickable { pendingRemove = c })
                }
            }
            pendingRemove?.let { c ->
                Column(Modifier.fillMaxWidth().background(palette.warnChipBg, RoundedCornerShape(12.dp))
                    .padding(16.dp).testTag("gm-connection-confirm")) {
                    Text(stringResource(R.string.mail_conn_cut_off_loses_access, c.product),
                        fontSize = 13.sp, color = palette.textPrimary)
                    Row(Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.mail_common_cancel), fontSize = 14.sp, color = palette.textSecondary,
                            modifier = Modifier.weight(1f, fill = false).clickable { pendingRemove = null })
                        Spacer(Modifier.width(24.dp))
                        Text(stringResource(R.string.mail_conn_remove_access), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = palette.error,
                            modifier = Modifier.weight(1f, fill = false).clickable {
                                pendingRemove = null
                                vm.saveConnections(connected.filter { it.product != c.product })
                            })
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The web's own scope words (`connections/page.tsx` SHARED map), copied rather
 *  than re-invented so the two clients describe one scope with one phrase. */
private fun scopeWord(scope: String): String = when (scope) {
    "profile" -> CoreStrings.get(R.string.mail_conn_name_address_photo) { "name, address and photo" }
    "mailbox" -> CoreStrings.get(R.string.mail_conn_mailbox) { "your mailbox" }
    "projects" -> CoreStrings.get(R.string.mail_conn_projects) { "your projects" }
    "contacts" -> CoreStrings.get(R.string.mail_conn_contacts) { "your contacts" }
    else -> scope
}

/** The web's FULL sharing sentences (`LinkedAppDetail.tsx` SHARED map), for the
 *  per-app page where the person decides what cutting access actually costs.
 *  The list row uses the short words; the detail uses these, exactly as the
 *  web splits the two. */
fun sharedWord(scope: String): String = when (scope) {
    "profile" -> CoreStrings.get(R.string.mail_conn_name_grapmail_address_profile) { "Your name, GrapMail address and profile photo" }
    "mailbox" -> CoreStrings.get(R.string.mail_conn_read_send_mail_grapmail) { "Read and send mail in your GrapMail mailbox" }
    "projects" -> CoreStrings.get(R.string.mail_conn_projects_client_records_own) { "Projects and client records you own on Grapsee products" }
    "contacts" -> CoreStrings.get(R.string.mail_conn_people_have_exchanged_mail) { "The people you have exchanged mail with" }
    else -> scope
}

// ── 94b · one linked app, in full ────────────────────────────────────────────

/**
 * The web's `/mail/account/connections/<product>`: what this app is, what it
 * received at the moment access was granted, and the one honest way to cut it
 * off. The scopes shown are the recorded ones, not a generic catalogue; an
 * unknown product word is shown as its own name rather than dropped, because a
 * scope the server knows and this build does not is the server's fact, not a
 * reason to hide a line. A product with no entry says so — nothing is shared,
 * nothing to remove, and the screen does not pretend the name being looked at
 * is connected just because the URL said so.
 */
@Composable
fun ConnectionDetailScreen(vm: ProfileViewModel, product: String, onBack: () -> Unit,
                           onSupport: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.load() }
    val connected = state.profile?.connections ?: emptyList()
    val entry = connected.firstOrNull { it.product.lowercase() == product.lowercase() }
    MailScaffold(product, onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            // Phones: no change. Tablets, foldables and desktop windows: the
            // form keeps a readable width, centred (ui/components/Adaptive.kt).
            .gmMaxWidth(GMLayout.Form)
            .padding(horizontal = 20.dp)) {
            // The unread shape FIRST: an entry missing because the profile has
            // not been read is not an entry that is absent. Showing "not
            // connected" during the read would be the invention this whole
            // screen exists to avoid.
            if (state.profile == null) {
                Text(if (state.loading) stringResource(R.string.mail_common_loading)
                     else stringResource(R.string.mail_conn_account_could_not_read),
                    fontSize = 14.sp, color = palette.textTertiary,
                    modifier = Modifier.padding(vertical = 16.dp)
                        .testTag("gm-connection-detail-read"))
                return@Column
            }
            state.note?.let { NoteLine(it, state.noteIsBad) }
            Spacer(Modifier.height(8.dp))
            val mono = product.replace(Regex("[^A-Za-z0-9 ]"), "")
                .split(" ").filter { it.isNotBlank() }
                .map { it.first() }.joinToString("").take(2).uppercase()
                .ifBlank { "?" }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(palette.softFill), contentAlignment = Alignment.Center) {
                    Text(mono, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        color = palette.primarySolid)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(product, fontSize = 20.sp, color = palette.textPrimary, maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("gm-connection-detail-name"))
                    val who = entry?.let { stringResource(R.string.mail_conn_host_connected,
                        it.host ?: stringResource(R.string.mail_conn_grapsee_product), (it.connectedAt ?: "").take(10)) }
                    Text(who ?: stringResource(R.string.mail_conn_not_connected_account),
                        fontSize = 13.sp, color = palette.textSecondary,
                        maxLines = 2)
                }
            }
            if (entry == null) {
                // The web's exact stance: a link to an app that is not linked
                // is answered, not redirected and not fabricated.
                Text(stringResource(R.string.mail_conn_app_does_not_have),
                    fontSize = 14.sp, lineHeight = 20.sp, color = palette.textSecondary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
                        .background(palette.softFill, RoundedCornerShape(12.dp))
                        .padding(14.dp).testTag("gm-connection-not-linked"))
                Spacer(Modifier.height(24.dp))
                return@Column
            }
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(palette.card)
                .border(1.dp, palette.border, RoundedCornerShape(16.dp))
                .padding(18.dp)) {
                Text(stringResource(R.string.mail_conn_how_grapmail_helps_sign, product),
                    fontSize = 15.sp, color = palette.textPrimary)
                Text(stringResource(R.string.mail_conn_when_use_grapsee_account, product),
                    fontSize = 13.sp, lineHeight = 20.sp, color = palette.textSecondary,
                    modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.mail_conn_can_reach, product.uppercase()),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(top = 14.dp))
                if (entry.scopes.isEmpty()) {
                    Text(stringResource(R.string.mail_conn_no_access_was_recorded),
                        fontSize = 13.sp, color = palette.textSecondary,
                        modifier = Modifier.padding(top = 6.dp))
                }
                entry.scopes.forEach { s ->
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(6.dp).padding(top = 6.dp)
                            .clip(CircleShape).background(palette.primarySolid))
                        Spacer(Modifier.width(10.dp))
                        Text(sharedWord(s), fontSize = 13.sp, color = palette.textPrimary,
                            modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(palette.card)
                .border(1.dp, palette.border, RoundedCornerShape(16.dp))
                .clickable(enabled = !state.busy) { confirmRemove = true }
                .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (state.busy) stringResource(R.string.mail_conn_removing) else stringResource(R.string.mail_conn_remove_access),
                        fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.error)
                    Text(stringResource(R.string.mail_conn_remove_all_links_stop, product),
                        fontSize = 12.sp, lineHeight = 17.sp, color = palette.textSecondary)
                }
            }
            if (confirmRemove) {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp)
                    .background(palette.warnChipBg, RoundedCornerShape(12.dp))
                    .padding(16.dp).testTag("gm-connection-confirm")) {
                    Text(stringResource(R.string.mail_conn_cut_off_everything, product),
                        fontSize = 13.sp, color = palette.textPrimary)
                    Row(Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.mail_common_cancel), fontSize = 14.sp, color = palette.textSecondary,
                            modifier = Modifier.weight(1f, fill = false).clickable { confirmRemove = false })
                        Spacer(Modifier.width(24.dp))
                        Text(stringResource(R.string.mail_conn_remove_access), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = palette.error,
                            modifier = Modifier.weight(1f, fill = false).clickable {
                                confirmRemove = false
                                vm.saveConnections(connected.filter {
                                    it.product.lowercase() != product.lowercase()
                                })
                            })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.mail_conn_something_wrong_app), fontSize = 12.sp, color = palette.textSecondary)
            Text(stringResource(R.string.mail_conn_report_app), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = palette.primarySolid,
                modifier = Modifier.padding(top = 4.dp).clickable { onSupport() })
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 90 · personal info ───────────────────────────────────────────────────────

/**
 * Personal info — the web's `/mail/account/profile`: the name on the account,
 * the birthday (with the same 13+ rule signup enforces), gender, the photo, and
 * how the person appears to recipients.
 */
@Composable
fun ProfileEditScreen(vm: ProfileViewModel, onBack: () -> Unit, onRecoveryEmail: () -> Unit = {}) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    val p = state.profile
    var first by remember(p?.firstName) { mutableStateOf(p?.firstName ?: "") }
    var last by remember(p?.lastName) { mutableStateOf(p?.lastName ?: "") }
    var display by remember(p?.displayName) { mutableStateOf(p?.displayName ?: "") }
    var formError by remember { mutableStateOf<String?>(null) }
    var birthday by remember(p?.birthday) { mutableStateOf(p?.birthday ?: "") }
    // Held as the code; a profile still holding the legacy English word
    // ("Female") is read as its code so the right row is selected.
    var gender by remember(p?.gender) { mutableStateOf(GenderOption.normalize(p?.gender) ?: "") }
    var photoError by remember { mutableStateOf<String?>(null) }
    val photoScope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) photoScope.launch {
            val ctx = vm.getApplication<android.app.Application>()
            val bytes = com.grapsee.grapmail.data.AvatarPhoto.encode(ctx, uri)
            if (bytes == null || bytes.isEmpty()) {
                photoError = CoreStrings.get(R.string.mail_profile_couldn_t_open_photo) { "Couldn’t open that photo. Choose another image." }
            } else {
                photoError = null
                vm.uploadAvatar(bytes, "profile.png", "image/png")
            }
        }
    }

    LaunchedEffect(Unit) { vm.load() }
    // Coming back from the recovery email screen, re-read the record so the
    // "verified" line is the server's answer, not the one from before.
    val editLifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(editLifecycle) {
        var firstResume = true
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (firstResume) firstResume = false else vm.load(force = true)
            }
        }
        editLifecycle.lifecycle.addObserver(observer)
        onDispose { editLifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(p?.avatarUrl, state.busy) {
        if (!state.busy) vm.loadAvatar()
    }
    MailScaffold(stringResource(R.string.mail_profile_personal_info), onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            // Phones: no change. Tablets, foldables and desktop windows: the
            // form keeps a readable width, centred (ui/components/Adaptive.kt).
            .gmMaxWidth(GMLayout.Form)
            .padding(horizontal = 20.dp)) {
            state.note?.let { NoteLine(it, state.noteIsBad) }
            photoError?.let { NoteLine(it, true) }
            if (p == null) {
                Text(if (state.fatal != null) stringResource(R.string.mail_profile_account_record_could_not, state.fatal ?: "")
                    else stringResource(R.string.mail_common_loading),
                    fontSize = 14.sp, color = palette.textTertiary,
                    modifier = Modifier.padding(vertical = 16.dp))
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 10.dp)) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(palette.softFill),
                    contentAlignment = Alignment.Center) {
                    AccountPhoto(state.avatar, p.name, p.address, 64.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(if (p.avatarUrl.isNullOrBlank() && state.avatar == null) stringResource(R.string.mail_profile_add_photo) else stringResource(R.string.mail_profile_change_photo),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = palette.primarySolid,
                        modifier = Modifier.clickable { picker.launch("image/*") })
                    if (!p.avatarUrl.isNullOrBlank() || state.avatar != null) {
                        Text(stringResource(R.string.mail_common_remove), fontSize = 13.sp, color = palette.error,
                            modifier = Modifier.padding(top = 4.dp).clickable { vm.removeAvatar() })
                    }
                }
            }
            SectionLabel(stringResource(R.string.mail_profile_name), palette)
            AppTextField(stringResource(R.string.mail_profile_first_name), first, { first = it })
            AppTextField(stringResource(R.string.mail_profile_last_name), last, { last = it },
                modifier = Modifier.padding(top = 8.dp))
            SectionLabel(stringResource(R.string.mail_profile_birthday), palette)
            Text(stringResource(R.string.mail_profile_grapmail_address_can_receive),
                fontSize = 12.sp, color = palette.textTertiary)
            AppTextField(stringResource(R.string.mail_profile_birthday), birthday, { birthday = it },
                hint = "YYYY-MM-DD",
                // KeyboardType.Text, not a date keyboard: Compose has no Date
                // keyboard type (the enum stops at ASCII/Percent), and the
                // field takes the route's own `YYYY-MM-DD` free text.
                keyboard = KeyboardType.Text, modifier = Modifier.padding(top = 6.dp))
            SectionLabel(stringResource(R.string.mail_profile_gender), palette)
            GenderOption.ALL.forEach { g ->
                val label = GenderOption.labelRes(g)?.let { stringResource(it) } ?: g
                RadioRow(label, gender == g, { gender = if (gender == g) "" else g })
            }
            SectionLabel(stringResource(R.string.mail_profile_how_appear), palette)
            AppTextField(stringResource(R.string.mail_profile_display_name), display, { display = it },
                hint = stringResource(R.string.mail_profile_what_recipients_see))
            // The recovery email is proven with a code before it counts, so it is
            // changed through that flow rather than typed here: a plain save
            // would store the address unverified, and reset links are only ever
            // sent to a verified one. The recovery phone field is gone because
            // GrapMail recovers by email only and nothing read it.
            SectionLabel(stringResource(R.string.mail_profile_recovery), palette)
            val recovery = p.recoveryEmail?.takeIf { it.isNotBlank() }
            Text(
                when {
                    recovery == null -> stringResource(R.string.mail_profile_no_recovery_email_yet)
                    p.recoveryEmailVerified == true -> stringResource(R.string.mail_profile_verified, recovery)
                    else -> stringResource(R.string.mail_profile_not_verified_yet, recovery)
                },
                fontSize = 14.sp, color = palette.textSecondary, modifier = Modifier.padding(top = 4.dp))
            Text(if (recovery == null) stringResource(R.string.mail_profile_add_recovery_email) else stringResource(R.string.mail_profile_change_recovery_email),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primarySolid,
                modifier = Modifier.clickable(onClick = onRecoveryEmail).padding(vertical = 8.dp))
            formError?.let {
                Text(it, fontSize = 13.sp, color = palette.error, modifier = Modifier.padding(vertical = 6.dp))
            }
            Text(stringResource(R.string.mail_profile_save), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = palette.onPrimary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("gm-profile-save")
                    .background(if (state.busy) palette.buttonDisabled else palette.primarySolid,
                        RoundedCornerShape(24.dp))
                    .clickable(enabled = !state.busy) {
                        // The same rules sign-up applies, checked before the
                        // request so the person sees which field is wrong.
                        formError = when {
                            first.trim().length < 2 -> CoreStrings.get(R.string.mail_profile_enter_first_name_2) { "Enter your first name (2+ characters)." }
                            last.trim().length < 2 -> CoreStrings.get(R.string.mail_profile_enter_last_name_2) { "Enter your last name (2+ characters)." }
                            display.trim().length > 60 -> CoreStrings.get(R.string.mail_profile_keep_display_name_60) { "Keep the display name to 60 characters." }
                            else -> birthdayProblem(birthday)
                        }
                        if (formError == null) vm.save(CoreStrings.get(R.string.mail_profile_saved) { "Saved." }, ProfilePatch(
                            firstName = first.trim(), lastName = last.trim(), birthday = birthday.trim(),
                            gender = gender, displayName = display.trim()))
                    }.padding(vertical = 14.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 91 · data & privacy ──────────────────────────────────────────────────────

/**
 * What this account keeps and the controls that change it — the web's
 * `/mail/account/privacy`. The deletion section routes to the real flow:
 * self-serve deletion is a password-proved call the backend owns
 * (`POST /auth/account/delete`), reached from the security hub, and the page
 * names where rather than drawing a second copy of the confirmation here.
 */
@Composable
fun PrivacyScreen(
    vm: ProfileViewModel,
    onBack: () -> Unit,
    onProfile: () -> Unit,
    onPhone: () -> Unit,
    onAddresses: () -> Unit,
    onPrefs: () -> Unit,
    onDeleteAccount: () -> Unit,
    onSupport: () -> Unit,
    onLegal: (com.grapsee.grapmail.ui.screens.GrapMailLegalDocument) -> Unit,
) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    val p = state.profile
    LaunchedEffect(Unit) { vm.load() }
    MailScaffold(stringResource(R.string.mail_privacy_data_privacy), onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            // Phones: no change. Tablets, foldables and desktop windows: the
            // form keeps a readable width, centred (ui/components/Adaptive.kt).
            .gmMaxWidth(GMLayout.Form)
            .padding(horizontal = 20.dp)) {
            SectionLabel(stringResource(R.string.mail_privacy_what_kept), palette)
            listOf(
                stringResource(R.string.mail_privacy_grapmail_address_aliases_identity),
                stringResource(R.string.mail_privacy_messages_stored_account_only),
                stringResource(R.string.mail_privacy_when_agreed_terms_timestamp),
            ).forEach {
                Text("• $it", fontSize = 13.sp, color = palette.textSecondary,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
            SectionLabel(stringResource(R.string.mail_privacy_controls), palette)
            SettingRow(Icons.Rounded.Person, stringResource(R.string.mail_privacy_edit_personal_info), stringResource(R.string.mail_privacy_name_birthday_photo),
                palette, onClick = onProfile)
            SettingRow(Icons.Rounded.Person, stringResource(R.string.mail_privacy_change_phone_number), stringResource(R.string.mail_privacy_proven_code),
                palette, onClick = onPhone)
            SettingRow(Icons.Rounded.MailOutline, stringResource(R.string.mail_privacy_manage_addresses), stringResource(R.string.mail_privacy_primary_aliases),
                palette, onClick = onAddresses)
            SettingRow(Icons.Rounded.Settings, stringResource(R.string.mail_privacy_mail_settings), stringResource(R.string.mail_privacy_signature_density_locale),
                palette, onClick = onPrefs)
            SectionLabel(stringResource(R.string.mail_privacy_account), palette)
            DetailLine(stringResource(R.string.mail_privacy_address), p?.address
                ?: if (state.fatal != null) stringResource(R.string.mail_privacy_could_not_read) else stringResource(R.string.mail_common_loading), palette)
            DetailLine(stringResource(R.string.mail_privacy_created), p?.createdAt?.take(10)
                ?: if (p == null) stringResource(R.string.mail_privacy_could_not_read) else stringResource(R.string.mail_privacy_not_reported), palette)
            DetailLine(stringResource(R.string.mail_privacy_terms_accepted), p?.termsAcceptedAt?.take(10)
                ?: if (p == null) stringResource(R.string.mail_privacy_could_not_read) else stringResource(R.string.mail_privacy_not_recorded), palette)
            DetailLine(stringResource(R.string.mail_privacy_2_step_verification), when (p?.twoFactorState) {
                true -> stringResource(R.string.mail_privacy_on)
                false -> stringResource(R.string.mail_common_off)
                null -> if (p == null) stringResource(R.string.mail_privacy_could_not_read) else stringResource(R.string.mail_privacy_not_reported)
            }, palette)
            SectionLabel(stringResource(R.string.mail_privacy_deleting_account), palette)
            Text(stringResource(R.string.mail_privacy_deleting_self_serve_permanent),
                fontSize = 13.sp, color = palette.textSecondary,
                modifier = Modifier.padding(vertical = 6.dp))
            Text(stringResource(R.string.mail_privacy_delete_account), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = palette.primarySolid, modifier = Modifier.clickable(onClick = onDeleteAccount)
                    .padding(vertical = 6.dp))
            SectionLabel(stringResource(R.string.mail_privacy_documents), palette)
            for (document in com.grapsee.grapmail.ui.screens.GrapMailLegalDocument.entries) {
                Text(document.title, fontSize = 14.sp, color = palette.primarySolid,
                    modifier = Modifier.fillMaxWidth().clickable { onLegal(document) }.padding(vertical = 12.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Same helper the mail screens use, reached from the privacy rows. */
@Composable
private fun DetailLine(label: String, value: String, palette: com.grapsee.grapmail.ui.theme.Palette) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        // Proportional, not a fixed 150dp: at 200% text a fixed label column
        // left the value a sliver on a narrow phone.
        Text(label, fontSize = 13.sp, color = palette.textSecondary, modifier = Modifier.weight(0.4f))
        Spacer(Modifier.width(8.dp))
        Text(value, fontSize = 14.sp, color = palette.textPrimary, modifier = Modifier.weight(0.6f))
    }
}

// ── 97 · help & support ──────────────────────────────────────────────────────

/**
 * The questions this product actually generates — the web's `/mail/support`,
 * whose answers are written against behaviours the code really has (recovery by
 * phone code, the 13+ rule, what an alias is). The routes at the bottom go to
 * the screens that can fix the problem, not to a contact form that vanishes into
 * an inbox.
 */
@Composable
fun SupportScreen(
    vm: ProfileViewModel,
    onBack: () -> Unit,
    onForgot: () -> Unit,
    onPhone: () -> Unit,
    onAddresses: () -> Unit,
) {
    val palette = LocalPalette.current
    val faq = remember {
        listOf(
            CoreStrings.get(R.string.mail_support_i_forgot_my_password) { "I forgot my password. What now?" } to
                CoreStrings.get(R.string.mail_support_tap_forgot_password_sign) { "Tap Forgot password on the sign-in screen. GrapMail emails a reset link to the " +
                "recovery email on your account, so add and verify one in Personal info before " +
                "you need it." },
            CoreStrings.get(R.string.mail_support_can_i_sign_my) { "Can I sign in with my phone number?" } to
                CoreStrings.get(R.string.mail_support_yes_sign_page_accepts) { "Yes. The sign-in page accepts either the GrapMail address or the phone " +
                "number you verified, but the number is never the identity of the account, " +
                "and mail is never addressed to it." },
            CoreStrings.get(R.string.mail_support_i_changed_my_number) { "I changed my number. Will I lose the account?" } to
                CoreStrings.get(R.string.mail_support_no_if_change_before) { "No, if you change it before losing access. The new number is proven with a " +
                "code before it replaces the old one." },
            CoreStrings.get(R.string.mail_support_why_can_i_not) { "Why can I not create an account for someone under 13?" } to
                CoreStrings.get(R.string.mail_support_grapmail_holds_address_can) { "GrapMail holds an address that can receive mail from anyone, so the account " +
                "requires an age of at least 13. The birthday you enter is checked against " +
                "that rule." },
            CoreStrings.get(R.string.mail_support_what_alias) { "What is an alias for?" } to
                CoreStrings.get(R.string.mail_support_alias_delivers_into_same) { "An alias delivers into the same mailbox under a second address, and can be " +
                "chosen as the sender. Useful when you want to write as \u201cbusiness\u201d " +
                "without a second account." },
            CoreStrings.get(R.string.mail_support_mail_i_was_expecting) { "Mail I was expecting never arrived." } to
                CoreStrings.get(R.string.mail_support_order_check_sending_address) { "In order: check the sending address was spelled exactly, look in Spam and " +
                "Trash under \u201cOthers\u201d, then check that the sender is not on the " +
                "suppression list because they unsubscribed earlier. If the mailbox is " +
                "connected and the message is still missing, the mail server has to be " +
                "involved. That is a support matter." },
        )
    }
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.load() }
    MailScaffold(stringResource(R.string.mail_support_help_support), onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            // Phones: no change. Tablets, foldables and desktop windows: the
            // form keeps a readable width, centred (ui/components/Adaptive.kt).
            .gmMaxWidth(GMLayout.Form)
            .padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.mail_support_answers_questions_product_actually), fontSize = 13.sp, color = palette.textTertiary,
                modifier = Modifier.padding(vertical = 8.dp))
            faq.forEach { (q, a) ->
                Column(Modifier.fillMaxWidth().testTag("gm-faq-item")
                    .clickable { open = if (open == q) null else q }.padding(vertical = 12.dp)) {
                    Row {
                        Text(if (open == q) "−" else "+", fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, color = palette.primarySolid)
                        Spacer(Modifier.width(8.dp))
                        Text(q, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            color = palette.textPrimary, modifier = Modifier.weight(1f))
                    }
                    if (open == q) Text(a, fontSize = 13.sp, color = palette.textSecondary,
                        lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            SectionLabel(stringResource(R.string.mail_support_where_person_answers), palette)
            Text("grapsee.com/contact", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = palette.primarySolid,
                modifier = Modifier.clickable {
                    openExternal(vm.getApplication(), "https://grapsee.com/contact")
                }.padding(vertical = 6.dp))
            Text(stringResource(R.string.mail_support_recovery_screens_order_fixes), fontSize = 13.sp,
                color = palette.textSecondary, modifier = Modifier.padding(top = 6.dp))
            listOf(stringResource(R.string.mail_support_forgot_password) to onForgot, stringResource(R.string.mail_support_change_my_phone) to onPhone,
                stringResource(R.string.mail_support_manage_addresses) to onAddresses).forEach { (label, go) ->
                Text(label, fontSize = 14.sp, color = palette.primarySolid,
                    modifier = Modifier.clickable(onClick = go).padding(vertical = 6.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Hand a URL to the device, the same rule the unsubscribe sheet applies. */
private fun openExternal(context: android.content.Context, url: String) {
    try {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // No browser on this device: the link is shown on screen as text
        // already, so nothing is lost — and a crash is not an answer.
    }
}
