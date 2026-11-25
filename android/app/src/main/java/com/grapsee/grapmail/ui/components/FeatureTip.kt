package com.grapsee.grapmail.ui.components

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.grapsee.grapmail.R
import com.grapsee.grapmail.logic.FeatureTipId
import com.grapsee.grapmail.logic.FeatureTipsStore
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.delay

/**
 * First-time feature tips: a small tonal card with an arrow, anchored to the
 * control it describes, with "Got it" and a close X.
 *
 * A plain [Popup] rather than material3's `TooltipBox`/`RichTooltip`: in the
 * BOM this app pins (2024.09.03, material3 1.3) those are experimental, and a
 * persistent tooltip there is dismissed by any outside tap — which would
 * count a stray scroll as "seen it" and lose the tip for good. The popup here
 * is not focusable, so the screen under it keeps working, and only the two
 * buttons (or using the feature) dismiss it.
 *
 * Which tip shows, and when, is [FeatureTipsStore]'s call: one per session,
 * never in the first one, never twice. An anchor only says "this tip lives
 * here" and whether showing it right now is allowed (no sheet, drawer or
 * dialog over it).
 *
 * Never in the CI snapshot host (`--es gmSnapshot <name>`), a launch that
 * passes the `gmNoTips` extra, or an IDE preview.
 */
@Composable
fun FeatureTipAnchor(
    vararg tips: FeatureTipId,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val allowed = rememberFeatureTipsAllowed()
    val active by FeatureTipsStore.active.collectAsState()
    val shown = active?.takeIf { allowed && enabled && it in tips }
    Box(modifier) {
        content()
        if (shown != null) {
            FeatureTipPopup(shown) { FeatureTipsStore.dismiss(context, shown) }
        }
    }
}

/**
 * The mailbox calls this with `ready` = "the list has loaded and has rows".
 * After it has held for a moment the store picks this session's tip (or
 * decides on none). Nothing happens while the first load is still running.
 */
@Composable
fun FeatureTipsSessionEffect(ready: Boolean) {
    val context = LocalContext.current
    val allowed = rememberFeatureTipsAllowed()
    LaunchedEffect(ready, allowed) {
        if (!ready || !allowed) return@LaunchedEffect
        delay(2_500)
        FeatureTipsStore.onMailboxSettled(context)
    }
}

/** False in previews, the CI snapshot host, and launches with `gmNoTips`. */
@Composable
fun rememberFeatureTipsAllowed(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        val intent = context.tipHostActivity()?.intent
        intent == null || (intent.getStringExtra("gmSnapshot") == null && !intent.hasExtra("gmNoTips"))
    }
}

private tailrec fun Context.tipHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.tipHostActivity()
    else -> null
}

private fun tipText(tip: FeatureTipId): Pair<Int, Int> = when (tip) {
    FeatureTipId.Swipe -> R.string.tip_swipe_title to R.string.tip_swipe_body
    FeatureTipId.LongPress -> R.string.tip_long_press_title to R.string.tip_long_press_body
    FeatureTipId.Categories -> R.string.tip_categories_title to R.string.tip_categories_body
    FeatureTipId.Screener -> R.string.tip_screener_title to R.string.tip_screener_body
    FeatureTipId.Accounts -> R.string.tip_accounts_title to R.string.tip_accounts_body
    FeatureTipId.Snooze -> R.string.tip_snooze_title to R.string.tip_snooze_body
    FeatureTipId.SafetyScore -> R.string.tip_safety_score_title to R.string.tip_safety_score_body
}

/** Where the provider put the card: the arrow's x inside it, and which side. */
private class TipPlacement {
    var arrowX by mutableIntStateOf(-1)
    var below by mutableStateOf(true)
}

/**
 * Below the anchor when it fits, above it otherwise; centred on the anchor and
 * kept [marginPx] inside the window. The arrow follows the anchor's centre,
 * never closer than [edgePx] to the card's rounded corners.
 */
private class TipPositionProvider(
    private val placement: TipPlacement,
    private val gapPx: Int,
    private val marginPx: Int,
    private val edgePx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val centerX = (anchorBounds.left + anchorBounds.right) / 2
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = (centerX - popupContentSize.width / 2).coerceIn(marginPx, maxX)
        val belowY = anchorBounds.bottom + gapPx
        val fitsBelow = belowY + popupContentSize.height <= windowSize.height - marginPx
        val y = if (fitsBelow) belowY
            else (anchorBounds.top - gapPx - popupContentSize.height).coerceAtLeast(marginPx)
        val w = popupContentSize.width
        val lo = minOf(edgePx, w / 2)
        val hi = maxOf(w - edgePx, w / 2)
        val arrow = (centerX - x).coerceIn(lo, hi)
        if (placement.below != fitsBelow) placement.below = fitsBelow
        if (placement.arrowX != arrow) placement.arrowX = arrow
        return IntOffset(x, y)
    }
}

@Composable
private fun FeatureTipPopup(tip: FeatureTipId, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val reduced = rememberMotionReduced()
    val placement = remember { TipPlacement() }
    val provider = remember(placement, density) {
        with(density) {
            TipPositionProvider(placement, gapPx = 4.dp.roundToPx(), marginPx = 12.dp.roundToPx(),
                edgePx = 26.dp.roundToPx())
        }
    }
    // Arrives with a short fade and scale; with "Remove animations" on it is
    // simply there (GMMotion.tween snaps).
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) {
        if (reduced) appear.snapTo(1f)
        else appear.animateTo(1f, GMMotion.tween(reduced, GMMotion.MediumMs, GMMotion.EmphasizedDecelerate))
    }
    val (titleRes, bodyRes) = tipText(tip)
    val title = stringResource(titleRes)
    val body = stringResource(bodyRes)
    val arrowWidth = 18.dp
    val arrowHeight = 9.dp
    val arrowHalfPx = with(density) { (arrowWidth / 2).roundToPx() }
    val shape = RoundedCornerShape(16.dp)
    // Never wider than the window minus the provider's 12dp margins (a 320dp
    // phone or a cover screen), and never taller than the window: at large
    // text in a landscape window the card scrolls instead of running off.
    val configuration = LocalConfiguration.current
    val maxTipWidth = minOf(320, configuration.screenWidthDp - 24).coerceAtLeast(200).dp
    val maxCardHeight = (configuration.screenHeightDp - 24 - 9).coerceAtLeast(120).dp

    Popup(
        popupPositionProvider = provider,
        properties = PopupProperties(focusable = false),
    ) {
        Column(
            Modifier
                .widthIn(max = maxTipWidth)
                .graphicsLayer {
                    alpha = appear.value
                    val s = 0.94f + 0.06f * appear.value
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0.5f, if (placement.below) 0f else 1f)
                }
                .semantics { paneTitle = title },
        ) {
            if (placement.below) {
                // The provider measures the arrow from the card's LEFT edge in
                // window pixels, so it is placed absolutely (not mirrored in RTL).
                TipArrow(up = true, xPx = placement.arrowX, halfPx = arrowHalfPx,
                    color = palette.infoFill, width = arrowWidth, height = arrowHeight,
                    modifier = Modifier.align(AbsoluteAlignment.Left))
            }
            Column(
                Modifier
                    .clip(shape)
                    .background(palette.infoFill)
                    .border(1.dp, palette.borderSoft, shape)
                    .heightIn(max = maxCardHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    // Title and body are one TalkBack stop, announced when the
                    // card appears; the two dismiss controls are their own stops.
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(top = 10.dp)
                            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary, modifier = Modifier.semantics { heading() })
                        Text(body, fontSize = 13.sp, color = palette.textSecondary,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.tip_close),
                            tint = palette.textSecondary)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.tip_got_it), color = palette.primarySolid,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            if (!placement.below) {
                TipArrow(up = false, xPx = placement.arrowX, halfPx = arrowHalfPx,
                    color = palette.infoFill, width = arrowWidth, height = arrowHeight,
                    modifier = Modifier.align(AbsoluteAlignment.Left))
            }
        }
    }
}

@Composable
private fun TipArrow(
    up: Boolean,
    xPx: Int,
    halfPx: Int,
    color: Color,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .absoluteOffset { IntOffset((xPx - halfPx).coerceAtLeast(0), 0) }
            .size(width, height),
    ) {
        // Not drawn until the provider has placed the card once.
        if (xPx < 0) return@Canvas
        val path = Path().apply {
            if (up) {
                moveTo(0f, size.height)
                lineTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
            } else {
                moveTo(0f, 0f)
                lineTo(size.width / 2f, size.height)
                lineTo(size.width, 0f)
            }
            close()
        }
        drawPath(path, color)
    }
}
