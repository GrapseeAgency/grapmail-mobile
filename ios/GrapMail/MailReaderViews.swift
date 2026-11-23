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
    @State private var showNotificationActions = false
    @Environment(\.scenePhase) private var scenePhase
    /// Per-attachment, not global: a download result belongs to the row that
    /// asked for it, and a shared variable would let a later failure overwrite an
    /// earlier success on screen.
    @State private var notices: [String: String] = [:]
    @State private var downloading: Set<String> = []
    @State private var isVisible = false
    @State private var expandedMessageId: String?
    @State private var didFocus = false
    @State private var pendingAction = false
    @State private var filed: [String: MailAction] = [:]
    @State private var feedback: [String: (text: String, error: Bool)] = [:]

    private var activeMessage: MailMessageFull? {
        vm.thread.messages.first { $0.id == (expandedMessageId ?? initialMessageId) } ?? vm.thread.messages.last
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            if vm.thread.messages.isEmpty && vm.thread.error == nil {
                CenteredState(title: "Opening this conversation…")
            } else if vm.thread.error != nil, vm.thread.messages.isEmpty {
                failedBody
            } else {
                ScrollViewReader { scroll in
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            subjectHeader
                            if isThread && (vm.thread.hasMore || vm.thread.loadingEarlier) {
                                ReaderOutlineButton(
                                    vm.thread.loadingEarlier ? "Loading earlier messages…" : "Load earlier messages",
                                    identifier: "mail-thread-load-earlier",
                                    action: { vm.loadEarlierThread(id) }
                                )
                                .disabled(vm.thread.loadingEarlier)
                            }
                            ForEach(vm.thread.messages) { message in
                                ThreadMessageCard(
                                    vm: vm,
                                    message: message,
                                    e2e: e2e,
                                    expanded: activeMessage?.id == message.id,
                                    onExpand: { expandedMessageId = message.id },
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
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.top, 8)
                        .padding(.bottom, 16)
                    }
                    .task(id: vm.thread.messages.contains { $0.id == initialMessageId }) {
                        guard let initialMessageId, !didFocus,
                              vm.thread.messages.contains(where: { $0.id == initialMessageId }) else { return }
                        expandedMessageId = initialMessageId
                        scroll.scrollTo(initialMessageId, anchor: .top)
                        didFocus = true
                        if openMessageActions { showNotificationActions = true }
                    }
                }
                if let activeMessage {
                    if let outcome = feedback[activeMessage.id] {
                        Text(outcome.text)
                            .font(.system(size: 12))
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
                    replyBar(for: activeMessage)
                    MailBottomBar(inboxSelected: true, unread: vm.state.unreadCount,
                                  onMail: onBack, onContacts: onContacts, overList: false)
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
        .task(id: MailStep(id: id, isThread: isThread)) {
            expandedMessageId = nil
            isThread ? vm.openThread(id, focusedID: initialMessageId) : vm.openMessage(id)
        }
        .onAppear { isVisible = true }
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

    private var header: some View {
        HStack(spacing: 0) {
            ReaderIconButton("Back", icon: "chevron.backward", action: onBack)
            Spacer(minLength: 0)
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
                            feedback[message.id] = (error ?? "Marked unread.", error != nil)
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
                        .font(.system(size: 19, weight: .medium))
                        .foregroundColor(GM.textPrimary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("Navigate messages")
            }
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 2)
    }

    private var subjectHeader: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 8) {
                Text(vm.thread.messages.first?.subjectOrNone ?? "(no subject)")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                Text(vm.state.box.title)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(GM.primarySolid)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(GM.softFill, in: Capsule())
            }
            .frame(maxWidth: .infinity, alignment: .leading)
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

    private func replyBar(for message: MailMessageFull) -> some View {
        HStack(spacing: 8) {
            replyButton("Reply", icon: "arrowshape.turn.up.left") { onReply(message.id, "reply") }
            replyButton("Forward", icon: "arrowshape.turn.up.right") { onReply(message.id, "forward") }
            ReaderIconButton("Reply all", icon: "arrowshape.turn.up.left.2", tint: GM.primarySolid) {
                onReply(message.id, "all")
            }
            .frame(width: 48, height: 48)
            .background(GM.softFill, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(GM.bg)
        .overlay(alignment: .top) { Rectangle().fill(GM.border).frame(height: 1) }
    }

    private func replyButton(_ label: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            let shape = RoundedRectangle(cornerRadius: 16, style: .continuous)
            Label(label, systemImage: icon)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(GM.primarySolid)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 8)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(GM.card, in: shape)
                .overlay(shape.strokeBorder(GM.border).allowsHitTesting(false))
                .contentShape(shape)
        }
        .buttonStyle(.plain)
    }

    private func file(_ message: MailMessageFull, action: MailAction, success: String) {
        pendingAction = true
        vm.actOn(message.id, action) { error in
            pendingAction = false
            if error == nil { filed[message.id] = action }
            feedback[message.id] = (error ?? success, error != nil)
        }
    }

    private var failedBody: some View {
        VStack(spacing: 8) {
            Text("This conversation could not be opened.")
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(GM.textPrimary)
            Text(vm.thread.error ?? "Check your connection and try again.")
                .font(.system(size: 14)).foregroundColor(GM.textSecondary)
            GradientButton(title: "Try again",
                           action: { isThread ? vm.openThread(id, focusedID: initialMessageId) : vm.openMessage(id) })
                .padding(.top, 8)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct ReaderIconButton: View {
    let label: String
    let icon: String
    let tint: Color
    let action: () -> Void

    @Environment(\.isEnabled) private var enabled

    init(_ label: String, icon: String, tint: Color = GM.textPrimary, action: @escaping () -> Void) {
        self.label = label
        self.icon = icon
        self.tint = tint
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 18, weight: .medium))
                .foregroundColor(enabled ? tint : GM.textTertiary.opacity(0.55))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

private struct ThreadMessageCard: View {
    let vm: MailboxModel
    let message: MailMessageFull
    let e2e: E2eModel
    let expanded: Bool
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
    /// renders inside; it resets with each message.
    @State private var htmlBodyHeight: CGFloat = 180
    // Handing the link over is the browser's job, not this app's, and the note
    // below says only what this process witnessed. It goes through
    // `UIApplication.shared.open` rather than `\.openURL`, because this SDK's
    // `OpenURLAction` cannot report an outcome at all — see the note at the
    // call — and a confirmation that exists to be exact about the destination
    // should not be built on an API that cannot say whether it reached it.

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if expanded {
                senderHeader
                if showDetails { recipientDetails }
                bodyView
                attachmentRows
                if detailsError != nil {
                    Text("Couldn’t load message details.").font(.system(size: 12)).foregroundColor(GM.textSecondary)
                    Button("Retry") { detailsAttempt += 1 }
                }
                if let unsubNote {
                    Text(unsubNote).font(.system(size: 12)).foregroundColor(GM.textSecondary)
                }
                if let handoffNote {
                    Text(handoffNote).font(.system(size: 12)).foregroundColor(GM.error)
                }
                if let decisionNote {
                    Text(decisionNote).font(.system(size: 12)).foregroundColor(GM.textSecondary)
                }
            } else {
                Button(action: onExpand) {
                    senderHeader
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint("Opens this message")
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
                            unsubNote = "That unsubscribe address could not be opened."
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
                                ? "Opened in your browser. GrapMail cannot see what the page said."
                                : "This device could not open that link."
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
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(GM.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(GM.border)
                .allowsHitTesting(false)
        }
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
            let allowImages = shownImages
            guard let render = try? await vm.loadBody(message.id, allowImages: allowImages),
                  !Task.isCancelled else { return }
            if render.error == nil || formattedBody?.html == nil {
                formattedBody = render
            }
            renderLoading = false
        }
    }

    private var senderHeader: some View {
        let sender = message.from?.display.nilIfBlank() ?? "Unknown sender"
        return HStack(spacing: 12) {
            SenderAvatar(display: sender, address: message.from?.address ?? "", size: 44)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(sender)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(GM.textPrimary)
                        .lineLimit(1)
                    SenderBadges(address: message.from?.address ?? "")
                    if let date = MailTime.short(message.createdAt) {
                        Text(date)
                            .font(.system(size: 11))
                            .foregroundColor(GM.textSecondary)
                            .lineLimit(1)
                            .fixedSize()
                    }
                }
                if expanded {
                    Button {
                        showDetails.toggle()
                    } label: {
                        HStack(spacing: 4) {
                            Text(message.to.first.map { "To " + $0 } ?? "Message details")
                                .lineLimit(1)
                            Image(systemName: showDetails ? "chevron.up" : "chevron.down")
                                .font(.system(size: 9, weight: .semibold))
                        }
                        .font(.system(size: 12))
                        .foregroundColor(GM.textSecondary)
                        .padding(.vertical, 5)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("Shows sender, recipients and date")
                } else {
                    Text(message.isE2eEncrypted || message.e2eEnvelope != nil
                         ? "Encrypted message" : (message.bodyText?.nilIfBlank() ?? "Show message"))
                        .font(.system(size: 13))
                        .foregroundColor(GM.textSecondary)
                        .lineLimit(2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if expanded {
                if unsubscribeTarget(message.unsubscribe) != nil {
                    Button("Unsubscribe") { showUnsub = true }
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(GM.primarySolid)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                        .frame(minHeight: 44)
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("mail-unsubscribe")
                }
                Menu {
                    Button("Reply") { onReply("reply") }
                    Button("Share") { showShare = true }
                        .accessibilityIdentifier("mail-share")
                    Button("Print") {
                        printMessageText(message, formattedText: formattedBody?.bodyText) { handoffNote = $0 }
                    }
                    .accessibilityIdentifier("mail-print")
                    Button("Report spam") {
                        vm.actOn(message.id, .spam) { decisionNote = $0 ?? "Moved to spam." }
                    }
                    .accessibilityIdentifier("mail-report-spam")
                    Button("Block sender") {
                        guard let sender = message.from?.address?.nilIfBlank()
                                ?? (message.from?.display ?? "").nilIfBlank() else {
                            decisionNote = "This message does not name a sender to block."
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
                            decisionNote = want ? "Muted: this conversation will not alert you." : "Unmuted."
                        }
                    }
                    .accessibilityIdentifier("mail-mute-thread")
                    Button(policyWatched ? "Stop notifying me" : "Notify me about replies") {
                        let want = !policyWatched
                        Task {
                            if let error = await NotificationPolicy.setWatched(threadKey, want) { decisionNote = error; return }
                            policyWatched = want
                            if want { policyMuted = false }
                            decisionNote = want ? "You will be alerted to replies, whatever your filters." : "Stopped following this conversation."
                        }
                    }
                    .accessibilityIdentifier("mail-watch-thread")
                    if let sender = senderKey {
                        Button(policyVip ? "Remove sender from VIPs" : "Add sender to VIPs") {
                            let want = !policyVip
                            Task {
                                if let error = await NotificationPolicy.setVip(sender, want) { decisionNote = error; return }
                                policyVip = want
                                decisionNote = want ? "\(sender) is a VIP." : "\(sender) is no longer a VIP."
                            }
                        }
                        .accessibilityIdentifier("mail-vip-sender")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(GM.textSecondary)
                        .frame(width: 34, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("Message actions")
            } else {
                Image(systemName: "chevron.down")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(GM.textSecondary)
                    .accessibilityHidden(true)
            }
        }
    }

    private var recipientDetails: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let address = message.from?.address { Text("From \(address)") }
            if !message.to.isEmpty { Text("To \(message.to.joined(separator: ", "))") }
            if !message.cc.isEmpty { Text("Cc \(message.cc.joined(separator: ", "))") }
            if let date = MailTime.full(message.createdAt) { Text(date) }
        }
        .font(.system(size: 12))
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

    @ViewBuilder private var bodyText: some View {
        if message.hasHtml {
            htmlBody
        } else if let text = message.bodyText?.nilIfBlank() {
            Text(text)
                .font(.system(size: 15))
                .foregroundColor(GM.textPrimary)
                .lineSpacing(4)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            Text("This message has no text body.")
                .font(.system(size: 14)).foregroundColor(GM.textSecondary)
        }
    }

    @ViewBuilder private var htmlBody: some View {
        if let render = formattedBody, let clean = render.html?.nilIfBlank() {
            MailHtmlBodyView(
                html: clean,
                allowImages: render.allowImages,
                onOpenLink: { urlString in
                    guard let url = URL(string: urlString),
                          let scheme = url.scheme?.lowercased(),
                          ["https", "http", "mailto"].contains(scheme) else { return }
                    UIApplication.shared.open(url)
                },
                contentHeight: $htmlBodyHeight
            )
            .frame(height: htmlBodyHeight)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("mail-body")
        } else {
            let text = message.bodyText?.nilIfBlank() ?? formattedBody?.bodyText?.nilIfBlank()
            if let text {
                Text(text)
                    .font(.system(size: 15))
                    .foregroundColor(GM.textPrimary)
                    .lineSpacing(4)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
            } else if formattedBody?.error != nil {
                Text("Couldn’t display this message.").font(.system(size: 13)).foregroundColor(GM.textSecondary)
                Button("Retry") { renderAttempt += 1 }.disabled(renderLoading)
            } else {
                ProgressView().frame(maxWidth: .infinity)
                    .accessibilityLabel("Loading message")
            }
        }
    }

    @ViewBuilder private var imagesGate: some View {
        if let render = formattedBody, render.blockedImages > 0, !render.allowImages {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(shownImages && !renderLoading ? "Images couldn’t load."
                         : "\(render.blockedImages) remote image\(render.blockedImages == 1 ? "" : "s") hidden")
                        .font(.system(size: 12, weight: .semibold)).foregroundColor(GM.textPrimary)
                    Text("Loading them tells the sender this message was opened.")
                        .font(.system(size: 11)).foregroundColor(GM.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 8)
                Button(shownImages && renderLoading ? "Loading…" : "Show images") {
                    if shownImages { renderAttempt += 1 } else { shownImages = true }
                }
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(GM.primarySolid)
                .frame(minHeight: 44)
                .disabled(shownImages && renderLoading)
                .accessibilityIdentifier("mail-show-images")
            }
            .padding(.horizontal, 12)
            .background(GM.bgTinted, in: RoundedRectangle(cornerRadius: 12))
        }
    }

    @ViewBuilder private var attachmentRows: some View {
        ForEach(message.attachments) { a in
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 10) {
                    Image(systemName: "paperclip").foregroundColor(GM.textTertiary)
                    Text(a.name ?? "attachment")
                        .font(.system(size: 14)).foregroundColor(GM.textPrimary)
                        .lineLimit(1)
                    if let n = a.sizeBytes {
                        Text(humanBytes(n)).font(.system(size: 12))
                            .foregroundColor(GM.textTertiary)
                    }
                    Spacer()
                    if downloading.contains(a.id) {
                        Text("Saving…").font(.system(size: 12)).foregroundColor(GM.textTertiary)
                    } else {
                        Button("Save") { onDownload(a) }
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(GM.primarySolid)
                    }
                }
                .padding(.vertical, 8)
                if let notice = notices[a.id] {
                    Text(notice).font(.system(size: 12)).foregroundColor(GM.textSecondary)
                }
            }
        }

    }
}

private struct EncryptedNotice: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: "lock.fill").font(.system(size: 14))
                    .foregroundColor(GM.successInk)
                Text("End-to-end encrypted").font(.system(size: 14, weight: .semibold))
                    .foregroundColor(GM.textPrimary)
            }
            Text("Only you and the sender can read this. The server holds the sealed "
                 + "envelope and cannot open it. Unlocking this device's key opens "
                 + "the body below — a wrong passphrase and a message not sealed "
                 + "to this key each say so, and the ciphertext is never shown as "
                 + "a body.")
                .font(.system(size: 13)).lineSpacing(4)
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
                    .font(.system(size: 15)).lineSpacing(5)
                    .foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("gm-e2e-opened-body")
            } else if let envelope = message.e2eEnvelope {
                if !showPhrase {
                    Text("Decrypt with your passphrase")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(GM.primarySolid)
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            e2e.load(includeDirectory: false)
                            showPhrase = true
                        }
                        .accessibilityIdentifier("gm-e2e-decrypt-open")
                } else {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Your passphrase decrypts this message on this device. It is never sent "
                             + "anywhere, and it is not kept after the message opens.")
                            .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                        AppTextField(title: "Passphrase", value: $phrase, isPassword: true)
                        HStack(spacing: 16) {
                            Text(working ? "Opening…" : "Open")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundColor(GM.primarySolid)
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
                                .font(.system(size: 14))
                                .foregroundColor(GM.textSecondary)
                                .padding(.vertical, 6)
                                .contentShape(Rectangle())
                                .onTapGesture { showPhrase = false }
                        }
                        if let failed {
                            Text(failed).font(.system(size: 12)).foregroundColor(GM.error)
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
                    .font(.system(size: 12)).foregroundColor(GM.warnInk)
                    .accessibilityIdentifier("gm-e2e-no-envelope")
            }
        }
    }
}

private struct HtmlStrippedNotice: View {
    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "info.circle").font(.system(size: 12))
            Text("Plain-text view of an HTML message. Remote images and markup are not loaded.")
                .font(.system(size: 12))
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
                        .frame(maxWidth: .infinity)
                    }
                }
                if let notice {
                    ScrollView(.vertical, showsIndicators: false) {
                        Text(notice)
                            .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 60)
                    .padding(.top, 10)
                }
                if let status = vm.compose.resultStatus {
                    SendReceipt(status: status,
                                detail: vm.compose.result,
                                error: vm.compose.error)
                        .padding(.top, 12)
                        .padding(.horizontal, 16)
                } else if let error = vm.compose.error {
                    Text(error).font(.system(size: 13)).foregroundColor(GM.error)
                        .padding(.top, 12)
                        .padding(.horizontal, 16)
                } else if vm.compose.autosaving {
                    // The autosave's own quiet line, deliberately NOT the receipt:
                    // a background save is not an event the person acted on, so it
                    // draws smaller and duller than anything the buttons produce,
                    // and it yields to the receipt entirely when one exists.
                    Text("Saving draft…")
                        .font(.system(size: 11)).foregroundColor(GM.textTertiary)
                        .padding(.top, 10)
                        .padding(.horizontal, 16)
                } else if let note = vm.compose.autosaveNote {
                    Text(note)
                        .font(.system(size: 11)).foregroundColor(GM.textTertiary)
                        .padding(.top, 10)
                        .padding(.horizontal, 16)
                }
                actions
            }
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .background(GM.bg)
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
                sendTapped()
            }
            Button("Keep editing", role: .cancel) { compatFindings = nil }
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
        .onChange(of: vm.compose.accepted) { accepted in
            guard accepted else { return }
            if UserDefaults.standard.bool(forKey: "gm.pendingFollowUp"),
               let threadId = vm.compose.inReplyTo {
                UserDefaults.standard.set(false, forKey: "gm.pendingFollowUp")
                MailFeaturePrefs.shared.markAwaitingReply(
                    threadId: threadId,
                    title: vm.compose.subject.isEmpty ? "(no subject)" : vm.compose.subject)
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
                Image(systemName: "xmark").font(.system(size: 17))
                    .foregroundColor(GM.textPrimary)
            }
            .accessibilityLabel("Discard")
            Text("Compose")
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(GM.textPrimary)
            Spacer()
            Button { sendTapped() } label: {
                Image(systemName: "paperplane.fill")
                    .font(.system(size: 18))
                    .foregroundColor(vm.compose.sending ? GM.textTertiary : GM.primarySolid)
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
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.white)
                Spacer()
                Button { vm.undoSend() } label: {
                    Text("Undo")
                        .font(.system(size: 14, weight: .bold))
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
            .font(.system(size: 13, weight: .medium))
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
        Divider().overlay(GM.border)
        // The encrypt row. The states are the web compose's own, and Android's:
        // a device with no key never sees the option; a locked key says what
        // would fix it; only an unlocked key can flip it on. And when it is on,
        // the person is told plainly that the body is sealed before it leaves
        // the device -- the server will store a notice, not text.
        if e2e.state.record != nil {
            HStack(spacing: 10) {
                Image(systemName: "lock").font(.system(size: 15))
                    .foregroundColor(encryptOn ? GM.primarySolid : GM.textTertiary)
                Text(encryptOn ? "Encrypting this message" : "Encrypt this message")
                    .font(.system(size: 14))
                    .foregroundColor(encryptOn ? GM.primarySolid : GM.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                ZStack {
                    Circle().strokeBorder(encryptOn ? GM.primarySolid : GM.border, lineWidth: 1.5)
                        .frame(width: 20, height: 20)
                    if encryptOn {
                        Circle().fill(GM.primarySolid).frame(width: 20, height: 20)
                        Text("✓").font(.system(size: 12)).foregroundColor(.white)
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
                    .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            } else if encryptOn {
                Text("Only the recipients can open this message. Encrypted messages are sent as plain text.")
                    .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer().frame(height: 4)
        }
    }

    private func field(
        label: String, key: WritableKeyPath<MailboxModel.ComposeState, String>,
        placeholder: String, tag: String,
    ) -> some View {
        HStack(spacing: 12) {
            Text(label).font(.system(size: 14)).foregroundColor(GM.textSecondary)
                .frame(width: 56, alignment: .leading)
            TextField(placeholder,
                      text: Binding(
                        get: { vm.compose[keyPath: key] },
                        set: { value in vm.composeUpdate { $0[keyPath: key] = value } }))
                .font(.system(size: 15))
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
                            SenderAvatar(display: p.name ?? "", address: p.address, size: 26)
                            Text(p.name ?? p.address)
                                .font(.system(size: 14)).foregroundColor(GM.textPrimary)
                            Text(p.address)
                                .font(.system(size: 12)).foregroundColor(GM.textTertiary)
                                .lineLimit(1)
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
            .font(.system(size: 13, weight: .medium))
            if formatting {
                ComposeFormattingEditor(
                    text: vm.compose.body, html: vm.compose.bodyHtml,
                    pendingImage: pendingImage,
                    onImageInserted: { pendingImage = nil },
                    onPickImage: encryptOn ? nil : { pickingInline = true }
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
                    .font(.system(size: 15))
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
                                Image(systemName: "paperclip").font(.system(size: 11))
                                Text(a.name ?? "file").font(.system(size: 12)).lineLimit(1)
                                Button { attached.removeAll { $0.id == a.id } } label: {
                                    Image(systemName: "xmark.circle")
                                }
                                .accessibilityLabel("Remove " + (a.name ?? "attachment"))
                                .disabled(vm.compose.sending || vm.compose.accepted || preparingSend)
                            }
                            .foregroundColor(GM.textSecondary)
                            .padding(.horizontal, 10).padding(.vertical, 6)
                            .background(Capsule().fill(GM.card))
                        }
                    }
                }
            }
        }
    }

    private var actions: some View {
        HStack(spacing: 12) {
            Button { picking = true } label: {
                Image(systemName: "plus").font(.system(size: 17))
                    .foregroundColor(GM.primarySolid)
                    .frame(width: 44, height: 44)
                    .background(Circle().stroke(GM.border, lineWidth: 1))
            }
            .accessibilityLabel("Attach a file")
            .disabled(!sendEnabled)
            Button {
                saveDraft()
            } label: {
                Text("Save draft")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(GM.primarySolid)
                    .padding(.vertical, 14).padding(.horizontal, 18)
                    .background(Capsule().stroke(GM.border, lineWidth: 1))
            }
            Spacer()
            GradientButton(title: vm.compose.sending ? "Sending…" : "Send",
                           enabled: sendEnabled,
                           action: { sendTapped() })
        }
        .padding(.top, 14)
        .padding(.bottom, 28)
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

    private func saveDraft(onSaved: @escaping () -> Void = {}) {
        guard sendEnabled else { return }
        guard attached.isEmpty else {
            notice = "Remove the attachments before saving this draft."
            return
        }
        vm.saveDraft(onSaved: onSaved)
    }

    private func sendTapped() {
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
                        vm.send(attachmentIds: attachmentIds)
                    } else {
                        compatFindings = report?.findings
                    }
                }
                return
            }
            vm.send(attachmentIds: attachmentIds)
            return
        }
        guard attached.isEmpty else {
            notice = "Encrypted messages cannot include attachments."
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
                notice = "The encryption directory is unavailable. Try again."
                return
            }
            guard plan.mode == "seal" else {
                notice = "A recipient has no published encryption key."
                return
            }
            guard let envelope = await e2e.seal(plaintext: draft.body, recipientKeys: plan.keys) else { return }
            let current = vm.compose
            guard current.to == draft.to, current.cc == draft.cc, current.bcc == draft.bcc,
                  current.body == draft.body, attached.isEmpty else {
                notice = "The message changed while encryption was being prepared. Tap Send again."
                return
            }
            vm.send(e2eEnvelope: envelope)
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
            let label = f.severity == "removed" ? "Removed" : f.severity == "warning" ? "May break" : "Note"
            let clients = f.clients.isEmpty ? "" : " (" + f.clients.joined(separator: ", ") + ")"
            return "\(label): \(f.message)\(clients)"
        }
        return "Some of this message's formatting won't arrive the way it looks here.\n\n" + lines.joined(separator: "\n\n")
    }

    /// Upload a picked photo to be placed inside the message (`inline`), then
    /// hand the editor a preview no wider than 600 px. The photo is re-encoded as
    /// JPEG (or PNG when it has transparency) at most 1600 px wide, so a 12 MP
    /// camera image does not become a 6 MB email.
    private func placeInlineImage(_ item: PhotosPickerItem) {
        guard sendEnabled, !uploading else { return }
        guard attached.count < 20 else {
            notice = "A message can have up to 20 attachments and images."
            return
        }
        uploading = true
        let generation = SessionStore.generation
        Task { @MainActor in
            defer { uploading = false }
            guard let data = try? await item.loadTransferable(type: Data.self),
                  let image = UIImage(data: data) else {
                notice = "That photo could not be read."
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
                notice = "That photo could not be prepared."
                return
            }
            guard bytes.count <= 10 * 1024 * 1024 else {
                notice = "Images inside a message can be up to 10 MB."
                return
            }
            guard let token = SessionStore.token, SessionStore.generation == generation else {
                notice = "Sign in again to insert an image."
                return
            }
            let name = "image." + (hasAlpha ? "png" : "jpg")
            notice = "Uploading image…"
            do {
                let attachment = try await ApiClient.mailAttachmentUpload(
                    token: token, bytes: bytes, filename: name,
                    mimeType: hasAlpha ? "image/png" : "image/jpeg", inline: true)
                guard SessionStore.generation == generation else { return }
                attached.append(attachment)
                guard let cid = attachment.contentId, !cid.isEmpty else {
                    notice = "This server can't place images inside messages yet, so the image was attached instead."
                    return
                }
                let preview = "data:image/" + (hasAlpha ? "png" : "jpeg") + ";base64," + previewBytes.base64EncodedString()
                pendingImage = InlineImage(contentId: cid, preview: preview, alt: "Image",
                                           width: Int(min(600, image.size.width)))
                notice = "Image inserted."
            } catch {
                notice = apiMessage(error, fallback: "That image could not be inserted.")
            }
        }
    }

    private func pick(_ result: Result<[URL], Error>) {
        guard case let .success(urls) = result, let url = urls.first else {
            if case let .failure(e) = result { notice = apiMessage(e, fallback: "Could not complete the message action. Try again.") }
            return
        }
        guard sendEnabled else { return }
        guard attached.count < 20 else {
            notice = "A message can have up to 20 attachments."
            return
        }
        uploading = true
        let generation = SessionStore.generation
        Task { @MainActor in
            let name = url.lastPathComponent
            notice = "Reading \(name)…"
            let result = await Self.readPickedFile(url)
            guard let file = result.file else {
                uploading = false
                notice = result.error
                return
            }
            guard SessionStore.generation == generation else {
                try? FileManager.default.removeItem(at: file)
                uploading = false
                notice = "The account changed before this file could be attached."
                return
            }
            notice = "Uploading \(name)…"
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
                        continuation.resume(returning: (nil, "That file exceeds the 25 MB attachment limit."))
                    } else if size == 0 {
                        try? FileManager.default.removeItem(at: staged)
                        continuation.resume(returning: (nil, "That file is empty."))
                    } else {
                        continuation.resume(returning: (staged, nil))
                    }
                } catch {
                    try? FileManager.default.removeItem(at: staged)
                    continuation.resume(returning: (nil, "That file could not be read."))
                }
            }
        }
    }

    static func append(_ existing: String, _ address: String) -> String {
        let t = existing.trimmingCharacters(in: .whitespaces)
        if t.isEmpty { return address }
        return t.hasSuffix(",") ? "\(t) \(address)" : "\(t), \(address)"
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
        let host = urlHost(url) ?? "the sender's server"
        VStack(alignment: .leading, spacing: 0) {
            SheetTitle(text: "Unsubscribe")
            Text("This offer came from the sender"
                 + (sender.map { ", about \($0)" } ?? "")
                 + ". Opening it goes to \(host) — not to GrapMail.")
                .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                .padding(.horizontal, 20).padding(.vertical, 6)
            Text("Some mailing lists treat any visit as proof that an address is live, "
                 + "so opening this is not risk-free. GrapMail cannot undo it, and cannot "
                 + "see what happens after: the page at \(host) is the only thing that "
                 + "will say whether you were removed.")
                .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                .lineSpacing(5)
                .padding(.horizontal, 20).padding(.vertical, 6)
            Spacer(minLength: 10)
            GradientButton(title: "Open in my browser", action: onOpen)
                .padding(.horizontal, 20)
            if let onOneClick {
                Text("This list also advertises a one-click request: nothing opens, and "
                     + "GrapMail's server sends the exact POST the list asked for. Whether "
                     + "the list acts on it is not visible from here.")
                    .font(.system(size: 13)).foregroundColor(GM.textSecondary)
                    .lineSpacing(5)
                    .padding(.horizontal, 20).padding(.vertical, 6)
                GradientButton(title: "Unsubscribe in one click", action: onOneClick)
                    .padding(.horizontal, 20)
            }
            Button(action: onCancel) {
                Text("Cancel")
                    .font(.system(size: 15)).foregroundColor(GM.textTertiary)
                    .frame(maxWidth: .infinity).padding(.vertical, 14)
            }
            .buttonStyle(.plain)
        }
        .padding(.bottom, 24)
        .accessibilityIdentifier("gm-sheets-unsub")
        .grapMailSheetSurface()
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
            Text(headline)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(tone)
            if let detail, !detail.trimmingCharacters(in: .whitespaces).isEmpty {
                Text(detail).font(.system(size: 13)).foregroundColor(GM.textSecondary)
            }
            if status == "queued" {
                Text("The queue has accepted it. Delivery is confirmed separately.")
                    .font(.system(size: 12)).foregroundColor(GM.textTertiary)
            }
            if let error, !error.trimmingCharacters(in: .whitespaces).isEmpty {
                Text(error).font(.system(size: 13)).foregroundColor(GM.error)
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
    let title: String
    let identifier: String
    let action: () -> Void

    init(_ title: String, identifier: String, action: @escaping () -> Void) {
        self.title = title
        self.identifier = identifier
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 15))
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
