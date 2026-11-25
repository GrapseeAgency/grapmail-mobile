import SwiftUI
import Combine
import UIKit
import UniformTypeIdentifiers
import PhotosUI

struct ThreadView: View {
    @ObservedObject var vm: MailboxModel
    /// The encryption sector's state, for the decrypt path on an encrypted
    /// message. Same single instance compose reads, so the answer to "is the
    /// key open" cannot differ between the two screens.
    @ObservedObject var e2e: E2eModel
    let id: String
    /// True when the id came from a row's `threadId`; otherwise it is a single
    /// message id and the thread route would not know it.
    let isThread: Bool
    let onBack: () -> Void
    /// The row to answer and how: "reply", "all" or "forward". Compose builds
    /// the quote and the addressing from the FULL row, so the thread hands
    /// over the id it actually displayed -- same contract as Android's.
    let onReply: (String, String) -> Void
    /// Step to the previous / next loaded row without leaving the reader. The
    /// model names the target, including whether it is a conversation: the
    /// reader does not know the order, and it must not guess the route.
    let onStepPrev: (MailStep) -> Void
    let onStepNext: (MailStep) -> Void
    let onContacts: () -> Void
    var initialMessageId: String? = nil
    var openMessageActions = false
    /// Hosted as the iPad's reader column beside the list (RootView `.home`,
    /// MailTwoPane.swift): the list stays on screen, so there is no Back and
    /// no second bottom bar, and the column takes ⌘R / ⌘⌫. False -- the
    /// pushed phone reader -- changes nothing.
    var inColumn = false
    @State private var showNotificationActions = false
    @Environment(\.scenePhase) private var scenePhase
    /// Per-attachment, not global: a download result belongs to the row that
    /// asked for it, and a shared variable would let a later failure overwrite an
    /// earlier success on screen.
    @State private var notices: [String: String] = [:]
    @State private var downloading: Set<String> = []
    @State private var isVisible = false
    /// The card the toolbar acts on: the last one opened, else the one the
    /// reader was opened on, else the latest.
    @State private var expandedMessageId: String?
    @State private var didFocus = false
    @State private var pendingAction = false
    @State private var filed: [String: MailAction] = [:]
    @State private var feedback: [String: (text: String, error: Bool)] = [:]
    // ── Round 8 conversation state (round8-spec §5.3, Android
    // `ConversationExpansion`) ──────────────────────────────────────────────
    /// What the person opened by hand; wins over the defaults while the
    /// conversation is open.
    @State private var foldOverrides: [String: Bool] = [:]
    /// Messages already seen by this reader, and the ones that were unread
    /// the first time they were seen (they stay open after being read).
    @State private var seenIds: Set<String> = []
    @State private var unreadAtFirstSight: Set<String> = []
    /// Every message that has been the latest keeps its default: a reply
    /// arriving while one is read must not fold the one being read.
    @State private var wasLatest: Set<String> = []
    /// True once an "N earlier messages" bar has been tapped.
    @State private var earlierRevealed = false
    /// The scroll position. A plain reference in `@State` (not observed
    /// here), so a scroll frame redraws only the header pieces that observe
    /// it, never the conversation list.
    @State private var scrollState = ReaderScrollState()
    @Environment(\.dynamicTypeSize) private var dts
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var activeMessage: MailMessageFull? {
        vm.thread.messages.first { $0.id == (expandedMessageId ?? initialMessageId) } ?? vm.thread.messages.last
    }

    private var threadSubject: String {
        vm.thread.messages.first?.subjectOrNone ?? String(localized: "(no subject)")
    }

    /// The account's own addresses, lowercased: the signed-in address, the
    /// mailbox's own (`/me`), and every alias this mailbox has read (§5.3
    /// "own messages"; outbound messages count as own regardless).
    private var myAddresses: Set<String> {
        var set = Set<String>()
        for raw in [SessionStore.address, vm.state.accountAddress] {
            if let address = ReaderConversation.bareAddress(raw) { set.insert(address) }
        }
        for alias in vm.aliasesState.list {
            if let address = ReaderConversation.bareAddress(alias.address) { set.insert(address) }
        }
        return set
    }

    /// Open on arrival: the latest message, every message that was unread
    /// when this reader first saw it, and the one the reader was opened on.
    private func isExpanded(_ message: MailMessageFull) -> Bool {
        if let choice = foldOverrides[message.id] { return choice }
        if message.id == vm.thread.messages.last?.id || wasLatest.contains(message.id) { return true }
        if message.id == initialMessageId { return true }
        if unreadAtFirstSight.contains(message.id) { return true }
        return !seenIds.contains(message.id) && !message.isRead
    }

    private func noteMessages() {
        var seen = seenIds
        var unread = unreadAtFirstSight
        for message in vm.thread.messages where !seen.contains(message.id) {
            seen.insert(message.id)
            if !message.isRead { unread.insert(message.id) }
        }
        if seen != seenIds { seenIds = seen }
        if unread != unreadAtFirstSight { unreadAtFirstSight = unread }
        if let last = vm.thread.messages.last?.id, !wasLatest.contains(last) { wasLatest.insert(last) }
    }

    private func expand(_ messageId: String) {
        withAnimation(GMMotion.gated(GMMotion.standard(GMMotion.medium), reduced: reduceMotion)) {
            foldOverrides[messageId] = true
            expandedMessageId = messageId
        }
    }

    /// The bar under the conversation is hidden in Spam and Trash, and on an
    /// encrypted message this device holds no key for (the encrypted block
    /// on the card says what to do there). The last open card's own Reply ·
    /// Reply all · Forward row is always there.
    private func showsQuickReply(for message: MailMessageFull) -> Bool {
        if [.spam, .trash].contains(vm.state.box) { return false }
        if (message.isE2eEncrypted || message.e2eEnvelope != nil) && e2e.state.record == nil { return false }
        return true
    }

    private var hasEncryptedMessage: Bool {
        vm.thread.messages.contains { $0.isE2eEncrypted || $0.e2eEnvelope != nil }
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            // Offline: whatever was already opened stays readable under this.
            GMOfflineBanner()
            if vm.thread.messages.isEmpty && vm.thread.error == nil {
                if vm.thread.waitingForNetwork {
                    GMSkeletonBlock(lines: 6)
                        .padding(.horizontal, 20)
                }
                Spacer(minLength: 0)
            } else if vm.thread.error != nil, vm.thread.messages.isEmpty {
                failedBody
            } else {
                conversation
                if let activeMessage {
                    VStack(spacing: 0) {
                    if let outcome = feedback[activeMessage.id] {
                        Text(outcome.text)
                            .gmFont(12)
                            .foregroundColor(outcome.error ? GM.error : GM.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 18)
                            .padding(.vertical, 4)
                    }
                    MailSmartPanelView(
                        message: activeMessage,
                        threadMessages: vm.thread.messages.map { m in
                            ThreadMessageText(
                                senderName: m.from?.name ?? m.from?.address ?? "",
                                body: m.bodyText ?? "",
                                fromMe: m.direction == "outbound")
                        })
                    SmartReplyStrip(text: activeMessage.bodyText ?? "") { suggestion in
                        MailFeaturePrefs.shared.stageInsert(suggestion)
                        onReply(activeMessage.id, "reply")
                    }
                    }
                    // Lined up with the messages above on wide windows.
                    .gmMaxWidth(ReaderLayout.column + 24)
                    // §5.7: outside the scroll content, so its height changes
                    // the scroll view's frame, never its offset.
                    if showsQuickReply(for: activeMessage) {
                        QuickReplyBar(
                            targetName: ReaderConversation.replyTargetFirstName(
                                activeMessage, own: ReaderConversation.isOwn(activeMessage, me: myAddresses)),
                            onReply: { onReply(activeMessage.id, "reply") },
                            onReplyAll: { onReply(activeMessage.id, "all") },
                            onForward: { onReply(activeMessage.id, "forward") }
                        )
                        .gmElevation(.level2)
                    }
                    if !inColumn {
                        MailBottomBar(inboxSelected: true, unread: vm.state.unreadCount,
                                      onMail: onBack, onContacts: onContacts, overList: false)
                    }
                }
            }
        }
        .background(GM.bg.ignoresSafeArea())
        .confirmationDialog("Message actions", isPresented: $showNotificationActions, presenting: activeMessage) { message in
            Button("Reply all") { onReply(message.id, "all") }
            Button("Forward") { onReply(message.id, "forward") }
            Button("Archive") { file(message, action: .archive, success: "Message archived.") }
            Button("Move to Trash", role: .destructive) { file(message, action: .trash, success: "Message moved to Trash.") }
        }
        .background {
            if inColumn, let message = activeMessage {
                ReaderColumnShortcuts(
                    canTrash: vm.state.box != .trash && !pendingAction && filed[message.id] != .trash,
                    onReply: { onReply(message.id, "reply") },
                    onTrash: { file(message, action: .trash, success: "Message moved to Trash.") }
                )
            }
        }
        .task(id: MailStep(id: id, isThread: isThread)) {
            expandedMessageId = nil
            foldOverrides = [:]
            earlierRevealed = false
            isThread ? vm.openThread(id, focusedID: initialMessageId) : vm.openMessage(id)
        }
        // The first sight of each message decides its default (open when it
        // was unread); recorded before anything can mark it read.
        .onAppear { noteMessages() }
        .onChange(of: vm.thread.messages.map(\.id)) { _ in noteMessages() }
        // Reads the local key record only (no network), so the quick reply
        // bar knows whether this device can answer an encrypted message.
        .task(id: hasEncryptedMessage) {
            if hasEncryptedMessage && e2e.state.record == nil { e2e.load(includeDirectory: false) }
        }
        .onAppear { isVisible = true }
        // The alias list, for "You" on mail sent from an alias. Read once per
        // session (a no-op when it already answered).
        .task { vm.loadAliases() }
        .onReceive(NotificationCenter.default.publisher(for: MailPoll.didReadStats)) { notification in
            guard isVisible, scenePhase == .active, let result = notification.object as? MailPoll.PollResult else { return }
            vm.acceptPoll(result, atTop: false)
        }
        .onDisappear {
            isVisible = false
            vm.leaveReader(id, isThread: isThread)
        }
        .accessibilityIdentifier("mail-thread")
    }

    // ── the conversation ────────────────────────────────────────────────────

    /// Where the reader lands on open (§5.3): the message it was opened on,
    /// else the first unread one, else the latest. Nil while that is not
    /// known yet (the focused message has not arrived and the thread is
    /// still loading).
    private var focusTargetId: String? {
        let messages = vm.thread.messages
        guard !messages.isEmpty else { return nil }
        if let initialMessageId {
            if messages.contains(where: { $0.id == initialMessageId }) { return initialMessageId }
            if vm.thread.loading { return nil }
        }
        if let firstUnread = messages.first(where: {
            unreadAtFirstSight.contains($0.id) || (!seenIds.contains($0.id) && !$0.isRead)
        }) {
            return firstUnread.id
        }
        return messages.last?.id
    }

    private var conversation: some View {
        let me = myAddresses
        let rows = ReaderConversation.rows(vm.thread.messages, isExpanded: isExpanded,
                                           earlierRevealed: earlierRevealed)
        // Reply · Reply all · Forward sit on the LAST open card only.
        let lastExpandedId = rows.last(where: { row in
            if case let .message(_, expanded, _) = row { return expanded }
            return false
        })?.id
        return ScrollViewReader { scroll in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: ReaderLayout.cardGap) {
                    subjectHeader
                    if isThread && (vm.thread.hasMore || vm.thread.loadingEarlier) {
                        ReaderOutlineButton(
                            vm.thread.loadingEarlier ? "Loading earlier messages…" : "Load earlier messages",
                            identifier: "mail-thread-load-earlier",
                            action: { vm.loadEarlierThread(id) }
                        )
                        .disabled(vm.thread.loadingEarlier)
                    }
                    ForEach(rows) { row in
                        rowView(row, me: me, lastExpandedId: lastExpandedId)
                            .readerCardFrame(row.id)
                    }
                }
                .padding(.top, 8)
                .padding(.bottom, 16)
                // The scroll offset (subject header, §5.4) and the list's own
                // frame (reading-position anchor, §5.5) from the list itself,
                // which is always realised (a lazy row is not).
                .background(alignment: .top) { ReaderScrollOffsetProbe() }
                .readerCardFrame(ReaderScrollState.contentKey)
                // The 680 pt reading column, centred on iPad and other wide
                // windows; a 12 pt margin on a phone.
                .readerColumn()
            }
            .coordinateSpace(name: ReaderLayout.scrollSpace)
            .background {
                GeometryReader { proxy in
                    Color.clear
                        .onAppear { scrollState.viewportHeight = proxy.size.height }
                        .onChange(of: proxy.size.height) { height in scrollState.viewportHeight = height }
                }
            }
            .onPreferenceChange(ReaderScrollOffsetKey.self) { offset in
                scrollState.update(rawOffset: offset)
            }
            .onPreferenceChange(ReaderCardFramesKey.self) { frames in
                if let correction = scrollState.readingPositionCorrection(frames) {
                    scroll.scrollTo(correction.id, anchor: correction.anchor)
                }
            }
            // No animation on first layout: the jump to the landing card is
            // instant, and only when it is not already the first card.
            .task(id: focusTargetId) {
                guard !didFocus, let target = focusTargetId else { return }
                didFocus = true
                if initialMessageId == target { expandedMessageId = target }
                if target != vm.thread.messages.first?.id {
                    scroll.scrollTo(target, anchor: .top)
                }
                if openMessageActions && initialMessageId == target { showNotificationActions = true }
            }
        }
    }

    @ViewBuilder private func rowView(_ row: ConversationRow, me: Set<String>, lastExpandedId: String?) -> some View {
        switch row {
        case let .earlier(hidden, _):
            EarlierMessagesBar(hidden: hidden) {
                withAnimation(GMMotion.gated(GMMotion.standard(GMMotion.medium), reduced: reduceMotion)) {
                    earlierRevealed = true
                }
            }
        case let .message(message, expanded, revealIndex):
            ThreadMessageCard(
                vm: vm,
                message: message,
                e2e: e2e,
                expanded: expanded,
                own: ReaderConversation.isOwn(message, me: me),
                me: me,
                showsFooter: expanded && message.id == lastExpandedId,
                onExpand: { expand(message.id) },
                downloading: downloading,
                notices: notices,
                onReply: { mode in onReply(message.id, mode) },
                onDownload: { attachment in
                    downloading.insert(attachment.id)
                    vm.downloadAttachment(attachment) { outcome in
                        downloading.remove(attachment.id)
                        notices[attachment.id] = outcome
                    }
                }
            )
            .transition(revealTransition(revealIndex))
        }
    }

    /// The folded cards appear one after another, 30 ms apart, when "N
    /// earlier messages" is tapped; at once under Reduce Motion, and never
    /// on first render (only a row that was hidden carries an index).
    private func revealTransition(_ index: Int?) -> AnyTransition {
        guard let index, !reduceMotion else { return AnyTransition.identity }
        return AnyTransition.opacity.animation(
            GMMotion.standard(GMMotion.medium).delay(Double(min(index, 20)) * 0.03))
    }

    private var header: some View {
        HStack(spacing: 0) {
            if !inColumn {
                ReaderIconButton("Back", icon: "chevron.backward", action: onBack)
            }
            // The subject, once its large title has scrolled away (§5.4).
            ReaderCompactTitle(scroll: scrollState, subject: threadSubject)
            if let message = activeMessage {
                if ![.archive, .spam, .trash].contains(vm.state.box) {
                    ReaderIconButton("Archive message", icon: "archivebox") {
                        file(message, action: .archive, success: "Message archived.")
                    }
                    .disabled(pendingAction || filed[message.id] != nil)
                }
                if vm.state.box != .trash {
                    ReaderIconButton("Move message to Trash", icon: "trash") {
                        file(message, action: .trash, success: "Message moved to Trash.")
                    }
                    .disabled(pendingAction || filed[message.id] == .trash)
                }
                if message.isRead {
                    ReaderIconButton("Mark unread", icon: "envelope.badge") {
                        pendingAction = true
                        vm.markUnread(message.id) { error in
                            pendingAction = false
                            feedback[message.id] = (error ?? String(localized: "Marked unread."), error != nil)
                        }
                    }
                    .disabled(pendingAction)
                    .accessibilityIdentifier("mail-mark-unread")
                }
            }
            let near = vm.neighbours(of: id)
            if near.prev != nil || near.next != nil {
                Menu {
                    let at = vm.loadedPosition(of: id)
                    let total = vm.loadedRowCount()
                    if at > 0 && total > 1 { Text("\(at) of \(total) loaded") }
                    Button("Previous message") { if let step = near.prev { onStepPrev(step) } }
                        .disabled(near.prev == nil)
                        .accessibilityIdentifier("mail-thread-prev")
                    Button("Next message") { if let step = near.next { onStepNext(step) } }
                        .disabled(near.next == nil)
                        .accessibilityIdentifier("mail-thread-next")
                } label: {
                    Image(systemName: "ellipsis")
                        .gmFont(19, .medium, maxSize: 28)
                        .foregroundColor(GM.textPrimary)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("Navigate messages")
            }
        }
        // Without Back (column mode) and before the first message loads the
        // row would otherwise collapse; 44 is the height Back always gave it.
        .frame(minHeight: 44)
        .padding(.horizontal, 6)
        .padding(.vertical, 2)
        // The 1 pt bottom border fades in with the compact title.
        .overlay(alignment: .bottom) { ReaderToolbarHairline(scroll: scrollState) }
    }

    /// "Inbox · 3 people · 5 messages", the chips under the large subject
    /// (Android `conversationFacts`).
    private var conversationChips: [String] {
        let messages = vm.thread.messages
        var chips = [vm.state.box.title]
        let people = ReaderConversation.participantCount(messages)
        if people > 1 { chips.append(String(localized: "\(people) people", table: "Reader")) }
        if messages.count > 1 { chips.append(String(localized: "\(messages.count) messages", table: "Reader")) }
        return chips
    }

    private var subjectHeader: some View {
        ReaderSubjectHeader(scroll: scrollState, subject: threadSubject, chips: conversationChips) {
            if let message = activeMessage {
                ReaderIconButton(message.isStarred ? "Unstar message" : "Star message",
                                 icon: message.isStarred ? "star.fill" : "star",
                                 tint: message.isStarred ? GM.star : GM.textSecondary) {
                    vm.actOn(message.id, message.isStarred ? .unstar : .star)
                }
            }
        }
        .padding(.leading, 6)
        .padding(.trailing, 2)
        .padding(.bottom, 6)
    }

    /// `success` is a resource so the literal at each call site is extracted
    /// into the String Catalog.
    private func file(_ message: MailMessageFull, action: MailAction, success: LocalizedStringResource) {
        pendingAction = true
        vm.actOn(message.id, action) { error in
            pendingAction = false
            if error == nil { filed[message.id] = action }
            feedback[message.id] = (error ?? String(localized: success), error != nil)
        }
    }

    private var failedBody: some View {
        VStack(spacing: 8) {
            Text("This conversation could not be opened.")
                .gmFont(17, .semibold)
                .foregroundColor(GM.textPrimary)
                .multilineTextAlignment(.center)
            Text(vm.thread.error ?? String(localized: "Check your connection and try again."))
                .gmFont(14).foregroundColor(GM.textSecondary)
                .multilineTextAlignment(.center)
            GradientButton(title: String(localized: "Try again"),
                           action: { isThread ? vm.openThread(id, focusedID: initialMessageId) : vm.openMessage(id) })
                .padding(.top, 8)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct ReaderIconButton: View {
    /// A resource: every caller passes a literal (the star button a ternary
    /// of two), which the String Catalog extracts; VoiceOver reads the
    /// translation.
    let label: LocalizedStringResource
    let icon: String
    let tint: Color
    let action: () -> Void

    @Environment(\.isEnabled) private var enabled

    init(_ label: LocalizedStringResource, icon: String, tint: Color = GM.textPrimary, action: @escaping () -> Void) {
        self.label = label
        self.icon = icon
        self.tint = tint
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            // Grows with the text size up to a cap, inside a hit square
            // that never drops below 44 pt.
            Image(systemName: icon)
                .gmFont(18, .medium, maxSize: 28)
                .foregroundColor(enabled ? tint : GM.textTertiary.opacity(0.55))
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
        .accessibilityShowsLargeContentViewer {
            Label { Text(label) } icon: { Image(systemName: icon) }
        }
    }
}

private struct ThreadMessageCard: View {
    let vm: MailboxModel
    let message: MailMessageFull
    let e2e: E2eModel
    let expanded: Bool
    /// The account's own message (§5.3): `ownMessageFill`, the leading
    /// accent bar, "You" as the sender.
    let own: Bool
    /// The account's addresses, lowercased, for the "to me" line.
    let me: Set<String>
    /// Reply · Reply all · Forward under the body: the last open card only.
    let showsFooter: Bool
    let onExpand: () -> Void
    let downloading: Set<String>
    let notices: [String: String]
    let onReply: (String) -> Void
    let onDownload: (MailAttachment) -> Void

    /// The unsubscribe confirmation. Owned by the CARD, not the screen: the offer is
    /// a property of one message, and a second message in the same thread can carry a
    /// different one. (It lived in `ThreadView` first, which compiled as
    /// "cannot find 'showUnsub' in scope" -- four errors, and the CI run named them.)
    @State private var showUnsub = false
    @State private var unsubNote: String?
    @State private var showDetails = false
    @State private var renderAttempt = 0
    @State private var renderLoading = false

    private struct RenderRequest: Equatable {
        let allowImages: Bool
        let attempt: Int
    }

    /// One note line for the affordances that leave the app: sharing and
    /// printing both ask the system for something, and a refusal has to be said
    /// here rather than left as a button that appeared to do nothing.
    @State private var handoffNote: String?
    /// The share sheet's own presentation state. The sheet is the system's, so
    /// this app only ever knows that it was asked for.
    @State private var showShare = false
    /// The menu's spam and block calls answer on the card the same way the
    /// handoff affordances do; a block files nothing, so without a line the
    /// button would look like it did nothing.
    @State private var decisionNote: String?
    /// This conversation's and sender's notification rules (NotificationPolicy.swift),
    /// read from this account's copy so the menu offers the right verb.
    @State private var policyMuted = false
    @State private var policyWatched = false
    @State private var policyVip = false
    private var threadKey: String { message.threadId?.nilIfBlank() ?? message.id }
    private var senderKey: String? { message.from?.address?.nilIfBlank() }
    /// The images the sender's HTML part points at, and whether this message has
    /// asked for them. Both belong to the message, and the card is rebuilt per
    /// message -- an opt-in given for one mail must not travel to the next.
    // Settings → Load remote images: on loads them as the message opens.
    @State private var shownImages = MailFeaturePrefs.shared.allowRemoteImages
    private var showsBody: Bool { expanded }
    @State private var detailsError: String?
    @State private var detailsAttempt = 0
    private var needsFormattedBody: Bool {
        showsBody && !message.isE2eEncrypted && message.e2eEnvelope == nil
            && message.hasHtml
    }
    @State private var formattedBody: MailboxModel.BodyRender?
    /// The formatted body's measured height. The web view sizes itself to its
    /// document and the reader owns scrolling, so this is the frame the island
    /// renders inside. nil until this card measured its body: the height then comes from
    /// this session's memory (`ReaderSession`), else the 120 pt placeholder
    /// (§5.5), so a card opened again does not jump.
    @State private var htmlBodyHeight: CGFloat?
    /// The formatted body has reported a height: the skeleton crossfades out.
    @State private var htmlMeasured = false
    /// The formatted-body request itself failed (not the server's own
    /// "could not render"): the text part is shown instead of a skeleton.
    @State private var renderFailed = false
    /// "Show original colours" (dark reader, HTML mail): nil follows what the
    /// person last chose for this sender (ReaderColourPrefs.swift); a tap on
    /// this card sets it for this message and remembers it for the sender.
    @State private var originalColours: Bool?
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dts
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// The sender disc: 40 pt at the default text size (§5.3), growing with
    /// the name beside it (capped in `senderAvatar`).
    @ScaledMetric(relativeTo: .subheadline) private var avatarSize: CGFloat = 40
    // Handing the link over is the browser's job, not this app's, and the note
    // below says only what this process witnessed. It goes through
    // `UIApplication.shared.open` rather than `\.openURL`, because this SDK's
    // `OpenURLAction` cannot report an outcome at all — see the note at the
    // call — and a confirmation that exists to be exact about the destination
    // should not be built on an API that cannot say whether it reached it.

    var body: some View {
        VStack(alignment: .leading, spacing: expanded ? 14 : 0) {
            if expanded {
                senderHeader
                if showDetails { recipientDetails }
                bodyView
                if !message.attachments.isEmpty {
                    ReaderAttachmentsView(vm: vm, message: message, downloading: downloading,
                                          notices: notices, onDownload: onDownload)
                }
                if detailsError != nil {
                    Text("Couldn’t load message details.").gmFont(12).foregroundColor(GM.textSecondary)
                    Button("Retry") { detailsAttempt += 1 }
                }
                if let unsubNote {
                    Text(unsubNote).gmFont(12).foregroundColor(GM.textSecondary)
                }
                if let handoffNote {
                    Text(handoffNote).gmFont(12).foregroundColor(GM.error)
                }
                if let decisionNote {
                    Text(decisionNote).gmFont(12).foregroundColor(GM.textSecondary)
                }
                if showsFooter {
                    ReaderFooterActions(onReply: onReply)
                }
            } else {
                // A folded card (§5.3): the whole card is the tap target and
                // opens it in place.
                Button(action: onExpand) {
                    CollapsedMessageRow(message: message, own: own, unread: !message.isRead)
                }
                .buttonStyle(.plain)
                .accessibilityHint("Opens this message")
                .accessibilityIdentifier("mail-collapsed-card")
            }
        }
        // `.sheet` hangs off the card's outermost view, not off the `if expanded`
        // block above it. A leading-dot modifier written after a conditional inside a
        // ViewBuilder binds to the conditional's last expression rather than to the
        // branch, which in this repo already produced
        // `type 'Any' has no member 'accessibilityIdentifier'` against a line that
        // looked correct -- so the presentation is attached where the compiler cannot
        // misread it.
        .sheet(isPresented: $showUnsub) {
            if let unsub = unsubscribeTarget(message.unsubscribe) {
                UnsubscribeSheet(
                    // Blank, not absent: `display` is a non-optional that yields
                    // "" when the server named nobody, so `??` produced
                    // "This offer came from the sender, about ." -- the same
                    // dangling clause Android had, in the sentence that exists to
                    // be exact about where the request goes.
                    sender: message.from?.address?.nilIfBlank()
                        ?? (message.from?.display ?? "").nilIfBlank(),
                    url: unsub,
                    onOneClick: (message.unsubscribe?.oneClickAdvertised == true
                                    && message.from?.address?.nilIfBlank() != nil)
                        ? ({ showUnsub = false
                             vm.unsubscribeOneClick(address: message.from?.address ?? "", url: unsub) { unsubNote = $0 } })
                        : nil,
                    onOpen: {
                        showUnsub = false
                        // Only what this app witnessed is reported. Whether the
                        // list acted on the request is not observable from here,
                        // so no branch here says "unsubscribed". A URL the
                        // system refuses to open is reported as that rather
                        // than passed to an opener that cannot use it.
                        guard let target = URL(string: unsub) else {
                            unsubNote = String(localized: "That unsubscribe address could not be opened.")
                            return
                        }
                        // What the two `openURL` shapes are on this SDK, learned
                        // from the compiler rather than assumed: the
                        // completion-handler form does not exist ("extra
                        // arguments at positions #2, #3"), and the one-argument
                        // form returns `()`, so using it as a condition is
                        // itself an error ("cannot convert value of type '()'
                        // to expected condition type 'Bool'"). A previous review
                        // round insisted the completion form was the honest one;
                        // the run named both errors, so neither claim survived.
                        // `UIApplication.open(_:options:completionHandler:)` is
                        // the API that really does answer, and its Bool means
                        // only that the system agreed to hand the link to
                        // something that can show it — the same evidence Android
                        // has when `startActivity` does not throw
                        // `ActivityNotFoundException`. Nothing here claims the
                        // mailing list did anything with the request, because
                        // the page's behaviour is not observable from this
                        // process at all.
                        UIApplication.shared.open(target, options: [:]) { accepted in
                            unsubNote = accepted
                                ? String(localized: "Opened in your browser. GrapMail cannot see what the page said.")
                                : String(localized: "This device could not open that link.")
                        }
                    },
                    onCancel: { showUnsub = false }
                )
            }
        }
        // The share sheet hangs off the card's outermost view for the same
        // reason the unsubscribe sheet does: a leading-dot modifier written
        // after a conditional inside a ViewBuilder binds to that conditional's
        // last expression, not to the branch. The item is the one text builder
        // print uses too, so the two handoffs cannot drift into describing
        // different messages.
        .sheet(isPresented: $showShare) {
            MessageActivityView(text: messageShareText(
                message, formattedText: formattedBody?.bodyText))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, expanded ? 14 : 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(own ? GM.ownMessageFill : GM.card,
                    in: RoundedRectangle(cornerRadius: GMShape.card, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: GMShape.card, style: .continuous)
                .strokeBorder(GM.border)
                .allowsHitTesting(false)
        }
        .overlay(alignment: .leading) {
            if own { OwnMessageAccent() }
        }
        .gmElevation(.level1)
        .onAppear {
            policyMuted = NotificationPolicy.isMuted(threadKey)
            policyWatched = NotificationPolicy.isWatched(threadKey)
            policyVip = NotificationPolicy.isVip(senderKey)
        }
        .task(id: showsBody ? detailsAttempt : nil) {
            guard showsBody else { return }
            let error = await vm.viewMessage(message.id)
            if !Task.isCancelled { detailsError = error }
        }
        .task(id: needsFormattedBody ? RenderRequest(allowImages: shownImages, attempt: renderAttempt) : nil) {
            guard needsFormattedBody else {
                formattedBody = nil
                renderLoading = false
                return
            }
            renderLoading = true
            renderFailed = false
            let allowImages = shownImages
            guard let render = try? await vm.loadBody(message.id, allowImages: allowImages) else {
                if !Task.isCancelled {
                    renderLoading = false
                    renderFailed = true
                }
                return
            }
            guard !Task.isCancelled else { return }
            if render.error == nil || formattedBody?.html == nil {
                formattedBody = render
            }
            renderLoading = false
        }
    }

    private var senderHeader: some View {
        let sender = own ? String(localized: "You", table: "Reader")
            : (message.from?.display.nilIfBlank() ?? String(localized: "Unknown sender"))
        return Group {
            if dts.isAccessibilitySize {
                // The accessibility sizes: avatar and menu on the top line, the
                // sender's words across the card's whole width under them, and
                // Unsubscribe on its own line, so nothing is squeezed to a
                // column a few letters wide.
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 12) {
                        senderAvatar(sender)
                        Spacer(minLength: 0)
                        actionsOrChevron
                    }
                    senderLines(sender)
                    if expanded { unsubscribeButton }
                }
            } else {
                HStack(spacing: 12) {
                    senderAvatar(sender)
                    senderLines(sender)
                    if expanded { unsubscribeButton }
                    actionsOrChevron
                }
            }
        }
    }

    private func senderAvatar(_ sender: String) -> some View {
        SenderAvatar(display: message.from?.display.nilIfBlank() ?? sender,
                     address: message.from?.address ?? "", size: min(avatarSize, 72))
            .accessibilityHidden(true)
    }

    private func senderLines(_ sender: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            // Name, badge and date share a line until the accessibility
            // sizes, where they stack and the name may use every line it
            // needs; at the larger standard sizes it may wrap to two.
            GMAdaptiveStack(spacing: 6) {
                Text(sender)
                    .gmFont(15, .semibold)
                    .foregroundColor(GM.textPrimary)
                    .lineLimit(dts.isAccessibilitySize ? nil : (dts >= .xxLarge ? 2 : 1))
                    .fixedSize(horizontal: false, vertical: dts.isAccessibilitySize)
                SenderBadges(address: message.from?.address ?? "")
                if let date = MailTime.short(message.createdAt) {
                    Text(date)
                        .gmFont(13)
                        .foregroundColor(GM.textTertiary)
                        .lineLimit(1)
                        .fixedSize()
                }
            }
            if expanded {
                Button {
                    showDetails.toggle()
                } label: {
                    HStack(spacing: 4) {
                        Text(recipientsSummary)
                            .lineLimit(dts.isAccessibilitySize ? 3 : 1)
                        Image(systemName: showDetails ? "chevron.up" : "chevron.down")
                            .gmFont(9, .semibold, maxSize: 18)
                    }
                    .gmFont(13)
                    .foregroundColor(GM.textSecondary)
                    .padding(.vertical, 5)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint("Shows sender, recipients and date")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// "to me" / "to Nusrat" / "to 3 people" (§5.1); an own message keeps
    /// its address in front ("you@… · to Nusrat").
    private var recipientsSummary: String {
        let line = ReaderConversation.recipientsLine(message, me: me)
        if own, let address = message.from?.address?.nilIfBlank() {
            return [address, line].compactMap { $0 }.joined(separator: " \u{00B7} ")
        }
        return line ?? String(localized: "Message details")
    }

    /// The sender's own unsubscribe offer, when the message carries one.
    @ViewBuilder private var unsubscribeButton: some View {
        if unsubscribeTarget(message.unsubscribe) != nil {
            Button("Unsubscribe") { showUnsub = true }
                .gmFont(12, .medium)
                .foregroundColor(GM.primaryText)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .frame(minHeight: 44)
                .buttonStyle(.plain)
                .accessibilityIdentifier("mail-unsubscribe")
        }
    }

    /// The message menu on an open card, the expand chevron on a closed one.
    @ViewBuilder private var actionsOrChevron: some View {
        if expanded {
            Menu {
                Button("Reply") { onReply("reply") }
                Button("Reply all") { onReply("all") }
                Button("Forward") { onReply("forward") }
                Button("Share") { showShare = true }
                    .accessibilityIdentifier("mail-share")
                Button("Print") {
                    printMessageText(message, formattedText: formattedBody?.bodyText) { handoffNote = $0 }
                }
                .accessibilityIdentifier("mail-print")
                if showsColourControl {
                    Button(darkeningOn ? String(localized: "Show original colours")
                                       : String(localized: "Show dark colours")) {
                        toggleColours()
                    }
                    .accessibilityIdentifier("mail-original-colours")
                }
                Button("Report spam") {
                    vm.actOn(message.id, .spam) { decisionNote = $0 ?? String(localized: "Moved to spam.") }
                }
                .accessibilityIdentifier("mail-report-spam")
                Button("Block sender") {
                    guard let sender = message.from?.address?.nilIfBlank()
                            ?? (message.from?.display ?? "").nilIfBlank() else {
                        decisionNote = String(localized: "This message does not name a sender to block.")
                        return
                    }
                    vm.blockSender(sender) { decisionNote = $0 }
                }
                .accessibilityIdentifier("mail-block-sender")
                Divider()
                Button(policyMuted ? "Unmute this conversation" : "Mute this conversation") {
                    let want = !policyMuted
                    Task {
                        if let error = await NotificationPolicy.setMuted(threadKey, want) { decisionNote = error; return }
                        policyMuted = want
                        if want { policyWatched = false }
                        decisionNote = want ? String(localized: "Muted: this conversation will not alert you.") : String(localized: "Unmuted.")
                    }
                }
                .accessibilityIdentifier("mail-mute-thread")
                Button(policyWatched ? "Stop notifying me" : "Notify me about replies") {
                    let want = !policyWatched
                    Task {
                        if let error = await NotificationPolicy.setWatched(threadKey, want) { decisionNote = error; return }
                        policyWatched = want
                        if want { policyMuted = false }
                        decisionNote = want ? String(localized: "You will be alerted to replies, whatever your filters.") : String(localized: "Stopped following this conversation.")
                    }
                }
                .accessibilityIdentifier("mail-watch-thread")
                if let sender = senderKey {
                    Button(policyVip ? "Remove sender from VIPs" : "Add sender to VIPs") {
                        let want = !policyVip
                        Task {
                            if let error = await NotificationPolicy.setVip(sender, want) { decisionNote = error; return }
                            policyVip = want
                            decisionNote = want ? String(localized: "\(sender) is a VIP.") : String(localized: "\(sender) is no longer a VIP.")
                        }
                    }
                    .accessibilityIdentifier("mail-vip-sender")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .gmFont(18, .semibold, maxSize: 28)
                    .foregroundColor(GM.textSecondary)
                    .frame(minWidth: 34, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Message actions")
        } else {
            Image(systemName: "chevron.down")
                .gmFont(12, .semibold, maxSize: 22)
                .foregroundColor(GM.textSecondary)
                .accessibilityHidden(true)
        }
    }

    private var recipientDetails: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let address = message.from?.address { Text("From \(address)") }
            if !message.to.isEmpty { Text("To \(message.to.joined(separator: ", "))") }
            if !message.cc.isEmpty { Text("Cc \(message.cc.joined(separator: ", "))") }
            if let date = MailTime.full(message.createdAt) { Text(date) }
        }
        .gmFont(12)
        .foregroundColor(GM.textSecondary)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(GM.bgTinted, in: RoundedRectangle(cornerRadius: 12))
    }

    @ViewBuilder private var bodyView: some View {
        // The flag is the claim — the server sets `isE2eEncrypted` and stores
        // the envelope beside it — but the envelope decides whether anything
        // can actually be opened here. Mirrors Android's condition exactly.
        if message.isE2eEncrypted || message.e2eEnvelope != nil {
            E2eEncryptedBlock(message: message, e2e: e2e)
        } else {
            imagesGate
            bodyText
        }
    }

    /// The subject announces a forward: nothing folds (the "quote" IS the message).
    private var forwarded: Bool { ReaderConversation.isForwardSubject(message.subject) }

    @ViewBuilder private var bodyText: some View {
        if message.hasHtml {
            htmlBody
        } else if let text = message.bodyText?.nilIfBlank() {
            // 16/25 reading text, trailing quoted history folded (§5.1, §5.2).
            ReaderPlainBody(text: text, messageId: message.id, forwarded: forwarded)
        } else {
            Text("This message has no text body.")
                .gmFont(14).foregroundColor(GM.textSecondary)
        }
    }

    /// The session-memory key of a formatted body's height: the message and
    /// whether its images were let in (they change the height).
    private func bodyHeightKey(allowImages: Bool) -> String {
        "\(message.id)|\(allowImages ? "img" : "noimg")"
    }

    /// The height the body box holds: what this card measured, else what
    /// this session last measured for it, else the placeholder (§5.5).
    private func shownBodyHeight(_ key: String) -> CGFloat {
        htmlBodyHeight ?? ReaderSession.bodyHeight(key) ?? ReaderLayout.placeholderBodyHeight
    }

    private func bodyHeightBinding(_ key: String) -> Binding<CGFloat> {
        Binding(get: { shownBodyHeight(key) }, set: { htmlBodyHeight = $0 })
    }

    private func bodyMeasured(_ height: CGFloat, key: String) {
        ReaderSession.storeBodyHeight(height, for: key)
        guard !htmlMeasured else { return }
        // The real body crossfades in over the skeleton (150 ms); at once
        // under Reduce Motion.
        withAnimation(reduceMotion ? nil : .easeInOut(duration: GMMotion.short)) {
            htmlMeasured = true
        }
    }

    @ViewBuilder private var htmlBody: some View {
        if let render = formattedBody, let clean = render.html?.nilIfBlank() {
            if showsColourControl { colourPill }
            let key = bodyHeightKey(allowImages: render.allowImages)
            ZStack(alignment: .topLeading) {
                MailHtmlBodyView(
                    html: clean,
                    allowImages: render.allowImages,
                    onOpenLink: { urlString in
                        guard let url = URL(string: urlString),
                              let scheme = url.scheme?.lowercased(),
                              ["https", "http", "mailto"].contains(scheme) else { return }
                        UIApplication.shared.open(url)
                    },
                    contentHeight: bodyHeightBinding(key),
                    darkeningEnabled: darkeningOn,
                    quoteKey: message.id,
                    forwarded: forwarded,
                    onMeasure: { height in bodyMeasured(height, key: key) }
                )
                .frame(height: shownBodyHeight(key))
                .frame(maxWidth: .infinity, alignment: .leading)
                .opacity(htmlMeasured ? 1 : 0)
                .accessibilityHidden(!htmlMeasured)
                .accessibilityIdentifier("mail-body")
                if !htmlMeasured {
                    ReaderBodySkeleton(height: shownBodyHeight(key))
                        .transition(.opacity)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // Never a skeleton forever: a document that does not report a
            // height within a moment is shown at the height it has.
            .task(id: clean) {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                if !Task.isCancelled && !htmlMeasured { htmlMeasured = true }
            }
        } else if needsFormattedBody && formattedBody == nil && !renderFailed {
            // The formatted body is on its way: hold the space it will most
            // likely need, so the card does not jump when it lands (§5.5).
            ReaderBodySkeleton(height: shownBodyHeight(bodyHeightKey(allowImages: shownImages)))
        } else {
            let text = message.bodyText?.nilIfBlank() ?? formattedBody?.bodyText?.nilIfBlank()
            if let text {
                ReaderPlainBody(text: text, messageId: message.id, forwarded: forwarded)
            } else if formattedBody?.error != nil || renderFailed {
                Text("Couldn’t display this message.").gmFont(13).foregroundColor(GM.textSecondary)
                Button("Retry") { renderAttempt += 1 }.disabled(renderLoading)
            } else {
                ReaderBodySkeleton(height: ReaderLayout.placeholderBodyHeight)
            }
        }
    }

    // ── original colours ─────────────────────────────────────────────────────

    /// Whether this message is drawn darkened: the card's own choice, else the
    /// sender's remembered one, else on.
    private var darkeningOn: Bool {
        originalColours.map { !$0 } ?? ReaderColourPrefs.darkening(for: senderKey)
    }

    /// Only a dark reader showing a formatted (HTML) body has colours to switch.
    private var showsColourControl: Bool {
        colorScheme == .dark && message.hasHtml && !message.isE2eEncrypted && message.e2eEnvelope == nil
            && formattedBody?.html?.nilIfBlank() != nil
    }

    private func toggleColours() {
        let darken = !darkeningOn
        originalColours = !darken
        ReaderColourPrefs.setDarkening(darken, for: senderKey)
        Haptics.selection()
        UIAccessibility.post(notification: .announcement,
                             argument: darken ? String(localized: "Showing dark colours.")
                                              : String(localized: "Showing original colours."))
    }

    /// The small inline switch above the body. It names what a tap gives:
    /// "Original colours" while darkened, "Dark colours" while original.
    private var colourPill: some View {
        HStack(spacing: 0) {
            Spacer(minLength: 0)
            Button { toggleColours() } label: {
                HStack(spacing: 6) {
                    Image(systemName: darkeningOn ? "paintpalette" : "moon")
                        .gmFont(11, .semibold, maxSize: 22)
                        .accessibilityHidden(true)
                    Text(darkeningOn ? String(localized: "Original colours") : String(localized: "Dark colours"))
                        .gmFont(12, .medium)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundColor(GM.textSecondary)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(Capsule().fill(GM.softFill))
                .overlay(Capsule().strokeBorder(GM.border))
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(darkeningOn ? String(localized: "Show original colours")
                                            : String(localized: "Show dark colours"))
            .accessibilityHint(String(localized: "Remembered for mail from this sender"))
            .accessibilityIdentifier("mail-colours-pill")
        }
    }

    @ViewBuilder private var imagesGate: some View {
        if let render = formattedBody, render.blockedImages > 0, !render.allowImages {
            // The button moves under the sentence at the accessibility sizes.
            GMAdaptiveStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 3) {
                    // A whole sentence per count, not an "s" suffix spliced into
                    // the noun: plural rules differ by language.
                    Text(shownImages && !renderLoading ? String(localized: "Images couldn’t load.")
                         : render.blockedImages == 1 ? String(localized: "1 remote image hidden")
                         : String(localized: "\(render.blockedImages) remote images hidden"))
                        .gmFont(12, .semibold).foregroundColor(GM.textPrimary)
                    Text("Loading them tells the sender this message was opened.")
                        .gmFont(11).foregroundColor(GM.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 8)
                Button(shownImages && renderLoading ? "Loading…" : "Show images") {
                    if shownImages { renderAttempt += 1 } else { shownImages = true }
                }
                .gmFont(12, .medium)
                .foregroundColor(GM.primaryText)
                .frame(minHeight: 44)
                .disabled(shownImages && renderLoading)
                .accessibilityIdentifier("mail-show-images")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12)
            .background(GM.bgTinted, in: RoundedRectangle(cornerRadius: 12))
        }
    }
}

private struct EncryptedNotice: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: "lock.fill").gmFont(14)
                    .foregroundColor(GM.successInk)
                Text("End-to-end encrypted").gmFont(14, .semibold)
                    .foregroundColor(GM.textPrimary)
            }
            Text("Only you and the sender can read this. The server holds the sealed envelope and cannot open it. Unlocking this device's key opens the body below — a wrong passphrase and a message not sealed to this key each say so, and the ciphertext is never shown as a body.")
                .gmFont(13).lineSpacing(4)
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(GM.infoFill))
    }
}

/// The encrypted message, as a state of the card. The notice is the server's
/// own text; the decrypt path needs the passphrase every time — the private
/// half is opened for one operation and kept nowhere. A wrong phrase, a
/// message not sealed to this key, and a damaged envelope each get their own
/// true sentence, and none of them ever shows ciphertext as a body. Swift
/// mirror of Android's `EncryptedBlock`.
private struct E2eEncryptedBlock: View {
    let message: MailMessageFull
    @ObservedObject var e2e: E2eModel
    @State private var showPhrase = false
    @State private var phrase = ""
    @State private var opened: String?
    @State private var working = false
    @State private var failed: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            EncryptedNotice()
            if let opened {
                Text(opened)
                    .gmFont(15).lineSpacing(5)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("gm-e2e-opened-body")
            } else if let envelope = message.e2eEnvelope {
                if !showPhrase {
                    Text("Decrypt with your passphrase")
                        .gmFont(14, .semibold)
                        .foregroundColor(GM.primaryText)
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            e2e.load(includeDirectory: false)
                            showPhrase = true
                        }
                        .accessibilityIdentifier("gm-e2e-decrypt-open")
                } else {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Your passphrase decrypts this message on this device. It is never sent anywhere, and it is not kept after the message opens.")
                            .gmFont(12).foregroundColor(GM.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                        AppTextField(title: String(localized: "Passphrase"), value: $phrase, isPassword: true)
                        HStack(spacing: 16) {
                            Text(working ? "Opening…" : "Open")
                                .gmFont(14, .semibold)
                                .foregroundColor(GM.primaryText)
                                .padding(.vertical, 6)
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    guard !working, !phrase.isEmpty else { return }
                                    working = true
                                    Task {
                                        opened = await e2e.decrypt(envelopeJson: envelope,
                                                                    passphrase: phrase)
                                        working = false
                                        phrase = ""
                                        if opened == nil {
                                            failed = e2e.state.note
                                            showPhrase = true
                                        }
                                    }
                                }
                            Text("Cancel")
                                .gmFont(14)
                                .foregroundColor(GM.textSecondary)
                                .padding(.vertical, 6)
                                .contentShape(Rectangle())
                                .onTapGesture { showPhrase = false }
                        }
                        if let failed {
                            Text(failed).gmFont(12).foregroundColor(GM.error)
                                .fixedSize(horizontal: false, vertical: true)
                                .accessibilityIdentifier("gm-e2e-decrypt-error")
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("gm-e2e-decrypt-panel")
                }
            } else {
                // Flagged encrypted with no envelope to open: say that, not nothing.
                Text("No encrypted content came with this message.")
                    .gmFont(12).foregroundColor(GM.warnInk)
                    .accessibilityIdentifier("gm-e2e-no-envelope")
            }
        }
    }
}

private struct HtmlStrippedNotice: View {
    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "info.circle").gmFont(12)
            Text("Plain-text view of an HTML message. Remote images and markup are not loaded.")
                .gmFont(12)
        }
        .foregroundColor(GM.warnInk)
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(GM.warnChipBg))
    }
}

/// Compose. The send button never claims delivery — the receipt prints the
/// server's own status word.
struct ComposeView: View {
    @ObservedObject var vm: MailboxModel
    /// The account record, for the signature the profile carries. Same rule
    /// as Android's compose: the per-address override first, then the
    /// default; empty when the profile has not been read or was never filled
    /// in -- an invented "-- Sent with GrapMail" would sign mail the person
    /// did not sign.
    @ObservedObject var account: ProfileModel
    /// The encryption sector's state — the same instance the eight E2E
    /// screens and the thread read, so the encrypt row and the key's real
    /// state can never disagree on screen.
    @ObservedObject var e2e: E2eModel
    let replyId: String
    let mode: String
    let onBack: () -> Void
    var initialRecipient = ""
    @State private var showFormatting = false
    @State private var attached: [MailAttachment] = []
    @State private var notice: String?
    @State private var showCc = false
    @State private var picking = false
    @State private var contactCandidates: [MailboxModel.Person] = []
    @State private var started = false
    @State private var prepared = false
    @State private var encryptOn = false
    @State private var preparingSend = false
    @State private var uploading = false
    // "Image" in the formatting toolbar: a picture placed inside the message.
    @State private var pickingInline = false
    @State private var inlineSelection: PhotosPickerItem?
    @State private var pendingImage: InlineImage?
    // The compatibility report the person is reviewing, and the body version they
    // chose to send past.
    @State private var compatFindings: [ComposeFinding]?
    @State private var acceptedHtml: String?
    // Drag and drop: a drag this screen can take is over it (the SwiftUI
    // surface, or the formatting editor's own web view), and text dropped
    // while the formatting editor shows, waiting for it to type it.
    @State private var dropTargeted = false
    @State private var editorDropHover = false
    @State private var droppedText: DroppedText?
    // Send motion (Motion.swift): the shake counter and the last Send tap.
    @State private var sendShakes: CGFloat = 0
    @State private var lastSendTap = Date.distantPast
    // Send later (SendLaterSheet.swift): the sheet, the time it handed back
    // (sent on the sheet's dismissal, so a compatibility alert never tries to
    // present over a sheet that is still leaving), the time waiting behind
    // that alert's "Send anyway", and whether THIS composer asked to schedule
    // -- a stale "scheduled" state can never close it.
    @State private var showSendLater = false
    @State private var sheetPickedSendAt: Date?
    @State private var pendingSendAt: Date?
    @State private var scheduleRequested = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dts
    /// The To / Cc / Subject label column: 56 pt at the default text size,
    /// widening with the words it holds. Past the standard sizes the label
    /// moves above its field instead (`field`).
    @ScaledMetric(relativeTo: .footnote) private var fieldLabelWidth: CGFloat = 56
    /// The suggestion avatar beside a contact's name.
    @ScaledMetric(relativeTo: .footnote) private var suggestionAvatar: CGFloat = 26
    /// The encrypt row's check disc.
    @ScaledMetric(relativeTo: .footnote) private var encryptDisc: CGFloat = 20

    private var signature: String {
        guard let s = account.state.profile?.settings else { return "" }
        let me = vm.state.accountAddress
        let per = (me.flatMap { s.signatures[$0] } ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return per.isEmpty ? s.signature.trimmingCharacters(in: .whitespacesAndNewlines) : per
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            if waitingForDraft {
                VStack(alignment: .leading, spacing: 10) {
                    if let error = vm.compose.error {
                        Text(error).foregroundStyle(GM.textPrimary)
                        Button("Try again") { vm.prepareReply(id: replyId, mode: mode, signature: signature) }
                    } else {
                        ProgressView("Opening draft…")
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding(16)
            } else {
                GeometryReader { viewport in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            fields
                            suggestions
                            bodyEditor
                                .frame(height: max(176, viewport.size.height - 160))
                                .clipped()
                        }
                        .padding(.horizontal, 16)
                        // Capped and centred on wide windows (iPad, Mac,
                        // Stage Manager); a phone is narrower than the cap.
                        .gmMaxWidth(GMLayout.compose)
                    }
                }
                if let notice {
                    ScrollView(.vertical, showsIndicators: false) {
                        Text(notice)
                            .gmFont(13).foregroundColor(GM.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 60)
                    .padding(.top, 10)
                }
                // A scheduled send closes the composer at once and is receipted
                // by the banner underneath (ScheduledBanner.swift), so it never
                // draws the delivery receipt here.
                if let status = vm.compose.resultStatus, status != "scheduled" {
                    SendReceipt(status: status,
                                detail: vm.compose.result,
                                error: vm.compose.error)
                        .padding(.top, 12)
                        .padding(.horizontal, 16)
                } else if let error = vm.compose.error {
                    Text(error).gmFont(13).foregroundColor(GM.error)
                        .padding(.top, 12)
                        .padding(.horizontal, 16)
                } else if vm.compose.autosaving {
                    // The autosave's own quiet line, deliberately NOT the receipt:
                    // a background save is not an event the person acted on, so it
                    // draws smaller and duller than anything the buttons produce,
                    // and it yields to the receipt entirely when one exists.
                    Text("Saving draft…")
                        .gmFont(11).foregroundColor(GM.textTertiary)
                        .padding(.top, 10)
                        .padding(.horizontal, 16)
                } else if let note = vm.compose.autosaveNote {
                    Text(note)
                        .gmFont(11).foregroundColor(GM.textTertiary)
                        .padding(.top, 10)
                        .padding(.horizontal, 16)
                }
                actions
            }
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .background(GM.bg)
        .overlay {
            if dropTargeted || editorDropHover { dropZone }
        }
        // Files, photos and text dragged in from another app (Split View,
        // Slide Over, Stage Manager). Drops over the formatting editor arrive
        // through the editor itself, into the same handler.
        .onDrop(of: [UTType.item], delegate: ComposeDropDelegate(
            enabled: sendEnabled, targeted: $dropTargeted, perform: handleDrop))
        // A send that was under way and ended refused: one shake, after a
        // beat for the verdict to land (and for the hand-off between the undo
        // window and the send, which is not an end).
        .onChange(of: sendBusy) { busy in
            guard !busy, !reduceMotion else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) {
                if sendFailed { shakeSend() }
            }
        }
        // A tap refused at once (no recipient, for one).
        .onChange(of: vm.compose.error) { error in
            if error != nil, !sendBusy, Date().timeIntervalSince(lastSendTap) < 1 { shakeSend() }
        }
        .fileImporter(isPresented: $picking,
                      allowedContentTypes: [.data, .image, .pdf],
                      allowsMultipleSelection: false) { result in
            pick(result)
        }
        .photosPicker(isPresented: $pickingInline, selection: $inlineSelection, matching: .images)
        .onChange(of: inlineSelection) { item in
            guard let item else { return }
            inlineSelection = nil
            placeInlineImage(item)
        }
        .alert("Check before sending", isPresented: Binding(
            get: { compatFindings != nil },
            set: { if !$0 { compatFindings = nil } }
        )) {
            Button("Send anyway") {
                acceptedHtml = vm.compose.bodyHtml
                compatFindings = nil
                // A schedule that stopped for the review stays a schedule.
                let sendAt = pendingSendAt
                pendingSendAt = nil
                sendTapped(at: sendAt)
            }
            Button("Keep editing", role: .cancel) {
                compatFindings = nil
                pendingSendAt = nil
            }
        } message: {
            Text(compatSummary)
        }
        .task(id: signature) {
            if !started {
                vm.resetCompose(initialRecipient: initialRecipient)
                started = true
            }
            account.load()
            e2e.load(includeDirectory: false)
            // A prepared compose (reply / reply-all / forward from the thread)
            // builds its fields from the full server row; a blank one gets the
            // signature appended as editable text, the way the web client
            // appends it client-side. Both run once per opened target, so a
            // re-run of this task cannot double-append what the person already
            // edited.
            if !prepared {
                if !replyId.isEmpty {
                    prepared = true
                    vm.prepareReply(id: replyId, mode: mode, signature: signature)
                } else if !signature.isEmpty {
                    vm.composeSeed { s in
                        if s.body.isEmpty { s.body = "\n\n-- \n" + signature }
                    }
                    prepared = true
                }
            }
            contactCandidates = await vm.people()
            if let staged = MailFeaturePrefs.shared.consumeStagedInsert() {
                vm.composeSeed { s in
                    if s.body.isEmpty {
                        s.body = staged
                    } else if !s.body.contains(staged) {
                        s.body = staged + "\n\n" + s.body
                    }
                }
            }
            UserDefaults.standard.set(false, forKey: "gm.pendingFollowUp")
        }
        .sheet(isPresented: $showSendLater, onDismiss: {
            guard let sendAt = sheetPickedSendAt else { return }
            sheetPickedSendAt = nil
            sendTapped(at: sendAt)
        }) {
            SendLaterSheet(
                onSchedule: { sendAt in
                    sheetPickedSendAt = sendAt
                    showSendLater = false
                },
                onCancel: { showSendLater = false })
        }
        .onChange(of: vm.compose.accepted) { accepted in
            guard accepted else { return }
            if UserDefaults.standard.bool(forKey: "gm.pendingFollowUp"),
               let threadId = vm.compose.inReplyTo {
                UserDefaults.standard.set(false, forKey: "gm.pendingFollowUp")
                MailFeaturePrefs.shared.markAwaitingReply(
                    threadId: threadId,
                    title: vm.compose.subject.isEmpty ? String(localized: "(no subject)") : vm.compose.subject)
            }
            // Scheduled: the success haptic has played (the model plays it on
            // the server's answer); the receipt moves to the screen underneath
            // and the composer closes.
            if scheduleRequested, vm.compose.resultStatus == "scheduled", let id = vm.compose.scheduledId {
                scheduleRequested = false
                ScheduledBannerCenter.shared.show(id: id, scheduledFor: vm.compose.scheduledFor, model: vm)
                onBack()
            }
        }
        .accessibilityIdentifier("mail-compose-screen")
    }

    @ViewBuilder private var header: some View {
        HStack(spacing: 12) {
            Button {
                guard !vm.compose.sending, !preparingSend, !uploading else { return }
                let draft = vm.compose
                // The body alone does not qualify: the seeded signature is
                // not the person's content. An edit, a recipient, or a subject is.
                if !waitingForDraft && !draft.accepted && (!attached.isEmpty || draft.userEdited || [draft.to, draft.cc, draft.subject].contains(where: { $0.nilIfBlank() != nil })) {
                    saveDraft(onSaved: onBack)
                } else {
                    onBack()
                }
            } label: {
                Image(systemName: "xmark").gmFont(17, maxSize: 28)
                    .foregroundColor(GM.textPrimary)
            }
            .accessibilityLabel("Discard")
            Text("Compose")
                .gmFont(17, .semibold)
                .foregroundColor(GM.textPrimary)
            Spacer()
            Button { sendLaterTapped() } label: {
                Image(systemName: "clock")
                    .gmFont(18, maxSize: 28)
                    .foregroundColor(sendEnabled ? GM.primarySolid : GM.textTertiary)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .disabled(!sendEnabled)
            .accessibilityLabel(String(localized: "Schedule send"))
            .accessibilityHint(String(localized: "Choose when to send"))
            .accessibilityAddTraits(.isButton)
            .accessibilityIdentifier("mail-send-later")
            Button { sendTapped() } label: {
                Image(systemName: "paperplane.fill")
                    .gmFont(18, maxSize: 28)
                    .foregroundColor(vm.compose.sending ? GM.textTertiary : GM.primarySolid)
                    // Nudges forward and dims while a send is under way
                    // (220 ms); shakes once when it is refused (280 ms).
                    .offset(x: sendBusy && !reduceMotion ? 5 : 0)
                    .opacity(sendBusy ? 0.45 : 1)
                    .animation(GMMotion.gated(sendBusy ? GMMotion.emphasizedDecelerate() : GMMotion.standard(),
                                              reduced: reduceMotion), value: sendBusy)
                    .modifier(GMShakeEffect(shakes: sendShakes))
            }
            .disabled(!sendEnabled)
            .accessibilityLabel("Send")
            .accessibilityIdentifier("mail-send")
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        // The undo-send window: the message is held on this device for five
        // seconds, and this bar is its whole story — nothing has been sent,
        // nothing is claimed sent, and the word names exactly what the tap
        // does. After the window the normal send flow takes over and the
        // server's own answer becomes the receipt.
        if vm.compose.pendingSend {
            HStack {
                Text("Sending…")
                    .gmFont(14, .semibold)
                    .foregroundColor(.white)
                Spacer()
                Button { vm.undoSend() } label: {
                    Text("Undo")
                        .gmFont(14, .bold)
                        .foregroundColor(.white)
                }
                .accessibilityIdentifier("mail-undo-send")
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(GM.primarySolid))
            .padding(.horizontal, 12).padding(.vertical, 6)
        }
    }

    @ViewBuilder private var fields: some View {
        field(label: "To", key: \MailboxModel.ComposeState.to, placeholder: "Search contacts or type email…",
              tag: "mail-to")
        HStack {
            Spacer()
            Button(showCc ? "Hide Cc" : "Add Cc") {
                withAnimation { showCc.toggle() }
            }
            .gmFont(13, .medium)
            .foregroundColor(GM.primarySolid)
            .padding(.vertical, 4)
        }
        if showCc {
            field(label: "Cc", key: \MailboxModel.ComposeState.cc, placeholder: "Addresses", tag: "mail-cc")
            field(label: "Bcc", key: \MailboxModel.ComposeState.bcc, placeholder: "Addresses", tag: "mail-bcc")
        }
        field(label: "Subject", key: \MailboxModel.ComposeState.subject, placeholder: "Add a subject", tag: "mail-subject")
        // F10 templates, F11 rewrite, F12 reminder: the compose shelf, bound to the same state the fields edit.
        MailComposeTools(
            subject: Binding(
                get: { vm.compose.subject },
                set: { value in vm.composeUpdate { $0.subject = value } }),
            draft: Binding(
                get: { vm.compose.body },
                set: { value in vm.composeUpdate { $0.body = value } }))
        // Per-message read receipt request. Not offered for encrypted mail
        // (the server ignores the flag for sealed sends as well).
        if !encryptOn {
            RequestReceiptToggle(isOn: Binding(
                get: { vm.requestReadReceipt },
                set: { vm.requestReadReceipt = $0 }))
                .padding(.horizontal, 16)
        }
        Divider().overlay(GM.border)
        // The encrypt row. The states are the web compose's own, and Android's:
        // a device with no key never sees the option; a locked key says what
        // would fix it; only an unlocked key can flip it on. And when it is on,
        // the person is told plainly that the body is sealed before it leaves
        // the device -- the server will store a notice, not text.
        if e2e.state.record != nil {
            HStack(spacing: 10) {
                Image(systemName: "lock").gmFont(15)
                    .foregroundColor(encryptOn ? GM.primarySolid : GM.textTertiary)
                Text(encryptOn ? "Encrypting this message" : "Encrypt this message")
                    .gmFont(14)
                    .foregroundColor(encryptOn ? GM.primarySolid : GM.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                ZStack {
                    Circle().strokeBorder(encryptOn ? GM.primarySolid : GM.border, lineWidth: 1.5)
                        .frame(width: min(encryptDisc, 34), height: min(encryptDisc, 34))
                    if encryptOn {
                        Circle().fill(GM.primarySolid).frame(width: min(encryptDisc, 34), height: min(encryptDisc, 34))
                        Text("✓").gmFont(12, maxSize: 20).foregroundColor(.white)
                    }
                }
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
            .onTapGesture {
                if e2e.state.unlocked { encryptOn.toggle() }
            }
            .accessibilityIdentifier("gm-compose-encrypt")
            if !e2e.state.unlocked {
                Text("Unlock your key to encrypt — it locks when the app closes.")
                    .gmFont(12).foregroundColor(GM.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            } else if encryptOn {
                Text("Only the recipients can open this message. Encrypted messages are sent as plain text.")
                    .gmFont(12).foregroundColor(GM.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer().frame(height: 4)
        }
    }

    /// Keys, not Strings: every caller passes literals, which the String
    /// Catalog extracts (`placeholder: "Add a subject"` reads as before).
    private func field(
        label: LocalizedStringKey, key: WritableKeyPath<MailboxModel.ComposeState, String>,
        placeholder: LocalizedStringKey, tag: String,
    ) -> some View {
        // Label beside the field up to the largest standard size; above it at
        // the accessibility sizes, where a label column would leave the field
        // no room to show what was typed.
        GMAdaptiveStack(spacing: dts.isAccessibilitySize ? 4 : 12) {
            Text(label).gmFont(14).foregroundColor(GM.textSecondary)
                .frame(width: dts.isAccessibilitySize ? nil : min(fieldLabelWidth, 120), alignment: .leading)
            TextField(placeholder,
                      text: Binding(
                        get: { vm.compose[keyPath: key] },
                        set: { value in vm.composeUpdate { $0[keyPath: key] = value } }))
                .gmFont(15)
                .foregroundColor(GM.textPrimary)
                .accessibilityIdentifier(tag)
        }
        .padding(.vertical, 12)
    }

    @ViewBuilder private var suggestions: some View {
        let matches = people
        if !matches.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(matches, id: \.address) { p in
                    Button {
                        vm.composeUpdate { s in s.to = ComposeView.append(s.to, p.address) }
                    } label: {
                        HStack(spacing: 8) {
                            SenderAvatar(display: p.name ?? "", address: p.address, size: min(suggestionAvatar, 44))
                            // Name and address stack at the accessibility sizes.
                            GMAdaptiveStack(spacing: dts.isAccessibilitySize ? 2 : 8) {
                                Text(p.name ?? p.address)
                                    .gmFont(14).foregroundColor(GM.textPrimary)
                                Text(p.address)
                                    .gmFont(12).foregroundColor(GM.textTertiary)
                                    .lineLimit(dts.isAccessibilitySize ? 2 : 1)
                            }
                            Spacer()
                        }
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var bodyEditor: some View {
        VStack(alignment: .leading, spacing: 6) {
            let formatting = showFormatting || vm.compose.bodyHtml != nil
            Button(formatting ? "Switch to plain text" : "Format message") {
                showFormatting = !formatting
                if formatting { vm.composeUpdate { $0.bodyHtml = nil } }
            }
            .gmFont(13, .medium)
            if formatting {
                ComposeFormattingEditor(
                    text: vm.compose.body, html: vm.compose.bodyHtml,
                    pendingImage: pendingImage,
                    onImageInserted: { pendingImage = nil },
                    onPickImage: encryptOn ? nil : { pickingInline = true },
                    droppedText: droppedText,
                    onTextInserted: { droppedText = nil },
                    onDrop: handleDrop,
                    dropEnabled: sendEnabled,
                    onDropHover: { editorDropHover = $0 }
                ) { text, html in
                    vm.composeUpdate {
                        $0.body = text
                        $0.bodyHtml = html
                    }
                }
                .frame(minHeight: 240)
                .accessibilityIdentifier("mail-body-field")
            } else {
                TextEditor(text: Binding(
                    get: { vm.compose.body },
                    set: { value in vm.composeUpdate { $0.body = value } }))
                    .gmFont(15)
                    .foregroundColor(GM.textPrimary)
                    .scrollContentBackground(.hidden)
                    // The floor only: the GeometryReader above hands this editor
                    // its exact grown height, so it never proposes infinity to
                    // the stack and inflate a sibling.
                    .frame(minHeight: 240)
                    // The identifier names the signed state, not the field: frame
                    // 76's settled marker must flip when the signature lands, the
                    // same mechanism as Android's `gm-signed-body` testTag.
                    .accessibilityIdentifier(
                        !signature.isEmpty && vm.compose.body.contains(signature)
                            ? "gm-signed-body" : "mail-body-field")
            }
            // Images placed inside the message are shown there, not as file chips.
            if attached.contains(where: { $0.contentId == nil }) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(attached.filter { $0.contentId == nil }) { a in
                            HStack(spacing: 6) {
                                Image(systemName: "paperclip").gmFont(11, maxSize: 20)
                                Text(a.name ?? String(localized: "file", comment: "Fallback name for an attached file with no name")).gmFont(12).lineLimit(1)
                                Button { attached.removeAll { $0.id == a.id } } label: {
                                    Image(systemName: "xmark.circle")
                                }
                                .accessibilityLabel(a.name.map { String(localized: "Remove \($0)") } ?? String(localized: "Remove attachment"))
                                .disabled(vm.compose.sending || vm.compose.accepted || preparingSend)
                            }
                            .foregroundColor(GM.textSecondary)
                            .padding(.horizontal, 10).padding(.vertical, 6)
                            .background(Capsule().fill(GM.card))
                            .transition(chipTransition)
                        }
                    }
                }
                .transition(chipTransition)
            }
        }
        // Chips arrive with a fade and a small scale-up and leave with a
        // shrink (220 ms), keyed on the list itself so nothing else moves.
        .animation(GMMotion.gated(GMMotion.standard(GMMotion.medium), reduced: reduceMotion),
                   value: attached.map(\.id))
    }

    private var chipTransition: AnyTransition {
        reduceMotion ? .identity
            : .asymmetric(insertion: .opacity.combined(with: .scale(scale: 0.92)),
                          removal: .opacity.combined(with: .scale(scale: 0.6)))
    }

    /// The drop zone: shown while a drag this screen can take is over it.
    private var dropZone: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(GM.primarySolid.opacity(0.16))
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(GM.primarySolid, lineWidth: 2)
            Text("Release to add to this message")
                .gmFont(15, .semibold)
                .foregroundColor(GM.primarySolid)
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(GM.card))
        }
        .padding(10)
        .allowsHitTesting(false)
        .accessibilityIdentifier("mail-compose-drop-zone")
    }

    private var actions: some View {
        // One row while it fits; at the larger text sizes (or in a narrow
        // Split View / Slide Over window) Send takes its own full-width line
        // under the attach and draft buttons.
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                attachButton
                saveDraftButton
                Spacer()
                sendLaterButton
                sendButton
            }
            VStack(spacing: 12) {
                HStack(spacing: 12) {
                    attachButton
                    saveDraftButton
                    Spacer(minLength: 0)
                    sendLaterButton
                }
                sendButton
            }
        }
        .gmMaxWidth(GMLayout.compose)
        .padding(.top, 14)
        .padding(.bottom, 28)
    }

    private var attachButton: some View {
        Button { picking = true } label: {
            Image(systemName: "plus").gmFont(17, maxSize: 26)
                .foregroundColor(GM.primarySolid)
                .frame(width: 44, height: 44)
                .background(Circle().stroke(GM.border, lineWidth: 1))
        }
        .accessibilityLabel("Attach a file")
        .accessibilityShowsLargeContentViewer {
            Label("Attach a file", systemImage: "plus")
        }
        .disabled(!sendEnabled)
    }

    private var saveDraftButton: some View {
        Button {
            saveDraft()
        } label: {
            Text("Save draft")
                .gmFont(15, .medium)
                .foregroundColor(GM.primarySolid)
                .padding(.vertical, 14).padding(.horizontal, 18)
                .background(Capsule().stroke(GM.border, lineWidth: 1))
        }
    }

    private var sendButton: some View {
        GradientButton(title: vm.compose.scheduling ? String(localized: "Scheduling…")
                           : vm.compose.sending ? String(localized: "Sending…") : String(localized: "Send"),
                       enabled: sendEnabled,
                       action: { sendTapped() })
            .modifier(GMShakeEffect(shakes: sendShakes))
    }

    /// Send later, beside the bottom Send: the same clock as the header's.
    private var sendLaterButton: some View {
        Button { sendLaterTapped() } label: {
            Image(systemName: "clock").gmFont(17, maxSize: 26)
                .foregroundColor(sendEnabled ? GM.primarySolid : GM.textTertiary)
                .frame(width: 44, height: 44)
                .background(Circle().stroke(GM.border, lineWidth: 1))
        }
        .disabled(!sendEnabled)
        .accessibilityLabel(String(localized: "Schedule send"))
        .accessibilityHint(String(localized: "Choose when to send"))
        .accessibilityAddTraits(.isButton)
        .accessibilityShowsLargeContentViewer {
            Label(String(localized: "Schedule send"), systemImage: "clock")
        }
        .accessibilityIdentifier("mail-send-later-bottom")
    }

    /// The clock: unavailable exactly when Send is (sending, uploading,
    /// preparing or sealing, accepted, the undo window). An empty To shows the
    /// same notice Send gives instead of a sheet whose every row would fail.
    private func sendLaterTapped() {
        lastSendTap = Date()
        guard sendEnabled else { return }
        if vm.refuseMissingRecipient() {
            GMHaptics.failure()
            return
        }
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        showSendLater = true
    }

    /// Send, or the seal path when the encrypt row is on: ask the directory
    /// for the plan, and only send encrypted when EVERY recipient has a key
    /// and this device can open its own. A plan that says "plain", a locked
    /// key, or no answer at all stops the send with a sentence — an encrypted
    /// message half built is the failure mode this gate exists to stop.
    /// Mirror of Android's compose send-icon logic.
    private var waitingForDraft: Bool {
        mode == "draft" && !vm.compose.accepted && vm.compose.draftId != replyId
    }

    private var sendEnabled: Bool {
        !waitingForDraft && !vm.compose.sending && !vm.compose.accepted && !vm.compose.pendingSend && !preparingSend && !uploading
    }

    /// A send under way: the undo window, the send itself, or the checks before it.
    private var sendBusy: Bool {
        vm.compose.pendingSend || vm.compose.sending || preparingSend
    }

    /// The last send ended refused (the receipt says failed, or an error stands).
    private var sendFailed: Bool {
        !vm.compose.accepted && (vm.compose.resultStatus == "failed" || vm.compose.error != nil)
    }

    /// One shake of the send controls (Motion.swift). Nothing under Reduce Motion.
    private func shakeSend() {
        guard !reduceMotion else { return }
        withAnimation(.linear(duration: 0.28)) { sendShakes += 1 }
    }

    private func saveDraft(onSaved: @escaping () -> Void = {}) {
        guard sendEnabled else { return }
        guard attached.isEmpty else {
            notice = String(localized: "Remove the attachments before saving this draft.")
            return
        }
        vm.saveDraft(onSaved: onSaved)
    }

    /// `sendAt` nil is Send; a date is Send later, through every check Send
    /// makes (uploads, the compatibility review, encryption) before the model
    /// schedules it.
    private func sendTapped(at sendAt: Date? = nil) {
        lastSendTap = Date()
        guard sendEnabled else { return }
        // An image removed from the body after it was placed is not sent at all.
        let html = vm.compose.bodyHtml
        let attachmentIds = attached
            .filter { a in a.contentId.map { html?.contains("cid:" + $0) == true } ?? true }
            .map(\.id)
        if !encryptOn {
            // Check designed HTML for what mail apps drop or break, once per
            // version of the body; "Send anyway" accepts that version.
            if let html, html != acceptedHtml, let token = SessionStore.token {
                preparingSend = true
                Task { @MainActor in
                    let report = try? await ApiClient.composeCheck(token, html: html)
                    preparingSend = false
                    let problems = (report?.findings ?? []).filter { $0.severity != "info" }
                    if problems.isEmpty {
                        acceptedHtml = html
                        if let sendAt {
                            scheduleRequested = true
                            vm.scheduleSend(at: sendAt, attachmentIds: attachmentIds)
                        } else {
                            vm.send(attachmentIds: attachmentIds)
                        }
                    } else {
                        pendingSendAt = sendAt
                        compatFindings = report?.findings
                    }
                }
                return
            }
            if let sendAt {
                scheduleRequested = true
                vm.scheduleSend(at: sendAt, attachmentIds: attachmentIds)
            } else {
                vm.send(attachmentIds: attachmentIds)
            }
            return
        }
        guard attached.isEmpty else {
            notice = String(localized: "Encrypted messages cannot include attachments.")
            return
        }
        let draft = vm.compose
        preparingSend = true
        Task {
            defer { preparingSend = false }
            let recipients = (MailboxModel.splitAddresses(draft.to)
                + MailboxModel.splitAddresses(draft.cc)
                + MailboxModel.splitAddresses(draft.bcc)).reduce(into: [String]()) {
                    list, a in if !list.contains(a) { list.append(a) }
                }
            guard let plan = await vm.encryptionPlan(addresses: recipients) else {
                notice = String(localized: "The encryption directory is unavailable. Try again.")
                return
            }
            guard plan.mode == "seal" else {
                notice = String(localized: "A recipient has no published encryption key.")
                return
            }
            guard let envelope = await e2e.seal(plaintext: draft.body, recipientKeys: plan.keys) else { return }
            let current = vm.compose
            guard current.to == draft.to, current.cc == draft.cc, current.bcc == draft.bcc,
                  current.body == draft.body, attached.isEmpty else {
                notice = String(localized: "The message changed while encryption was being prepared. Tap Send again.")
                return
            }
            if let sendAt {
                scheduleRequested = true
                vm.scheduleSend(at: sendAt, e2eEnvelope: envelope)
            } else {
                vm.send(e2eEnvelope: envelope)
            }
        }
    }

    /// The people the To field can name — derived from mail the app has loaded,
    /// not an address book. There is no contacts endpoint; the web client
    /// derives them the same way and the app says so rather than implying more.
    private var people: [MailboxModel.Person] {
        let needle = vm.compose.to.trimmingCharacters(in: .whitespaces)
        guard !needle.isEmpty else { return [] }
        let already = MailboxModel.splitAddresses(vm.compose.to)
        return Array(contactCandidates.lazy.filter { p in
            (p.address.localizedCaseInsensitiveContains(needle)
             || p.name?.localizedCaseInsensitiveContains(needle) == true)
            && !already.contains { $0.caseInsensitiveCompare(p.address) == .orderedSame }
        }.prefix(5))
    }

    /// The report, one line per finding, for the alert.
    private var compatSummary: String {
        let lines = (compatFindings ?? []).map { f -> String in
            let label = f.severity == "removed" ? String(localized: "Removed", comment: "Compatibility finding severity")
                : f.severity == "warning" ? String(localized: "May break", comment: "Compatibility finding severity")
                : String(localized: "Note", comment: "Compatibility finding severity")
            let clients = f.clients.isEmpty ? "" : " (" + f.clients.joined(separator: ", ") + ")"
            return "\(label): \(f.message)\(clients)"
        }
        return String(localized: "Some of this message's formatting won't arrive the way it looks here.") + "\n\n" + lines.joined(separator: "\n\n")
    }

    /// Upload a picked photo to be placed inside the message (`inline`), then
    /// hand the editor a preview no wider than 600 px. The photo is re-encoded as
    /// JPEG (or PNG when it has transparency) at most 1600 px wide, so a 12 MP
    /// camera image does not become a 6 MB email.
    private func placeInlineImage(_ item: PhotosPickerItem) {
        guard sendEnabled, !uploading else { return }
        guard attached.count < 20 else {
            notice = String(localized: "A message can have up to 20 attachments and images.")
            return
        }
        uploading = true
        let generation = SessionStore.generation
        Task { @MainActor in
            defer { uploading = false }
            guard let data = try? await item.loadTransferable(type: Data.self),
                  let image = UIImage(data: data) else {
                notice = String(localized: "That photo could not be read.")
                return
            }
            let hasAlpha: Bool = {
                guard let alpha = image.cgImage?.alphaInfo else { return false }
                return [.first, .last, .premultipliedFirst, .premultipliedLast].contains(alpha)
            }()
            func scaled(_ maxWidth: CGFloat) -> UIImage {
                let width = min(maxWidth, image.size.width)
                let size = CGSize(width: width, height: image.size.height * width / max(image.size.width, 1))
                let format = UIGraphicsImageRendererFormat()
                format.scale = 1
                return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
            }
            let full = scaled(1600)
            guard let bytes = hasAlpha ? full.pngData() : full.jpegData(compressionQuality: 0.85),
                  let previewBytes = hasAlpha ? scaled(600).pngData() : scaled(600).jpegData(compressionQuality: 0.8) else {
                notice = String(localized: "That photo could not be prepared.")
                return
            }
            guard bytes.count <= 10 * 1024 * 1024 else {
                notice = String(localized: "Images inside a message can be up to 10 MB.")
                return
            }
            guard let token = SessionStore.token, SessionStore.generation == generation else {
                notice = String(localized: "Sign in again to insert an image.")
                return
            }
            let name = "image." + (hasAlpha ? "png" : "jpg")
            notice = String(localized: "Uploading image…")
            do {
                let attachment = try await ApiClient.mailAttachmentUpload(
                    token: token, bytes: bytes, filename: name,
                    mimeType: hasAlpha ? "image/png" : "image/jpeg", inline: true)
                guard SessionStore.generation == generation else { return }
                attached.append(attachment)
                guard let cid = attachment.contentId, !cid.isEmpty else {
                    notice = String(localized: "This server can't place images inside messages yet, so the image was attached instead.")
                    return
                }
                let preview = "data:image/" + (hasAlpha ? "png" : "jpeg") + ";base64," + previewBytes.base64EncodedString()
                pendingImage = InlineImage(contentId: cid, preview: preview, alt: String(localized: "Image", comment: "Alt text of a picture placed inside a message"),
                                           width: Int(min(600, image.size.width)))
                notice = String(localized: "Image inserted.")
            } catch {
                notice = apiMessage(error, fallback: String(localized: "That image could not be inserted."))
            }
        }
    }

    private func pick(_ result: Result<[URL], Error>) {
        guard case let .success(urls) = result, let url = urls.first else {
            if case let .failure(e) = result { notice = apiMessage(e, fallback: String(localized: "Could not complete the message action. Try again.")) }
            return
        }
        guard sendEnabled else { return }
        guard attached.count < 20 else {
            notice = String(localized: "A message can have up to 20 attachments.")
            return
        }
        uploading = true
        let generation = SessionStore.generation
        Task { @MainActor in
            let name = url.lastPathComponent
            notice = String(localized: "Reading \(name)…")
            let result = await Self.readPickedFile(url)
            guard let file = result.file else {
                uploading = false
                notice = result.error
                return
            }
            guard SessionStore.generation == generation else {
                try? FileManager.default.removeItem(at: file)
                uploading = false
                notice = String(localized: "The account changed before this file could be attached.")
                return
            }
            notice = String(localized: "Uploading \(name)…")
            vm.uploadAttachment(file: file, filename: name,
                                mimeType: MimeType.of(filename: name)) { a, message in
                uploading = false
                notice = message
                if let a { attached.append(a) }
            }
        }
    }

private nonisolated static func readPickedFile(_ url: URL) async -> (file: URL?, error: String?) {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                continuation.resume(returning: Self.stageCopy(url))
            }
        }
    }

    /// The capped copy into a staged upload file: 25 MB, empty refused, the
    /// same sentences. Shared by the attach button (`readPickedFile`) and drops
    /// (`stageDropped`), so the two can never apply different limits.
    private nonisolated static func stageCopy(_ url: URL) -> (file: URL?, error: String?) {
        let staged = FileManager.default.temporaryDirectory.appendingPathComponent("upload-\(UUID().uuidString)")
        do {
            let maxBytes = 25 * 1024 * 1024
            let file = try FileHandle(forReadingFrom: url)
            defer { try? file.close() }
            guard FileManager.default.createFile(atPath: staged.path, contents: nil) else {
                throw CocoaError(.fileWriteUnknown)
            }
            let output = try FileHandle(forWritingTo: staged)
            defer { try? output.close() }
            var size = 0
            while size <= maxBytes {
                let count = min(64 * 1024, maxBytes + 1 - size)
                guard let chunk = try file.read(upToCount: count), !chunk.isEmpty else { break }
                try output.write(contentsOf: chunk)
                size += chunk.count
            }
            if size > maxBytes {
                try? FileManager.default.removeItem(at: staged)
                return (nil, String(localized: "That file exceeds the 25 MB attachment limit."))
            } else if size == 0 {
                try? FileManager.default.removeItem(at: staged)
                return (nil, String(localized: "That file is empty."))
            } else {
                return (staged, nil)
            }
        } catch {
            try? FileManager.default.removeItem(at: staged)
            return (nil, String(localized: "That file could not be read."))
        }
    }

    // ── drag and drop ────────────────────────────────────────────────────────
    //
    // Files dropped from another app (Split View, Slide Over, Stage Manager)
    // go through the attach button's own path -- `stageCopy`'s 25 MB cap, the
    // 20-attachment cap, `vm.uploadAttachment`, the same sentences. Dropped
    // text is not a file: it goes into the body (at the caret in the
    // formatting editor; above the signature, or at the end, in the plain
    // one). Android's ComposeScreen `handleDrop`, refusals included: a
    // `file://` URL is a path, not a file handed over, and is refused, so a
    // drag can never make the app attach its own private files.

    private enum DropKind { case file, text, unsupported }

    /// One handler for both drop surfaces: the screen (SwiftUI `.onDrop`) and
    /// the formatting editor (a web view, which receives its own drops).
    private func handleDrop(_ providers: [NSItemProvider]) -> Bool {
        guard sendEnabled, !providers.isEmpty else { return false }
        var files: [NSItemProvider] = []
        var texts: [NSItemProvider] = []
        var unsupported = false
        for provider in providers {
            switch Self.dropKind(provider) {
            case .file: files.append(provider)
            case .text: texts.append(provider)
            case .unsupported: unsupported = true
            }
        }
        if !texts.isEmpty {
            let textProviders = texts
            Task { @MainActor in
                var pieces: [String] = []
                for provider in textProviders {
                    if let text = await Self.loadDroppedText(provider) { pieces.append(text) }
                }
                if pieces.isEmpty {
                    notice = String(localized: "That file could not be read.")
                } else {
                    insertDroppedText(pieces.joined(separator: "\n"))
                }
            }
        }
        if !files.isEmpty {
            attachDropped(files)
        } else if unsupported {
            notice = String(localized: "That file could not be read.")
        }
        return !files.isEmpty || !texts.isEmpty
    }

    /// Dropped files, one after another, each through the picker's path.
    private func attachDropped(_ providers: [NSItemProvider]) {
        uploading = true
        let generation = SessionStore.generation
        Task { @MainActor in
            defer { uploading = false }
            for provider in providers {
                guard attached.count < 20 else {
                    notice = String(localized: "A message can have up to 20 attachments.")
                    break
                }
                let shown = provider.suggestedName
                    ?? String(localized: "file", comment: "Fallback name for an attached file with no name")
                notice = String(localized: "Reading \(shown)…")
                let read = await Self.stageDropped(provider)
                guard let file = read.file else {
                    notice = read.error
                    continue
                }
                guard SessionStore.generation == generation else {
                    try? FileManager.default.removeItem(at: file)
                    notice = String(localized: "The account changed before this file could be attached.")
                    break
                }
                let name = read.name
                notice = String(localized: "Uploading \(name)…")
                let outcome: (attachment: MailAttachment?, message: String) = await withCheckedContinuation { continuation in
                    vm.uploadAttachment(file: file, filename: name,
                                        mimeType: MimeType.of(filename: name)) { a, message in
                        continuation.resume(returning: (a, message))
                    }
                }
                notice = outcome.message
                if let a = outcome.attachment { attached.append(a) }
            }
        }
    }

    /// Dropped text into the body. The formatting editor types it at its
    /// caret; SwiftUI's plain editor does not report a caret, so there it goes
    /// above the signature the screen seeds ("-- " on its own line), or at the
    /// end -- Android's `droppedTextPosition`. (Text dropped straight onto the
    /// plain editor is placed by the system at the drop point.)
    private func insertDroppedText(_ raw: String) {
        let text = raw.replacingOccurrences(of: "\r\n", with: "\n")
        guard !text.isEmpty else { return }
        if showFormatting || vm.compose.bodyHtml != nil {
            droppedText = DroppedText(text: text)
            return
        }
        vm.composeUpdate { s in
            let at = Self.droppedTextIndex(s.body)
            let piece = at == s.body.endIndex && !s.body.isEmpty && !s.body.hasSuffix("\n") ? "\n" + text : text
            s.body.insert(contentsOf: piece, at: at)
        }
    }

    private nonisolated static func droppedTextIndex(_ body: String) -> String.Index {
        guard let signature = body.range(of: "\n-- \n") else { return body.endIndex }
        if body[..<signature.lowerBound].trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return body.startIndex
        }
        return signature.lowerBound
    }

    /// The type a dropped file is read as: its first (richest) data type that
    /// has a file extension and is not a link or a text flavour; or, for a
    /// named document (a .txt from Files), its first text type. Nil: not a file.
    private nonisolated static func dropFileType(_ provider: NSItemProvider) -> UTType? {
        let types = provider.registeredTypeIdentifiers.compactMap { UTType($0) }
        let named = !((provider.suggestedName ?? "") as NSString).pathExtension.isEmpty
        let textual: [UTType] = [.text, .url, .rtfd, .flatRTFD, .webArchive]
        if let binary = types.first(where: { type in
            type.conforms(to: .data) && !textual.contains(where: { type.conforms(to: $0) })
                && (named || type.preferredFilenameExtension != nil)
        }) {
            return binary
        }
        return named ? types.first(where: { $0.conforms(to: .text) }) : nil
    }

    private nonisolated static func dropKind(_ provider: NSItemProvider) -> DropKind {
        if dropFileType(provider) != nil { return .file }
        if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier)
            || provider.canLoadObject(ofClass: String.self) { return .text }
        return .unsupported
    }

    /// A dropped link (http, https, mailto only; a `file://` URL is refused)
    /// or plain text. Nil when nothing usable arrived.
    private nonisolated static func loadDroppedText(_ provider: NSItemProvider) async -> String? {
        if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
            let url: URL? = await withCheckedContinuation { continuation in
                _ = provider.loadObject(ofClass: URL.self) { url, _ in continuation.resume(returning: url) }
            }
            if let url {
                guard let scheme = url.scheme?.lowercased(), ["http", "https", "mailto"].contains(scheme) else { return nil }
                return url.absoluteString
            }
        }
        guard provider.canLoadObject(ofClass: String.self) else { return nil }
        let text: String? = await withCheckedContinuation { continuation in
            _ = provider.loadObject(ofClass: String.self) { text, _ in continuation.resume(returning: text) }
        }
        guard let text, !text.isEmpty else { return nil }
        return text
    }

    /// One dropped file staged through `stageCopy`. The system's copy of the
    /// file only exists inside the completion handler, so it is copied there.
    private nonisolated static func stageDropped(_ provider: NSItemProvider) async -> (file: URL?, name: String, error: String?) {
        guard let type = dropFileType(provider) else {
            return (nil, "", String(localized: "That file could not be read."))
        }
        return await withCheckedContinuation { continuation in
            _ = provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                guard let url else {
                    continuation.resume(returning: (nil, "", String(localized: "That file could not be read.")))
                    return
                }
                let suggested = provider.suggestedName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                var name = (suggested.isEmpty ? url.lastPathComponent : suggested)
                    .replacingOccurrences(of: "/", with: "_")
                    .replacingOccurrences(of: ":", with: "_")
                if (name as NSString).pathExtension.isEmpty, let ext = type.preferredFilenameExtension {
                    name += "." + ext
                }
                let staged = Self.stageCopy(url)
                continuation.resume(returning: (staged.file, name, staged.error))
            }
        }
    }

    static func append(_ existing: String, _ address: String) -> String {
        let t = existing.trimmingCharacters(in: .whitespaces)
        if t.isEmpty { return address }
        return t.hasSuffix(",") ? "\(t) \(address)" : "\(t), \(address)"
    }
}

/// The compose screen's SwiftUI drop target. `enabled` is the attach
/// button's own gate (nothing while sending, uploading or opening a draft);
/// a drop that would be refused shows as forbidden and lights nothing.
private struct ComposeDropDelegate: DropDelegate {
    let enabled: Bool
    @Binding var targeted: Bool
    let perform: ([NSItemProvider]) -> Bool

    func validateDrop(info: DropInfo) -> Bool {
        enabled && info.hasItemsConforming(to: [UTType.item])
    }

    func dropEntered(info: DropInfo) { targeted = true }

    func dropExited(info: DropInfo) { targeted = false }

    func dropUpdated(info: DropInfo) -> DropProposal? {
        DropProposal(operation: enabled ? .copy : .forbidden)
    }

    func performDrop(info: DropInfo) -> Bool {
        targeted = false
        guard enabled else { return false }
        return perform(info.itemProviders(for: [UTType.item]))
    }
}

/// The unsubscribe confirmation. Reached only from a message that actually
/// carries the sender's `List-Unsubscribe` offer.
///
/// It says four things before the user can act, because the honest answer to "did
/// this unsubscribe me?" is "the app cannot know":
///
///  - the request goes to the SENDER's server, not GrapMail's;
///  - visiting such a link can itself confirm the address is live, a real cost the
///    one-tap framing of the design hides;
///  - GrapMail cannot undo it;
///  - nothing here claims the outcome — the line shown afterwards reports only that
///    the link was handed to a browser.
///
/// Not private, so the CI snapshot host can photograph it: a CI mailbox has no
/// subscribed mail, so the live path cannot reach this sheet on its own. Android's
/// `UnsubscribeContent` is the same screen with the same four statements.
struct UnsubscribeSheet: View {
    let sender: String?
    let url: String
    /// Present only when the list advertised a one-click request AND the
    /// message names a sender address to put in it. The button sends nothing
    /// by itself — the closure does, through the account's own server.
    var onOneClick: (() -> Void)? = nil
    let onOpen: () -> Void
    let onCancel: () -> Void

    var body: some View {
        // As designed while the four statements fit the sheet; when larger
        // text makes them taller than the sheet, the same content scrolls so
        // the buttons can always be reached.
        ViewThatFits(in: .vertical) {
            sheetContent
            ScrollView { sheetContent }
        }
        .padding(.bottom, 24)
        .accessibilityIdentifier("gm-sheets-unsub")
        .grapMailSheetSurface()
    }

    private var sheetContent: some View {
        let host = urlHost(url) ?? String(localized: "the sender's server", comment: "Stands in for a host name inside the unsubscribe sentences")
        return VStack(alignment: .leading, spacing: 0) {
            SheetTitle(text: "Unsubscribe")
            // One whole sentence per case, never a clause spliced in: the
            // translation must be able to reorder sender and host.
            Text(sender.map { String(localized: "This offer came from the sender, about \($0). Opening it goes to \(host) — not to GrapMail.") }
                 ?? String(localized: "This offer came from the sender. Opening it goes to \(host) — not to GrapMail."))
                .gmFont(13).foregroundColor(GM.textSecondary)
                .padding(.horizontal, 20).padding(.vertical, 6)
            Text("Some mailing lists treat any visit as proof that an address is live, so opening this is not risk-free. GrapMail cannot undo it, and cannot see what happens after: the page at \(host) is the only thing that will say whether you were removed.")
                .gmFont(13).foregroundColor(GM.textSecondary)
                .lineSpacing(5)
                .padding(.horizontal, 20).padding(.vertical, 6)
            Spacer(minLength: 10)
            GradientButton(title: String(localized: "Open in my browser"), action: onOpen)
                .padding(.horizontal, 20)
            if let onOneClick {
                Text("This list also advertises a one-click request: nothing opens, and GrapMail's server sends the exact POST the list asked for. Whether the list acts on it is not visible from here.")
                    .gmFont(13).foregroundColor(GM.textSecondary)
                    .lineSpacing(5)
                    .padding(.horizontal, 20).padding(.vertical, 6)
                GradientButton(title: String(localized: "Unsubscribe in one click"), action: onOneClick)
                    .padding(.horizontal, 20)
            }
            Button(action: onCancel) {
                Text("Cancel")
                    .gmFont(15).foregroundColor(GM.textTertiary)
                    .frame(maxWidth: .infinity).padding(.vertical, 14)
            }
            .buttonStyle(.plain)
        }
    }
}

/// The send receipt. The status word is the server's, and `queued` is shown as
/// queued — a message the queue has only accepted is not a delivered message.
///
/// Internal, not private, because the CI snapshot host photographs this receipt.
/// It used to carry its own copy instead, and the copy handled only `queued` and
/// `failed` — a `sent` frame would have read "Not delivered". Two lists of one
/// screen's words drift with nothing to notice it; one renderer cannot.
struct SendReceipt: View {
    let status: String
    let detail: String?
    let error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // `headline` stays a String of literals because
            // `scripts/mail-honesty.py` reads its arms by shape; the key is
            // looked up here, and the five headlines are listed in the String
            // Catalog inventory (`l10n/source/ios-mail.json`) by hand.
            Text(LocalizedStringKey(headline))
                .gmFont(15, .semibold)
                .foregroundColor(tone)
            if let detail, !detail.trimmingCharacters(in: .whitespaces).isEmpty {
                Text(detail).gmFont(13).foregroundColor(GM.textSecondary)
            }
            if status == "queued" {
                Text("The queue has accepted it. Delivery is confirmed separately.")
                    .gmFont(12).foregroundColor(GM.textTertiary)
            }
            if let error, !error.trimmingCharacters(in: .whitespaces).isEmpty {
                Text(error).gmFont(13).foregroundColor(GM.error)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(GM.softFill))
        .accessibilityIdentifier("mail-send-receipt")
    }

    private var headline: String {
        switch status {
        case "sent": return "Sent"
        // The live send route answers a delivered self-send with this status
        // word — measured, not assumed (the walk's own send came back
        // "delivered"). Without the arm it fell to the verbatim fallback.
        case "delivered": return "Delivered"
        case "queued": return "Queued for delivery"
        case "failed": return "Not delivered"
        case "draft": return "Saved to Drafts"
        default: return "Delivery could not be confirmed"
        }
    }

    private var tone: Color {
        switch status {
        case "sent", "draft", "delivered": return GM.successInk
        case "failed": return GM.error
        default: return GM.warnInk
        }
    }
}

enum MimeType {
    /// Best-effort from the extension, because the picker reports a filename and
    /// the route stores the type it is handed. An unknown extension is sent as
    /// octet-stream rather than guessed at — a wrong declared type can make a
    /// recipient's client refuse the file.
    static func of(filename: String) -> String {
        let ext = (filename as NSString).pathExtension.lowercased()
        let map = [
            "png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg",
            "gif": "image/gif", "pdf": "application/pdf",
            "txt": "text/plain", "csv": "text/csv", "md": "text/markdown",
            "zip": "application/zip", "doc": "application/msword",
            "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls": "application/vnd.ms-excel",
            "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "heic": "image/heic", "webp": "image/webp", "mp4": "video/mp4",
        ]
        return map[ext] ?? "application/octet-stream"
    }
}

/// An outlined action inside the reader, in the shape the reader's own controls
/// use.
///
/// There is no `maxWidth: .infinity` here and that is the whole point: the
/// unsubscribe button owns its row alone, and three of these share one.
/// Android's `OutlineButton(fill: false)` is the same decision made for the same
/// reason — a filling button inside a shared row measures against the whole row
/// and leaves its siblings no width at all, which is how two of the reader's
/// three reply doors vanished from 74-mail-thread while iOS showed all three.
struct ReaderOutlineButton: View {
    /// A resource: the one caller passes literals, which the String Catalog
    /// extracts.
    let title: LocalizedStringResource
    let identifier: String
    let action: () -> Void

    init(_ title: LocalizedStringResource, identifier: String, action: @escaping () -> Void) {
        self.title = title
        self.identifier = identifier
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title)
                .gmFont(15)
                .foregroundColor(GM.textPrimary)
                .padding(.horizontal, 14)
                .frame(minHeight: 44)
                .background(RoundedRectangle(cornerRadius: GMShape.button, style: .continuous)
                    .stroke(GM.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
    }
}
