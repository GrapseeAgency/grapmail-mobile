@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.grapsee.grapmail

import android.app.Application
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.waterfall
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.grapsee.grapmail.data.MailAction
import com.grapsee.grapmail.data.MailAttachmentDto
import com.grapsee.grapmail.data.MailBox
import com.grapsee.grapmail.data.MailE2e
import com.grapsee.grapmail.data.MailE2eKeyDto
import com.grapsee.grapmail.data.MailE2eKeysDto
import com.grapsee.grapmail.data.MailE2eLookupEntryDto
import com.grapsee.grapmail.data.MailInboxResponse
import com.grapsee.grapmail.data.MailLabelDto
import com.grapsee.grapmail.data.MailMessageDto
import com.grapsee.grapmail.data.MailMessageFullDto
import com.grapsee.grapmail.data.MailPersonDto
import com.grapsee.grapmail.data.MailStore
import com.grapsee.grapmail.data.MailUnsubscribeDto
import com.grapsee.grapmail.data.MailUnsubscribeOfferDto
import com.grapsee.grapmail.data.ProfileAliasDto
import com.grapsee.grapmail.data.ProfileConnectionDto
import com.grapsee.grapmail.data.ProfileDto
import com.grapsee.grapmail.data.ProfileSettingsDto
import com.grapsee.grapmail.data.ProviderAccountDto
import com.grapsee.grapmail.data.SafetyChecklistItemDto
import com.grapsee.grapmail.data.SafetyChecklistMetaDto
import com.grapsee.grapmail.data.SafetyScoreDto
import com.grapsee.grapmail.data.SecurityDeviceDto
import com.grapsee.grapmail.data.SecurityDeviceRemovalDto
import com.grapsee.grapmail.data.SecurityLocationDto
import com.grapsee.grapmail.data.SecurityOverviewAccountDto
import com.grapsee.grapmail.data.SecurityOverviewResponse
import com.grapsee.grapmail.data.SecurityTimelineEventDto
import com.grapsee.grapmail.logic.ConnectedAccountsViewModel
import com.grapsee.grapmail.logic.E2eViewModel
import com.grapsee.grapmail.logic.FeatureTipId
import com.grapsee.grapmail.logic.FeatureTipsStore
import com.grapsee.grapmail.logic.MailboxViewModel
import com.grapsee.grapmail.logic.ProfileViewModel
import com.grapsee.grapmail.ui.components.MessageRow
import com.grapsee.grapmail.ui.components.SelectionToolbar
import com.grapsee.grapmail.ui.screens.OnboardingScreen
import com.grapsee.grapmail.ui.screens.WelcomeScreen
import com.grapsee.grapmail.ui.screens.mail.AssignLabelContent
import com.grapsee.grapmail.ui.screens.mail.AttachmentPreviewScreen
import com.grapsee.grapmail.ui.screens.mail.ComposeScreen
import com.grapsee.grapmail.ui.screens.mail.ConnectedAccountsScreen
import com.grapsee.grapmail.ui.screens.mail.ConnectionsScreen
import com.grapsee.grapmail.ui.screens.mail.E2eDetailsScreen
import com.grapsee.grapmail.ui.screens.mail.E2eRecipientsScreen
import com.grapsee.grapmail.ui.screens.mail.E2eStatusScreen
import com.grapsee.grapmail.ui.screens.mail.FilesScreen
import com.grapsee.grapmail.ui.screens.mail.LabelsScreen
import com.grapsee.grapmail.ui.screens.mail.MailSettingsScreen
import com.grapsee.grapmail.ui.screens.mail.MailSheet
import com.grapsee.grapmail.ui.screens.mail.MailboxScreen
import com.grapsee.grapmail.ui.screens.mail.MoveContent
import com.grapsee.grapmail.ui.screens.mail.ScreenerScreen
import com.grapsee.grapmail.ui.screens.mail.SenderDecisionContent
import com.grapsee.grapmail.ui.screens.mail.SnoozeContent
import com.grapsee.grapmail.ui.screens.mail.SortFilterContent
import com.grapsee.grapmail.ui.screens.mail.SubscriptionsScreen
import com.grapsee.grapmail.ui.screens.mail.ThreadContent
import com.grapsee.grapmail.ui.screens.mail.ThreadScreen
import com.grapsee.grapmail.ui.screens.mail.UndoBanner
import com.grapsee.grapmail.ui.screens.mail.UnsubscribeContent
import com.grapsee.grapmail.ui.screens.security.AccountSecurityScreen
import com.grapsee.grapmail.ui.screens.security.SecurityCenterScreen
import com.grapsee.grapmail.ui.theme.GrapMailTheme
import com.grapsee.grapmail.ui.theme.ImmersiveSurface
import com.grapsee.grapmail.ui.theme.LocalPalette
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Debug-only host for high-resolution marketing screenshot captures.
 * Renders the real GrapMail composables with cohesive, offline demo fixtures.
 * Launch via: adb shell am start -W -n com.grapsee.grapmail/.MarketingCaptureActivity --es scene <name>
 */
class MarketingCaptureActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Suppress first-run feature tips so overlays never obscure marketing frames.
        FeatureTipId.entries.forEach { FeatureTipsStore.dismiss(this, it) }
        val scene = intent?.getStringExtra("scene") ?: "inbox"
        setContent {
            GrapMailTheme {
                val bg = ImmersiveSurface.color.value ?: LocalPalette.current.bg
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(bg)
                        .semantics {
                            testTagsAsResourceId = true
                            contentDescription = "gm-marketing:$scene"
                        },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier
                            .windowInsetsPadding(
                                WindowInsets.systemBars
                                    .union(WindowInsets.displayCutout)
                                    .union(WindowInsets.waterfall)
                            )
                            .fillMaxSize()
                    ) {
                        MarketingScene(scene)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketingScene(scene: String) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm = remember { MailboxViewModel(app, snapshotMode = true) }
    val profileVm = remember { ProfileViewModel(app, snapshotMode = true) }
    val e2eVm = remember { E2eViewModel(app, snapshotMode = true) }
    val connectedVm = remember { ConnectedAccountsViewModel(app) }
    val mailboxState by vm.state.collectAsState()
    var seeded by remember { mutableStateOf(false) }

    LaunchedEffect(scene) {
        val rows = demoRows()
        seedDemoCache(rows)
        vm.seedLabelsForSnapshot(demoLabels)
        vm.seedScreenerForSnapshot(enabled = true, pending = 3)
        vm.seedForSnapshot {
            it.copy(
                accountName = "Elena Vance",
                accountAddress = "elena.vance@grapmail.com",
                accountStatus = "active",
                memberSince = "March 2026",
                rows = rows,
                loaded = rows.size,
                total = 24,
                statsSent = 12,
                statsDrafts = 2,
                statsStarred = 4,
                statsArchive = 38,
                statsOthers = 64,
                statsSnoozed = 1,
                statsSpam = 0,
                statsTrash = 2,
            )
        }
        profileVm.seed(demoProfile())
        e2eVm.seedForSnapshot(
            record = demoIdentity,
            published = demoPublished,
            fingerprint = demoFingerprint,
            unlocked = true,
            lookup = demoLookup,
            lookupFor = listOf("marcus@atelier-studio.example", "henrik@nordic-security.example"),
        )
        connectedVm.seedForSnapshot(demoConnectedProviders)

        when (scene) {
            "thread", "tablet-inbox-thread", "reader-actions" -> seedDesignThread(vm)
            "thread-rich" -> seedNewsletterThread(vm)
            "thread-encrypted" -> seedEncryptedThread(vm)
            "compose", "compose-keyboard" -> {
                vm.composeSeed {
                    it.copy(
                        to = "marcus@atelier-studio.example",
                        subject = "Re: Brand refresh — final vector grid & type specimens",
                        body = "Hi Marcus,\n\nThe updated kerning on slides 4–9 looks crisp. I've reviewed the Figma tokens and approved the export for Friday's release.\n\nLet's lock the dark-mode palette once Sora signs off on the icon weights.\n\nBest,\nElena",
                        userEdited = true,
                    )
                }
            }
            "search" -> {
                val filtered = rows.filter {
                    (it.subject ?: "").contains("invoice", ignoreCase = true) ||
                        (it.subject ?: "").contains("flight", ignoreCase = true) ||
                        (it.subject ?: "").contains("brand", ignoreCase = true)
                }
                vm.seedForSnapshot {
                    it.copy(query = "studio", rows = filtered, loaded = filtered.size, total = filtered.size)
                }
            }
            "undo" -> {
                vm.seedForSnapshot {
                    it.copy(
                        undoIds = listOf("m4", "m7"),
                        undoInverse = MailAction.Unarchive,
                        undoNote = "Archived 2 conversations",
                    )
                }
            }
        }
        seeded = true
    }

    if (!seeded) {
        Box(Modifier.fillMaxSize().background(LocalPalette.current.bg))
        return
    }

    when (scene) {
        "welcome" -> WelcomeScreen(onCreateAccount = {}, onSignIn = {}, onLegal = {})
        "onboarding-1" -> OnboardingScreen(onFinish = {}, initialScene = 0)
        "onboarding-2" -> OnboardingScreen(onFinish = {}, initialScene = 1)
        "onboarding-3" -> OnboardingScreen(onFinish = {}, initialScene = 2)
        "onboarding-4" -> OnboardingScreen(onFinish = {}, initialScene = 3)
        "onboarding-5" -> OnboardingScreen(onFinish = {}, initialScene = 4)

        "inbox", "search", "undo" -> DemoMailboxHome(vm)
        "tablet-inbox-thread" -> DemoTabletTwoPane(vm, e2eVm)

        "thread" -> ThreadScreen(
            vm = vm, e2e = e2eVm, threadId = "t-design", isThread = true,
            onBack = {}, onReply = { _, _ -> }, onStepPrev = {}, onStepNext = {}, onContacts = {},
        )
        "reader-actions" -> ThreadScreen(
            vm = vm, e2e = e2eVm, threadId = "t-design", isThread = true,
            initialMessageId = "m1-4", openMessageActions = true,
            onBack = {}, onReply = { _, _ -> }, onStepPrev = {}, onStepNext = {}, onContacts = {},
        )
        "thread-rich" -> ThreadScreen(
            vm = vm, e2e = e2eVm, threadId = "t-news", isThread = true,
            onBack = {}, onReply = { _, _ -> }, onStepPrev = {}, onStepNext = {}, onContacts = {},
        )
        "thread-encrypted" -> ThreadScreen(
            vm = vm, e2e = e2eVm, threadId = "t-enc", isThread = true,
            onBack = {}, onReply = { _, _ -> }, onStepPrev = {}, onStepNext = {}, onContacts = {},
        )

        "compose", "compose-keyboard" -> ComposeScreen(
            vm = vm, profile = profileVm, e2e = e2eVm, onBack = {},
        )

        "labels" -> LabelsScreen(vm, onBack = {}, onCreate = {}, onEdit = {})
        "files" -> FilesScreen(vm, onBack = {}, onOpen = {})
        "attachment-preview" -> AttachmentPreviewScreen(vm, attachmentId = "att-brand", onBack = {})
        "settings" -> MailSettingsScreen(
            vm, onBack = {}, onSecurity = {}, onScreener = {}, onLabels = {},
            onNotes = {}, onTasks = {}, onPrefs = {}, onAddresses = {},
            onConnections = {}, onSubscriptions = {}, onProfile = {}, onPrivacy = {},
            onEncryption = {}, onNotificationPrefs = {}, onPerAccountNotifications = {},
            onHelp = {}, onSignOut = {},
        )
        "screener" -> ScreenerScreen(vm, onBack = {}, onOpenQueue = {})
        "subscriptions" -> SubscriptionsScreen(vm, onBack = {})
        "connected-accounts" -> ConnectedAccountsScreen(onBack = {}, vm = connectedVm)
        "connections" -> ConnectionsScreen(profileVm, onBack = {}, onOpen = {})

        "e2e-status" -> E2eStatusScreen(
            e2eVm, onBack = {}, onSetup = {}, onUnlock = {}, onDetails = {},
            onPublish = {}, onChangePassphrase = {}, onRemove = {}, onRecipients = {},
        )
        "e2e-details" -> E2eDetailsScreen(e2eVm, onBack = {})
        "e2e-recipients" -> E2eRecipientsScreen(e2eVm, onBack = {}, onCompose = {})

        "security-center" -> SecurityCenterScreen(
            onBack = {}, onAction = {}, onNotMeSecured = {}, onSignOutThisDevice = {},
            onChangePassword = {}, onDeviceKeys = {}, demoOverview = demoSecurityOverview(),
        )
        "security-hub" -> AccountSecurityScreen(
            onBack = {}, onTwoFactor = {}, onDisableTwoFactor = {}, onPhone = {},
            onRecoveryEmail = {}, onSessions = {}, onActivity = {}, onTrustedDevices = {},
            onPasskeys = {}, onRecoveryCodes = {}, onNotificationPrefs = {},
            onBackupCodes = {}, onPassword = {}, onSignOutAll = {},
            onDeleteAccount = {}, onSupport = {}, onSecurityCenter = {},
            demoProfile = demoProfile(),
        )

        "selection" -> DemoSelectionScreen(demoRows())

        "sheet-sort" -> DemoSheetOverInbox(vm) {
            SortFilterContent(mailboxState.sort, mailboxState.filter, mailboxState.rows.size, mailboxState.total) { _, _ -> }
        }
        "sheet-move" -> DemoSheetOverInbox(vm) { MoveContent { } }
        "sheet-label" -> DemoSheetOverInbox(vm) {
            AssignLabelContent(
                labels = demoLabels,
                labelsRead = com.grapsee.grapmail.data.AssignmentsRead.Read,
                marks = mapOf(
                    "l1" to com.grapsee.grapmail.data.LabelMark.On,
                    "l2" to com.grapsee.grapmail.data.LabelMark.Mixed,
                    "l3" to com.grapsee.grapmail.data.LabelMark.On,
                    "l4" to com.grapsee.grapmail.data.LabelMark.Off,
                ),
                read = com.grapsee.grapmail.data.AssignmentsRead.Read,
                note = null,
                selectedCount = 2,
                busy = false,
            ) { }
        }
        "sheet-snooze" -> DemoSheetOverInbox(vm) { SnoozeContent { } }
        "sheet-sender" -> DemoSheetOverInbox(vm) {
            SenderDecisionContent(
                senders = listOf("alex.mercer@studio-partner.example", "billing@cloudhost.example"),
                busy = false, result = null, onPick = { _, _ -> },
            )
        }
        "sheet-unsub" -> DemoSheetOverInbox(vm) {
            UnsubscribeContent(
                sender = "editorial@weeklydraft.example",
                url = "https://lists.weeklydraft.example/unsubscribe?token=8a4c",
                onOpen = {}, onCancel = {},
            )
        }
    }
}

@Composable
private fun DemoMailboxHome(vm: MailboxViewModel) {
    MailboxScreen(
        vm = vm,
        onOpenThread = { _, _, _ -> },
        onCompose = {},
        onOpenDraft = {},
        onProfile = {},
        onSettings = {},
        onSecurity = {},
        onHelp = {},
        onLabels = {},
        onContacts = {},
        onFiles = {},
        onScreener = {},
        onSubscriptions = {},
        onScheduled = {},
        onSignOut = {},
        onAddAccount = {},
    )
}

@Composable
private fun DemoTabletTwoPane(vm: MailboxViewModel, e2eVm: E2eViewModel) {
    val palette = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.bg)) {
        val listWidth = maxOf(300.dp, minOf(400.dp, maxWidth * 0.4f))
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(listWidth).fillMaxHeight()) {
                MailboxScreen(
                    vm = vm,
                    highlightedId = "t-design",
                    onOpenThread = { _, _, _ -> },
                    onCompose = {}, onOpenDraft = {}, onProfile = {},
                    onSettings = {}, onSecurity = {}, onHelp = {}, onLabels = {},
                    onContacts = {}, onFiles = {}, onScreener = {},
                    onSubscriptions = {}, onScheduled = {}, onSignOut = {}, onAddAccount = {},
                )
            }
            VerticalDivider(color = palette.border)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                ThreadContent(
                    vm = vm, e2e = e2eVm, threadId = "t-design", isThread = true,
                    showBackButton = false,
                    onBack = {}, onReply = { _, _ -> }, onStepPrev = {}, onStepNext = {}, onContacts = {},
                )
            }
        }
    }
}

@Composable
private fun DemoSheetOverInbox(vm: MailboxViewModel, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        DemoMailboxHome(vm)
        MailSheet(onDismiss = {}) { content() }
    }
}

@Composable
private fun DemoSelectionScreen(rows: List<MailMessageDto>) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize().background(palette.bg)) {
        SelectionToolbar(
            count = 3, onClose = {}, onArchive = {}, onDelete = {}, onMove = {},
            onLabel = {}, onMarkRead = {}, onMarkUnread = {}, onMore = {},
        )
        rows.take(7).forEachIndexed { idx, row ->
            MessageRow(
                message = row,
                selected = idx in setOf(0, 2, 4),
                onSelect = {}, onOpen = {}, onStar = {}, selecting = true,
            )
        }
    }
}

// ── Realistic Fictional Demo Fixtures ────────────────────────────────────────

private fun demoRows(): List<MailMessageDto> {
    val now = Instant.now()
    fun minsAgo(m: Long) = now.minusSeconds(m * 60).toString()
    fun hoursAgo(h: Long) = now.minusSeconds(h * 3600).toString()
    fun daysAgo(d: Long) = now.minusSeconds(d * 86400).toString()

    return listOf(
        MailMessageDto(
            id = "m1-4", threadId = "t-design", direction = "inbound", status = "delivered",
            from = MailPersonDto("marcus@atelier-studio.example", "Marcus Chen"),
            to = listOf("elena.vance@grapmail.com"),
            subject = "Brand refresh — final vector grid & type specimens",
            snippet = "Updated the display kerning on slides 4–9 and exported the Figma specs. Ready for sign-off?",
            isRead = false, isStarred = true, hasAttachments = true,
            createdAt = minsAgo(18),
        ),
        MailMessageDto(
            id = "m2", threadId = "t-travel", direction = "inbound", status = "delivered",
            from = MailPersonDto("bookings@nordicair.example", "Nordic Air"),
            to = listOf("elena.vance@grapmail.com"),
            subject = "Flight confirmation NA 418 — Zurich (ZRH) to Copenhagen (CPH)",
            snippet = "Departs Thu Nov 28 at 08:45 · Terminal 2 · Gate B14 · Seat 4A · Ref NV84K2",
            isRead = false, isStarred = false, hasAttachments = true,
            createdAt = minsAgo(42),
        ),
        MailMessageDto(
            id = "m3", threadId = "t-sec", direction = "inbound", status = "delivered",
            from = MailPersonDto("security@grapmail.com", "GrapMail Security"),
            to = listOf("elena.vance@grapmail.com"),
            subject = "Sign-in verification code: 482913",
            snippet = "Use verification code 482913 to confirm your new Pixel 9 Pro session. Valid for 10 minutes.",
            isRead = false, isStarred = false, hasAttachments = false,
            createdAt = minsAgo(12),
        ),
        MailMessageDto(
            id = "m4", threadId = "t-invoice", direction = "inbound", status = "delivered",
            from = MailPersonDto("amira@northwind.example", "Amira Rahman"),
            to = listOf("elena.vance@grapmail.com"),
            subject = "Q4 studio retainer & facilities invoice #2026-114",
            snippet = "Attached is the itemized PDF invoice for November studio operations ($4,280.00).",
            isRead = true, isStarred = true, hasAttachments = true,
            createdAt = hoursAgo(3),
        ),
        MailMessageDto(
            id = "m5", threadId = "t-track", direction = "inbound", status = "delivered",
            from = MailPersonDto("tracking@veloce.example", "Veloce Logistics"),
            to = listOf("elena.vance@grapmail.com"),
            subject = "Your package is out for delivery today",
            snippet = "Tracking #VL904821735 — Arriving by 6:00 PM · Studio Display Stand & USB-C Hub",
            isRead = false, isStarred = false, hasAttachments = false,
            createdAt = hoursAgo(5),
        ),
        MailMessageDto(
            id = "m6", threadId = "t-enc", direction = "inbound", status = "delivered",
            from = MailPersonDto("henrik@nordic-security.example", "Dr. Henrik Lindqvist"),
            to = listOf("elena.vance@grapmail.com"),
            subject = "Hardware key recovery ceremony & audit notes",
            snippet = "End-to-end encrypted message",
            isRead = true, isStarred = true, isE2eEncrypted = true,
            createdAt = daysAgo(1),
        ),
        MailMessageDto(
            id = "m7", threadId = "t-personal", direction = "inbound", status = "delivered",
            from = MailPersonDto("maya@linfamily.example", "Maya Lin"),
            to = listOf("elena.vance@grapmail.com"),
            subject = "Weekend dinner in Kyoto — photos & table booking",
            snippet = "Confirmed 7:30 PM at Nishiki for Saturday! Attached the film scans from Sunday.",
            isRead = true, isStarred = false, hasAttachments = true,
            createdAt = daysAgo(1),
        ),
        MailMessageDto(
            id = "m8", threadId = "t-news", direction = "inbound", status = "delivered",
            from = MailPersonDto("editorial@weeklydraft.example", "The Weekly Draft"),
            to = listOf("elena.vance@grapmail.com"),
            subject = "Issue #142 — Tactile interfaces & quiet software",
            snippet = "Why the best productivity tools feel like paper, plus three essays on Swiss typography.",
            isRead = true, isStarred = false, hasAttachments = false,
            unsubscribe = MailUnsubscribeDto(
                offers = listOf(MailUnsubscribeOfferDto("https://lists.weeklydraft.example/unsub?id=142", true)),
                oneClickAdvertised = true,
                mailtoOneClick = "mailto:leave@weeklydraft.example",
            ),
            createdAt = daysAgo(2),
        ),
        MailMessageDto(
            id = "m9", threadId = "t-legal", direction = "inbound", status = "delivered",
            from = MailPersonDto("priya@havenworks.example", "Priya Nathan"),
            to = listOf("elena.vance@grapmail.com"),
            subject = "MSA redlines — clean copy for signature",
            snippet = "Clause 4.2 is finalized and approved by counsel. Ready whenever you are.",
            isRead = true, isStarred = false, hasAttachments = true,
            createdAt = daysAgo(3),
        ),
    )
}

private val demoLabels = listOf(
    MailLabelDto(id = "l1", name = "Design", color = "blue", unread = 2),
    MailLabelDto(id = "l2", name = "Travel", color = "green", unread = 1),
    MailLabelDto(id = "l3", name = "Receipts", color = "amber", unread = 1),
    MailLabelDto(id = "l4", name = "Security", color = "purple", unread = 1),
    MailLabelDto(id = "l5", name = "Personal", color = "rose", unread = 0),
)

private fun seedDesignThread(vm: MailboxViewModel) {
    val now = Instant.now()
    vm.seedThreadForSnapshot(
        forId = "t-design",
        messages = listOf(
            MailMessageFullDto(
                id = "m1-1", threadId = "t-design", direction = "inbound", status = "delivered",
                from = MailPersonDto("marcus@atelier-studio.example", "Marcus Chen"),
                to = listOf("elena.vance@grapmail.com", "sora@atelier-studio.example"),
                subject = "Brand refresh — final vector grid & type specimens",
                isRead = true, createdAt = now.minusSeconds(86400 * 2).toString(),
                bodyText = "Sharing the initial spacing grid for the GrapMail mobile campaign. Let me know if the 12px baseline feels balanced on high-DPI displays.",
            ),
            MailMessageFullDto(
                id = "m1-3", threadId = "t-design", direction = "outbound", status = "delivered",
                from = MailPersonDto("elena.vance@grapmail.com", "Elena Vance"),
                to = listOf("marcus@atelier-studio.example", "sora@atelier-studio.example"),
                subject = "Re: Brand refresh — final vector grid & type specimens",
                isRead = true, createdAt = now.minusSeconds(7200).toString(),
                bodyText = "Checked the dark-mode surface contrast and baseline — all primary chips pass WCAG AA. Ready for the v4 Figma export.",
            ),
            MailMessageFullDto(
                id = "m1-4", threadId = "t-design", direction = "inbound", status = "delivered",
                from = MailPersonDto("marcus@atelier-studio.example", "Marcus Chen"),
                to = listOf("elena.vance@grapmail.com"),
                cc = listOf("sora@atelier-studio.example"),
                subject = "Brand refresh — final vector grid & type specimens",
                isRead = false, isStarred = true, hasAttachments = true,
                createdAt = now.minusSeconds(18 * 60).toString(),
                bodyText = "Updated the display kerning on slides 4–9 and exported the Figma specs.\n\nHighlights in v4:\n• Tightened headline tracking to -0.4sp for 1440px displays\n• Verified light and dark token parity across cards and sheets\n• Packaged the vector grid and type specimens below\n\nReady for your sign-off before Friday?",
                attachments = listOf(
                    MailAttachmentDto(id = "att-brand", name = "grapmail-brand-spec-v4.pdf", mimeType = "application/pdf", sizeBytes = 2_840_120),
                    MailAttachmentDto(id = "att-tokens", name = "type-scale-tokens.json", mimeType = "application/json", sizeBytes = 42_680),
                ),
            ),
        ),
    )
}

private fun seedNewsletterThread(vm: MailboxViewModel) {
    val now = Instant.now()
    vm.seedThreadForSnapshot(
        forId = "t-news",
        messages = listOf(
            MailMessageFullDto(
                id = "m8", threadId = "t-news", direction = "inbound", status = "delivered",
                from = MailPersonDto("editorial@weeklydraft.example", "The Weekly Draft"),
                to = listOf("elena.vance@grapmail.com"),
                subject = "Issue #142 — Tactile interfaces & quiet software",
                isRead = true, createdAt = now.minusSeconds(86400 * 2).toString(),
                hasHtml = true,
                unsubscribe = MailUnsubscribeDto(
                    offers = listOf(MailUnsubscribeOfferDto("https://lists.weeklydraft.example/unsub?id=142", true)),
                    oneClickAdvertised = true,
                ),
            ),
        ),
    )
    vm.seedBodyForSnapshot(
        "m8",
        MailboxViewModel.BodyRender(
            html = """
                <table width="600" cellpadding="0" cellspacing="0" style="width:100%;border-collapse:separate;background-color:#f4f6fb;border-radius:18px">
                  <tr><td style="padding:26px;background-color:#152038;border-radius:18px;color:#ffffff;font-family:Arial,sans-serif">
                    <p style="margin:0;color:#8ea8ff;font-size:11px;font-weight:700;letter-spacing:2px">THE WEEKLY DRAFT · ISSUE 142</p>
                    <h1 style="margin:12px 0;font-size:30px;line-height:1.15">Tactile interfaces &amp; quiet software.</h1>
                    <p style="margin:0;color:#cbd5e1;font-size:15px;line-height:1.6">Why calm typography, honest privacy defaults, and fast native transitions outlast noisy feeds.</p>
                  </td></tr>
                  <tr><td style="padding:24px;color:#0f172a;font-family:Arial,sans-serif">
                    <h2 style="margin:0 0 8px;font-size:18px">1. Respecting attention</h2>
                    <p style="margin:0 0 16px;font-size:15px;line-height:1.6">Software that holds back remote tracking pixels by default gives readers control over when their inbox talks to third-party servers.</p>
                    <a href="https://weeklydraft.example/issue-142" style="display:inline-block;padding:10px 18px;border-radius:10px;background-color:#2f66ff;color:#ffffff;text-decoration:none;font-weight:600">Read full essay ↗</a>
                  </td></tr>
                </table>
                <p><img alt="Editorial diagram" data-blocked-src="https://images.weeklydraft.example/issue-142-hero.png"><img alt="Tracker" data-blocked-src="https://images.weeklydraft.example/pixel.gif"></p>
            """.trimIndent(),
            blockedImages = 2,
        ),
    )
}

private fun seedEncryptedThread(vm: MailboxViewModel) {
    val now = Instant.now()
    vm.seedThreadForSnapshot(
        forId = "t-enc",
        messages = listOf(
            MailMessageFullDto(
                id = "m6", threadId = "t-enc", direction = "inbound", status = "delivered",
                from = MailPersonDto("henrik@nordic-security.example", "Dr. Henrik Lindqvist"),
                to = listOf("elena.vance@grapmail.com"),
                subject = "Hardware key recovery ceremony & audit notes",
                isRead = true, isStarred = true, isE2eEncrypted = true,
                createdAt = now.minusSeconds(86400).toString(),
                e2eEnvelope = Json.parseToJsonElement(
                    """{"v":2,"alg":"X25519+HKDF-SHA256+AES-256-GCM (multi-recipient)","iv":"c2VlZC1pdg==","ct":"c2VlZC1jdA==","tag":"c2VlZC10YWc=","recipients":[{"epk":"c2VlZC1lcGs=","wrapIv":"c2VlZC13aXY=","wrappedKey":"c2VlZC13aw==","wrapTag":"c2VlZC13dA=="}]}"""
                ) as? JsonObject,
            ),
        ),
    )
}

private suspend fun seedDemoCache(rows: List<MailMessageDto>) {
    val subRows = listOf(
        MailMessageDto(
            id = "s1", threadId = "t-s1", direction = "inbound", status = "delivered",
            from = MailPersonDto("editorial@weeklydraft.example", "The Weekly Draft"),
            to = listOf("elena.vance@grapmail.com"), subject = "Issue #142 — Tactile interfaces",
            snippet = "Three essays on Swiss typography.", isRead = true,
            unsubscribe = MailUnsubscribeDto(
                offers = listOf(MailUnsubscribeOfferDto("https://lists.weeklydraft.example/unsub?id=142", true)),
                oneClickAdvertised = true, mailtoOneClick = "mailto:leave@weeklydraft.example",
            ),
            createdAt = "2026-11-24T08:00:00Z",
        ),
        MailMessageDto(
            id = "s2", threadId = "t-s2", direction = "inbound", status = "delivered",
            from = MailPersonDto("editorial@weeklydraft.example", "The Weekly Draft"),
            to = listOf("elena.vance@grapmail.com"), subject = "Issue #141 — Designing for focus",
            snippet = "Calm defaults in modern tools.", isRead = true,
            unsubscribe = MailUnsubscribeDto(
                offers = listOf(MailUnsubscribeOfferDto("https://lists.weeklydraft.example/unsub?id=141", true)),
                oneClickAdvertised = true,
            ),
            createdAt = "2026-11-17T08:00:00Z",
        ),
        MailMessageDto(
            id = "s3", threadId = "t-s3", direction = "inbound", status = "delivered",
            from = MailPersonDto("digest@swisstype.example.org", "Swiss Type Journal"),
            to = listOf("elena.vance@grapmail.com"), subject = "November grid & specimen archive",
            snippet = "New Neo-Grotesque releases.", isRead = true,
            unsubscribe = MailUnsubscribeDto(raw = "<https://swisstype.example.org/unsub?id=9>"),
            createdAt = "2026-11-20T09:00:00Z",
        ),
        MailMessageDto(
            id = "s4", threadId = "t-s4", direction = "inbound", status = "delivered",
            from = MailPersonDto("digest@swisstype.example.org", "Swiss Type Journal"),
            to = listOf("elena.vance@grapmail.com"), subject = "October grid & specimen archive",
            snippet = "Variable font optical sizes.", isRead = true,
            unsubscribe = MailUnsubscribeDto(raw = "<https://swisstype.example.org/unsub?id=8>"),
            createdAt = "2026-11-10T09:00:00Z",
        ),
    )
    MailStore.putPage(
        MailBox.Inbox, null,
        MailInboxResponse(success = true, box = "inbox", total = rows.size + subRows.size, messages = rows + subRows),
        replace = true,
    )
    MailStore.putBody(
        MailMessageFullDto(
            id = "m1-4", threadId = "t-design", direction = "inbound", status = "delivered",
            from = MailPersonDto("marcus@atelier-studio.example", "Marcus Chen"),
            to = listOf("elena.vance@grapmail.com"),
            subject = "Brand refresh — final vector grid & type specimens",
            isRead = false, hasAttachments = true, createdAt = Instant.now().minusSeconds(18 * 60).toString(),
            attachments = listOf(
                MailAttachmentDto(id = "att-brand", name = "grapmail-brand-spec-v4.pdf", mimeType = "application/pdf", sizeBytes = 2_840_120),
                MailAttachmentDto(id = "att-tokens", name = "type-scale-tokens.json", mimeType = "application/json", sizeBytes = 42_680),
            ),
        ),
    )
    MailStore.putBody(
        MailMessageFullDto(
            id = "m4", threadId = "t-invoice", direction = "inbound", status = "delivered",
            from = MailPersonDto("amira@northwind.example", "Amira Rahman"),
            to = listOf("elena.vance@grapmail.com"),
            subject = "Q4 studio retainer & facilities invoice #2026-114",
            isRead = true, hasAttachments = true, createdAt = Instant.now().minusSeconds(3 * 3600).toString(),
            attachments = listOf(
                MailAttachmentDto(id = "att-inv", name = "northwind-invoice-2026-114.pdf", mimeType = "application/pdf", sizeBytes = 1_415_732),
            ),
        ),
    )
    MailStore.putBody(
        MailMessageFullDto(
            id = "m2", threadId = "t-travel", direction = "inbound", status = "delivered",
            from = MailPersonDto("bookings@nordicair.example", "Nordic Air"),
            to = listOf("elena.vance@grapmail.com"),
            subject = "Flight confirmation NA 418 — Zurich to Copenhagen",
            isRead = false, hasAttachments = true, createdAt = Instant.now().minusSeconds(42 * 60).toString(),
            attachments = listOf(
                MailAttachmentDto(id = "att-pass", name = "boarding-pass-na418.pdf", mimeType = "application/pdf", sizeBytes = 318_400),
            ),
        ),
    )
}

private fun demoProfile(): ProfileDto = ProfileDto(
    id = "demo-elena",
    name = "Elena Vance",
    firstName = "Elena",
    lastName = "Vance",
    birthday = "1994-08-19",
    gender = "Female",
    address = "elena.vance@grapmail.com",
    handle = "elena.vance",
    domain = "grapmail.com",
    phone = "+41 44 555 0194",
    phoneVerified = true,
    twoFactor = "email",
    twoFactorEnabled = true,
    displayName = "Elena Vance",
    recoveryEmail = "elena@vance-studio.example",
    recoveryEmailVerified = true,
    fromHeader = "Elena Vance <elena.vance@grapmail.com>",
    createdAt = "2026-03-14T09:41:00.000Z",
    passwordChangedAt = "2026-09-12T10:15:00.000Z",
    termsAcceptedAt = "2026-03-14T09:42:00.000Z",
    backupCodesRemaining = 8,
    settings = ProfileSettingsDto(
        signature = "Sent with GrapMail",
        density = "comfortable",
        notes = listOf("Finalize Q4 design review deck", "Send updated NDA to Havenworks"),
        tasks = listOf("Approve Figma vector grid", "Check Zurich–Copenhagen boarding pass"),
        signatures = mapOf("studio@grapmail.com" to "Vance Design Studio"),
        blockedSenders = listOf("promo@bulk-deals.example"),
        safeSenders = listOf("marcus@atelier-studio.example", "amira@northwind.example"),
    ),
    aliases = listOf(
        ProfileAliasDto(address = "studio@grapmail.com", label = "Studio", isActive = true, createdAt = "2026-04-02T10:00:00.000Z"),
        ProfileAliasDto(address = "travel@grapmail.com", label = "Travel", isActive = true, createdAt = "2026-05-18T14:20:00.000Z"),
    ),
    connections = listOf(
        ProfileConnectionDto(product = "Grapsee Drive", host = null, scopes = listOf("profile", "files"), connectedAt = "2026-07-01T08:00:00.000Z"),
        ProfileConnectionDto(product = "Atelier SSO", host = "sso.atelier-studio.example", scopes = listOf("profile", "email"), connectedAt = "2026-08-19T15:20:00.000Z"),
    ),
)

private val demoIdentity = MailE2e.Identity(
    encryption = MailE2e.ProtectedPrivateKey(
        kind = "X25519",
        publicKey = "ZGVtby1wdWJsaWMteDI1NTE5LWtleS1mb3ItZ3JhcG1haWw=",
        iterations = 600_000,
        salt = "ZGVtby1zYWx0", iv = "ZGVtby1pdg==", ct = "ZGVtby1jdA==",
    ),
    signing = MailE2e.ProtectedPrivateKey(
        kind = "Ed25519",
        publicKey = "ZGVtby1wdWJsaWMtZWQyNTUxOS1rZXktZm9yLWdyYXBtYWls",
        iterations = 600_000,
        salt = "ZGVtby1zYWx0", iv = "ZGVtby1pdg==", ct = "ZGVtby1jdA==",
    ),
    createdAt = "2026-08-02T10:00:00Z",
)

private const val demoFingerprint =
    "7C:0F:2A:91:B4:D8:3E:6C:05:AA:1F:BE:D2:48:67:90:13:EC:B6:24:FA:5D:71:09:8C:30:B7:E4:16:9F:A2:C8"

private val demoPublished = MailE2eKeysDto(
    userId = "demo-elena",
    encryption = MailE2eKeyDto(
        publicKey = "ZGVtby1wdWJsaWMteDI1NTE5LWtleS1mb3ItZ3JhcG1haWw=",
        fingerprint = demoFingerprint,
        keyId = "7C0FB624FA5D7109",
    ),
    signing = null,
    publishedAt = "2026-08-02T10:05:00Z",
    updatedAt = "2026-08-02T10:05:00Z",
)

private val demoLookup = listOf(
    MailE2eLookupEntryDto(
        status = "found", address = "marcus@atelier-studio.example",
        signing = MailE2eKeyDto(
            publicKey = "bWFyY3VzLXB1YmxpYy1zaWduaW5nLWtleQ==",
            fingerprint = "9E:41:77:CA:0B:5D:2F:86:A3:C1:F0:64:18:9B:7A:DD:0C:E7:35:52:BF:81:6E:40:2D:A9:15:C3:FE:07:84:B1",
            keyId = "9E413552BF816E40",
        ),
        reason = null, domain = "atelier-studio.example",
    ),
    MailE2eLookupEntryDto(
        status = "found", address = "henrik@nordic-security.example",
        signing = MailE2eKeyDto(
            publicKey = "aGVucmlrLXB1YmxpYy1zaWduaW5nLWtleQ==",
            fingerprint = "4B:18:90:D3:7E:22:6A:F1:88:3C:09:E4:51:BA:C7:30:9F:42:D1:68:0E:7A:B3:29:C4:11:8F:56:AA:02:E9:74",
            keyId = "4B18D1680E7AB329",
        ),
        reason = null, domain = "nordic-security.example",
    ),
)

private val demoConnectedProviders = listOf(
    ProviderAccountDto(
        id = "prov-1", provider = "microsoft", providerEmail = "elena.vance@outlook.example",
        status = "connected", lastSyncAt = Instant.now().minusSeconds(240).toString(),
        createdAt = "2026-09-10T08:00:00Z",
    ),
    ProviderAccountDto(
        id = "prov-2", provider = "imap", providerEmail = "studio@vance-design.example",
        status = "connected", lastSyncAt = Instant.now().minusSeconds(720).toString(),
        createdAt = "2026-10-04T11:30:00Z",
    ),
)

private fun demoSecurityOverview(): SecurityOverviewResponse {
    val now = Instant.now()
    return SecurityOverviewResponse(
        success = true,
        generatedAt = now.toString(),
        account = SecurityOverviewAccountDto(
            address = "elena.vance@grapmail.com",
            displayName = "Elena Vance",
            hasPassword = true,
            passwordChangeRequired = false,
        ),
        score = SafetyScoreDto(
            version = 1, value = 92, level = "strong", earnedPoints = 92, possiblePoints = 100,
        ),
        checklist = listOf(
            SafetyChecklistItemDto("two_factor", "done", 25, 25, "two_factor"),
            SafetyChecklistItemDto("passkey", "done", 25, 25, "passkeys", SafetyChecklistMetaDto(count = 2)),
            SafetyChecklistItemDto("recovery_email", "done", 20, 20, "recovery_email"),
            SafetyChecklistItemDto("recovery_codes", "done", 15, 15, "recovery_codes", SafetyChecklistMetaDto(remaining = 8)),
            SafetyChecklistItemDto("password_health", "done", 15, 15, "password", SafetyChecklistMetaDto(status = "ok")),
        ),
        devices = listOf(
            SecurityDeviceDto(
                id = "td-galaxy", kind = "app", platform = "Android", formFactor = "phone",
                label = "Samsung Galaxy S24 Ultra", os = "Android 14",
                current = true, trusted = true, attestation = "strong",
                location = SecurityLocationDto(label = "Zurich, Switzerland", city = "Zurich", country = "CH"),
                lastActiveAt = now.minusSeconds(60).toString(), activeSessionCount = 1,
            ),
            SecurityDeviceDto(
                id = "td-mac", kind = "browser", platform = "macOS", formFactor = "desktop",
                label = "Chrome on MacBook Pro", browser = "Chrome 131", os = "macOS 15",
                current = false, trusted = true,
                location = SecurityLocationDto(label = "Zurich, Switzerland", city = "Zurich", country = "CH"),
                lastActiveAt = now.minusSeconds(3600 * 2).toString(), activeSessionCount = 1,
                removal = SecurityDeviceRemovalDto("session", "ss-mac"),
            ),
            SecurityDeviceDto(
                id = "td-tab", kind = "app", platform = "Android", formFactor = "tablet",
                label = "Galaxy Tab S9", os = "Android 14",
                current = false, trusted = true,
                location = SecurityLocationDto(label = "Basel, Switzerland", city = "Basel", country = "CH"),
                lastActiveAt = now.minusSeconds(86400).toString(), activeSessionCount = 1,
                removal = SecurityDeviceRemovalDto("trusted_device", "td-tab"),
            ),
        ),
        events = listOf(
            SecurityTimelineEventDto(
                id = "ev-1", type = "signin", occurredAt = now.minusSeconds(1800).toString(),
                deviceId = "td-galaxy", deviceLabel = "Samsung Galaxy S24 Ultra",
                location = SecurityLocationDto(label = "Zurich, Switzerland"),
                risk = "low", action = "ALLOW", factor = "passkey", acknowledged = true,
            ),
            SecurityTimelineEventDto(
                id = "ev-2", type = "passkey_added", occurredAt = now.minusSeconds(86400 * 3).toString(),
                deviceId = "td-mac", deviceLabel = "Chrome on MacBook Pro",
                location = SecurityLocationDto(label = "Zurich, Switzerland"),
                risk = "low", action = "ALLOW", factor = "passkey", acknowledged = true,
            ),
            SecurityTimelineEventDto(
                id = "ev-3", type = "recovery_email_verified", occurredAt = now.minusSeconds(86400 * 9).toString(),
                deviceId = "td-mac", deviceLabel = "Chrome on MacBook Pro",
                location = SecurityLocationDto(label = "Zurich, Switzerland"),
                risk = "low", action = "ALLOW", acknowledged = true,
            ),
        ),
    )
}
