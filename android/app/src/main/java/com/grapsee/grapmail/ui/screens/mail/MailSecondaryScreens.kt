package com.grapsee.grapmail.ui.screens.mail

import androidx.compose.foundation.layout.offset
import com.grapsee.grapmail.ui.components.gmPrefersStackedRows
import com.grapsee.grapmail.ui.components.gmMaxWidth
import com.grapsee.grapmail.ui.components.GMLayout
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Label
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
import androidx.compose.material.icons.rounded.Palette
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
import androidx.compose.runtime.collectAsState
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
import com.grapsee.grapmail.ui.components.EmptyState
import com.grapsee.grapmail.ui.components.ErrorState
import com.grapsee.grapmail.ui.components.ListSkeleton
import com.grapsee.grapmail.ui.components.OfflineBanner
import com.grapsee.grapmail.ui.theme.LocalPalette
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.grapsee.grapmail.R
import com.grapsee.grapmail.ui.i18n.CoreStrings

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

/**
 * The secondary screens' frame. [maxContentWidth] caps the content and centres
 * it on wide windows (tablets, unfolded foldables, desktop / freeform windows);
 * on a phone in portrait it changes nothing. Lists keep the readable width,
 * forms and settings pass [GMLayout.Form]. The content area also sits above
 * the keyboard (the root pads only the system bars and cutout), so a field and
 * its button stay reachable while typing.
 */
@Composable
internal fun MailScaffold(
    title: String,
    onBack: () -> Unit,
    maxContentWidth: Dp = GMLayout.Readable,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize().background(palette.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.mail_common_back), tint = palette.textPrimary)
            }
            Spacer(Modifier.width(4.dp))
            Text(title, modifier = Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
        }
        // Every secondary screen says when the device is offline, over whatever
        // it is still showing (ScreenStates.kt). Draws nothing while online.
        OfflineBanner()
        Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            Box(Modifier.gmMaxWidth(maxContentWidth).fillMaxHeight()) { content() }
        }
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
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, fontSize = 13.sp, color = palette.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
    val largeText = gmPrefersStackedRows()

    LaunchedEffect(Unit) { vm.refreshLabels() }

    MailScaffold(stringResource(R.string.mail_labels_labels), onBack) {
        Column(Modifier.fillMaxSize()) {
            NoteBar(stringResource(R.string.mail_labels_labels_real_filter_references))
            notice?.let {
                Text(it, fontSize = 12.sp, color = palette.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            // At large text the count and the button share no line: the count
            // sits above a full-width button instead of being squeezed to a
            // column of single words.
            if (largeText) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pluralStringResource(R.plurals.mail_labels_count, state.labels.size, state.labels.size),
                        fontSize = 13.sp, color = palette.textSecondary)
                    OutlineButton(stringResource(R.string.mail_labels_new_label), onClick = onCreate,
                        trailing = { Icon(Icons.Rounded.Add, null, tint = palette.primarySolid,
                            modifier = Modifier.size(16.dp)) })
                }
            } else Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.mail_labels_count, state.labels.size, state.labels.size),
                    fontSize = 13.sp, color = palette.textSecondary, modifier = Modifier.weight(1f))
                OutlineButton(stringResource(R.string.mail_labels_new_label), onClick = onCreate, fill = false,
                    trailing = { Icon(Icons.Rounded.Add, null, tint = palette.primarySolid,
                        modifier = Modifier.size(16.dp)) })
            }
            // A refresh that failed over labels already shown keeps them and
            // says so; only a failure with nothing to show is the error state.
            if (state.error != null && state.labels.isNotEmpty()) NoteBar(state.error!!)
            if (state.loading && state.labels.isEmpty()) {
                ListSkeleton(Modifier.fillMaxSize(), rows = 5, avatar = false,
                    label = stringResource(R.string.states_labels_loading))
            } else if (state.labels.isEmpty() && state.error != null) {
                ErrorState(onRetry = { vm.refreshLabels(force = true) },
                    title = stringResource(R.string.states_labels_failed_title),
                    message = state.error)
            } else if (state.labels.isEmpty()) {
                // Not “becomes a filter on the inbox”, which is what this said on
                // both platforms. The listing returns no label field per row and
                // takes no label query, so nothing filters by label today; a
                // promise the app cannot keep is the same defect as a control that
                // goes nowhere, and the parity gate cannot see it because both
                // sides agreed on the wrong words.
                EmptyState(Icons.AutoMirrored.Rounded.Label, stringResource(R.string.mail_labels_no_labels_yet),
                    body = stringResource(R.string.mail_labels_create_one_becomes_available),
                    actionLabel = stringResource(R.string.mail_labels_new_label), onAction = onCreate)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.labels, key = { it.id }) { label ->
                        // The row is the edit affordance. It already looked
                        // tappable and did nothing, which is the one thing this
                        // sector was told not to ship — renaming is what tapping
                        // a label's own row should mean, and now it is.
                        Row(
                            Modifier.fillMaxWidth().clickable { onEdit(label) }
                                .padding(start = 16.dp, end = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(12.dp).clip(CircleShape)
                                .background(com.grapsee.grapmail.ui.components.labelSwatch(label.color)))
                            Spacer(Modifier.width(12.dp))
                            Text(label.name, fontSize = 15.sp, color = palette.textPrimary,
                                maxLines = if (largeText) 3 else 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                            CountBadge(label.unread)
                            // The delete glyph stays 18dp; its touch target is the full 48dp.
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).clickable(
                                    role = androidx.compose.ui.semantics.Role.Button,
                                ) {
                                    vm.deleteLabel(label.id) { notice = it }
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Delete, stringResource(R.string.mail_labels_remove_label, label.name),
                                    tint = palette.textTertiary, modifier = Modifier.size(18.dp))
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

    MailScaffold(if (editing == null) stringResource(R.string.mail_labels_new_label) else stringResource(R.string.mail_labels_edit_label), onDone,
        maxContentWidth = GMLayout.Form) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.mail_labels_name), fontSize = 13.sp, color = palette.textSecondary)
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
                        if (name.isEmpty()) Text(stringResource(R.string.mail_labels_work_travel_receipts),
                            fontSize = 16.sp, color = palette.textTertiary)
                        inner()
                    }
                },
            )

            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.mail_labels_colour), fontSize = 13.sp, color = palette.textSecondary)
            Spacer(Modifier.height(3.dp))
            // Two rows of four: eight swatches on one line does not fit a 360dp
            // phone once the screen's own 40dp of padding is taken off.
            // Each 34dp swatch sits in a 48dp touch target; the -7dp offset and
            // the zero gap keep the swatches where the 12dp gap put them.
            com.grapsee.grapmail.ui.components.LABEL_COLOUR_KEYS.chunked(4).forEach { row ->
                Row(Modifier.offset(x = (-7).dp)) {
                    row.forEach { key ->
                        val picked = key == chosen
                        val swatch = com.grapsee.grapmail.ui.components.labelSwatch(key)
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { chosen = key }
                                .testTag("label-colour-$key"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(swatch)
                                    .border(if (picked) 3.dp else 1.dp,
                                        if (picked) palette.textPrimary else palette.border, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (picked) Icon(Icons.Rounded.Check, null, tint = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(5.dp))

            result?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, fontSize = 13.sp, color = palette.error)
            }

            Spacer(Modifier.height(24.dp))
            GradientButton(if (editing == null) stringResource(R.string.mail_labels_create_label) else stringResource(R.string.mail_labels_save_changes), onClick = {
                if (name.isBlank()) { result = CoreStrings.get(R.string.mail_labels_label_needs_name) { "A label needs a name." }; return@GradientButton }
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
            Spacer(Modifier.height(16.dp))
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
    // Until the first read of the device's mail settles, "No people yet" would
    // be a claim nobody checked; the skeleton holds the place instead.
    var peopleLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { people = vm.people(); peopleLoaded = true }
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
    val largeText = gmPrefersStackedRows()

    MailScaffold(stringResource(R.string.mail_contacts_contacts), onBack) {
        Column(Modifier.fillMaxSize()) {
            NoteBar(stringResource(R.string.mail_contacts_people_read_out_mail))
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
                            if (query.isEmpty()) Text(stringResource(R.string.mail_contacts_search_people), fontSize = 14.sp,
                                color = palette.textTertiary)
                            inner()
                        }
                    },
                )
            }
            if (!peopleLoaded) {
                ListSkeleton(Modifier.fillMaxSize(), rows = 6,
                    label = stringResource(R.string.states_contacts_loading))
            } else if (people.isEmpty()) {
                EmptyState(Icons.Rounded.Contacts, stringResource(R.string.mail_contacts_no_people_yet),
                    body = stringResource(R.string.mail_contacts_contacts_appear_here_mail))
            } else if (needle.isNotEmpty() && matches == null) {
                EmptyNote(stringResource(R.string.mail_contacts_searching), "")
            } else if (shown.isEmpty()) {
                EmptyState(Icons.Rounded.Contacts, stringResource(R.string.mail_contacts_no_matching_contacts),
                    body = stringResource(R.string.mail_contacts_try_another_name_email))
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
                                    maxLines = if (largeText) 3 else 1, overflow = TextOverflow.Ellipsis)
                                if (!p.name.isNullOrBlank()) {
                                    Text(p.address, fontSize = 12.sp, color = palette.textSecondary,
                                        maxLines = if (largeText) 3 else 1, overflow = TextOverflow.Ellipsis)
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

    MailScaffold(stringResource(R.string.mail_contacts_contact), onBack, maxContentWidth = GMLayout.Form) {
        // Scrolls: a landscape phone or 200% text would otherwise push Write
        // below the window with no way to reach it.
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            SenderAvatar(person?.name ?: "", address, size = 72.dp)
            Spacer(Modifier.height(14.dp))
            Text(person?.name ?: address, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                color = palette.textPrimary)
            Text(address, fontSize = 14.sp, color = palette.textSecondary)
            Spacer(Modifier.height(18.dp))
            DetailLine(stringResource(R.string.mail_contacts_messages_seen), person?.seen?.toString() ?: stringResource(R.string.mail_contacts_not_loaded), palette)
            DetailLine(stringResource(R.string.mail_contacts_build), stringResource(R.string.mail_contacts_derived_from_loaded_mail), palette)
            Spacer(Modifier.height(22.dp))
            GradientButton(stringResource(R.string.mail_contacts_write), onClick = onWrite)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, palette: com.grapsee.grapmail.ui.theme.Palette) {
    // At large text the value goes under its label; side by side, a long
    // value (a subject, a MIME type) used to take the whole row and squeeze
    // the label to nothing.
    if (gmPrefersStackedRows()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 13.sp, color = palette.textSecondary)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.textPrimary)
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = palette.textSecondary, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.textPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1.4f, fill = false))
    }
}

// ── 78 files, 79 attachment preview ──────────────────────────────────────────

@Composable
fun FilesScreen(vm: MailboxViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val palette = LocalPalette.current
    var files by remember { mutableStateOf<List<MailboxViewModel.FileEntry>>(emptyList()) }
    val filesRevision by vm.filesRevision.collectAsStateWithLifecycle()
    var filesLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(vm, filesRevision) { files = vm.files(); filesLoaded = true }
    val largeText = gmPrefersStackedRows()

    MailScaffold(stringResource(R.string.mail_files_files), onBack) {
        Column(Modifier.fillMaxSize()) {
            NoteBar(stringResource(R.string.mail_files_lists_attachments_messages_have))
            if (!filesLoaded) {
                ListSkeleton(Modifier.fillMaxSize(), rows = 5,
                    label = stringResource(R.string.states_files_loading))
            } else if (files.isEmpty()) {
                EmptyState(Icons.Rounded.AttachFile, stringResource(R.string.mail_files_no_files_yet),
                    body = stringResource(R.string.mail_files_open_message_attachment_appears))
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
                                Text(entry.attachment.name ?: stringResource(R.string.mail_files_attachment), fontSize = 15.sp,
                                    color = palette.textPrimary, maxLines = if (largeText) 3 else 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        entry.attachment.sizeBytes?.let { humanBytes(it) },
                                        entry.fromMessage?.let { stringResource(R.string.mail_files_in, it) },
                                    ).joinToString(" · ").ifBlank { stringResource(R.string.mail_files_size_not_recorded) },
                                    fontSize = 12.sp, color = palette.textSecondary,
                                    maxLines = if (largeText) 3 else 1, overflow = TextOverflow.Ellipsis,
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

    MailScaffold(stringResource(R.string.mail_attach_attachment), onBack, maxContentWidth = GMLayout.Form) {
        // Scrolls, so Save / Open / Remove stay reachable in a landscape or
        // split-screen window and at 200% text. The viewport height keeps the
        // loading / removed note centred as it was.
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            val a = entry?.attachment
            if (a == null || deleted) {
                Box(Modifier.fillMaxWidth().height(viewport), contentAlignment = Alignment.Center) {
                    Text(when {
                        !loaded -> stringResource(R.string.mail_attach_loading_attachment)
                        deleted -> stringResource(R.string.mail_attach_attachment_has_been_removed)
                        else -> stringResource(R.string.mail_attach_attachment_no_longer_listed)
                    },
                        fontSize = 14.sp, color = palette.textSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
            Text(a.name ?: stringResource(R.string.mail_attach_attachment), fontSize = 19.sp, fontWeight = FontWeight.Bold,
                color = palette.textPrimary)
            Spacer(Modifier.height(10.dp))
            DetailLine(stringResource(R.string.mail_attach_type), a.mimeType?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mail_attach_not_recorded), palette)
            DetailLine(stringResource(R.string.mail_attach_size), a.sizeBytes?.let { humanBytes(it) } ?: stringResource(R.string.mail_attach_not_recorded), palette)
            entry?.fromMessage?.let { DetailLine(stringResource(R.string.mail_attach_from_message), it, palette) }

            outcome?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, fontSize = 12.sp, color = palette.textSecondary,
                    maxLines = if (gmPrefersStackedRows()) 8 else 3,
                    overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.height(22.dp))
            GradientButton(if (saving) stringResource(R.string.mail_common_saving) else stringResource(R.string.mail_attach_save_device), onClick = {
                saving = true
                vm.downloadAttachment(a) { r -> saving = false; outcome = r }
            }, enabled = !saving && !opening && !deleting)
            // The other half of the affordance: saving to a path nobody can
            // reach from inside the app is only useful if the person can then
            // open the file. Handing it to a viewer is the point of an
            // attachment.
            Spacer(Modifier.height(12.dp))
            OutlineButton(if (opening) stringResource(R.string.mail_attach_opening) else stringResource(R.string.mail_attach_open_another_app),
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
                Text(stringResource(R.string.mail_attach_remove_from_message), fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, color = palette.error,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .border(1.dp, palette.error, RoundedCornerShape(24.dp))
                        .clickable(enabled = !deleting && !deleted) { confirmDelete = true }
                        .padding(vertical = 14.dp).testTag("mail-attachment-delete"),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(palette.warnChipBg).padding(16.dp)
                    .testTag("mail-attachment-confirm")) {
                    Text(stringResource(R.string.mail_attach_remove_confirm, a.name ?: stringResource(R.string.mail_attach_this_file)),
                        fontSize = 13.sp, color = palette.textPrimary)
                    // 48dp targets; the top padding shrinks by the target's own
                    // slack so the pair sits where it did.
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.mail_common_cancel), fontSize = 14.sp, color = palette.textSecondary,
                            modifier = Modifier.minimumInteractiveComponentSize().clickable { confirmDelete = false })
                        Spacer(Modifier.width(24.dp))
                        Text(if (deleting) stringResource(R.string.mail_attach_removing) else stringResource(R.string.mail_common_remove), fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, color = palette.error,
                            modifier = Modifier.minimumInteractiveComponentSize().clickable(enabled = !deleting) {
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
            Spacer(Modifier.height(16.dp))
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
    onProviders: () -> Unit = {},
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

    MailScaffold(stringResource(R.string.mail_settings_settings), onBack, maxContentWidth = GMLayout.Form) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionLabel(stringResource(R.string.mail_settings_account), palette)
            SettingRow(Icons.Rounded.MailOutline, stringResource(R.string.mail_settings_grapmail_address), account.first ?: stringResource(R.string.mail_settings_not_loaded), palette)
            SettingRow(Icons.Rounded.Star, stringResource(R.string.mail_settings_mailbox_status),
                account.second ?: stringResource(R.string.mail_settings_not_reported), palette)
            SettingRow(Icons.Rounded.Lock, stringResource(R.string.mail_settings_member_since),
                account.third ?: stringResource(R.string.mail_settings_not_reported), palette)

            SectionLabel(stringResource(R.string.mail_settings_sending), palette)
            // Three answers, not two: while the read is outstanding the screen
            // must not claim the mailbox has no aliases — "nobody answered yet"
            // and "there are none" are different sentences (the same rule the
            // Addresses screen's null-vs-empty split enforces).
            when {
                aliases.read == com.grapsee.grapmail.data.AssignmentsRead.Reading ||
                    aliases.read == com.grapsee.grapmail.data.AssignmentsRead.NotRead ->
                    Text(stringResource(R.string.mail_settings_checking), fontSize = 13.sp, color = palette.textTertiary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                aliases.read == com.grapsee.grapmail.data.AssignmentsRead.Failed && aliases.list.isEmpty() ->
                    Text(aliases.error ?: stringResource(R.string.mail_settings_alias_list_did_not),
                        fontSize = 13.sp, color = palette.textSecondary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                aliases.list.isEmpty() ->
                    Text(stringResource(R.string.mail_settings_no_aliases_mailbox_alias),
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

            SectionLabel(stringResource(R.string.mail_settings_mailbox), palette)
            SettingRow(Icons.Rounded.Shield, stringResource(R.string.mail_settings_account_security),
                stringResource(R.string.mail_settings_password_two_step_sessions), palette, onClick = onSecurity)
            SettingRow(Icons.Rounded.Lock, stringResource(R.string.mail_settings_screener),
                stringResource(R.string.mail_settings_approval_unknown_senders), palette, onClick = onScreener)
            SettingRow(Icons.Rounded.Settings, stringResource(R.string.mail_settings_labels),
                stringResource(R.string.mail_settings_filters_inbox), palette, onClick = onLabels)
            SettingRow(Icons.Rounded.MailOutline, stringResource(R.string.mail_settings_mail_settings),
                stringResource(R.string.mail_settings_signature_auto_reply_forwarding), palette, onClick = onPrefs)
            SettingRow(Icons.Rounded.Star, stringResource(R.string.mail_settings_plan),
                stringResource(R.string.mail_settings_monthly_sending_allowance_upgrades), palette, onClick = onPlan)
            SettingRow(Icons.Rounded.PersonAddAlt1, stringResource(R.string.mail_settings_addresses),
                stringResource(R.string.mail_settings_primary_aliases), palette, onClick = onAddresses)
            SettingRow(Icons.Rounded.Person, stringResource(R.string.mail_settings_connected_accounts),
                stringResource(R.string.mail_settings_apps_can_use_sign), palette, onClick = onConnections)
            SettingRow(Icons.Rounded.MailOutline, stringResource(R.string.mail_providers_title),
                stringResource(R.string.mail_providers_settings_subtitle), palette, onClick = onProviders)
            SettingRow(Icons.Rounded.MailOutline, stringResource(R.string.mail_settings_subscriptions),
                stringResource(R.string.mail_settings_senders_mail_most), palette, onClick = onSubscriptions)
            var showImport by remember { mutableStateOf(false) }
            SettingRow(Icons.Rounded.MailOutline, stringResource(R.string.mail_settings_import_mail),
                stringResource(R.string.mail_settings_from_gmail_yahoo_icloud), palette, onClick = { showImport = true })
            if (showImport) ImportMailDialog(onDismiss = { showImport = false })
            SettingRow(Icons.Rounded.Star, stringResource(R.string.mail_settings_notes),
                stringResource(R.string.mail_settings_kept_account), palette, onClick = onNotes)
            SettingRow(Icons.Rounded.Check, stringResource(R.string.mail_settings_tasks),
                stringResource(R.string.mail_settings_list_beside_mailbox), palette, onClick = onTasks)

            SectionLabel(stringResource(R.string.mail_settings_this_account), palette)
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
            SettingRow(Icons.Rounded.NotificationsActive, stringResource(R.string.mail_settings_new_mail_notifications),
                if (notifyAllowed) stringResource(R.string.mail_settings_grapmail_checks_new_mail)
                else stringResource(R.string.mail_settings_off_tap_allow_new),
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
            SettingRow(Icons.Rounded.Lock, stringResource(R.string.mail_settings_mail_notification_previews),
                if (hideMailPreviews) stringResource(R.string.mail_settings_hidden_device) else stringResource(R.string.mail_settings_show_sender_subject),
                palette,
                onClick = {
                    hideMailPreviews = !hideMailPreviews
                    PushSync.setGenericPreviews(notifyContext, hideMailPreviews)
                })
            // The account-level rows: what the server sends for this account
            // (mail, security, account scopes) and its quiet-hours window —
            // per-account state, unlike the two rows above, which are per-device.
            SettingRow(Icons.Rounded.NotificationsActive, stringResource(R.string.mail_settings_notification_preferences),
                stringResource(R.string.mail_settings_mail_security_account_alerts),
                palette, onClick = onNotificationPrefs)
            // The per-account mute: which signed-in account may alert at all
            // on this handset. The row above shapes the alerts one account
            // sends; this one is the off switch per account.
            SettingRow(Icons.Rounded.NotificationsOff, stringResource(R.string.mail_settings_per_account_notifications),
                stringResource(R.string.mail_settings_silence_one_signed_account),
                palette, onClick = onPerAccountNotifications)
            // Client-side filtering (priority only, VIP senders, lock-screen
            // previews) — a different layer from the server's per-scope alerts.
            var showNotificationFilters by remember { mutableStateOf(false) }
            SettingRow(Icons.Rounded.NotificationsActive, stringResource(R.string.mail_settings_notification_filters),
                stringResource(R.string.mail_settings_priority_only_vip_senders),
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
                Icons.Rounded.Lock, stringResource(R.string.mail_settings_app_lock),
                when {
                    !appLockUsable -> stringResource(R.string.mail_settings_not_available_device)
                    appLockOn -> stringResource(R.string.mail_settings_asks_fingerprint_face_when)
                    else -> stringResource(R.string.mail_settings_require_unlock_when_grapmail)
                },
                palette,
                onClick = toggleAppLock.takeIf { appLockUsable },
            )
            // FLAG_SECURE while signed in (security/ScreenPrivacy.kt). Per
            // device, default off; MainActivity applies it to the window.
            val allowShots by com.grapsee.grapmail.security.ScreenPrivacy
                .allowScreenshotsFlow(notifyContext).collectAsState()
            SettingRow(
                Icons.Rounded.Lock,
                androidx.compose.ui.res.stringResource(com.grapsee.grapmail.R.string.acct_allow_screenshots_title),
                if (allowShots) androidx.compose.ui.res.stringResource(com.grapsee.grapmail.R.string.acct_allow_screenshots_on)
                else androidx.compose.ui.res.stringResource(com.grapsee.grapmail.R.string.acct_allow_screenshots_off),
                palette,
                onClick = {
                    com.grapsee.grapmail.security.ScreenPrivacy.setAllowScreenshots(notifyContext, !allowShots)
                },
            )
            // Crash and hang reports to GrapMail's own server (logic/CrashReporter.kt):
            // app version, device model and the stack trace, never the account or mail.
            var crashReports by remember { mutableStateOf(com.grapsee.grapmail.logic.CrashReporter.enabled(notifyContext)) }
            SettingRow(
                Icons.Rounded.Shield, stringResource(R.string.mail_settings_send_crash_reports),
                if (crashReports) stringResource(R.string.mail_settings_app_version_device_error)
                else stringResource(R.string.mail_settings_off_crashes_not_reported),
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
                    Icons.Rounded.Language, stringResource(R.string.mail_settings_app_language),
                    stringResource(R.string.mail_settings_opens_systems_page_apps),
                    palette,
                    onClick = {
                        val open = android.content.Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS)
                            .setData(android.net.Uri.parse("package:${notifyContext.packageName}"))
                        runCatching { notifyContext.startActivity(open) }
                    },
                )
            }
            SettingRow(Icons.Rounded.Person, stringResource(R.string.mail_settings_personal_info),
                stringResource(R.string.mail_settings_name_birthday_photo), palette, onClick = onProfile)
            SettingRow(Icons.Rounded.Shield, stringResource(R.string.mail_settings_data_privacy),
                stringResource(R.string.mail_settings_what_kept_what_control), palette, onClick = onPrivacy)
            SettingRow(Icons.Rounded.Lock, stringResource(R.string.mail_settings_encryption),
                stringResource(R.string.mail_settings_end_end_key_device), palette, onClick = onEncryption)

            com.grapsee.grapmail.ui.mailfeatures.MailFeatureSettingsSection()

            // Round 8 — identity (round8-spec §1.11). Material You is opt-in: the
            // row exists only where the platform has wallpaper colours (Android
            // 12+), and below that it is absent rather than disabled.
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                SectionLabel(stringResource(R.string.identity_settings_appearance), palette)
                MatchWallpaperRow(palette)
            }

            SectionLabel(stringResource(R.string.mail_settings_support), palette)
            SettingRow(Icons.Rounded.HelpOutline, stringResource(R.string.mail_settings_help_support),
                stringResource(R.string.mail_settings_questions_product_actually_raises), palette, onClick = onHelp)

            // 48dp target; the spacers give back the target's slack so the
            // line sits where it did.
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.mail_settings_sign_out), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.error,
                modifier = Modifier.padding(horizontal = 20.dp).minimumInteractiveComponentSize()
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onSignOut)
                    .testTag("mail-sign-out"))
            Spacer(Modifier.height(16.dp))
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
                    ?: throw IllegalStateException(CoreStrings.get(R.string.mail_peracct_sign_again) { "Sign in again." })
                val session = SessionRefresh.forAccount(context, stored.id)
                    ?: throw IllegalStateException(CoreStrings.get(R.string.mail_peracct_sign_again) { "Sign in again." })
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
                error = e.message ?: CoreStrings.get(R.string.mail_peracct_change_did_not_save) { "The change did not save. Try again." }
            } finally {
                busyEmail = null
            }
        }
    }

    MailScaffold(stringResource(R.string.mail_peracct_per_account_notifications), onBack,
        maxContentWidth = GMLayout.Form) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionLabel(stringResource(R.string.mail_peracct_signed_accounts), palette)
            val current = rows
            when {
                current == null && !failed ->
                    Text(stringResource(R.string.mail_peracct_checking), fontSize = 13.sp, color = palette.textTertiary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                current == null -> {
                    Text(stringResource(R.string.mail_peracct_account_list_did_not), fontSize = 13.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    androidx.compose.material3.TextButton(onClick = { scope.launch { load() } }) {
                        Text(stringResource(R.string.mail_common_retry))
                    }
                }
                current.isEmpty() ->
                    Text(stringResource(R.string.mail_peracct_no_accounts_signed_device), fontSize = 13.sp,
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
                                    row.deviceRowId == null -> stringResource(R.string.mail_peracct_not_registered_push_device)
                                    row.muted -> stringResource(R.string.mail_peracct_muted_accounts_alerts_silenced)
                                    else -> stringResource(R.string.mail_peracct_alerts_arrive_normally)
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
            NoteBar(stringResource(R.string.mail_peracct_silencing_one_account_stops))
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

/**
 * Settings → Appearance → "Match wallpaper" (round8-spec §1.11).
 *
 * Off by default. On, the accent family — buttons, links, highlights — follows
 * the wallpaper through Material You, while avatars, status colours, neutrals
 * and text stay GrapMail's. The value lives on the device
 * (MailDisplayPrefs.matchWallpaper) and GrapMailTheme reads it as snapshot
 * state, so the app repaints the moment the switch flips. The whole row is the
 * toggle, with switch semantics, so TalkBack reads the title, the subtitle and
 * the on/off state as one control.
 */
@Composable
private fun MatchWallpaperRow(palette: com.grapsee.grapmail.ui.theme.Palette) {
    val context = androidx.compose.ui.platform.LocalContext.current
    remember(context) { com.grapsee.grapmail.data.MailDisplayPrefs.ensureAppearanceLoaded(context) }
    val on = com.grapsee.grapmail.data.MailDisplayPrefs.matchWallpaper
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = on, role = androidx.compose.ui.semantics.Role.Switch) { want ->
                com.grapsee.grapmail.data.MailDisplayPrefs.setMatchWallpaper(context, want)
            }
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("settings-match-wallpaper"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Palette, null, tint = palette.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.identity_match_wallpaper_title), fontSize = 15.sp,
                fontWeight = FontWeight.Medium, color = palette.textPrimary)
            Text(stringResource(R.string.identity_match_wallpaper_subtitle), fontSize = 13.sp,
                color = palette.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = palette.primarySolid),
        )
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

    MailScaffold(stringResource(R.string.mail_screener_screener), onBack, maxContentWidth = GMLayout.Form) {
        // Scrolls, so the switch and Open queue stay reachable in a short
        // window or at large text.
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.size(if (com.grapsee.grapmail.ui.components.gmIsShortWindow()) 52.dp else 70.dp)
                .clip(RoundedCornerShape(18.dp)).background(palette.softFill),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Lock, null, tint = palette.primarySolid, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.mail_screener_unknown_sender_approval), fontSize = 19.sp, fontWeight = FontWeight.Bold,
                color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.mail_screener_mail_from_senders_have),
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
                    Text(stringResource(R.string.mail_screener_screener_enabled), fontSize = 15.sp, color = palette.textPrimary)
                    Text(
                        when {
                            !state.loaded -> stringResource(R.string.mail_screener_checking_mailbox)
                            state.enabled == null -> stringResource(R.string.mail_screener_status_not_reported)
                            else -> if (state.enabled == true) stringResource(R.string.mail_screener_unknown_senders_held)
                            else stringResource(R.string.mail_screener_off_all_senders_reach)
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
                    Text(stringResource(R.string.mail_common_retry))
                }
            }

            Spacer(Modifier.height(18.dp))
            DetailLine(stringResource(R.string.mail_screener_waiting_queue),
                state.pending?.toString() ?: stringResource(R.string.mail_screener_not_loaded), palette)

            Spacer(Modifier.height(18.dp))
            GradientButton(stringResource(R.string.mail_screener_open_queue), onClick = onOpenQueue)
            Spacer(Modifier.height(16.dp))
        }
    }
}
