package com.grapsee.grapmail.ui.screens.mail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.ui.graphics.vector.ImageVector
import com.grapsee.grapmail.ui.components.GMMotion
import com.grapsee.grapmail.ui.components.rememberMotionReduced
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun SwipeableMessageRow(
    leadingTo: String?,
    trailingTo: String?,
    onLeading: () -> Unit,
    onTrailing: () -> Unit,
    modifier: Modifier = Modifier,
    restoresToInbox: Boolean = false,
    /**
     * What each side draws (Settings → Swipe actions, MailSwipePrefs). Null
     * keeps the original pair: archive (or restore, with [restoresToInbox])
     * on the right, trash on the left. The commit threshold, the pop and the
     * row's travel are the same for every action.
     */
    leadingGlyph: SwipeGlyph? = null,
    trailingGlyph: SwipeGlyph? = null,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val archive by rememberUpdatedState(onLeading)
    val trash by rememberUpdatedState(onTrailing)
    var width by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableFloatStateOf(0f) }
    var rawOffset by remember { mutableFloatStateOf(0f) }
    var committing by remember { mutableStateOf(false) }
    var animation by remember { mutableStateOf<Job?>(null) }

    // Drawing only: the commit threshold by the same formula the gesture
    // uses below (which stays the one that decides), so the action behind
    // the row can say "release now" exactly when releasing would commit.
    val reduced = rememberMotionReduced()
    val threshold = with(density) { (width * 0.31f).coerceIn(84.dp.toPx(), 126.dp.toPx()) }
    val arm = if (width > 0f) (abs(offset) / threshold).coerceIn(0f, 1f) else 0f
    val armed = width > 0f && abs(offset) >= threshold
    // One small pop as the drag crosses the threshold; nothing while resting.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(armed) {
        if (armed && !reduced) {
            pop.animateTo(1.18f, tween(90, easing = GMMotion.EmphasizedDecelerate))
            pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium))
        } else {
            pop.snapTo(1f)
        }
    }

    fun release(commit: Boolean) {
        animation?.cancel()
        val direction = offset.sign
        committing = commit
        animation = scope.launch {
            val end = if (commit) direction * width else 0f
            val spec: AnimationSpec<Float> = if (commit) tween(170, easing = CubicBezierEasing(0.2f, 0.65f, 0.3f, 1f))
                else spring(dampingRatio = 0.88f, stiffness = 420f)
            try {
                Animatable(offset).animateTo(end, spec) { offset = value }
            } finally {
                if (commit) {
                    if (direction > 0f) archive() else trash()
                }
            }
            if (commit) {
                delay(75)
                Animatable(offset).animateTo(0f, spring(dampingRatio = 0.88f, stiffness = 420f)) { offset = value }
            }
            rawOffset = 0f
            committing = false
        }
    }

    val actions = Modifier.semantics {
        customActions = listOfNotNull(
            leadingTo?.let { CustomAccessibilityAction(it) { if (committing) false else { archive(); true } } },
            trailingTo?.let { CustomAccessibilityAction(it) { if (committing) false else { trash(); true } } },
        )
    }
    val drag = if (leadingTo != null || trailingTo != null) {
        Modifier.pointerInput(leadingTo, trailingTo, density) {
            val minThreshold = 84.dp.toPx()
            val maxThreshold = 126.dp.toPx()
            detectHorizontalDragGestures(
                onDragStart = {
                    if (!committing) {
                        animation?.cancel()
                        rawOffset = offset
                    }
                },
                onHorizontalDrag = { change, delta ->
                    if (!committing) {
                        change.consume()
                        rawOffset = (rawOffset + delta).coerceIn(
                            if (trailingTo != null) -width else 0f,
                            if (leadingTo != null) width else 0f,
                        )
                        val threshold = (width * 0.31f).coerceIn(minThreshold, maxThreshold)
                        val distance = abs(rawOffset)
                        offset = rawOffset.sign * (distance.coerceAtMost(threshold) +
                            (distance - threshold).coerceAtLeast(0f) * 0.58f)
                    }
                },
                onDragEnd = {
                    if (!committing) release(abs(rawOffset) >= (width * 0.31f).coerceIn(minThreshold, maxThreshold))
                },
                onDragCancel = { if (!committing) release(false) },
            )
        }
    } else Modifier

    Box(modifier.fillMaxWidth().clipToBounds().onSizeChanged { width = it.width.toFloat() }.then(actions).then(drag)) {
        val distance = with(density) { abs(offset).toDp() }
        if (distance > 8.dp) {
            val isTrash = offset < 0
            val glyph = (if (isTrash) trailingGlyph else leadingGlyph)
                ?: when {
                    isTrash -> SwipeGlyph.Trash
                    restoresToInbox -> SwipeGlyph.Restore
                    else -> SwipeGlyph.Archive
                }
            val tones = swipeTones(glyph)
            val reveal = distance - 8.dp
            val progress = ((distance.value - 16f) / 92f).coerceIn(0f, 1f)
            Box(
                Modifier.matchParentSize().padding(horizontal = 4.dp, vertical = 5.dp),
                contentAlignment = if (isTrash) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft,
            ) {
                Box(
                    Modifier.width(reveal).fillMaxHeight().background(
                        // Deepens as the drag nears the commit threshold.
                        lerp(tones.rest, tones.armed, arm),
                        CircleShape,
                    ).clipToBounds(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (reveal >= 24.dp) {
                        when (glyph) {
                            SwipeGlyph.Archive, SwipeGlyph.Restore, SwipeGlyph.Trash ->
                                SwipeActionIcon(glyph == SwipeGlyph.Trash, restore = glyph == SwipeGlyph.Restore,
                                    progress = progress, arm = arm, pop = { pop.value })
                            else -> SwipeVectorIcon(glyph, tones.ink, progress = progress, arm = arm,
                                pop = { pop.value })
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().graphicsLayer { translationX = offset }.background(LocalPalette.current.cardAlt)) {
            content()
        }
    }
}

/**
 * The action a swipe side commits, as drawn behind the row. Archive, Restore
 * and Trash keep their animated hand-drawn glyphs below; the rest are the
 * Material glyphs at the same size, alpha and pop.
 */
internal enum class SwipeGlyph { Archive, Restore, Trash, Read, Unread, Star, Unstar, Snooze, Move }

private class SwipeTones(val rest: Color, val armed: Color, val ink: Color)

private fun swipeTones(glyph: SwipeGlyph): SwipeTones = when (glyph) {
    SwipeGlyph.Archive, SwipeGlyph.Restore -> SwipeTones(Color(0xFFA1E9B8), Color(0xFF6CD993), Color(0xFF113B28))
    SwipeGlyph.Trash -> SwipeTones(Color(0xFFFFB9C4), Color(0xFFFF8A9E), Color(0xFF541A26))
    SwipeGlyph.Read, SwipeGlyph.Unread -> SwipeTones(Color(0xFFB8D4FF), Color(0xFF86B3FF), Color(0xFF0E2A57))
    SwipeGlyph.Star, SwipeGlyph.Unstar -> SwipeTones(Color(0xFFFFE3A1), Color(0xFFFFCC55), Color(0xFF4A3300))
    SwipeGlyph.Snooze -> SwipeTones(Color(0xFFD8CCFF), Color(0xFFB49DFF), Color(0xFF2D1A60))
    SwipeGlyph.Move -> SwipeTones(Color(0xFFA9E8E1), Color(0xFF6FD3C7), Color(0xFF0B3D38))
}

private fun swipeVector(glyph: SwipeGlyph): ImageVector = when (glyph) {
    SwipeGlyph.Read -> Icons.Rounded.MarkEmailRead
    SwipeGlyph.Unread -> Icons.Rounded.MarkEmailUnread
    SwipeGlyph.Star -> Icons.Rounded.Star
    SwipeGlyph.Unstar -> Icons.Rounded.StarBorder
    SwipeGlyph.Snooze -> Icons.Rounded.Snooze
    else -> Icons.Rounded.MoveToInbox
}

@Composable
private fun SwipeVectorIcon(
    glyph: SwipeGlyph,
    ink: Color,
    progress: Float,
    arm: Float,
    pop: () -> Float,
) {
    androidx.compose.material3.Icon(
        swipeVector(glyph), null, tint = ink,
        modifier = Modifier.size(24.dp).graphicsLayer {
            alpha = progress.coerceAtLeast(0.12f)
            scaleX = (0.8f + arm * 0.26f) * pop()
            scaleY = scaleX
        },
    )
}

@Composable
private fun SwipeActionIcon(
    trash: Boolean,
    restore: Boolean,
    progress: Float,
    /** 0..1 toward the commit threshold: the glyph grows with it. */
    arm: Float = progress,
    /** The threshold pop, read at draw time so it never recomposes the row. */
    pop: () -> Float = { 1f },
) {
    val ink = if (trash) Color(0xFF541A26) else Color(0xFF113B28)
    Canvas(Modifier.size(24.dp).graphicsLayer {
        alpha = progress.coerceAtLeast(0.12f)
        scaleX = (0.8f + arm * 0.26f) * pop()
        scaleY = scaleX
    }) {
        val unit = size.width / 24f
        scale(unit, unit, pivot = Offset.Zero) {
            val stroke = Stroke(1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(ink, Offset(x1, y1), Offset(x2, y2), stroke.width, StrokeCap.Round)
            if (trash) {
                drawPath(Path().apply {
                    moveTo(6f, 9f)
                    lineTo(7f, 19f)
                    quadraticTo(7.2f, 21f, 9f, 21f)
                    lineTo(15f, 21f)
                    quadraticTo(16.8f, 21f, 17f, 19f)
                    lineTo(18f, 9f)
                }, ink, style = stroke)
                line(10f, 11f, 10.4f, 17.5f)
                line(14f, 11f, 13.6f, 17.5f)
                rotate(-11f * progress, pivot = Offset(6f, 7f)) {
                    val lift = progress * 0.9f
                    line(4.5f, 7f - lift, 19.5f, 7f - lift)
                    drawPath(Path().apply {
                        moveTo(9f, 7f - lift)
                        lineTo(9f, 5f - lift)
                        quadraticTo(9f, 4f - lift, 10f, 4f - lift)
                        lineTo(14f, 4f - lift)
                        quadraticTo(15f, 4f - lift, 15f, 5f - lift)
                        lineTo(15f, 7f - lift)
                    }, ink, style = stroke)
                }
            } else {
                drawRoundRect(ink, topLeft = Offset(4f, 4f), size = Size(16f, 5f),
                    cornerRadius = CornerRadius(1.45f), style = stroke)
                drawPath(Path().apply {
                    moveTo(5.5f, 9f)
                    lineTo(5.5f, 18.5f)
                    quadraticTo(5.5f, 20f, 7f, 20f)
                    lineTo(17f, 20f)
                    quadraticTo(18.5f, 20f, 18.5f, 18.5f)
                    lineTo(18.5f, 9f)
                }, ink, style = stroke)
                val shift = 1.2f * (1f - progress)
                val tip = if (restore) 11.8f + shift else 16.5f - shift
                val shoulder = tip + if (restore) 2.3f else -2.3f
                line(12f, tip + if (restore) 4.7f else -4.7f, 12f, tip)
                line(9.7f, shoulder, 12f, tip)
                line(12f, tip, 14.3f, shoulder)
            }
        }
    }
}
