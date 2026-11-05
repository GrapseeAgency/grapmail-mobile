package com.grapsee.grapmail.ui.artwork

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Code-drawn artwork primitives.
 *
 * The project's own convention (AGENTS-level, recorded three times in the
 * repo's memory) is that illustration is vector shape code with programmatic
 * motion, not an image file: the account and security flows shipped ~50
 * raster PNGs cropped out of reference designs — including a literal
 * landscape photograph behind the puzzle slider. Everything in this package
 * draws with [DrawScope] paths and brand gradients instead.
 *
 * Coordinate space: every primitive draws inside a 0..1 normalized box that
 * the scene scales to the view's size, so one drawing is correct on a 460dp
 * tablet frame and a 150dp phone frame without a second asset — which is the
 * other half of why vectors were chosen over PNGs.
 */

/**
 * A monotonic animation clock in seconds, paused at zero when the device
 * asks for no motion.
 *
 * `windowAnimationScale == 0` is the system's own reduced-motion switch on
 * Android; honoring it here means every scene in the app stops animating
 * from one gate instead of each caller remembering to check. The clock is
 * one shared value per composition, not per-primitive timers: 20 animated
 * shapes on a screen must not schedule 20 frame loops.
 */
@Composable
fun rememberArtClock(): State<Float> {
    val context = LocalContext.current
    val inspecting = LocalInspectionMode.current
    val reduced = remember(inspecting) {
        if (inspecting) true
        else Settings.Global.getFloat(context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val t = remember { mutableFloatStateOf(0f) }
    if (!reduced) {
        LaunchedEffect(Unit) {
            val start = System.nanoTime()
            while (true) {
                withFrameNanos { now ->
                    t.floatValue = (now - start) / 1_000_000_000f
                }
            }
        }
    }
    return t
}

/** Sine wave in [0,1] over a period, phase-shifted per element. */
fun artPulse(t: Float, period: Float = 2.4f, phase: Float = 0f): Float =
    (0.5 + 0.5 * sin(2.0 * PI * ((t + phase) / period).toDouble())).toFloat()

/** Smooth vertical float offset in dp-ish units for a scene height h. */
fun artFloat(t: Float, amp: Float, period: Float = 3.2f, phase: Float = 0f): Float =
    (amp.toDouble() * sin(2.0 * PI * ((t + phase) / period).toDouble())).toFloat()

/** The brand blue gradient, top-left to bottom-right, as the PNGs had it. */
fun brandBrush(w: Float, h: Float, top: Color, bottom: Color): Brush =
    Brush.linearGradient(
        colors = listOf(top, bottom),
        start = Offset(0f, 0f),
        end = Offset(w, h),
    )

// ── background vocabulary ────────────────────────────────────────────────────

/**
 * Soft blob field: the low-opacity rounded clouds every reference frame
 * carries behind the hero object. Drifts slowly; never a flat rectangle.
 */
fun DrawScope.drawBlobs(base: Color, t: Float) {
    val w = size.width
    val h = size.height
    val blobs = listOf(
        Triple(0.18f, 0.30f, 0.34f),
        Triple(0.78f, 0.22f, 0.26f),
        Triple(0.86f, 0.74f, 0.30f),
        Triple(0.12f, 0.80f, 0.22f),
    )
    blobs.forEachIndexed { i, (cx, cy, r) ->
        val drift = artFloat(t, 0.012f, period = 5f + i, phase = i * 0.7f)
        drawCircle(
            color = base.copy(alpha = 0.16f + 0.05f * artPulse(t, 5f, i * 0.9f)),
            radius = r * h,
            center = Offset(cx * w, (cy * h) + drift * h),
        )
    }
}

/** Four-point sparkle star, twinkling on an opacity cycle. */
fun DrawScope.drawSparkle(cx: Float, cy: Float, r: Float, color: Color, alpha: Float) {
    val path = Path().apply {
        moveTo(cx, cy - r)
        quadraticBezierTo(cx, cy, cx + r, cy)
        quadraticBezierTo(cx, cy, cx, cy + r)
        quadraticBezierTo(cx, cy, cx - r, cy)
        quadraticBezierTo(cx, cy, cx, cy - r)
        close()
    }
    drawPath(path, color, alpha = alpha)
}

fun DrawScope.drawSparkleField(color: Color, t: Float, seed: Float = 0f) {
    val w = size.width
    val h = size.height
    val pts = listOf(0.10f to 0.18f, 0.90f to 0.30f, 0.16f to 0.82f, 0.82f to 0.86f, 0.55f to 0.10f)
    pts.forEachIndexed { i, (x, y) ->
        val a = 0.35f + 0.65f * artPulse(t, 2.2f, seed + i * 0.45f)
        drawSparkle(x * w, y * h, (0.03f + 0.012f * i) * h, color, a)
    }
}

/** Confetti: small drifting shapes, the celebration variant of the field. */
fun DrawScope.drawConfetti(colors: List<Color>, t: Float) {
    val w = size.width
    val h = size.height
    val bits = listOf(
        Triple(0.12f, 0.22f, 0), Triple(0.88f, 0.18f, 1), Triple(0.20f, 0.78f, 2),
        Triple(0.80f, 0.80f, 3), Triple(0.35f, 0.12f, 0), Triple(0.68f, 0.90f, 1),
    )
    bits.forEachIndexed { i, (x, y, ci) ->
        val drift = artFloat(t, 0.02f, period = 3.4f, phase = i * 0.5f)
        val c = colors[ci % colors.size]
        val s = 0.022f * h
        if (i % 2 == 0) {
            drawRoundRect(c, Offset(x * w - s / 2, (y * h + drift * h) - s / 2),
                Size(s, s), cornerRadiusX = s / 3, cornerRadiusY = s / 3)
        } else {
            drawCircle(c, radius = s / 2, center = Offset(x * w, y * h + drift * h))
        }
    }
}

/** Concentric pulse rings — the "broadcast / protected" backdrop. */
fun DrawScope.drawConcentric(cx: Float, cy: Float, maxR: Float, color: Color, t: Float) {
    for (i in 0 until 3) {
        val phase = artPulse(t, 2.8f, i * 0.9f)
        drawCircle(color.copy(alpha = 0.22f * (1f - phase)),
            radius = maxR * (0.45f + 0.55f * phase), center = Offset(cx, cy))
    }
}

// ── hero objects ─────────────────────────────────────────────────────────────

/**
 * Rounded envelope with an open flap and the letter rising out of it.
 * `letter` draws the white card, `check` the success badge — the two
 * states the created/welcome/hero frames split on.
 */
fun DrawScope.drawEnvelope(
    cx: Float, cy: Float, w: Float, h: Float,
    top: Color, bottom: Color, ink: Color, letterColor: Color,
    t: Float, letter: Boolean = true, check: Boolean = false,
) {
    val lift = artFloat(t, 0.03f * h, period = 3.6f)
    val body = Path().apply {
        addRoundRect(RoundRect(Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
            w * 0.10f, w * 0.10f))
    }
    if (letter) {
        val lw = w * 0.62f
        val lh = h * 0.66f
        drawRoundRect(letterColor, Offset(cx - lw / 2, cy - h / 2 - lh * 0.55f + lift),
            Size(lw, lh), cornerRadiusX = lw * 0.08f, cornerRadiusY = lw * 0.08f)
        val lineC = ink.copy(alpha = 0.35f)
        for (i in 0 until 2) {
            drawRoundRect(lineC,
                Offset(cx - lw * 0.36f, cy - h / 2 - lh * 0.30f + lift + i * lh * 0.22f),
                Size(lw * (0.72f - i * 0.18f), lh * 0.09f),
                cornerRadiusX = lh * 0.045f, cornerRadiusY = lh * 0.045f)
        }
    }
    drawPath(body, brandBrush(w, h, top, bottom))
    val flap = Path().apply {
        moveTo(cx - w / 2, cy - h / 2)
        lineTo(cx, cy + h * 0.12f)
        lineTo(cx + w / 2, cy - h / 2)
        close()
    }
    drawPath(flap, Color.White, alpha = 0.18f)
    drawPath(flap, bottom, style = Stroke(width = w * 0.012f))
    if (check) {
        val bx = cx + w * 0.42f
        val by = cy + h * 0.40f
        val br = w * 0.14f
        val pop = 1f + 0.06f * artPulse(t, 2.2f, 0.3f)
        drawCircle(top, radius = br * pop, center = Offset(bx, by))
        drawCircle(Color.White, radius = br * pop * 0.78f, center = Offset(bx, by),
            style = Stroke(width = br * 0.06f))
        drawCheck(bx, by, br * 0.5f, top, br * 0.22f)
    }
}

/** A checkmark stroke inside a badge, reused by shields/devices/badges. */
fun DrawScope.drawCheck(cx: Float, cy: Float, r: Float, color: Color, stroke: Float) {
    val p = Path().apply {
        moveTo(cx - r, cy)
        lineTo(cx - r * 0.25f, cy + r * 0.7f)
        lineTo(cx + r, cy - r * 0.75f)
    }
    drawPath(p, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
}

/** Shield with one of the three glyphs the flows use: lock, check, person. */
fun DrawScope.drawShield(
    cx: Float, cy: Float, w: Float, h: Float,
    top: Color, bottom: Color, ink: Color, glyph: String, t: Float,
) {
    val bob = artFloat(t, 0.02f * h, period = 3.8f)
    val shield = Path().apply {
        moveTo(cx, cy - h / 2 + bob)
        cubicTo(cx + w * 0.48f, cy - h * 0.38f + bob, cx + w * 0.5f, cy - h * 0.1f + bob,
            cx + w * 0.42f, cy + h * 0.2f + bob)
        cubicTo(cx + w * 0.32f, cy + h * 0.42f + bob, cx + w * 0.1f, cy + h * 0.5f + bob,
            cx, cy + h * 0.54f + bob)
        cubicTo(cx - w * 0.1f, cy + h * 0.5f + bob, cx - w * 0.32f, cy + h * 0.42f + bob,
            cx - w * 0.42f, cy + h * 0.2f + bob)
        cubicTo(cx - w * 0.5f, cy - h * 0.1f + bob, cx - w * 0.48f, cy - h * 0.38f + bob,
            cx, cy - h / 2 + bob)
        close()
    }
    drawPath(shield, brandBrush(w, h, top, bottom))
    drawPath(shield, Color.White, alpha = 0.16f, style = Stroke(width = w * 0.03f))
    when (glyph) {
        "lock" -> {
            val lw = w * 0.34f
            val lh = h * 0.26f
            drawRoundRect(ink, Offset(cx - lw / 2, cy - lh * 0.1f + bob),
                Size(lw, lh), cornerRadiusX = lw * 0.18f, cornerRadiusY = lw * 0.18f)
            val arc = Path().apply {
                addRoundRect(RoundRect(Rect(cx - lw * 0.32f, cy - lh * 0.75f + bob,
                    cx + lw * 0.32f, cy + lh * 0.25f + bob), lw * 0.5f, lw * 0.5f))
            }
            drawPath(arc, ink, style = Stroke(width = lw * 0.16f))
            drawCircle(bottom, radius = lw * 0.09f,
                center = Offset(cx, cy + lh * 0.15f + bob))
        }
        "check" -> drawCheck(cx, cy + bob, w * 0.16f, ink, w * 0.07f)
        "person" -> {
            drawCircle(ink, radius = w * 0.11f, center = Offset(cx, cy - h * 0.08f + bob))
            val shoulders = Path().apply {
                addRoundRect(RoundRect(Rect(cx - w * 0.2f, cy + h * 0.04f + bob,
                    cx + w * 0.2f, cy + h * 0.3f + bob), w * 0.18f, w * 0.18f))
            }
            drawPath(shoulders, ink)
        }
        "alert" -> {
            drawRoundRect(ink, Offset(cx - w * 0.035f, cy - h * 0.16f + bob),
                Size(w * 0.07f, h * 0.22f), cornerRadiusX = w * 0.035f, cornerRadiusY = w * 0.035f)
            drawCircle(ink, radius = w * 0.045f, center = Offset(cx, cy + h * 0.14f + bob))
        }
    }
}

/** Paper plane with the dashed flight trail, top-right of the welcome frame. */
fun DrawScope.drawPaperPlane(cx: Float, cy: Float, s: Float, top: Color, bottom: Color, t: Float) {
    val glide = artFloat(t, 0.04f * s, period = 4.2f, phase = 0.6f)
    val p = Path().apply {
        moveTo(cx - s, cy + glide)
        lineTo(cx + s, cy - s * 0.55f + glide)
        lineTo(cx - s * 0.15f, cy + s * 0.75f + glide)
        close()
    }
    drawPath(p, brandBrush(s * 2f, s * 2f, top, bottom))
    val fold = Path().apply {
        moveTo(cx - s, cy + glide)
        lineTo(cx + s * 0.1f, cy - s * 0.1f + glide)
    }
    drawPath(fold, bottom, style = Stroke(width = s * 0.06f))
    val trail = Path().apply {
        moveTo(cx - s * 1.3f, cy + s * 0.9f + glide * 0.4f)
        quadraticBezierTo(cx - s * 0.2f, cy + s * 1.2f + glide, cx + s * 0.9f, cy + s * 0.6f + glide)
    }
    drawPath(trail, top.copy(alpha = 0.5f), style = Stroke(
        width = s * 0.05f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(s * 0.18f, s * 0.22f),
            (t * s * 0.4f) % (s * 0.4f))))
}

/** Laptop + phone pair: the sessions / devices scene. */
fun DrawScope.drawDevices(
    cx: Float, cy: Float, w: Float, h: Float,
    top: Color, bottom: Color, ink: Color, cardColor: Color, t: Float,
    checkOn: Boolean = true,
) {
    val bob = artFloat(t, 0.015f * h, period = 4f)
    val lw = w * 0.62f
    val lh = h * 0.52f
    drawRoundRect(cardColor, Offset(cx - lw / 2, cy - lh / 2 + bob), Size(lw, lh),
        cornerRadiusX = lw * 0.05f, cornerRadiusY = lw * 0.05f)
    drawRoundRect(brandBrush(lw, lh, top, bottom),
        Offset(cx - lw * 0.46f, cy - lh * 0.44f + bob), Size(lw * 0.92f, lh * 0.88f),
        cornerRadiusX = lw * 0.03f, cornerRadiusY = lw * 0.03f)
    drawRoundRect(cardColor, Offset(cx - lw * 0.62f, cy + lh / 2 + bob),
        Size(lw * 1.24f, lh * 0.10f), cornerRadiusX = lh * 0.05f, cornerRadiusY = lh * 0.05f)
    val pw = w * 0.2f
    val ph = h * 0.44f
    val px = cx + lw * 0.62f
    val py = cy + h * 0.1f - bob
    drawRoundRect(cardColor, Offset(px - pw / 2, py - ph / 2), Size(pw, ph),
        cornerRadiusX = pw * 0.18f, cornerRadiusY = pw * 0.18f)
    drawRoundRect(brandBrush(pw, ph, top, bottom),
        Offset(px - pw * 0.4f, py - ph * 0.42f), Size(pw * 0.8f, ph * 0.84f),
        cornerRadiusX = pw * 0.1f, cornerRadiusY = pw * 0.1f)
    if (checkOn) {
        drawCheck(cx, cy + bob, lw * 0.13f, ink, lw * 0.05f)
        drawCheck(px, py, pw * 0.16f, ink, pw * 0.07f)
    }
    val sw = w * 0.16f
    drawCircle(cardColor, radius = sw, center = Offset(cx - lw * 0.85f, cy + h * 0.18f + bob))
    drawRoundRect(brandBrush(sw, sw, top, bottom),
        Offset(cx - lw * 0.85f - sw * 0.4f, cy + h * 0.18f + bob - sw * 0.55f),
        Size(sw * 0.8f, sw * 1.1f), cornerRadiusX = sw * 0.16f, cornerRadiusY = sw * 0.16f)
}

/** Person bust: the identity/name scene. */
fun DrawScope.drawPerson(cx: Float, cy: Float, s: Float, skin: Color, shirt: Color, ink: Color, t: Float) {
    val bob = artFloat(t, 0.012f * s, period = 4.4f)
    drawCircle(skin, radius = s * 0.30f, center = Offset(cx, cy - s * 0.18f + bob))
    val hair = Path().apply {
        addRoundRect(RoundRect(Rect(cx - s * 0.32f, cy - s * 0.52f + bob,
            cx + s * 0.32f, cy - s * 0.12f + bob), s * 0.3f, s * 0.3f))
    }
    drawPath(hair, ink)
    val body = Path().apply {
        addRoundRect(RoundRect(Rect(cx - s * 0.46f, cy + s * 0.16f + bob,
            cx + s * 0.46f, cy + s * 0.9f + bob), s * 0.42f, s * 0.42f))
    }
    drawPath(body, shirt)
}

/** Floating message card with two text lines. */
fun DrawScope.drawCard(cx: Float, cy: Float, w: Float, h: Float, bg: Color, line: Color, t: Float) {
    val bob = artFloat(t, 0.02f * h, period = 3.9f, phase = 1.1f)
    drawRoundRect(bg, Offset(cx - w / 2, cy - h / 2 + bob), Size(w, h),
        cornerRadiusX = w * 0.10f, cornerRadiusY = w * 0.10f)
    for (i in 0 until 2) {
        drawRoundRect(line, Offset(cx - w * 0.34f, cy - h * 0.22f + bob + i * h * 0.3f),
            Size(w * (0.68f - i * 0.2f), h * 0.12f),
            cornerRadiusX = h * 0.06f, cornerRadiusY = h * 0.06f)
    }
}

/** Small rounded chip with a glyph dot — the "verified / action" badge. */
fun DrawScope.drawChip(cx: Float, cy: Float, w: Float, h: Float, bg: Color, ink: Color, glyph: String, t: Float) {
    val bob = artFloat(t, 0.015f * h, period = 3.3f, phase = 0.4f)
    drawRoundRect(bg, Offset(cx - w / 2, cy - h / 2 + bob), Size(w, h),
        cornerRadiusX = h * 0.5f, cornerRadiusY = h * 0.5f)
    val gx = cx - w * 0.3f
    when (glyph) {
        "check" -> {
            drawCircle(ink, radius = h * 0.28f, center = Offset(gx, cy + bob))
            drawCheck(gx, cy + bob, h * 0.16f, bg, h * 0.09f)
        }
        "key" -> drawKey(gx, cy + bob, h * 0.3f, ink, h * 0.08f)
        else -> drawCircle(ink, radius = h * 0.22f, center = Offset(gx, cy + bob))
    }
    drawRoundRect(ink.copy(alpha = 0.55f), Offset(cx - w * 0.08f, cy - h * 0.06f + bob),
        Size(w * 0.4f, h * 0.12f), cornerRadiusX = h * 0.06f, cornerRadiusY = h * 0.06f)
}

/** Key glyph: circle bow + shaft + teeth. */
fun DrawScope.drawKey(cx: Float, cy: Float, r: Float, color: Color, stroke: Float) {
    drawCircle(color, radius = r, center = Offset(cx - r * 1.4f, cy),
        style = Stroke(width = stroke * 0.8f))
    drawLine(color, Offset(cx - r * 0.4f, cy), Offset(cx + r * 1.6f, cy), stroke)
    drawLine(color, Offset(cx + r * 1.1f, cy), Offset(cx + r * 1.1f, cy + r * 0.7f), stroke)
    drawLine(color, Offset(cx + r * 1.6f, cy), Offset(cx + r * 1.6f, cy + r * 0.9f), stroke)
}

/** Clock glyph: ring + hands. */
fun DrawScope.drawClock(cx: Float, cy: Float, r: Float, color: Color, stroke: Float, t: Float) {
    drawCircle(color, radius = r, center = Offset(cx, cy), style = Stroke(width = stroke))
    val a = (t * 0.6f) % (2f * PI).toFloat()
    drawLine(color, Offset(cx, cy), Offset(cx + r * 0.55f * sin(a), cy - r * 0.55f * cos(a)), stroke * 0.8f)
    drawLine(color, Offset(cx, cy), Offset(cx, cy - r * 0.75f), stroke * 0.8f)
}

/** Bell glyph: dome + clapper. */
fun DrawScope.drawBell(cx: Float, cy: Float, r: Float, color: Color, stroke: Float) {
    val dome = Path().apply {
        moveTo(cx - r, cy + r * 0.5f)
        quadraticBezierTo(cx - r, cy - r * 1.2f, cx, cy - r * 1.2f)
        quadraticBezierTo(cx + r, cy - r * 1.2f, cx + r, cy + r * 0.5f)
        close()
    }
    drawPath(dome, color)
    drawCircle(color, radius = r * 0.22f, center = Offset(cx, cy + r * 0.85f))
}

/** Globe glyph: circle + meridians. */
fun DrawScope.drawGlobe(cx: Float, cy: Float, r: Float, color: Color, stroke: Float) {
    drawCircle(color, radius = r, center = Offset(cx, cy), style = Stroke(width = stroke))
    drawOval(color, Rect(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r),
        style = Stroke(width = stroke * 0.7f))
    drawLine(color, Offset(cx - r, cy), Offset(cx + r, cy), stroke * 0.7f)
}

/** List glyph: rows of bullet + bar. */
fun DrawScope.drawList(cx: Float, cy: Float, w: Float, h: Float, color: Color, stroke: Float) {
    for (i in 0 until 3) {
        val y = cy - h / 2 + h * (0.15f + i * 0.35f)
        drawCircle(color, radius = stroke * 0.9f, center = Offset(cx - w / 2, y))
        drawRoundRect(color, Offset(cx - w * 0.28f, y - stroke * 0.5f),
            Size(w * 0.78f, stroke), cornerRadiusX = stroke * 0.5f, cornerRadiusY = stroke * 0.5f)
    }
}

/** Trash glyph: lid + can. */
fun DrawScope.drawTrash(cx: Float, cy: Float, r: Float, color: Color, stroke: Float) {
    drawLine(color, Offset(cx - r, cy - r * 0.7f), Offset(cx + r, cy - r * 0.7f), stroke)
    val can = Path().apply {
        moveTo(cx - r * 0.7f, cy - r * 0.4f)
        lineTo(cx - r * 0.55f, cy + r)
        lineTo(cx + r * 0.55f, cy + r)
        lineTo(cx + r * 0.7f, cy - r * 0.4f)
        close()
    }
    drawPath(can, color, style = Stroke(width = stroke))
}

/** Sign-out glyph: door + exiting arrow. */
fun DrawScope.drawLogout(cx: Float, cy: Float, r: Float, color: Color, stroke: Float, t: Float) {
    val slide = artFloat(t, 0.08f * r, period = 2.6f, phase = 0.2f)
    val door = Path().apply {
        moveTo(cx - r * 0.2f, cy - r)
        lineTo(cx - r, cy - r)
        lineTo(cx - r, cy + r)
        lineTo(cx - r * 0.2f, cy + r)
    }
    drawPath(door, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    drawLine(color, Offset(cx, cy + slide), Offset(cx + r, cy + slide), stroke)
    val head = Path().apply {
        moveTo(cx + r * 0.55f, cy - r * 0.35f + slide)
        lineTo(cx + r, cy + slide)
        lineTo(cx + r * 0.55f, cy + r * 0.35f + slide)
    }
    drawPath(head, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
}

/** Activity glyph: pulse line. */
fun DrawScope.drawActivity(cx: Float, cy: Float, w: Float, color: Color, stroke: Float, t: Float) {
    val sweep = (t * 0.5f) % 1f
    val p = Path().apply {
        moveTo(cx - w / 2, cy)
        lineTo(cx - w * 0.25f, cy)
        lineTo(cx - w * 0.1f, cy - w * 0.18f)
        lineTo(cx + w * 0.05f, cy + w * 0.18f)
        lineTo(cx + w * 0.18f, cy)
        lineTo(cx + w / 2, cy)
    }
    drawPath(p, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    val dot = Offset(cx - w / 2 + w * sweep, cy)
    drawCircle(color.copy(alpha = 0.8f), radius = stroke * 1.1f, center = dot)
}

/** Alert glyph: ring + exclamation. */
fun DrawScope.drawAlert(cx: Float, cy: Float, r: Float, color: Color, stroke: Float, t: Float) {
    val pulse = 1f + 0.05f * artPulse(t, 1.6f)
    drawCircle(color.copy(alpha = 0.25f), radius = r * 1.3f * pulse, center = Offset(cx, cy))
    drawCircle(color, radius = r * pulse, center = Offset(cx, cy), style = Stroke(width = stroke))
    drawRoundRect(color, Offset(cx - stroke * 0.6f, cy - r * 0.5f),
        Size(stroke * 1.2f, r * 0.8f), cornerRadiusX = stroke * 0.6f, cornerRadiusY = stroke * 0.6f)
    drawCircle(color, radius = stroke * 0.8f, center = Offset(cx, cy + r * 0.5f))
}

/**
 * The puzzle slider's backdrop: a geometric mountain-and-lake scene.
 *
 * This replaces a literal landscape photograph that was cropped out of the
 * reference set and shipped as `puzzle_photo.png`. A photo of someone's
 * lake says nothing about GrapMail, cannot be themed, and is the single
 * clearest example of the crop-everything habit; a flat vector range in
 * brand blues says the same "drag the piece into place" and belongs to the
 * product.
 */
fun DrawScope.drawPuzzleScene(top: Color, bottom: Color, sky: Color, water: Color, t: Float) {
    val w = size.width
    val h = size.height
    drawRect(sky)
    val far = Path().apply {
        moveTo(0f, h * 0.62f)
        lineTo(w * 0.12f, h * 0.34f); lineTo(w * 0.24f, h * 0.55f)
        lineTo(w * 0.38f, h * 0.22f); lineTo(w * 0.52f, h * 0.58f)
        lineTo(w * 0.66f, h * 0.30f); lineTo(w * 0.80f, h * 0.56f)
        lineTo(w * 0.92f, h * 0.38f); lineTo(w, h * 0.60f)
        lineTo(w, h * 0.66f); lineTo(0f, h * 0.66f)
        close()
    }
    drawPath(far, top.copy(alpha = 0.55f))
    val snow = Path().apply {
        moveTo(w * 0.38f, h * 0.22f)
        lineTo(w * 0.42f, h * 0.32f); lineTo(w * 0.38f, h * 0.30f)
        lineTo(w * 0.34f, h * 0.32f); close()
    }
    drawPath(snow, Color.White, alpha = 0.85f)
    drawRect(water, topLeft = Offset(0f, h * 0.66f), size = Size(w, h * 0.34f))
    val ripple = artFloat(t, 0.004f * h, period = 2.8f)
    for (i in 0 until 3) {
        drawLine(bottom.copy(alpha = 0.25f),
            Offset(w * 0.1f, h * (0.74f + i * 0.08f) + ripple * h),
            Offset(w * 0.9f, h * (0.74f + i * 0.08f) + ripple * h),
            strokeWidth = h * 0.008f)
    }
}
