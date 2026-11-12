package com.grapsee.grapmail.ui.screens.mail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SheetState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
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
    /** A draft row opens the composer on its own text, not the reader. */
    onOpenDraft: (id: String) -> Unit,
    onSettings: () -> Unit,
    onSecurity: () -> Unit,
    onHelp: () -> Unit,
    onLabels: () -> Unit,
    onContacts: () -> Unit,
    onFiles: () -> Unit,
    onScreener: () -> Unit,
    onSubscriptions: () -> Unit,
    onSignOut: () -> Unit,
) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showSortSheet by remember { mutableStateOf(false) }
    var showAdvSheet by remember { mutableStateOf(false) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showLabelSheet by remember { mutableStateOf(false) }
    var showSnoozeSheet by remember { mutableStateOf(false) }
    var showPermanentConfirm by remember { mutableStateOf(false) }
    var showSenderSheet by remember { mutableStateOf(false) }
    // The last decision receipt, kept here rather than in the view model: it is
    // one line of text about one interaction, and the sheet that shows it is the
    // only thing that can dismiss it. The view model's own `error` stays on the
    // screener screen, where it belongs.
    var decisionResult by remember { mutableStateOf<String?>(null) }
    val screener by vm.screener.collectAsState()

    // Ask for more once the list is nearly at the end. Not "on the last item",
    // which fires one screen late and makes the wait visible.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, state.pages) {
        if (nearEnd && state.hasRows && state.canLoadMore && state.appendError == null) vm.loadMore()
    }

    // The screen's first ask: the box that has never been fetched. Nothing here
    // fetched the list at all before this line existed, which is how a mailbox
    // renders "Inbox is empty" having never asked the server anything.
    LaunchedEffect(vm) {
        vm.ensureLoaded()
        vm.loadAccount()
    }

    var refreshing by remember { mutableStateOf(false) }
    // The gesture's spinner is released by the load SETTLING, not by `loading`
    // going false. The two are not the same thing here: a refresh over a populated
    // list deliberately never raises `loading` (that is the no-blank rule), so
    // keying on it meant the key never changed, this effect never re-ran, and one
    // pull left an indeterminate spinner parked over the inbox for the rest of the
    // session -- whether the refresh brought rows or an error.
    LaunchedEffect(state.settleGeneration) { if (state.settleGeneration > 0) refreshing = false }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            MailDrawer(
                box = state.box,
                boxes = drawerBoxes(state),
                unread = state.unreadCount,
                counts = drawerCounts(state),
                onBox = { scope.launch { drawer.close() }; vm.open(it) },
                onLabels = { scope.launch { drawer.close() }; onLabels() },
                onScreener = { scope.launch { drawer.close() }; onScreener() },
                onSubscriptions = { scope.launch { drawer.close() }; onSubscriptions() },
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
                        // The read is asked for when the sheet is opened, not when
                        // the screen loads: a mailbox where nobody labels in bulk
                        // should not pay for a pair list, and asking here means the
                        // first open is the only time the question can be unanswered
                        // — which the sheet says instead of guessing.
                        onLabel = { vm.ensureAssignments(); showLabelSheet = true },
                        onMarkRead = { vm.act(com.grapsee.grapmail.data.MailAction.Read) },
                        onMarkUnread = { vm.act(com.grapsee.grapmail.data.MailAction.Unread) },
                        onMore = { showSnoozeSheet = true },
                        // Only the queue can decide a sender. Every other box's
                        // mail has already been delivered, so the sheet's four
                        // options would be rules about a message that needs no
                        // rule — which is why this is null rather than a button
                        // that opens an empty sheet.
                        onSender = if (state.box == com.grapsee.grapmail.data.MailBox.Screener)
                            ({ showSenderSheet = true }) else null,
                    )
                } else {
                    MailTopBar(
                        onMenu = { scope.launch { drawer.open() } },
                        accountName = state.accountName.orEmpty(),
                        accountAddress = state.accountAddress,
                        onSettings = onSettings,
                        onSignOut = onSignOut,
                    ) {
                        SearchField(
                            query = state.query,
                            onQuery = vm::query,
                            onFilter = { showSortSheet = true },
                            filterActive = state.filter != MailFilter.All || state.sort != MailSort.Newest,
                            onAdvanced = { showAdvSheet = true },
                            advActive = state.advFrom.isNotBlank() || state.advTo.isNotBlank() ||
                                state.advSubject.isNotBlank() || state.advFiles ||
                                state.advAfter.isNotBlank() || state.advBefore.isNotBlank(),
                        )
                    }
                    Text(
                        state.box.title,
                        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                        color = palette.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // The device's own path to the network, read before asking
                // rather than after a failure. The chip below can only report a
                // refresh that was refused; this says why, while the list is
                // still on screen, so a list that has not moved for ten minutes
                // is not mistaken for a quiet inbox. `null` — no reading yet —
                // draws nothing: "offline" before the callback has spoken is a
                // claim about a network nobody checked. iOS's `offlineBanner`
                // is the same statement in the same place.
                if (state.online == false) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(10.dp)).background(palette.warnChipBg)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("No network right now — showing the last loaded mail.",
                            fontSize = 12.sp, color = palette.warnInk,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).testTag("mail-offline"))
                    }
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

                // The snooze undo. Shown in the same place as the stale-list chip
                // because it is the same kind of thing: a fact about what the app
                // just did, above the list it affects, with the action that reverses
                // it. It says "Snoozed …" rather than offering a list of postponed
                // mail, because until `?box=snoozed` is deployed the app only knows
                // about the ones it snoozed a moment ago.
                if (state.snoozeUndoNote != null && state.snoozeUndoIds.isNotEmpty()) {
                    UndoBanner(state.snoozeUndoNote!! + " \u00b7 out of the inbox until then",
                        "mail-snooze-undo", onUndo = { vm.undoSnooze() },
                        onDone = { vm.dismissSnoozeUndo() })
                }

                // The filing undo: the same kind of fact in the same place --
                // what the app just did, above the list it affects, with the
                // inverse PATCH as the way back. It names the ids the server
                // accepted, not "all your mail", and it disappears the moment
                // another filing supersedes it.
                if (state.undoNote != null && state.undoIds.isNotEmpty()) {
                    UndoBanner(state.undoNote!!, "mail-undo", onUndo = { vm.undoFiling() },
                        onDone = { vm.dismissUndo() })
                }

                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { refreshing = true; vm.load() },
                    modifier = Modifier.weight(1f),
                ) {
                    MailStateBlock(
                        loading = state.loading,
                        failed = state.fatal != null,
                        noMailbox = state.noMailbox,
                        empty = !state.hasRows && !state.canLoadMore && !state.loadingMore,
                        boxTitle = state.box.title,
                        onRetry = { vm.load() },
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().testTag("mail-list"),
                        ) {
                            // `key` and `contentType` are arguments of `items`, not
                            // of `LazyColumn` — the spec writes them as if they sat on
                            // the list, and the compiler rejects that ("no parameter
                            // with name 'contentType' found"). They belong to the item
                            // provider because they are per-item facts.
                            //
                            // Keys are the message id, which is what lets an appended
                            // page keep the rows it already composed. One content type
                            // because every item is the same row shape: it is how the
                            // list may recycle a composition between rows instead of
                            // treating each as a different layout.
                            //
                            // Stated plainly rather than improved in passing: a
                            // selection change recomposes the rows on screen, because
                            // `selection` lives in the one state object the list
                            // reads. Only on-screen rows recompose — the lazy scope
                            // holds — but per-row `derivedStateOf` would need the
                            // selection set out of that object, which is a view-model
                            // change this sector's evidence does not ask for.
                            items(state.rows, key = { it.id }, contentType = { "row" }) { row ->
                                // Which swipes mean something depends on where the
                                // message already is. Archiving something that is in
                                // Archive, or trashing something already in Trash,
                                // is a gesture that fires a request to change a state
                                // it cannot change — so those directions are simply
                                // not offered there, and the row does not move.
                                val inTrashOrSpam = state.box == com.grapsee.grapmail.data.MailBox.Trash ||
                                    state.box == com.grapsee.grapmail.data.MailBox.Spam
                                val inArchive = state.box == com.grapsee.grapmail.data.MailBox.Archive
                                SwipeableMessageRow(
                                    leadingTo = if (inTrashOrSpam) "Back to Inbox"
                                        else if (inArchive) null else "Archive",
                                    trailingTo = if (inTrashOrSpam) null else "Trash",
                                    onLeading = {
                                        vm.actOn(row.id, if (inTrashOrSpam)
                                            com.grapsee.grapmail.data.MailAction.Restore
                                        else com.grapsee.grapmail.data.MailAction.Archive)
                                    },
                                    onTrailing = {
                                        vm.actOn(row.id, com.grapsee.grapmail.data.MailAction.Trash)
                                    },
                                ) {
                                    MessageRow(
                                        message = row,
                                        selected = state.selection.contains(row.id),
                                        onSelect = { vm.toggleSelect(row.id) },
                                        onOpen = {
                                            // Drafts are the person's own
                                            // unfinished mail: tapping one
                                            // continues it in the composer.
                                            // Opening the reader instead —
                                            // what this did — shows a
                                            // message you cannot edit or
                                            // send, which is a draft that
                                            // does not draft.
                                            if (state.box == MailBox.Drafts) {
                                                onOpenDraft(row.id)
                                            } else {
                                                val tid = row.threadId
                                                onOpenThread(tid ?: row.id, tid != null)
                                            }
                                        },
                                        onStar = {
                                            vm.actOn(row.id,
                                                if (row.isStarred) com.grapsee.grapmail.data.MailAction.Unstar
                                                else com.grapsee.grapmail.data.MailAction.Star)
                                        },
                                        selecting = state.selecting,
                                    )
                                }
                                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    .height(1.dp).background(palette.border))
                            }
                            if (state.loadingMore || state.canLoadMore) {
                                item {
                                    Column(Modifier.fillMaxWidth().padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (state.loadingMore) {
                                            Text("Loading more…", fontSize = 13.sp, color = palette.textTertiary)
                                        } else {
                                            state.appendError?.let {
                                                Text(it, fontSize = 13.sp, color = palette.textSecondary)
                                            }
                                            androidx.compose.material3.TextButton(onClick = vm::loadMore) {
                                                Text(if (state.appendError == null) "Load more" else "Retry")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                MailBottomBar(selected = 0, unread = state.unreadCount,
                    onContacts = onContacts, onFiles = onFiles,
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
    if (showAdvSheet) {
        MailSheet(onDismiss = { showAdvSheet = false }) {
            AdvancedSearchContent(state, vm, onDone = { showAdvSheet = false })
        }
    }
    if (showMoveSheet) {
        MailSheet(onDismiss = { showMoveSheet = false }) {
            MoveContent { action -> vm.act(action); showMoveSheet = false }
        }
    }
    if (showLabelSheet) {
        MailSheet(onDismiss = { showLabelSheet = false }) {
            AssignLabelContent(
                labels = state.labels,
                labelsRead = state.labelsRead,
                marks = state.labelMarks,
                read = state.assignmentsRead,
                note = state.labelNote,
                selectedCount = state.selection.size,
            ) { vm.toggleLabel(it) }
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
    if (showSenderSheet) {
        // Deliberately not dismissed on pick, unlike every other sheet here. A
        // filing action either worked or it did not and the list changes under
        // you, so the sheet closing is the feedback. A decision is a standing
        // rule about someone else's FUTURE mail, invisible in this list, so the
        // only feedback there is the receipt line — and a sheet that closed
        // before it could be read would have taken the answer with it.
        MailSheet(onDismiss = { showSenderSheet = false; decisionResult = null }) {
            SenderDecisionContent(
                senders = state.rows.filter { it.id in state.selection }
                    .mapNotNull { it.from?.address?.takeIf { a -> a.isNotBlank() } }
                    .distinct(),
                busy = screener.busy,
                result = decisionResult,
            ) { scope, decision ->
                vm.decideSenders(
                    state.rows.filter { it.id in state.selection }
                        .mapNotNull { it.from?.address }.distinct(),
                    scope, decision,
                ) { decisionResult = it }
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
    // These three used to be hardcoded nulls on the claim that `/stats` never
    // answers them. It does now — `starred`, `archive` and `others` are counted by
    // the same `boxCount` that builds the listing beside each badge
    // (`grapmail.ts:666-688`) — so the drawer shows the server's number, and shows
    // nothing at all on a deployment that has not answered yet.
    MailBox.Starred to s.statsStarred,
    MailBox.Archive to s.statsArchive,
    MailBox.All to s.statsOthers,
    MailBox.Snoozed to s.statsSnoozed,
    MailBox.Screener to null,
)

/**
 * The drawer's folder list.
 *
 * `Snoozed` is inserted only when `/stats` answered the `snoozed` key. That is a
 * capability read off the server's own response shape, not a build flag: a
 * deployment whose `BOXES` lacks `snoozed` (`grapmail-box-terms.ts:66-68`) also
 * fails to answer the count, and the row must not exist for a listing that would
 * refuse it. Derived from the state snapshot rather than from the view model so a
 * Compose recomposition follows the answer as it arrives.
 */
internal fun drawerBoxes(s: MailboxViewModel.MailboxUiState): List<MailBox> =
    if (s.statsSnoozed != null) {
        listOf(
            MailBox.Inbox, MailBox.Starred, MailBox.Sent, MailBox.Drafts,
            MailBox.Snoozed, MailBox.Archive, MailBox.Spam, MailBox.Trash, MailBox.All,
        )
    } else MailBox.drawerOrder

// ── chrome ───────────────────────────────────────────────────────────────────

@Composable
private fun MailTopBar(
    onMenu: () -> Unit,
    accountName: String,
    accountAddress: String?,
    onSettings: () -> Unit,
    onSignOut: () -> Unit,
    search: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    var accountOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.IconButton(onClick = onMenu, modifier = Modifier.testTag("mail-menu")) {
            Icon(Icons.Rounded.Menu, "Open folders", tint = palette.textPrimary)
        }
        Box(Modifier.weight(1f)) { search() }
        Box {
            androidx.compose.material3.IconButton(
                onClick = { accountOpen = true },
                modifier = Modifier.semantics { contentDescription = "Account" },
            ) { SenderAvatar(accountName, accountAddress, size = 32.dp) }
            DropdownMenu(expanded = accountOpen, onDismissRequest = { accountOpen = false }) {
                if (!accountAddress.isNullOrBlank()) {
                    Text(accountAddress, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                DropdownMenuItem(text = { Text("Settings") }, onClick = {
                    accountOpen = false
                    onSettings()
                })
                DropdownMenuItem(text = { Text("Sign out") }, onClick = {
                    accountOpen = false
                    onSignOut()
                })
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onFilter: () -> Unit, filterActive: Boolean,
                        onAdvanced: () -> Unit, advActive: Boolean) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.card)
            .border(1.dp, palette.border, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = palette.textTertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        // A real value field: the reference's placeholder is not a hint that the
        // box is empty, it is what an unfocused search looks like. The wording
        // names what the server's term actually covers -- subject, body, sender
        // (`grapmail-search.ts:110-118`) -- because a placeholder promising
        // "people or files" would advertise a search the route does not run.
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onQuery,
            maxLines = 1,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = palette.textPrimary),
            modifier = Modifier.weight(1f).testTag("mail-search")
                .semantics { contentDescription = "Search subject, body or sender" },
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text("Search mail", fontSize = 14.sp, color = palette.textTertiary)
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Rounded.Tune, "Search filters",
            tint = if (advActive) palette.primarySolid else palette.textSecondary,
            modifier = Modifier.size(18.dp).clip(CircleShape).clickable(onClick = onAdvanced)
                .testTag("mail-adv-filter"),
        )
        Spacer(Modifier.width(10.dp))
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
            LabelChip("Inbox", s.unreadCount, null, s.query.isEmpty() && s.filter == MailFilter.All)
        }
        items(s.labels) { l ->
            LabelChip(l.name, l.unread, l.color, false)
        }
    }
}

/**
 * The box tabs. Tappable, and they go to the same place the drawer rows do.
 *
 * These were inert once — the row drew the active underline and the drawer's
 * counts, so it read as the box switcher the reference shows, and a tap did
 * nothing. A control that only looks like a control is worse than no control:
 * the drawer works, so a person concludes the tab row is broken rather than
 * decorative. It now opens the box through the same `vm.open`, which means the
 * same fetch policy (a box already read is not re-read) and the same
 * selection-clearing as the drawer path.
 */
@Composable
private fun BoxTabRow(s: MailboxViewModel.MailboxUiState, onSelect: (MailBox) -> Unit) {
    val counts = drawerCounts(s)
    // Scrollable tabs, the Material answer to a tab bar wider than the
    // screen. The fixed Row this replaces measured every label against an
    // equal slice of width: on the 1080px phone "Inbox" wrapped to
    // "In/bo/x" and Starred/Archive broke mid-word, and on the narrow
    // walk device four of the five boxes fell off the row entirely --
    // doors that exist must stay reachable at every width.
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            .horizontalScroll(rememberScrollState())
            .testTag("mail-box-tabs"),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        MailBox.tabOrder.forEach { box ->
            BoxTab(box.title, counts[box], box == s.box, onClick = { onSelect(box) })
        }
    }
}

/**
 * A message row that files on swipe.
 *
 * Two directions, and each is optional: a null label means that direction is not
 * offered at all, so the row refuses to move rather than firing a request that
 * cannot change anything. That is how one component serves Trash — where the
 * useful swipe is back out to the inbox, and trashing an already-trashed message
 * is a no-op dressed as an action — and the inbox, where both mean something.
 *
 * Nothing here destroys mail. A swipe can archive, trash or restore, and all three
 * undo; permanent deletion stays behind its confirmation sheet, reachable only
 * from Trash. So both colours mean "moved", never "gone".
 *
 * The action fires on the settled value rather than during the drag, and the row
 * snaps back afterwards: a swipe released short of the threshold animates home and
 * must file nothing, and a row left dismissed off-screen would show a gap in a list
 * whose length has not changed yet.
 *
 * One mechanic genuinely differs from iOS, and it is worth naming rather than
 * describing the two as identical: SwiftUI can refuse a full fling
 * (`allowsFullSwipe: false`) so the row stays open until the revealed button is
 * tapped, and Material3 has no equivalent — past the halfway threshold a fling
 * files the message. Both directions here archive, trash or restore, all of which
 * undo, so the difference is in how much a stray flick costs: nothing irreversible
 * is reachable either way.
 */
@Composable
private fun SwipeableMessageRow(
    leadingTo: String?,
    trailingTo: String?,
    onLeading: () -> Unit,
    onTrailing: () -> Unit,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    val state = rememberSwipeToDismissBoxState()
    val direction by remember(state) { derivedStateOf { state.dismissDirection } }

    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> if (leadingTo != null) onLeading()
            SwipeToDismissBoxValue.EndToStart -> if (trailingTo != null) onTrailing()
            SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
        }
        state.snapTo(SwipeToDismissBoxValue.Settled)
    }

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = leadingTo != null,
        enableDismissFromEndToStart = trailingTo != null,
        backgroundContent = {
            val toLeading = direction == SwipeToDismissBoxValue.StartToEnd
            val swiping = direction != SwipeToDismissBoxValue.Settled
            val label = if (toLeading) leadingTo else trailingTo
            Row(
                Modifier.fillMaxSize()
                    .background(when {
                        !swiping -> Color.Transparent
                        toLeading -> palette.swipeArchive
                        else -> palette.swipeTrash
                    })
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toLeading) Arrangement.Start else Arrangement.End,
            ) {
                // Nothing at all while the row is at rest: a coloured band behind an
                // untouched row would read as a state the message is not in.
                if (swiping && label != null) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = Color.White)
                }
            }
        },
    ) { Box(Modifier.fillMaxWidth().background(palette.bg)) { content() } }
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
    onContacts: () -> Unit,
    onFiles: () -> Unit,
    onMore: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(palette.card).border(1.dp, palette.border)
            .padding(vertical = 8.dp).testTag("mail-tabs"),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        // No handler for Mail: it is the screen this bar is standing on.
        TabItem(Icons.Rounded.Inbox, "Mail", selected == 0, unread)
        TabItem(Icons.Rounded.Contacts, "Contacts", selected == 1) { onContacts() }
        TabItem(Icons.Rounded.Description, "Files", selected == 2) { onFiles() }
        TabItem(Icons.Rounded.MoreHoriz, "More", selected == 3) { onMore() }
    }
}

@Composable
private fun TabItem(icon: ImageVector, label: String, active: Boolean, badge: Int? = null,
                    onClick: (() -> Unit)? = null) {
    val palette = LocalPalette.current
    Column(
        // A tab that names the screen you are already on is not a destination, so
        // it takes no click and no `clickable` at all — no ripple, no accessibility
        // action, nothing for a tap to fall through to. A Button wired to an empty
        // lambda is precisely the control-that-goes-nowhere this sector was told
        // not to ship, and it is invisible to a screenshot: the frame looks right.
        Modifier.then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 14.dp, vertical = 4.dp),
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
 * The folder drawer. `boxes` comes from the view model, not from a constant: the
 * Snoozed row is only there when `/stats` answered the `snoozed` key, so the row
 * and the route behind it appear together or not at all. What stays absent is
 * Important and Calendar — no `importance` column and no calendar backend anywhere
 * in the API, recorded with the file:line in the README's honest-UI contract.
 */
@Composable
internal fun MailDrawer(
    box: MailBox,
    boxes: List<MailBox>,
    unread: Int?,
    counts: Map<MailBox, Int?>,
    onBox: (MailBox) -> Unit,
    onLabels: () -> Unit,
    onScreener: () -> Unit,
    onSubscriptions: () -> Unit,
    onSettings: () -> Unit,
    onSecurity: () -> Unit,
    onHelp: () -> Unit,
    onSignOut: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        // The folders scroll, the account actions are pinned. The lower group
        // lived in the same scroller as the boxes, which laid Security and Sign
        // out below the scroller's own interactive bounds — unreachable for a
        // thumb, and untappable for the walk. Same shape as iOS.
        Modifier.width(300.dp).fillMaxSize().background(palette.card)
            .padding(top = 28.dp, bottom = 16.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            GrapMailLogo(size = 34.dp, wordmark = true)
        }
        Spacer(Modifier.height(20.dp))

        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        ) {
        boxes.forEach { b ->
            DrawerRow(b.title, iconFor(b), counts[b], selected = b == box) { onBox(b) }
        }


        Spacer(Modifier.height(14.dp))
        // The web account shell's Subscriptions page, on the phone. The word
        // is the screen's claim exactly: senders detected from loaded mail,
        // each with a handoff to stop them -- the screen itself states its
        // scope, so the row does not have to.
        DrawerSection("Mail")
        DrawerRow("Subscriptions", Icons.Rounded.MailOutline, null, selected = false) {
            onSubscriptions()
        }

        Spacer(Modifier.height(14.dp))
        // Queue and Labels follow the Mail section deliberately: frame 62
        // caught Subscriptions sitting below an unmarked fold, invisible in
        // the drawer that is its only phone entry. Manage labels keeps a
        // second door (the home's label strip), Pending approval keeps the
        // Settings row -- these two survive the fold; Subscriptions must not.
        DrawerSection("Queue")
        DrawerRow("Pending approval", Icons.Rounded.Lock, null, selected = false) { onScreener() }

        Spacer(Modifier.height(14.dp))
        DrawerSection("Labels")
        DrawerRow("Manage labels", Icons.Rounded.Label, null, selected = false) { onLabels() }
        }

        // No spacer here. This one carried `weight(1f)` from the layout that
        // preceded the scrollable list, when its job was to push the account rows
        // to the bottom. The folder list now claims `weight(1f)` too, so the two
        // split the drawer and the list got half the room it asked for: Spam, Trash,
        // All, the queue row and Manage labels all fell below a fold that gave no
        // sign it scrolled, and Trash is where the permanent-delete flow lives.
        // The scroller already takes the leftover height, so the footer sits at the
        // bottom on its own — which is what iOS's structure does.
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp)
            .background(palette.border))
        DrawerRow("Settings", Icons.Rounded.Settings, null, selected = false) { onSettings() }
        // Shield, not Star: the drawer's Security row means protection, and iOS
        // draws the same meaning. Star is the Starred box's own icon, and one
        // glyph carrying two unrelated ideas is how a drawer becomes unreadable.
        DrawerRow("Security", Icons.Rounded.Shield, null, selected = false) { onSecurity() }
        DrawerRow("Help & Support", Icons.Rounded.HelpOutline, null, selected = false) { onHelp() }
        Spacer(Modifier.height(10.dp))
        Text("Sign out", fontSize = 14.sp, color = palette.error,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 20.dp).clickable(onClick = onSignOut)
                .testTag("sign-out"))
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
            .testTag("mail-drawer-$label")
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
    // A clock, not a bell: the box holds mail the server is holding back until a
    // moment the user chose, and iOS draws the same metaphor in the same slot.
    MailBox.Snoozed -> Icons.Rounded.Schedule
    MailBox.Archive -> Icons.Rounded.Folder
    MailBox.Spam -> Icons.Rounded.Report
    MailBox.Trash -> Icons.Rounded.Delete
    MailBox.Screener -> Icons.Rounded.Lock
    MailBox.All -> Icons.Rounded.AllInbox
    MailBox.None -> Icons.Rounded.Inbox
}

// ── sheets ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun MailSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    // Partially expanded, as Material3 intends. This was switched to fully
    // expanded one commit ago on the theory that the sheet opened with its title
    // clipped off screen. The dump that produced the complaint shows the title at
    // [300,1376][485,1424] — plainly on a 2400px screen — so the theory was wrong
    // and the change was unjustified. Reverted rather than left in "because it also
    // seemed nicer": an app behaviour changed on a misreading is a change nobody
    // can defend later.
    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // The root sets `testTagsAsResourceId` for the app's own tree, but a
        // ModalBottomSheet composes into its OWN window, and the property does not
        // cross that boundary — so every `gm-sheets-*` tag inside a sheet is
        // invisible to a uiautomator dump unless it is set again here. Proven the
        // slow way: the drawer, menu and search anchors all resolved on the first
        // run and only the sheet's title timed out, which is what separated
        // "the mechanism is broken" from "the mechanism stops at a window edge".
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)
            .semantics { testTagsAsResourceId = true }) { content() }
    }
}

/**
 * A sheet's heading.
 *
 * `tag` is the sheet's identity, and it is a parameter rather than something
 * derived from `text` on purpose: the whole point of the walk anchoring on an
 * identifier is that re-wording a heading is a copy change and not a test
 * failure. Deriving the tag from the title would rebuild the exact coupling this
 * removes. The values match iOS's `gm-sheets-*` identifiers, so the two walks
 * prove the same thing about the same sheets.
 *
 * `internal`, not `private`: the unsubscribe confirmation lives in `ThreadScreen.kt`
 * and needs the same heading with the same identifier contract. A second copy of
 * this in that file is how a sheet heading would stop matching the tag the walk
 * waits for.
 */
@Composable
internal fun SheetTitle(text: String, tag: String) {
    val palette = LocalPalette.current
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).testTag(tag))
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
    SheetTitle("Sort & filter", "gm-sheets-sort")
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
 * The undo banner -- one composable for both undo offers (snooze and filing),
 * because they are the same kind of fact: what the app just did, above the
 * list it affects, with the action that reverses it. The live screen raises it
 * after a real filing; the snapshot host draws it with the words a real
 * filing would produce, because a read-only walk cannot archive anything and
 * a banner no photograph has ever taken is a banner whose layout nobody has
 * seen.
 */
@Composable
internal fun UndoBanner(note: String, tag: String, onUndo: () -> Unit, onDone: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)).background(palette.softFill)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(note, fontSize = 12.sp, color = palette.textPrimary,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text("Undo", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = palette.primarySolid, modifier = Modifier.clickable(onClick = onUndo))
        Spacer(Modifier.width(12.dp))
        Text("Done", fontSize = 12.sp, color = palette.textTertiary,
            modifier = Modifier.clickable(onClick = onDone))
    }
}

/**
 * The advanced search sheet -- six filters the server has no route for, run
 * CLIENT-SIDE over the pages this device has loaded, and saying so in its own
 * scope line the way the sort sheet does. The server's `q` is one literal term
 * over subject, body and from (`grapmail-search.ts:110-118`); there is no
 * from/to/attachment/date filter endpoint to call, so this sheet filters what
 * is here and never claims to search the box.
 */
@Composable
internal fun AdvancedSearchContent(
    state: com.grapsee.grapmail.logic.MailboxViewModel.MailboxUiState,
    vm: com.grapsee.grapmail.logic.MailboxViewModel,
    onDone: () -> Unit,
) {
    val palette = LocalPalette.current
    var from by remember { mutableStateOf(state.advFrom) }
    var to by remember { mutableStateOf(state.advTo) }
    var subject by remember { mutableStateOf(state.advSubject) }
    var files by remember { mutableStateOf(state.advFiles) }
    var after by remember { mutableStateOf(state.advAfter) }
    var before by remember { mutableStateOf(state.advBefore) }
    SheetTitle("Search filters", "gm-sheets-advsearch")
    Column(Modifier.padding(horizontal = 20.dp)) {
        AdvField("From", "A name or address in the sender", from) { from = it }
        AdvField("To", "A name or address in the recipients", to) { to = it }
        AdvField("Subject", "A word the subject contains", subject) { subject = it }
        Row(
            Modifier.fillMaxWidth().clickable { files = !files }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AttachFile, null,
                tint = if (files) palette.primarySolid else palette.textTertiary,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Has attachments", fontSize = 14.sp,
                color = if (files) palette.primarySolid else palette.textPrimary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { AdvField("After", "YYYY-MM-DD", after) { after = it } }
            Box(Modifier.weight(1f)) { AdvField("Before", "YYYY-MM-DD", before) { before = it } }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Apply", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = palette.primarySolid,
                modifier = Modifier.clickable {
                    vm.setAdvanced(from, to, subject, files, after, before); onDone()
                })
            Text("Clear", fontSize = 14.sp, color = palette.textSecondary,
                modifier = Modifier.clickable {
                    vm.clearAdvanced()
                    from = ""; to = ""; subject = ""; files = false; after = ""; before = ""
                })
        }
        // The scope, stated exactly as the sort sheet states its own: these
        // filters run on the loaded pages, not the box. The typed term above
        // is the whole-box search; this sheet is what it narrows further.
        Text("These filters run on the ${state.advScope} messages loaded on this " +
            "device \u2014 not all ${state.total} in this box. The typed term is " +
            "the whole-box search.",
            fontSize = 12.sp, color = palette.warnInk,
            modifier = Modifier.padding(vertical = 8.dp))
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AdvField(label: String, placeholder: String, value: String, onValue: (String) -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.textTertiary)
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValue,
            maxLines = 1,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = palette.textPrimary),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, fontSize = 14.sp, color = palette.textTertiary)
                    inner()
                }
            },
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
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
    SheetTitle("Move to", "gm-sheets-move")
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
    labelsRead: com.grapsee.grapmail.data.AssignmentsRead,
    marks: Map<String, com.grapsee.grapmail.data.LabelMark>,
    read: com.grapsee.grapmail.data.AssignmentsRead,
    note: String?,
    selectedCount: Int,
    onPick: (String) -> Unit,
) {
    val palette = LocalPalette.current
    SheetTitle("Label", "gm-sheets-label")
    if (labels.isEmpty()) {
        // Two different facts, and the old copy asserted the second one whatever
        // had happened: a mailbox whose label list could not be read is not a
        // mailbox with no labels, and "create one from the drawer" is advice to
        // act on. Same rule the sheet already applies to the assignment read.
        Text(
            when (labelsRead) {
                com.grapsee.grapmail.data.AssignmentsRead.Failed -> "Couldn't read your labels."
                com.grapsee.grapmail.data.AssignmentsRead.Read -> "No labels yet. Create one from the drawer."
                else -> "Loading labels…"
            },
            fontSize = 13.sp, color = palette.textSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
    // What the marks mean, said before the user reads anything into a tick. This
    // line is the difference between a sheet that consulted the server and one
    // that merely looks like it did: when the read has not answered, the rows are
    // deliberately unmarked and the sheet says so rather than showing them clean.
    // A named local, not `Text(when (…) { … }, fontSize = …)`. Kotlin's parser
    // read the branch that opens with `if (selectedCount > 1)` as a statement
    // rather than the arm's value, and the run died with "expecting a
    // when-condition" plus "none of the following candidates is applicable" on the
    // Text call -- three errors from one shape. Hoisting the `when` out of the
    // argument list makes both go away and reads better anyway.
    val readLine = when (read) {
        com.grapsee.grapmail.data.AssignmentsRead.Reading,
        com.grapsee.grapmail.data.AssignmentsRead.NotRead ->
            "Checking which labels these messages carry…"
        com.grapsee.grapmail.data.AssignmentsRead.Failed ->
            "Couldn't read which labels they carry, so nothing is marked here. " +
            "Tapping adds a label; it won't remove one."
        com.grapsee.grapmail.data.AssignmentsRead.Read ->
            if (selectedCount > 1)
                "Marks show what the $selectedCount selected messages carry. " +
                "A dash means some but not all."
            else "Marks show what this message carries."
    }
    Text(readLine, fontSize = 12.sp, color = palette.textTertiary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    labels.forEach { l ->
        val m = marks[l.id] ?: com.grapsee.grapmail.data.LabelMark.Unknown
        SheetOption(
            l.name,
            active = m == com.grapsee.grapmail.data.LabelMark.On,
            mixed = m == com.grapsee.grapmail.data.LabelMark.Mixed,
        ) { onPick(l.id) }
    }
    note?.let {
        Text(it, fontSize = 13.sp, color = palette.successInk,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

/**
 * Deciding a sender, from the screener queue.
 *
 * Three things this sheet refuses to pretend about:
 *
 *  - It does not show which decision, if any, is already in force for these
 *    senders. The screener surface writes decisions and cannot read them back, so
 *    no option is marked as current — a pre-selected radio would be an invented
 *    fact, and the wrong one is worse than none.
 *  - It does not claim the messages on screen will move. A decision is a rule for
 *    the NEXT delivery; the queue is re-read afterwards and the rows that remain
 *    stay visible, because that is what the server says.
 *  - It states what "the whole domain" reaches before it is chosen, not after.
 *    Approving a domain admits every mailbox at it including ones that do not
 *    exist yet, and that is not something to discover from a receipt line.
 */
@Composable
internal fun SenderDecisionContent(
    senders: List<String>,
    busy: Boolean,
    result: String?,
    onPick: (com.grapsee.grapmail.data.MailSenderScope, com.grapsee.grapmail.data.MailDecision) -> Unit,
) {
    val palette = LocalPalette.current
    var scope by remember { mutableStateOf(com.grapsee.grapmail.data.MailSenderScope.Address) }

    SheetTitle("Decide the sender", "gm-sheets-sender")
    Text(
        if (senders.size == 1) "Sender: ${senders[0]}"
        else "${senders.size} senders in the selected messages",
        fontSize = 13.sp, color = palette.textSecondary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )

    Text("APPLIES TO", fontSize = 11.sp, fontWeight = FontWeight.Bold,
        color = palette.textTertiary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    com.grapsee.grapmail.data.MailSenderScope.entries.forEach { s ->
        SheetOption(s.title, scope == s) { scope = s }
    }
    if (scope == com.grapsee.grapmail.data.MailSenderScope.Domain) {
        Text(
            com.grapsee.grapmail.data.MailSenderScope.Domain.warning,
            fontSize = 12.sp, color = palette.warnInk,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }

    Text("DECISION", fontSize = 11.sp, fontWeight = FontWeight.Bold,
        color = palette.textTertiary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    com.grapsee.grapmail.data.MailDecision.entries.forEach { d ->
        // No `enabled` while a write is in flight: the row still looks tappable
        // and simply declines, which is what the other sheets do, and the guard
        // is in the handler rather than in the widget so the rule cannot be
        // bypassed by a caller that draws the same option differently.
        SheetOption(d.title, false) {
            if (!busy) onPick(scope, d)
        }
        Text(d.note, fontSize = 12.sp, color = palette.textTertiary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
    }

    result?.let {
        Text(it, fontSize = 12.sp, color = palette.textPrimary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
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
    SheetTitle("Snooze until", "gm-sheets-snooze")
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
        // The sentence used to end "unsnoozing needs the message to be visible " +
        // "first", which this build no longer does: the Undo beside the list takes " +
        // "mail back out of snooze precisely when it is not visible, because the " +
        // "moment of snoozing is the moment the app knows. A sheet that denies a " +
        // "capability the same screen is offering is the oldest defect in this " +
        // "sector wearing new clothes, and this line is photographed in frame " +
        // "84-mail-snooze-sheet on both platforms, so the claim ships either way. " +
        // "The box really is absent server-side — that half stays stated."
        "Snoozed mail leaves your inbox until it is due. The server has no box " +
            "that lists what is postponed, so Undo appears beside the list for the " +
            "mail this session snoozed, and for no other mail.",
        fontSize = 12.sp, color = palette.warnInk,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
internal fun PermanentDeleteContent(count: Int, cancel: () -> Unit, confirm: () -> Unit) {
    val palette = LocalPalette.current
    SheetTitle(if (count == 1) "Delete this message permanently?" else "Delete $count messages permanently?",
        "gm-sheets-permanent")
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
private fun SheetOption(
    label: String,
    active: Boolean,
    /** Half-checked: some of the selection carries it. Only the Label sheet has a
     *  state this can describe — a filing target is either where the message is or
     *  it is not — so no other caller passes it. */
    mixed: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = palette.textPrimary, modifier = Modifier.weight(1f))
        if (mixed) {
            Box(
                Modifier.size(18.dp).clip(CircleShape)
                    .border(1.5.dp, palette.primarySolid, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // A dash, not a dot: iOS draws the same half-checked state as a
                // short bar inside the ring, and a filled square would read as a
                // different mark rather than as “some of these”.
                Box(Modifier.width(9.dp).height(3.dp).background(palette.primarySolid))
            }
        } else if (active) {
            // A real glyph. The child here was `Text("", ...)`, which paints
            // nothing: `on` was a solid disc while iOS drew a checkmark inside the
            // same circle. That matters now because the tick is the signal that a
            // tap will REMOVE the label -- an invisible mark is a wrong affordance,
            // not a cosmetic one, and it is what frame 83 is meant to show.
            Box(Modifier.size(18.dp).clip(CircleShape).background(palette.primarySolid),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = palette.onPrimary,
                    modifier = Modifier.size(12.dp))
            }
        }
    }
}
