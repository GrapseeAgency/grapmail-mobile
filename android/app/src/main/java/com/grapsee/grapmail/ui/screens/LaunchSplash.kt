package com.grapsee.grapmail.ui.screens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The launch splash, drawn the way the brand's web loader is: the envelope mark
 * centered, and "Grapsee" beneath as a nine-slice shimmer — every slice carries
 * the whole word at its own scale and opacity, clipped to one vertical band,
 * with a white→grey boundary that rides the sweep. The sizes bump toward the
 * middle (fisheye), exactly like the CSS the brand supplies.
 *
 * Shown once per process, on top of the navigation, while the session restores
 * underneath; the parent fades it out when [onFinished] fires.
 */
@Composable
fun LaunchSplash(onFinished: () -> Unit) {
    LaunchedEffectShimmerDelay(onFinished)
    // The system splash (Android 12+) has already shown the app icon on this
    // blue — re-showing a logo page would be a second, redundant picture. The
    // background is the mark's own diagonal gradient so this screen reads as
    // one continuous blue with the word, and nothing else.
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    0.0f to Color(0xFF3E89F8),
                    1.0f to Color(0xFFC6D9FD),
                ),
            ),
    ) {
        // The branded element lives in the official bottom zone (200x80dp):
        // small, quiet, at the bottom edge — never a centerpiece.
        GrapseeShimmer(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .size(width = 210.dp, height = 38.dp),
        )
    }
}

@Composable
private fun LaunchedEffectShimmerDelay(onFinished: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        delay(2100)
        onFinished()
    }
}

/**
 * The nine-slice shimmer. One Canvas, one measured layout per slice, each
 * clipped to its band and translated so the word stays centered while the
 * highlight sweeps — the same three moving parts the CSS has: slice clip,
 * per-slice scale/opacity, and the animated white→grey boundary.
 */
@Composable
fun GrapseeShimmer(modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val transition = rememberInfiniteTransition(label = "grapsee-shimmer")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = CubicBezierEasing(0.1f, 0.6f, 0.9f, 0.4f)),
        ),
        label = "sweep",
    )
    // Slice scale and opacity straight from the CSS: 20/16/13/11/10/11/13/16/20
    // of the main size and .6/.7/.8/.9/1/.9/.8/.7/.6 — the fisheye bump.
    val sizes = listOf(20, 16, 13, 11, 10, 11, 13, 16, 20)
    val alphas = listOf(0.6f, 0.7f, 0.8f, 0.9f, 1f, 0.9f, 0.8f, 0.7f, 0.6f)
    // The white→grey boundary stops per slice, in the CSS's own percentages;
    // slices six to nine are reversed (grey leads) because the highlight has
    // already passed their half of the word.
    val stops = listOf(
        0.04f to 0.07f, 0.09f to 0.13f, 0.15f to 0.18f, 0.20f to 0.23f,
        null,
        0.29f to 0.32f, 0.34f to 0.37f, 0.39f to 0.42f, 0.45f to 0.48f,
    )
    val white = Color(0xFFFFFFFF)
    val grey = Color(0xFF9A9A9A)

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.onGloballyPositioned { canvasSize = IntSize(it.size.width, it.size.height) }) {
        if (canvasSize == IntSize.Zero) return@Box
        val w = canvasSize.width.toFloat()
        val h = canvasSize.height.toFloat()
        val base = h * 0.9f
        val layouts = remember(measurer, canvasSize) {
            sizes.map { s ->
                measurer.measure(
                    "Grapsee",
                    TextStyle(fontSize = (base * (10f / s)).sp, fontWeight = FontWeight.Black),
                    constraints = Constraints(maxWidth = Int.MAX_VALUE, maxHeight = Int.MAX_VALUE),
                )
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val sliceW = w / layouts.size
            // The CSS truth: each span translates from -100% (fully left, off
            // screen) to 100% (fully right, off screen). Every band shows its
            // own scaled copy of the SAME sliding word, anchored at the same
            // left edge — centering was the mangling.
            layouts.forEachIndexed { i, lm ->
                val slideX = sweep * (w + lm.size.width) - lm.size.width
                clipRect(i * sliceW, 0f, (i + 1) * sliceW, h) {
                    translate(left = slideX, top = (h - lm.size.height) / 2f) {
                        val pair = stops[i]
                        // The TextLayoutResult overloads take brush/color as
                        // separate parameters, not a wrapped TextStyle.
                        if (pair == null) {
                            drawText(lm, color = white, alpha = alphas[i])
                        } else {
                            val ride = (sweep * 2f - 1f + 1f) * 0.22f
                            val a = pair.first + ride
                            val b = pair.second + ride
                            drawText(
                                lm,
                                brush = Brush.linearGradient(
                                    0.0f to grey,
                                    a to grey,
                                    b to white,
                                    1.0f to white,
                                ),
                                alpha = alphas[i],
                            )
                        }
                    }
                }
            }
        }
    }
}
