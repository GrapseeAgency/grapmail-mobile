package com.grapsee.grapmail.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The four states every data screen shares: loading (a skeleton, not a bare
 * spinner), empty, failed (plain words and a Retry), and offline (a banner over
 * whatever is still on screen).
 *
 * Drawn from [LocalPalette] like every other component here, so light and dark
 * are the same code. Every piece is dumb: none of them fetch. The one piece of
 * state is [NetworkStatus], which is the device's own answer about the network,
 * read once per process and kept current by the system callback.
 *
 * Copy lives in `res/values/strings_states.xml` (prefix `states_`).
 */

// ── the device's network, watched rather than inferred ─────────────────────

/**
 * One process-wide reading of whether the device has a path to the internet.
 *
 * `null` means no reading has landed, which draws no banner: "offline" before
 * the system has spoken is a claim about a network nobody checked (the same
 * rule `MailboxViewModel.watchNetwork` keeps for the inbox). Registered once on
 * the application context and kept for the life of the process, so screens can
 * ask freely without stacking callbacks on the system's per-app limit.
 */
object NetworkStatus {
    @Volatile
    private var state: MutableStateFlow<Boolean?>? = null

    fun online(context: Context): StateFlow<Boolean?> {
        state?.let { return it.asStateFlow() }
        synchronized(this) {
            state?.let { return it.asStateFlow() }
            val flow = MutableStateFlow<Boolean?>(null)
            state = flow
            val manager = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return flow.asStateFlow()
            fun current(): Boolean? = runCatching {
                manager.activeNetwork
                    ?.let { manager.getNetworkCapabilities(it) }
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }.getOrNull()
            flow.value = current()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    flow.value = true
                }

                override fun onLost(network: Network) {
                    // A loss that leaves another network standing (wifi off,
                    // cell on) is answered by what is active now.
                    flow.value = current() == true
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    flow.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            }
            runCatching { manager.registerDefaultNetworkCallback(callback) }
            return flow.asStateFlow()
        }
    }
}

/** The current network reading for a composable: true, false, or null (unknown). */
@Composable
fun rememberOnline(): Boolean? {
    val context = LocalContext.current
    val flow = remember(context) { NetworkStatus.online(context) }
    val online by flow.collectAsState()
    return online
}

// ── offline banner ─────────────────────────────────────────────────────────

/**
 * The offline banner. Draws only when the device has said it is offline; the
 * content under it stays on screen. [online] defaults to the shared reading,
 * and a screen that already holds its own (the mailbox's view model) passes it.
 */
@Composable
fun OfflineBanner(
    modifier: Modifier = Modifier,
    online: Boolean? = rememberOnline(),
    text: String = stringResource(R.string.states_offline_banner),
) {
    if (online != false) return
    val palette = LocalPalette.current
    Row(
        // Capped and centred on wide windows (tablets, desktop windows), so the
        // banner lines up with the capped content beneath it.
        modifier.gmMaxWidth(GMLayout.Readable).padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)).background(palette.warnChipBg)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag("states-offline"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = palette.warnInk,
            modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, lineHeight = 16.sp, color = palette.warnInk,
            modifier = Modifier.weight(1f))
    }
}

// ── skeleton loading ───────────────────────────────────────────────────────

/** The skeleton's pulse: one alpha every bone on the screen shares. */
@Composable
private fun skeletonAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    return alpha
}

/** One placeholder bar. */
@Composable
fun SkeletonBone(modifier: Modifier = Modifier, height: Dp = 12.dp, alpha: Float = 1f) {
    val palette = LocalPalette.current
    Box(modifier.height(height).alpha(alpha).clip(RoundedCornerShape(6.dp)).background(palette.softFill))
}

/**
 * The first-load placeholder for a list: rows shaped like the rows that will
 * replace them (avatar, two lines). TalkBack hears one sentence, [label], not
 * a dozen unlabeled boxes.
 */
@Composable
fun ListSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 6,
    avatar: Boolean = true,
    label: String = stringResource(R.string.states_loading),
) {
    val alpha = skeletonAlpha()
    Column(
        modifier.fillMaxWidth()
            .clearAndSetSemantics { contentDescription = label }
            .testTag("states-skeleton"),
    ) {
        repeat(rows) { index ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (avatar) {
                    Box(Modifier.size(40.dp).alpha(alpha).clip(CircleShape)
                        .background(LocalPalette.current.softFill))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Varying widths, so the placeholder reads as text rather than a grid.
                    SkeletonBone(Modifier.fillMaxWidth(if (index % 2 == 0) 0.55f else 0.4f), 12.dp, alpha)
                    SkeletonBone(Modifier.fillMaxWidth(if (index % 3 == 0) 0.9f else 0.75f), 10.dp, alpha)
                }
            }
        }
    }
}

/**
 * The first-load placeholder for a reader-style page: a title and paragraph
 * lines. Used where the content is one document rather than rows.
 */
@Composable
fun DetailSkeleton(
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.states_loading),
) {
    val alpha = skeletonAlpha()
    Column(
        modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)
            .clearAndSetSemantics { contentDescription = label }
            .testTag("states-skeleton"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SkeletonBone(Modifier.fillMaxWidth(0.7f), 22.dp, alpha)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).alpha(alpha).clip(CircleShape).background(LocalPalette.current.softFill))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBone(Modifier.fillMaxWidth(0.45f), 12.dp, alpha)
                SkeletonBone(Modifier.fillMaxWidth(0.3f), 10.dp, alpha)
            }
        }
        Spacer(Modifier.height(6.dp))
        listOf(1f, 0.95f, 0.9f, 1f, 0.6f).forEach { width ->
            SkeletonBone(Modifier.fillMaxWidth(width), 11.dp, alpha)
        }
    }
}

// ── empty and error ────────────────────────────────────────────────────────

/** The icon tile, title, body and optional action both states below share. */
@Composable
private fun StateLayout(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    body: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier,
    testTag: String,
) {
    val palette = LocalPalette.current
    val short = gmIsShortWindow()
    Box(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = if (short) 8.dp else 16.dp).testTag(testTag),
        contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A landscape phone or split-screen half has little height: a
            // smaller tile keeps the title and the action in view.
            Box(Modifier.size(if (short) 48.dp else 64.dp).clip(RoundedCornerShape(18.dp)).background(palette.softFill),
                contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(if (short) 10.dp else 16.dp))
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            if (!body.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(body, fontSize = 14.sp, lineHeight = 21.sp, color = palette.textSecondary,
                    textAlign = TextAlign.Center)
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(18.dp))
                // OutlineButton is 54dp tall: comfortably over the 48dp target.
                OutlineButton(actionLabel, onAction, fill = false,
                    modifier = Modifier.testTag("$testTag-action"))
            }
        }
    }
}

/** An empty list: an icon, a sentence, and optionally the way to fill it. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StateLayout(icon, LocalPalette.current.primarySolid, title, body, actionLabel, onAction,
        modifier, "states-empty")
}

/**
 * A load that failed with nothing to show. [message] is the specific reason
 * when there is one; offline replaces the icon and adds the plain-language
 * hint. [onRetry] re-runs the load that failed.
 */
@Composable
fun ErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.states_error_title),
    message: String? = null,
    online: Boolean? = rememberOnline(),
) {
    val palette = LocalPalette.current
    val offline = online == false
    StateLayout(
        icon = if (offline) Icons.Rounded.CloudOff else Icons.Rounded.ErrorOutline,
        iconTint = if (offline) palette.warnInk else palette.error,
        title = title,
        body = if (offline) stringResource(R.string.states_error_offline_body)
        else message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.states_error_body),
        actionLabel = stringResource(R.string.states_retry),
        onAction = onRetry,
        modifier = modifier,
        testTag = "states-error",
    )
}
