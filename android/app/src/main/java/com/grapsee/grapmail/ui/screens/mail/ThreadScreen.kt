package com.grapsee.grapmail.ui.screens.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Forward
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.ReplyAll
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.mutableStateMapOf
import com.grapsee.grapmail.data.MailAction
import com.grapsee.grapmail.data.MailBox
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.data.MailAttachmentDto
import com.grapsee.grapmail.data.MailMessageFullDto
import com.grapsee.grapmail.logic.MailPollWorker
import com.grapsee.grapmail.logic.MailboxViewModel
import com.grapsee.grapmail.logic.MailStep
import com.grapsee.grapmail.ui.components.SenderAvatar
import com.grapsee.grapmail.ui.components.unsubscribeTarget
import com.grapsee.grapmail.ui.components.absoluteDateTimeOrRaw
import com.grapsee.grapmail.ui.components.mailTime
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

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
    /** Step to the previous / next loaded row without leaving the reader.
     *  The ViewModel names the target, including whether it is a conversation:
     *  the reader does not know the order, and it must not guess the route. */
    onStepPrev: (MailStep) -> Unit,
    onStepNext: (MailStep) -> Unit,
    onContacts: () -> Unit,
    initialMessageId: String? = null,
    openMessageActions: Boolean = false,
) {
    val palette = LocalPalette.current
    val thread by vm.thread.collectAsStateWithLifecycle()
    val mailbox by vm.state.collectAsStateWithLifecycle()
    var expandedMessageId by remember(threadId, isThread, initialMessageId) { mutableStateOf(initialMessageId) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var didFocus by remember(threadId, initialMessageId) { mutableStateOf(false) }
    var showInitialActions by androidx.compose.runtime.saveable.rememberSaveable(threadId, initialMessageId, openMessageActions) {
        mutableStateOf(openMessageActions)
    }
    val focusIndex = if (initialMessageId == null) -1 else thread.messages.indexOfFirst { it.id == initialMessageId }
    LaunchedEffect(focusIndex) {
        if (focusIndex >= 0 && !didFocus) {
            if (focusIndex > 0) {
                val headerCount = if (isThread && (thread.hasMore || thread.loadingEarlier)) 3 else 2
                listState.scrollToItem(focusIndex + headerCount)
            }
            didFocus = true
        }
    }
    val active = thread.messages.firstOrNull { it.id == expandedMessageId } ?: thread.messages.lastOrNull()
    val subject = thread.messages.firstOrNull()?.subject?.takeIf { it.isNotBlank() } ?: "(no subject)"
    var pendingAction by remember(threadId, isThread) { mutableStateOf(false) }
    var showNavigation by remember { mutableStateOf(false) }
    val filed = remember(threadId, isThread) { mutableStateMapOf<String, MailAction>() }
    val feedback = remember(threadId, isThread) { mutableStateMapOf<String, Pair<String, Boolean>>() }
    val notices = remember(threadId, isThread) { mutableStateMapOf<String, String>() }
    val downloading = remember(threadId, isThread) { mutableStateMapOf<String, Boolean>() }
    val (prior, next) = vm.neighboursOf(threadId)
    val at = vm.loadedPositionOf(threadId)
    val total = vm.loadedRowCount()
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(vm, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            MailPollWorker.results.collect { vm.acceptPoll(it, atTop = false) }
        }
    }

    fun file(message: MailMessageFullDto, action: MailAction, success: String) {
        pendingAction = true
        vm.actOn(message.id, action) { error ->
            pendingAction = false
            if (error == null) filed[message.id] = action
            feedback[message.id] = (error ?: success) to (error != null)
        }
    }

    LaunchedEffect(threadId, isThread, initialMessageId) {
        if (isThread) vm.openThread(threadId, initialMessageId) else vm.openMessage(threadId)
    }
    androidx.compose.runtime.DisposableEffect(vm, threadId, isThread) {
        onDispose { vm.leaveReader(threadId, isThread) }
    }

    Column(Modifier.fillMaxSize().background(palette.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
            Spacer(Modifier.weight(1f))
            active?.let { message ->
                if (mailbox.box !in listOf(MailBox.Archive, MailBox.Spam, MailBox.Trash)) {
                    ReaderIcon(Icons.Rounded.Archive, "Archive message",
                        enabled = !pendingAction && filed[message.id] == null) {
                        file(message, MailAction.Archive, "Message archived.")
                    }
                }
                if (mailbox.box != MailBox.Trash) {
                    ReaderIcon(Icons.Rounded.DeleteOutline, "Move message to Trash",
                        enabled = !pendingAction && filed[message.id] != MailAction.Trash) {
                        file(message, MailAction.Trash, "Message moved to Trash.")
                    }
                }
                if (message.isRead) {
                    ReaderIcon(Icons.Rounded.MarkEmailUnread, "Mark unread",
                        enabled = !pendingAction, modifier = Modifier.testTag("mail-mark-unread")) {
                        pendingAction = true
                        vm.markUnread(message.id) { error ->
                            pendingAction = false
                            feedback[message.id] = (error ?: "Marked unread.") to (error != null)
                        }
                    }
                }
            }
            if (prior != null || next != null) {
                Box {
                    ReaderIcon(Icons.Rounded.MoreVert, "Navigate messages") { showNavigation = true }
                    DropdownMenu(
                        expanded = showNavigation,
                        onDismissRequest = { showNavigation = false },
                        containerColor = palette.card,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (at > 0 && total > 1) {
                            Text("$at of $total loaded", fontSize = 12.sp, color = palette.textSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                        DropdownMenuItem(text = { Text("Previous message") }, enabled = prior != null,
                            modifier = Modifier.testTag("mail-thread-prev"), onClick = {
                                showNavigation = false
                                prior?.let(onStepPrev)
                            })
                        DropdownMenuItem(text = { Text("Next message") }, enabled = next != null,
                            modifier = Modifier.testTag("mail-thread-next"), onClick = {
                                showNavigation = false
                                next?.let(onStepNext)
                            })
                    }
                }
            }
        }

        if (thread.messages.isEmpty() && thread.error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Opening this conversation…", fontSize = 14.sp, color = palette.textSecondary)
            }
            return@Column
        }

        if (thread.messages.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("This conversation could not be opened.", fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text(thread.error.orEmpty(), fontSize = 14.sp, color = palette.textSecondary)
                Spacer(Modifier.height(16.dp))
                com.grapsee.grapmail.ui.components.GradientButton("Try again", onClick = {
                    if (isThread) vm.openThread(threadId, initialMessageId) else vm.openMessage(threadId)
                })
            }
            return@Column
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "subject") {
                Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(subject, fontSize = 24.sp, lineHeight = 30.sp,
                            fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                        Text(mailbox.box.title, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            color = palette.primarySolid,
                            modifier = Modifier.background(palette.softFill, CircleShape)
                                .padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    if (active != null) {
                        ReaderIcon(
                            if (active.isStarred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            if (active.isStarred) "Unstar message" else "Star message",
                            tint = if (active.isStarred) palette.star else palette.textSecondary,
                        ) {
                            vm.actOn(active.id, if (active.isStarred) MailAction.Unstar else MailAction.Star)
                        }
                    }
                }
            }
            item(key = "smart-panel") {
                com.grapsee.grapmail.ui.mailfeatures.MailSmartPanel(
                    message = active,
                    messages = thread.messages,
                )
            }
            if (isThread && (thread.hasMore || thread.loadingEarlier)) {
                item(key = "load-earlier") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        com.grapsee.grapmail.ui.components.OutlineButton(
                            if (thread.loadingEarlier) "Loading earlier messages…" else "Load earlier messages",
                            onClick = { if (!thread.loadingEarlier) vm.loadEarlierThread(threadId) },
                            fill = false,
                        )
                    }
                }
            }
            items(thread.messages, key = { it.id }) { message ->
                ThreadMessageCard(
                    vm = vm,
                    message = message,
                    expanded = active?.id == message.id,
                    onExpand = { expandedMessageId = message.id },
                    downloading = downloading,
                    e2e = e2e,
                    onReply = { mode -> onReply(message, mode) },
                    onDownload = { a ->
                        downloading[a.id] = true
                        vm.downloadAttachment(a) { outcome ->
                            downloading.remove(a.id)
                            notices[a.id] = outcome
                        }
                    },
                    notice = notices,
                    openActions = showInitialActions && message.id == initialMessageId,
                    onActionsOpened = { showInitialActions = false },
                )
            }
        }
        active?.let { message ->
            feedback[message.id]?.let { (text, isError) ->
                Text(text, fontSize = 12.sp, color = if (isError) palette.error else palette.textSecondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp))
            }
            // The suggestion engine lowercases the body and runs its scan
            // battery; keyed to the message it computes once per mail instead
            // of once per recomposition (polls, state writes, focus changes).
            val smartSuggestions = remember(message.id) {
                com.grapsee.grapmail.features.SmartReplyEngine.suggest(
                    subject = message.subject.orEmpty(),
                    bodyText = message.bodyText.orEmpty(),
                )
            }
            com.grapsee.grapmail.ui.mailfeatures.SmartReplyStrip(
                suggestions = smartSuggestions,
                onPick = { suggestion ->
                    com.grapsee.grapmail.logic.MailFeaturePrefs.stageComposeInsert(suggestion)
                    onReply(message, "reply")
                },
            )
            ReaderReplyBar { mode -> onReply(message, mode) }
            MailBottomBar(inboxSelected = true, unread = mailbox.unreadCount, onMail = onBack,
                onContacts = onContacts, overList = false)
        }
    }
}

@Composable
private fun ReaderIcon(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = LocalPalette.current.textPrimary,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(icon, label, modifier = Modifier.size(21.dp),
            tint = if (enabled) tint else palette.textTertiary.copy(alpha = 0.55f))
    }
}

@Composable
private fun ReaderReplyBar(onReply: (String) -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(palette.bg)) {
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderReplyButton("Reply", Icons.AutoMirrored.Rounded.Reply, Modifier.weight(1f)) { onReply("reply") }
            ReaderReplyButton("Forward", Icons.AutoMirrored.Rounded.Forward, Modifier.weight(1f)) { onReply("forward") }
            ReaderIcon(Icons.AutoMirrored.Rounded.ReplyAll, "Reply all",
                modifier = Modifier.size(48.dp).background(palette.softFill, RoundedCornerShape(16.dp)),
                tint = palette.primarySolid) { onReply("all") }
        }
    }
}

@Composable
private fun ReaderReplyButton(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.heightIn(min = 48.dp).clip(shape).background(palette.card)
        .border(1.dp, palette.border, shape)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = palette.primarySolid, modifier = Modifier.size(18.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = palette.primarySolid, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun ThreadMessageCard(
    vm: MailboxViewModel,
    message: MailMessageFullDto,
    expanded: Boolean,
    onExpand: () -> Unit,
    downloading: Map<String, Boolean>,
    e2e: com.grapsee.grapmail.logic.E2eViewModel,
    onReply: (String) -> Unit,
    onDownload: (MailAttachmentDto) -> Unit,
    notice: Map<String, String>,
    openActions: Boolean = false,
    onActionsOpened: () -> Unit = {},
) {
    val palette = LocalPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var showUnsub by androidx.compose.runtime.remember { mutableStateOf(false) }
    // Keyed on the message, because the reader can now step to a neighbouring
    // message without leaving this composition: a note that belonged to the
    // message just read would otherwise be printed under the next one.
    var unsubNote by androidx.compose.runtime.remember(message.id) { mutableStateOf<String?>(null) }
    // One note line for the affordances that can fail out of view — sharing
    // and printing both leave the app to ask the system for something, and
    // "nothing received it" has to be said here rather than left as a button
    // that appeared to do nothing.
    var shareNote by androidx.compose.runtime.remember(message.id) { mutableStateOf<String?>(null) }
    // The menu's spam and block calls answer on the card the same way the
    // handoff affordances do; without a line, a block would look like a
    // button that did nothing.
    var decisionNote by androidx.compose.runtime.remember(message.id) { mutableStateOf<String?>(null) }
    var showDetails by remember(message.id) { mutableStateOf(false) }
    var showActions by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(openActions, expanded) {
        if (openActions && expanded) {
            showActions = true
            onActionsOpened()
        }
    }
    var renderAttempt by remember(message.id) { mutableStateOf(0) }
    // The images the sender's HTML part points at, and whether this message has
    // asked for them. Both belong to the message: stepping from here must not
    // carry an opt-in that was given for somebody else's mail.
    // Settings → Tracker shield. On (the default) holds remote images until the
    // person asks; off loads them as the message opens.
    val imagesByDefault = !com.grapsee.grapmail.logic.MailFeaturePrefs(
        androidx.compose.ui.platform.LocalContext.current,
    ).trackerShieldEnabled
    var shownImages by androidx.compose.runtime.remember(message.id) { mutableStateOf(imagesByDefault) }
    // The formatted body is fetched when the card composes and again when the
    // images gate is answered — the two variants are different renders, and
    // the model memoises both per message.
    val showsBody = expanded
    var detailsError by remember(message.id) { mutableStateOf<String?>(null) }
    var detailsAttempt by remember(message.id) { mutableStateOf(0) }
    LaunchedEffect(message.id, showsBody, detailsAttempt) {
        if (showsBody) detailsError = vm.viewMessage(message.id)
    }
    val needsFormattedBody = showsBody && !message.isE2eEncrypted && message.e2eEnvelope == null &&
        message.hasHtml
    var render by remember(message.id) { mutableStateOf<MailboxViewModel.BodyRender?>(null) }
    var renderLoading by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(message.id, shownImages, needsFormattedBody, renderAttempt) {
        if (!needsFormattedBody) {
            render = null
            renderLoading = false
            return@LaunchedEffect
        }
        renderLoading = true
        val loaded = vm.loadBody(message.id, shownImages)
        if (loaded.error == null || render == null) render = loaded
        renderLoading = false
    }
    // The sender's own `List-Unsubscribe` offer, narrowed to an http(s) address by
    // the same rule iOS applies. Null means show nothing: a button that opens a
    // broken or dangerous URL is worse than no button, and most mail simply has no
    // such header.
    val unsubUrl = remember(message.id, message.unsubscribe) { unsubscribeTarget(message.unsubscribe) }
    val shape = RoundedCornerShape(20.dp)
    val sender = message.from?.display?.takeIf { it.isNotBlank() }
        ?: message.from?.address?.takeIf { it.isNotBlank() } ?: "Unknown sender"
    val recipient = message.to.firstOrNull().orEmpty()
    Column(
        Modifier.fillMaxWidth().clip(shape).background(palette.card).border(1.dp, palette.border, shape)
            .then(if (!expanded) Modifier.clickable(role = Role.Button, onClick = onExpand) else Modifier)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SenderAvatar(sender, message.from?.address, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(sender, modifier = Modifier.weight(1f, fill = false), fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, color = palette.textPrimary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    mailTime(message.createdAt)?.let { date ->
                        Text(date, fontSize = 11.sp, color = palette.textSecondary, maxLines = 1)
                    }
                }
                if (expanded) {
                    Row(Modifier.clickable(role = Role.Button) { showDetails = !showDetails }
                        .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (recipient.isBlank()) "Message details" else "To $recipient",
                            modifier = Modifier.weight(1f, fill = false), fontSize = 12.sp,
                            color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.ExpandMore, null, tint = palette.textSecondary,
                            modifier = Modifier.size(16.dp))
                    }
                } else {
                    Text(
                        if (message.isE2eEncrypted || message.e2eEnvelope != null) "Encrypted message"
                        else message.bodyText?.takeIf { it.isNotBlank() } ?: "Show message",
                        fontSize = 13.sp, lineHeight = 18.sp, color = palette.textSecondary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!expanded) {
                Icon(Icons.Rounded.ExpandMore, "Show message", tint = palette.textSecondary,
                    modifier = Modifier.padding(start = 8.dp).size(20.dp))
            } else {
                if (unsubUrl != null) {
                    TextButton(onClick = { showUnsub = true }, modifier = Modifier.testTag("mail-unsubscribe")) {
                        Text("Unsubscribe", fontSize = 12.sp, maxLines = 1)
                    }
                }
                Box {
                    ReaderIcon(Icons.Rounded.MoreVert, "Message actions") { showActions = true }
                    DropdownMenu(
                        expanded = showActions,
                        onDismissRequest = { showActions = false },
                        containerColor = palette.card,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        DropdownMenuItem(text = { Text("Reply") }, onClick = { showActions = false; onReply("reply") })
                        DropdownMenuItem(text = { Text("Share") }, modifier = Modifier.testTag("mail-share"), onClick = {
                            showActions = false
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_SUBJECT, message.subject.orEmpty())
                                .putExtra(android.content.Intent.EXTRA_TEXT, messageShareText(message, render?.bodyText))
                            try {
                                context.startActivity(android.content.Intent.createChooser(intent, "Share message"))
                            } catch (_: android.content.ActivityNotFoundException) {
                                shareNote = "Nothing on this device can receive shared text."
                            }
                        })
                        DropdownMenuItem(text = { Text("Print") }, modifier = Modifier.testTag("mail-print"), onClick = {
                            showActions = false
                            printMessageText(context, message, render?.bodyText) { outcome -> shareNote = outcome }
                        })

                        DropdownMenuItem(text = { Text("Unsubscribe in one click") }, modifier = Modifier.testTag("mail-unsubscribe-oneclick"), onClick = {
                            showActions = false
                            val address = message.from?.address?.takeIf { it.isNotBlank() }
                            if (message.unsubscribe?.oneClickAdvertised == true && address != null) {
                                vm.unsubscribeOneClick(address, unsubUrl ?: "") { unsubNote = it }
                            } else {
                                unsubNote = "This message does not carry a one-click request."
                            }
                        })
                        DropdownMenuItem(text = { Text("Report spam") }, modifier = Modifier.testTag("mail-report-spam"), onClick = {
                            showActions = false
                            vm.actOn(message.id, com.grapsee.grapmail.data.MailAction.Spam) { note -> decisionNote = note ?: "Moved to spam." }
                        })
                        DropdownMenuItem(text = { Text("Block sender") }, modifier = Modifier.testTag("mail-block-sender"), onClick = {
                            showActions = false
                            val address = message.from?.address?.takeIf { it.isNotBlank() }
                            if (address == null) decisionNote = "This message does not name a sender to block."
                            else vm.blockSender(address) { decisionNote = it }
                        })

                    }
                }
            }
        }

        if (!expanded) return@Column
        if (showDetails) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp).background(palette.bgTinted, RoundedCornerShape(12.dp))
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                message.from?.address?.let { Text("From $it", fontSize = 12.sp, color = palette.textSecondary) }
                if (message.to.isNotEmpty()) Text("To ${message.to.joinToString(", ")}", fontSize = 12.sp, color = palette.textSecondary)
                if (message.cc.isNotEmpty()) Text("Cc ${message.cc.joinToString(", ")}", fontSize = 12.sp, color = palette.textSecondary)
                message.createdAt?.let { Text(absoluteDateTimeOrRaw(it), fontSize = 12.sp, color = palette.textSecondary) }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (message.isE2eEncrypted || message.e2eEnvelope != null) {
            EncryptedBlock(message, e2e)
        } else {
            val formatted = render
            val blockedImages = formatted?.blockedImages ?: 0
            if (blockedImages > 0 && formatted?.allowImages != true) {
                Row(
                    Modifier.fillMaxWidth().background(palette.bgTinted, RoundedCornerShape(12.dp))
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(if (shownImages && !renderLoading) "Images couldn’t load."
                             else "$blockedImages remote image${if (blockedImages == 1) "" else "s"} hidden",
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                        Text("Loading them tells the sender this message was opened.",
                            fontSize = 11.sp, lineHeight = 15.sp, color = palette.textSecondary)
                    }
                    TextButton(
                        onClick = { if (shownImages) renderAttempt++ else shownImages = true },
                        enabled = !shownImages || !renderLoading,
                        modifier = Modifier.testTag("mail-show-images"),
                    ) { Text(if (shownImages && renderLoading) "Loading…" else "Show images", fontSize = 12.sp) }
                }
                Spacer(Modifier.height(10.dp))
            }
            if (message.hasHtml && formatted?.html != null) {
                HtmlMailBody(
                    html = formatted.html,
                    allowImages = formatted.allowImages,
                    onOpenLink = { url -> openExternally(context, url) },
                    modifier = Modifier.testTag("mail-body"),
                )
            } else {
                val text = message.bodyText?.takeIf { it.isNotBlank() }
                    ?: formatted?.bodyText?.takeIf { it.isNotBlank() }
                when {
                    text != null || !message.hasHtml -> Text(
                        text ?: "This message has no text body.", fontSize = 15.sp, lineHeight = 23.sp,
                        color = palette.textPrimary, modifier = Modifier.testTag("mail-body"),
                    )
                    formatted?.error != null -> {
                        Text("Couldn’t display this message.", fontSize = 13.sp, color = palette.textSecondary)
                        TextButton(onClick = { renderAttempt++ }, enabled = !renderLoading) { Text("Retry") }
                    }
                    else -> LinearProgressIndicator(Modifier.fillMaxWidth().testTag("mail-body-loading"))
                }
            }
        }

        detailsError?.let {
            Text("Couldn’t load message details.", fontSize = 12.sp, color = palette.textSecondary)
            androidx.compose.material3.TextButton(onClick = { detailsAttempt++ }) {
                Text("Retry")
            }
        }

        if (message.attachments.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("${message.attachments.size} attachment" +
                (if (message.attachments.size == 1) "" else "s"),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textSecondary)
            Spacer(Modifier.height(8.dp))
            message.attachments.forEach { a ->
                AttachmentRow(a, busy = downloading[a.id] == true, notice = notice[a.id], onDownload = { onDownload(a) })
                Spacer(Modifier.height(6.dp))
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

        listOfNotNull(unsubNote, shareNote, decisionNote).forEach {
            Text(it, fontSize = 12.sp, color = palette.textSecondary, modifier = Modifier.padding(top = 8.dp))
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
                    e2e.load(includeDirectory = false)
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
 *
 * Public, not file-private: `internal` at top level is FILE-scoped in Kotlin, and
 * the subscriptions screen hands off through this same witnessed-only path —
 * one handoff mechanism per app, so the sentence a user reads cannot differ
 * between the two doors to the same sender page.
 */
fun openExternally(context: android.content.Context, url: String): String = try {
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
