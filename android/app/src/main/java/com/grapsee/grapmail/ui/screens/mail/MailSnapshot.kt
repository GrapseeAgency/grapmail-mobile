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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.grapsee.grapmail.data.ProfileAliasDto
import com.grapsee.grapmail.data.ProfileConnectionDto
import com.grapsee.grapmail.data.ProfileDto
import com.grapsee.grapmail.data.ProfileSettingsDto
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
    // `snapshotMode` so the model's own boot reads do not overwrite the seed.
    val vm = remember { MailboxViewModel(app, snapshotMode = true) }
    // The account record the eight Phase-1 screens read, seeded the same way:
    // the host owns the document for these frames, and `snapshotMode` keeps the
    // model's own boot read from overwriting it with the CI account's real
    // (empty) notes list — the race the mailbox seeds already guard against.
    val profileVm = remember { com.grapsee.grapmail.logic.ProfileViewModel(app, snapshotMode = true) }
    // The sheet below prints the scope line from the live state; collecting it
    // (not reading .value once) is what lets the seeded numbers recompose the
    // photograph instead of the initial empty state being what ships.
    val mailboxState by vm.state.collectAsState()

    // The seeds run in an effect, not in composition. Writing a state flow while
    // composing is a side effect in the wrong place — it can schedule a
    // recomposition of the very code that performs it. The screens all read the
    // seeded flows, so they render their populated branch as soon as this lands,
    // and the walk waits for that settled state before it photographs.
    LaunchedEffect(name) {
        when (name) {
            // The id goes with the body: the thread screen asks the model for its
            // conversation on appear, and the model has to know this one is already
            // here rather than fetching over it and landing on an error state.
            "mail-thread" -> seedThread(vm, id = "t-m1", encrypted = false)
            "mail-thread-encrypted" -> seedThread(vm, id = "t-m9", encrypted = true)
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
            "mail-labels" -> vm.seedLabelsForSnapshot(sampleLabels)
            "mail-settings" -> vm.seedForSnapshot {
                it.copy(
                    accountAddress = "amira@grapmail.com",
                    accountStatus = "active",
                    memberSince = "March 2026",
                )
            }
            "mail-screener" -> vm.seedScreenerForSnapshot(enabled = true, pending = 3)
            // The advanced-search sheet photographs its scope line with real
            // numbers: the seeded page's five rows are what the filters ran
            // over, and the box's total says the box is bigger than that.
            "mail-adv-search" -> {
                seedCache()
                vm.seedForSnapshot {
                    it.copy(accountAddress = "amira@grapmail.com", total = 40, advScope = 5)
                }
            }
            // The undo banner exists only after a filing, and a read-only walk
            // must not file anything -- so the words a real archive produces are
            // drawn by the live banner composable over the live list frame.
            "mail-undo" -> vm.seedForSnapshot {
                it.copy(undoIds = listOf("m1", "m2"),
                    undoInverse = com.grapsee.grapmail.data.MailAction.Unarchive,
                    undoNote = "Archived 2 messages")
            }
            // The account-record screens all read one document; one seed covers
            // all eight, and each frame's settled marker is a value that exists
            // only once that document has landed. `mail-compose` joins the list:
            // the compose frame is the photograph that proves the profile's
            // signature arrives as editable text in the body, on both phones.
            "mail-notes", "mail-tasks", "mail-settings-prefs", "mail-addresses",
            "account-connections", "account-profile", "account-privacy", "mail-support",
            "mail-compose" ->
                profileVm.seed(sampleProfile())
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
            // There is no `mail-home-dark` case, and there must not be one: the dark
            // home is the SAME screen photographed with the system appearance forced
            // (`cmd uimode night yes` in `scripts/android-walk.sh`), so a second host
            // name for it would be a second copy of a screen rather than a second
            // piece of data — and it was one, launched by nothing, until the name-set
            // pin in `scripts/mail-honesty.py` made an unlaunched case illegal.
            "mail-home" -> listFrame(rows, MailBox.Inbox, loading = false,
                failed = false, noMailbox = false)
            // No `mail-drawer`, `mail-search` or `mail-sort-filter` case, and no
            // `drawerFrame`/`searchFrame` to draw them: frames 62, 67 and 69 are
            // photographed from the LIVE screen on both platforms (the walk opens the
            // drawer, types the term and raises the sort sheet itself). A host case
            // for a screen the walk already reaches is a second copy of that screen —
            // the rule this sector already enforces for the send receipt — and the
            // name-set pin in `scripts/mail-honesty.py` now refuses one on either
            // side, which is how the three were found dead here and on iOS at once.
            "mail-empty" -> listFrame(emptyList(), MailBox.Archive, loading = false,
                failed = false, noMailbox = false)
            "mail-loading" -> listFrame(emptyList(), MailBox.Inbox, loading = true,
                failed = false, noMailbox = false)
            "mail-load-failed" -> listFrame(emptyList(), MailBox.Inbox, loading = false,
                failed = true, noMailbox = false)
            "mail-no-mailbox" -> listFrame(emptyList(), MailBox.Inbox, loading = false,
                failed = false, noMailbox = true)
            "mail-selection" -> selectionFrame()
            "mail-move-sheet" -> sheetHost { MoveContent { } }
            "mail-delete-sheet" -> sheetHost { PermanentDeleteContent(3, cancel = {}, confirm = {}) }
            "mail-assign-label-sheet" -> sheetHost {
                // All three states the assignment read can produce, in one frame:
                // one label on every selected message, one on some, one on none.
                // Photographing only the unmarked sheet would leave the tick and
                // the dash unproven — and the tick is the thing this sheet exists
                // to show. The renderer is the live one; only the data is invented.
                AssignLabelContent(
                    labels = sampleLabels,
                    labelsRead = com.grapsee.grapmail.data.AssignmentsRead.Read,
                    marks = mapOf(
                        "l1" to com.grapsee.grapmail.data.LabelMark.On,
                        "l2" to com.grapsee.grapmail.data.LabelMark.Mixed,
                        "l3" to com.grapsee.grapmail.data.LabelMark.Off,
                    ),
                    read = com.grapsee.grapmail.data.AssignmentsRead.Read,
                    note = null,
                    selectedCount = 3,
                ) { }
            }
            "mail-snooze-sheet" -> sheetHost { SnoozeContent { } }
            // The unsubscribe confirmation is reachable only from a message that
            // carries the sender's `List-Unsubscribe` header, which no CI mailbox has,
            // so without this frame the screen would ship unphotographed — the same
            // gap `mail-sender-sheet` was added for. The URL is an invented
            // newsletter address on a reserved TLD; the renderer is the live one.
            "mail-unsubscribe" -> sheetHost {
                UnsubscribeContent(
                    sender = "hello@newsletter.example",
                    url = "https://lists.newsletter.example/unsub?id=7f3",
                    onOpen = { }, onCancel = { },
                )
            }
            // The sender-decision sheet appears only when the screener queue is
            // open and messages are selected, so no read-only walk can reach it on
            // a fresh CI mailbox — the queue is empty there. Two senders rather
            // than one, because the plural line is the branch that would otherwise
            // never be photographed and the scope row reads differently.
            "mail-sender-sheet" -> sheetHost {
                SenderDecisionContent(
                    senders = listOf("priya@havenworks.example", "tomas@fieldkit.example"),
                    busy = false, result = null, onPick = { _, _ -> },
                )
            }
            // The snapshot host never edits: this screen is photographed as the
            // list of labels the account has, and the row action is a no-op here
            // exactly as every other control in this host is.
            "mail-labels" -> LabelsScreen(vm, onBack = {}, onCreate = {}, onEdit = { })
            "mail-label-create" -> LabelCreateScreen(vm, onDone = {})
            "mail-thread" -> ThreadScreen(vm, threadId = "t-m1", isThread = true,
                onBack = {}, onReply = { _, _ -> })
            "mail-thread-encrypted" -> ThreadScreen(vm, threadId = "t-m9", isThread = true,
                onBack = {}, onReply = { _, _ -> })
            "mail-compose" -> ComposeScreen(vm = vm, profile = profileVm, onBack = {})
            "mail-send-receipt" -> sheetHost {
                // The live `SendReceipt` composable, with the strings a server
                // would send standing in for a real send. This host used to draw
                // its own copy of the receipt instead — and the copy had already
                // lost the semibold headline and the detail line, so the frame
                // photographed a screen nobody sees. Only the DATA is invented
                // here; the rendering is the shipping one.
                Column(Modifier.padding(16.dp)) {
                    SendReceipt(status = "queued", detail = "Accepted for delivery.", error = null)
                    Spacer(Modifier.height(12.dp))
                    SendReceipt(status = "failed", detail = null,
                        error = "Recipient domain not reachable.")
                }
            }
            "mail-contacts" -> ContactsScreen(vm, onBack = {}, onOpen = { })
            "mail-contact-detail" -> ContactDetailScreen(
                vm, address = "amira@northwind.example", onBack = {}, onWrite = { })
            "mail-files" -> FilesScreen(vm, onBack = {}, onOpen = { })
            "mail-attachment-preview" -> AttachmentPreviewScreen(vm, attachmentId = "a1", onBack = {})
            "mail-settings" -> MailSettingsScreen(vm, onBack = {}, onSecurity = {},
                onScreener = {}, onLabels = {}, onNotes = {}, onTasks = {}, onPrefs = {},
                onAddresses = {}, onConnections = {}, onProfile = {}, onPrivacy = {},
                onHelp = {}, onSignOut = {})
            "mail-notes" -> NotesScreen(profileVm, onBack = {})
            "mail-tasks" -> TasksScreen(profileVm, onBack = {})
            "mail-settings-prefs" -> MailPrefsScreen(profileVm, onBack = {})
            "mail-addresses" -> AddressesScreen(profileVm, onBack = {})
            "account-connections" -> ConnectionsScreen(profileVm, onBack = {})
            "account-profile" -> ProfileEditScreen(profileVm, onBack = {})
            "account-privacy" -> PrivacyScreen(profileVm, onBack = {}, onProfile = {},
                onPhone = {}, onAddresses = {}, onPrefs = {}, onSupport = {})
            "mail-support" -> SupportScreen(profileVm, onBack = {}, onForgot = {},
                onPhone = {}, onAddresses = {})
            "mail-screener" -> ScreenerScreen(vm, onBack = {}, onOpenQueue = {})
            "mail-adv-search" -> sheetHost {
                AdvancedSearchContent(mailboxState, vm, onDone = { })
            }
            "mail-undo" -> Column(Modifier.fillMaxSize()) {
                UndoBanner("Archived 2 messages", "mail-undo", onUndo = {}, onDone = {})
                listFrame(rows, MailBox.Inbox, loading = false, failed = false,
                    noMailbox = false)
            }
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

/// The label colours are the server's palette KEYS, not hex values, because a hex
/// value is not something `grapmail-labels.ts` can ever answer with — `normaliseColour`
/// rejects it and stores `slate`. Sample data that carried a shape the server cannot
/// send would have made a broken colour lookup look correct here.
private val sampleLabels = listOf(
    MailLabelDto(id = "l1", name = "Work", color = "blue", unread = 2),
    MailLabelDto(id = "l2", name = "Bills", color = "green"),
    MailLabelDto(id = "l3", name = "Receipts", color = "amber", unread = 1),
)

/**
 * The account record the eight Phase-1 frames are photographed with. Invented,
 * like every seed in this file — it never appears in a live path — but shaped
 * exactly like `GET /auth/account/profile` answers (`auth-account-profile.ts:175-214`):
 * the alias rows carry the table's own fields, the connection list mixes a
 * first-party product (no host, so it draws a monogram) with a third-party app
 * (a host, so it draws its domain), and every settled marker the walk waits for
 * — the birthday, the note, the task, the signature, the alias address, the
 * product name, the created date — exists only once this document has landed.
 */
private fun sampleProfile(): ProfileDto = ProfileDto(
    id = "p1",
    name = "Amira Rahman",
    firstName = "Amira",
    lastName = "Rahman",
    birthday = "1993-04-12",
    gender = "Female",
    address = "amira@grapmail.com",
    handle = "amira",
    domain = "grapmail.com",
    phone = "+8801711223344",
    phoneVerified = true,
    twoFactor = "sms",
    twoFactorEnabled = true,
    displayName = "Amira Rahman",
    recoveryEmail = "amira@northwind.example",
    fromHeader = "Amira Rahman <amira@grapmail.com>",
    createdAt = "2026-03-14T04:30:00.000Z",
    passwordChangedAt = "2026-06-02T11:00:00.000Z",
    termsAcceptedAt = "2026-03-14T04:31:10.000Z",
    backupCodesRemaining = 7,
    settings = ProfileSettingsDto(
        signature = "Sent with GrapMail",
        density = "comfortable",
        notes = listOf("Send the Q3 numbers before Friday"),
        tasks = listOf("Book the studio visit"),
        signatures = mapOf("business@grapmail.com" to "Northwind Studio"),
        blockedSenders = listOf("deals@spamyard.example"),
        safeSenders = listOf("hello@northwind.example"),
    ),
    aliases = listOf(
        ProfileAliasDto(address = "business@grapmail.com", label = "Business",
            isActive = true, createdAt = "2026-04-02T10:00:00.000Z"),
        ProfileAliasDto(address = "old@grapmail.com", label = "Old",
            isActive = false, createdAt = "2026-03-20T09:00:00.000Z"),
    ),
    connections = listOf(
        ProfileConnectionDto(product = "Grapsee Projects", host = null,
            scopes = listOf("profile", "projects"), connectedAt = "2026-07-01T08:00:00.000Z"),
        ProfileConnectionDto(product = "Northwind SSO", host = "sso.northwind.example",
            scopes = listOf("profile"), connectedAt = "2026-07-19T15:20:00.000Z"),
    ),
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

private fun seedThread(vm: MailboxViewModel, id: String, encrypted: Boolean) {
    vm.seedThreadForSnapshot(forId = id, messages = listOf(
        MailMessageFullDto(
            id = if (encrypted) "m9" else "m1",
            threadId = id,
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

/// The receipt frame is drawn by the live `SendReceipt` in `ComposeScreen.kt`.
/// Nothing here reimplements it: a second copy of a screen's words is a photograph
/// of the copy, and the two drift apart with nothing to notice.
