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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.grapsee.grapmail.logic.ProfileViewModel
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.theme.LocalPalette

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
        Icon(Icons.Rounded.Delete, "Remove", tint = palette.textTertiary,
            modifier = Modifier.size(20.dp).clickable(onClick = onRemove))
    }
}

/** A labelled radio row: one option of a fixed set, written the moment it is picked. */
@Composable
private fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
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
        Text(title, fontSize = 15.sp, color = palette.textPrimary)
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
            else Icon(Icons.Rounded.Add, "Add", tint = palette.onPrimary)
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
    val state by vm.state.collectAsState()
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.load() }
    MailScaffold("Notes", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)) {
            state.note?.let { NoteLine(it, state.noteIsBad) }
            AddRow("Write a note", draft, { draft = it }, {
                val list = state.profile?.settings?.notes ?: return@AddRow
                vm.saveSettings(ProfileSettingsPatch(notes = listOf(draft.trim()) + list))
                draft = ""
            }, state.busy, "gm-add-note")
            Text("Notes are stored on the account, not on this phone, so they follow you to any " +
                "machine you sign in from.", fontSize = 12.sp, color = palette.textTertiary,
                modifier = Modifier.padding(vertical = 6.dp))
            val notes = state.profile?.settings?.notes
            when {
                state.profile == null && state.loading ->
                    Text("Loading…", fontSize = 14.sp, color = palette.textTertiary,
                        modifier = Modifier.padding(vertical = 16.dp))
                state.profile == null && state.fatal != null ->
                    Text("Notes could not be read. ${state.fatal}", fontSize = 14.sp,
                        color = palette.textSecondary, modifier = Modifier.padding(vertical = 16.dp))
                notes != null && notes.isEmpty() ->
                    Text("Nothing kept here yet.", fontSize = 14.sp, color = palette.textTertiary,
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
    val state by vm.state.collectAsState()
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.load() }
    MailScaffold("Tasks", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)) {
            state.note?.let { NoteLine(it, state.noteIsBad) }
            AddRow("Add a task", draft, { draft = it }, {
                val list = state.profile?.settings?.tasks ?: return@AddRow
                vm.saveSettings(ProfileSettingsPatch(tasks = list + draft.trim()))
                draft = ""
            }, state.busy, "gm-add-task")
            Text("Ticking a task removes it. This list has no archive.",
                fontSize = 12.sp, color = palette.textTertiary,
                modifier = Modifier.padding(vertical = 6.dp))
            val tasks = state.profile?.settings?.tasks
            when {
                state.profile == null && state.loading ->
                    Text("Loading…", fontSize = 14.sp, color = palette.textTertiary,
                        modifier = Modifier.padding(vertical = 16.dp))
                state.profile == null && state.fatal != null ->
                    Text("Tasks could not be read. ${state.fatal}", fontSize = 14.sp,
                        color = palette.textSecondary, modifier = Modifier.padding(vertical = 16.dp))
                tasks != null && tasks.isEmpty() ->
                    Text("No tasks on this account.", fontSize = 14.sp, color = palette.textTertiary,
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
    val state by vm.state.collectAsState()
    val s = state.profile?.settings
    var signature by remember(s?.signature) { mutableStateOf(s?.signature ?: "") }
    var language by remember(s?.locale) { mutableStateOf(s?.locale?.language ?: "") }
    var timezone by remember(s?.locale) { mutableStateOf(s?.locale?.timezone ?: "") }
    var blockedDraft by remember { mutableStateOf("") }
    var safeDraft by remember { mutableStateOf("") }
    // The per-address overrides being edited, seeded from the profile. The
    // web's "Signature per address" section is the reference: one box per
    // address compose can send as, empty means "use the main one".
    var overrides by remember(s?.signatures) { mutableStateOf(s?.signatures ?: emptyMap()) }
    LaunchedEffect(Unit) { vm.load() }
    MailScaffold("Mail settings", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)) {
            state.note?.let { NoteLine(it, state.noteIsBad) }
            if (s == null && state.fatal != null) {
                Text("These settings could not be read. ${state.fatal}", fontSize = 14.sp,
                    color = palette.textSecondary, modifier = Modifier.padding(vertical = 16.dp))
                return@Column
            }
            if (s == null) {
                Text("Loading…", fontSize = 14.sp, color = palette.textTertiary,
                    modifier = Modifier.padding(vertical = 16.dp))
                return@Column
            }

            SectionLabel("Signature", palette)
            Text("Appended to what you send, on every address you write from unless an override " +
                "says otherwise.", fontSize = 12.sp, color = palette.textTertiary)
            Box(Modifier.fillMaxWidth().heightIn(min = 80.dp)
                .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                .padding(12.dp)
                .testTag("gm-signature-field")) {
                BasicTextField(signature, { signature = it },
                    textStyle = TextStyle(fontSize = 15.sp, color = palette.textPrimary,
                        lineHeight = 22.sp),
                    cursorBrush = SolidColor(palette.primarySolid),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    decorationBox = { inner ->
                        if (signature.isEmpty()) Text("— none —", fontSize = 15.sp,
                            color = palette.textTertiary)
                        inner()
                    })
            }
            Text("Save signature", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
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
            SectionLabel("Signature per address", palette)
            Text("When you send as an alias, this is what gets added, so an alias does " +
                "not sign like your personal address. Leave one empty and that address " +
                "uses the main signature above.", fontSize = 12.sp, color = palette.textTertiary)
            val signatureAddresses =
                listOfNotNull(state.profile?.address?.takeIf { it.isNotBlank() }) +
                    (state.profile?.aliases ?: emptyList()).map { it.address }
            signatureAddresses.forEach { address ->
                Text(address, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = palette.textSecondary, modifier = Modifier.padding(top = 10.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(top = 4.dp)
                    .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .semantics { contentDescription = "Signature for $address" }
                    .testTag("gm-sig-override-field")) {
                    BasicTextField(overrides[address] ?: "",
                        { v -> overrides = overrides + (address to v) },
                        textStyle = TextStyle(fontSize = 14.sp, color = palette.textPrimary,
                            lineHeight = 20.sp),
                        cursorBrush = SolidColor(palette.primarySolid),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        decorationBox = { inner ->
                            if (overrides[address].isNullOrEmpty()) Text("Uses the main signature",
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
            Text("Save per-address signatures", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (dirty) palette.primarySolid else palette.textTertiary,
                modifier = Modifier.clickable(enabled = dirty && !state.busy) {
                    vm.saveSettings(ProfileSettingsPatch(signatures = toSave))
                }.padding(vertical = 8.dp))

            SectionLabel("List density", palette)
            RadioRow("Comfortable — the roomier list", s.density != "compact", {
                if (s.density != "comfortable") vm.saveSettings(ProfileSettingsPatch(density = "comfortable"))
            })
            RadioRow("Compact — more messages per screen", s.density == "compact", {
                if (s.density != "compact") vm.saveSettings(ProfileSettingsPatch(density = "compact"))
            })

            SectionLabel("Language and dates", palette)
            AppTextField("Language", language, { language = it },
                hint = "en-US")
            AppTextField("Time zone", timezone, { timezone = it },
                hint = "Asia/Dhaka", modifier = Modifier.padding(top = 8.dp))
            Text("Save language and time zone", fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.primarySolid,
                modifier = Modifier.clickable(enabled = !state.busy &&
                    (language.isNotBlank() || timezone.isNotBlank()) &&
                    (language != s.locale.language || timezone != s.locale.timezone)) {
                    vm.saveSettings(ProfileSettingsPatch(locale = LocaleDto(
                        language = language.ifBlank { s.locale.language },
                        timezone = timezone.ifBlank { s.locale.timezone },
                        dateFormat = s.locale.dateFormat)))
                }.padding(vertical = 8.dp))
            Text("Dates in messages and the list:", fontSize = 13.sp,
                color = palette.textSecondary)
            listOf("dmy" to "day first (25/10/26)", "mdy" to "month first (10/25/26)",
                "ymd" to "year first (2026-10-25)").forEach { (code, label) ->
                RadioRow(label, s.locale.dateFormat == code, {
                    if (s.locale.dateFormat != code) vm.saveSettings(ProfileSettingsPatch(
                        locale = s.locale.copy(dateFormat = code)))
                })
            }

            SectionLabel("Senders you always accept", palette)
            Text("A safe sender skips the approval queue even when two-step checking is on.",
                fontSize = 12.sp, color = palette.textTertiary)
            AddRow("address@example.com", safeDraft, { safeDraft = it }, {
                vm.saveSettings(ProfileSettingsPatch(
                    safeSenders = (s.safeSenders + safeDraft.trim().lowercase()).distinct()))
                safeDraft = ""
            }, state.busy, "gm-add-safe")
            s.safeSenders.forEach { e ->
                ListRow(e, { vm.saveSettings(ProfileSettingsPatch(
                    safeSenders = s.safeSenders.filter { it != e })) }, "gm-safe-row")
            }

            SectionLabel("Senders you block", palette)
            Text("Blocked senders are refused at the door: nothing arrives and nothing queues " +
                "for approval.", fontSize = 12.sp, color = palette.textTertiary)
            AddRow("address@example.com", blockedDraft, { blockedDraft = it }, {
                vm.saveSettings(ProfileSettingsPatch(
                    blockedSenders = (s.blockedSenders + blockedDraft.trim().lowercase()).distinct()))
                blockedDraft = ""
            }, state.busy, "gm-add-blocked")
            s.blockedSenders.forEach { e ->
                ListRow(e, { vm.saveSettings(ProfileSettingsPatch(
                    blockedSenders = s.blockedSenders.filter { it != e })) }, "gm-blocked-row")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
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
    val state by vm.state.collectAsState()
    var label by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.load() }
    MailScaffold("Addresses", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)) {
            state.note?.let { NoteLine(it, state.noteIsBad) }
            SectionLabel("Your GrapMail address", palette)
            Text(state.profile?.address ?: (if (state.fatal != null) "Could not be read" else "Loading…"),
                fontSize = 16.sp, fontWeight = FontWeight.Medium, color = palette.textPrimary,
                modifier = Modifier.fillMaxWidth().testTag("gm-primary-address")
                    .background(palette.bgTinted, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp))
            SectionLabel("Email aliases", palette)
            Text("An alias delivers into the same mailbox under a second address, and you can " +
                "choose it as the sender.", fontSize = 12.sp, color = palette.textTertiary)
            AddRow("A label — business, family…", label, { label = it }, {
                // `POST /aliases` mints the address itself; the label is what the
                // person calls it by. The screen reloads from the profile after.
                vmAliasCreate(vm, label) { label = "" }
            }, state.busy, "gm-add-alias")
            val aliases = state.profile?.aliases
            when {
                aliases == null && state.fatal != null ->
                    Text("Aliases could not be read.", fontSize = 14.sp,
                        color = palette.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
                aliases != null && aliases.isEmpty() ->
                    Text("No aliases yet.", fontSize = 14.sp, color = palette.textTertiary,
                        modifier = Modifier.padding(vertical = 12.dp))
                aliases != null -> aliases.forEach { a ->
                    Row(Modifier.fillMaxWidth().testTag("gm-alias-row").padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.label.ifBlank { a.address }, fontSize = 15.sp,
                                fontWeight = FontWeight.Medium, color = palette.textPrimary)
                            Text(a.address, fontSize = 13.sp, color = palette.textSecondary)
                            if (!a.isActive) Text("Paused — nothing new arrives here.",
                                fontSize = 12.sp, color = palette.warnInk)
                        }
                        Icon(Icons.Rounded.Delete, "Remove ${a.address}",
                            tint = palette.error,
                            modifier = Modifier.size(20.dp)
                                .clickable { pendingDelete = a.address })
                    }
                }
            }
            pendingDelete?.let { address ->
                Column(Modifier.fillMaxWidth().background(palette.warnChipBg, RoundedCornerShape(12.dp))
                    .padding(16.dp).testTag("gm-alias-confirm")) {
                    Text("Stop receiving at $address? Mail already delivered stays in the " +
                        "mailbox; nothing new arrives at this address.",
                        fontSize = 13.sp, color = palette.textPrimary)
                    Row(Modifier.padding(top = 12.dp)) {
                        Text("Cancel", fontSize = 14.sp, color = palette.textSecondary,
                            modifier = Modifier.clickable { pendingDelete = null })
                        Spacer(Modifier.width(24.dp))
                        Text("Remove address", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = palette.error,
                            modifier = Modifier.clickable {
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
    val state by vm.state.collectAsState()
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
    MailScaffold("Connected accounts", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)) {
            state.note?.let { NoteLine(it, state.noteIsBad) }
            Text("When you use this account to sign in somewhere, that app appears here with " +
                "what it can reach, and you can remove it at any time.",
                fontSize = 13.sp, color = palette.textSecondary,
                modifier = Modifier.padding(vertical = 8.dp))
            AppTextField("", query, { query = it }, hint = "Search connected apps by name",
                showLabel = false, modifier = Modifier.testTag("gm-connections-search"))
            Row(Modifier.padding(vertical = 10.dp)) {
                listOf("all" to "All", "mailbox" to "With mail", "products" to "Grapsee apps")
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
                Text(if (connected.isEmpty()) "No app has access to this account."
                    else "Nothing matches that.",
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
                        Text(if (scopes.isBlank()) "Signed in here" else "Sees: $scopes",
                            fontSize = 12.sp, color = palette.textSecondary)
                    }
                    Text("Remove", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = palette.error,
                        modifier = Modifier.clickable { pendingRemove = c })
                }
            }
            pendingRemove?.let { c ->
                Column(Modifier.fillMaxWidth().background(palette.warnChipBg, RoundedCornerShape(12.dp))
                    .padding(16.dp).testTag("gm-connection-confirm")) {
                    Text("Cut ${c.product} off? It loses access to what is listed beside it, " +
                        "and has to sign in again to get back in.",
                        fontSize = 13.sp, color = palette.textPrimary)
                    Row(Modifier.padding(top = 12.dp)) {
                        Text("Cancel", fontSize = 14.sp, color = palette.textSecondary,
                            modifier = Modifier.clickable { pendingRemove = null })
                        Spacer(Modifier.width(24.dp))
                        Text("Remove access", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = palette.error,
                            modifier = Modifier.clickable {
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
    "profile" -> "name, address and photo"
    "mailbox" -> "your mailbox"
    "projects" -> "your projects"
    "contacts" -> "your contacts"
    else -> scope
}

/** The web's FULL sharing sentences (`LinkedAppDetail.tsx` SHARED map), for the
 *  per-app page where the person decides what cutting access actually costs.
 *  The list row uses the short words; the detail uses these, exactly as the
 *  web splits the two. */
fun sharedWord(scope: String): String = when (scope) {
    "profile" -> "Your name, GrapMail address and profile photo"
    "mailbox" -> "Read and send mail in your GrapMail mailbox"
    "projects" -> "Projects and client records you own on Grapsee products"
    "contacts" -> "The people you have exchanged mail with"
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
    val state by vm.state.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.load() }
    val connected = state.profile?.connections ?: emptyList()
    val entry = connected.firstOrNull { it.product.lowercase() == product.lowercase() }
    MailScaffold(product, onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)) {
            // The unread shape FIRST: an entry missing because the profile has
            // not been read is not an entry that is absent. Showing "not
            // connected" during the read would be the invention this whole
            // screen exists to avoid.
            if (state.profile == null) {
                Text(if (state.loading) "Loading…"
                     else "The account could not be read, so this screen cannot " +
                             "say what the app can reach.",
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
                    Text(product, fontSize = 20.sp, color = palette.textPrimary, maxLines = 1,
                        modifier = Modifier.testTag("gm-connection-detail-name"))
                    val who = entry?.let { (it.host ?: "Grapsee product") + " · connected " +
                        (it.connectedAt ?: "").take(10) }
                    Text(who ?: "Not connected to this account",
                        fontSize = 13.sp, color = palette.textSecondary,
                        maxLines = 2)
                }
            }
            if (entry == null) {
                // The web's exact stance: a link to an app that is not linked
                // is answered, not redirected and not fabricated.
                Text("This app does not have access to this account. Nothing is " +
                        "being shared with it, and there is nothing to remove.",
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
                Text("How GrapMail helps you sign in to $product",
                    fontSize = 15.sp, color = palette.textPrimary)
                Text("When you use your Grapsee account to sign in to $product, this " +
                        "account proves who you are and hands over only what is listed " +
                        "below. Your password is never shared with $product.",
                    fontSize = 13.sp, lineHeight = 20.sp, color = palette.textSecondary,
                    modifier = Modifier.padding(top = 8.dp))
                Text(product.uppercase() + " CAN REACH",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(top = 14.dp))
                if (entry.scopes.isEmpty()) {
                    Text("No access was recorded when this was connected.",
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
                    Text(if (state.busy) "Removing…" else "Remove access",
                        fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.error)
                    Text("Remove all links and stop sharing this account's data with $product.",
                        fontSize = 12.sp, lineHeight = 17.sp, color = palette.textSecondary)
                }
            }
            if (confirmRemove) {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp)
                    .background(palette.warnChipBg, RoundedCornerShape(12.dp))
                    .padding(16.dp).testTag("gm-connection-confirm")) {
                    Text("Cut $product off? It loses access to everything listed above " +
                            "and has to sign in again to get back in.",
                        fontSize = 13.sp, color = palette.textPrimary)
                    Row(Modifier.padding(top = 12.dp)) {
                        Text("Cancel", fontSize = 14.sp, color = palette.textSecondary,
                            modifier = Modifier.clickable { confirmRemove = false })
                        Spacer(Modifier.width(24.dp))
                        Text("Remove access", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = palette.error,
                            modifier = Modifier.clickable {
                                confirmRemove = false
                                vm.saveConnections(connected.filter {
                                    it.product.lowercase() != product.lowercase()
                                })
                            })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Something wrong with this app?", fontSize = 12.sp, color = palette.textSecondary)
            Text("Report this app", fontSize = 13.sp, fontWeight = FontWeight.Medium,
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
fun ProfileEditScreen(vm: ProfileViewModel, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsState()
    val p = state.profile
    var first by remember(p?.firstName) { mutableStateOf(p?.firstName ?: "") }
    var last by remember(p?.lastName) { mutableStateOf(p?.lastName ?: "") }
    var display by remember(p?.displayName) { mutableStateOf(p?.displayName ?: "") }
    var recoveryEmail by remember(p?.recoveryEmail) { mutableStateOf(p?.recoveryEmail ?: "") }
    var recoveryPhone by remember(p?.recoveryPhone) { mutableStateOf(p?.recoveryPhone ?: "") }
    var birthday by remember(p?.birthday) { mutableStateOf(p?.birthday ?: "") }
    var gender by remember(p?.gender) { mutableStateOf(p?.gender ?: "") }
    var photoError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            // Read the bytes, honour the server's own ceiling, and hand them to
            // the upload route. A refusal says why in the same words the API
            // uses — the screen never uploads something it already knows will
            // be rejected.
            val ctx = vm.getApplication<android.app.Application>()
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.onSuccess { bytes ->
                when {
                    bytes == null -> photoError = "That file could not be read."
                    bytes.size > 256 * 1024 ->
                        photoError = "That photo is too large. A profile photo can be up to 256 KB."
                    else -> {
                        val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
                        val ext = when (mime) {
                            "image/png" -> "png"; "image/webp" -> "webp"
                            "image/gif" -> "gif"; else -> "jpg"
                        }
                        vm.uploadAvatar(bytes, "profile.$ext", mime)
                        photoError = null
                    }
                }
            }.onFailure { photoError = "That file could not be read." }
        }
    }

    LaunchedEffect(Unit) { vm.load() }
    MailScaffold("Personal info", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)) {
            state.note?.let { NoteLine(it, state.noteIsBad) }
            photoError?.let { NoteLine(it, true) }
            if (p == null) {
                Text(if (state.fatal != null) "Your account record could not be read. ${state.fatal}"
                    else "Loading…",
                    fontSize = 14.sp, color = palette.textTertiary,
                    modifier = Modifier.padding(vertical = 16.dp))
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 10.dp)) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(palette.softFill),
                    contentAlignment = Alignment.Center) {
                    val bytes = state.avatar
                    if (bytes != null && state.avatarTried) {
                        // Decode where it is drawn: a corrupt or unsupported
                        // bitmap falls back to the initial, not to a crash — the
                        // bytes came from the server, and refusing to render them
                        // is a display question, not a data question.
                        val img = remember(bytes) {
                            runCatching {
                                android.graphics.BitmapFactory
                                    .decodeByteArray(bytes, 0, bytes.size)
                                    ?.asImageBitmap()
                            }.getOrNull()
                        }
                        if (img != null) {
                            Image(img, null, Modifier.fillMaxSize().clip(CircleShape))
                        } else Text(p.name.take(1).uppercase(), fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold, color = palette.primarySolid)
                    } else Text(p.name.take(1).uppercase().ifBlank { "?" },
                        fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        color = palette.primarySolid)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(if (p.avatarUrl.isNullOrBlank()) "Add a photo" else "Change photo",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = palette.primarySolid,
                        modifier = Modifier.clickable { picker.launch("image/*") })
                    if (!p.avatarUrl.isNullOrBlank()) {
                        Text("Remove", fontSize = 13.sp, color = palette.error,
                            modifier = Modifier.padding(top = 4.dp).clickable { vm.removeAvatar() })
                    }
                }
            }
            SectionLabel("Your name", palette)
            AppTextField("First name", first, { first = it })
            AppTextField("Last name", last, { last = it },
                modifier = Modifier.padding(top = 8.dp))
            SectionLabel("Birthday", palette)
            Text("A GrapMail address can receive mail from anyone, so the account requires an " +
                "age of at least 13 — the same rule signup enforces.",
                fontSize = 12.sp, color = palette.textTertiary)
            AppTextField("Birthday", birthday, { birthday = it },
                hint = "YYYY-MM-DD",
                // KeyboardType.Text, not a date keyboard: Compose has no Date
                // keyboard type (the enum stops at ASCII/Percent), and the
                // field takes the route's own `YYYY-MM-DD` free text.
                keyboard = KeyboardType.Text, modifier = Modifier.padding(top = 6.dp))
            SectionLabel("Gender", palette)
            listOf("Female", "Male", "Rather not say", "Custom").forEach { g ->
                RadioRow(g, gender == g, { gender = if (gender == g) "" else g })
            }
            SectionLabel("How you appear", palette)
            AppTextField("Display name", display, { display = it },
                hint = "What recipients see")
            SectionLabel("Recovery", palette)
            AppTextField("Recovery email", recoveryEmail, { recoveryEmail = it },
                hint = "A mailbox you can still reach", keyboard = KeyboardType.Email)
            AppTextField("Recovery phone", recoveryPhone, { recoveryPhone = it },
                hint = "+8801XXXXXXXXX", keyboard = KeyboardType.Phone,
                modifier = Modifier.padding(top = 8.dp))
            Text("Save", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = palette.onPrimary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("gm-profile-save")
                    .background(if (state.busy) palette.buttonDisabled else palette.primarySolid,
                        RoundedCornerShape(24.dp))
                    .clickable(enabled = !state.busy) {
                        vm.save("Saved.", ProfilePatch(
                            firstName = first, lastName = last, birthday = birthday,
                            gender = gender, displayName = display,
                            recoveryEmail = recoveryEmail, recoveryPhone = recoveryPhone))
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
) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsState()
    val p = state.profile
    LaunchedEffect(Unit) { vm.load() }
    MailScaffold("Data & privacy", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)) {
            SectionLabel("What is kept", palette)
            listOf(
                "GrapMail address and aliases: the identity of the account and any extra " +
                    "addresses that deliver into it.",
                "Your messages: stored for your account only. Nobody at Grapsee reads " +
                    "message contents to build profiles, and addresses are not sold.",
                "When you agreed to the terms: a timestamp, so the record can answer " +
                    "\u201cdid they agree, and when?\u201d",
            ).forEach {
                Text("• $it", fontSize = 13.sp, color = palette.textSecondary,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
            SectionLabel("Your controls", palette)
            SettingRow(Icons.Rounded.Person, "Edit personal info", "Name, birthday, photo",
                palette, onClick = onProfile)
            SettingRow(Icons.Rounded.Person, "Change phone number", "Proven with a code",
                palette, onClick = onPhone)
            SettingRow(Icons.Rounded.MailOutline, "Manage addresses", "Primary and aliases",
                palette, onClick = onAddresses)
            SettingRow(Icons.Rounded.Settings, "Mail settings", "Signature, density, locale",
                palette, onClick = onPrefs)
            SectionLabel("This account", palette)
            DetailLine("Address", p?.address
                ?: if (state.fatal != null) "Could not be read" else "Loading…", palette)
            DetailLine("Created", p?.createdAt?.take(10)
                ?: if (p == null) "Could not be read" else "Not reported", palette)
            DetailLine("Terms accepted", p?.termsAcceptedAt?.take(10)
                ?: if (p == null) "Could not be read" else "not recorded", palette)
            DetailLine("2-step verification", when (p?.twoFactorState) {
                true -> "On"
                false -> "Off"
                null -> if (p == null) "Could not be read" else "Not reported"
            }, palette)
            SectionLabel("Deleting the account", palette)
            Text("Deleting is self-serve and permanent: it removes the mailbox, every " +
                "message, the aliases and the address itself, and it cannot be undone. " +
                "The flow asks for the account password and the server confirms it; " +
                "this page does not carry the confirmation itself, so the destructive " +
                "step exists in exactly one place.",
                fontSize = 13.sp, color = palette.textSecondary,
                modifier = Modifier.padding(vertical = 6.dp))
            Text("Delete this account…", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = palette.primarySolid, modifier = Modifier.clickable(onClick = onDeleteAccount)
                    .padding(vertical = 6.dp))
            SectionLabel("The documents", palette)
            Text("Privacy Policy · Terms of Service", fontSize = 14.sp,
                color = palette.primarySolid,
                modifier = Modifier.clickable {
                    openExternal(vm.getApplication(), "https://grapsee.com/legal/privacy-policy")
                }.padding(vertical = 6.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Same helper the mail screens use, reached from the privacy rows. */
@Composable
private fun DetailLine(label: String, value: String, palette: com.grapsee.grapmail.ui.theme.Palette) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, fontSize = 13.sp, color = palette.textSecondary, modifier = Modifier.width(150.dp))
        Text(value, fontSize = 14.sp, color = palette.textPrimary, modifier = Modifier.weight(1f))
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
            "I forgot my password. What now?" to
                "A GrapMail account has no second inbox, so recovery proves the account with " +
                "the phone number on file: a 6-digit code goes to that number and the new " +
                "password is set only after the code is presented.",
            "Can I sign in with my phone number?" to
                "Yes. The sign-in page accepts either the GrapMail address or the phone " +
                "number you verified, but the number is never the identity of the account, " +
                "and mail is never addressed to it.",
            "I changed my number. Will I lose the account?" to
                "No, if you change it before losing access. The new number is proven with a " +
                "code before it replaces the old one.",
            "Why can I not create an account for someone under 13?" to
                "GrapMail holds an address that can receive mail from anyone, so the account " +
                "requires an age of at least 13. The birthday you enter is checked against " +
                "that rule.",
            "What is an alias for?" to
                "An alias delivers into the same mailbox under a second address, and can be " +
                "chosen as the sender. Useful when you want to write as \u201cbusiness\u201d " +
                "without a second account.",
            "Mail I was expecting never arrived." to
                "In order: check the sending address was spelled exactly, look in Spam and " +
                "Trash under \u201cOthers\u201d, then check that the sender is not on the " +
                "suppression list because they unsubscribed earlier. If the mailbox is " +
                "connected and the message is still missing, the mail server has to be " +
                "involved. That is a support matter.",
        )
    }
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.load() }
    MailScaffold("Help & support", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)) {
            Text("Answers to the questions this product actually raises, and how to reach a " +
                "person.", fontSize = 13.sp, color = palette.textTertiary,
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
            SectionLabel("Where a person answers", palette)
            Text("grapsee.com/contact", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = palette.primarySolid,
                modifier = Modifier.clickable {
                    openExternal(vm.getApplication(), "https://grapsee.com/contact")
                }.padding(vertical = 6.dp))
            Text("Recovery screens, in the order that fixes things:", fontSize = 13.sp,
                color = palette.textSecondary, modifier = Modifier.padding(top = 6.dp))
            listOf("Forgot password" to onForgot, "Change my phone" to onPhone,
                "Manage addresses" to onAddresses).forEach { (label, go) ->
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
