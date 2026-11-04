import SwiftUI

/// The mailbox home: greeting, search, label strip, box tabs, list, selection,
/// drawer, FAB and the four sheets.
///
/// The reference draws Gmail's category tabs (`Primary / Social / Promotions /
/// Updates`) and filter chips (`All / Personal / Work / +2`). No `category`
/// column exists on the messages table and `/inbox` accepts no such filter, so
/// inventing counts for them would put numbers on screen that no system
/// produced. What ships instead is the real box row and the real labels from
/// `GET /grapmail/labels`. The bottom bar carries no Calendar tab for the same
/// reason. Each omission is recorded with its proof in the README.
struct MailboxView: View {
    /// Owned by `RootView`, not by this screen: the thread, compose and the
    /// secondary screens are separate destinations on the stack and must show
    /// the same mailbox the list is showing. A model per screen would mean a
    /// list that forgets what a thread just read.
    @ObservedObject var vm: MailboxModel
    /// The thread route carries either a thread id or, when a message has none,
    /// its own id — and says which it is. Asking the thread route for a message
    /// id answers 404, so a row without a thread must open through the message
    /// route or the tap does nothing useful.
    let onThread: (String, Bool) -> Void
    let onCompose: () -> Void
    let onLabels: () -> Void
    let onScreener: () -> Void
    let onSubscriptions: () -> Void
    let onSettings: () -> Void
    let onSecurity: () -> Void
    let onHelp: () -> Void
    let onContacts: () -> Void
    let onFiles: () -> Void
    let onSignOut: () -> Void

    @State private var drawerOpen = false
    @State private var sheet: MailSheet?
    @State private var decisionResult: String?

    enum MailSheet: String, Identifiable {
        case sortFilter, move, label, snooze, permanent, sender, advanced
        var id: String { rawValue }
    }

    var body: some View {
        ZStack(alignment: .leading) {
            content
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
                    onSettings: { drawerOpen = false; onSettings() },
                    onSecurity: { drawerOpen = false; onSecurity() },
                    onHelp: { drawerOpen = false; onHelp() },
                    onSignOut: { drawerOpen = false; onSignOut() }
                )
                .transition(.move(edge: .leading))
                .zIndex(2)
            }
        }
        // No ignoresSafeArea on the screen. It was applied to the whole ZStack,
        // which pushed the tab bar and the drawer's pinned footer under the home
        // indicator: the rows rendered, reported a frame, and could not be hit —
        // and a bottom bar drawn under the indicator is a defect for a person,
        // not only for the test. The background may still bleed past it.
        .sheet(item: $sheet) { which in sheetContent(which) }
        .task {
            // Ask for the box only when it has never been asked for, and read
            // `/me` once. Returning from a thread re-enters this task.
            vm.ensureLoaded()
            vm.loadAccount()
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
        ZStack(alignment: .bottomTrailing) {
            GM.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                header
                staleBanner
                listArea
                MailBottomBar(
                    unread: vm.state.unreadCount,
                    onContacts: onContacts,
                    onFiles: onFiles,
                    onMore: { toggleDrawer() }
                )
            }
            MailFab(onClick: onCompose)
                .padding(.trailing, 20)
                .padding(.bottom, 96)
                .accessibilityIdentifier("mail-compose")
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
                onArchive: { vm.act(.archive) },
                // "Delete" trashes. Permanent destruction is the second,
                // explicit step behind the confirmation sheet, because a bulk
                // toolbar must never be able to destroy mail.
                onDelete: { sheet = .permanent },
                onMove: { sheet = .move },
                onLabel: { vm.ensureAssignments(); sheet = .label },
                onMarkRead: { vm.act(.read) },
                onMarkUnread: { vm.act(.unread) },
                onMore: { sheet = .snooze },
                // Only the queue offers this. Deciding a sender is a rule about
                // their next message, which means nothing for a box whose mail
                // already arrived. Last because Swift orders arguments the way the
                // struct declares them, and `onSender` is the defaulted one.
                onSender: vm.state.box == .screener ? { sheet = .sender } : nil
            )
        } else {
            VStack(spacing: 0) {
                HStack(spacing: 10) {
                    Button { toggleDrawer() } label: {
                        Image(systemName: "line.3.horizontal")
                            .font(.system(size: 19))
                            .foregroundColor(GM.textPrimary)
                            .frame(width: 32, height: 32)
                    }
                    .accessibilityLabel("Open folders")
                    .accessibilityIdentifier("mail-menu")
                    Wordmark()
                    Spacer()
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)

                VStack(spacing: 12) {
                    MailGreeting(name: vm.state.accountName, address: vm.state.accountAddress)
                    searchField
                    LabelStrip(labels: vm.state.labels, unread: vm.state.unreadCount,
                               query: vm.state.query, filter: vm.state.filter)
                }
                .padding(.horizontal, 16)

                BoxTabRow(state: vm.state, onSelect: { open($0) })
            }
        }
    }

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15)).foregroundColor(GM.textTertiary)
            // The wording names what the server's term actually covers --
            // subject, body, sender (`grapmail-search.ts:110-118`) -- because
            // a placeholder promising "people or files" would advertise a
            // search the route does not run. Android's field says the same.
            TextField("Search subject, body or sender…",
                      text: Binding(
                        get: { vm.state.query },
                        set: { vm.query($0) }))
                .font(.system(size: 14))
                .foregroundColor(GM.textPrimary)
                .accessibilityIdentifier("mail-search")
            Button { sheet = .advanced } label: {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 15))
                    .foregroundColor(advActive ? GM.primarySolid : GM.textSecondary)
            }
            .accessibilityLabel("Search filters")
            .accessibilityIdentifier("mail-adv-filter")
            Button { sheet = .sortFilter } label: {
                Image(systemName: "tag")
                    .font(.system(size: 15))
                    .foregroundColor(filterActive ? GM.primarySolid : GM.textSecondary)
            }
            .accessibilityLabel("Sort and filter")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .background(GM.card)
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
            .stroke(GM.border, lineWidth: 1))
    }

    private var advActive: Bool {
        !vm.state.advFrom.isEmpty || !vm.state.advTo.isEmpty ||
        !vm.state.advSubject.isEmpty || vm.state.advFiles ||
        !vm.state.advAfter.isEmpty || !vm.state.advBefore.isEmpty
    }

    private var filterActive: Bool {
        vm.state.filter != .unread || vm.state.sort != .newest
    }

    /// A refresh that failed over a populated list says so here instead of
    /// replacing the list with an error screen — the hub's defect, designed out.
    @ViewBuilder private var staleBanner: some View {
        if let msg = vm.state.staleError, !vm.state.rows.isEmpty {
            HStack(spacing: 8) {
                Text(msg)
                    .font(.system(size: 12)).foregroundColor(GM.warnInk)
                    .lineLimit(2)
                Spacer(minLength: 4)
                Button("Retry") { vm.load() }
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(GM.primarySolid)
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
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
            GMUndoBanner(note: note + " · out of the inbox until then",
                         marker: "mail-snooze-undo",
                         onUndo: { vm.undoSnooze() }, onDone: { vm.dismissSnoozeUndo() })
        }

        // The filing undo: same kind of fact in the same place -- what the
        // app just did, above the list it affects, with the inverse PATCH as
        // the way back. It names the ids the server accepted, not "all your
        // mail", and it disappears the moment another filing supersedes it.
        if let note = vm.state.undoNote, !vm.state.undoIds.isEmpty {
            GMUndoBanner(note: note, marker: "mail-undo",
                         onUndo: { vm.undoFiling() }, onDone: { vm.dismissUndo() })
        }
    }

    private var listArea: some View {
        MailStateBlock(
            loading: vm.state.loading,
            failed: vm.state.fatal != nil,
            noMailbox: vm.state.noMailbox,
            empty: vm.state.rows.isEmpty,
            boxTitle: vm.state.box.title,
            onRetry: { vm.load() }
        ) {
            messageList
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// `List` is lazy, which a mailbox needs and the wizard screens did not.
    /// Pagination is driven by the last visible row rather than a "load more"
    /// button, and the sentinel only asks when the server says more exists.
    private var messageList: some View {
        List {
            ForEach(vm.state.rows) { row in
                MessageRow(
                    message: row,
                    selecting: !vm.state.selection.isEmpty,
                    selected: vm.state.selection.contains(row.id),
                    onSelect: { vm.toggleSelect(row.id) },
                    onOpen: { onThread(row.threadId ?? row.id, row.threadId != nil) },
                    onStar: { vm.actOn(row.id, row.isStarred ? .unstar : .star) }
                )
                .listRowInsets(EdgeInsets())
                .listRowSeparator(.hidden)
                // Swipe files, and only in the directions that mean something
                // where the message already is. Same two directions and same two
                // colours as Android's `SwipeableMessageRow`: leading is archive
                // (or back out of Trash/Spam), trailing is trash, and neither
                // destroys — permanent deletion stays behind its confirmation
                // sheet, so no gesture here can erase mail.
                //
                // `allowsFullSwipe` is the one mechanic Android cannot match:
                // Material3 fires on crossing the halfway threshold, with no
                // "stay open until tapped" mode. So a fling toward Trash is
                // refused here and accepted there — which is why neither
                // direction is allowed to be irreversible.
                .swipeActions(edge: .leading, allowsFullSwipe: true) {
                    if vm.state.box == .trash || vm.state.box == .spam {
                        Button("Back to Inbox") { vm.actOn(row.id, .restore) }
                            .tint(GM.swipeArchive)
                    } else if vm.state.box != .archive {
                        Button("Archive") { vm.actOn(row.id, .archive) }
                            .tint(GM.swipeArchive)
                    }
                }
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    if vm.state.box != .trash && vm.state.box != .spam {
                        // Not `.destructive` role: that draws the system red and
                        // implies irreversibility, and trashing undoes.
                        Button("Trash") { vm.actOn(row.id, .trash) }
                            .tint(GM.swipeTrash)
                    }
                }
                .onAppear {
                    if row.id == vm.state.rows.last?.id { vm.loadMore() }
                }
            }
            if vm.state.loadingMore {
                Text("Loading more…")
                    .font(.system(size: 13)).foregroundColor(GM.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .refreshable { await vm.loadAndSettle() }
        .accessibilityIdentifier("mail-list")
    }

    @ViewBuilder private func sheetContent(_ which: MailSheet) -> some View {
        switch which {
        case .sortFilter:
            SortFilterSheet(sort: vm.state.sort, filter: vm.state.filter,
                            sortedCount: vm.sortScope, total: vm.state.total) { s, f in
                vm.setSort(s); vm.setFilter(f); sheet = nil
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
            MoveSheet { action in vm.act(action); sheet = nil }
        case .label:
            AssignLabelSheet(
                labels: vm.state.labels,
                labelsRead: vm.state.labelsRead,
                marks: vm.state.labelMarks,
                read: vm.state.assignmentsRead,
                note: vm.state.labelNote,
                selectedCount: vm.state.selection.count,
            ) { id in
                // Not dismissed on pick, unlike the move and snooze sheets. Those
                // change the list under the user, which is the feedback; a label
                // changes nothing visible on this screen, so the sheet has to stay
                // open and show its own result line.
                vm.toggleLabel(id)
            }
        case .snooze:
            SnoozeSheet { until in vm.act(.snooze, until: until); sheet = nil }
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
        withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
            drawerOpen.toggle()
        }
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

    var body: some View {
        HStack(spacing: 8) {
            Text(note)
                .font(.system(size: 12)).foregroundColor(GM.textPrimary)
                .lineLimit(2)
            Spacer(minLength: 4)
            Button("Undo", action: onUndo)
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(GM.primarySolid)
            Button("Done", action: onDone)
                .font(.system(size: 12))
                .foregroundColor(GM.textTertiary)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
            .fill(GM.softFill))
        .padding(.horizontal, 16).padding(.vertical, 6)
        .accessibilityIdentifier(marker)
    }
}
