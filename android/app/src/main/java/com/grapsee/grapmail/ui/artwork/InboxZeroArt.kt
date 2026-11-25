package com.grapsee.grapmail.ui.artwork

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.grapsee.grapmail.ui.theme.LocalPalette
import com.grapsee.grapmail.ui.theme.Palette

/**
 * The Inbox Zero illustration: an empty tray at rest, a check badge settling
 * above it, a paper plane leaving the frame, soft blobs and sparkles behind —
 * the same vocabulary and the same normalized-space drawing as every scene in
 * Artwork.kt, built from the ArtPrimitives.kt shapes. A new file rather than
 * a new `Artwork(name)` branch so the shared scene dispatcher stays as it is.
 *
 * Motion is supplied by the caller and read at draw time (no recomposition
 * per frame): [entrance] 0→1 is the one-time arrival (the tray rises, the
 * badge pops), [confetti] 0→1 is the optional once-a-day fall, null for none.
 * The ambient bob comes from [rememberArtClock], which stops after its
 * entrance and holds still under the system's "Remove animations".
 * Decorative: no semantics — the headline under it says what it means.
 */
@Composable
fun InboxZeroArt(
    modifier: Modifier = Modifier,
    entrance: () -> Float = { 1f },
    confetti: (() -> Float)? = null,
) {
    val p = LocalPalette.current
    val clock = rememberArtClock()
    val t by clock
    Canvas(modifier.clearAndSetSemantics { }) {
        sceneInboxZero(p, t, entrance().coerceIn(0f, 1f))
        val fall = confetti?.invoke()
        if (fall != null && fall > 0f && fall < 1f) {
            drawFallingConfetti(
                listOf(p.primarySolid, p.success, p.star, p.primaryTop, p.error.copy(alpha = 0.7f)),
                fall,
            )
        }
    }
}

private fun DrawScope.sceneInboxZero(p: Palette, t: Float, e: Float) {
    val w = size.width
    val h = size.height
    drawBlobs(p.infoFill, t)
    drawSparkleField(p.primarySolid, t, seed = 0.4f)
    // A low sun behind the tray: calm, not a spotlight.
    drawCircle(p.star.copy(alpha = 0.22f * e), radius = h * 0.17f, center = Offset(w * 0.68f, h * 0.36f))
    drawCircle(p.star.copy(alpha = 0.35f * e), radius = h * 0.11f, center = Offset(w * 0.68f, h * 0.36f))
    // The last letter has left: a small plane heading out of the frame.
    drawPaperPlane(w * 0.20f, h * 0.24f - (1f - e) * h * 0.04f, w * 0.05f, p.primaryTop, p.primaryBottom, t)
    val rise = (1f - e) * h * 0.10f
    drawInboxTray(w * 0.47f, h * 0.62f + rise, w * 0.48f, h * 0.40f, p, t)
    // The badge pops in after the tray has nearly landed.
    val pop = ((e - 0.45f) / 0.55f).coerceIn(0f, 1f)
    if (pop > 0f) {
        val overshoot = 1f + 0.12f * kotlin.math.sin(pop * Math.PI.toFloat())
        val bx = w * 0.70f
        val by = h * 0.40f + artFloat(t, 0.012f * h, period = 3.6f, phase = 0.8f)
        val br = h * 0.085f * pop * overshoot
        drawCircle(p.card, radius = br * 1.18f, center = Offset(bx, by))
        drawCircle(p.success, radius = br, center = Offset(bx, by))
        drawCheck(bx, by, br * 0.5f, Color.White, br * 0.2f)
    }
}

/**
 * An empty inbox tray: the brand-gradient body, the lighter well it opens
 * into, and the notch in its front lip that every inbox glyph carries.
 */
private fun DrawScope.drawInboxTray(cx: Float, cy: Float, w: Float, h: Float, p: Palette, t: Float) {
    val bob = artFloat(t, 0.015f * h, period = 4.2f)
    val top = cy - h / 2 + bob
    val bottom = cy + h / 2 + bob
    // Soft ground shadow keeps it resting, not floating.
    drawOval(p.primarySolid.copy(alpha = 0.10f), Offset(cx - w * 0.46f, bottom + h * 0.04f - bob),
        Size(w * 0.92f, h * 0.10f))
    // Back wall.
    drawRoundRect(p.primaryBottom.copy(alpha = 0.55f), Offset(cx - w * 0.42f, top),
        Size(w * 0.84f, h * 0.44f), cornerRadius = CornerRadius(w * 0.06f, w * 0.06f))
    // The well: an empty, lighter interior.
    drawRoundRect(p.card, Offset(cx - w * 0.36f, top + h * 0.08f),
        Size(w * 0.72f, h * 0.40f), cornerRadius = CornerRadius(w * 0.04f, w * 0.04f))
    for (i in 0 until 2) {
        drawRoundRect(p.textTertiary.copy(alpha = 0.18f),
            Offset(cx - w * 0.26f, top + h * (0.17f + i * 0.09f)),
            Size(w * (0.52f - i * 0.16f), h * 0.04f),
            cornerRadius = CornerRadius(h * 0.02f, h * 0.02f))
    }
    // Front body with the notch.
    val lipY = top + h * 0.42f
    val notchW = w * 0.20f
    val body = Path().apply {
        addRoundRect(RoundRect(Rect(cx - w / 2, lipY, cx + w / 2, bottom), w * 0.08f, w * 0.08f))
    }
    drawPath(body, brandBrush(w, h, p.primaryTop, p.primaryBottom))
    val notch = Path().apply {
        moveTo(cx - w / 2 + w * 0.06f, lipY + h * 0.02f)
        lineTo(cx - notchW, lipY + h * 0.02f)
        quadraticBezierTo(cx - notchW * 0.7f, lipY + h * 0.20f, cx, lipY + h * 0.20f)
        quadraticBezierTo(cx + notchW * 0.7f, lipY + h * 0.20f, cx + notchW, lipY + h * 0.02f)
        lineTo(cx + w / 2 - w * 0.06f, lipY + h * 0.02f)
    }
    drawPath(notch, Color.White.copy(alpha = 0.55f),
        style = Stroke(width = h * 0.025f, cap = StrokeCap.Round))
    drawPath(body, Color.White, alpha = 0.14f, style = Stroke(width = w * 0.012f))
}

/** A short shower of brand-coloured bits, falling and fading once. [fall] is 0..1. */
private fun DrawScope.drawFallingConfetti(colors: List<Color>, fall: Float) {
    val w = size.width
    val h = size.height
    val fade = (1f - fall).coerceIn(0f, 1f)
    for (i in 0 until 16) {
        val x = ((i * 37) % 100) / 100f
        val delay = (i % 5) * 0.08f
        val local = ((fall - delay) / (1f - delay)).coerceIn(0f, 1f)
        if (local <= 0f) continue
        val y = -0.08f + local * (0.75f + (i % 3) * 0.08f)
        val sway = kotlin.math.sin((local * 4f + i) * 1.3f) * 0.02f
        val c = colors[i % colors.size].copy(alpha = 0.85f * fade)
        val s = h * (0.018f + (i % 3) * 0.004f)
        val center = Offset((x + sway) * w, y * h)
        rotate(degrees = local * 220f + i * 23f, pivot = center) {
            if (i % 3 == 0) drawCircle(c, radius = s * 0.5f, center = center)
            else drawRoundRect(c, Offset(center.x - s / 2, center.y - s * 0.3f), Size(s, s * 0.6f),
                cornerRadius = CornerRadius(s * 0.15f, s * 0.15f))
        }
    }
}
