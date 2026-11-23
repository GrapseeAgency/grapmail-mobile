package com.grapsee.grapmail.ui.screens.mail

import com.grapsee.grapmail.logic.PushSync
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAddAlt1
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.MailAliasDto
import com.grapsee.grapmail.data.MailLabelDto
import com.grapsee.grapmail.data.SessionRefresh
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.logic.AccountPushState
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
internal fun MailScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize().background(palette.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = palette.textPrimary)
            }
            Spacer(Modifier.width(4.dp))
            Text(title, modifier = Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
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
    val state by vm.labelState.collectAsStateWithLifecycle()
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
                OutlineButton("New label", onClick = onCreate, fill = false,
                    trailing = { Icon(Icons.Rounded.Add, null, tint = palette.primarySolid,
                        modifier = Modifier.size(16.dp)) })
            }
            if (state.loading && state.labels.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.primarySolid)
                }
            } else if (state.labels.isEmpty()) {
                // Not “becomes a filter on the inbox”, which is what this said on
                // both platforms. The listing returns no label field per row and
                // takes no label query, so nothing filters by label today; a
                // promise the app cannot keep is the same defect as a control that
                // goes nowhere, and the parity gate cannot see it because both
                // sides agreed on the wrong words.
                EmptyNote("No labels yet",
                    "Create one and it becomes available in the Label sheet.")
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
                                .background(com.grapsee.grapmail.ui.components.labelSwatch(label.color)))
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
    // The server's palette KEY, held as the key rather than as a colour and
    // converted back at the last moment. Prefilled from the label being edited,
    // defaulting to the same value the route defaults to (`slate`, the column's
    // own default) when the label carries something unrecognised.
    var chosen by remember {
        mutableStateOf(editing?.color?.takeIf { it in com.grapsee.grapmail.ui.components.LABEL_COLOUR_KEYS }
            ?: "slate")
    }
    var result by remember { mutableStateOf<String?>(null) }

    var saving by remember { mutableStateOf(false) }

    MailScaffold(if (editing == null) "New label" else "Edit label", onDone) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Text("Name", fontSize = 13.sp, color = palette.textSecondary)
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalPalette.current.cursor),
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
            // Two rows of four: eight swatches on one line does not fit a 360dp
            // phone once the screen's own 40dp of padding is taken off.
            com.grapsee.grapmail.ui.components.LABEL_COLOUR_KEYS.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { key ->
                        val picked = key == chosen
                        val swatch = com.grapsee.grapmail.ui.components.labelSwatch(key)
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(swatch)
                                .border(if (picked) 3.dp else 1.dp,
                                    if (picked) palette.textPrimary else palette.border, CircleShape)
                                .clickable { chosen = key }
                                .testTag("label-colour-$key"),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (picked) Icon(Icons.Rounded.Check, null, tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            result?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, fontSize = 13.sp, color = palette.error)
            }

            Spacer(Modifier.height(24.dp))
            GradientButton(if (editing == null) "Create label" else "Save changes", onClick = {
                if (name.isBlank()) { result = "A label needs a name."; return@GradientButton }
                saving = true
                val done: (Boolean, String) -> Unit = { saved, outcome ->
                    saving = false
                    if (saved) onDone() else result = outcome
                }
                // The key is sent as it stands. It used to be a hex string built
                // from a local ramp, and the route's `normaliseColour` rejects
                // anything that is not one of its eight words — so the request
                // succeeded with 201 while the server stored `slate`, and the
                // colour the user picked was never the colour the label got.
                if (editing == null) vm.createLabel(name.trim(), chosen, done)
                else vm.renameLabel(editing.id, name.trim(), chosen, done)
            }, enabled = name.isNotBlank() && !saving)
        }
    }
}

// ── 77 contacts, 85 contact detail ───────────────────────────────────────────

@Composable
fun ContactsScreen(vm: MailboxViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val palette = LocalPalette.current
    var people by remember { mutableStateOf<List<MailboxViewModel.Person>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    var matches by remember { mutableStateOf<List<MailboxViewModel.Person>?>(null) }
    val needle = query.trim()
    LaunchedEffect(vm) { people = vm.people() }
    LaunchedEffect(needle, people) {
        if (needle.isEmpty()) {
            matches = null
        } else {
            kotlinx.coroutines.delay(120)
            matches = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                people.filter {
                    it.address.contains(needle, ignoreCase = true) ||
                        it.name?.contains(needle, ignoreCase = true) == true
                }
            }
        }
    }
    val shown = if (needle.isEmpty()) people else matches.orEmpty()

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
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalPalette.current.cursor),
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
            if (people.isEmpty()) {
                EmptyNote("No people yet", "Contacts appear here as mail arrives from or to them.")
            } else if (needle.isNotEmpty() && matches == null) {
                EmptyNote("Searching…", "")
            } else if (shown.isEmpty()) {
                EmptyNote("No matching contacts", "Try another name or email address.")
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
            DetailLine("Messages seen", person?.seen?.toString() ?: "Not loaded", palette)
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
    val filesRevision by vm.filesRevision.collectAsStateWithLifecycle()
    LaunchedEffect(vm, filesRevision) { files = vm.files() }

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
                            AttachmentThumb(
                                vm = vm,
                                attachment = entry.attachment,
                                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                                    .background(palette.softFill),
                            ) {
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
 * It reports size, type and where it came from, and saves the bytes. Images are
 * drawn as a thumbnail through the same authenticated fetch the download button
 * uses (fixpack A2, Coil); every other format keeps the type icon rather than a
 * box that would decode to nothing.
 */
@Composable
fun AttachmentPreviewScreen(vm: MailboxViewModel, attachmentId: String, onBack: () -> Unit) {
    val palette = LocalPalette.current
    var entry by remember(attachmentId) { mutableStateOf<MailboxViewModel.FileEntry?>(null) }
    var loaded by remember(attachmentId) { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    // The delete is a two-step: the button asks, the panel confirms, and only
    // then does the route see it. `DELETE /attachments/:id` takes the file out
    // of the message for good -- the body cache is dropped on success, so the
    // screen's own row goes to the honest "no longer listed" state rather
    // than keeping a card for bytes the server has thrown away.
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }

    LaunchedEffect(vm, attachmentId) {
        entry = vm.file(attachmentId)
        loaded = true
    }

    MailScaffold("Attachment", onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            val a = entry?.attachment
            if (a == null || deleted) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(when {
                        !loaded -> "Loading attachment…"
                        deleted -> "This attachment has been removed from the message."
                        else -> "This attachment is no longer listed. Open its message again."
                    },
                        fontSize = 14.sp, color = palette.textSecondary)
                }
                return@Column
            }
            Spacer(Modifier.height(16.dp))
            AttachmentThumb(
                vm = vm,
                attachment = a,
                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(18.dp))
                    .background(palette.softFill),
            ) {
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
            }, enabled = !saving && !opening && !deleting)
            // The other half of the affordance: saving to a path nobody can
            // reach from inside the app is only useful if the person can then
            // open the file. Handing it to a viewer is the point of an
            // attachment.
            Spacer(Modifier.height(12.dp))
            OutlineButton(if (opening) "Opening…" else "Open in another app",
                onClick = {
                    if (!opening && !saving && !deleting) {
                        opening = true
                        vm.openAttachment(a) { r -> opening = false; outcome = r }
                    }
                },
                modifier = Modifier.testTag("mail-attachment-open"),
            )
            Spacer(Modifier.height(12.dp))
            if (!confirmDelete) {
                Text("Remove from this message", fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, color = palette.error,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .border(1.dp, palette.error, RoundedCornerShape(24.dp))
                        .clickable(enabled = !deleting && !deleted) { confirmDelete = true }
                        .padding(vertical = 12.dp).testTag("mail-attachment-delete"),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(palette.warnChipBg).padding(16.dp)
                    .testTag("mail-attachment-confirm")) {
                    Text("Remove ${a.name ?: "this file"} from the message? The message keeps " +
                        "everything else; this file is gone from it for good.",
                        fontSize = 13.sp, color = palette.textPrimary)
                    Row(Modifier.padding(top = 12.dp)) {
                        Text("Cancel", fontSize = 14.sp, color = palette.textSecondary,
                            modifier = Modifier.clickable { confirmDelete = false })
                        Spacer(Modifier.width(24.dp))
                        Text(if (deleting) "Removing…" else "Remove", fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, color = palette.error,
                            modifier = Modifier.clickable(enabled = !deleting) {
                                deleting = true
                                vm.deleteAttachment(a.id) { removed, message ->
                                    deleting = false; confirmDelete = false
                                    deleted = removed
                                    outcome = message
                                }
                            })
                    }
                }
            }
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
    onNotes: () -> Unit,
    onTasks: () -> Unit,
    onPrefs: () -> Unit,
    onAddresses: () -> Unit,
    onConnections: () -> Unit,
    onSubscriptions: () -> Unit,
    onProfile: () -> Unit,
    onPrivacy: () -> Unit,
    onEncryption: () -> Unit,
    onNotificationPrefs: () -> Unit,
    onPerAccountNotifications: () -> Unit,
    onHelp: () -> Unit,
    onSignOut: () -> Unit,
    onPlan: () -> Unit = {},
) {
    val palette = LocalPalette.current
    // The alias list is a read with a state, not a value that appears: `GET
    // /aliases` answers three columns and nothing else, so the screen says
    // "Checking…" while it is outstanding and names the failure when it is not.
    // A locally cached list rendered as though it were the server's answer would
    // show a create or a delete that had not happened.
    val aliases by vm.aliasesState.collectAsStateWithLifecycle()
    val accountFields = remember(vm) {
        vm.state.map { Triple(it.accountAddress, it.accountStatus, it.memberSince) }
            .distinctUntilChanged()
    }
    val account by accountFields.collectAsStateWithLifecycle(
        initialValue = vm.state.value.let { Triple(it.accountAddress, it.accountStatus, it.memberSince) },
    )
    LaunchedEffect(Unit) { vm.loadAliases() }

    MailScaffold("Settings", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionLabel("Account", palette)
            SettingRow(Icons.Rounded.MailOutline, "GrapMail address", account.first ?: "Not loaded", palette)
            SettingRow(Icons.Rounded.Star, "Mailbox status",
                account.second ?: "Not reported", palette)
            SettingRow(Icons.Rounded.Lock, "Member since",
                account.third ?: "Not reported", palette)

            SectionLabel("Sending as", palette)
            // Three answers, not two: while the read is outstanding the screen
            // must not claim the mailbox has no aliases — "nobody answered yet"
            // and "there are none" are different sentences (the same rule the
            // Addresses screen's null-vs-empty split enforces).
            when {
                aliases.read == com.grapsee.grapmail.data.AssignmentsRead.Reading ||
                    aliases.read == com.grapsee.grapmail.data.AssignmentsRead.NotRead ->
                    Text("Checking…", fontSize = 13.sp, color = palette.textTertiary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                aliases.read == com.grapsee.grapmail.data.AssignmentsRead.Failed && aliases.list.isEmpty() ->
                    Text(aliases.error ?: "The alias list did not load.",
                        fontSize = 13.sp, color = palette.textSecondary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                aliases.list.isEmpty() ->
                    Text("No aliases on this mailbox. An alias is a second address " +
                        "that delivers into the same inbox.",
                        fontSize = 13.sp, color = palette.textSecondary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                else -> aliases.list.forEach { a ->
                    // No "paused" chip here: `GET /aliases` answers three
                    // columns and none of them is a state (`grapmail-aliases
                    // .ts:131-152`), so this list can only say what the
                    // mailbox receives as. The Addresses screen shows paused —
                    // the profile's alias rows DO answer `isActive`.
                    SettingRow(Icons.Rounded.PersonAddAlt1, a.label, a.address, palette)
                }
            }

            SectionLabel("Mailbox", palette)
            SettingRow(Icons.Rounded.Shield, "Account security",
                "Password, two-step, sessions", palette, onClick = onSecurity)
            SettingRow(Icons.Rounded.Lock, "Screener",
                "Approval for unknown senders", palette, onClick = onScreener)
            SettingRow(Icons.Rounded.Settings, "Labels",
                "Filters for the inbox", palette, onClick = onLabels)
            SettingRow(Icons.Rounded.MailOutline, "Mail settings",
                "Signature, auto-reply, forwarding, dates, senders", palette, onClick = onPrefs)
            SettingRow(Icons.Rounded.Star, "Your plan",
                "Monthly sending allowance and upgrades", palette, onClick = onPlan)
            SettingRow(Icons.Rounded.PersonAddAlt1, "Addresses",
                "Primary and aliases", palette, onClick = onAddresses)
            SettingRow(Icons.Rounded.Person, "Connected accounts",
                "Apps that can use this sign-in", palette, onClick = onConnections)
            SettingRow(Icons.Rounded.MailOutline, "Subscriptions",
                "Senders that mail you most", palette, onClick = onSubscriptions)
            SettingRow(Icons.Rounded.Star, "Notes",
                "Kept on the account", palette, onClick = onNotes)
            SettingRow(Icons.Rounded.Check, "Tasks",
                "The list beside the mailbox", palette, onClick = onTasks)

            SectionLabel("This account", palette)
            // New-mail alerts have two wake paths: FCM for the immediate signal and
            // WorkManager polling as the reliability fallback. Both converge on the
            // same authenticated `GET /stats` read before the app claims a count;
            // the push payload itself carries no message content. The description
            // therefore says only the durable product fact — GrapMail checks for
            // mail in the background — rather than promising one transport always
            // arrives first.
            //
            // The row reports the device's real answer rather than a remembered
            // one, and the ask is here on a tap, not at launch. Android 13 made
            // it a runtime permission: without this row the poll worker could
            // run its read correctly forever and never be allowed to say so,
            // which is a feature that exists only in the source file.
            val notifyContext = androidx.compose.ui.platform.LocalContext.current
            var notifyAllowed by androidx.compose.runtime.remember {
                mutableStateOf(com.grapsee.grapmail.logic.Notifications.allowed(notifyContext))
            }
            val notificationLifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(notificationLifecycle, notifyContext) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        notifyAllowed = com.grapsee.grapmail.logic.Notifications.allowed(notifyContext)
                    }
                }
                notificationLifecycle.addObserver(observer)
                onDispose { notificationLifecycle.removeObserver(observer) }
            }
            val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) {
                notifyAllowed = com.grapsee.grapmail.logic.Notifications.allowed(notifyContext)
                if (notifyAllowed) {
                    com.grapsee.grapmail.logic.PushSync.registerAsync(notifyContext.applicationContext)
                }
            }
            SettingRow(Icons.Rounded.NotificationsActive, "New mail notifications",
                if (notifyAllowed) "On — GrapMail checks for new mail in the background"
                else "Off — tap to allow new-mail alerts",
                palette,
                onClick = {
                    val needsPermission = android.os.Build.VERSION.SDK_INT >= 33 &&
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            notifyContext, android.Manifest.permission.POST_NOTIFICATIONS,
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!needsPermission) {
                        val open = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, notifyContext.packageName)
                        runCatching { notifyContext.startActivity(open) }
                    } else {
                        askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                })
            var hideMailPreviews by remember {
                mutableStateOf(PushSync.genericPreviews(notifyContext))
            }
            SettingRow(Icons.Rounded.Lock, "Mail notification previews",
                if (hideMailPreviews) "Hidden on this device" else "Show sender and subject",
                palette,
                onClick = {
                    hideMailPreviews = !hideMailPreviews
                    PushSync.setGenericPreviews(notifyContext, hideMailPreviews)
                })
            // The account-level rows: what the server sends for this account
            // (mail, security, account scopes) and its quiet-hours window —
            // per-account state, unlike the two rows above, which are per-device.
            SettingRow(Icons.Rounded.NotificationsActive, "Notification preferences",
                "Mail, security and account alerts, plus quiet hours",
                palette, onClick = onNotificationPrefs)
            // The per-account mute: which signed-in account may alert at all
            // on this handset. The row above shapes the alerts one account
            // sends; this one is the off switch per account.
            SettingRow(Icons.Rounded.NotificationsOff, "Per-account notifications",
                "Silence one signed-in account on this device",
                palette, onClick = onPerAccountNotifications)
            // Client-side filtering (priority only, VIP senders, lock-screen
            // previews) — a different layer from the server's per-scope alerts.
            var showNotificationFilters by remember { mutableStateOf(false) }
            SettingRow(Icons.Rounded.NotificationsActive, "Notification filters",
                "Priority only, VIP senders, quiet hours, lock-screen previews",
                palette, onClick = { showNotificationFilters = true })
            if (showNotificationFilters) NotificationFiltersDialog(onDismiss = { showNotificationFilters = false })
            // The app lock is a preference on this handset, not an account
            // setting: it guards this device's copy of the mailbox. A device
            // that cannot keep the promise says so instead of offering a lock
            // it cannot hold.
            var appLockOn by remember {
                mutableStateOf(com.grapsee.grapmail.logic.AppLock.isEnabled(notifyContext))
            }
            val appLockUsable = remember {
                com.grapsee.grapmail.logic.AppLock.available(notifyContext)
            }
            // Both directions go through the platform prompt. Turning the lock
            // off without it would let anyone holding an unlocked phone remove
            // the one thing standing between them and the mailbox; turning it on
            // without it could lock out a person whose fingerprint is not enrolled
            // the way they think it is.
            val lockActivity = remember(notifyContext) {
                generateSequence(notifyContext) { (it as? android.content.ContextWrapper)?.baseContext }
                    .filterIsInstance<androidx.fragment.app.FragmentActivity>()
                    .firstOrNull()
            }
            val toggleAppLock: () -> Unit = {
                val want = !appLockOn
                val activity = lockActivity
                if (activity != null) {
                    com.grapsee.grapmail.logic.AppLock.prompt(activity) { verified ->
                        if (verified) {
                            appLockOn = want
                            com.grapsee.grapmail.logic.AppLock.setEnabled(notifyContext, want)
                        }
                    }
                }
            }
            SettingRow(
                Icons.Rounded.Lock, "App lock",
                when {
                    !appLockUsable -> "Not available on this device"
                    appLockOn -> "On — asks for your fingerprint or face when opened"
                    else -> "Require unlock when GrapMail is opened"
                },
                palette,
                onClick = toggleAppLock.takeIf { appLockUsable },
            )
            // Crash and hang reports to GrapMail's own server (logic/CrashReporter.kt):
            // app version, device model and the stack trace, never the account or mail.
            var crashReports by remember { mutableStateOf(com.grapsee.grapmail.logic.CrashReporter.enabled(notifyContext)) }
            SettingRow(
                Icons.Rounded.Shield, "Send crash reports",
                if (crashReports) "On — app version, device and the error, never your mail"
                else "Off — crashes are not reported",
                palette,
                onClick = {
                    crashReports = !crashReports
                    com.grapsee.grapmail.logic.CrashReporter.setEnabled(notifyContext, crashReports)
                },
            )
            // Android 13's per-app language: the system owns the picker; this
            // row opens its page for this app. The languages it lists are what
            // the app actually ships copy for — res/xml/locales_config.xml.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                SettingRow(
                    Icons.Rounded.Language, "App language",
                    "Opens the system's page for this app's language",
                    palette,
                    onClick = {
                        val open = android.content.Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS)
                            .setData(android.net.Uri.parse("package:${notifyContext.packageName}"))
                        runCatching { notifyContext.startActivity(open) }
                    },
                )
            }
            SettingRow(Icons.Rounded.Person, "Personal info",
                "Name, birthday, photo", palette, onClick = onProfile)
            SettingRow(Icons.Rounded.Shield, "Data & privacy",
                "What is kept, what you control", palette, onClick = onPrivacy)
            SettingRow(Icons.Rounded.Lock, "Encryption",
                "Your end-to-end key, on this device", palette, onClick = onEncryption)

            com.grapsee.grapmail.ui.mailfeatures.MailFeatureSettingsSection()

            SectionLabel("Support", palette)
            SettingRow(Icons.Rounded.HelpOutline, "Help & Support",
                "Questions this product actually raises", palette, onClick = onHelp)

            Spacer(Modifier.height(20.dp))
            Text("Sign out", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.error,
                modifier = Modifier.padding(horizontal = 20.dp).clickable(onClick = onSignOut)
                    .testTag("mail-sign-out"))
            Spacer(Modifier.height(30.dp))
        }
    }
}

// ── 80a per-account notifications ────────────────────────────────────────────

/**
 * One mute per account, on this device. The register route mints a device row
 * per signed-in account and answers its id; the PATCH on that row is what
 * silences the account — and it must travel with the muted account's own
 * bearer, which is why the toggle refreshes that account's session instead of
 * reusing the mailbox's active one. The switch is optimistic and reverts with
 * the reason on a refused write, the pattern the screener's switch follows.
 * The device-wide previews toggle in the settings stays untouched: this
 * screen changes who hears, not what the preview shows.
 */
@Composable
fun PerAccountNotificationsScreen(onBack: () -> Unit) {
    val palette = LocalPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<AccountPushState>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busyEmail by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        try {
            rows = PushSync.accountPushState(context)
            failed = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            rows = null
            failed = true
        }
    }

    LaunchedEffect(Unit) { load() }

    fun setMuted(row: AccountPushState, muted: Boolean) {
        val deviceRowId = row.deviceRowId ?: return
        val previous = rows
        // Optimistic: the switch answers at once and reverts with the reason
        // if the server refuses the write.
        rows = rows?.map { if (it.email == row.email) it.copy(muted = muted) else it }
        scope.launch {
            busyEmail = row.email
            error = null
            try {
                val stored = SessionStore.snapshotAll(context)
                    .firstOrNull { it.address.equals(row.email, ignoreCase = true) }
                    ?: throw IllegalStateException("Sign in again.")
                val session = SessionRefresh.forAccount(context, stored.id)
                    ?: throw IllegalStateException("Sign in again.")
                val answer = Api.setAccountPushPreferences(
                    session.token, deviceRowId,
                    enabled = !muted,
                    preview = row.preview,
                )
                val confirmed = answer.enabled?.let { !it } ?: muted
                PushSync.setMuted(context, row.email, confirmed)
                rows = rows?.map { if (it.email == row.email) it.copy(muted = confirmed) else it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                rows = previous
                error = e.message ?: "The change did not save. Try again."
            } finally {
                busyEmail = null
            }
        }
    }

    MailScaffold("Per-account notifications", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionLabel("Signed-in accounts", palette)
            val current = rows
            when {
                current == null && !failed ->
                    Text("Checking…", fontSize = 13.sp, color = palette.textTertiary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                current == null -> {
                    Text("The account list did not load.", fontSize = 13.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    androidx.compose.material3.TextButton(onClick = { scope.launch { load() } }) {
                        Text("Retry")
                    }
                }
                current.isEmpty() ->
                    Text("No accounts are signed in on this device.", fontSize = 13.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                else -> current.forEach { row ->
                    val busy = busyEmail == row.email
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Person, null, tint = palette.textSecondary,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.email, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                color = palette.textPrimary)
                            Text(
                                when {
                                    row.deviceRowId == null -> "Not registered for push on this device"
                                    row.muted -> "Muted — this account's alerts are silenced"
                                    else -> "Alerts arrive normally"
                                },
                                fontSize = 13.sp, color = palette.textSecondary,
                            )
                        }
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Switch(
                                checked = row.muted,
                                onCheckedChange = { setMuted(row, it) },
                                enabled = row.deviceRowId != null,
                                colors = SwitchDefaults.colors(checkedTrackColor = palette.primarySolid),
                            )
                        }
                    }
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 12.sp, color = palette.error,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
            Spacer(Modifier.height(14.dp))
            NoteBar("Silencing one account stops its mail alerts on this device. " +
                "Mandatory security alerts still arrive — the server keeps those on for every account.")
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
internal fun SectionLabel(text: String, palette: com.grapsee.grapmail.ui.theme.Palette) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        color = palette.textSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
}

@Composable
internal fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    palette: com.grapsee.grapmail.ui.theme.Palette,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = palette.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.textPrimary)
            Text(subtitle, fontSize = 13.sp, color = palette.textSecondary)
            trailing?.let { Text(it, fontSize = 12.sp, color = palette.warnInk) }
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = palette.textSecondary, modifier = Modifier.size(18.dp))
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
    val state by vm.screener.collectAsStateWithLifecycle()

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
                if (!state.busy) androidx.compose.material3.TextButton(onClick = vm::loadScreener) {
                    Text("Retry")
                }
            }

            Spacer(Modifier.height(18.dp))
            DetailLine("Waiting in the queue",
                state.pending?.toString() ?: "Not loaded", palette)

            Spacer(Modifier.height(18.dp))
            GradientButton("Open the queue", onClick = onOpenQueue)
        }
    }
}
