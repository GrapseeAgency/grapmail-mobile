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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Forward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SupervisedUserCircle
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import com.grapsee.grapmail.data.MailAction
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.data.MailAttachmentDto
import com.grapsee.grapmail.data.MailMessageFullDto
import com.grapsee.grapmail.logic.MailboxViewModel
import com.grapsee.grapmail.ui.components.SenderAvatar
import com.grapsee.grapmail.ui.components.absoluteDateTimeOrRaw
import com.grapsee.grapmail.ui.components.mailTime
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * Reference 74 — a conversation. 75 is the same screen with an encrypted message
 * on it, which is why the encrypted branch is here rather than a separate screen:
 * it is one state of one layout.
 *
 * ── WHY THE HTML PART IS NOT RENDERED ───────────────────────────────────────
 * `fullRow` carries `bodyHtml` as the sender wrote it. Rendering it would hand a
 * remote message control of the app: a crafted body could load remote images (a
 * tracking pixel that tells the sender the mail was read, from a screen the user
 * never left), restyle the message to look like a system warning, or — with a
 * WebView — run script. There is no sanitiser in this app's dependencies, and the
 * correct one is not a regex.
 *
 * So the body comes from `bodyText`. When a message has only an HTML part, the
 * tags are stripped for display and the screen says it is showing a plain-text
 * rendering of an HTML message. That is a visible, honest downgrade rather than a
 * silent one, and it keeps the one thing a mail client must not outsource: trust
 * in what a message is allowed to do to the interface.
 */
@Composable
fun ThreadScreen(
    vm: MailboxViewModel,
    e2e: com.grapsee.grapmail.logic.E2eViewModel,
    threadId: String,
    /// A row without a `threadId` falls back to its own id, and the thread route
    /// answers that with a 404. The list knows which one it handed over, so the
    /// screen opens the right resource instead of showing an error for a message
    /// that exists.
    isThread: Boolean,
    onBack: () -> Unit,
    /** The row to answer, and how: "reply", "all" or "forward". The compose
     *  screen builds the quote and the addressing from the FULL row, so the
     *  thread hands over the row it actually displayed. */
    onReply: (MailMessageFullDto, String) -> Unit,
) {
    val palette = LocalPalette.current
    val thread by vm.thread.collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }
    // Per-message, not global: a download result belongs to the row that asked
    // for it, and a shared variable would let a later failure overwrite an
    // earlier success on screen.
    val notices = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(threadId) { if (isThread) vm.openThread(threadId) else vm.openMessage(threadId) }

    Column(Modifier.fillMaxSize().background(palette.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ArrowBack, "Back", tint = palette.textPrimary,
                modifier = Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onBack).padding(2.dp))
            Spacer(Modifier.width(12.dp))
            Text(if (thread.messages.size > 1) "${thread.messages.size} messages" else "Message",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.weight(1f))
            if (thread.messages.firstOrNull()?.isStarred == true) {
                Icon(Icons.Rounded.Star, "Starred", tint = palette.star, 
                    modifier = Modifier.size(22.dp))
            }
        }

        if (thread.loading && thread.messages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Opening this conversation…", fontSize = 14.sp, color = palette.textSecondary)
            }
            return@Column
        }

        if (thread.error != null && thread.messages.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("This conversation could not be opened.", fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text(thread.error!!, fontSize = 14.sp, color = palette.textSecondary)
                Spacer(Modifier.height(16.dp))
                com.grapsee.grapmail.ui.components.GradientButton("Try again", onClick = {
                    if (isThread) vm.openThread(threadId) else vm.openMessage(threadId)
                })
            }
            return@Column
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            thread.messages.forEachIndexed { index, message ->
                ThreadMessageCard(
                    message = message,
                    expanded = index == thread.messages.lastIndex,
                    busy = busy == message.id,
                    e2e = e2e,
                    onReply = { mode -> onReply(message, mode) },
                    onStar = {
                        busy = message.id
                        vm.actOn(message.id, if (message.isStarred) MailAction.Unstar else MailAction.Star)
                        busy = null
                    },
                    onDownload = { a ->
                        busy = a.id
                        vm.downloadAttachment(a) { outcome ->
                            busy = null
                            notices[a.id] = outcome
                        }
                    },
                    notice = notices,
                )
                if (index < thread.messages.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun ThreadMessageCard(
    message: MailMessageFullDto,
    expanded: Boolean,
    busy: Boolean,
    e2e: com.grapsee.grapmail.logic.E2eViewModel,
    onReply: (String) -> Unit,
    onStar: () -> Unit,
    onDownload: (MailAttachmentDto) -> Unit,
    notice: Map<String, String>,
) {
    val palette = LocalPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var showUnsub by androidx.compose.runtime.remember { mutableStateOf(false) }
    var unsubNote by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    // The sender's own `List-Unsubscribe` offer, narrowed to an http(s) address by
    // the same rule iOS applies. Null means show nothing: a button that opens a
    // broken or dangerous URL is worse than no button, and most mail simply has no
    // such header.
    val unsubUrl = com.grapsee.grapmail.ui.components.parseUnsubscribe(message.unsubscribe)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Text(
            message.subject?.takeIf { it.isNotBlank() } ?: "(no subject)",
            fontSize = 19.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            message.createdAt?.let { absoluteDateTimeOrRaw(it) } ?: "Time not recorded",
            fontSize = 12.sp, color = palette.textTertiary,
        )

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SenderAvatar(message.from?.display ?: "", message.from?.address, size = 38.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(message.from?.display?.takeIf { it.isNotBlank() } ?: "Unknown sender",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                message.from?.address?.let {
                    Text(it, fontSize = 12.sp, color = palette.textSecondary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(
                if (message.isStarred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                if (message.isStarred) "Starred" else "Star",
                tint = if (message.isStarred) palette.star else palette.textTertiary,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable(onClick = onStar).padding(2.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        Text("To", fontSize = 12.sp, color = palette.textTertiary)
        Text(message.to.joinToString(", ").ifBlank { "Not recorded" },
            fontSize = 13.sp, color = palette.textSecondary, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        if (message.cc.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Cc ${message.cc.joinToString(", ")}", fontSize = 12.sp, color = palette.textSecondary)
        }

        Spacer(Modifier.height(16.dp))

        // The encrypted branch is a state of this layout, not another screen.
        // The flag is the claim — the server sets `isE2eEncrypted` and stores
        // its notice in place of the body, and the envelope is the ciphertext.
        // The notice is never shown as if it were the message, and the
        // ciphertext is never shown at all: only an opened body is.
        if (message.isE2eEncrypted || message.e2eEnvelope != null) {
            EncryptedBlock(message, e2e)
        } else {
            val text = message.bodyText
            if (text.isNullOrBlank()) {
                if (!message.bodyHtml.isNullOrBlank()) {
                    // Stripped for display, and the screen says so.
                    HtmlStrippedNotice()
                    Spacer(Modifier.height(8.dp))
                    Text(stripHtml(message.bodyHtml!!), fontSize = 15.sp,
                        color = palette.textPrimary, lineHeight = 22.sp)
                } else if (expanded) {
                    Text("This message has no text body.", fontSize = 14.sp, color = palette.textSecondary)
                }
            } else {
                Text(text, fontSize = 15.sp, color = palette.textPrimary, lineHeight = 22.sp,
                    modifier = Modifier.testTag("mail-body"))
            }
        }

        if (message.attachments.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("${message.attachments.size} attachment" +
                (if (message.attachments.size == 1) "" else "s"),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textTertiary)
            Spacer(Modifier.height(8.dp))
            message.attachments.forEach { a ->
                AttachmentRow(a, busy = busy, notice = notice[a.id], onDownload = { onDownload(a) })
                Spacer(Modifier.height(6.dp))
            }
        }

        if (unsubUrl != null) {
            Spacer(Modifier.height(12.dp))
            com.grapsee.grapmail.ui.components.OutlineButton(
                "Unsubscribe from this sender", onClick = { showUnsub = true },
            )
            unsubNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = palette.error)
            }
        }

        if (showUnsub && unsubUrl != null) {
            MailSheet(onDismiss = { showUnsub = false }) {
                UnsubscribeContent(
                    // Blank, not absent: `display` is a non-optional that yields ""
                    // for a sender the server never named, so `?:` on it produced
                    // "This offer came from the sender, about ." -- a dangling clause
                    // in the one sentence whose job is to be exact about where the
                    // request goes.
                    sender = message.from?.address?.takeIf { it.isNotBlank() }
                        ?: message.from?.display?.takeIf { it.isNotBlank() },
                    url = unsubUrl,
                    onOpen = {
                        showUnsub = false
                        // The result is reported as what actually happened: the link
                        // was handed to another app. Whether the list processed it is
                        // something this app cannot know, so nothing here says
                        // "unsubscribed".
                        unsubNote = openExternally(context, unsubUrl)
                    },
                    onCancel = { showUnsub = false },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            com.grapsee.grapmail.ui.components.OutlineButton("Reply", { onReply("reply") },
                trailing = { Icon(Icons.Rounded.Reply, null, tint = palette.primarySolid,
                    modifier = Modifier.size(16.dp)) })
            com.grapsee.grapmail.ui.components.OutlineButton("Reply all", { onReply("all") },
                trailing = { Icon(Icons.Rounded.SupervisedUserCircle, null,
                    tint = palette.primarySolid, modifier = Modifier.size(16.dp)) })
            com.grapsee.grapmail.ui.components.OutlineButton("Forward", { onReply("forward") },
                trailing = { Icon(Icons.Rounded.Forward, null, tint = palette.primarySolid,
                    modifier = Modifier.size(16.dp)) })
        }
    }
}

/**
 * The encrypted message, as a state of the card. The notice is the server's
 * own text; the decrypt path needs the passphrase every time — the private
 * half is opened for one operation and kept nowhere. A wrong phrase, a
 * message not sealed to this key, and a damaged envelope each get their own
 * true sentence, and none of them ever shows ciphertext as a body.
 */
@Composable
private fun EncryptedBlock(message: MailMessageFullDto, e2e: com.grapsee.grapmail.logic.E2eViewModel) {
    val palette = LocalPalette.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showPhrase by remember { mutableStateOf(false) }
    var phrase by remember { mutableStateOf("") }
    var opened by remember(message.id) { mutableStateOf<String?>(null) }
    var working by remember(message.id) { mutableStateOf(false) }
    val envelope = message.e2eEnvelope?.toString()

    EncryptedNotice()
    if (opened != null) {
        Spacer(Modifier.height(12.dp))
        Text(opened!!, fontSize = 15.sp, color = palette.textPrimary, lineHeight = 22.sp,
            modifier = Modifier.testTag("gm-e2e-opened-body"))
    } else if (envelope != null) {
        Spacer(Modifier.height(12.dp))
        if (!showPhrase) {
            Text("Decrypt with your passphrase", fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, color = palette.primarySolid,
                modifier = Modifier.clickable {
                    scope.launch { e2e.load() }
                    showPhrase = true
                }.testTag("gm-e2e-decrypt-open").padding(vertical = 6.dp))
        } else {
            Column(Modifier.fillMaxWidth().testTag("gm-e2e-decrypt-panel")) {
                Text("Your passphrase decrypts this message on this device. It is never sent " +
                    "anywhere, and it is not kept after the message opens.",
                    fontSize = 12.sp, color = palette.textTertiary)
                Spacer(Modifier.height(8.dp))
                AppTextField("Passphrase", phrase, { phrase = it }, password = true)
                Row(Modifier.padding(top = 8.dp)) {
                    Text(if (working) "Opening…" else "Open", fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, color = palette.primarySolid,
                        modifier = Modifier.clickable(enabled = !working && phrase.isNotEmpty()) {
                            working = true
                            scope.launch {
                                opened = e2e.decrypt(envelope, phrase)
                                working = false
                                phrase = ""
                                if (opened == null) showPhrase = true
                            }
                        }.padding(vertical = 6.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Cancel", fontSize = 14.sp, color = palette.textSecondary,
                        modifier = Modifier.clickable { showPhrase = false }.padding(vertical = 6.dp))
                }
                e2e.state.value.note?.let {
                    if (e2e.state.value.noteIsBad) {
                        Text(it, fontSize = 12.sp, color = palette.error,
                            modifier = Modifier.padding(top = 6.dp).testTag("gm-e2e-decrypt-error"))
                    }
                }
            }
        }
    } else {
        // Flagged encrypted with no envelope to open: say that, not nothing.
        Spacer(Modifier.height(8.dp))
        Text("No encrypted content came with this message.", fontSize = 12.sp,
            color = palette.warnInk, modifier = Modifier.testTag("gm-e2e-no-envelope"))
    }
}

@Composable
private fun EncryptedNotice() {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.infoFill)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = palette.successInk, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("End-to-end encrypted", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Only you and the sender can read this. The server holds the sealed " +
                "envelope and cannot open it. Unlocking this device's key opens " +
                "the body below — a wrong passphrase and a message not sealed " +
                "to this key each say so, and the ciphertext is never shown as " +
                "a body.",
            fontSize = 13.sp, color = palette.textSecondary, lineHeight = 19.sp,
        )
    }
}

@Composable
private fun HtmlStrippedNotice() {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(palette.warnChipBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AttachFile, null, tint = palette.warnInk, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text("Plain-text view of an HTML message. Remote images and markup are not loaded.",
            fontSize = 12.sp, color = palette.warnInk, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AttachmentRow(a: MailAttachmentDto, busy: Boolean, notice: String?, onDownload: () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.card)
                .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AttachFile, null, tint = palette.textTertiary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name ?: "Attachment", fontSize = 14.sp, color = palette.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        a.mimeType?.takeIf { it.isNotBlank() },
                        a.sizeBytes?.let { humanBytes(it) },
                    ).joinToString(" · ").ifBlank { "Type not recorded" },
                    fontSize = 12.sp, color = palette.textSecondary,
                )
            }
            if (busy) {
                Text("Saving…", fontSize = 12.sp, color = palette.textTertiary)
            } else {
                Icon(Icons.Rounded.Download, "Download", tint = palette.primarySolid,
                    modifier = Modifier.size(20.dp).clip(CircleShape).clickable(onClick = onDownload).padding(2.dp))
            }
        }
        // The outcome is the app's own words about what happened, shown against
        // the row that asked. A silent failure here would look like a download.
        if (notice != null) {
            Spacer(Modifier.height(4.dp))
            Text(notice, fontSize = 11.sp, color = palette.textSecondary, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 40.dp))
        }
    }
}

fun humanBytes(n: Int): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.0f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / (1024.0 * 1024.0))
}

/**
 * Tags stripped for display only.
 *
 * This is not a sanitiser and is not used to make HTML safe to render — the
 * output is plain text, so markup cannot act on anything. It exists so a message
 * with only an HTML part is still readable instead of blank.
 */
private fun stripHtml(html: String): String =
    html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?is)<br\\s*/?>"), "\n")
        .replace(Regex("(?is)</p>"), "\n\n")
        .replace(Regex("(?is)<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

/**
 * The unsubscribe confirmation. Reached only from a message that actually carries
 * the sender's `List-Unsubscribe` offer.
 *
 * Four things it says before the user can act, because the honest answer to "did
 * this unsubscribe me?" is "the app cannot know":
 *
 *  - the request goes to the SENDER's server, not GrapMail's, and leaves this
 *    device the moment the link opens;
 *  - visiting such a link can itself confirm the address is live, which is a real
 *    cost the design's one-tap framing hides;
 *  - GrapMail cannot undo it;
 *  - no screen here claims the outcome. The line after opening reports that the
 *    link was handed to a browser, which is the only part this app witnessed.
 *
 * `internal` so the CI snapshot host can photograph it: a CI mailbox has no
 * subscribed mail, so the live path cannot reach this sheet on its own.
 */
@Composable
internal fun UnsubscribeContent(
    sender: String?,
    url: String,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalPalette.current
    val host = com.grapsee.grapmail.ui.components.urlHost(url) ?: "the sender's server"
    SheetTitle("Unsubscribe", "gm-sheets-unsub")
    Text(
        "This offer came from the sender" + (sender?.let { ", about $it" } ?: "") +
            ". Opening it goes to $host — not to GrapMail.",
        fontSize = 13.sp, color = palette.textSecondary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
    Text(
        "Some mailing lists treat any visit as proof that an address is live, so " +
            "opening this is not risk-free. GrapMail cannot undo it, and cannot see " +
            "what happens after: the page at $host is the only thing that will say " +
            "whether you were removed.",
        fontSize = 13.sp, color = palette.textSecondary, lineHeight = 19.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
    Spacer(Modifier.height(10.dp))
    com.grapsee.grapmail.ui.components.GradientButton(
        "Open in my browser", onClick = onOpen,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    Text(
        "Cancel", fontSize = 14.sp, color = palette.textTertiary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
            .clickable(onClick = onCancel),
    )
}

/**
 * Hand the link to whatever app handles web addresses, and report only what this
 * app actually witnessed.
 *
 * Returns the line the thread shows afterwards. It is deliberately not
 * "Unsubscribed": the request leaves this device the moment the browser opens, and
 * the sender's response is not observable from here. A failure to find a browser is
 * reported as that rather than as silence, so the user is not left wondering.
 */
internal fun openExternally(context: android.content.Context, url: String): String = try {
    context.startActivity(
        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    "Opened in your browser. GrapMail cannot see what the page said."
} catch (e: Exception) {
    // ActivityNotFoundException is the expected one (no browser installed, or a
    // work profile that blocks it); anything else from an Intent construction is
    // still just "this device could not open it", and reporting the exception
    // text would show the user a framework message they cannot act on.
    "No app on this device can open that link."
}
