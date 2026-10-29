package com.grapsee.grapmail.ui.screens.mail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInbox
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.MailAction
import com.grapsee.grapmail.data.MailBox
import com.grapsee.grapmail.data.MailFilter
import com.grapsee.grapmail.data.MailSort
import com.grapsee.grapmail.data.isServerSide
import com.grapsee.grapmail.data.title
import com.grapsee.grapmail.logic.MailboxViewModel
import com.grapsee.grapmail.ui.components.BoxTab
import com.grapsee.grapmail.ui.components.CountBadge
import com.grapsee.grapmail.ui.components.GrapMailLogo
import com.grapsee.grapmail.ui.components.LabelChip
import com.grapsee.grapmail.ui.components.MailFab
import com.grapsee.grapmail.ui.components.MailGreeting
import com.grapsee.grapmail.ui.components.MailStateBlock
import com.grapsee.grapmail.ui.components.MessageRow
import com.grapsee.grapmail.ui.components.SenderAvatar
import com.grapsee.grapmail.ui.components.SelectionToolbar
import com.grapsee.grapmail.ui.components.UnreadPill
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * The mailbox home — reference screens 60/61 (light and dark are the same
 * screen under a different palette), with the drawer (62), selection (68) and
 * sort/filter (69) built into it.
 *
 * ── WHAT THIS SCREEN DOES NOT DRAW ──────────────────────────────────────────
 * The reference's `Primary / Social / Promotions / Updates` tabs and the
 * `All / Personal / Work / +2` chips have no backing anywhere in the API: there
 * is no `category` column on `grapmail_messages`, and the website's own tab list
 * is the box list (`page.tsx:199-208`). Inventing counts for them would put
 * numbers on screen that no system produced, so the tab row is the real boxes and
 * the chip row is the real labels from `/labels`. The bottom bar has no Calendar
 * tab for the same reason — there is no calendar backend. Each omission is
 * recorded with its proof in the README's honest-UI contract.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MailboxScreen(
    vm: MailboxViewModel,
    onOpenThread: (id: String, isThread: Boolean) -> Unit,
    onCompose: () -> Unit,
    onSettings: () -> Unit,
    onSecurity: () -> Unit,
    onHelp: () -> Unit,
    onLabels: () -> Unit,
    onContacts: () -> Unit,
    onFiles: () -> Unit,
    onScreener: () -> Unit,
    onSignOut: () -> Unit,
) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showSortSheet by remember { mutableStateOf(false) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showLabelSheet by remember { mutableStateOf(false) }
    var showSnoozeSheet by remember { mutableStateOf(false) }
    var showPermanentConfirm by remember { mutableStateOf(false) }

    // Ask for more once the list is nearly at the end. Not "on the last item",
    // which fires one screen late and makes the wait visible.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd && state.canLoadMore) vm.loadMore() }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            MailDrawer(
                box = state.box,
                unread = state.unreadCount,
                counts = drawerCounts(state),
                onBox = { scope.launch { drawer.close() }; vm.open(it) },
                onLabels = { scope.launch { drawer.close() }; onLabels() },
                onScreener = { scope.launch { drawer.close() }; onScreener() },
                onSettings = { scope.launch { drawer.close() }; onSettings() },
                onSecurity = { scope.launch { drawer.close() }; onSecurity() },
                onHelp = { scope.launch { drawer.close() }; onHelp() },
                onSignOut = { scope.launch { drawer.close() }; onSignOut() },
            )
        },
    ) {
        Box(Modifier.fillMaxSize().background(palette.bg)) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {

                if (state.selecting) {
                    SelectionToolbar(
                        count = state.selection.size,
                        onClose = vm::clearSelection,
                        onArchive = { vm.act(com.grapsee.grapmail.data.MailAction.Archive) },
                        // "Delete" trashes. Permanent destruction is the second,
                        // explicit step behind the confirmation sheet, because a
                        // bulk toolbar must never be able to destroy mail.
                        onDelete = { showPermanentConfirm = true },
                        onMove = { showMoveSheet = true },
                        onLabel = { showLabelSheet = true },
                        onMore = { showSnoozeSheet = true },
                    )
                } else {
                    MailTopBar(
                        onMenu = { scope.launch { drawer.open() } },
                        onSearch = { /* search field below owns the query */ },
                    )
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        MailGreeting(state.accountName, state.accountAddress)
                        Spacer(Modifier.height(14.dp))
                        SearchField(
                            query = state.query,
                            onQuery = vm::query,
                            onFilter = { showSortSheet = true },
                            filterActive = state.filter != MailFilter.All || state.sort != MailSort.Newest,
                        )
                        Spacer(Modifier.height(12.dp))
                        LabelStrip(state)
                        Spacer(Modifier.height(6.dp))
                    }
                    BoxTabRow(state)
                }

                // A refresh that failed over a populated list says so here rather
                // than replacing the list with an error screen.
                if (state.staleError != null && state.hasRows) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(10.dp)).background(palette.warnChipBg)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(state.staleError!!, fontSize = 12.sp, color = palette.warnInk,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text("Retry", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = palette.primarySolid, modifier = Modifier.clickable { vm.load() })
                    }
                }

                Box(Modifier.weight(1f)) {
                    MailStateBlock(
                        loading = state.loading,
                        failed = state.fatal != null,
                        noMailbox = state.noMailbox,
                        empty = !state.hasRows,
                        boxTitle = state.box.title,
                        onRetry = { vm.load() },
                    ) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("mail-list")) {
                            items(state.rows, key = { it.id }) { row ->
                                MessageRow(
                                    message = row,
                                    selected = state.selection.contains(row.id),
                                    onSelect = { vm.toggleSelect(row.id) },
                                    onOpen = {
                                    val tid = row.threadId
                                    onOpenThread(tid ?: row.id, tid != nil)
                                },
                                    onStar = {
                                        vm.actOn(row.id,
                                            if (row.isStarred) com.grapsee.grapmail.data.MailAction.Unstar
                                            else com.grapsee.grapmail.data.MailAction.Star)
                                    },
                                    selecting = state.selecting,
                                )
                                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    .height(1.dp).background(palette.border))
                            }
                            if (state.loadingMore) {
                                item {
                                    Box(Modifier.fillMaxWidth().padding(20.dp),
                                        contentAlignment = Alignment.Center) {
                                        Text("Loading more…", fontSize = 13.sp, color = palette.textTertiary)
                                    }
                                }
                            }
                        }
                    }
                }

                MailBottomBar(selected = 0, unread = state.unreadCount,
                    onMail = { }, onContacts = onContacts, onFiles = onFiles,
                    onMore = { scope.launch { drawer.open() } })
            }

            // The FAB sits above the list but inside the content, so it survives
            // the drawer opening and closing.
            Box(Modifier.fillMaxSize().padding(end = 20.dp, bottom = 96.dp),
                contentAlignment = Alignment.BottomEnd) {
                MailFab(onCompose, modifier = Modifier.testTag("mail-compose"))
            }
        }
    }

    if (showSortSheet) {
        MailSheet(onDismiss = { showSortSheet = false }) {
            SortFilterContent(state.sort, state.filter, state.sortScope, state.total) { sort, filter ->
                vm.setSort(sort); vm.setFilter(filter); showSortSheet = false
            }
        }
    }
    if (showMoveSheet) {
        MailSheet(onDismiss = { showMoveSheet = false }) {
            MoveContent { action -> vm.act(action); showMoveSheet = false }
        }
    }
    if (showLabelSheet) {
        MailSheet(onDismiss = { showLabelSheet = false }) {
            AssignLabelContent(state.labels) { id -> vm.assignLabel(id); showLabelSheet = false }
        }
    }
    if (showSnoozeSheet) {
        MailSheet(onDismiss = { showSnoozeSheet = false }) {
            SnoozeContent { until ->
                vm.act(com.grapsee.grapmail.data.MailAction.Snooze, until)
                showSnoozeSheet = false
            }
        }
    }
    if (showPermanentConfirm) {
        MailSheet(onDismiss = { showPermanentConfirm = false }) {
            PermanentDeleteContent(
                count = state.selection.size,
                cancel = { showPermanentConfirm = false },
                confirm = { vm.deletePermanently(); showPermanentConfirm = false },
            )
        }
    }
}

/** Counts the drawer can honestly show: only the five boxes `/stats` answers.
 *  `internal`, not `private`: the snapshot host draws the drawer, and a second
 *  copy of this mapping is how a badge would drift from the list's own. */
internal fun drawerCounts(s: MailboxViewModel.MailboxUiState): Map<MailBox, Int?> = mapOf(
    MailBox.Inbox to s.total.takeIf { s.box == MailBox.Inbox },
    MailBox.Sent to s.statsSent,
    MailBox.Drafts to s.statsDrafts,
    MailBox.Spam to s.statsSpam,
    MailBox.Trash to s.statsTrash,
    // Unanswered by the server, so no badge — see CountBadge.
    MailBox.Starred to null,
    MailBox.Archive to null,
    MailBox.All to null,
    MailBox.Screener to null,
)

// ── chrome ───────────────────────────────────────────────────────────────────

@Composable
private fun MailTopBar(onMenu: () -> Unit, onSearch: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Menu, "Open folders", tint = palette.textPrimary,
            modifier = Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onMenu).padding(2.dp))
        Spacer(Modifier.width(10.dp))
        GrapMailLogo(size = 30.dp, wordmark = true)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.Search, "Search", tint = palette.textPrimary,
            modifier = Modifier.size(22.dp).clip(CircleShape).clickable(onClick = onSearch))
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onFilter: () -> Unit, filterActive: Boolean) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.card)
            .border(1.dp, palette.border, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = palette.textTertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        // A real value field: the reference's placeholder is not a hint that the
        // box is empty, it is what an unfocused search looks like.
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onQuery,
            maxLines = 1,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = palette.textPrimary),
            modifier = Modifier.weight(1f).testTag("mail-search"),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text("Search emails, people, or files…", fontSize = 14.sp, color = palette.textTertiary)
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Rounded.Label, "Sort and filter",
            tint = if (filterActive) palette.primarySolid else palette.textSecondary,
            modifier = Modifier.size(18.dp).clip(CircleShape).clickable(onClick = onFilter),
        )
    }
}

@Composable
private fun LabelStrip(s: MailboxViewModel.MailboxUiState) {
    if (s.labels.isEmpty()) return
    val palette = LocalPalette.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        item {
            LabelChip("Inbox", s.unreadCount, null, s.query.isEmpty() && s.filter == MailFilter.All,
                onClick = { })
        }
        items(s.labels) { l ->
            LabelChip(l.name, l.unread, l.color, false, onClick = { })
        }
    }
}

@Composable
private fun BoxTabRow(s: MailboxViewModel.MailboxUiState) {
    val counts = drawerCounts(s)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        MailBox.tabOrder.forEach { box ->
            BoxTab(box.title, counts[box], box == s.box, onClick = { })
        }
    }
}

/**
 * The bottom bar. Four tabs, not five: the reference draws a Calendar tab and
 * there is no calendar backend anywhere in the API, so drawing it would be a
 * control that goes nowhere — the one thing this sector was told not to ship.
 */
@Composable
private fun MailBottomBar(
    selected: Int,
    unread: Int?,
    onMail: () -> Unit,
    onContacts: () -> Unit,
    onFiles: () -> Unit,
    onMore: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(palette.card).border(1.dp, palette.border)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        TabItem(Icons.Rounded.Inbox, "Mail", selected == 0, unread) { onMail() }
        TabItem(Icons.Rounded.Contacts, "Contacts", selected == 1) { onContacts() }
        TabItem(Icons.Rounded.Description, "Files", selected == 2) { onFiles() }
        TabItem(Icons.Rounded.MoreHoriz, "More", selected == 3) { onMore() }
    }
}

@Composable
private fun TabItem(icon: ImageVector, label: String, active: Boolean, badge: Int? = null, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(icon, null, tint = if (active) palette.primarySolid else palette.textTertiary,
                modifier = Modifier.size(22.dp))
            if (badge != null && badge > 0) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(palette.error))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = if (active) palette.primarySolid else palette.textTertiary)
    }
}

// ── drawer ───────────────────────────────────────────────────────────────────

/**
 * The folder drawer. Reference rows it can honestly answer for are kept; the
 * three it cannot (Important, Snoozed as a box, Calendar) are absent, and the
 * reason is in the README with the file:line that proves it.
 */
@Composable
internal fun MailDrawer(
    box: MailBox,
    unread: Int?,
    counts: Map<MailBox, Int?>,
    onBox: (MailBox) -> Unit,
    onLabels: () -> Unit,
    onScreener: () -> Unit,
    onSettings: () -> Unit,
    onSecurity: () -> Unit,
    onHelp: () -> Unit,
    onSignOut: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        Modifier.width(300.dp).fillMaxSize().background(palette.card)
            .padding(top = 28.dp, bottom = 16.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            GrapMailLogo(size = 34.dp, wordmark = true)
        }
        Spacer(Modifier.height(20.dp))

        MailBox.drawerOrder.forEach { b ->
            DrawerRow(b.title, iconFor(b), counts[b], selected = b == box) { onBox(b) }
        }

        Spacer(Modifier.height(14.dp))
        DrawerSection("Queue")
        DrawerRow("Pending approval", Icons.Rounded.Lock, null, selected = false) { onScreener() }

        Spacer(Modifier.height(14.dp))
        DrawerSection("Labels")
        DrawerRow("Manage labels", Icons.Rounded.Label, null, selected = false) { onLabels() }

        Spacer(Modifier.weight(1f))
        DrawerRow("Settings", Icons.Rounded.Settings, null, selected = false) { onSettings() }
        DrawerRow("Security", Icons.Rounded.Star, null, selected = false) { onSecurity() }
        DrawerRow("Help & Support", Icons.Rounded.HelpOutline, null, selected = false) { onHelp() }
        Spacer(Modifier.height(10.dp))
        Text("Sign out", fontSize = 14.sp, color = palette.error,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 20.dp).clickable(onClick = onSignOut))
    }
}

@Composable
private fun DrawerSection(title: String) {
    val palette = LocalPalette.current
    Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        color = palette.textTertiary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
}

@Composable
private fun DrawerRow(label: String, icon: ImageVector, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) palette.softFill else palette.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) palette.primarySolid else palette.textSecondary,
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) palette.primarySolid else palette.textPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        CountBadge(count)
    }
}

private fun iconFor(b: MailBox): ImageVector = when (b) {
    MailBox.Inbox -> Icons.Rounded.Inbox
    MailBox.Starred -> Icons.Rounded.Star
    MailBox.Sent -> Icons.Rounded.Send
    MailBox.Drafts -> Icons.Rounded.Description
    MailBox.Archive -> Icons.Rounded.Folder
    MailBox.Spam -> Icons.Rounded.Report
    MailBox.Trash -> Icons.Rounded.Delete
    MailBox.Screener -> Icons.Rounded.Lock
    MailBox.All -> Icons.Rounded.AllInbox
    MailBox.None -> Icons.Rounded.Inbox
}

// ── sheets ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MailSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) { content() }
    }
}

@Composable
private fun SheetTitle(text: String) {
    val palette = LocalPalette.current
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}

/**
 * Sort and filter. The footer states what the sort actually covered: the server
 * orders by UID descending and nothing else, so every other option reordered the
 * loaded pages, not the box. Saying so is the difference between a filter and a
 * claim.
 */
@Composable
internal fun SortFilterContent(
    sort: MailSort,
    filter: MailFilter,
    sortedCount: Int,
    total: Int,
    onPick: (MailSort, MailFilter) -> Unit,
) {
    val palette = LocalPalette.current
    SheetTitle("Sort & filter")
    Text("Sort by", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textTertiary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    MailSort.entries.forEach { s ->
        SheetOption(s.title, s == sort) { onPick(s, filter) }
    }
    Text("Filter by", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.textTertiary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    MailFilter.entries.forEach { f ->
        SheetOption(f.title, f == filter) { onPick(sort, f) }
    }
    if (!sort.isServerSide && sortedCount < total) {
        Text(
            "Sorted across the $sortedCount messages loaded so far, not all $total in this box.",
            fontSize = 12.sp, color = palette.warnInk,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

/**
 * The filing targets that actually exist. There is no arbitrary "move to
 * folder" endpoint: `FILING_ACTIONS` is archive/unarchive/spam/unspam
 * (`grapmail-archive.ts:146`) plus trash/restore. The sheet offers those and
 * nothing else, so no option can silently do something other than its label.
 */
@Composable
internal fun MoveContent(onPick: (com.grapsee.grapmail.data.MailAction) -> Unit) {
    SheetTitle("Move to")
    val palette = LocalPalette.current
    SheetOption("Archive", false) { onPick(com.grapsee.grapmail.data.MailAction.Archive) }
    SheetOption("Trash", false) { onPick(com.grapsee.grapmail.data.MailAction.Trash) }
    SheetOption("Spam", false) { onPick(com.grapsee.grapmail.data.MailAction.Spam) }
    SheetOption("Back to Inbox", false) { onPick(com.grapsee.grapmail.data.MailAction.Restore) }
    Text(
        "Moving out of Spam or Trash returns the message to the inbox.",
        fontSize = 12.sp, color = palette.textTertiary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
internal fun AssignLabelContent(
    labels: List<com.grapsee.grapmail.data.MailLabelDto>,
    onPick: (String) -> Unit,
) {
    val palette = LocalPalette.current
    SheetTitle("Label")
    if (labels.isEmpty()) {
        Text("No labels yet. Create one from the drawer.", fontSize = 13.sp,
            color = palette.textSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
    labels.forEach { l -> SheetOption(l.name, false) { onPick(l.id) } }
}

/**
 * Snooze. The body key the server reads is `until`
 * (`grapmail-archive.ts:171`), and the cap is two years — beyond that a snooze
 * is indistinguishable from hiding a message for good.
 *
 * The warning is not decoration: there is no `snoozed` box to read these back
 * from, so a snoozed message leaves the visible set until it comes round.
 */
@Composable
internal fun SnoozeContent(onPick: (String) -> Unit) {
    val palette = LocalPalette.current
    SheetTitle("Snooze until")
    val options = listOf(
        "This evening" to 4,
        "Tomorrow morning" to 16,
        "Next weekend" to 96,
    )
    options.forEach { (label, hours) ->
        SheetOption(label, false) {
            val millis = System.currentTimeMillis() + hours * 3_600_000L
            onPick(java.time.Instant.ofEpochMilli(millis).toString())
        }
    }
    Text(
        "Snoozed mail leaves your inbox until it is due. This build has no way to " +
            "list messages that are snoozed, because the API has no box for them — " +
            "unsnoozing needs the message to be visible first.",
        fontSize = 12.sp, color = palette.warnInk,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
internal fun PermanentDeleteContent(count: Int, cancel: () -> Unit, confirm: () -> Unit) {
    val palette = LocalPalette.current
    SheetTitle(if (count == 1) "Delete this message permanently?" else "Delete $count messages permanently?")
    Text(
        "This cannot be undone. Messages in Trash can be restored instead — " +
            "this removes them for good, and only works from Trash.",
        fontSize = 13.sp, color = palette.textSecondary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        com.grapsee.grapmail.ui.components.OutlineButton("Cancel", cancel)
        com.grapsee.grapmail.ui.components.GradientButton("Delete permanently", confirm)
    }
}

@Composable
private fun SheetOption(label: String, active: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = palette.textPrimary, modifier = Modifier.weight(1f))
        if (active) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(palette.primarySolid),
                contentAlignment = Alignment.Center) {
                Text("", fontSize = 10.sp, color = palette.onPrimary)
            }
        }
    }
}
