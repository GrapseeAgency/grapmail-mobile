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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonAddAlt1
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.MailAttachmentDto
import com.grapsee.grapmail.logic.MailboxViewModel
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.OutlineButton
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * Reference 76 — compose — and 82, the send receipt, which is this screen's own
 * result state rather than a separate destination.
 *
 * ── WHY THE RECEIPT WORDS ITSELF THE WAY IT DOES ────────────────────────────
 * `POST /send` answers with a `status` and a `deliveryError`, and for a message
 * that the queue accepted but has not yet delivered the status is `queued`
 * (`grapmail.ts:846`, and the send-status honesty test the web client pins).
 * Printing "Sent" for that would tell the person their mail arrived when the
 * only fact is that it was taken. So the receipt prints the server's own status
 * word, and its own reason when there is one, and never upgrades `queued` to
 * `sent`.
 *
 * ── WHAT THE ATTACHMENT BUTTONS ACTUALLY DO ─────────────────────────────────
 * All four are real. File and image read bytes through the content resolver and
 * upload them (`POST /attachments`, fields `file` and `messageId`), and the id
 * the route returns is what the send carries — a filename that was never
 * uploaded is a document the recipient cannot open. Link and emoji insert into
 * the body at the caret's end. Nothing here is a decorative glyph.
 */
@Composable
fun ComposeScreen(
    vm: MailboxViewModel,
    profile: com.grapsee.grapmail.logic.ProfileViewModel,
    e2e: com.grapsee.grapmail.logic.E2eViewModel,
    replyId: String = "",
    mode: String = "reply",
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state by vm.compose.collectAsState()
    val profileState by profile.state.collectAsState()
    val e2eState by e2e.state.collectAsState()
    val attached = remember { mutableStateListOf<MailAttachmentDto>() }
    var notice by remember { mutableStateOf<String?>(null) }
    var showCc by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    // The encryption choice. It can only be ON when this device holds an
    // unlocked key -- a toggle that sealed with a locked key would fail on
    // send, and a failure at send time is the worst place to learn the key
    // was locked the whole time.
    var encryptOn by remember { mutableStateOf(false) }

    // The signature the profile carries: the per-address override for the
    // address this account sends from first, then the default. Empty when the
    // profile has not been read or was never filled in -- an invented
    // "-- Sent with GrapMail" would sign mail the person did not sign.
    val signature = (profileState.profile?.settings?.let { s ->
        val me = vm.state.value.accountAddress
        (me?.let { s.signatures[it] } ?: "").ifBlank { s.signature }
    } ?: "").trim()

    LaunchedEffect(Unit) { profile.load() }
    LaunchedEffect(Unit) { e2e.load() }

    // A prepared compose (reply / reply-all / forward from the thread) builds
    // its fields from the full server row; a blank one gets the signature
    // appended as editable text. Both run once per opened target, so a
    // rotation does not double-append what the person already edited.
    var prepared by remember { mutableStateOf(false) }
    LaunchedEffect(replyId, signature) {
        if (prepared) return@LaunchedEffect
        if (replyId.isNotBlank()) {
            prepared = true
            vm.prepareReply(replyId, mode, signature)
        } else if (signature.isNotBlank()) {
            vm.composeUpdate { s ->
                if (s.body.isBlank()) s.copy(body = "\n\n-- \n$signature") else s
            }
            prepared = true
        }
    }

    // People the To field can offer. Derived, not an address book — see
    // MailboxViewModel.people for why that distinction is stated rather than
    // papered over.
    var people by remember { mutableStateOf<List<MailboxViewModel.Person>>(emptyList()) }
    LaunchedEffect(state.to) {
        val already = splitTo(state.to)
        people = vm.people().filter { p ->
            (p.address.contains(state.to.trim(), ignoreCase = true) ||
                p.name?.contains(state.to.trim(), ignoreCase = true) == true) &&
                already.none { it.equals(p.address, ignoreCase = true) }
        }.take(5)
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) uploadOne(vm, context, uri, attached) { notice = it }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) uploadOne(vm, context, uri, attached) { notice = it }
    }

    Column(Modifier.fillMaxSize().background(palette.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Close, "Discard", tint = palette.textPrimary,
                modifier = Modifier.size(22.dp).clip(CircleShape).clickable {
                    // Discarding is a choice, not an accident: the draft is saved
                    // first, because losing half-written text is the other way a
                    // mail app loses something.
                    if (state.body.isNotBlank() || state.subject.isNotBlank()) vm.saveDraft()
                    onBack()
                })
            Spacer(Modifier.width(12.dp))
            Text("Compose", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.Send, "Send", tint = palette.primarySolid,
                modifier = Modifier.size(22.dp).clip(CircleShape)
                    .clickable(enabled = !state.sending) {
                        if (!encryptOn) { vm.send(); return@clickable }
                        // The seal path: ask the directory for the plan, and
                        // only send encrypted when EVERY recipient has a key
                        // and this device can open its own. A plan that says
                        // "plain", a locked key, or no answer at all stops the
                        // send with a sentence -- an encrypted message half
                        // built is the failure mode this gate exists to stop.
                        scope.launch {
                            val recipients = (splitTo(state.to) + splitTo(state.cc) +
                                splitTo(state.bcc)).distinct()
                            val plan = vm.encryptionPlan(recipients)
                            when {
                                plan == null -> notice =
                                    "The directory did not answer, so nothing was encrypted " +
                                        "and nothing was sent. Try again."
                                plan.first != "seal" -> notice =
                                    "Not every recipient has a published key, so this message " +
                                        "cannot be encrypted. Turn encryption off to send it " +
                                        "unencrypted, or stop and wait for the recipients."
                                else -> {
                                    val envelope = e2e.seal(state.body, plan.second)
                                    if (envelope != null) vm.send(envelope)
                                }
                            }
                        }
                    }
                    .padding(2.dp).testTag("mail-send"))
        }

        // Fields on top, the body filling everything between them and the
        // pinned action bar: the compose idiom Gmail and Apple Mail ship.
        // The scrollable Column this replaces left the body at a 240dp floor
        // with a dead zone under it -- frame 76's photograph showed the whole
        // lower half of the screen empty, the exact top-heavy layout the
        // design audit set out to remove. No outer scroll: the body grows.
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

            FieldRow(label = "To", value = state.to, placeholder = "Search contacts or type email…",
                onValue = { vm.composeUpdate { s -> s.copy(to = it) } },
                tag = "mail-to",
                trailing = {
                    Icon(Icons.Rounded.PersonAddAlt1, "Add recipient", tint = palette.primarySolid,
                        modifier = Modifier.size(18.dp).clickable { showCc = true })
                })

            if (people.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    people.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable { vm.composeUpdate { s -> s.copy(to = appendTo(s.to, p.address)) } }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(p.name ?: p.address, fontSize = 14.sp, color = palette.textPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(p.address, fontSize = 12.sp, color = palette.textTertiary, maxLines = 1)
                        }
                    }
                }
            }

            if (showCc) {
                FieldRow(label = "Cc", value = state.cc, placeholder = "Addresses",
                    onValue = { vm.composeUpdate { s -> s.copy(cc = it) } }, tag = "mail-cc")
                FieldRow(label = "Bcc", value = state.bcc, placeholder = "Addresses",
                    onValue = { vm.composeUpdate { s -> s.copy(bcc = it) } }, tag = "mail-bcc")
            }

            Divider(palette)

            FieldRow(label = "Subject", value = state.subject, placeholder = "Add a subject",
                onValue = { vm.composeUpdate { s -> s.copy(subject = it) } }, tag = "mail-subject")

            Divider(palette)

            // The encrypt row. The states are the web compose's own: a device
            // with no key never sees the option; a locked key says what would
            // fix it; only an unlocked key can flip it on. And when it is on,
            // the person is told plainly that the body is sealed before it
            // leaves the device -- the server will store a notice, not text.
            if (e2eState.record != null) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .clickable(enabled = e2eState.unlocked) { encryptOn = !encryptOn }
                        .testTag("gm-compose-encrypt"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Lock, null,
                        tint = if (encryptOn) palette.primarySolid else palette.textTertiary,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (encryptOn) "Encrypting this message" else "Encrypt this message",
                        fontSize = 14.sp,
                        color = if (encryptOn) palette.primarySolid else palette.textPrimary,
                        modifier = Modifier.weight(1f))
                    Box(Modifier.size(20.dp).clip(CircleShape)
                        .border(1.5.dp, if (encryptOn) palette.primarySolid else palette.border,
                            CircleShape)
                        .background(if (encryptOn) palette.primarySolid else palette.card),
                        contentAlignment = Alignment.Center) {
                        if (encryptOn) Text("✓", color = palette.onPrimary, fontSize = 12.sp)
                    }
                }
                if (!e2eState.unlocked) {
                    Text("Unlock your key to encrypt — it locks when the app closes.",
                        fontSize = 12.sp, color = palette.textTertiary)
                } else if (encryptOn) {
                    Text("Only the recipients' published keys can open this. The server will " +
                        "store a notice, not your text.",
                        fontSize = 12.sp, color = palette.textTertiary)
                }
                Spacer(Modifier.height(4.dp))
            }

            // The growth lives on a Box the field fills, not on the field's
            // own modifier: a weighted child beside the scroll floor proved
            // fragile in frame 76's photograph (the bar floated mid-screen).
            // The Box takes the leftover height, unconditionally; the text
            // paints inside it at the same insets as before.
            Box(Modifier.weight(1f).heightIn(min = 240.dp)) {
                BasicTextField(
                    value = state.body,
                    onValueChange = { vm.composeUpdate { s -> s.copy(body = it) } },
                    // The tag names the signed state because the walk needs a
                    // settled marker that is not the field's text: the body's
                    // uiautomator text is the WHOLE value ("-- \\nSent with
                    // GrapMail"), and an exact-match wait on the signature alone
                    // could never fire. A testTag flips with the signature landing
                    // -- the same mechanism the bottom sheets are photographed by.
                    modifier = Modifier.fillMaxSize()
                        .testTag(if (signature.isNotBlank() && state.body.contains(signature))
                            "gm-signed-body" else "mail-body-field")
                        .padding(vertical = 14.dp),
                    textStyle = TextStyle(fontSize = 15.sp, color = palette.textPrimary, lineHeight = 22.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    decorationBox = { inner ->
                        Box {
                            if (state.body.isEmpty()) {
                                Text("Write your message…", fontSize = 15.sp, color = palette.textTertiary)
                            }
                            inner()
                        }
                    },
                )
            }

            if (attached.isNotEmpty()) {
                attached.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp)).background(palette.softFill)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.AttachFile, null, tint = palette.textSecondary,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(a.name ?: "Attachment", fontSize = 13.sp, color = palette.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        a.sizeBytes?.let { Text(humanBytes(it), fontSize = 12.sp, color = palette.textTertiary) }
                    }
                }
            }

            notice?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 12.sp, color = palette.textSecondary, maxLines = 3,
                    overflow = TextOverflow.Ellipsis)
            }

            // The receipt. Shown as the server framed it, never smoothed over.
            state.resultStatus?.let { status ->
                Spacer(Modifier.height(14.dp))
                SendReceipt(status = status, detail = state.result, error = state.error)
            }

            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 14.dp)) {
                Icon(Icons.Rounded.AttachFile, "Attach a file", tint = palette.textSecondary,
                    modifier = Modifier.size(20.dp).clickable { pickFile.launch(arrayOf("*/*")) })
                Icon(Icons.Rounded.Image, "Attach an image", tint = palette.textSecondary,
                    modifier = Modifier.size(20.dp).clickable { pickImage.launch("image/*") })
                Icon(Icons.Rounded.Link, "Insert a link", tint = palette.textSecondary,
                    modifier = Modifier.size(20.dp).clickable {
                        vm.composeUpdate { s -> s.copy(body = if (s.body.isBlank()) "https://" else "${s.body}\n\nhttps://") }
                    })
                Icon(Icons.Rounded.EmojiEmotions, "Insert an emoji", tint = palette.textSecondary,
                    modifier = Modifier.size(20.dp).clickable { showEmoji = !showEmoji })
            }

            if (showEmoji) {
                // Inserting a character, not opening a panel that goes nowhere.
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("🙂", "👍", "🎉", "❤️", "😂", "🙏").forEach { e ->
                        Text(e, fontSize = 20.sp,
                            modifier = Modifier.clickable {
                                vm.composeUpdate { s -> s.copy(body = s.body + e) }
                            })
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlineButton("Save draft", onClick = {
                    vm.saveDraft()
                    notice = "Saving…"
                })
                GradientButton(if (state.sending) "Sending…" else "Send", onClick = { vm.send() },
                    enabled = !state.sending, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

private fun appendTo(existing: String, address: String): String =
    if (existing.isBlank()) address else "${existing.trim().removeSuffix(",")}, $address"

private fun splitTo(raw: String): List<String> =
    raw.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun Divider(palette: com.grapsee.grapmail.ui.theme.Palette) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    placeholder: String,
    onValue: (String) -> Unit,
    tag: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = palette.textSecondary, modifier = Modifier.width(56.dp))
        BasicTextField(
            value = value,
            onValueChange = onValue,
            maxLines = 3,
            textStyle = TextStyle(fontSize = 15.sp, color = palette.textPrimary),
            modifier = Modifier.weight(1f).testTag(tag),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = palette.textTertiary)
                    inner()
                }
            },
        )
        if (trailing != null) { Spacer(Modifier.width(10.dp)); trailing() }
    }
}

/**
 * The send receipt. The status word is the server's, and `queued` is shown as
 * queued.
 *
 * `internal`, not `private`, for one reason: the CI snapshot host photographs
 * this receipt, and it must photograph THIS composable. It used to carry its own
 * copy of these words, which drifted immediately — the copy dropped the semibold
 * headline and the `detail` line and invented a failure sentence the server never
 * sends. A photograph of a re-implementation proves nothing about the screen a
 * person sees, and nothing in a build or a PNG count would notice.
 */
@Composable
internal fun SendReceipt(status: String, detail: String?, error: String?) {
    val palette = LocalPalette.current
    val (headline, tone) = when (status) {
        "sent" -> "Sent" to palette.successInk
        "queued" -> "Queued for delivery" to palette.warnInk
        "failed" -> "Not delivered" to palette.error
        // The draft path answers with this status word, and without an arm for it a
        // successful save fell through to "Server reported: draft" in warn ink
        // beneath a line saying "Saved to Drafts". iOS already handles `draft`:
        // this is parity, and the headline stays the server's own word.
        "draft" -> "Saved to Drafts" to palette.successInk
        else -> "Server reported: $status" to palette.warnInk
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.softFill)
            .padding(14.dp),
    ) {
        Text(headline, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = tone)
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(detail, fontSize = 13.sp, color = palette.textSecondary)
        }
        if (status == "queued") {
            Spacer(Modifier.height(4.dp))
            Text("The queue has accepted it. Delivery is confirmed separately.",
                fontSize = 12.sp, color = palette.textTertiary)
        }
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(error, fontSize = 13.sp, color = palette.error)
        }
    }
}

/**
 * Read the picked document's bytes and upload them.
 *
 * `OpenDocument` grants read access for this process only, so the bytes are read
 * here and handed on — a URI passed to a later screen would fail once the
 * permission died, which is the kind of bug that only appears a day later.
 */
private fun uploadOne(
    vm: MailboxViewModel,
    context: android.content.Context,
    uri: Uri,
    into: androidx.compose.runtime.snapshots.SnapshotStateList<MailAttachmentDto>,
    report: (String) -> Unit,
) {
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    val name = queryName(context, uri) ?: uri.lastPathSegment ?: "attachment"
    val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
        .getOrNull()
    if (bytes == null) { report("That file could not be read."); return }
    if (bytes.isEmpty()) { report("That file is empty."); return }
    report("Uploading ${name}…")
    vm.uploadAttachment(bytes, name, mime) { attachment, message ->
        if (attachment != null) into.add(attachment)
        report(message)
    }
}

private fun queryName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
}
