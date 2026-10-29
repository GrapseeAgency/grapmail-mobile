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
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
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
import com.grapsee.grapmail.data.MailAttachmentDto
import com.grapsee.grapmail.data.MailMessageFullDto
import com.grapsee.grapmail.logic.MailboxViewModel
import com.grapsee.grapmail.ui.components.SenderAvatar
import com.grapsee.grapmail.ui.components.absoluteDateTimeOrRaw
import com.grapsee.grapmail.ui.components.mailTime
import com.grapsee.grapmail.ui.theme.LocalPalette

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
    threadId: String,
    /// A row without a `threadId` falls back to its own id, and the thread route
    /// answers that with a 404. The list knows which one it handed over, so the
    /// screen opens the right resource instead of showing an error for a message
    /// that exists.
    isThread: Boolean,
    onBack: () -> Unit,
    onReply: (MailMessageFullDto) -> Unit,
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
                Icon(Icons.Rounded.Star, "Starred", tint = androidx.compose.ui.graphics.Color(0xFFF5A623),
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
                    onReply = { onReply(message) },
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
    onReply: () -> Unit,
    onStar: () -> Unit,
    onDownload: (MailAttachmentDto) -> Unit,
    notice: Map<String, String>,
) {
    val palette = LocalPalette.current
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
                tint = if (message.isStarred) androidx.compose.ui.graphics.Color(0xFFF5A623) else palette.textTertiary,
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
        if (message.isE2eEncrypted && message.bodyText.isNullOrBlank()) {
            EncryptedNotice()
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

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            com.grapsee.grapmail.ui.components.OutlineButton("Reply", onReply,
                trailing = { Icon(Icons.Rounded.Reply, null, tint = palette.primarySolid,
                    modifier = Modifier.size(16.dp)) })
        }
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
                "envelope and cannot open it, and this build does not yet hold a " +
                "private key to open it either — so the body is not available here " +
                "rather than empty.",
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
