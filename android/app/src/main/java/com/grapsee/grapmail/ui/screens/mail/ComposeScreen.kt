package com.grapsee.grapmail.ui.screens.mail

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.MailAttachmentDto
import com.grapsee.grapmail.logic.MailboxViewModel
import com.grapsee.grapmail.ui.components.GMLayout
import com.grapsee.grapmail.ui.components.GMMotion
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.OutlineButton
import com.grapsee.grapmail.ui.components.gmIsNarrowWindow
import com.grapsee.grapmail.ui.components.gmMaxWidth
import com.grapsee.grapmail.ui.components.rememberMotionReduced
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.ensureActive
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
 *
 * ── DRAG AND DROP ───────────────────────────────────────────────────────────
 * Files dropped from another app (split screen, DeX, ChromeOS, a tablet's
 * taskbar) go through `uploadOne`, the picker's own path — the same 25 MB cap,
 * the same 20-attachment cap, the same sentences. The drop's read grant is asked
 * for while the drop is being handled (it dies with the event otherwise) and
 * released once the bytes are copied and uploaded. Dropped text is not a file:
 * it goes into the body at the caret, or above the signature when the body was
 * never focused. Only `content://` items are read; `file://` and anything that
 * names one of this app's own providers are refused, so a drag can never make
 * the app attach its own private files.
 *
 * ── ROOM ────────────────────────────────────────────────────────────────────
 * The top bar (close, title, send later, Send) never scrolls: it sits above the
 * keyboard (the screen is padded by the IME inset; system bars and cutouts are
 * padded by the root, MainActivity), so Send is always one tap away, in a
 * landscape phone with the keyboard up too. Everything under it is one column
 * that scrolls as a whole when the window is short or the font is large (the
 * body then gets a fixed-height box instead of the leftover height). On a wide
 * window (tablet, desktop / Chromebook / DeX window, wide split screen) the
 * form keeps [GMLayout.Compose] and is centred. File chips lay out in as many
 * columns as fit (ReaderAdaptive.kt), the bottom actions stack in a very narrow
 * window or at very large font, and the emoji row scrolls sideways.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ComposeScreen(
    vm: MailboxViewModel,
    profile: com.grapsee.grapmail.logic.ProfileViewModel,
    e2e: com.grapsee.grapmail.logic.E2eViewModel,
    replyId: String = "",
    mode: String = "reply",
    onBack: () -> Unit,
    initialRecipient: String = "",
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state by vm.compose.collectAsStateWithLifecycle()
    val waitingForDraft = mode == "draft" && !state.accepted && state.draftId != replyId
    val profileState by profile.state.collectAsStateWithLifecycle()
    val e2eState by e2e.state.collectAsStateWithLifecycle()
    val attached = remember { mutableStateListOf<MailAttachmentDto>() }
    // An uploaded image waiting for the editor to place it, and the last
    // compatibility report the person chose to send past.
    var pendingImage by remember { mutableStateOf<InlineImage?>(null) }
    var compatFindings by remember { mutableStateOf<List<com.grapsee.grapmail.data.ComposeFindingDto>?>(null) }
    var acceptedHtml by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var preparingSend by remember { mutableStateOf(false) }
    // Sentences the non-composable callbacks below report, read once here.
    val noticeRemoveAttachments = stringResource(R.string.mail_compose_remove_attachments_for_draft)
    val noticeMaxAttachments = stringResource(R.string.mail_compose_max_attachments)
    val noticeMaxAttachmentsImages = stringResource(R.string.mail_compose_max_attachments_images)
    val noticeEncryptedNoAttachments = stringResource(R.string.mail_compose_encrypted_no_attachments)
    val noticeDirectoryUnavailable = stringResource(R.string.mail_compose_directory_unavailable)
    val noticeNoPublishedKey = stringResource(R.string.mail_compose_no_published_key)
    val noticeChangedWhileSealing = stringResource(R.string.mail_compose_changed_while_sealing)
    val noticeFileUnreadable = stringResource(R.string.mail_compose_file_unreadable)
    // Drag and drop: whether a drag the screen could take is in progress
    // anywhere (the zone is shown), and whether it is over this screen (the
    // zone is emphasised).
    var dragActive by remember { mutableStateOf(false) }
    var dragOver by remember { mutableStateOf(false) }
    // The plain body field's own value, so a text drop knows where the caret
    // is. Kept exactly the way BasicTextField's String overload keeps it.
    var bodyField by remember { mutableStateOf(TextFieldValue("")) }
    var bodyHadCursor by remember { mutableStateOf(false) }
    // Text dropped while the formatting editor is showing, waiting for it.
    var droppedText by remember { mutableStateOf<DroppedText?>(null) }
    val saveDraft: (() -> Unit) -> Unit = { onSaved ->
        if (!uploading && !preparingSend && !state.sending && !state.accepted) {
            if (attached.isEmpty()) vm.saveDraft(onSaved)
            else notice = noticeRemoveAttachments
        }
    }
    val close = {
        if (!state.sending && !uploading && !preparingSend) {
            // The body alone does not qualify: the seeded signature is not
            // the person's content. An edit, a recipient, or a subject is.
            if (!waitingForDraft && !state.accepted && (attached.isNotEmpty() ||
                    state.userEdited ||
                    listOf(state.to, state.cc, state.subject).any { it.isNotBlank() })
            ) {
                saveDraft(onBack)
            } else {
                onBack()
            }
        }
    }
    BackHandler(onBack = close)
    var showCc by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var showFormatting by remember { mutableStateOf(false) }
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
    LaunchedEffect(Unit) { e2e.load(includeDirectory = false) }
    LaunchedEffect(vm) { vm.loadAccount() }

    // A prepared compose (reply / reply-all / forward from the thread) builds
    // its fields from the full server row; a blank one gets the signature
    // appended as editable text. Both run once per opened target, so a
    // rotation does not double-append what the person already edited.
    var started by rememberSaveable { mutableStateOf(false) }
    var prepared by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(replyId, signature) {
        if (!started) {
            vm.resetCompose(initialRecipient)
            started = true
        }
        if (prepared) return@LaunchedEffect
        if (replyId.isNotBlank()) {
            prepared = true
            vm.prepareReply(replyId, mode, signature)
        } else if (signature.isNotBlank()) {
            vm.composeSeed { s ->
                if (s.body.isBlank()) s.copy(body = "\n\n-- \n$signature") else s
            }
            prepared = true
        }
    }

    // People the To field can offer. Derived, not an address book — see
    // MailboxViewModel.people for why that distinction is stated rather than
    // papered over.
    var contactCandidates by remember(vm) { mutableStateOf<List<MailboxViewModel.Person>>(emptyList()) }
    LaunchedEffect(vm) { contactCandidates = vm.people() }
    val people = remember(contactCandidates, state.to) {
        val needle = state.to.trim()
        if (needle.isEmpty()) return@remember emptyList()
        val already = splitTo(state.to)
        contactCandidates.asSequence().filter { p ->
            (p.address.contains(needle, ignoreCase = true) ||
                p.name?.contains(needle, ignoreCase = true) == true) &&
                already.none { it.equals(p.address, ignoreCase = true) }
        }.take(5).toList()
    }

    val attachFile: (Uri?) -> Unit = { uri ->
        if (uri != null && !uploading && attached.size < 20 && !state.sending && !state.accepted) {
            uploading = true
            scope.launch {
                try {
                    uploadOne(vm, context, uri, attached) { notice = it }
                } finally {
                    uploading = false
                }
            }
        } else if (attached.size >= 20) {
            notice = noticeMaxAttachments
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        attachFile(uri)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        attachFile(uri)
    }
    // "Image" in the formatting toolbar: the picture goes inside the message.
    val pickInlineImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null || uploading || state.sending || state.accepted) return@rememberLauncherForActivityResult
        if (attached.size >= 20) { notice = noticeMaxAttachmentsImages; return@rememberLauncherForActivityResult }
        uploading = true
        scope.launch {
            try {
                val placed = uploadInlineImage(vm, context, uri) { notice = it }
                if (placed != null) {
                    attached.add(placed.first)
                    if (placed.second.contentId.isNotEmpty()) pendingImage = placed.second
                }
            } finally {
                uploading = false
            }
        }
    }

    // Dropped text: into the body at the caret, never as an attachment.
    val insertDroppedText: (String) -> Unit = { raw ->
        val text = raw.replace("\r\n", "\n")
        if (text.isNotEmpty()) {
            if (showFormatting || state.bodyHtml != null) {
                droppedText = DroppedText(System.nanoTime(), text)
            } else {
                val current = vm.compose.value.body
                val start: Int
                val end: Int
                if (bodyHadCursor) {
                    start = bodyField.selection.min.coerceIn(0, current.length)
                    end = bodyField.selection.max.coerceIn(start, current.length)
                } else {
                    start = droppedTextPosition(current)
                    end = start
                }
                val piece = if (start == current.length && current.isNotEmpty() && !current.endsWith("\n")) "\n$text" else text
                val next = current.substring(0, start) + piece + current.substring(end)
                bodyField = TextFieldValue(next, TextRange(start + piece.length))
                vm.composeUpdate { s -> s.copy(body = next) }
            }
        }
    }
    val canTakeDrop = { !waitingForDraft && !state.sending && !state.accepted && !preparingSend }
    // One handler for both drop surfaces: the screen (Compose) and the
    // formatting editor (a WebView, which receives its own drag events).
    val handleDrop: (android.view.DragEvent) -> Boolean = handleDrop@{ event ->
        if (!canTakeDrop()) return@handleDrop false
        val clip = event.clipData ?: return@handleDrop false
        val uris = mutableListOf<Uri>()
        val texts = mutableListOf<String>()
        var unsupported = false
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val uri: Uri? = item.uri
            val itemText: String? = item.text?.toString()
            when {
                uri != null && uri.scheme == android.content.ContentResolver.SCHEME_CONTENT ->
                    if (uri.authority.orEmpty().startsWith(context.packageName)) unsupported = true else uris += uri
                uri != null && uri.scheme.orEmpty().lowercase() in setOf("http", "https", "mailto") ->
                    texts += itemText?.takeIf { it.isNotBlank() } ?: uri.toString()
                uri == null && !itemText.isNullOrEmpty() -> texts += itemText
                else -> unsupported = true
            }
        }
        if (texts.isNotEmpty()) insertDroppedText(texts.joinToString("\n"))
        val files = if (uploading) emptyList() else uris
        if (files.isNotEmpty()) {
            // Asked for now, while the event is live; a grant requested later
            // does not exist. Null means the source gave none — the read then
            // fails in uploadOne with the picker's own "could not be read".
            val permissions = context.findActivity()?.let { activity ->
                runCatching { activity.requestDragAndDropPermissions(event) }.getOrNull()
            }
            uploading = true
            scope.launch {
                try {
                    for (uri in files) {
                        if (attached.size >= 20) { notice = noticeMaxAttachments; break }
                        uploadOne(vm, context, uri, attached) { notice = it }
                    }
                } finally {
                    permissions?.release()
                    uploading = false
                }
            }
        } else if (unsupported) {
            notice = noticeFileUnreadable
        }
        files.isNotEmpty() || texts.isNotEmpty()
    }
    val currentDrop by rememberUpdatedState(handleDrop)
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) { dragActive = true }
            override fun onEntered(event: DragAndDropEvent) { dragOver = true }
            override fun onExited(event: DragAndDropEvent) { dragOver = false }
            override fun onEnded(event: DragAndDropEvent) { dragActive = false; dragOver = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragOver = false
                return currentDrop(event.toAndroidDragEvent())
            }
        }
    }

    // Send later: the time the person picked rides through exactly the checks
    // Send makes (uploads finished, the HTML compatibility check, sealing), so a
    // scheduled message is validated like a sent one; only the last call
    // differs. Null is a normal Send. Every entry point sets it (tapSend clears
    // it), so a schedule interrupted by the compatibility dialog can never turn
    // a later plain Send into a scheduled one.
    var pendingScheduleAt by remember { mutableStateOf<java.time.Instant?>(null) }
    // True from a send-later tap until its receipt is handled. A new composer
    // starts false, so a stale "scheduled" state can never close it.
    var scheduleRequested by rememberSaveable { mutableStateOf(false) }
    val deliver: (String?, List<String>) -> Unit = { envelope, ids ->
        val at = pendingScheduleAt
        pendingScheduleAt = null
        if (at == null) vm.send(envelope, ids) else vm.scheduleSend(at, envelope, ids)
    }
    val sendMessage: () -> Unit = sendMessage@{
        if (uploading || preparingSend || state.sending || state.accepted) return@sendMessage
        // An image removed from the body after it was placed is not sent at all.
        val html = state.bodyHtml
        val attachmentIds = attached
            .filter { a -> a.contentId == null || html?.contains("cid:${a.contentId}") == true }
            .map { it.id }
        if (!encryptOn) {
            // Check designed HTML for what mail apps drop or break, once per version
            // of the body; "Send anyway" accepts that version.
            if (html != null && html != acceptedHtml) {
                preparingSend = true
                scope.launch {
                    val report = try {
                        com.grapsee.grapmail.data.SessionStore.token(context)?.let {
                            kotlinx.coroutines.withTimeoutOrNull(6000) { com.grapsee.grapmail.data.Api.composeCheck(it, html) }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    } finally {
                        preparingSend = false
                    }
                    val problems = report?.findings.orEmpty().filter { it.severity != "info" }
                    if (problems.isEmpty()) {
                        acceptedHtml = html
                        deliver(null, attachmentIds)
                    } else {
                        compatFindings = report?.findings.orEmpty()
                    }
                }
                return@sendMessage
            }
            deliver(null, attachmentIds)
            return@sendMessage
        }
        if (attachmentIds.isNotEmpty()) {
            notice = noticeEncryptedNoAttachments
            return@sendMessage
        }
        val draft = vm.compose.value
        preparingSend = true
        scope.launch {
            try {
                val recipients = (splitTo(draft.to) + splitTo(draft.cc) + splitTo(draft.bcc)).distinct()
                val plan = vm.encryptionPlan(recipients)
                when {
                    plan == null -> notice = noticeDirectoryUnavailable
                    plan.first != "seal" -> notice = noticeNoPublishedKey
                    else -> {
                        val envelope = e2e.seal(draft.body, plan.second)
                        val current = vm.compose.value
                        if (current.to != draft.to || current.cc != draft.cc || current.bcc != draft.bcc ||
                            current.body != draft.body || attached.isNotEmpty()
                        ) {
                            notice = noticeChangedWhileSealing
                        } else if (envelope != null) {
                            deliver(envelope, emptyList())
                        }
                    }
                }
            } finally {
                preparingSend = false
            }
        }
    }
    compatFindings?.let { findings ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { compatFindings = null; pendingScheduleAt = null },
            title = { Text(stringResource(R.string.mail_compose_check_title)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text(stringResource(R.string.mail_compose_check_body),
                        fontSize = 14.sp, color = palette.textSecondary)
                    findings.forEach { f ->
                        val label = when (f.severity) {
                            "removed" -> stringResource(R.string.mail_compose_finding_removed)
                            "warning" -> stringResource(R.string.mail_compose_finding_may_break)
                            else -> stringResource(R.string.mail_compose_finding_note)
                        }
                        Text(if (f.clients.isNotEmpty()) stringResource(R.string.mail_compose_finding_clients, label, f.message, f.clients.joinToString())
                            else stringResource(R.string.mail_compose_finding, label, f.message),
                            fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                            color = if (f.severity == "info") palette.textSecondary else palette.textPrimary)
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    acceptedHtml = state.bodyHtml
                    compatFindings = null
                    sendMessage()
                }) { Text(stringResource(R.string.mail_compose_send_anyway)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { compatFindings = null; pendingScheduleAt = null }) { Text(stringResource(R.string.mail_compose_keep_editing)) }
            },
        )
    }
    val sendEnabled = !waitingForDraft && !state.sending && !state.accepted && !preparingSend && !uploading && !state.pendingSend

    // Send motion (ui/components/Motion.kt). Visual only: what Send does, and
    // the progress the screen already shows (the undo bar, "Sending…"), are
    // unchanged. The plane nudges forward and dims while a send is under way
    // (220 ms), and the send controls shake once (280 ms) when it fails.
    val reducedMotion = rememberMotionReduced()
    val sendBusy = state.pendingSend || state.sending || preparingSend
    val sendNudge by animateFloatAsState(
        targetValue = if (sendBusy) 1f else 0f,
        animationSpec = GMMotion.tween(reducedMotion, GMMotion.MediumMs,
            if (sendBusy) GMMotion.EmphasizedDecelerate else GMMotion.Standard),
        label = "sendNudge",
    )
    val sendForward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val sendShake = remember { Animatable(0f) }
    var lastSendTap by remember { mutableLongStateOf(0L) }
    var wasSendBusy by remember { mutableStateOf(false) }
    val sendFailed = !state.accepted && (state.resultStatus == "failed" || state.error != null)
    val sendFailedNow by rememberUpdatedState(sendFailed)
    val shakeSend: suspend () -> Unit = {
        sendShake.snapTo(0f)
        sendShake.animateTo(0f, keyframes {
            durationMillis = 280
            -6f at 40
            6f at 100
            -4f at 160
            3f at 220
        })
    }
    // A send that was under way and ended refused.
    LaunchedEffect(sendBusy) {
        val ended = wasSendBusy && !sendBusy
        wasSendBusy = sendBusy
        if (ended && !reducedMotion) {
            // A beat for the verdict to land (and for a hand-off between the
            // compatibility check and the undo window, which is not an end).
            kotlinx.coroutines.delay(120)
            if (sendFailedNow) shakeSend()
        }
    }
    // A tap refused at once (no recipient, for one).
    LaunchedEffect(state.error) {
        if (state.error != null && !sendBusy && !reducedMotion &&
            android.os.SystemClock.uptimeMillis() - lastSendTap < 1_000
        ) shakeSend()
    }
    val tapSend: () -> Unit = {
        lastSendTap = android.os.SystemClock.uptimeMillis()
        pendingScheduleAt = null
        scheduleRequested = false
        sendMessage()
    }

    // ── send later ──────────────────────────────────────────────────────────
    // The clock beside Send opens SendLaterSheet.kt's flow; a picked time goes
    // through sendMessage like Send. When the server answers `scheduled` the
    // composer closes and SendLaterSnackbar.kt shows the receipt with Undo/View.
    var sendLaterOpen by rememberSaveable { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val needRecipient = stringResource(R.string.core_compose_need_recipient)
    val openSendLater: () -> Unit = {
        // The same first refusal Send makes, before a time is chosen for nothing.
        if (splitTo(state.to).isEmpty()) notice = needRecipient
        else {
            keyboard?.hide()
            sendLaterOpen = true
        }
    }
    val tapSchedule: (java.time.Instant) -> Unit = { at ->
        sendLaterOpen = false
        lastSendTap = android.os.SystemClock.uptimeMillis()
        pendingScheduleAt = at
        scheduleRequested = true
        sendMessage()
    }
    if (sendLaterOpen) {
        SendLaterFlow(onClose = { sendLaterOpen = false }, onSchedule = tapSchedule)
    }
    LaunchedEffect(state.resultStatus, state.scheduledId) {
        // Read live, not the captured frame: only this composer's own request.
        val live = vm.compose.value
        val id = live.scheduledId
        if (!scheduleRequested || live.resultStatus != "scheduled" || id == null) return@LaunchedEffect
        scheduleRequested = false
        val at = com.grapsee.grapmail.data.MailSchedule.parse(live.scheduledFor)
        val whenText = at?.let { sendLaterWhen(context, it) } ?: live.scheduledFor.orEmpty()
        context.findActivity()?.let { activity ->
            SendLaterReceipt.show(activity, id, context.getString(R.string.compose_send_later_scheduled_for, whenText), vm)
        }
        onBack()
    }

    // F12: a reply the server accepted is a thread now awaiting an answer.
    // Recorded once, at the acceptance itself, so the follow-up center counts
    // something that actually happened instead of something the person must
    // remember to log.
    val followUpContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(state.accepted) {
        if (state.accepted && mode == "reply" && replyId.isNotBlank()) {
            com.grapsee.grapmail.logic.MailFeaturePrefs(followUpContext).markAwaitingReply(replyId)
        }
    }
    // The form's width on a wide window, shared by the bars above it so the
    // close and Send controls line up with the fields.
    val formWidth = Modifier.gmMaxWidth(GMLayout.Compose).fillMaxWidth()
    Box(
        Modifier.fillMaxSize().background(palette.bg).statusBarsPadding().imePadding()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    canTakeDrop() && !uploading &&
                        (event.toAndroidDragEvent().clipDescription?.mimeTypeCount ?: 0) > 0
                },
                target = dropTarget,
            ),
    ) {
        Column(Modifier.fillMaxSize()) {
            // The undo-send window: the message is held on this device for five
            // seconds, and this bar is its whole story — nothing has been sent,
            // nothing is claimed sent, and the word names exactly what the tap
            // does. After the window the normal send flow takes over and the
            // server's own answer becomes the receipt.
            if (state.pendingSend) {
                Row(
                    formWidth.padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(10.dp)).background(palette.primarySolid)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.mail_compose_sending), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = palette.onPrimary, modifier = Modifier.weight(1f))
                    Text(
                        stringResource(R.string.mail_common_undo),
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = palette.onPrimary,
                        modifier = Modifier.clip(CircleShape).clickable { vm.undoSend() }
                            .padding(horizontal = 8.dp, vertical = 4.dp).testTag("mail-undo-send"),
                    )
                }
            }
            Row(
                formWidth.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Close, stringResource(R.string.mail_compose_discard), tint = palette.textPrimary,
                    modifier = Modifier.size(22.dp).clip(CircleShape).clickable {
                        // Discarding is a choice, not an accident: the draft is saved
                        // first, because losing half-written text is the other way a
                        // mail app loses something.
                        close()
                    })
                Spacer(Modifier.width(12.dp))
                // The title gives way first (one line, ellipsised) so the send
                // controls keep their place at any font size and width.
                Text(stringResource(R.string.mail_compose), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ScheduleSendButton(enabled = sendEnabled, onClick = openSendLater, tag = "mail-send-later")
                Spacer(Modifier.width(14.dp))
                Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.mail_send), tint = palette.primarySolid,
                    modifier = Modifier.size(22.dp).clip(CircleShape)
                        .clickable(enabled = sendEnabled, onClick = tapSend)
                        .padding(2.dp).testTag("mail-send")
                        // Last, so only the glyph moves; the touch target stays put.
                        .graphicsLayer {
                            translationX = (sendForward * sendNudge * 5f + sendShake.value).dp.toPx()
                            alpha = 1f - 0.55f * sendNudge
                        })
            }

            if (waitingForDraft) {
                Column(formWidth.padding(20.dp)) {
                    val error = state.error
                    if (error == null) {
                        Text(stringResource(R.string.mail_compose_opening_draft), color = palette.textSecondary)
                    } else {
                        Text(error, color = palette.textPrimary)
                        androidx.compose.material3.TextButton(onClick = { vm.prepareReply(replyId, mode, signature) }) {
                            Text(stringResource(R.string.mail_common_try_again))
                        }
                    }
                }
                return@Column
            }

            val configuration = LocalConfiguration.current
            val density = LocalDensity.current
            val imeHeight = with(density) { WindowInsets.ime.getBottom(density).toDp() }
            val shortEditor = configuration.screenHeightDp.dp - imeHeight < 440.dp ||
                density.fontScale > 1.3f
            // A very narrow window or a very large font: the bottom actions
            // stack instead of squeezing three controls into one line.
            val stackActions = gmIsNarrowWindow() || density.fontScale > 1.5f
            // The scroll spans the window (a mouse wheel or a drag anywhere
            // scrolls it); the fields inside keep the compose width.
            val form = Modifier.fillMaxSize().let {
                if (shortEditor) it.verticalScroll(rememberScrollState()) else it
            }.gmMaxWidth(GMLayout.Compose)
            Column(form.padding(horizontal = 16.dp)) {

                FieldRow(label = stringResource(R.string.mail_compose_to), value = state.to,
                    placeholder = stringResource(R.string.mail_compose_to_placeholder),
                    onValue = { vm.composeUpdate { s -> s.copy(to = it) } },
                    tag = "mail-to",
                    trailing = {
                        Icon(Icons.Rounded.PersonAddAlt1, stringResource(R.string.mail_compose_add_recipient), tint = palette.primarySolid,
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
                                // Shares the line with the name instead of pushing it out.
                                Text(p.address, fontSize = 12.sp, color = palette.textTertiary, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false))
                            }
                        }
                    }
                }

                if (showCc) {
                    FieldRow(label = stringResource(R.string.mail_compose_cc), value = state.cc,
                        placeholder = stringResource(R.string.mail_compose_addresses),
                        onValue = { vm.composeUpdate { s -> s.copy(cc = it) } }, tag = "mail-cc")
                    FieldRow(label = stringResource(R.string.mail_compose_bcc), value = state.bcc,
                        placeholder = stringResource(R.string.mail_compose_addresses),
                        onValue = { vm.composeUpdate { s -> s.copy(bcc = it) } }, tag = "mail-bcc")
                }

                Divider(palette)

                FieldRow(label = stringResource(R.string.mail_compose_subject), value = state.subject, placeholder = stringResource(R.string.mail_compose_subject_placeholder),
                    onValue = { vm.composeUpdate { s -> s.copy(subject = it) } }, tag = "mail-subject")

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(700)
                    com.grapsee.grapmail.logic.MailFeaturePrefs.consumeStagedComposeInsert()?.let { staged ->
                        if (staged.isNotBlank()) {
                            vm.composeUpdate { s ->
                                s.copy(body = if (s.body.isBlank()) staged else s.body + "\n\n" + staged)
                            }
                        }
                    }
                }

                com.grapsee.grapmail.ui.mailfeatures.MailComposeTools(
                    getBody = { state.body },
                    setBody = { b -> vm.composeUpdate { s -> s.copy(body = b) } },
                )

                // Per-message read receipt request. Not offered for encrypted mail
                // (the server ignores the flag for sealed sends as well).
                var requestReceipt by remember(vm) { mutableStateOf(vm.requestReadReceipt) }
                if (!encryptOn) {
                    com.grapsee.grapmail.ui.mailfeatures.RequestReceiptToggle(requestReceipt) {
                        requestReceipt = it; vm.requestReadReceipt = it
                    }
                }
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
                        Text(if (encryptOn) stringResource(R.string.mail_compose_encrypting)
                            else stringResource(R.string.mail_compose_encrypt),
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
                        Text(stringResource(R.string.mail_compose_unlock_to_encrypt),
                            fontSize = 12.sp, color = palette.textTertiary)
                    } else if (encryptOn) {
                        Text(stringResource(R.string.mail_compose_encrypt_note),
                            fontSize = 12.sp, color = palette.textTertiary)
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // The growth lives on a Box the field fills, not on the field's
                // own modifier: a weighted child beside the scroll floor proved
                // fragile in frame 76's photograph (the bar floated mid-screen).
                // The Box takes the leftover height, unconditionally; the text
                // paints inside it at the same insets as before.
                androidx.compose.material3.TextButton(onClick = {
                    if (showFormatting || state.bodyHtml != null) {
                        showFormatting = false
                        vm.composeUpdate { it.copy(bodyHtml = null) }
                    } else showFormatting = true
                }) {
                    Text(if (showFormatting || state.bodyHtml != null) stringResource(R.string.mail_compose_plain_text)
                        else stringResource(R.string.mail_compose_format))
                }
                Box(if (shortEditor) Modifier.height(176.dp) else Modifier.weight(1f)) {
                    if (showFormatting || state.bodyHtml != null) {
                        ComposeFormattingEditor(
                            state.body, state.bodyHtml,
                            onChange = { text, html -> vm.composeUpdate { it.copy(body = text, bodyHtml = html) } },
                            modifier = Modifier.fillMaxSize().testTag("mail-body-field"),
                            pendingImage = pendingImage,
                            onImageInserted = { pendingImage = null },
                            onPickImage = if (encryptOn) null else ({ pickInlineImage.launch("image/*") }),
                            droppedText = droppedText,
                            onTextInserted = { droppedText = null },
                            onDragDrop = handleDrop,
                            onDragHover = { dragOver = it },
                        )
                    } else {
                        // BasicTextField's String overload, written out so the caret
                        // is readable by a text drop; behaviour is the same.
                        val bodyValue = bodyField.copy(text = state.body)
                        SideEffect {
                            if (bodyValue.selection != bodyField.selection || bodyValue.composition != bodyField.composition) {
                                bodyField = bodyValue
                            }
                        }
                        var lastBodyText by remember(state.body) { mutableStateOf(state.body) }
                        BasicTextField(
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalPalette.current.cursor),
                        value = bodyValue,
                        onValueChange = { next ->
                            bodyField = next
                            val changed = lastBodyText != next.text
                            lastBodyText = next.text
                            if (changed) vm.composeUpdate { s -> s.copy(body = next.text) }
                        },
                        // The tag names the signed state because the walk needs a
                        // settled marker that is not the field's text: the body's
                        // uiautomator text is the WHOLE value ("-- \\nSent with
                        // GrapMail"), and an exact-match wait on the signature alone
                        // could never fire. A testTag flips with the signature landing
                        // -- the same mechanism the bottom sheets are photographed by.
                        modifier = Modifier.fillMaxSize()
                            .onFocusChanged { if (it.isFocused) bodyHadCursor = true }
                            .testTag(if (signature.isNotBlank() && state.body.contains(signature))
                                "gm-signed-body" else "mail-body-field")
                            .padding(vertical = 14.dp),
                        textStyle = TextStyle(fontSize = 15.sp, color = palette.textPrimary, lineHeight = 22.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        decorationBox = { inner ->
                            Box {
                                if (state.body.isEmpty()) {
                                    Text(stringResource(R.string.mail_compose_body_placeholder), fontSize = 15.sp, color = palette.textTertiary)
                                }
                                inner()
                            }
                        },
                    )
                    }
                }

                // Images placed inside the message are shown there, not as file chips.
                val files = attached.filter { it.contentId == null }
                // Chips arrive with a fade and scale, and leave by shrinking away.
                // As many columns as the form's width fits (one on a phone).
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val chipColumns = attachmentGridColumns(maxWidth, LocalDensity.current.fontScale)
                    AttachmentGrid(columns = chipColumns, modifier = Modifier.fillMaxWidth()) {
                        AnimatedAttachmentChips(files, reducedMotion) { a ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(10.dp)).background(palette.softFill)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.AttachFile, null, tint = palette.textSecondary,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(a.name ?: stringResource(R.string.mail_attachment), fontSize = 13.sp, color = palette.textPrimary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                a.sizeBytes?.let { Text(humanBytes(it), fontSize = 12.sp, color = palette.textTertiary, maxLines = 1) }
                                androidx.compose.material3.IconButton(
                                    onClick = { attached.remove(a) },
                                    enabled = !state.sending && !state.accepted && !preparingSend,
                                ) {
                                    Icon(Icons.Rounded.Close, a.name?.let { stringResource(R.string.mail_compose_remove_named, it) }
                                        ?: stringResource(R.string.mail_compose_remove_attachment))
                                }
                            }
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

                // The autosave's own quiet line, deliberately NOT the receipt: a
                // background save is not an event the person acted on, so it draws
                // smaller and duller than anything the buttons produce. It yields
                // to the receipt entirely when one exists — the send verdict is
                // the sentence this area exists for.
                if (state.resultStatus == null && state.error != null) {
                    Text(state.error.orEmpty(), color = palette.error, fontSize = 12.sp)
                }
                if (state.resultStatus == null && state.error == null) {
                    val autosaveNote = state.autosaveNote
                    when {
                        state.autosaving -> {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.mail_compose_saving_draft), fontSize = 11.sp, color = palette.textTertiary)
                        }
                        autosaveNote != null -> {
                            Spacer(Modifier.height(8.dp))
                            Text(autosaveNote, fontSize = 11.sp, color = palette.textTertiary)
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 14.dp)) {
                    Icon(Icons.Rounded.AttachFile, stringResource(R.string.mail_compose_attach_file), tint = palette.textSecondary,
                        modifier = Modifier.size(20.dp).clickable { pickFile.launch(arrayOf("*/*")) })
                    Icon(Icons.Rounded.Image, stringResource(R.string.mail_compose_attach_image), tint = palette.textSecondary,
                        modifier = Modifier.size(20.dp).clickable { pickImage.launch("image/*") })
                    if (!showFormatting && state.bodyHtml == null) Icon(Icons.Rounded.Link, stringResource(R.string.mail_compose_insert_link), tint = palette.textSecondary,
                        modifier = Modifier.size(20.dp).clickable {
                            vm.composeUpdate { s -> s.copy(body = if (s.body.isBlank()) "https://" else "${s.body}\n\nhttps://") }
                        })
                    if (!showFormatting && state.bodyHtml == null) Icon(Icons.Rounded.EmojiEmotions, stringResource(R.string.mail_compose_insert_emoji), tint = palette.textSecondary,
                        modifier = Modifier.size(20.dp).clickable { showEmoji = !showEmoji })
                }

                if (showEmoji && !showFormatting && state.bodyHtml == null) {
                    // Inserting a character, not opening a panel that goes nowhere.
                    // Scrolls sideways when large text makes the row wider than the window.
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("🙂", "👍", "🎉", "❤️", "😂", "🙏").forEach { e ->
                            Text(e, fontSize = 20.sp,
                                modifier = Modifier.clickable {
                                    vm.composeUpdate { s -> s.copy(body = s.body + e) }
                                })
                        }
                    }
                }

                val sendLabel = when {
                    state.sending && scheduleRequested -> stringResource(R.string.compose_send_later_scheduling)
                    state.sending -> stringResource(R.string.mail_compose_sending)
                    else -> stringResource(R.string.mail_send)
                }
                // Send later, beside Send: a full 48 dp target here.
                val sendLaterBottom: @Composable () -> Unit = {
                    androidx.compose.material3.IconButton(onClick = openSendLater, enabled = sendEnabled,
                        modifier = Modifier.testTag("mail-send-later-bottom")) {
                        Icon(Icons.Rounded.Schedule, stringResource(R.string.compose_send_later_button),
                            tint = if (sendEnabled) palette.primarySolid else palette.textTertiary)
                    }
                }
                if (stackActions) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlineButton(stringResource(R.string.mail_compose_save_draft), onClick = { saveDraft {} },
                            modifier = Modifier.fillMaxWidth(), fill = false)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            GradientButton(
                                sendLabel, onClick = tapSend,
                                enabled = sendEnabled, modifier = Modifier.weight(1f)
                                    .graphicsLayer { translationX = sendShake.value.dp.toPx() })
                            sendLaterBottom()
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlineButton(stringResource(R.string.mail_compose_save_draft), onClick = { saveDraft {} }, modifier = Modifier.weight(1f), fill = false)
                        GradientButton(
                            sendLabel, onClick = tapSend,
                            enabled = sendEnabled, modifier = Modifier.weight(1f)
                                .graphicsLayer { translationX = sendShake.value.dp.toPx() })
                        sendLaterBottom()
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
        // The drop zone: shown while a drag this screen can take is under way,
        // emphasised while it is over the screen.
        if (dragActive) {
            Box(
                Modifier.matchParentSize().padding(10.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.primarySolid.copy(alpha = if (dragOver) 0.16f else 0.07f))
                    .border(2.dp, if (dragOver) palette.primarySolid else palette.border, RoundedCornerShape(16.dp))
                    .testTag("mail-compose-drop-zone"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (dragOver) stringResource(R.string.mail_compose_drop_release)
                    else stringResource(R.string.mail_compose_drop_zone),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if (dragOver) palette.primarySolid else palette.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(palette.card)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * The top bar's send-later clock, drawn like the Send glyph beside it. TalkBack
 * reads "Schedule send, button, double-tap to choose when to send".
 */
@Composable
private fun ScheduleSendButton(enabled: Boolean, onClick: () -> Unit, tag: String) {
    val palette = LocalPalette.current
    Icon(
        Icons.Rounded.Schedule, stringResource(R.string.compose_send_later_button),
        tint = if (enabled) palette.primarySolid else palette.textTertiary,
        modifier = Modifier.size(26.dp).clip(CircleShape)
            .clickable(
                enabled = enabled,
                onClickLabel = stringResource(R.string.compose_send_later_button_action),
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            )
            .padding(2.dp).testTag(tag),
    )
}

/**
 * The file chips, animated in and out: a new chip fades in and scales up from
 * 92 % while its row opens (220 ms); a removed chip fades, scales down and its
 * row shrinks closed (220 ms). Removal itself is immediate — [items] is the
 * real list, and the leaving chip is only a picture of what was there, so a
 * send in the middle of the exit never carries the removed file.
 * With reduced motion the chips are drawn exactly as before.
 *
 * The chips are emitted as one flat, keyed list, so the caller can lay them
 * out in columns ([AttachmentGrid]) without a chip losing its animation state
 * when the column count changes or a neighbour leaves.
 */
@Composable
private fun AnimatedAttachmentChips(
    items: List<MailAttachmentDto>,
    reduced: Boolean,
    chip: @Composable (MailAttachmentDto) -> Unit,
) {
    if (reduced) {
        items.forEach { a -> chip(a) }
        return
    }
    // What is drawn: the live items, plus any just-removed chip still leaving,
    // kept at the place it had.
    var shown by remember { mutableStateOf(items) }
    val drawn = remember(items, shown) {
        val live = items.map { it.id }.toSet()
        val out = items.toMutableList()
        shown.forEachIndexed { index, old ->
            if (old.id !in live) out.add(index.coerceAtMost(out.size), old)
        }
        out.toList()
    }
    drawn.forEach { a ->
        key(a.id) {
            val present = items.any { it.id == a.id }
            val visibility = remember { MutableTransitionState(false) }
            LaunchedEffect(present) { visibility.targetState = present }
            AnimatedVisibility(
                visibleState = visibility,
                enter = fadeIn(tween(GMMotion.MediumMs, easing = GMMotion.Standard)) +
                    scaleIn(tween(GMMotion.MediumMs, easing = GMMotion.EmphasizedDecelerate), initialScale = 0.92f) +
                    expandVertically(tween(GMMotion.MediumMs, easing = GMMotion.Standard), expandFrom = Alignment.Top),
                exit = fadeOut(tween(GMMotion.ShortMs, easing = GMMotion.Standard)) +
                    scaleOut(tween(GMMotion.MediumMs, easing = GMMotion.EmphasizedAccelerate), targetScale = 0.92f) +
                    shrinkVertically(tween(GMMotion.MediumMs, easing = GMMotion.Standard), shrinkTowards = Alignment.Top),
                label = "attachmentChip",
            ) {
                chip(a)
            }
            if (!present && visibility.isIdle && !visibility.currentState) {
                LaunchedEffect(Unit) { shown = shown.filterNot { it.id == a.id } }
            }
        }
    }
    LaunchedEffect(drawn) { shown = drawn }
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
    val stackedLabel = LocalDensity.current.fontScale >= 1.2f
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        if (stackedLabel) {
            Text(label, fontSize = 14.sp, color = palette.textSecondary)
            Spacer(Modifier.height(6.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!stackedLabel) {
                Text(label, fontSize = 14.sp, color = palette.textSecondary, modifier = Modifier.width(56.dp))
            }
            BasicTextField(
                cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.cursor),
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
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
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
        "sent" -> stringResource(R.string.mail_receipt_sent) to palette.successInk
        // The status the live send route answers for a delivered message —
        // measured, not assumed: the walk's own self-send came back
        // "delivered". Without this arm it fell to the warn-ink fallback.
        "delivered" -> stringResource(R.string.mail_receipt_delivered) to palette.successInk
        "queued" -> stringResource(R.string.mail_receipt_queued) to palette.warnInk
        "failed" -> stringResource(R.string.mail_receipt_failed) to palette.error
        // The draft path answers with this status word, and without an arm for it a
        // successful save fell through to "Server reported: draft" in warn ink
        // beneath a line saying "Saved to Drafts". iOS already handles `draft`:
        // this is parity, and the headline stays the server's own word.
        "draft" -> stringResource(R.string.mail_receipt_draft) to palette.successInk
        else -> stringResource(R.string.mail_receipt_unconfirmed) to palette.warnInk
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
            Text(stringResource(R.string.mail_receipt_queued_note),
                fontSize = 12.sp, color = palette.textTertiary)
        }
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(error, fontSize = 13.sp, color = palette.error)
        }
    }
}

/**
 * Upload a picked image to be placed inside the message (`inline=1`), and build
 * the editor's preview: the picture scaled to at most 600 px wide, as a data URL.
 * The full image is what is uploaded and sent.
 *
 * Returns null when the image could not be used, with the reason reported. A
 * server that gave no Content-ID keeps the upload as an ordinary attachment.
 */
private suspend fun uploadInlineImage(
    vm: MailboxViewModel,
    context: android.content.Context,
    uri: Uri,
    report: (String) -> Unit,
): Pair<MailAttachmentDto, InlineImage>? {
    val maxBytes = 10 * 1024 * 1024
    val session = com.grapsee.grapmail.data.SessionStore.snapshot(context)
        ?: run { report(context.getString(R.string.mail_compose_sign_in_image)); return null }
    val temporary = java.io.File(context.cacheDir, "inline-${java.util.UUID.randomUUID()}")
    try {
        val prepared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val resolver = context.contentResolver
            val name = queryName(context, uri) ?: "image"
            val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: ""
            if (mime !in setOf("image/png", "image/jpeg", "image/gif", "image/webp")) return@withContext null
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
            if (bytes.isEmpty() || bytes.size > maxBytes) return@withContext Triple(name, mime, bytes)
            temporary.writeBytes(bytes)
            Triple(name, mime, bytes)
        } ?: run { report(context.getString(R.string.mail_compose_image_types)); return null }
        val (name, mime, bytes) = prepared
        if (bytes.isEmpty()) { report(context.getString(R.string.mail_compose_image_empty)); return null }
        if (bytes.size > maxBytes) { report(context.getString(R.string.mail_compose_image_too_big)); return null }
        val preview = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 600) sample *= 2
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
            val scaled = if (bitmap.width > 600)
                android.graphics.Bitmap.createScaledBitmap(bitmap, 600, (bitmap.height * 600f / bitmap.width).toInt().coerceAtLeast(1), true)
            else bitmap
            val out = java.io.ByteArrayOutputStream()
            val png = mime == "image/png" || mime == "image/gif"
            scaled.compress(if (png) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
            Pair("data:image/${if (png) "png" else "jpeg"};base64," +
                android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP),
                minOf(bounds.outWidth, 600))
        } ?: run { report(context.getString(R.string.mail_compose_image_unreadable)); return null }
        report(context.getString(R.string.mail_compose_uploading, name))
        val attachment = vm.uploadAttachment(temporary, name, mime, session.id, inline = true)
        val cid = attachment.contentId
        if (cid.isNullOrBlank()) {
            report(context.getString(R.string.mail_compose_inline_unsupported, name))
            return attachment to InlineImage("", "", "", 0)
        }
        report(context.getString(R.string.mail_compose_inserted, name))
        return attachment to InlineImage(cid, preview.first, name.substringBeforeLast('.'), preview.second)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        report(error.message ?: context.getString(R.string.mail_compose_image_insert_failed))
        return null
    } finally {
        temporary.delete()
    }
}

/**
 * Read the picked document's bytes and upload them.
 *
 * `OpenDocument` grants read access for this process only, so the bytes are read
 * here and handed on — a URI passed to a later screen would fail once the
 * permission died, which is the kind of bug that only appears a day later.
 */
private suspend fun uploadOne(
    vm: MailboxViewModel,
    context: android.content.Context,
    uri: Uri,
    into: androidx.compose.runtime.snapshots.SnapshotStateList<MailAttachmentDto>,
    report: (String) -> Unit,
) {
    val maxBytes = 25 * 1024 * 1024
    val session = com.grapsee.grapmail.data.SessionStore.snapshot(context)
        ?: run { report(context.getString(R.string.mail_compose_sign_in_file)); return }
    val temporary = java.io.File(context.cacheDir, "upload-${java.util.UUID.randomUUID()}")
    try {
        val (name, mime, size) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val resolver = context.contentResolver
            val name = queryName(context, uri) ?: uri.lastPathSegment ?: "attachment"
            val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
            val size = runCatching {
                val stream = resolver.openInputStream(uri) ?: return@runCatching null
                stream.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var remaining = maxBytes + 1
                        while (remaining > 0) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                        maxBytes + 1 - remaining
                    }
                }
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                null
            }
            Triple(name, mime, size)
        }
        if (size == null) { report(context.getString(R.string.mail_compose_file_unreadable)); return }
        if (size > maxBytes) { report(context.getString(R.string.mail_compose_file_too_big)); return }
        if (size == 0) { report(context.getString(R.string.mail_compose_file_empty)); return }
        report(context.getString(R.string.mail_compose_uploading, name))
        val attachment = vm.uploadAttachment(temporary, name, mime, session.id)
        into.add(attachment)
        report(context.getString(R.string.mail_compose_attached, attachment.name ?: name))
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        report(error.message ?: context.getString(R.string.mail_compose_file_attach_failed))
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            temporary.delete()
        }
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

/**
 * Where dropped text lands when the body was never focused: above the
 * signature delimiter the screen seeds ("-- " on its own line), so the text is
 * not signed off before it starts; at the end when there is no signature.
 */
private fun droppedTextPosition(body: String): Int {
    val signature = body.indexOf("\n-- \n")
    return when {
        signature < 0 -> body.length
        body.substring(0, signature).isBlank() -> 0
        else -> signature
    }
}

/** The Activity behind a Compose context, which may be wrapped (locale, theme). */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var current: android.content.Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return null
}
