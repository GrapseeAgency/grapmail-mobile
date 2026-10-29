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
    /// its own id — the same fallback the Android screen uses.
    let onThread: (String) -> Void
    let onCompose: () -> Void
    let onLabels: () -> Void
    let onScreener: () -> Void
    let onSettings: () -> Void
    let onSecurity: () -> Void
    let onHelp: () -> Void
    let onContacts: () -> Void
    let onFiles: () -> Void
    let onSignOut: () -> Void

    @State private var drawerOpen = false
    @State private var sheet: MailSheet?

    enum MailSheet: String, Identifiable {
        case sortFilter, move, label, snooze, permanent
        var id: String { rawValue }
    }

    var body: some View {
        ZStack(alignment: .leading) {
            content
            if drawerOpen {
                MailDrawer(
                    box: vm.state.box,
                    counts: drawerCounts(vm.state),
                    onSelect: { open($0); drawerOpen = false },
                    onDismiss: { toggleDrawer() },
                    onLabels: { drawerOpen = false; onLabels() },
                    onScreener: { drawerOpen = false; onScreener() },
                    onSettings: { drawerOpen = false; onSettings() },
                    onSecurity: { drawerOpen = false; onSecurity() },
                    onHelp: { drawerOpen = false; onHelp() },
                    onSignOut: { drawerOpen = false; onSignOut() }
                )
                .transition(.move(edge: .leading))
                .zIndex(2)
            }
        }
        .ignoresSafeArea(edges: .bottom)
        .sheet(item: $sheet) { which in sheetContent(which) }
        .task {
            vm.open(vm.state.box)
            vm.loadAccount()
        }
        .accessibilityIdentifier("mail-home")
    }

    // ── the column ───────────────────────────────────────────────────────────

    private var content: some View {
        ZStack(alignment: .bottomTrailing) {
            GM.bg
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
                onLabel: { sheet = .label },
                onMore: { sheet = .snooze }
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
                    LabelStrip(labels: vm.state.labels, unread: vm.state.unreadCount)
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
            TextField("Search emails, people, or files…",
                      text: Binding(
                        get: { vm.state.query },
                        set: { vm.query($0) }))
                .font(.system(size: 14))
                .foregroundColor(GM.textPrimary)
                .accessibilityIdentifier("mail-search")
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
                    onOpen: { onThread(row.threadId ?? row.id) },
                    onStar: { vm.actOn(row.id, row.isStarred ? .unstar : .star) }
                )
                .listRowInsets(EdgeInsets())
                .listRowSeparator(.hidden)
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
        .refreshable { vm.load() }
        .accessibilityIdentifier("mail-list")
    }

    @ViewBuilder private func sheetContent(_ which: MailSheet) -> some View {
        switch which {
        case .sortFilter:
            SortFilterSheet(sort: vm.state.sort, filter: vm.state.filter,
                            sortedCount: vm.sortScope, total: vm.state.total) { s, f in
                vm.setSort(s); vm.setFilter(f); sheet = nil
            }
        case .move:
            MoveSheet { action in vm.act(action); sheet = nil }
        case .label:
            AssignLabelSheet(labels: vm.state.labels) { id in
                vm.assignLabel(id); sheet = nil
            }
        case .snooze:
            SnoozeSheet { until in vm.act(.snooze, until: until); sheet = nil }
        case .permanent:
            PermanentDeleteSheet(
                count: vm.state.selection.count,
                cancel: { sheet = nil },
                confirm: { vm.deletePermanently(); sheet = nil }
            )
        }
    }

    private func open(_ box: MailBox) { vm.open(box) }

    private func toggleDrawer() {
        withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
            drawerOpen.toggle()
        }
    }
}

/// Counts the app can honestly show: only the boxes `/grapmail/stats` answers.
/// Starred, Archive and All Mail get no badge because the server reports no
/// count for them, and a `0` there would claim an empty folder it never said
/// anything about.
func drawerCounts(_ s: MailboxModel.State) -> [MailBox: Int?] {
    [
        // The listing's own total, which only describes the box currently open.
        .inbox: s.box == .inbox ? s.total : nil,
        .sent: s.statsSent,
        .drafts: s.statsDrafts,
        .spam: s.statsSpam,
        .trash: s.statsTrash,
        .starred: nil,
        .archive: nil,
        .others: nil,
        .screener: nil,
    ]
}
