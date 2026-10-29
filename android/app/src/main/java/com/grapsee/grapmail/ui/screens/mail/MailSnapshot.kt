package com.grapsee.grapmail.ui.screens.mail

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.MailAttachmentDto
import com.grapsee.grapmail.data.MailBox
import com.grapsee.grapmail.data.MailFilter
import com.grapsee.grapmail.data.MailLabelDto
import com.grapsee.grapmail.data.MailInboxResponse
import com.grapsee.grapmail.data.MailMessageDto
import com.grapsee.grapmail.data.MailStore
import com.grapsee.grapmail.data.MailMessageFullDto
import com.grapsee.grapmail.data.MailPersonDto
import com.grapsee.grapmail.data.MailSort
import com.grapsee.grapmail.logic.MailboxViewModel
import com.grapsee.grapmail.ui.components.MailGreeting
import com.grapsee.grapmail.ui.components.MailStateBlock
import com.grapsee.grapmail.ui.components.MessageRow
import com.grapsee.grapmail.ui.components.SelectionToolbar
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Test-only entry into the mailbox screens a read-only CI walk cannot reach.
 *
 * Every screen below needs a message to exist. CI signs up a throwaway account
 * on a mailbox the gateway has not provisioned, so the live path legitimately
 * shows loading, empty, failed and no-mailbox — and those are photographed from
 * the real screen. What needs mail to have arrived is rendered here instead,
 * from literals, with closures that do nothing, exactly like the security
 * sector's post-write confirmations.
 *
 * Nothing in the real flow passes the extra, and no screen here is reachable
 * without it. Each frame carries the `gm-snapshot` description: absence of an
 * error message is not evidence that the intended screen was on screen, and
 * eleven screenshots in the previous sector were photographs of a different view
 * layered over the stub. `contentDescription` rather than `testTag` because
 * uiautomator dumps do not carry test tags.
 */
@Composable
fun MailSnapshotScreen(name: String) {
    val palette = LocalPalette.current
    val app = LocalContext.current.applicationContext as Application
    // The screens under test are the real ones, so they need the real state
    // holder. It is constructed here, in the test-only host, and seeded from the
    // literals below — no live path can reach either half.
    val vm = remember { MailboxViewModel(app) }

    // The seeds run in an effect, not in composition. Writing a state flow while
    // composing is a side effect in the wrong place — it can schedule a
    // recomposition of the very code that performs it. The screens all read the
    // seeded flows, so they render their populated branch as soon as this lands,
    // and the walk waits for that settled state before it photographs.
    LaunchedEffect(name) {
        when (name) {
            "mail-thread" -> seedThread(vm, encrypted = false)
            "mail-thread-encrypted" -> seedThread(vm, encrypted = true)
            "mail-files", "mail-attachment-preview" -> seedCache()
            "mail-contacts", "mail-contact-detail" -> {
                // Contacts are derived from the mail the app has loaded, with no
                // contacts endpoint behind them, so the only way this screen can
                // be photographed populated is by loading the sample pages into
                // the same cache the live list writes. That is what the snapshot
                // host is for: the data stays here, and the derivation stays real.
                seedCache()
                vm.seedForSnapshot { it.copy(accountAddress = "amira@grapmail.com") }
            }
            "mail-settings" -> vm.seedForSnapshot {
                it.copy(
                    accountAddress = "amira@grapmail.com",
                    accountStatus = "active",
                    memberSince = "March 2026",
                )
            }
            "mail-screener" -> vm.seedScreenerForSnapshot(enabled = true, pending = 3)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .semantics { contentDescription = "gm-snapshot" }
            .testTag("gm-snapshot"),
    ) {
        when (name) {
            "mail-home" -> listFrame(rows, MailBox.Inbox, loading = false,
                failed = false, noMailbox = false)
            "mail-home-dark" -> listFrame(rows, MailBox.Inbox, loading = false,
                failed = false, noMailbox = false)
            "mail-drawer" -> drawerFrame()
            "mail-empty" -> listFrame(emptyList(), MailBox.Archive, loading = false,
                failed = false, noMailbox = false)
            "mail-loading" -> listFrame(emptyList(), MailBox.Inbox, loading = true,
                failed = false, noMailbox = false)
            "mail-load-failed" -> listFrame(emptyList(), MailBox.Inbox, loading = false,
                failed = true, noMailbox = false)
            "mail-no-mailbox" -> listFrame(emptyList(), MailBox.Inbox, loading = false,
                failed = false, noMailbox = true)
            "mail-search" -> searchFrame()
            "mail-selection" -> selectionFrame()
            "mail-sort-filter" -> sheetHost {
                SortFilterContent(MailSort.SenderAsc, MailFilter.Unread, 25, 148) { _, _ -> }
            }
            "mail-move-sheet" -> sheetHost { MoveContent { } }
            "mail-delete-sheet" -> sheetHost { PermanentDeleteContent(3, cancel = {}, confirm = {}) }
            "mail-assign-label-sheet" -> sheetHost { AssignLabelContent(sampleLabels) { } }
            "mail-snooze-sheet" -> sheetHost { SnoozeContent { } }
            "mail-labels" -> LabelsScreen(vm, onBack = {}, onCreate = {})
            "mail-label-create" -> LabelCreateScreen(vm, onDone = {})
            "mail-thread" -> ThreadScreen(vm, threadId = "t-m1", isThread = true,
                onBack = {}, onReply = { })
            "mail-thread-encrypted" -> ThreadScreen(vm, threadId = "t-m9", isThread = true,
                onBack = {}, onReply = { })
            "mail-compose" -> ComposeScreen(vm, onBack = {})
            "mail-send-receipt" -> sheetHost {
                Column(Modifier.padding(16.dp)) {
                    SendReceiptPreview("queued")
                    Spacer(Modifier.height(12.dp))
                    SendReceiptPreview("failed")
                }
            }
            "mail-contacts" -> ContactsScreen(vm, onBack = {}, onOpen = { })
            "mail-contact-detail" -> ContactDetailScreen(
                vm, address = "amira@northwind.example", onBack = {}, onWrite = { })
            "mail-files" -> FilesScreen(vm, onBack = {}, onOpen = { })
            "mail-attachment-preview" -> AttachmentPreviewScreen(vm, attachmentId = "a1", onBack = {})
            "mail-settings" -> MailSettingsScreen(vm, onBack = {}, onSecurity = {},
                onScreener = {}, onLabels = {}, onHelp = {}, onSignOut = {})
            "mail-screener" -> ScreenerScreen(vm, onBack = {}, onOpenQueue = {})
            else -> Text(
                "Unknown mail snapshot: $name",
                fontSize = 15.sp, color = palette.textSecondary,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}

// ── the sample mailbox ───────────────────────────────────────────────────────
// Senders and subjects are invented for the photograph. They never appear in a
// live path, so a populated screen can be reviewed without anyone mistaking the
// picture for delivered mail.

private fun msg(
    id: String, from: String, address: String, subject: String, snippet: String,
    unread: Boolean = false, starred: Boolean = false, files: Boolean = false,
): MailMessageDto = MailMessageDto(
    id = id,
    threadId = "t-$id",
    direction = "inbound",
    status = "delivered",
    from = MailPersonDto(address = address, name = from),
    to = listOf("you@grapmail.com"),
    subject = subject,
    snippet = snippet,
    isRead = !unread,
    isStarred = starred,
    hasAttachments = files,
    createdAt = "2026-10-29T09:12:00Z",
)

private val rows = listOf(
    msg("m1", "Amira Rahman", "amira@northwind.example", "Q3 facilities invoice",
        "Attached is the invoice for the quarter, payable on receipt.", unread = true),
    msg("m2", "Devon Okafor", "devon@brightline.example", "Standoff on the shared calendar",
        "Can we move the review forty minutes later? The room is booked.",
        unread = true, starred = true),
    msg("m3", "Priya Nathan", "priya@havenworks.example", "Draft contract, second pass",
        "Clause 4 is the only one still open. Everything else reads clean.", files = true),
    msg("m4", "GrapMail Security", "security@grapmail.com", "Your recovery kit is ready",
        "The kit is generated on your device and never leaves it unread."),
    msg("m5", "Tomas Varga", "tomas@fieldkit.example", "Re: Winter delivery windows",
        "Agreed — we will hold the Thursday slot for both routes."),
)

private val sampleLabels = listOf(
    MailLabelDto(id = "l1", name = "Work", color = "#2F6FED", unread = 2),
    MailLabelDto(id = "l2", name = "Bills", color = "#1E9E6A"),
)

/// The sample pages, in the same cache the live list reads. Without them the
/// Contacts and Files screens can only ever photograph their empty branch, and
/// the screen under review is the populated one.
private suspend fun seedCache() {
    MailStore.putPage(MailBox.Inbox, null,
        MailInboxResponse(success = true, box = "inbox", total = rows.size, messages = rows),
        replace = true)
    MailStore.putBody(
        MailMessageFullDto(
            id = "m1", threadId = "t-m1", direction = "inbound", status = "delivered",
            from = MailPersonDto(address = "amira@northwind.example", name = "Amira Rahman"),
            to = listOf("you@grapmail.com"), subject = "Q3 facilities invoice",
            isRead = true, hasAttachments = true, createdAt = "2026-10-29T09:12:00Z",
            bodyText = "Attached is the invoice for the quarter, payable on receipt.",
            attachments = listOf(MailAttachmentDto(id = "a1", name = "invoice-q3.pdf",
                mimeType = "application/pdf", sizeBytes = 1_415_732)),
        ),
    )
}

private fun seedThread(vm: MailboxViewModel, encrypted: Boolean) {
    vm.seedThreadForSnapshot(listOf(
        MailMessageFullDto(
            id = if (encrypted) "m9" else "m1",
            threadId = if (encrypted) "t-m9" else "t-m1",
            direction = "inbound",
            status = "delivered",
            from = MailPersonDto(address = "amira@northwind.example", name = "Amira Rahman"),
            to = listOf("you@grapmail.com"),
            subject = "Q3 facilities invoice",
            isRead = true,
            isStarred = true,
            hasAttachments = !encrypted,
            isE2eEncrypted = encrypted,
            createdAt = "2026-10-29T09:12:00Z",
            bodyText = if (encrypted) null else
                "Attached is the invoice for the quarter, payable on receipt. " +
                    "The facilities line item moved again — see page two.",
            attachments = if (encrypted) emptyList() else listOf(
                MailAttachmentDto(id = "a1", name = "invoice-q3.pdf",
                    mimeType = "application/pdf", sizeBytes = 1_415_732),
            ),
        ),
    ))
}

// ── frames ───────────────────────────────────────────────────────────────────

/// The list states, drawn by the same [MailStateBlock] the live screen uses. The
/// difference between empty, failed and no-mailbox is the thing being
/// photographed, so it must not be re-implemented here.
@Composable
private fun listFrame(
    shown: List<MailMessageDto>,
    box: MailBox,
    loading: Boolean,
    failed: Boolean,
    noMailbox: Boolean,
) {
    val palette = LocalPalette.current
    // The frame takes its values as arguments instead of seeding the view model:
    // writing a flow during composition is a side effect in the wrong place, and
    // this screen is the one place that already knows exactly what it wants to
    // draw. The components are the live ones, so the photograph is of them.
    Column(Modifier.fillMaxSize()) {
        MailGreeting("Amira Rahman", "amira@grapmail.com")
        Box(Modifier.fillMaxWidth().weight(1f)) {
            MailStateBlock(
                loading = loading,
                failed = failed,
                noMailbox = noMailbox,
                empty = shown.isEmpty(),
                boxTitle = box.title,
                onRetry = {},
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    shown.forEach { row ->
                        MessageRow(
                            message = row, selected = false,
                            onSelect = {}, onOpen = {}, onStar = {}, selecting = false,
                        )
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .height(1.dp).background(palette.border))
                    }
                }
            }
        }
    }
}

@Composable
private fun drawerFrame() {
    MailDrawer(
        box = MailBox.Inbox,
        unread = 2,
        counts = mapOf(
            MailBox.Inbox to 148, MailBox.Sent to 31, MailBox.Drafts to 2,
            MailBox.Spam to 4, MailBox.Trash to 0,
            // Unanswered by the server, so no badge.
            MailBox.Starred to null, MailBox.Archive to null, MailBox.All to null,
            MailBox.Screener to null,
        ),
        onBox = { }, onLabels = { }, onScreener = { }, onSettings = { },
        onSecurity = { }, onHelp = { }, onSignOut = { },
    )
}

@Composable
private fun searchFrame() {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("3 results for “invoice”", fontSize = 13.sp, color = palette.textSecondary,
            modifier = Modifier.padding(vertical = 10.dp))
        rows[0].let {
            MessageRow(message = it, selected = false, onSelect = {}, onOpen = {},
                onStar = {}, selecting = false)
        }
        rows[2].let {
            MessageRow(message = it, selected = false, onSelect = {}, onOpen = {},
                onStar = {}, selecting = false)
        }
    }
}

@Composable
private fun selectionFrame() {
    Column(Modifier.fillMaxSize()) {
        SelectionToolbar(count = 2, onClose = {}, onArchive = {}, onDelete = {},
            onMove = {}, onLabel = {}, onMore = {})
        rows.take(3).forEachIndexed { i, row ->
            MessageRow(message = row, selected = i < 2, onSelect = {}, onOpen = {},
                onStar = {}, selecting = true)
        }
    }
}

@Composable
private fun sheetHost(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(top = 40.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top,
    ) { content() }
}

/// The receipt, drawn from the same words the live screen prints. `queued` is
/// never rendered as delivered — the status word is the server's, and this
/// photograph exists to prove the screen keeps them apart.
@Composable
private fun SendReceiptPreview(status: String) {
    val palette = LocalPalette.current
    val (headline, tone) = when (status) {
        "sent" -> "Sent" to palette.successInk
        "queued" -> "Queued for delivery" to palette.warnInk
        "failed" -> "Not delivered" to palette.error
        else -> "Server reported: $status" to palette.warnInk
    }
    Column(
        Modifier.fillMaxWidth().padding(14.dp)
            .background(palette.softFill, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
    ) {
        Text(headline, fontSize = 15.sp, color = tone)
        if (status == "queued") {
            Spacer(Modifier.height(4.dp))
            Text("The queue has accepted it. Delivery is confirmed separately.",
                fontSize = 12.sp, color = palette.textTertiary)
        }
        if (status == "failed") {
            Spacer(Modifier.height(4.dp))
            Text("Recipient domain not reachable.", fontSize = 13.sp, color = palette.error)
        }
    }
}
