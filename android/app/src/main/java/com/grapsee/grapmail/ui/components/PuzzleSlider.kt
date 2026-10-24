package com.grapsee.grapmail.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * GrapMail's own human verification — the slide puzzle from the design,
 * replacing the Telegram gate entirely. A jigsaw piece is dragged along the
 * track; the challenge is met when the piece lands within tolerance of the
 * notch cut into the photo. The target position is randomized per challenge.
 * A genuine interaction test: no passive bypass, no third-party messenger,
 * no phone number.
 */
@Composable
fun PuzzleSlider(
    verified: Boolean,
    onVerified: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var challengeSeed by remember { mutableStateOf(Random.nextInt()) }
    val targetFraction = remember(challengeSeed) { 0.45f + Random(challengeSeed).nextFloat() * 0.35f }

    val drag = remember { Animatable(0f) }
    var failed by remember { mutableStateOf(false) }
    val trackWidth = remember { mutableFloatStateOf(1f) }
    val pieceSizePx = remember { mutableFloatStateOf(0f) }

    fun maxX(): Float = (trackWidth.floatValue - pieceSizePx.floatValue - 12f).coerceAtLeast(1f)
    fun targetX(): Float = maxX() * targetFraction
    val tolerance = 28f

    LaunchedEffect(verified) {
        if (verified) drag.snapTo(targetX())
    }
    LaunchedEffect(challengeSeed) {
        drag.snapTo(0f)
        failed = false
    }

    Column(modifier.fillMaxWidth()) {
        // ── Photo strip with the notch ──────────────────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(14.dp))
        ) {
            Image(
                painter = painterResource(R.drawable.puzzle_photo),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(132.dp),
                contentScale = ContentScale.Crop,
            )
            Canvas(Modifier.matchParentSize()) {
                val side = size.height * 0.62f
                val notchX = maxX() * targetFraction + 6f
                val top = (size.height - side) / 2f
                drawPath(puzzlePath(Offset(notchX, top), side), Color.Black.copy(alpha = 0.55f))
                drawPath(puzzlePath(Offset(notchX, top), side), Color.White.copy(alpha = 0.85f), style = Stroke(width = 2f))
                if (verified) {
                    drawPath(puzzlePath(Offset(notchX, top), side), Brush.linearGradient(listOf(palette.primaryTop, palette.primaryBottom)))
                    drawPath(puzzlePath(Offset(notchX, top), side), palette.success, style = Stroke(width = 3f))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Slider track ────────────────────────────────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .onSizeChanged { trackWidth.floatValue = it.width.toFloat() }
                .clip(RoundedCornerShape(14.dp))
                .background(if (verified) palette.success.copy(alpha = 0.12f) else palette.softFill)
                .pointerInput(verified, challengeSeed) {
                    if (verified) return@pointerInput
                    detectDragGestures(
                        onDragStart = { failed = false },
                        onDragEnd = {
                            if (abs(drag.value - targetX()) <= tolerance) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch { drag.animateTo(targetX(), spring(stiffness = 700f)) }
                                onVerified()
                            } else {
                                failed = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    drag.animateTo(
                                        0f,
                                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 380f),
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            if (abs(drag.value - targetX()) > tolerance) {
                                scope.launch { drag.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
                            }
                        },
                    ) { change, amount ->
                        change.consume()
                        val next = (drag.value + amount.x).coerceIn(0f, maxX())
                        scope.launch { drag.snapTo(next) }
                    }
                }
        ) {
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                if (verified) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.success, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Verified — you're human!", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.success)
                } else {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = palette.textTertiary,
                        modifier = Modifier.size(18.dp).alpha(0.7f),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (failed) "Almost — try again" else "Slide to verify",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (failed) palette.error else palette.textSecondary,
                    )
                }
            }
            // The jigsaw piece handle rides along the track
            Box(
                Modifier
                    .offset {
                        val y = ((56.dp.toPx() - 44.dp.toPx()) / 2f).roundToInt()
                        IntOffset((drag.value + 6f).roundToInt(), y)
                    }
                    .size(44.dp)
                    .onSizeChanged { pieceSizePx.floatValue = it.width.toFloat() }
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (verified) palette.success
                        else Color.Transparent
                    )
            ) {
                Canvas(Modifier.size(30.dp).align(Alignment.Center)) {
                    if (!verified) {
                        drawPath(
                            puzzlePath(Offset(size.width * 0.08f, size.height * 0.08f), size.minDimension * 0.84f),
                            Brush.linearGradient(listOf(palette.primaryTop, palette.primaryBottom)),
                        )
                    } else {
                        drawPath(
                            puzzlePath(Offset(size.width * 0.08f, size.height * 0.08f), size.minDimension * 0.84f),
                            Color.White,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Jigsaw piece outline: rounded square with a right-side tab and a bottom
 * notch — the classic slider-captcha silhouette in the founder's design.
 */
private fun puzzlePath(topLeft: Offset, side: Float): Path {
    val (x, y) = topLeft
    val r = side * 0.14f
    val tab = side * 0.22f
    return Path().apply {
        moveTo(x + r, y)
        lineTo(x + side - r, y)
        quadraticBezierTo(x + side, y, x + side, y + r)
        lineTo(x + side, y + side * 0.45f)
        cubicTo(x + side + tab, y + side * 0.38f, x + side + tab, y + side * 0.62f, x + side, y + side * 0.55f)
        lineTo(x + side, y + side - r)
        quadraticBezierTo(x + side, y + side, x + side - r, y + side)
        lineTo(x + side * 0.55f, y + side)
        cubicTo(x + side * 0.62f, y + side + tab, x + side * 0.38f, y + side + tab, x + side * 0.45f, y + side)
        lineTo(x + r, y + side)
        quadraticBezierTo(x, y + side, x, y + side - r)
        lineTo(x, y + r)
        quadraticBezierTo(x, y, x + r, y)
        close()
    }
}
