@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.grapsee.grapmail.ui.screens.security

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PhonelinkErase
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Support
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.SecurityDeviceDto
import com.grapsee.grapmail.data.SecurityOverviewCache
import com.grapsee.grapmail.data.SecurityOverviewResponse
import com.grapsee.grapmail.data.SecurityProfileCache
import com.grapsee.grapmail.data.SecurityTimelineEventDto
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.logic.GMHaptics
import com.grapsee.grapmail.ui.components.GMLayout
import com.grapsee.grapmail.ui.components.OutlineButton
import com.grapsee.grapmail.ui.components.gmIsNarrowWindow
import com.grapsee.grapmail.ui.components.gmMaxWidth
import com.grapsee.grapmail.ui.components.rememberMotionReduced
import com.grapsee.grapmail.ui.components.rememberOnline
import com.grapsee.grapmail.ui.theme.BodyMedium
import com.grapsee.grapmail.ui.theme.Caption
import com.grapsee.grapmail.ui.theme.CaptionMedium
import com.grapsee.grapmail.ui.theme.H2
import com.grapsee.grapmail.ui.theme.LocalPalette
import com.grapsee.grapmail.ui.theme.Micro
import com.grapsee.grapmail.ui.theme.Title
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * Round 8 — Workstream C: the Device & Security Center (docs/design/round8-spec.md §3).
 *
 * One screen for devices, recent security activity and the safety score, fed
 * by GET /security/overview (§6.2). It renders the cached overview at once and
 * refetches on every resume — first appear, return from a child screen, app
 * foreground — and on pull-to-refresh. The old Trusted devices / Sessions /
 * Activity screens stay reachable (deep links, the device-keys link below).
 */

/** In-center destinations a caller may ask the center to scroll to on arrival. */
object SecurityCenterNav {
    @Volatile
    private var pendingFocus: SafetyAction? = null

    /** [SafetyAction.Devices] or [SafetyAction.Activity]; anything else is ignored. */
    fun requestFocus(action: SafetyAction) {
        if (action == SafetyAction.Devices || action == SafetyAction.Activity) pendingFocus = action
    }

    internal fun consumeFocus(): SafetyAction? = pendingFocus.also { pendingFocus = null }
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private const val VISIBLE_DEVICES = 5

@Composable
fun SecurityCenterScreen(
    onBack: () -> Unit,
    /** A checklist step that leads out of the center (two-step, passkeys, …). */
    onAction: (SafetyAction) -> Unit,
    /** Not-me succeeded: push "Account secured" (§3.4 step 4). */
    onNotMeSecured: (NotMeOutcome) -> Unit,
    /** The current device's "Sign out": the app's existing sign-out flow. */
    onSignOutThisDevice: () -> Unit,
    /** The §3.6 banner's button: change password in required mode. */
    onChangePassword: () -> Unit,
    /** The hardware-key screen (TrustedDevicesScreen) for re-enrolling this device. */
    onDeviceKeys: (() -> Unit)? = null,
    demoOverview: SecurityOverviewResponse? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = secColors()
    val reduced = rememberMotionReduced()
    val online = rememberOnline()
    val snackbar = remember { SnackbarHostState() }

    var overview by remember { mutableStateOf<SecurityOverviewResponse?>(null) }
    var fetchedAt by remember { mutableLongStateOf(0L) }
    var olderEvents by remember { mutableStateOf<List<SecurityTimelineEventDto>>(emptyList()) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var loadingOlder by remember { mutableStateOf(false) }
    var olderFailed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var pullRefreshing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var refreshFailed by remember { mutableStateOf(false) }
    var animateFrom by remember { mutableStateOf<Int?>(null) }
    var celebrate by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    var removingIds by remember { mutableStateOf(emptySet<String>()) }
    var removeTarget by remember { mutableStateOf<SecurityDeviceDto?>(null) }
    var removeBusy by remember { mutableStateOf(false) }
    var notMeTarget by remember { mutableStateOf<NotMeTarget?>(null) }
    var openEvent by remember { mutableStateOf<SecurityTimelineEventDto?>(null) }
    var acking by remember { mutableStateOf(false) }
    var showSecondDeviceHelp by rememberSaveable { mutableStateOf(false) }
    var showAllDevices by rememberSaveable { mutableStateOf(false) }

    val devicesRequester = remember { BringIntoViewRequester() }
    val activityRequester = remember { BringIntoViewRequester() }
    val firstReviewRequester = remember { BringIntoViewRequester() }

    suspend fun refresh() {
        if (demoOverview != null) {
            overview = demoOverview
            fetchedAt = System.currentTimeMillis()
            loading = false
            refreshing = false
            pullRefreshing = false
            failed = false
            refreshFailed = false
            return
        }
        val session = SessionStore.snapshot(context)
        if (session == null) {
            failed = overview == null
            loading = false
            pullRefreshing = false
            return
        }
        if (overview == null) {
            SecurityOverviewCache.read(context, session.address)?.let { held ->
                overview = held.overview
                fetchedAt = held.fetchedAt
                nextCursor = held.overview.eventsNextCursor
            }
        }
        loading = overview == null
        refreshing = overview != null
        try {
            val fresh = Api.securityOverview(session.token)
            if (!SessionStore.isCurrent(context, session.id)) return
            val previous = overview?.score?.value
                ?: SecurityOverviewCache.read(context, session.address)?.overview?.score?.value
            SecurityOverviewCache.publish(context, session.address, fresh)
            animateFrom = previous
            celebrate = fresh.score?.let { SafetyLocalFlags.shouldCelebrate(context, session.address, previous, it.value) } == true
            overview = fresh
            fetchedAt = System.currentTimeMillis()
            olderEvents = emptyList()
            olderFailed = false
            nextCursor = fresh.eventsNextCursor
            failed = false
            refreshFailed = false
            rememberAccountCreatedAt(context, session.address, fresh, session.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            if (SessionStore.isCurrent(context, session.id)) {
                if (overview == null) failed = true else refreshFailed = true
            }
        } finally {
            loading = false
            refreshing = false
            pullRefreshing = false
        }
    }

    // Screen appear, return from any child screen, and app foreground while
    // visible are all an ON_RESUME of this destination (§3.7 "Refetch on").
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload += 1 }
    LaunchedEffect(reload) { if (reload > 0) refresh() }

    fun loadOlder() {
        val cursor = nextCursor ?: return
        if (loadingOlder) return
        loadingOlder = true
        olderFailed = false
        scope.launch {
            try {
                val session = SessionStore.snapshot(context) ?: return@launch
                val page = Api.securityOverview(session.token, eventsCursor = cursor)
                if (!SessionStore.isCurrent(context, session.id)) return@launch
                val seen = (overview?.events.orEmpty() + olderEvents).map { it.id }.toHashSet()
                olderEvents = olderEvents + page.events.filter { it.id !in seen }
                nextCursor = page.eventsNextCursor
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                olderFailed = true
                GMHaptics.failure()
            } finally {
                loadingOlder = false
            }
        }
    }

    fun focusOn(action: SafetyAction) {
        scope.launch {
            // One frame (and a little) so a freshly composed section has a layout to bring into view.
            delay(80)
            when (action) {
                SafetyAction.Devices -> {
                    devicesRequester.bringIntoView()
                    showSecondDeviceHelp = true
                }
                SafetyAction.Activity -> {
                    val all = overview?.events.orEmpty() + olderEvents
                    if (all.any { it.needsReview() }) firstReviewRequester.bringIntoView()
                    else activityRequester.bringIntoView()
                }
                else -> Unit
            }
        }
    }

    // A deep link into "the devices section" or "the first Review row" (§4.2 #5, #8).
    LaunchedEffect(overview != null) {
        if (overview == null) return@LaunchedEffect
        SecurityCenterNav.consumeFocus()?.let { focusOn(it) }
    }

    fun onItem(entry: SafetyChecklistEntry) {
        when (entry.action) {
            SafetyAction.Devices, SafetyAction.Activity -> focusOn(entry.action)
            else -> onAction(entry.action)
        }
    }

    fun remove(device: SecurityDeviceDto) {
        val removal = device.removal ?: return
        if (removeBusy) return
        removeBusy = true
        scope.launch {
            val label = device.label.ifBlank { context.getString(R.string.seccenter_unknown_device) }
            try {
                val session = SessionStore.snapshot(context)
                    ?: throw IllegalStateException(context.getString(R.string.seccenter_remove_failed))
                when (removal.via) {
                    "trusted_device" -> Api.revokeDevice(session.token, removal.id)
                    "session" -> Api.revokeSession(session.token, removal.id)
                    else -> throw IllegalStateException(context.getString(R.string.seccenter_remove_failed))
                }
                if (!SessionStore.isCurrent(context, session.id)) return@launch
                removeTarget = null
                GMHaptics.success()
                // The card collapses (height → 0 + fade, 220 ms; instant under
                // reduce motion), then leaves the list, then the overview refetches.
                removingIds = removingIds + device.id
                delay(if (reduced) 0L else 220L)
                overview = overview?.let { o -> o.copy(devices = o.devices.filterNot { it.id == device.id }) }
                removingIds = removingIds - device.id
                launch { snackbar.showSnackbar(context.getString(R.string.seccenter_removed, label)) }
                reload += 1
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ApiException) {
                removeTarget = null
                GMHaptics.failure()
                launch {
                    snackbar.showSnackbar(
                        if (e.status == 404) context.getString(R.string.seccenter_err_gone)
                        else context.getString(R.string.seccenter_remove_failed),
                    )
                }
                if (e.status == 404) reload += 1
            } catch (_: Exception) {
                GMHaptics.failure()
                launch { snackbar.showSnackbar(context.getString(R.string.seccenter_err_network)) }
            } finally {
                removeBusy = false
            }
        }
    }

    fun acknowledge(event: SecurityTimelineEventDto) {
        if (acking) return
        acking = true
        scope.launch {
            try {
                val session = SessionStore.snapshot(context) ?: return@launch
                Api.acknowledgeSecurityEvent(session.token, event.id)
                if (!SessionStore.isCurrent(context, session.id)) return@launch
                GMHaptics.success()
                openEvent = null
                reload += 1
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                GMHaptics.failure()
                launch { snackbar.showSnackbar(context.getString(R.string.seccenter_ack_failed)) }
            } finally {
                acking = false
            }
        }
    }

    // §3.7: with a cache on screen, offline or a failed refresh disables every
    // destructive action, with the reason said where the action would be.
    val actionsBlocked = online == false || refreshFailed

    Box(Modifier.fillMaxSize().background(LocalPalette.current.bg)) {
        Column(Modifier.fillMaxSize()) {
            CenterTopBar(onBack = onBack)
            // "Loading (with cache)": a thin indeterminate bar under the top bar.
            if (refreshing && overview != null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = colors.primaryText,
                    trackColor = colors.softFill,
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }
            PullToRefreshBox(
                isRefreshing = pullRefreshing,
                onRefresh = { pullRefreshing = true; reload += 1 },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                val shown = overview
                when {
                    shown == null && failed -> CenterError(onRetry = { reload += 1 })
                    shown == null -> CenterSkeleton(reduced = reduced)
                    else -> CenterContent(
                        overview = shown,
                        olderEvents = olderEvents,
                        fetchedAt = fetchedAt,
                        online = online,
                        refreshFailed = refreshFailed,
                        actionsBlocked = actionsBlocked,
                        animateFrom = animateFrom,
                        celebrate = celebrate,
                        removingIds = removingIds,
                        reduced = reduced,
                        showAllDevices = showAllDevices,
                        onToggleAllDevices = { showAllDevices = !showAllDevices },
                        hasOlder = nextCursor != null,
                        loadingOlder = loadingOlder,
                        olderFailed = olderFailed,
                        onLoadOlder = { loadOlder() },
                        onRetry = { reload += 1 },
                        onItem = { onItem(it) },
                        onChangePassword = onChangePassword,
                        onNotMeDevice = { d ->
                            notMeTarget = NotMeTarget(
                                deviceId = d.id,
                                deviceLabel = d.label.ifBlank { null },
                                targetSessions = d.activeSessionCount.coerceAtLeast(1),
                            )
                        },
                        onRemoveDevice = { removeTarget = it },
                        onSignOut = onSignOutThisDevice,
                        onOpenEvent = { openEvent = it },
                        onDeviceKeys = onDeviceKeys,
                        devicesRequester = devicesRequester,
                        activityRequester = activityRequester,
                        firstReviewRequester = firstReviewRequester,
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    removeTarget?.let { device ->
        val label = device.label.ifBlank { stringResource(R.string.seccenter_unknown_device) }
        AlertDialog(
            onDismissRequest = { if (!removeBusy) removeTarget = null },
            title = { Text(stringResource(R.string.seccenter_remove_title, label)) },
            text = { Text(stringResource(R.string.seccenter_remove_body)) },
            confirmButton = {
                TextButton(enabled = !removeBusy && !actionsBlocked, onClick = { remove(device) }) {
                    Text(stringResource(R.string.seccenter_remove_confirm), color = colors.error)
                }
            },
            dismissButton = {
                TextButton(enabled = !removeBusy, onClick = { removeTarget = null }) {
                    Text(stringResource(R.string.seccenter_cancel), color = colors.primaryText)
                }
            },
            containerColor = colors.surface,
        )
    }

    openEvent?.let { event ->
        SecurityEventSheet(
            event = event,
            actionsBlocked = actionsBlocked,
            acking = acking,
            onThisWasMe = { acknowledge(event) },
            onNotMe = {
                openEvent = null
                notMeTarget = NotMeTarget(eventId = event.id, deviceLabel = event.deviceLabel?.ifBlank { null })
            },
            onDismiss = { if (!acking) openEvent = null },
        )
    }

    notMeTarget?.let { target ->
        NotMeConfirmSheet(
            target = target,
            onDismiss = { notMeTarget = null },
            onSecured = { outcome ->
                notMeTarget = null
                onNotMeSecured(outcome)
            },
            onTargetGone = { reload += 1 },
        )
    }

    if (showSecondDeviceHelp) {
        AlertDialog(
            onDismissRequest = { showSecondDeviceHelp = false },
            title = { Text(stringResource(R.string.seccenter_second_device_sheet_title)) },
            text = { Text(stringResource(R.string.seccenter_second_device_sheet_body)) },
            confirmButton = {
                TextButton(onClick = { showSecondDeviceHelp = false }) {
                    Text(stringResource(R.string.seccenter_got_it), color = colors.primaryText)
                }
            },
            containerColor = colors.surface,
        )
    }
}

// ── Layout ──────────────────────────────────────────────────────────────────

@Composable
private fun CenterTopBar(onBack: () -> Unit) {
    val colors = secColors()
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.mail_common_back),
                tint = colors.textPrimary,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.seccenter_title),
            style = Title,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
    }
}

@Composable
private fun CenterContent(
    overview: SecurityOverviewResponse,
    olderEvents: List<SecurityTimelineEventDto>,
    fetchedAt: Long,
    online: Boolean?,
    refreshFailed: Boolean,
    actionsBlocked: Boolean,
    animateFrom: Int?,
    celebrate: Boolean,
    removingIds: Set<String>,
    reduced: Boolean,
    showAllDevices: Boolean,
    onToggleAllDevices: () -> Unit,
    hasOlder: Boolean,
    loadingOlder: Boolean,
    olderFailed: Boolean,
    onLoadOlder: () -> Unit,
    onRetry: () -> Unit,
    onItem: (SafetyChecklistEntry) -> Unit,
    onChangePassword: () -> Unit,
    onNotMeDevice: (SecurityDeviceDto) -> Unit,
    onRemoveDevice: (SecurityDeviceDto) -> Unit,
    onSignOut: () -> Unit,
    onOpenEvent: (SecurityTimelineEventDto) -> Unit,
    onDeviceKeys: (() -> Unit)?,
    devicesRequester: BringIntoViewRequester,
    activityRequester: BringIntoViewRequester,
    firstReviewRequester: BringIntoViewRequester,
) {
    val context = LocalContext.current
    val now = remember(fetchedAt, overview) { System.currentTimeMillis() }
    val smallestWidth = LocalConfiguration.current.smallestScreenWidthDp
    val devices = remember(overview, fetchedAt, smallestWidth) {
        val sorted = sortDevices(overview.devices)
        // §3.7 "Devices: never empty": the current device is always present.
        if (sorted.any { it.current }) sorted
        else listOf(localDevice(fetchedAt, tablet = smallestWidth >= 600)) + sorted
    }
    val events = remember(overview, olderEvents) {
        (overview.events + olderEvents).distinctBy { it.id }
            .sortedByDescending { parseInstantMillis(it.occurredAt) ?: Long.MIN_VALUE }
    }
    val side = if (gmIsNarrowWindow()) 12.dp else 16.dp

    val header: @Composable () -> Unit = {
        SafetyScoreHeader(
            overview = overview,
            loading = false,
            onItem = onItem,
            animateFrom = animateFrom,
            celebrate = celebrate,
            featureTip = true,
        )
    }
    val banners: @Composable () -> Unit = {
        if (overview.account?.passwordChangeRequired == true) {
            Spacer(Modifier.height(12.dp))
            PasswordChangeRequiredBanner(onChangePassword = onChangePassword)
        }
        if (online == false || refreshFailed) {
            Spacer(Modifier.height(12.dp))
            StaleBanner(
                offline = online == false,
                time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(fetchedAt.takeIf { it > 0 } ?: now)),
                onRetry = onRetry,
            )
        }
    }
    val devicesSection: @Composable () -> Unit = {
        Column(Modifier.bringIntoViewRequester(devicesRequester)) {
            CenterSectionLabel(stringResource(R.string.seccenter_section_devices, devices.size))
            val shownDevices = if (showAllDevices || devices.size <= VISIBLE_DEVICES) devices else devices.take(VISIBLE_DEVICES)
            shownDevices.forEach { device ->
                key(device.id) {
                    AnimatedVisibility(
                        visible = device.id !in removingIds,
                        enter = EnterTransition.None,
                        exit = if (reduced) ExitTransition.None
                        else shrinkVertically(tween(220)) + fadeOut(tween(220)),
                    ) {
                        Column {
                            DeviceCard(
                                device = device,
                                isLocal = device.id == LOCAL_DEVICE_ID,
                                actionsBlocked = actionsBlocked,
                                now = now,
                                onNotMe = { onNotMeDevice(device) },
                                onRemove = { onRemoveDevice(device) },
                                onSignOut = onSignOut,
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
            if (devices.size > VISIBLE_DEVICES) {
                TextLink(
                    if (showAllDevices) stringResource(R.string.seccenter_show_fewer_devices)
                    else stringResource(R.string.seccenter_show_all_devices, devices.size),
                    onToggleAllDevices,
                )
            }
        }
    }
    val activitySection: @Composable () -> Unit = {
        Column(Modifier.bringIntoViewRequester(activityRequester)) {
            CenterSectionLabel(stringResource(R.string.seccenter_section_activity))
            if (events.isEmpty()) {
                EventsEmpty()
            } else {
                Timeline(events = events, firstReviewRequester = firstReviewRequester, onOpen = onOpenEvent)
                if (hasOlder) {
                    Spacer(Modifier.height(4.dp))
                    TextLink(
                        if (loadingOlder) stringResource(R.string.seccenter_loading_older)
                        else stringResource(R.string.seccenter_show_older_activity),
                        { if (!loadingOlder) onLoadOlder() },
                    )
                    if (olderFailed) {
                        Text(stringResource(R.string.seccenter_older_failed), style = Caption, color = secColors().error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
        }
    }
    val footer: @Composable () -> Unit = { CenterFooter(onDeviceKeys) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Two columns on a tablet / unfolded foldable / desktop window (§3.1):
        // devices on the left (360–480), the timeline on the right, the score
        // header spanning both. Everything else is the phone's one column.
        // Captured here: the nested Column below opens its own scope, where the
        // BoxWithConstraints receiver's maxWidth is no longer implicitly available.
        val boxMaxWidth = maxWidth
        val twoColumn = boxMaxWidth >= 840.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier
                    .gmMaxWidth(if (twoColumn) 1120.dp else GMLayout.Readable)
                    .padding(horizontal = side)
                    .padding(top = 8.dp, bottom = 32.dp),
            ) {
                header()
                banners()
                if (twoColumn) {
                    val left = (boxMaxWidth * 0.4f).coerceIn(360.dp, 480.dp)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.width(left)) { devicesSection() }
                        Spacer(Modifier.width(24.dp))
                        Column(Modifier.weight(1f)) { activitySection() }
                    }
                } else {
                    devicesSection()
                    activitySection()
                }
                footer()
            }
        }
    }
}

@Composable
private fun CenterSectionLabel(text: String) {
    val colors = secColors()
    Text(
        text.uppercase(Locale.getDefault()),
        style = Micro,
        color = colors.textSecondary,
        modifier = Modifier
            .padding(top = 24.dp, bottom = 10.dp, start = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    val colors = secColors()
    Text(
        text,
        style = CaptionMedium,
        color = colors.primaryText,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 4.dp, vertical = 12.dp),
    )
}

@Composable
private fun CenterFooter(onDeviceKeys: (() -> Unit)?) {
    val colors = secColors()
    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 4.dp)) {
        Icon(Icons.Rounded.Shield, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.seccenter_footer), style = Caption, color = colors.textSecondary)
    }
    if (onDeviceKeys != null) {
        Spacer(Modifier.height(8.dp))
        SecurityRow(
            icon = Icons.Rounded.PhonelinkLock,
            title = stringResource(R.string.seccenter_device_keys_link),
            subtitle = stringResource(R.string.seccenter_footer_link_hint),
            onClick = onDeviceKeys,
        )
    }
}

/** §3.7 "Error / offline (with cache)": `warningContainer`, the time of the cache, Retry. */
@Composable
private fun StaleBanner(offline: Boolean, time: String, onRetry: () -> Unit) {
    val colors = secColors()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.warningContainer)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (offline) stringResource(R.string.seccenter_stale_offline, time)
            else stringResource(R.string.seccenter_stale_error, time),
            style = Caption,
            color = colors.warning,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(stringResource(R.string.seccenter_retry), style = CaptionMedium, color = colors.warning)
        }
    }
}

// ── Loading / error / empty (§3.7) ──────────────────────────────────────────

/** The §3.7 shimmer: a 1200 ms linear sweep over `softFill`; static under reduce motion. */
@Composable
private fun rememberShimmer(reduced: Boolean): Brush {
    val colors = secColors()
    if (reduced) return SolidColor(colors.softFill)
    val transition = rememberInfiniteTransition(label = "seccenter-shimmer")
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "seccenter-shimmer-x",
    )
    val span = 1600f
    val start = -span + x * span * 2f
    return Brush.linearGradient(
        listOf(colors.softFill, colors.surface, colors.softFill),
        start = Offset(start, 0f),
        end = Offset(start + span / 2f, 0f),
    )
}

@Composable
private fun CenterSkeleton(reduced: Boolean) {
    val colors = secColors()
    val brush = rememberShimmer(reduced)
    val label = stringResource(R.string.seccenter_loading)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .gmMaxWidth(GMLayout.Readable)
            .padding(16.dp)
            .clearAndSetSemantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surface).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(96.dp)) {
                val strokePx = 10.dp.toPx()
                drawCircle(brush, radius = (size.minDimension - strokePx) / 2f, style = Stroke(strokePx))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(RoundedCornerShape(6.dp)).background(brush))
                Box(Modifier.fillMaxWidth(0.4f).height(10.dp).clip(RoundedCornerShape(6.dp)).background(brush))
            }
        }
        Spacer(Modifier.height(36.dp))
        repeat(2) {
            Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(20.dp)).background(brush))
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(24.dp))
        repeat(4) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(brush))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(0.5f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush))
                    Box(Modifier.fillMaxWidth(0.8f).height(10.dp).clip(RoundedCornerShape(6.dp)).background(brush))
                }
            }
        }
    }
}

@Composable
private fun CenterError(onRetry: () -> Unit) {
    val colors = secColors()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .gmMaxWidth(GMLayout.Form)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        com.grapsee.grapmail.ui.artwork.Artwork("ill_sec_sessions", modifier = Modifier.width(160.dp).aspectRatio(2f))
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.seccenter_error_title),
            style = Title,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.seccenter_error_body), style = Caption, color = colors.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        OutlineButton(stringResource(R.string.seccenter_try_again), onRetry, fill = false)
    }
}

@Composable
private fun EventsEmpty() {
    val colors = secColors()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        com.grapsee.grapmail.ui.artwork.Artwork("ill_sec_activity", modifier = Modifier.width(160.dp).aspectRatio(2f))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.seccenter_events_empty_title), style = Title, color = colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.seccenter_events_empty_body), style = Caption, color = colors.textSecondary, textAlign = TextAlign.Center)
    }
}

// ── Device card (§3.2) ──────────────────────────────────────────────────────

internal const val LOCAL_DEVICE_ID = "local-this-device"

/** §3.7: the server omitted the current device, so this phone describes itself. */
private fun localDevice(fetchedAt: Long, tablet: Boolean) = SecurityDeviceDto(
    id = LOCAL_DEVICE_ID,
    kind = "app",
    platform = "android",
    formFactor = if (tablet) "tablet" else "phone",
    label = android.os.Build.MODEL.orEmpty(),
    current = true,
    lastActiveAt = Instant.ofEpochMilli(fetchedAt.takeIf { it > 0 } ?: System.currentTimeMillis()).toString(),
)

private fun sortDevices(list: List<SecurityDeviceDto>): List<SecurityDeviceDto> =
    list.sortedWith(
        compareByDescending<SecurityDeviceDto> { it.current }
            .thenByDescending { parseInstantMillis(it.lastActiveAt) ?: Long.MIN_VALUE },
    )

private fun deviceIcon(device: SecurityDeviceDto): ImageVector = when {
    device.formFactor == "phone" -> Icons.Rounded.Smartphone
    device.formFactor == "tablet" -> Icons.Rounded.TabletAndroid
    device.formFactor == "desktop" || device.kind == "browser" -> Icons.Rounded.Laptop
    else -> Icons.Rounded.Devices
}

@Composable
private fun deviceSubtitle(device: SecurityDeviceDto): String = when {
    device.kind == "app" && device.platform == "android" -> stringResource(R.string.seccenter_subtitle_android)
    device.kind == "app" && device.platform == "ios" -> stringResource(R.string.seccenter_subtitle_ios)
    !device.browser.isNullOrBlank() && !device.os.isNullOrBlank() ->
        stringResource(R.string.seccenter_subtitle_browser, device.browser, device.os)
    else -> device.browser?.takeIf { it.isNotBlank() } ?: device.os.orEmpty()
}

/** §3.2 last-active wording, localized through ICU's relative-time formatter. */
@Composable
private fun lastActiveText(device: SecurityDeviceDto, now: Long): String? {
    if (device.current) return stringResource(R.string.seccenter_active_now)
    val at = parseInstantMillis(device.lastActiveAt) ?: return null
    val age = (now - at).coerceAtLeast(0L)
    return when {
        age < 2 * MINUTE_MS -> stringResource(R.string.seccenter_active_now)
        age < 7 * DAY_MS -> stringResource(R.string.seccenter_active_ago, relativeAgo(age))
        else -> stringResource(R.string.seccenter_last_active_on, mediumDate(at))
    }
}

private fun relativeAgo(age: Long): String {
    val formatter = android.icu.text.RelativeDateTimeFormatter.getInstance(
        android.icu.util.ULocale.getDefault(),
        null,
        android.icu.text.RelativeDateTimeFormatter.Style.SHORT,
        android.icu.text.DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE,
    )
    val (quantity, unit) = when {
        age < HOUR_MS -> (age / MINUTE_MS).coerceAtLeast(1L) to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MINUTES
        age < DAY_MS -> (age / HOUR_MS) to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.HOURS
        else -> (age / DAY_MS) to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS
    }
    return formatter.format(quantity.toDouble(), android.icu.text.RelativeDateTimeFormatter.Direction.LAST, unit)
}

private fun mediumDate(millis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private fun fullDateTime(millis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** ISO-8601 (`…Z` or with an offset) to epoch millis; null for blank or unreadable. */
internal fun parseInstantMillis(iso: String?): Long? {
    val s = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
}

@Composable
private fun Pill(text: String, background: Color, ink: Color, icon: ImageVector? = null) {
    Row(
        Modifier
            .heightIn(min = 24.dp)
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = CaptionMedium, color = ink)
    }
}

@Composable
private fun DeviceCard(
    device: SecurityDeviceDto,
    isLocal: Boolean,
    actionsBlocked: Boolean,
    now: Long,
    onNotMe: () -> Unit,
    onRemove: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = secColors()
    var menu by remember { mutableStateOf(false) }
    val label = device.label.ifBlank { stringResource(R.string.seccenter_unknown_device) }
    val subtitle = deviceSubtitle(device)
    val thisLabel = stringResource(
        if (device.kind == "browser") R.string.seccenter_badge_this_browser else R.string.seccenter_badge_this_device,
    )
    val trustedLabel = stringResource(R.string.seccenter_badge_trusted)
    val badges = listOfNotNull(thisLabel.takeIf { device.current }, trustedLabel.takeIf { device.trusted })
    val location = device.location?.label?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.seccenter_location_unavailable)
    val lastActive = lastActiveText(device, now)
    val firstSeen = parseInstantMillis(device.firstSeenAt)?.let { stringResource(R.string.seccenter_first_seen, mediumDate(it)) }
    val meta = listOfNotNull(lastActive, firstSeen).joinToString(" · ")
    val description = listOf(label, subtitle, badges.joinToString(", "), location, lastActive.orEmpty())
        .filter { it.isNotBlank() }
        .joinToString(", ")

    // "This wasn't me" is never offered on the current device; the server
    // refuses it too (409 CANNOT_TARGET_CURRENT).
    val canNotMe = !device.current && !isLocal && device.notMeAllowed
    val canRemove = !device.current && !isLocal && device.removal != null
    val notMeLabel = stringResource(R.string.seccenter_menu_not_me)
    val removeLabel = stringResource(R.string.seccenter_menu_remove)
    val signOutLabel = stringResource(R.string.seccenter_menu_sign_out)
    val actions = buildList {
        if (device.current) add(CustomAccessibilityAction(signOutLabel) { onSignOut(); true })
        if (canNotMe && !actionsBlocked) add(CustomAccessibilityAction(notMeLabel) { onNotMe(); true })
        if (canRemove && !actionsBlocked) add(CustomAccessibilityAction(removeLabel) { onRemove(); true })
    }
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(1.dp, shape)
            .clip(shape)
            .background(colors.surface)
            .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clearAndSetSemantics {
                    contentDescription = description
                    if (actions.isNotEmpty()) customActions = actions
                },
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(colors.softFill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(deviceIcon(device), contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = Title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = Caption, color = colors.textSecondary)
                if (badges.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (device.current) Pill(thisLabel, colors.primaryContainer, colors.onPrimaryContainer)
                        if (device.trusted) Pill(trustedLabel, colors.successContainer, colors.success, Icons.Rounded.VerifiedUser)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(location, style = Caption, color = colors.textSecondary)
                }
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(meta, style = Caption, color = colors.textSecondary)
                }
                if (isLocal) {
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.seccenter_local_device_note), style = Caption, color = colors.textTertiary)
                }
            }
        }
        if (device.current || canNotMe || canRemove) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.seccenter_more_options_for, label),
                        tint = colors.textSecondary,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (device.current) {
                        DropdownMenuItem(
                            text = { Text(signOutLabel, color = colors.textPrimary) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, tint = colors.textSecondary) },
                            onClick = { menu = false; onSignOut() },
                        )
                    }
                    if (canNotMe) {
                        DropdownMenuItem(
                            text = { Text(notMeLabel, color = if (actionsBlocked) colors.textTertiary else colors.error) },
                            leadingIcon = {
                                Icon(Icons.Rounded.GppMaybe, contentDescription = null,
                                    tint = if (actionsBlocked) colors.textTertiary else colors.error)
                            },
                            enabled = !actionsBlocked,
                            onClick = { menu = false; onNotMe() },
                        )
                    }
                    if (canRemove) {
                        DropdownMenuItem(
                            text = { Text(removeLabel, color = if (actionsBlocked) colors.textTertiary else colors.textPrimary) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = colors.textSecondary) },
                            enabled = !actionsBlocked,
                            onClick = { menu = false; onRemove() },
                        )
                    }
                    if (actionsBlocked && (canNotMe || canRemove)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.seccenter_needs_connection), style = Caption, color = colors.textSecondary) },
                            enabled = false,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}

// ── Timeline (§3.3) ─────────────────────────────────────────────────────────

private enum class Tone { Soft, Primary, Success, Warning, Error }

private fun SecurityTimelineEventDto.needsReview(): Boolean =
    !reported && !acknowledged && action in setOf("ALERT", "STEP_UP", "BLOCK")

private fun eventIcon(type: String): ImageVector = when (type) {
    "signin" -> Icons.AutoMirrored.Rounded.Login
    "signin_new_device" -> Icons.Rounded.AddToHomeScreen
    "signin_suspicious" -> Icons.Rounded.GppMaybe
    "signin_blocked" -> Icons.Rounded.Block
    "password_changed" -> Icons.Rounded.VpnKey
    "passkey_added" -> Icons.Rounded.Fingerprint
    "passkey_removed" -> Icons.Rounded.PersonRemove
    "two_step_enabled" -> Icons.Rounded.VerifiedUser
    "two_step_disabled" -> Icons.Rounded.RemoveModerator
    "recovery_requested", "recovery_used" -> Icons.Rounded.Support
    "recovery_codes_generated" -> Icons.Rounded.Password
    "device_added" -> Icons.Rounded.PhonelinkLock
    "device_removed" -> Icons.Rounded.PhonelinkErase
    "session_signed_out" -> Icons.AutoMirrored.Rounded.Logout
    "not_me_reported" -> Icons.Rounded.Flag
    "account_created" -> Icons.Rounded.AutoAwesome
    else -> Icons.Rounded.Shield
}

/** §3.3 disc colour: the type's default, `high` risk always error, `medium` warning unless already error. */
private fun eventTone(type: String, risk: String?): Tone {
    val base = when (type) {
        "signin_new_device", "account_created" -> Tone.Primary
        "signin_suspicious", "two_step_disabled", "recovery_requested", "recovery_used" -> Tone.Warning
        "signin_blocked", "not_me_reported" -> Tone.Error
        "passkey_added", "two_step_enabled" -> Tone.Success
        else -> Tone.Soft
    }
    return when {
        risk == "high" -> Tone.Error
        risk == "medium" && base != Tone.Error -> Tone.Warning
        else -> base
    }
}

private fun toneColors(tone: Tone, colors: SecColors): Pair<Color, Color> = when (tone) {
    Tone.Soft -> colors.softFill to colors.textSecondary
    Tone.Primary -> colors.primaryContainer to colors.onPrimaryContainer
    Tone.Success -> colors.successContainer to colors.success
    Tone.Warning -> colors.warningContainer to colors.warning
    Tone.Error -> colors.errorContainer to colors.error
}

@Composable
internal fun securityEventTitle(type: String): String = stringResource(
    when (type) {
        "signin" -> R.string.seccenter_event_signin
        "signin_new_device" -> R.string.seccenter_event_signin_new_device
        "signin_suspicious" -> R.string.seccenter_event_signin_suspicious
        "signin_blocked" -> R.string.seccenter_event_signin_blocked
        "password_changed" -> R.string.seccenter_event_password_changed
        "passkey_added" -> R.string.seccenter_event_passkey_added
        "passkey_removed" -> R.string.seccenter_event_passkey_removed
        "two_step_enabled" -> R.string.seccenter_event_two_step_enabled
        "two_step_disabled" -> R.string.seccenter_event_two_step_disabled
        "recovery_requested" -> R.string.seccenter_event_recovery_requested
        "recovery_used" -> R.string.seccenter_event_recovery_used
        "recovery_codes_generated" -> R.string.seccenter_event_recovery_codes_generated
        "device_added" -> R.string.seccenter_event_device_added
        "device_removed" -> R.string.seccenter_event_device_removed
        "session_signed_out" -> R.string.seccenter_event_session_signed_out
        "not_me_reported" -> R.string.seccenter_event_not_me_reported
        "account_created" -> R.string.seccenter_event_account_created
        else -> R.string.seccenter_event_unknown
    },
)

@Composable
private fun Timeline(
    events: List<SecurityTimelineEventDto>,
    firstReviewRequester: BringIntoViewRequester,
    onOpen: (SecurityTimelineEventDto) -> Unit,
) {
    val colors = secColors()
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val groups = remember(events) {
        events.groupBy { e -> parseInstantMillis(e.occurredAt)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } }
            .entries.toList()
    }
    val firstReviewId = remember(events) { events.firstOrNull { it.needsReview() }?.id }
    val dayPattern = remember {
        DateTimeFormatter.ofPattern(
            android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd"),
            Locale.getDefault(),
        )
    }
    val todayLabel = stringResource(R.string.seccenter_day_today)
    val yesterdayLabel = stringResource(R.string.seccenter_day_yesterday)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        groups.forEach { (day, list) ->
            if (day != null) {
                Text(
                    when (day) {
                        today -> todayLabel
                        today.minusDays(1) -> yesterdayLabel
                        else -> dayPattern.format(day)
                    },
                    style = CaptionMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
                )
            }
            list.forEachIndexed { index, event ->
                EventRow(
                    event = event,
                    isFirst = index == 0,
                    isLast = index == list.lastIndex,
                    onClick = { onOpen(event) },
                    modifier = if (event.id == firstReviewId) Modifier.bringIntoViewRequester(firstReviewRequester) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun EventRow(
    event: SecurityTimelineEventDto,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = secColors()
    val (disc, ink) = toneColors(eventTone(event.type, event.risk), colors)
    val time = parseInstantMillis(event.occurredAt)?.let {
        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it))
    }
    val detail = listOfNotNull(
        event.deviceLabel?.takeIf { it.isNotBlank() },
        event.location?.label?.takeIf { it.isNotBlank() },
        time,
    ).joinToString(" · ")
    val connector = colors.border
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            // The 2 dp connector joining the discs, drawn behind the column of discs.
            .drawBehind {
                val x = 16.dp.toPx().let { if (layoutDirection == LayoutDirection.Rtl) size.width - it else it }
                val mid = size.height / 2f
                val top = if (isFirst) mid else 0f
                val bottom = if (isLast) mid else size.height
                if (bottom > top) {
                    drawLine(connector, Offset(x, top), Offset(x, bottom), strokeWidth = 2.dp.toPx())
                }
            }
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(disc), contentAlignment = Alignment.Center) {
            Icon(eventIcon(event.type), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(securityEventTitle(event.type), style = BodyMedium, color = colors.textPrimary)
            if (detail.isNotBlank()) Text(detail, style = Caption, color = colors.textSecondary)
        }
        when {
            event.reported -> {
                Spacer(Modifier.width(8.dp))
                Pill(stringResource(R.string.seccenter_chip_reported), colors.errorContainer, colors.error)
            }
            event.needsReview() -> {
                Spacer(Modifier.width(8.dp))
                Pill(stringResource(R.string.seccenter_chip_review), colors.warningContainer, colors.warning)
            }
        }
    }
}

/** §3.3 event sheet: when / device / location / approximate network, then the actions. */
@Composable
private fun SecurityEventSheet(
    event: SecurityTimelineEventDto,
    actionsBlocked: Boolean,
    acking: Boolean,
    onThisWasMe: () -> Unit,
    onNotMe: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = secColors()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val notRecorded = stringResource(R.string.seccenter_sheet_not_recorded)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = GMLayout.Form,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                securityEventTitle(event.type),
                style = H2,
                color = colors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            DetailRow(
                Icons.Rounded.Schedule,
                stringResource(R.string.seccenter_sheet_when),
                parseInstantMillis(event.occurredAt)?.let { fullDateTime(it) } ?: notRecorded,
            )
            DetailRow(
                Icons.Rounded.Devices,
                stringResource(R.string.seccenter_sheet_device),
                event.deviceLabel?.takeIf { it.isNotBlank() } ?: notRecorded,
            )
            DetailRow(
                Icons.Rounded.LocationOn,
                stringResource(R.string.seccenter_sheet_location),
                event.location?.label?.takeIf { it.isNotBlank() } ?: stringResource(R.string.seccenter_location_unavailable),
            )
            event.ipMasked?.takeIf { it.isNotBlank() }?.let {
                DetailRow(Icons.Rounded.Router, stringResource(R.string.seccenter_sheet_network), it)
            }
            Spacer(Modifier.height(18.dp))
            if (event.notMeAllowed) {
                OutlineButton(
                    stringResource(R.string.seccenter_this_was_me),
                    onClick = { if (!acking) onThisWasMe() },
                )
                Spacer(Modifier.height(10.dp))
                ErrorFillButton(
                    text = stringResource(R.string.seccenter_menu_not_me),
                    enabled = !actionsBlocked && !acking,
                    onClick = onNotMe,
                )
                if (actionsBlocked) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.seccenter_needs_connection), style = Caption, color = colors.textSecondary)
                }
            } else {
                OutlineButton(stringResource(R.string.seccenter_close), onClick = onDismiss)
            }
        }
    }
}

// ── The Account Security hub's header (§4.3 A) ─────────────────────────────

/**
 * The safety-score header at the top of Account Security, plus the row that
 * opens the Security Center. Its own read (cache first, refetch on resume),
 * so the hub's profile load is untouched. A failed first read draws nothing:
 * the hub already has its own failure states for the profile.
 */
@Composable
fun SecurityHubSafetyHeader(
    onOpenCenter: () -> Unit,
    onAction: (SafetyAction) -> Unit,
) {
    val context = LocalContext.current
    var overview by remember { mutableStateOf<SecurityOverviewResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var animateFrom by remember { mutableStateOf<Int?>(null) }
    var celebrate by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload += 1 }
    LaunchedEffect(reload) {
        if (reload == 0) return@LaunchedEffect
        val session = SessionStore.snapshot(context) ?: run { loading = false; return@LaunchedEffect }
        if (overview == null) overview = SecurityOverviewCache.read(context, session.address)?.overview
        loading = overview == null
        try {
            val fresh = Api.securityOverview(session.token)
            if (!SessionStore.isCurrent(context, session.id)) return@LaunchedEffect
            val previous = overview?.score?.value
            SecurityOverviewCache.publish(context, session.address, fresh)
            animateFrom = previous
            celebrate = fresh.score?.let { SafetyLocalFlags.shouldCelebrate(context, session.address, previous, it.value) } == true
            overview = fresh
            rememberAccountCreatedAt(context, session.address, fresh, session.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep whatever was cached; nothing to add over the hub's own states.
        } finally {
            loading = false
        }
    }
    if (overview == null && !loading) return
    SafetyScoreHeader(
        overview = overview,
        loading = loading,
        onItem = { entry -> onAction(entry.action) },
        animateFrom = animateFrom,
        celebrate = celebrate,
    )
    Spacer(Modifier.height(8.dp))
    SecurityRow(
        icon = Icons.Rounded.Devices,
        title = stringResource(R.string.seccenter_title),
        subtitle = stringResource(R.string.seccenter_hub_row_subtitle),
        onClick = onOpenCenter,
    )
    Spacer(Modifier.height(12.dp))
}

/**
 * The account's creation time, for the nudge's "account older than 24 h" rule
 * (§4.3 B rule 6). Taken from an `account_created` event if the window holds
 * one, else from the security profile the hub already loaded. Stored once.
 */
private fun rememberAccountCreatedAt(
    context: android.content.Context,
    address: String,
    overview: SecurityOverviewResponse,
    sessionId: String,
) {
    if (SafetyLocalFlags.accountCreatedAt(context, address) > 0L) return
    val fromEvent = overview.events.firstOrNull { it.type == "account_created" }?.occurredAt
    val fromProfile = SecurityProfileCache.read(sessionId)?.createdAt
    parseInstantMillis(fromEvent ?: fromProfile)?.let { SafetyLocalFlags.setAccountCreatedAt(context, address, it) }
}

// ── Mailbox: the §3.6 banner and the §4.3 B nudge ───────────────────────────

/** Navigation the mailbox slot needs; provided by the nav graph, null when the feature is off. */
@Immutable
data class SecurityCenterLinks(
    val openCenter: () -> Unit,
    val changePassword: () -> Unit,
)

val LocalSecurityCenterLinks = compositionLocalOf<SecurityCenterLinks?> { null }

enum class MailboxSafetyKind { None, Banner, Nudge }

@Immutable
data class MailboxSafetySlot(
    val kind: MailboxSafetyKind,
    val address: String = "",
    val value: Int = 0,
    val level: String? = null,
    val next: SafetyChecklistEntry? = null,
) {
    val visible: Boolean get() = kind != MailboxSafetyKind.None
}

private val NoSafetySlot = MailboxSafetySlot(MailboxSafetyKind.None)

/** "Dismissing hides it for the rest of the session"; "being shown once sets lastShownAt". */
object SafetyNudgeSession {
    internal val shown = mutableStateMapOf<String, Boolean>()
    internal val dismissed = mutableStateMapOf<String, Boolean>()
}

/**
 * What the mailbox's top slot shows right now. The §3.6 banner wins over the
 * nudge (rule 3). The nudge shows only when every §4.3 B rule holds:
 *  1. score < 60;  2. the cached overview is ≤ 24 h old (the background read
 *  below runs at most once a day);  3. no password banner;  4. no unreviewed
 *  security-alert banner (the mailbox has none today, so nothing to defer to);
 *  5. not shown to this account in the last 7 days;  6. the account is older
 *  than 24 h;  7. not in multi-select, search, or offline.
 * It also waits while the Good-morning card is up, so it never sits above the
 * greeting it belongs below.
 */
@Composable
fun rememberMailboxSafetySlot(
    address: String?,
    inbox: Boolean,
    selecting: Boolean,
    searching: Boolean,
    online: Boolean?,
    morningVisible: Boolean,
): MailboxSafetySlot {
    val context = LocalContext.current
    val links = LocalSecurityCenterLinks.current
    val version by SecurityOverviewCache.version.collectAsState()
    if (links == null || address.isNullOrBlank()) return NoSafetySlot
    LaunchedEffect(address) { backgroundRefresh(context, address) }
    val entry = remember(address, version) { SecurityOverviewCache.read(context, address) } ?: return NoSafetySlot
    val overview = entry.overview
    if (overview.account?.passwordChangeRequired == true) {
        return if (selecting) NoSafetySlot else MailboxSafetySlot(MailboxSafetyKind.Banner, address)
    }
    if (!inbox || selecting || searching || online == false || morningVisible) return NoSafetySlot
    val score = overview.score ?: return NoSafetySlot
    if (score.value >= 60) return NoSafetySlot
    val now = System.currentTimeMillis()
    if (now - entry.fetchedAt > DAY_MS) return NoSafetySlot
    val key = SecurityOverviewCache.accountKey(address)
    if (SafetyNudgeSession.dismissed[key] == true) return NoSafetySlot
    val shownThisSession = SafetyNudgeSession.shown[key] == true
    if (!shownThisSession && now - SafetyLocalFlags.nudgeLastShownAt(context, address) < 7 * DAY_MS) return NoSafetySlot
    val createdAt = SafetyLocalFlags.accountCreatedAt(context, address)
    if (createdAt <= 0L || now - createdAt < DAY_MS) return NoSafetySlot
    val next = remember(overview) { visibleChecklist(overview).firstOrNull { !it.done } } ?: return NoSafetySlot
    return MailboxSafetySlot(MailboxSafetyKind.Nudge, address, score.value, score.level, next)
}

/** The mailbox slot's content: the §3.6 banner, or the nudge card with its slide-out dismissal. */
@Composable
fun MailboxSafetySlotContent(slot: MailboxSafetySlot) {
    val links = LocalSecurityCenterLinks.current ?: return
    when (slot.kind) {
        MailboxSafetyKind.Banner -> PasswordChangeRequiredBanner(
            onChangePassword = links.changePassword,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
        MailboxSafetyKind.Nudge -> {
            val context = LocalContext.current
            val reduced = rememberMotionReduced()
            val key = SecurityOverviewCache.accountKey(slot.address)
            var leaving by remember(key) { mutableStateOf(false) }
            LaunchedEffect(key) {
                if (SafetyNudgeSession.shown[key] != true) {
                    SafetyNudgeSession.shown[key] = true
                    SafetyLocalFlags.markNudgeShown(context, slot.address)
                }
            }
            LaunchedEffect(leaving) {
                if (!leaving) return@LaunchedEffect
                delay(if (reduced) 0L else 220L)
                SafetyNudgeSession.dismissed[key] = true
            }
            val next = slot.next ?: return
            AnimatedVisibility(
                visible = !leaving,
                enter = EnterTransition.None,
                exit = if (reduced) ExitTransition.None else shrinkVertically(tween(220)) + fadeOut(tween(220)),
            ) {
                com.grapsee.grapmail.ui.mailfeatures.SafetyNudgeCard(
                    value = slot.value,
                    level = slot.level,
                    nextStep = safetyItemTitle(next),
                    onImprove = links.openCenter,
                    onDismiss = { leaving = true },
                )
            }
        }
        MailboxSafetyKind.None -> Unit
    }
}

/**
 * The mailbox's own read of the overview: at most once a day (every 15 min
 * while a password change is pending, so the banner can clear after a change
 * made elsewhere). Quiet on failure — the nudge simply does not show.
 */
private suspend fun backgroundRefresh(context: android.content.Context, address: String) {
    val now = System.currentTimeMillis()
    val held = SecurityOverviewCache.read(context, address)
    val pending = held?.overview?.account?.passwordChangeRequired == true
    val gap = if (pending) 15 * MINUTE_MS else DAY_MS
    if (held != null && now - held.fetchedAt < gap) return
    if (now - SafetyLocalFlags.backgroundFetchAt(context, address) < gap) return
    val session = SessionStore.snapshot(context) ?: return
    if (!session.address.equals(address, ignoreCase = true)) return
    SafetyLocalFlags.markBackgroundFetch(context, address, now)
    try {
        val fresh = Api.securityOverview(session.token)
        if (!SessionStore.isCurrent(context, session.id)) return
        SecurityOverviewCache.publish(context, session.address, fresh)
        rememberAccountCreatedAt(context, session.address, fresh, session.id)
        if (SafetyLocalFlags.accountCreatedAt(context, session.address) <= 0L) {
            // Once per account: the profile carries `createdAt` when no event does.
            Api.profile(session.token).profile?.createdAt
                ?.let(::parseInstantMillis)
                ?.let { SafetyLocalFlags.setAccountCreatedAt(context, session.address, it) }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        // The nudge is optional; the mailbox never says the background read failed.
    }
}
