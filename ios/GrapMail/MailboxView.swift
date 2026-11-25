import SwiftUI
import Combine

/// The mailbox home presents messages, search, and account and folder controls.
///
/// The reference draws Gmail's category tabs (`Primary / Social / Promotions /
/// Updates`) and filter chips (`All / Personal / Work / +2`). No `category`
/// column exists on the messages table and `/inbox` accepts no such filter, so
/// inventing counts for them would put numbers on screen that no system
/// produced. What ships instead is the real box row and the real labels from
/// `GET /grapmail/labels`. The bottom bar carries no Calendar tab for the same
/// reason. Each omission is recorded with its proof in the README.
struct MailboxView: View {
    @Environment(\.displayScale) private var displayScale
    /// The banners stack their action under their sentence at the
    /// accessibility text sizes rather than squeezing it to a sliver.
    @Environment(\.dynamicTypeSize) private var dts
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Owned by `RootView`, not by this screen: the thread, compose and the
    /// secondary screens are separate destinations on the stack and must show
    /// the same mailbox the list is showing. A model per screen would mean a
    /// list that forgets what a thread just read.
    @ObservedObject var vm: MailboxModel
    /// The device's connectivity, read from the shared monitor rather than
    /// owned here: the monitor starts with the app, before any screen exists,
    /// so the first mailbox draw already knows whether it is asking over a
    /// path that works.
    @ObservedObject private var network = NetworkMonitor.shared
    /// The thread route carries either a thread id or, when a message has none,
    /// its own id — and says which it is. Asking the thread route for a message
    /// id answers 404, so a row without a thread must open through the message
    /// route or the tap does nothing useful. The third value is the tapped
    /// row's own message id -- the iOS 18 zoom push's source (RootView), which
    /// stays unique per row where two rows of one thread share a thread id.
    let onThread: (String, Bool, String) -> Void
    /// A draft row opens the composer on its own text, not the reader.
    let onDraft: (String) -> Void
    let onCompose: () -> Void
    let onLabels: () -> Void
    let onScreener: () -> Void
    let onSubscriptions: () -> Void
    let onProfile: () -> Void
    let onSettings: () -> Void
    let onSecurity: () -> Void
    let onHelp: () -> Void
    let onContacts: () -> Void
    let onFiles: () -> Void
    let onSignOut: () -> Void
    var onAddAccount: () -> Void = {}
    var onSwitchAccount: (String) -> Void = { _ in }
    var accountPhoto: Data? = nil
    /// The thread/message id open in the iPad's reader column, so its row is
    /// drawn as the selected one. nil on compact widths: nothing is drawn.
    var openRowID: String? = nil
    /// The long-press menu's Reply (MailRowPreview.swift), handed the row's
    /// message id. nil hides the item.
    var onReply: ((String) -> Void)? = nil
    /// RootView's namespace for the iOS 18 "grows out of its row" push. nil on
    /// the two-pane layout, where a tap selects instead of pushing.
    var zoomNamespace: Namespace.ID? = nil

    @Environment(\.scenePhase) private var scenePhase
    @State private var isAtTop = true
    @State private var isVisible = false
    @State private var controlsVisible = true
    @State private var isUserScrolling = false
    @State private var lastScrollTranslation: CGFloat = 0
    @GestureState private var scrollTranslation: CGSize? = nil

    @State private var drawerOpen = false
    @State private var sheet: MailSheet?
    @State private var decisionResult: String?
    @State private var searchDraft = ""
    @State private var searchTask: Task<Void, Never>?
    @State private var searchRevision = 0
    /// The search pill's keyboard focus (⌘F puts it there on iPad).
    @FocusState private var searchFocused: Bool
    @State private var categoryFilter: String? = nil

    // ── the Good morning card, code chips and Inbox Zero ──
    /// The card's active filter (MailboxInsights.swift); shown as a chip.
    @State private var insightFilter: InboxInsightKind? = nil
    /// The time the insights are judged at. Ticks once a minute while the
    /// list is on screen, so the greeting period and the 60-minute code
    /// window move without a reload.
    @State private var insightClock = Date()
    /// The period the card was dismissed in ("2026-11-24.morning").
    @AppStorage("gm.inboxBrief.dismissedPeriod") private var dismissedBriefPeriod = ""
    /// Mail settings → On-device intelligence (MailFeaturePrefs). Off hides the
    /// category chips and the card's package count; bound here so the list
    /// redraws the moment the switch flips, not on the next reload.
    @AppStorage(MailFeaturePrefs.smartFeaturesKey) private var smartFeatures = true
    @State private var codeCopied = false
    @State private var codeToastTask: Task<Void, Never>?

    // ── choose your swipes ──
    @AppStorage(SwipePreferences.rightKey) private var swipeRight: SwipeChoice = SwipePreferences.defaultRight
    @AppStorage(SwipePreferences.leftKey) private var swipeLeft: SwipeChoice = SwipePreferences.defaultLeft
    /// A row picked by a Move to… swipe so the existing sheet (which acts on
    /// the selection) can act on it; released when the sheet closes.
    @State private var swipeSheetRow: String?
    @State private var releaseAfterBusy: String?
    /// The row a Snooze swipe opened the SnoozeSheet for. Snoozed directly
    /// (`MailboxModel.actOn(_:_:until:)`), never through the selection.
    @State private var swipeSnoozeRow: String?

    /// The boxes whose rows offer the sign-in code chip: mail that arrived.
    /// Not Sent / Drafts (outbound), not Spam (a code there is a lure) and
    /// not Trash.
    private static let codeBoxes: Set<MailBox> = [.inbox, .starred, .archive, .others, .snoozed]

    enum MailSheet: String, Identifiable {
        case sortFilter, move, label, snooze, permanent, sender, advanced
        var id: String { rawValue }
    }

    var body: some View {
        ZStack(alignment: .leading) {
            content
                // While the folder drawer is open the list behind it is not
                // reachable by VoiceOver either (the drawer is a modal).
                .accessibilityHidden(drawerOpen)
            if drawerOpen {
                MailDrawer(
                    box: vm.state.box,
                    boxes: drawerBoxes(vm.state),
                    counts: drawerCounts(vm.state),
                    onSelect: { open($0); drawerOpen = false },
                    onDismiss: { toggleDrawer() },
                    onLabels: { drawerOpen = false; onLabels() },
                    onScreener: { drawerOpen = false; onScreener() },
                    onSubscriptions: { drawerOpen = false; onSubscriptions() },
                    onFiles: { drawerOpen = false; onFiles() },
                    onSortFilter: { drawerOpen = false; sheet = .sortFilter },
                    onAdvanced: { drawerOpen = false; sheet = .advanced },
                    onSettings: { drawerOpen = false; onSettings() },
                    onSecurity: { drawerOpen = false; onSecurity() },
                    onHelp: { drawerOpen = false; onHelp() },
                    onSignOut: { drawerOpen = false; onSignOut() }
                )
                // Reduce Motion: the drawer fades in place instead of sliding
                // (round8-spec §1.10).
                .transition(reduceMotion ? .opacity : .move(edge: .leading))
                .zIndex(2)
            }
        }
        // No ignoresSafeArea on the screen. It was applied to the whole ZStack,
        // which pushed the tab bar and the drawer's pinned footer under the home
        // indicator: the rows rendered, reported a frame, and could not be hit —
        // and a bottom bar drawn under the indicator is a defect for a person,
        // not only for the test. The background may still bleed past it.
        .sheet(item: $sheet, onDismiss: releaseSwipeSelection) { which in sheetContent(which).grapMailSheetSurface() }
        // First-time feature tips (FeatureTips.swift): this session's one tip is
        // picked only after the list has loaded rows, and none shows while a
        // sheet or the drawer is over the list.
        .featureTipsSession(ready: !vm.state.loading && !vm.state.rows.isEmpty && vm.state.fatal == nil,
                            overlayOpen: sheet != nil || drawerOpen)
        .onChange(of: categoryFilter) { value in
            FeatureTips.used(.categories)
            // A category hides the Good morning card, so its filter goes too.
            if value != nil { insightFilter = nil }
        }
        .onChange(of: smartFeatures) { on in
            // Switched off: the chips are gone, so no category may keep
            // filtering the list, and the packages filter has nothing to count.
            guard !on else { return }
            categoryFilter = nil
            if insightFilter == .packages { insightFilter = nil }
        }
        .onAppear {
            if searchDraft != vm.state.query { searchDraft = vm.state.query }
            insightClock = Date()
        }
        .onDisappear {
            searchTask?.cancel()
            searchTask = nil
        }
        .task {
            // Ask for the box only when it has never been asked for, and read
            // `/me` once. Returning from a thread re-enters this task.
            vm.ensureLoaded()
            vm.loadAccount()
            if scenePhase == .active { vm.refreshOnResume(atTop: isAtTop || vm.state.rows.isEmpty) }
        }
        .task {
            // The greeting's period and the code chips' 60-minute window are
            // both time-bound; a minute tick keeps them true while the list
            // sits on screen. Cancelled with the view.
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                guard !Task.isCancelled else { return }
                insightClock = Date()
            }
        }
        .onAppear { isVisible = true }
        .onChange(of: vm.state.box) { _ in
            controlsVisible = true
            insightFilter = nil
        }
        .onChange(of: vm.state.query) { _ in
            controlsVisible = true
            insightFilter = nil
        }
        .onChange(of: vm.state.filter) { _ in controlsVisible = true }
        .onChange(of: vm.state.busy) { busy in
            guard !busy else { return }
            settleBulkFiling()
        }
        .onDisappear { isVisible = false }
        .onChange(of: scenePhase) { phase in
            if phase == .active { insightClock = Date() }
            if phase == .active, isVisible { vm.refreshOnResume(atTop: isAtTop || vm.state.rows.isEmpty) }
        }
        .onChange(of: network.online) { online in
            if online == true, scenePhase == .active, isVisible,
               !vm.state.loading, !vm.state.loadingMore, !vm.state.busy,
               vm.state.selection.isEmpty, vm.state.pages <= 1,
               vm.state.fatal != nil || vm.state.staleError != nil {
                vm.load(forceCounts: false)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: MailPoll.didReadStats)) { notification in
            guard scenePhase == .active, isVisible, let result = notification.object as? MailPoll.PollResult else { return }
            vm.acceptPoll(result, atTop: isAtTop || vm.state.rows.isEmpty)
        }
        // No `.accessibilityElement(children: .contain)` here. It makes the
        // identifier resolvable, and it broke every tap on the screen: with the
        // drawer overlay as a sibling inside this ZStack, XCUITest's
        // scroll-to-visible action on any descendant returned
        // kAXErrorCannotComplete. The home is anchored on a real leaf instead —
        // see `homeOnScreen` in the walk.
    }

    // ── the column ───────────────────────────────────────────────────────────

    private var content: some View {
        ZStack(alignment: .bottom) {
            GM.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                header
                // The box's name is drawn once, by MailHomeTopBar under the
                // search pill. A second copy here printed "Inbox" twice.
                if vm.state.selection.isEmpty {
                    if vm.state.box == .inbox {
                        if smartFeatures {
                            MailCategoryChipsRow(selected: $categoryFilter)
                                .featureTip(.categories)
                        }
                        // The Good morning card's filter, clearable in one tap.
                        if let insightFilter {
                            InboxInsightFilterChip(kind: insightFilter, onClear: clearInsightFilter)
                                .transition(.opacity)
                        }
                    }
                }
                offlineBanner
                // Storage at 80% and above (StorageBanner.swift).
                StorageBanner(onOpenFiles: onFiles)
                staleBanner
                listArea
            }
            MailBottomControls(
                inboxSelected: vm.state.box == .inbox,
                unread: vm.state.unreadCount,
                onMail: { open(.inbox) },
                onCompose: onCompose,
                onContacts: onContacts,
                visible: controlsVisible
            )
            // "Code copied" after a row's code chip, above the bottom bar.
            if codeCopied {
                CodeCopiedToast()
                    .padding(.bottom, MailBottomControls.clearance + 8)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .bottom)))
                    .zIndex(3)
            }
        }
    }

    /// The selection bar replaces the whole header block rather than sitting
    /// over it: a toolbar drawn on top of the controls it acts on is a bar that
    /// covers what the user may want to check first.
    @ViewBuilder private var header: some View {
        if !vm.state.selection.isEmpty {
            SelectionToolbar(
                count: vm.state.selection.count,
                onClose: { vm.clearSelection() },
                onArchive: { bulk(.archive) },
                // "Delete" trashes. Permanent destruction is the second,
                // explicit step behind the confirmation sheet, because a bulk
                // toolbar must never be able to destroy mail.
                onDelete: {
                    if vm.state.box == .trash { sheet = .permanent }
                    else { bulk(.trash) }
                },
                onMove: { sheet = .move },
                onLabel: { vm.ensureAssignments(); sheet = .label },
                onMarkRead: { vm.act(.read) },
                onMarkUnread: { vm.act(.unread) },
                onMore: { FeatureTips.used(.snooze); sheet = .snooze },
                // Only the queue offers this. Deciding a sender is a rule about
                // their next message, which means nothing for a box whose mail
                // already arrived. Last because Swift orders arguments the way the
                // struct declares them, and `onSender` is the defaulted one.
                onSender: vm.state.box == .screener ? { sheet = .sender } : nil
            )
            .featureTip(.snooze)
        } else {
            MailHomeTopBar(
                boxTitle: vm.state.box.title,
                accountName: vm.state.accountName,
                accountAddress: vm.state.accountAddress,
                onMenu: { FeatureTips.used(.screener); toggleDrawer() },
                onProfile: onProfile,
                onSettings: onSettings,
                onSignOut: onSignOut,
                onAddAccount: onAddAccount,
                onSwitchAccount: onSwitchAccount,
                accountPhoto: accountPhoto,
                search: { searchField }
            )
        }
    }

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(GM.textSecondary)
                .accessibilityHidden(true)
            TextField("Search in mail", text: $searchDraft)
                .focused($searchFocused)
                .submitLabel(.search)
                .onChange(of: searchDraft) { text in
                    searchTask?.cancel()
                    searchRevision &+= 1
                    let revision = searchRevision
                    searchTask = Task {
                        try? await Task.sleep(nanoseconds: 350_000_000)
                        guard !Task.isCancelled else { return }
                        await MainActor.run {
                            guard !Task.isCancelled, revision == searchRevision,
                                  text == searchDraft else { return }
                            vm.query(text)
                        }
                    }
                }
                .onChange(of: vm.state.query) { query in
                    if searchDraft != query { searchDraft = query }
                }
                .gmFont(14)
                .foregroundColor(GM.textPrimary)
                .accessibilityLabel("Search subject, body or sender")
                .accessibilityIdentifier("mail-search")
            // One tap empties the field (and the search with it, through the
            // same debounce as typing). Only while there is something to clear.
            if !searchDraft.isEmpty {
                Button {
                    searchDraft = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .gmFont(15, maxSize: 24)
                        .foregroundColor(GM.textTertiary)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Clear search", tableName: "R9Mailbox",
                                         comment: "VoiceOver label of the button that empties the mailbox search field"))
                .accessibilityIdentifier("mail-search-clear")
            }
        }
        .padding(.horizontal, 12)
        // 44 pt tall whether or not the clear button shows (it carries its own
        // 44 pt target), and taller only when the text itself is.
        .frame(minHeight: 44)
        .background(GM.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(GM.border)
                .allowsHitTesting(false)
        }
        // ⌘F on a hardware keyboard (iPad) puts the cursor in the search field.
        .background {
            Button("Search") { searchFocused = true }
                .keyboardShortcut("f", modifiers: .command)
                .mailShortcutCarrier()
        }
    }

    private var advActive: Bool {
        !vm.state.advFrom.isEmpty || !vm.state.advTo.isEmpty ||
        !vm.state.advSubject.isEmpty || vm.state.advFiles ||
        !vm.state.advAfter.isEmpty || !vm.state.advBefore.isEmpty
    }

    private var filterActive: Bool {
        vm.state.filter != .all || vm.state.sort != .newest
    }

    /// The device's own path to the network, read before asking rather than
    /// after a failure. The stale-list chip can only say a refresh was refused;
    /// this says why, and says it while the list is still on screen, so a list
    /// that has not moved for ten minutes is not mistaken for a quiet inbox.
    ///
    /// `nil` — no reading yet — draws nothing. "Offline" before the monitor's
    /// first callback would be a claim about a network nobody has checked.
    @ViewBuilder private var offlineBanner: some View {
        if network.online == false {
            HStack(spacing: 8) {
                Text("No network right now — showing the last loaded mail.")
                    .gmFont(12).foregroundColor(GM.warnInk)
                    .lineLimit(dts.isAccessibilitySize ? nil : 2)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 4)
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(GM.warnChipBg))
            .padding(.horizontal, 16).padding(.vertical, 6)
            // The same reading width as the screens' shared banner.
            .gmMaxWidth(GMLayout.readable)
            .accessibilityIdentifier("mail-offline")
        }
    }

    /// A refresh that failed over a populated list says so here instead of
    /// replacing the list with an error screen — the hub's defect, designed out.
    @ViewBuilder private var staleBanner: some View {
        if let msg = vm.state.actionError ?? (vm.state.rows.isEmpty ? nil : vm.state.staleError) {
            GMAdaptiveStack(spacing: 8) {
                Text(msg)
                    .gmFont(12).foregroundColor(GM.warnInk)
                    .lineLimit(dts.isAccessibilitySize ? nil : 2)
                    .fixedSize(horizontal: false, vertical: true)
                if !dts.isAccessibilitySize { Spacer(minLength: 4) }
                Button {
                    if vm.state.actionError == nil { vm.load() }
                    else { vm.dismissActionError() }
                } label: {
                    (vm.state.actionError == nil ? Text("Retry") : Text("Dismiss"))
                        .gmFont(12, .semibold)
                        // Text on the amber chip: the text token, which lifts to
                        // the periwinkle in dark (cobalt text there is under 4.5:1).
                        .foregroundColor(GM.primaryText)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12).padding(.vertical, 2)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(GM.warnChipBg))
            .padding(.horizontal, 16).padding(.vertical, 6)
        }

        // The snooze undo — same place and same kind of statement as the stale-list
        // chip above: a fact about what the app just did, with the action that
        // reverses it. It names the mail it snoozed rather than offering a list of
        // postponed messages, because until `?box=snoozed` is deployed the app knows
        // only about these.
        if let note = vm.state.snoozeUndoNote, !vm.state.snoozeUndoIds.isEmpty {
            GMUndoBanner(note: String(localized: "\(note) · out of the inbox until then", comment: "Snooze undo banner; the first part is e.g. 'Snoozed 2 messages until Mon, 3 Mar · 9:00 AM'"),
                         marker: "mail-snooze-undo",
                         // The model takes back the "handled today" count
                         // for what the server actually restored.
                         onUndo: { vm.undoSnooze() },
                         onDone: { vm.dismissSnoozeUndo() })
        }

        // The filing undo: same kind of fact in the same place -- what the
        // app just did, above the list it affects, with the inverse PATCH as
        // the way back. It names the ids the server accepted, not "all your
        // mail", and it disappears the moment another filing supersedes it.
        if let note = vm.state.undoNote, !vm.state.undoIds.isEmpty {
            GMUndoBanner(note: note, marker: "mail-undo",
                         onUndo: { vm.undoFiling() },
                         onDone: { vm.dismissUndo() })
        }
    }

    /// Search, the sort/filter sheet's filter, or an advanced field: the list
    /// is showing a subset, so its empty state says "No matching messages".
    private var listFiltered: Bool {
        vm.state.query.trimmed.count >= 3 || vm.state.filter != .all ||
            !vm.state.advFrom.trimmed.isEmpty || !vm.state.advTo.trimmed.isEmpty ||
            !vm.state.advSubject.trimmed.isEmpty || vm.state.advFiles ||
            !vm.state.advAfter.trimmed.isEmpty || !vm.state.advBefore.trimmed.isEmpty
    }

    /// Inbox Zero is a fact only after a read answered: the primary Inbox
    /// with a page actually loaded (`pages > 0` -- a box switch, a new search
    /// and the very first launch all start at 0, so the first load never
    /// counts), no error, no stale refresh, not offline, and no search or
    /// filter narrowing it. A background refresh over that answered empty
    /// page (the filing reconcile, pull-to-refresh) keeps the moment on
    /// screen instead of flashing the skeleton and replaying its entrance.
    /// Anything else keeps MailStateBlock's own states.
    private var showsInboxZero: Bool {
        let s = vm.state
        return s.box == .inbox && s.rows.isEmpty && s.pages > 0
            && !s.loadingMore && !s.canLoadMore
            && s.fatal == nil && !s.noMailbox && s.staleError == nil
            && network.online != false
            && !listFiltered && categoryFilter == nil && insightFilter == nil
    }

    /// Recorded by the model when the server accepts (MailboxModel), read at
    /// render: every accepted filing also changes `vm.state`, which redraws.
    private var handledToday: Int {
        MailHandledToday.count(account: vm.state.accountAddress, now: insightClock)
    }

    private var listArea: some View {
        Group {
            if showsInboxZero {
                InboxZeroView(handledToday: handledToday, account: vm.state.accountAddress)
            } else {
                MailStateBlock(
                    loading: vm.state.loading,
                    failed: vm.state.fatal != nil,
                    noMailbox: vm.state.noMailbox,
                    empty: vm.state.rows.isEmpty && !vm.state.canLoadMore && !vm.state.loadingMore,
                    filtered: listFiltered,
                    boxTitle: vm.state.box.title,
                    onRetry: { vm.load() }
                ) {
                    messageList
                }
            }
        }
        .mailListSurface()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    @ViewBuilder
    private func rowView(for row: MailMessage, prefetchIDs: Set<String>, showDivider: Bool,
                         tipAnchor: Bool = false) -> some View {
        let box = vm.state.box
        let canSwipe = vm.state.selection.isEmpty
        // Settings → Swipe actions (SwipePreferences.swift); the defaults are
        // the original Archive-right / Trash-left pair.
        let right = canSwipe ? swipeAction(swipeRight, for: row, box: box) : nil
        let left = canSwipe ? swipeAction(swipeLeft, for: row, box: box) : nil
        // One door for the tap and the long-press menu's Open.
        let openRow: () -> Void = {
            if vm.state.box == .drafts { onDraft(row.id) }
            else { onThread(row.threadId ?? row.id, row.threadId != nil, row.id) }
        }
        // A fresh sign-in code (last 60 minutes, never sealed mail).
        let code = Self.codeBoxes.contains(box) ? MailboxInsights.freshCode(row, now: insightClock) : nil
        let copy: (() -> Void)? = code.map { value -> () -> Void in
            return { copyCode(value) }
        }
        MailSwipeRow(right: right, left: left) {
            MessageRow(
                message: row,
                selecting: !vm.state.selection.isEmpty,
                selected: vm.state.selection.contains(row.id),
                onSelect: { vm.toggleSelect(row.id) },
                onOpen: openRow,
                onStar: { vm.actOn(row.id, row.isStarred ? .unstar : .star) },
                // While nothing is picked the long press belongs to the
                // context menu below; while picking it keeps toggling picks.
                longPressSelects: !canSwipe,
                signInCode: code,
                onCopyCode: copy
            )
            .equatable()
        }
        // iOS 18+: the reader zooms out of this row (no-op before, or two-pane).
        .mailZoomSource(id: row.id, in: zoomNamespace)
        // Long-press preview and actions (MailRowPreview.swift). Detached while
        // picking rows, so it never fights multi-select; the swipe is a drag
        // and the menu a stationary press, so the two do not compete.
        .mailRowContextMenu(enabled: canSwipe) {
            MailRowMenuItems(
                message: row,
                box: box,
                // Any pick from the menu means the long press was found.
                onOpen: { FeatureTips.used(.longPress); openRow() },
                onReply: onReply.map { reply -> (String) -> Void in
                    return { id in FeatureTips.used(.longPress); reply(id) }
                },
                onSelect: { FeatureTips.used(.longPress); vm.toggleSelect(row.id) },
                onAction: { FeatureTips.used(.longPress); fileRow(row.id, $0) }
            )
        } preview: {
            MailRowPreview(message: row)
        }
        // The swipe and long-press tips point at the first row only; the
        // second rides a clear background so the two popovers never share
        // one view.
        .featureTip(.swipe, enabled: tipAnchor)
        .background { if tipAnchor { Color.clear.featureTip(.longPress) } }
        .overlay {
            if let openRowID, (row.threadId ?? row.id) == openRowID {
                MailOpenRowHighlight()
            }
        }
        .overlay(alignment: .bottom) {
            if showDivider {
                Rectangle()
                    .fill(GM.border)
                    .frame(height: 1 / displayScale)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
        .onAppear {
            if vm.state.rows.first?.id == row.id { isAtTop = true }
        }
        .task(id: prefetchIDs.contains(row.id) ? vm.state.pages : -1) {
            if prefetchIDs.contains(row.id) { await vm.loadMoreWhenReady() }
        }
        .onDisappear {
            if vm.state.rows.first?.id == row.id { isAtTop = false }
        }
    }

    // ── swipes ───────────────────────────────────────────────────────────────

    /// One side's action for one row, from the person's choice. nil disables
    /// that side (None, or a choice that means nothing in this box -- Archive
    /// in Archive, Trash in Trash and Spam, Snooze where mail cannot snooze).
    /// In Trash and Spam the Archive choice keeps its old meaning there:
    /// "Back to Inbox".
    private func swipeAction(_ choice: SwipeChoice, for row: MailMessage, box: MailBox) -> MailSwipeAction? {
        let recovering = box == .trash || box == .spam
        switch choice {
        case .off:
            return nil
        case .archive:
            guard box != .archive else { return nil }
            return MailSwipeAction(
                title: recovering ? String(localized: "Back to Inbox") : String(localized: "Archive", comment: "Swipe action"),
                restoresToInbox: recovering,
                kind: recovering ? .restore : .archive
            ) {
                FeatureTips.used(.swipe)
                fileRow(row.id, recovering ? .restore : .archive)
            }
        case .trash:
            guard !recovering else { return nil }
            return MailSwipeAction(title: String(localized: "Trash", comment: "Swipe action"), kind: .trash) {
                FeatureTips.used(.swipe)
                fileRow(row.id, .trash)
            }
        case .readToggle:
            let action: MailAction = row.isRead ? .unread : .read
            return MailSwipeAction(
                title: row.isRead ? String(localized: "Mark unread", comment: "Swipe action")
                                  : String(localized: "Mark read", comment: "Swipe action"),
                kind: row.isRead ? .unread : .read,
                removesRow: false
            ) {
                FeatureTips.used(.swipe)
                vm.actOn(row.id, action)
            }
        case .star:
            let action: MailAction = row.isStarred ? .unstar : .star
            return MailSwipeAction(
                title: row.isStarred ? String(localized: "Unstar", comment: "Swipe action")
                                     : String(localized: "Star", comment: "Swipe action"),
                kind: row.isStarred ? .unstar : .star,
                // Unstarring inside Starred takes the row out of the list.
                removesRow: box == .starred && row.isStarred
            ) {
                FeatureTips.used(.swipe)
                vm.actOn(row.id, action)
            }
        case .snooze:
            guard !recovering, box != .snoozed, box != .drafts, box != .sent else { return nil }
            return MailSwipeAction(title: String(localized: "Snooze", comment: "Swipe action"),
                                   kind: .snooze, removesRow: false) {
                FeatureTips.used(.swipe)
                FeatureTips.used(.snooze)
                openSwipeSnooze(row.id)
            }
        case .move:
            return MailSwipeAction(title: String(localized: "Move to…", comment: "Swipe action: opens the folder picker"),
                                   kind: .move, removesRow: false) {
                FeatureTips.used(.swipe)
                openRowSheet(row.id, .move)
            }
        }
    }

    /// The Snooze swipe: the SnoozeSheet's choice snoozes this row directly
    /// (`actOn(_:_:until:)`), so the selection is never touched.
    private func openSwipeSnooze(_ id: String) {
        guard vm.state.selection.isEmpty, !vm.state.busy else { return }
        swipeSnoozeRow = id
        sheet = .snooze
    }

    /// The existing Move sheet acts on the selection, so a swipe picks just
    /// its row and opens the sheet; closing the sheet without a choice puts
    /// the selection back the way it was.
    private func openRowSheet(_ id: String, _ which: MailSheet) {
        guard vm.state.selection.isEmpty, !vm.state.busy else { return }
        vm.toggleSelect(id)
        swipeSheetRow = id
        sheet = which
    }

    private func releaseSwipeSelection() {
        swipeSnoozeRow = nil
        guard let id = swipeSheetRow else { return }
        swipeSheetRow = nil
        if vm.state.busy {
            // A choice was made and is landing; release after it settles.
            releaseAfterBusy = id
        } else if vm.state.selection == [id] {
            vm.clearSelection()
        }
    }

    // ── filing ───────────────────────────────────────────────────────────────
    //
    // "You handled N emails today" is counted by the model where the server
    // accepted (MailboxModel `actOn` / `act` / `deletePermanently`, and back
    // out on an accepted Undo), so the reader's filings count too and nothing
    // here counts a second time.

    /// One row's action through the model.
    private func fileRow(_ id: String, _ action: MailAction) {
        vm.actOn(id, action)
    }

    /// The selection's action through the model.
    private func bulk(_ action: MailAction, until: String? = nil) {
        vm.act(action, until: until)
    }

    /// A Move to… swipe's row is released once its filing settles.
    private func settleBulkFiling() {
        if let id = releaseAfterBusy {
            releaseAfterBusy = nil
            if vm.state.selection == [id] { vm.clearSelection() }
        }
    }

    // ── code chips ───────────────────────────────────────────────────────────

    /// The chip's copy: the shared local-only, expiring pasteboard write (it
    /// plays the success haptic through Haptics.swift), a short "Code copied"
    /// that never repeats the code, and no navigation.
    private func copyCode(_ code: String) {
        SignInCodePasteboard.copy(code)
        GMAnnounce.say(String(localized: "Code copied", comment: "Confirmation after copying a sign-in code; never includes the code"))
        codeToastTask?.cancel()
        withAnimation(GMMotion.gated(GMMotion.emphasizedDecelerate(), reduced: reduceMotion)) {
            codeCopied = true
        }
        codeToastTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_600_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(GMMotion.gated(GMMotion.standard(), reduced: reduceMotion)) {
                codeCopied = false
            }
        }
    }

    // ── the Good morning card ────────────────────────────────────────────────

    /// The primary Inbox only: no other folder, no search or filter, no
    /// category chip, nothing picked, not dismissed in this period.
    private var briefEligible: Bool {
        vm.state.box == .inbox && vm.state.selection.isEmpty && !listFiltered && !advActive
            && categoryFilter == nil && !vm.state.rows.isEmpty
            && dismissedBriefPeriod != InboxDayPart.periodID(insightClock)
    }

    private func briefCard(_ ctx: MailboxInsights.Context) -> some View {
        InboxBriefCard(
            greeting: InboxDayPart.at(insightClock).greeting(MailboxInsights.firstName(vm.state.accountName)),
            parts: MailboxInsights.parts(vm.state.rows, ctx),
            active: insightFilter,
            account: vm.state.accountAddress,
            onPick: pickInsight,
            onDismiss: dismissBrief
        )
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }

    private func pickInsight(_ kind: InboxInsightKind) {
        Haptics.selection()
        withAnimation(GMMotion.gated(GMMotion.standard(), reduced: reduceMotion)) {
            insightFilter = insightFilter == kind ? nil : kind
        }
    }

    private func clearInsightFilter() {
        withAnimation(GMMotion.gated(GMMotion.standard(), reduced: reduceMotion)) {
            insightFilter = nil
        }
    }

    private func dismissBrief() {
        let now = Date()
        insightClock = now
        withAnimation(GMMotion.gated(GMMotion.standard(), reduced: reduceMotion)) {
            dismissedBriefPeriod = InboxDayPart.periodID(now)
        }
    }

    @ViewBuilder
    private var listFooter: some View {
        if vm.state.loadingMore {
            Text("Loading more…")
                .gmFont(13).foregroundColor(GM.textTertiary)
                .frame(maxWidth: .infinity, alignment: .center)
                .listRowSeparator(.hidden)
        } else if vm.state.canLoadMore {
            VStack(spacing: 4) {
                if let error = vm.state.appendError {
                    Text(error).gmFont(13).foregroundColor(GM.textSecondary)
                }
                Button(vm.state.appendError == nil ? "Load more" : "Retry") { vm.loadMore() }
            }
            .frame(maxWidth: .infinity)
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
        }
    }

    /// `List` is lazy, which a mailbox needs and the wizard screens did not.
    /// Prefetch near the end, with a footer for manual loading and retry.
    ///
    /// The rows sit inside ONE rounded card, the way the reference draws it —
    /// and that card is drawn by `listArea`, once, around this whole list.
    /// The list itself keeps a clear background and hides its separators, so
    /// the only corners on screen are the container's.
    private func shownRows(_ ctx: MailboxInsights.Context) -> [MailMessage] {
        guard vm.state.box == .inbox else { return vm.state.rows }
        var rows = vm.state.rows
        if let categoryFilter {
            let priority = MailFeaturePrefs.shared.prioritySenders
            rows = rows.filter { row in
                let decision = MailCategorizer.classify(
                    senderAddress: row.from?.address ?? "",
                    subject: row.subject ?? "",
                    hasListUnsubscribe: row.unsubscribe != nil,
                    knownSenders: priority)
                if categoryFilter == "priority" { return decision.priority }
                return decision.category.rawValue == categoryFilter
            }
        }
        // The Good morning card's filter: the same rules that counted them.
        if let insightFilter {
            rows = rows.filter { MailboxInsights.matches(insightFilter, $0, ctx) }
        }
        return rows
    }

    @ViewBuilder private var messageList: some View {
        // shownRows classifies every row (lowercased subject/body scans); the
        // old body evaluated it four times per render — once per call site.
        // One local, four consumers.
        let ctx = MailboxInsights.Context.current(now: insightClock, me: vm.state.accountAddress,
                                                  smartFeatures: smartFeatures)
        let rows = shownRows(ctx)
        let prefetchIDs = Set(rows.suffix(4).map(\.id))
        let lastID = rows.last?.id
        let firstID = rows.first?.id
        let showBrief = briefEligible
        let list = List {
            // Scrolls away with the rows rather than pinning above them.
            if showBrief {
                briefCard(ctx)
            }
            // Round 8 — security center (SafetyNudgeCard.swift): the §3.6
            // "Finish securing your account" banner, or the §4.3 safety nudge.
            SafetyMailboxSlot(isInbox: vm.state.box == .inbox, selecting: !vm.state.selection.isEmpty,
                              searching: listFiltered || advActive || categoryFilter != nil,
                              offline: network.online == false)
            // Either filter (the card's, or a category chip) can leave nothing:
            // say so instead of drawing an empty card.
            if (insightFilter != nil || categoryFilter != nil) && rows.isEmpty {
                Text("Nothing matches this filter right now.")
                    .gmFont(13).foregroundColor(GM.textTertiary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 24)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }
            ForEach(rows) { row in
                rowView(for: row, prefetchIDs: prefetchIDs, showDivider: row.id != lastID,
                        tipAnchor: row.id == firstID)
            }
            listFooter
            // Room for the bottom bar AND the compose button above it, so the
            // last message's star and time are never hidden under the button
            // when the list is scrolled to its end.
            Color.clear
                .frame(height: MailBottomControls.clearance + 72)
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .accessibilityHidden(true)
        }
        .listStyle(.plain)
        .id(vm.state.box)
        .scrollContentBackground(.hidden)
        .refreshable { await vm.loadAndSettle() }
        .accessibilityIdentifier("mail-list")

        if #available(iOS 18.0, *) {
            list
                .onScrollPhaseChange { _, phase in
                    isUserScrolling = phase == .interacting || phase == .decelerating
                }
                .onScrollGeometryChange(for: Int.self) { geometry in
                    let inset = geometry.contentInsets
                    let limit = max(0, geometry.contentSize.height + inset.top + inset.bottom - geometry.containerSize.height)
                    let offset = min(limit, max(0, geometry.contentOffset.y + inset.top))
                    return Int(offset / 8)
                } action: { previous, current in
                    if current == 0 {
                        if !controlsVisible {
                            withAnimation(.spring(response: 0.35, dampingFraction: 0.82)) {
                                controlsVisible = true
                            }
                        }
                    } else if isUserScrolling {
                        let shouldShow = current < previous
                        if shouldShow != controlsVisible {
                            withAnimation(.spring(response: 0.35, dampingFraction: 0.82)) {
                                controlsVisible = shouldShow
                            }
                        }
                    }
                }
        } else {
            list
                .simultaneousGesture(
                    DragGesture(minimumDistance: 8, coordinateSpace: .global)
                        .updating($scrollTranslation) { drag, translation, _ in
                            translation = drag.translation
                        }
                )
                .onChange(of: scrollTranslation, perform: updateScrollControls)
        }
    }

    private func updateScrollControls(_ translation: CGSize?) {
        guard let translation else {
            lastScrollTranslation = 0
            return
        }
        // The list also handles horizontal swipe actions on individual messages.
        guard abs(translation.height) > abs(translation.width) else { return }
        let distance = translation.height - lastScrollTranslation
        guard abs(distance) >= 8 else { return }
        lastScrollTranslation = translation.height
        let shouldShow = distance > 0
        if shouldShow != controlsVisible {
            withAnimation(.spring(response: 0.35, dampingFraction: 0.82)) {
                controlsVisible = shouldShow
            }
        }
    }

    @ViewBuilder private func sheetContent(_ which: MailSheet) -> some View {
        switch which {
        case .sortFilter:
            SortFilterSheet(sort: vm.state.sort, filter: vm.state.filter,
                            sortedCount: vm.sortScope, total: vm.state.total) { s, f in
                vm.setSortAndFilter(s, f); sheet = nil
            }
        case .advanced:
            AdvancedSearchSheet(state: vm.state) {
                from, to, subject, files, after, before in
                vm.setAdvanced(from: from, to: to, subject: subject, files: files,
                               after: after, before: before)
            } onClear: {
                vm.clearAdvanced()
            } onDone: {
                sheet = nil
            }
        case .move:
            MoveSheet { action in bulk(action); sheet = nil }
        case .label:
            AssignLabelSheet(
                labels: vm.state.labels,
                labelsRead: vm.state.labelsRead,
                marks: vm.state.labelMarks,
                read: vm.state.assignmentsRead,
                note: vm.state.labelNote,
                selectedCount: vm.state.selection.count,
                busy: vm.state.busy,
                onPick: { id in
                    // Not dismissed on pick, unlike the move and snooze sheets. Those
                    // change the list under the user, which is the feedback; a label
                    // changes nothing visible on this screen, so the sheet has to stay
                    // open and show its own result line.
                    vm.toggleLabel(id)
                }
            )
        case .snooze:
            SnoozeSheet { until in
                if let id = swipeSnoozeRow {
                    // A Snooze swipe: this row, at the chosen time, directly.
                    swipeSnoozeRow = nil
                    vm.actOn(id, .snooze, until: until)
                } else {
                    bulk(.snooze, until: until)
                }
                sheet = nil
            }
        case .sender:
            // Not dismissed on pick, unlike the others. A filing action shows
            // whether it worked by changing the list under you; a decision changes
            // nothing on this screen because it governs the NEXT delivery, so the
            // receipt line is the only feedback there is and the sheet has to stay
            // open long enough to be read.
            SenderDecisionSheet(
                senders: selectedSenders,
                busy: vm.screener.busy,
                result: decisionResult,
            ) { scope, decision in
                vm.decideSenders(selectedSenders, scope: scope, decision: decision) {
                    decisionResult = $0
                }
            }
        case .permanent:
            PermanentDeleteSheet(
                count: vm.state.selection.count,
                cancel: { sheet = nil },
                confirm: { vm.deletePermanently(); sheet = nil }
            )
        }
    }

    private func open(_ box: MailBox) { vm.open(box) }

    /// The senders named by the selected rows, deduplicated by the model so one
    /// person with five messages in the queue is decided once, not five times.
    private var selectedSenders: [String] {
        vm.state.rows.filter { vm.state.selection.contains($0.id) }
            .compactMap { $0.from?.address }
    }

    private func toggleDrawer() {
        // Reduce Motion: the drawer swaps in with its fade, no slide spring.
        withAnimation(GMMotion.gatedOrFade(.spring(response: 0.32, dampingFraction: 0.86), reduced: reduceMotion)) {
            drawerOpen.toggle()
        }
    }

    /// The bottom bar's show/hide while scrolling; no spring under Reduce Motion.
    private var controlsAnimation: Animation? {
        GMMotion.gated(.spring(response: 0.35, dampingFraction: 0.82), reduced: reduceMotion)
    }
}

/// Counts the app can honestly show: only the boxes `/grapmail/stats` answers.
/// Starred, Archive and All Mail used to get no badge because the server
/// reported no count for them -- it does now (`starred`, `archive`, `others`
/// and `snoozed` are counted by the same `boxCount` that builds the listing
/// beside each badge, `grapmail.ts:666-688`), so the drawer shows the server's
/// number and shows nothing at all on a deployment that has not answered yet.
/// A `0` where the server said nothing would claim an empty folder it never
/// said anything about.
func drawerCounts(_ s: MailboxModel.State) -> [MailBox: Int?] {
    [
        // The listing's own total, which only describes the box currently open.
        .inbox: s.box == .inbox ? s.total : nil,
        .sent: s.statsSent,
        .drafts: s.statsDrafts,
        .spam: s.statsSpam,
        .trash: s.statsTrash,
        .starred: s.statsStarred,
        .archive: s.statsArchive,
        .others: s.statsOthers,
        .snoozed: s.statsSnoozed,
        .screener: nil,
    ]
}

/// The drawer's folder list. `Snoozed` is inserted only when `/stats` answered
/// the `snoozed` key -- a capability read off the server's own response shape,
/// not a build flag: a deployment whose `BOXES` lacks `snoozed`
/// (`grapmail-box-terms.ts:66-68`) also fails to answer the count, and the row
/// must not exist for a listing that would refuse it. The same derivation
/// Android's `drawerBoxes` applies.
func drawerBoxes(_ s: MailboxModel.State) -> [MailBox] {
    if s.statsSnoozed != nil {
        return [.inbox, .starred, .sent, .drafts, .snoozed, .archive, .spam, .trash, .others]
    }
    return MailBox.drawerOrder
}

/// The undo banner -- one view for both undo offers (snooze and filing),
/// because they are the same kind of fact: what the app just did, above the
/// list it affects, with the action that reverses it. The live screen raises
/// it after a real filing; the snapshot host draws it with the words a real
/// filing would produce, because a read-only walk cannot archive anything and
/// a banner no photograph has ever taken is a banner whose layout nobody has
/// seen. Android's `UndoBanner` is the same component.
struct GMUndoBanner: View {
    let note: String
    let marker: String
    let onUndo: () -> Void
    let onDone: () -> Void

    @Environment(\.dynamicTypeSize) private var dts

    var body: some View {
        // At the accessibility text sizes the sentence takes the full width
        // and Undo / Done sit on their own line under it.
        GMAdaptiveStack(spacing: 8) {
            Text(note)
                .gmFont(12).foregroundColor(GM.textPrimary)
                .lineLimit(dts.isAccessibilitySize ? nil : 2)
                .fixedSize(horizontal: false, vertical: true)
            if !dts.isAccessibilitySize { Spacer(minLength: 4) }
            HStack(spacing: dts.isAccessibilitySize ? 20 : 8) {
                // 44 pt touch targets on the words themselves; the banner's own
                // vertical padding is trimmed to match, so it stays slim.
                Button(action: onUndo) {
                    Text("Undo")
                        .gmFont(12, .semibold)
                        .foregroundColor(GM.primaryText)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Button(action: onDone) {
                    Text("Done")
                        .gmFont(12)
                        .foregroundColor(GM.textTertiary)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12).padding(.vertical, 2)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
            .fill(GM.softFill))
        .padding(.horizontal, 16).padding(.vertical, 6)
        .accessibilityIdentifier(marker)
    }
}
