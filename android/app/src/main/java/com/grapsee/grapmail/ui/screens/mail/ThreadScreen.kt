package com.grapsee.grapmail.ui.screens.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.rounded.MailOutline
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
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
import com.grapsee.grapmail.ui.components.GMLayout
import com.grapsee.grapmail.ui.components.SenderAvatar
import com.grapsee.grapmail.ui.components.gmIsNarrowWindow
import com.grapsee.grapmail.ui.components.gmMaxWidth
import com.grapsee.grapmail.ui.components.unsubscribeTarget
import com.grapsee.grapmail.ui.components.absoluteDateTimeOrRaw
import com.grapsee.grapmail.ui.components.mailTime
import com.grapsee.grapmail.ui.components.displayTitle
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * The reader as a full screen (its own route, or the phone-width home with a
 * pane selection). The body lives in [ThreadContent], so the two-pane home on
 * tablets / unfolded foldables can host the same reader beside the list.
 */
@Composable
fun ThreadScreen(
    vm: MailboxViewModel,
    e2e: com.grapsee.grapmail.logic.E2eViewModel,
    threadId: String,
    isThread: Boolean,
    onBack: () -> Unit,
    onReply: (MailMessageFullDto, String) -> Unit,
    onStepPrev: (MailStep) -> Unit,
    onStepNext: (MailStep) -> Unit,
    onContacts: () -> Unit,
    initialMessageId: String? = null,
    openMessageActions: Boolean = false,
    /** Set only when a phone opens this reader from its list row: the key the
     *  row and this reader share bounds under (MailOpenTransition.kt). */
    sharedElementKey: String? = null,
) {
    ThreadContent(
        vm = vm,
        e2e = e2e,
        threadId = threadId,
        isThread = isThread,
        onBack = onBack,
        onReply = onReply,
        onStepPrev = onStepPrev,
        onStepNext = onStepNext,
        onContacts = onContacts,
        initialMessageId = initialMessageId,
        openMessageActions = openMessageActions,
        showBackButton = true,
        sharedElementKey = sharedElementKey,
    )
}

/**
 * The reader's content, hostable full screen or as the right pane of the
 * two-pane home. In a pane ([showBackButton] = false) the back arrow and the
 * bottom mail bar are left out: the list beside it is the way back, and the
 * system back gesture clears the selection (MainActivity, Routes.HOME).
 *
 * Width: the toolbar spans the window; the subject, the message cards, the
 * feedback line, the smart replies and the reply bar keep a comfortable
 * reading width ([GMLayout.Readable]) and are centred when the window is
 * wider (a tablet in portrait, a desktop / Chromebook / DeX window, a wide
 * split-screen half). The list still scrolls from anywhere in the window. In
 * the two-pane home the pane is usually narrower than the cap, so nothing
 * changes there. System bars and cutouts are already padded by the root
 * (MainActivity); the reader adds the keyboard's inset, so the passphrase
 * field of an encrypted message and the reply bar stay above it.
 */
@Composable
fun ThreadContent(
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
    showBackButton: Boolean = true,
    /** The phone's row-to-reader shared-bounds key, or null (two-pane, a
     *  notification / deep link / widget open, previous / next, reduced motion). */
    sharedElementKey: String? = null,
    modifier: Modifier = Modifier,
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
    val subject = thread.messages.firstOrNull()?.subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mail_no_subject)
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

    val archivedNote = stringResource(R.string.mail_thread_archived)
    val trashedNote = stringResource(R.string.mail_thread_trashed)
    val markedUnreadNote = stringResource(R.string.mail_thread_marked_unread)
    // Every reading-column element shares this: full width up to the reading
    // cap, centred beyond it, and filling that width itself.
    val readable = Modifier.gmMaxWidth(GMLayout.Readable).fillMaxWidth()

    // Phones, opened from the list: this surface is the tapped row's container
    // grown to full screen (shared bounds, keyed by the thread), and the body
    // inside it fades in a beat later. Both are no-ops without a key.
    Box(modifier.mailOpenReaderBounds(sharedElementKey).fillMaxSize().background(palette.bg)) {
    Column(Modifier.mailOpenBodyFade(sharedElementKey != null).fillMaxSize().statusBarsPadding().imePadding()) {
        Row(
            // The minimum height keeps the toolbar from collapsing in a pane,
            // where there is no back arrow and the actions wait for the load.
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBackButton) {
                ReaderIcon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.mail_common_back), onClick = onBack)
            }
            Spacer(Modifier.weight(1f))
            active?.let { message ->
                if (mailbox.box !in listOf(MailBox.Archive, MailBox.Spam, MailBox.Trash)) {
                    ReaderIcon(Icons.Rounded.Archive, stringResource(R.string.mail_thread_archive_message),
                        enabled = !pendingAction && filed[message.id] == null) {
                        file(message, MailAction.Archive, archivedNote)
                    }
                }
                if (mailbox.box != MailBox.Trash) {
                    ReaderIcon(Icons.Rounded.DeleteOutline, stringResource(R.string.mail_thread_trash_message),
                        enabled = !pendingAction && filed[message.id] != MailAction.Trash) {
                        file(message, MailAction.Trash, trashedNote)
                    }
                }
                if (message.isRead) {
                    ReaderIcon(Icons.Rounded.MarkEmailUnread, stringResource(R.string.mail_action_mark_unread),
                        enabled = !pendingAction, modifier = Modifier.testTag("mail-mark-unread")) {
                        pendingAction = true
                        vm.markUnread(message.id) { error ->
                            pendingAction = false
                            feedback[message.id] = (error ?: markedUnreadNote) to (error != null)
                        }
                    }
                }
            }
            if (prior != null || next != null) {
                Box {
                    ReaderIcon(Icons.Rounded.MoreVert, stringResource(R.string.mail_thread_navigate)) { showNavigation = true }
                    DropdownMenu(
                        expanded = showNavigation,
                        onDismissRequest = { showNavigation = false },
                        containerColor = palette.card,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (at > 0 && total > 1) {
                            Text(stringResource(R.string.mail_thread_position, at, total), fontSize = 12.sp, color = palette.textSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.mail_thread_previous)) }, enabled = prior != null,
                            modifier = Modifier.testTag("mail-thread-prev"), onClick = {
                                showNavigation = false
                                prior?.let(onStepPrev)
                            })
                        DropdownMenuItem(text = { Text(stringResource(R.string.mail_thread_next)) }, enabled = next != null,
                            modifier = Modifier.testTag("mail-thread-next"), onClick = {
                                showNavigation = false
                                next?.let(onStepNext)
                            })
                    }
                }
            }
        }

        // The mailbox's own network reading: a conversation already open stays
        // readable offline, with the banner saying why actions may not land.
        com.grapsee.grapmail.ui.components.OfflineBanner(online = mailbox.online)

        if (thread.messages.isEmpty() && thread.error == null) {
            com.grapsee.grapmail.ui.components.DetailSkeleton(
                Modifier.fillMaxSize(), label = stringResource(R.string.mail_thread_opening))
            return@Column
        }

        if (thread.messages.isEmpty()) {
            com.grapsee.grapmail.ui.components.ErrorState(
                onRetry = { if (isThread) vm.openThread(threadId, initialMessageId) else vm.openMessage(threadId) },
                title = stringResource(R.string.mail_thread_open_failed),
                message = thread.error,
                online = mailbox.online,
            )
            return@Column
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "subject") {
                Box(readable) {
                Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(subject, fontSize = 24.sp, lineHeight = 30.sp,
                            fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                        Text(mailbox.box.displayTitle(), fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            color = palette.primarySolid,
                            modifier = Modifier.background(palette.softFill, CircleShape)
                                .padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    if (active != null) {
                        ReaderIcon(
                            if (active.isStarred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            if (active.isStarred) stringResource(R.string.mail_thread_unstar)
                            else stringResource(R.string.mail_thread_star),
                            tint = if (active.isStarred) palette.star else palette.textSecondary,
                        ) {
                            vm.actOn(active.id, if (active.isStarred) MailAction.Unstar else MailAction.Star)
                        }
                    }
                }
                }
            }
            item(key = "smart-panel") {
                Box(readable) {
                    com.grapsee.grapmail.ui.mailfeatures.MailSmartPanel(
                        message = active,
                        messages = thread.messages,
                    )
                }
            }
            if (isThread && (thread.hasMore || thread.loadingEarlier)) {
                item(key = "load-earlier") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        com.grapsee.grapmail.ui.components.OutlineButton(
                            if (thread.loadingEarlier) stringResource(R.string.mail_thread_loading_earlier)
                            else stringResource(R.string.mail_thread_load_earlier),
                            onClick = { if (!thread.loadingEarlier) vm.loadEarlierThread(threadId) },
                            fill = false,
                        )
                    }
                }
            }
            items(thread.messages, key = { it.id }) { message ->
                Box(readable) {
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
        }
        active?.let { message ->
            feedback[message.id]?.let { (text, isError) ->
                Text(text, fontSize = 12.sp, color = if (isError) palette.error else palette.textSecondary,
                    modifier = readable.padding(horizontal = 18.dp, vertical = 4.dp))
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
                modifier = readable,
            )
            ReaderReplyBar { mode -> onReply(message, mode) }
            if (showBackButton) {
                MailBottomBar(inboxSelected = true, unread = mailbox.unreadCount, onMail = onBack,
                    onContacts = onContacts, overList = false)
            }
        }
    }
    } // Box
}

/** The right pane of the two-pane home while no mail is selected. */
@Composable
fun ReaderPaneEmptyState(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(
        modifier.fillMaxSize().background(palette.bg).padding(24.dp).testTag("mail-pane-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).background(palette.softFill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MailOutline, contentDescription = null,
                tint = palette.primarySolid, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.mail_pane_select_email),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = palette.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
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
        // The bar's line spans the window; its buttons keep the reading width.
        Row(Modifier.gmMaxWidth(GMLayout.Readable).fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderReplyButton(stringResource(R.string.mail_reply), Icons.AutoMirrored.Rounded.Reply, Modifier.weight(1f)) { onReply("reply") }
            ReaderReplyButton(stringResource(R.string.mail_forward), Icons.AutoMirrored.Rounded.Forward, Modifier.weight(1f)) { onReply("forward") }
            ReaderIcon(Icons.AutoMirrored.Rounded.ReplyAll, stringResource(R.string.mail_reply_all),
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
    val notifyScope = androidx.compose.runtime.rememberCoroutineScope()
    val threadKey = message.threadId?.takeIf { it.isNotBlank() } ?: message.id
    val senderAddress = message.from?.address?.trim()?.takeIf { it.isNotBlank() }
    var muted by remember(threadKey) { mutableStateOf(false) }
    var watched by remember(threadKey) { mutableStateOf(false) }
    var vip by remember(senderAddress) { mutableStateOf(false) }
    LaunchedEffect(threadKey, senderAddress) {
        // This account's copy of its rules, so the menu offers the right verb.
        val account = com.grapsee.grapmail.data.SessionStore.address(context)
        muted = com.grapsee.grapmail.logic.NotificationPolicy.isMuted(context, account, threadKey)
        watched = com.grapsee.grapmail.logic.NotificationPolicy.isWatched(context, account, threadKey)
        vip = com.grapsee.grapmail.logic.NotificationPolicy.isVip(context, account, senderAddress)
    }
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
    // "Show original colours": only a dark reader showing an HTML body has
    // anything to switch (light mode never darkens). The choice is this
    // device's, per sender (ReaderColourPrefs.kt), so the next mail from the
    // same address opens the same way; a sender without an address keeps the
    // choice for this message only. [localOriginal] makes the flip immediate
    // for the card that asked, whatever the store answers.
    val isDarkReader = palette.card.luminance() < 0.5f
    var localOriginal by remember(message.id) { mutableStateOf<Boolean?>(null) }
    val showOriginal = localOriginal ?: ReaderColourPrefs.showsOriginal(context, senderAddress)
    val canToggleColours = isDarkReader && expanded && message.hasHtml &&
        !message.isE2eEncrypted && message.e2eEnvelope == null && render?.html != null
    var colourNote by remember(message.id) { mutableStateOf<String?>(null) }
    val toggleColours: () -> Unit = {
        val want = !showOriginal
        localOriginal = want
        ReaderColourPrefs.setShowsOriginal(context, senderAddress, want)
        colourNote = senderAddress?.let {
            context.getString(
                if (want) R.string.reader_colours_remembered_original else R.string.reader_colours_remembered_dark, it)
        }
    }
    // Large text or a very narrow window (a 320 dp phone, a cover screen, a
    // narrow split-screen half): the header's extras move onto their own
    // lines instead of squeezing the sender's name to nothing.
    val roomy = LocalDensity.current.fontScale <= 1.3f && !gmIsNarrowWindow()
    // The sender's own `List-Unsubscribe` offer, narrowed to an http(s) address by
    // the same rule iOS applies. Null means show nothing: a button that opens a
    // broken or dangerous URL is worse than no button, and most mail simply has no
    // such header.
    val unsubUrl = remember(message.id, message.unsubscribe) { unsubscribeTarget(message.unsubscribe) }
    val shape = RoundedCornerShape(20.dp)
    val sender = message.from?.display?.takeIf { it.isNotBlank() }
        ?: message.from?.address?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mail_unknown_sender)
    val recipient = message.to.firstOrNull().orEmpty()
    Column(
        Modifier.fillMaxWidth().clip(shape).background(palette.card).border(1.dp, palette.border, shape)
            .then(if (!expanded) Modifier.clickable(role = Role.Button, onClick = onExpand) else Modifier)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        val messageDate = mailTime(message.createdAt, yesterday = stringResource(R.string.mail_time_yesterday))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SenderAvatar(sender, message.from?.address, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(sender, modifier = Modifier.weight(1f, fill = false), fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, color = palette.textPrimary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    com.grapsee.grapmail.ui.components.SenderBadges(message.from?.address)
                    if (roomy) {
                        messageDate?.let { date ->
                            Text(date, fontSize = 11.sp, color = palette.textSecondary, maxLines = 1)
                        }
                    }
                }
                if (!roomy) {
                    messageDate?.let { date ->
                        Text(date, fontSize = 11.sp, color = palette.textSecondary)
                    }
                }
                if (expanded) {
                    Row(Modifier.clickable(role = Role.Button) { showDetails = !showDetails }
                        .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (recipient.isBlank()) stringResource(R.string.mail_thread_message_details)
                            else stringResource(R.string.mail_scheduled_to, recipient),
                            modifier = Modifier.weight(1f, fill = false), fontSize = 12.sp,
                            color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.ExpandMore, null, tint = palette.textSecondary,
                            modifier = Modifier.size(16.dp))
                    }
                } else {
                    Text(
                        if (message.isE2eEncrypted || message.e2eEnvelope != null) stringResource(R.string.mail_thread_encrypted_message)
                        else message.bodyText?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mail_thread_show_message),
                        fontSize = 13.sp, lineHeight = 18.sp, color = palette.textSecondary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!expanded) {
                Icon(Icons.Rounded.ExpandMore, stringResource(R.string.mail_thread_show_message), tint = palette.textSecondary,
                    modifier = Modifier.padding(start = 8.dp).size(20.dp))
            } else {
                if (unsubUrl != null && roomy) {
                    TextButton(onClick = { showUnsub = true }, modifier = Modifier.testTag("mail-unsubscribe")) {
                        Text(stringResource(R.string.mail_subs_unsubscribe), fontSize = 12.sp, maxLines = 1)
                    }
                }
                val shareChooserTitle = stringResource(R.string.mail_thread_share_chooser)
                val shareNothing = stringResource(R.string.mail_thread_share_nothing)
                val noOneClick = stringResource(R.string.mail_thread_no_one_click)
                val movedToSpam = stringResource(R.string.mail_thread_moved_to_spam)
                val noSenderToBlock = stringResource(R.string.mail_thread_no_sender_to_block)
                val mutedNote = stringResource(R.string.mail_thread_muted_note)
                val unmutedNote = stringResource(R.string.mail_thread_unmuted_note)
                val watchedNote = stringResource(R.string.mail_thread_watched_note)
                val unwatchedNote = stringResource(R.string.mail_thread_unwatched_note)
                Box {
                    ReaderIcon(Icons.Rounded.MoreVert, stringResource(R.string.mail_thread_message_actions)) { showActions = true }
                    DropdownMenu(
                        expanded = showActions,
                        onDismissRequest = { showActions = false },
                        containerColor = palette.card,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.mail_reply)) }, onClick = { showActions = false; onReply("reply") })
                        DropdownMenuItem(text = { Text(stringResource(R.string.mail_thread_share)) }, modifier = Modifier.testTag("mail-share"), onClick = {
                            showActions = false
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_SUBJECT, message.subject.orEmpty())
                                .putExtra(android.content.Intent.EXTRA_TEXT, messageShareText(message, render?.bodyText, context.resources))
                            try {
                                context.startActivity(android.content.Intent.createChooser(intent, shareChooserTitle))
                            } catch (_: android.content.ActivityNotFoundException) {
                                shareNote = shareNothing
                            }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.mail_thread_print)) }, modifier = Modifier.testTag("mail-print"), onClick = {
                            showActions = false
                            printMessageText(context, message, render?.bodyText) { outcome -> shareNote = outcome }
                        })
                        // The same switch as the pill above the body, for the
                        // person who looks for it in the menu (and for a
                        // keyboard / switch-access user, one stop fewer).
                        if (canToggleColours) {
                            DropdownMenuItem(text = { Text(if (showOriginal) stringResource(R.string.reader_colours_menu_dark)
                                else stringResource(R.string.reader_colours_menu_original)) },
                                modifier = Modifier.testTag("mail-original-colours"), onClick = {
                                    showActions = false
                                    toggleColours()
                                })
                        }

                        DropdownMenuItem(text = { Text(stringResource(R.string.mail_thread_unsub_one_click)) }, modifier = Modifier.testTag("mail-unsubscribe-oneclick"), onClick = {
                            showActions = false
                            val address = message.from?.address?.takeIf { it.isNotBlank() }
                            if (message.unsubscribe?.oneClickAdvertised == true && address != null) {
                                vm.unsubscribeOneClick(address, unsubUrl ?: "") { unsubNote = it }
                            } else {
                                unsubNote = noOneClick
                            }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.mail_thread_report_spam)) }, modifier = Modifier.testTag("mail-report-spam"), onClick = {
                            showActions = false
                            vm.actOn(message.id, com.grapsee.grapmail.data.MailAction.Spam) { note -> decisionNote = note ?: movedToSpam }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.mail_thread_block_sender)) }, modifier = Modifier.testTag("mail-block-sender"), onClick = {
                            showActions = false
                            val address = message.from?.address?.takeIf { it.isNotBlank() }
                            if (address == null) decisionNote = noSenderToBlock
                            else vm.blockSender(address) { decisionNote = it }
                        })
                        // Notification rules for this conversation and sender, saved to
                        // the account (logic/NotificationPolicy.kt), so every device
                        // follows them.
                        DropdownMenuItem(text = { Text(if (muted) stringResource(R.string.mail_thread_unmute)
                            else stringResource(R.string.mail_thread_mute)) },
                            modifier = Modifier.testTag("mail-mute-thread"), onClick = {
                            showActions = false
                            val want = !muted
                            notifyScope.launch {
                                val error = com.grapsee.grapmail.logic.NotificationPolicy.setMuted(context, threadKey, want)
                                if (error == null) {
                                    muted = want
                                    if (want) watched = false
                                    decisionNote = if (want) mutedNote else unmutedNote
                                } else decisionNote = error
                            }
                        })
                        DropdownMenuItem(text = { Text(if (watched) stringResource(R.string.mail_thread_unwatch)
                            else stringResource(R.string.mail_thread_watch)) },
                            modifier = Modifier.testTag("mail-watch-thread"), onClick = {
                            showActions = false
                            val want = !watched
                            notifyScope.launch {
                                val error = com.grapsee.grapmail.logic.NotificationPolicy.setWatched(context, threadKey, want)
                                if (error == null) {
                                    watched = want
                                    if (want) muted = false
                                    decisionNote = if (want) watchedNote else unwatchedNote
                                } else decisionNote = error
                            }
                        })
                        senderAddress?.let { address ->
                            DropdownMenuItem(text = { Text(if (vip) stringResource(R.string.mail_thread_vip_remove)
                                else stringResource(R.string.mail_thread_vip_add)) },
                                modifier = Modifier.testTag("mail-vip-sender"), onClick = {
                                showActions = false
                                val want = !vip
                                notifyScope.launch {
                                    val error = com.grapsee.grapmail.logic.NotificationPolicy.setVip(context, address, want)
                                    if (error == null) {
                                        vip = want
                                        decisionNote = if (want) context.getString(R.string.mail_thread_vip_added, address)
                                        else context.getString(R.string.mail_thread_vip_removed, address)
                                    } else decisionNote = error
                                }
                            })
                        }

                    }
                }
            }
        }

        if (!expanded) return@Column
        // Large text / narrow window: the unsubscribe offer gets its own line
        // under the header (same tag, only one of the two is ever composed).
        if (unsubUrl != null && !roomy) {
            TextButton(onClick = { showUnsub = true }, modifier = Modifier.testTag("mail-unsubscribe")) {
                Text(stringResource(R.string.mail_subs_unsubscribe), fontSize = 12.sp)
            }
        }
        if (showDetails) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp).background(palette.bgTinted, RoundedCornerShape(12.dp))
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                message.from?.address?.let { Text(stringResource(R.string.mail_thread_details_from, it), fontSize = 12.sp, color = palette.textSecondary) }
                if (message.to.isNotEmpty()) Text(stringResource(R.string.mail_scheduled_to, message.to.joinToString(", ")), fontSize = 12.sp, color = palette.textSecondary)
                if (message.cc.isNotEmpty()) Text(stringResource(R.string.mail_thread_details_cc, message.cc.joinToString(", ")), fontSize = 12.sp, color = palette.textSecondary)
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
                val imagesCopy: @Composable (Modifier) -> Unit = { m ->
                    Column(m) {
                        Text(if (shownImages && !renderLoading) stringResource(R.string.mail_thread_images_failed)
                             else pluralStringResource(R.plurals.mail_thread_images_hidden, blockedImages, blockedImages),
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                        Text(stringResource(R.string.mail_thread_images_note),
                            fontSize = 11.sp, lineHeight = 15.sp, color = palette.textSecondary)
                    }
                }
                val imagesButton: @Composable () -> Unit = {
                    TextButton(
                        onClick = { if (shownImages) renderAttempt++ else shownImages = true },
                        enabled = !shownImages || !renderLoading,
                        modifier = Modifier.testTag("mail-show-images"),
                    ) { Text(if (shownImages && renderLoading) stringResource(R.string.mail_common_loading)
                        else stringResource(R.string.mail_thread_show_images), fontSize = 12.sp) }
                }
                if (roomy) {
                    Row(
                        Modifier.fillMaxWidth().background(palette.bgTinted, RoundedCornerShape(12.dp))
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        imagesCopy(Modifier.weight(1f).padding(vertical = 8.dp))
                        imagesButton()
                    }
                } else {
                    // Large text: the note over the button, so neither is squeezed.
                    Column(
                        Modifier.fillMaxWidth().background(palette.bgTinted, RoundedCornerShape(12.dp))
                            .padding(start = 12.dp, end = 4.dp, top = 8.dp),
                    ) {
                        imagesCopy(Modifier.fillMaxWidth().padding(end = 8.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { imagesButton() }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            if (message.hasHtml && formatted?.html != null) {
                if (canToggleColours) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ReaderColoursPill(showingOriginal = showOriginal, onToggle = toggleColours)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                HtmlMailBody(
                    html = formatted.html,
                    allowImages = formatted.allowImages,
                    onOpenLink = { url -> openExternally(context, url) },
                    modifier = Modifier.testTag("mail-body"),
                    darkeningEnabled = !showOriginal,
                )
            } else {
                val text = message.bodyText?.takeIf { it.isNotBlank() }
                    ?: formatted?.bodyText?.takeIf { it.isNotBlank() }
                when {
                    text != null || !message.hasHtml -> Text(
                        text ?: stringResource(R.string.mail_share_no_text_body), fontSize = 15.sp, lineHeight = 23.sp,
                        color = palette.textPrimary, modifier = Modifier.testTag("mail-body"),
                    )
                    formatted?.error != null -> {
                        Text(stringResource(R.string.mail_thread_display_failed), fontSize = 13.sp, color = palette.textSecondary)
                        TextButton(onClick = { renderAttempt++ }, enabled = !renderLoading) { Text(stringResource(R.string.mail_common_retry)) }
                    }
                    else -> LinearProgressIndicator(Modifier.fillMaxWidth().testTag("mail-body-loading"))
                }
            }
        }

        detailsError?.let {
            Text(stringResource(R.string.mail_thread_details_failed), fontSize = 12.sp, color = palette.textSecondary)
            androidx.compose.material3.TextButton(onClick = { detailsAttempt++ }) {
                Text(stringResource(R.string.mail_common_retry))
            }
        }

        if (message.attachments.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(pluralStringResource(R.plurals.mail_thread_attachment_count, message.attachments.size, message.attachments.size),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textSecondary)
            Spacer(Modifier.height(8.dp))
            // One column on a phone, more as the card widens (tablet, desktop
            // window, landscape); fewer again as the font grows.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = attachmentGridColumns(maxWidth, LocalDensity.current.fontScale)
                AttachmentGrid(columns = columns, modifier = Modifier.fillMaxWidth(),
                    horizontalGap = 8.dp, verticalGap = 6.dp) {
                    message.attachments.forEach { a ->
                        androidx.compose.runtime.key(a.id) {
                            AttachmentRow(vm, a, busy = downloading[a.id] == true, notice = notice[a.id], onDownload = { onDownload(a) })
                        }
                    }
                }
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

        listOfNotNull(unsubNote, shareNote, decisionNote, colourNote).forEach {
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
            Text(stringResource(R.string.mail_e2e_decrypt_open), fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, color = palette.primarySolid,
                modifier = Modifier.clickable {
                    e2e.load(includeDirectory = false)
                    showPhrase = true
                }.testTag("gm-e2e-decrypt-open").padding(vertical = 6.dp))
        } else {
            Column(Modifier.fillMaxWidth().testTag("gm-e2e-decrypt-panel")) {
                Text(stringResource(R.string.mail_e2e_decrypt_note),
                    fontSize = 12.sp, color = palette.textTertiary)
                Spacer(Modifier.height(8.dp))
                AppTextField(stringResource(R.string.mail_e2e_passphrase), phrase, { phrase = it }, password = true)
                Row(Modifier.padding(top = 8.dp)) {
                    Text(if (working) stringResource(R.string.mail_e2e_opening) else stringResource(R.string.mail_e2e_open), fontSize = 14.sp,
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
                    Text(stringResource(R.string.mail_common_cancel), fontSize = 14.sp, color = palette.textSecondary,
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
        Text(stringResource(R.string.mail_e2e_no_envelope), fontSize = 12.sp,
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
            Text(stringResource(R.string.mail_e2e_notice_title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.mail_e2e_notice_body),
            fontSize = 13.sp, color = palette.textSecondary, lineHeight = 19.sp,
        )
    }
}

/**
 * One attachment in the reader. Tapping the download icon opens / saves it as
 * before; a long-press on the chip drags it out to another app (split screen,
 * DeX, ChromeOS) under the same rules as the Files thumbnails
 * (AttachmentThumb.kt): only images with a decoder, at most 2 MB, whose bytes
 * are in the preview cache; any other file keeps the download icon only.
 * Laid out by [AttachmentGrid], so it fills whatever cell width it is given.
 */
@Composable
private fun AttachmentRow(
    vm: MailboxViewModel,
    a: MailAttachmentDto,
    busy: Boolean,
    notice: String?,
    onDownload: () -> Unit,
) {
    val palette = LocalPalette.current
    // Fetched into the preview cache when the expanded message shows the row
    // (the same path and 2 MB cap the thumbnails use), so the long-press can
    // start the drag at once: a drag cannot wait for a download.
    val dragFile = rememberAttachmentPreviewFile(vm, a)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.card)
                .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                .attachmentDragSource(a, dragFile)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AttachFile, null, tint = palette.textTertiary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name ?: stringResource(R.string.mail_attachment), fontSize = 14.sp, color = palette.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        a.mimeType?.takeIf { it.isNotBlank() },
                        a.sizeBytes?.let { humanBytes(it) },
                    ).joinToString(" · ").ifBlank { stringResource(R.string.mail_attachment_type_unknown) },
                    fontSize = 12.sp, color = palette.textSecondary,
                )
            }
            if (busy) {
                Text(stringResource(R.string.mail_common_saving), fontSize = 12.sp, color = palette.textTertiary)
            } else {
                Icon(Icons.Rounded.Download, stringResource(R.string.mail_attachment_download), tint = palette.primarySolid,
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
 *
 * Short windows (a phone in landscape): every host of this content is a
 * [MailSheet], whose column scrolls and which opens fully expanded there, so
 * the "Open in browser" button and Cancel stay reachable. It deliberately adds
 * no scroll of its own -- a vertical scroll nested in the sheet's would be
 * measured with an unbounded height and throw.
 */
@Composable
internal fun UnsubscribeContent(
    sender: String?,
    url: String,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalPalette.current
    val host = com.grapsee.grapmail.ui.components.urlHost(url) ?: stringResource(R.string.mail_unsub_host_fallback)
    SheetTitle(stringResource(R.string.mail_subs_unsubscribe), "gm-sheets-unsub")
    Text(
        if (sender != null) stringResource(R.string.mail_unsub_offer_about, sender, host)
        else stringResource(R.string.mail_unsub_offer, host),
        fontSize = 13.sp, color = palette.textSecondary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
    Text(
        stringResource(R.string.mail_unsub_risk, host),
        fontSize = 13.sp, color = palette.textSecondary, lineHeight = 19.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
    Spacer(Modifier.height(10.dp))
    com.grapsee.grapmail.ui.components.GradientButton(
        stringResource(R.string.mail_unsub_open_browser), onClick = onOpen,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    Text(
        stringResource(R.string.mail_common_cancel), fontSize = 14.sp, color = palette.textTertiary,
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
    context.getString(R.string.mail_open_external_done)
} catch (e: Exception) {
    // ActivityNotFoundException is the expected one (no browser installed, or a
    // work profile that blocks it); anything else from an Intent construction is
    // still just "this device could not open it", and reporting the exception
    // text would show the user a framework message they cannot act on.
    context.getString(R.string.mail_open_external_none)
}
