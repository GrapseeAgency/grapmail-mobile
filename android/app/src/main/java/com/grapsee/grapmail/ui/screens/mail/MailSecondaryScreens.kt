package com.grapsee.grapmail.ui.screens.mail

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.PersonAddAlt1
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.MailAliasDto
import com.grapsee.grapmail.data.MailLabelDto
import com.grapsee.grapmail.logic.MailboxViewModel
import com.grapsee.grapmail.ui.components.CountBadge
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.OutlineButton
import com.grapsee.grapmail.ui.components.SenderAvatar
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * The mailbox's secondary destinations — reference 72, 73, 77, 78, 79, 80, 81
 * and 85.
 *
 * Each one states what it actually is. Labels are real records from `/labels`.
 * Contacts are derived from the mail the app has seen, because there is no
 * contacts endpoint. Files are the attachments of messages the user has opened,
 * because there is no list-all attachments route. Saying so on screen is the
 * difference between a partial view and a false one.
 */

@Composable
private fun MailScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize().background(palette.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ArrowBack, "Back", tint = palette.textPrimary,
                modifier = Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onBack).padding(2.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun EmptyNote(title: String, body: String) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(body, fontSize = 13.sp, color = palette.textSecondary)
    }
}

@Composable
private fun NoteBar(text: String) {
    val palette = LocalPalette.current
    Text(
        text, fontSize = 12.sp, color = palette.warnInk,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp)).background(palette.warnChipBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

// ── 72 labels ────────────────────────────────────────────────────────────────

@Composable
fun LabelsScreen(vm: MailboxViewModel, onBack: () -> Unit, onCreate: () -> Unit,
                 onEdit: (MailLabelDto) -> Unit) {
    val palette = LocalPalette.current
    val state by vm.labelState.collectAsState()
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refreshLabels() }

    MailScaffold("Labels", onBack) {
        Column(Modifier.fillMaxSize()) {
            NoteBar("Labels are the real filter: the reference's Personal and " +
                "Work chips have no backing in the API, so these are what the " +
                "chip row is built from.")
            notice?.let {
                Text(it, fontSize = 12.sp, color = palette.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${state.labels.size} label${if (state.labels.size == 1) "" else "s"}",
                    fontSize = 13.sp, color = palette.textSecondary, modifier = Modifier.weight(1f))
                OutlineButton("New label", onClick = onCreate,
                    trailing = { Icon(Icons.Rounded.Add, null, tint = palette.primarySolid,
                        modifier = Modifier.size(16.dp)) })
            }
            if (state.loading && state.labels.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.primarySolid)
                }
            } else if (state.labels.isEmpty()) {
                EmptyNote("No labels yet",
                    "Create one and it becomes a filter on the inbox.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.labels, key = { it.id }) { label ->
                        // The row is the edit affordance. It already looked
                        // tappable and did nothing, which is the one thing this
                        // sector was told not to ship — renaming is what tapping
                        // a label's own row should mean, and now it is.
                        Row(
                            Modifier.fillMaxWidth().clickable { onEdit(label) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(12.dp).clip(CircleShape)
                                .background(com.grapsee.grapmail.ui.components.parseHex(label.color)
                                    ?: palette.primarySolid))
                            Spacer(Modifier.width(12.dp))
                            Text(label.name, fontSize = 15.sp, color = palette.textPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            CountBadge(label.unread)
                            Spacer(Modifier.width(10.dp))
                            Icon(Icons.Rounded.Delete, "Remove label ${label.name}",
                                tint = palette.textTertiary,
                                modifier = Modifier.size(18.dp).clickable {
                                    vm.deleteLabel(label.id) { notice = it }
                                })
                        }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp)
                            .background(palette.border))
                    }
                }
            }
        }
    }
}

// ── 73 create label ──────────────────────────────────────────────────────────

@Composable
fun LabelCreateScreen(vm: MailboxViewModel, onDone: () -> Unit,
                      editing: MailLabelDto? = null) {
    val palette = LocalPalette.current
    // Prefilled when a row opened this screen for editing, empty for a new one.
    // One screen for both, because the fields, the ramp and the validation are
    // the same control and a second copy of them would drift.
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var chosen by remember { mutableStateOf(com.grapsee.grapmail.ui.components.parseHex(editing?.color)
            ?: Ramp[0]) }
    var result by remember { mutableStateOf<String?>(null) }

    MailScaffold(if (editing == null) "New label" else "Edit label", onDone) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Text("Name", fontSize = 13.sp, color = palette.textSecondary)
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = name, onValueChange = { name = it }, maxLines = 1,
                textStyle = TextStyle(fontSize = 16.sp, color = palette.textPrimary),
                modifier = Modifier.fillMaxWidth().testTag("label-name")
                    .clip(RoundedCornerShape(12.dp)).background(palette.card)
                    .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    Box {
                        if (name.isEmpty()) Text("Work, Travel, Receipts…",
                            fontSize = 16.sp, color = palette.textTertiary)
                        inner()
                    }
                },
            )

            Spacer(Modifier.height(20.dp))
            Text("Colour", fontSize = 13.sp, color = palette.textSecondary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Ramp.forEach { hex ->
                    val picked = hex == chosen
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(hex)
                            .border(if (picked) 3.dp else 1.dp,
                                if (picked) palette.textPrimary else palette.border, CircleShape)
                            .clickable { chosen = hex },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (picked) Icon(Icons.Rounded.Check, null, tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(16.dp))
                    }
                }
            }

            result?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, fontSize = 13.sp, color = palette.error)
            }

            Spacer(Modifier.height(24.dp))
            GradientButton(if (editing == null) "Create label" else "Save changes", onClick = {
                if (name.isBlank()) { result = "A label needs a name."; return@GradientButton }
                val done: (String) -> Unit = { outcome ->
                    result = null
                    // The route caps labels and rejects duplicates; either way it
                    // says why, and that text is what the user sees.
                    if (outcome.startsWith("Created") || outcome == "Saved.") onDone()
                    else result = outcome
                }
                if (editing == null) vm.createLabel(name.trim(), hexString(chosen), done)
                else vm.renameLabel(editing.id, name.trim(), hexString(chosen), done)
            }, enabled = name.isNotBlank())
        }
    }
}

private val Ramp = listOf(
    androidx.compose.ui.graphics.Color(0xFF1A6DF0), androidx.compose.ui.graphics.Color(0xFF7C3AED),
    androidx.compose.ui.graphics.Color(0xFF059669), androidx.compose.ui.graphics.Color(0xFFF97316),
    androidx.compose.ui.graphics.Color(0xFFEC4899), androidx.compose.ui.graphics.Color(0xFFDC2626),
)

private fun hexString(c: androidx.compose.ui.graphics.Color): String {
    val r = (c.red * 255).toInt().coerceIn(0, 255)
    val g = (c.green * 255).toInt().coerceIn(0, 255)
    val b = (c.blue * 255).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(r, g, b)
}

// ── 77 contacts, 85 contact detail ───────────────────────────────────────────

@Composable
fun ContactsScreen(vm: MailboxViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val palette = LocalPalette.current
    var people by remember { mutableStateOf<List<MailboxViewModel.Person>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { people = vm.people() }
    val shown = remember(query, people) {
        if (query.isBlank()) people
        else people.filter {
            it.address.contains(query.trim(), ignoreCase = true) ||
                it.name?.contains(query.trim(), ignoreCase = true) == true
        }
    }

    MailScaffold("Contacts", onBack) {
        Column(Modifier.fillMaxSize()) {
            NoteBar("People are read out of the mail you have received and sent. " +
                "There is no separate contacts store in GrapMail, so this list " +
                "grows with your mailbox rather than being edited by hand.")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(12.dp)).background(palette.card)
                    .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Contacts, null, tint = palette.textTertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query, onValueChange = { query = it }, maxLines = 1,
                    textStyle = TextStyle(fontSize = 14.sp, color = palette.textPrimary),
                    modifier = Modifier.weight(1f).testTag("contacts-search"),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) Text("Search people", fontSize = 14.sp,
                                color = palette.textTertiary)
                            inner()
                        }
                    },
                )
            }
            if (shown.isEmpty()) {
                EmptyNote("No people yet",
                    "Contacts appear here as mail arrives from or to them.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.address }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(p.address) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SenderAvatar(p.name ?: "", p.address, size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name ?: p.address, fontSize = 15.sp, color = palette.textPrimary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!p.name.isNullOrBlank()) {
                                    Text(p.address, fontSize = 12.sp, color = palette.textSecondary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (p.seen > 0) CountBadge(p.seen)
                        }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp)
                            .background(palette.border))
                    }
                }
            }
        }
    }
}

@Composable
fun ContactDetailScreen(vm: MailboxViewModel, address: String, onBack: () -> Unit, onWrite: () -> Unit) {
    val palette = LocalPalette.current
    var person by remember { mutableStateOf<MailboxViewModel.Person?>(null) }
    LaunchedEffect(address) { person = vm.people().firstOrNull { it.address == address } }

    MailScaffold("Contact", onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            SenderAvatar(person?.name ?: "", address, size = 72.dp)
            Spacer(Modifier.height(14.dp))
            Text(person?.name ?: address, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                color = palette.textPrimary)
            Text(address, fontSize = 14.sp, color = palette.textSecondary)
            Spacer(Modifier.height(18.dp))
            DetailLine("Messages seen", (person?.seen ?: 0).toString(), palette)
            DetailLine("In this build", "Derived from loaded mail", palette)
            Spacer(Modifier.height(22.dp))
            GradientButton("Write", onClick = onWrite)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, palette: com.grapsee.grapmail.ui.theme.Palette) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, fontSize = 13.sp, color = palette.textSecondary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.textPrimary)
    }
}

// ── 78 files, 79 attachment preview ──────────────────────────────────────────

@Composable
fun FilesScreen(vm: MailboxViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val palette = LocalPalette.current
    var files by remember { mutableStateOf<List<MailboxViewModel.FileEntry>>(emptyList()) }
    LaunchedEffect(Unit) { files = vm.files() }

    MailScaffold("Files", onBack) {
        Column(Modifier.fillMaxSize()) {
            NoteBar("This lists the attachments of messages you have opened. " +
                "GrapMail has no endpoint that lists every file in a mailbox, so " +
                "a file inside mail you have not read is not shown here.")
            if (files.isEmpty()) {
                EmptyNote("No files yet", "Open a message with an attachment and it appears here.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(files, key = { it.attachment.id }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(entry.attachment.id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                                .background(palette.softFill), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.AttachFile, null, tint = palette.primarySolid,
                                    modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.attachment.name ?: "Attachment", fontSize = 15.sp,
                                    color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        entry.attachment.sizeBytes?.let { humanBytes(it) },
                                        entry.fromMessage?.let { "in: $it" },
                                    ).joinToString(" · ").ifBlank { "Size not recorded" },
                                    fontSize = 12.sp, color = palette.textSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp)
                            .background(palette.border))
                    }
                }
            }
        }
    }
}

/**
 * 79 — an attachment's own screen.
 *
 * It reports size, type and where it came from, and saves the bytes. It does not
 * claim to preview formats the app cannot decode: an image is offered as a
 * download, not a thumbnail, because there is no image loader in this build and
 * pretending otherwise would show a broken box.
 */
@Composable
fun AttachmentPreviewScreen(vm: MailboxViewModel, attachmentId: String, onBack: () -> Unit) {
    val palette = LocalPalette.current
    var entry by remember { mutableStateOf<MailboxViewModel.FileEntry?>(null) }
    var outcome by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(attachmentId) {
        entry = vm.files().firstOrNull { it.attachment.id == attachmentId }
    }

    MailScaffold("Attachment", onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            val a = entry?.attachment
            if (a == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("This attachment is no longer listed. Open its message again.",
                        fontSize = 14.sp, color = palette.textSecondary)
                }
                return@Column
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(18.dp)).background(palette.softFill),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AttachFile, null, tint = palette.primarySolid, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(a.name ?: "Attachment", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                color = palette.textPrimary)
            Spacer(Modifier.height(10.dp))
            DetailLine("Type", a.mimeType?.takeIf { it.isNotBlank() } ?: "Not recorded", palette)
            DetailLine("Size", a.sizeBytes?.let { humanBytes(it) } ?: "Not recorded", palette)
            entry?.fromMessage?.let { DetailLine("From message", it, palette) }

            outcome?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, fontSize = 12.sp, color = palette.textSecondary, maxLines = 3,
                    overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.height(22.dp))
            GradientButton(if (saving) "Saving…" else "Save to this device", onClick = {
                saving = true
                vm.downloadAttachment(a) { r -> saving = false; outcome = r }
            }, enabled = !saving)
        }
    }
}

// ── 80 settings ──────────────────────────────────────────────────────────────

@Composable
fun MailSettingsScreen(
    vm: MailboxViewModel,
    onBack: () -> Unit,
    onSecurity: () -> Unit,
    onScreener: () -> Unit,
    onLabels: () -> Unit,
    onHelp: () -> Unit,
    onSignOut: () -> Unit,
) {
    val palette = LocalPalette.current
    var aliases by remember { mutableStateOf<List<MailAliasDto>>(emptyList()) }
    LaunchedEffect(Unit) {
        aliases = vm.aliases()
    }

    MailScaffold("Settings", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val me = vm.state.value.accountAddress
            SectionLabel("Account", palette)
            SettingRow(Icons.Rounded.MailOutline, "GrapMail address", me ?: "Not loaded", palette)
            SettingRow(Icons.Rounded.Star, "Mailbox status",
                vm.state.value.accountStatus ?: "Not reported", palette)
            SettingRow(Icons.Rounded.Lock, "Member since",
                vm.state.value.memberSince ?: "Not reported", palette)

            SectionLabel("Sending as", palette)
            if (aliases.isEmpty()) {
                Text("No aliases on this mailbox. An alias is a second address " +
                    "that delivers into the same inbox.",
                    fontSize = 13.sp, color = palette.textSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            } else {
                aliases.forEach { a ->
                    SettingRow(Icons.Rounded.PersonAddAlt1, a.label, a.address, palette,
                        trailing = if (!a.isActive) "paused" else null)
                }
            }

            SectionLabel("Mailbox", palette)
            SettingRow(Icons.Rounded.Shield, "Account security",
                "Password, two-step, sessions", palette, onClick = onSecurity)
            SettingRow(Icons.Rounded.Lock, "Screener",
                "Approval for unknown senders", palette, onClick = onScreener)
            SettingRow(Icons.Rounded.Settings, "Labels",
                "Filters for the inbox", palette, onClick = onLabels)

            SectionLabel("Support", palette)
            SettingRow(Icons.Rounded.HelpOutline, "Help & Support",
                "grapsee.com/contact", palette, onClick = onHelp)

            Spacer(Modifier.height(20.dp))
            Text("Sign out", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.error,
                modifier = Modifier.padding(horizontal = 20.dp).clickable(onClick = onSignOut)
                    .testTag("mail-sign-out"))
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String, palette: com.grapsee.grapmail.ui.theme.Palette) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        color = palette.textTertiary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    palette: com.grapsee.grapmail.ui.theme.Palette,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = palette.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = palette.textPrimary)
            Text(subtitle, fontSize = 12.sp, color = palette.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.let {
            Text(it, fontSize = 12.sp, color = palette.warnInk)
        }
    }
}

// ── 81 screener ──────────────────────────────────────────────────────────────

/**
 * The sender-approval queue's settings.
 *
 * `enabled` is drawn as "Checking…" until the server answers. A switch shown as
 * off after a failed read would tell the person their inbound mail is
 * unfiltered when nothing was said either way — the same rule the security hub
 * learned when an unanswered 2FA verdict started rendering as "Off".
 */
@Composable
fun ScreenerScreen(vm: MailboxViewModel, onBack: () -> Unit, onOpenQueue: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.screener.collectAsState()

    LaunchedEffect(Unit) { vm.loadScreener() }

    MailScaffold("Screener", onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.size(70.dp).clip(RoundedCornerShape(18.dp)).background(palette.softFill),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Lock, null, tint = palette.primarySolid, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("Unknown sender approval", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Mail from senders you have not approved waits in a queue instead " +
                    "of reaching the inbox. The queue is a real folder, so a mail " +
                    "client sees it too.",
                fontSize = 14.sp, color = palette.textSecondary, lineHeight = 21.sp,
            )

            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.card)
                    .border(1.dp, palette.border, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Screener enabled", fontSize = 15.sp, color = palette.textPrimary)
                    Text(
                        when {
                            !state.loaded -> "Checking with your mailbox…"
                            state.enabled == null -> "Status not reported"
                            else -> if (state.enabled == true) "Unknown senders are held"
                            else "Off — all senders reach the inbox"
                        },
                        fontSize = 12.sp, color = palette.textSecondary,
                    )
                }
                Switch(
                    checked = state.enabled == true,
                    onCheckedChange = { vm.setScreener(it) },
                    enabled = state.loaded && state.enabled != null && !state.busy,
                    colors = SwitchDefaults.colors(checkedTrackColor = palette.primarySolid),
                )
            }

            state.error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 12.sp, color = palette.error)
            }

            Spacer(Modifier.height(18.dp))
            DetailLine("Waiting in the queue",
                state.pending?.toString() ?: "Not loaded", palette)

            Spacer(Modifier.height(18.dp))
            GradientButton("Open the queue", onClick = onOpenQueue)
        }
    }
}
