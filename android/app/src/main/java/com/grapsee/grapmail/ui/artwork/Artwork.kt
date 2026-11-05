package com.grapsee.grapmail.ui.artwork

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.grapsee.grapmail.ui.theme.LocalPalette
import com.grapsee.grapmail.ui.theme.Palette

/**
 * The illustration layer, drawn.
 *
 * `Artwork(name)` replaces `painterResource(illRes(name))` everywhere: the
 * same scene names the PNG set used, now composed from the vector
 * primitives in `ArtPrimitives.kt` with programmatic motion. Nothing here
 * reads a drawable — the raster set is deleted, and the names keep their
 * old spelling so every existing `illustration = "…"` call site stays
 * correct. The dispatch is the migration, not the call sites.
 *
 * Each scene is one `Canvas` pass in a normalized 0..1 space, so the same
 * code is sharp at 150dp on a phone and 460dp on a tablet — the second
 * thing the cropped PNGs could never do.
 */
@Composable
fun Artwork(name: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val clock = rememberArtClock()
    val t by clock
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // The reference art sits on a transparent field; the screens own the
        // background. Everything below uses fractions of (w,h) via the
        // helpers' absolute coords.
        when (name) {
            "welcome", "hero" -> sceneWelcome(p, t, w, h)
            "step1_name" -> sceneIdentity(p, t, w, h)
            "verify" -> sceneVerify(p, t, w, h)
            "created_a" -> sceneCreated(p, t, w, h, confetti = true)
            "created_b" -> sceneCreated(p, t, w, h, confetti = false)
            "puzzle" -> drawPuzzleScene(p.primaryTop, p.primaryBottom,
                p.infoFill, p.softFill, t)

            "account-2fa" -> sceneShieldLock(p, t, w, h, rings = true, badge = true)
            "account-backup" -> sceneBackup(p, t, w, h)
            "account-forgot" -> sceneKeyRestore(p, t, w, h)
            "account-recovery-code" -> sceneCode(p, t, w, h)
            "account-new-password" -> sceneKeyRestore(p, t, w, h, fresh = true)
            "account-reset-ok" -> sceneCreated(p, t, w, h, confetti = true)
            "account-pending" -> scenePending(p, t, w, h)
            "account-find-address" -> sceneFind(p, t, w, h)
            "account-address-found" -> sceneFind(p, t, w, h, found = true)
            "account-phone" -> scenePhone(p, t, w, h, ok = false)
            "account-phone-ok" -> scenePhone(p, t, w, h, ok = true)

            "ill_sec_hub" -> sceneShieldLock(p, t, w, h, rings = false, badge = false)
            "ill_sec_hub_secure" -> sceneShieldLock(p, t, w, h, rings = true, badge = true)
            "ill_sec_2fa" -> sceneTwoFactor(p, t, w, h, state = "idle")
            "ill_sec_2fa_on" -> sceneTwoFactor(p, t, w, h, state = "on")
            "ill_sec_2fa_off" -> sceneTwoFactor(p, t, w, h, state = "off")
            "ill_sec_2fa_off_confirm" -> sceneTwoFactor(p, t, w, h, state = "confirm")
            "ill_sec_password" -> scenePassword(p, t, w, h, "idle")
            "ill_sec_password_confirm" -> scenePassword(p, t, w, h, "confirm")
            "ill_sec_password_ok" -> scenePassword(p, t, w, h, "ok")
            "ill_sec_password_after" -> scenePassword(p, t, w, h, "after")
            "ill_sec_password_updated" -> scenePassword(p, t, w, h, "updated")
            "ill_sec_phone" -> scenePhone(p, t, w, h, ok = false)
            "ill_sec_backup_generate" -> sceneBackup(p, t, w, h, generating = true)
            "ill_sec_backup_saved" -> sceneBackup(p, t, w, h, saved = true)
            "ill_sec_signout_all" -> sceneSignOut(p, t, w, h, ok = false)
            "ill_sec_signout_all_ok" -> sceneSignOut(p, t, w, h, ok = true)
            "ill_sec_sessions" -> sceneSessions(p, t, w, h, "list")
            "ill_sec_session_revoke" -> sceneSessions(p, t, w, h, "revoke")
            "ill_sec_session_revoked" -> sceneSessions(p, t, w, h, "revoked")
            "ill_sec_session_alert" -> sceneSessions(p, t, w, h, "alert")
            "ill_sec_revoke_confirm" -> sceneSessions(p, t, w, h, "revoke")
            "ill_sec_revoked_suspicious" -> sceneSessions(p, t, w, h, "alert")
            "ill_sec_activity" -> sceneActivity(p, t, w, h)
            "ill_sec_alert" -> sceneAlert(p, t, w, h)
            "ill_sec_secure" -> sceneShieldLock(p, t, w, h, rings = true, badge = true)
            else -> sceneWelcome(p, t, w, h)
        }
    }
}

// ── scenes ───────────────────────────────────────────────────────────────────

private fun androidx.compose.ui.graphics.drawscope.DrawScope.sceneWelcome(
    p: Palette, t: Float, w: Float, h: Float,
) {
    drawBlobs(p.infoFill, t)
    drawSparkleField(p.primarySolid, t)
    drawEnvelope(w * 0.46f, h * 0.55f, w * 0.42f, h * 0.52f,
        p.primaryTop, p.primaryBottom, Color.White, p.card, t, letter = true)
    drawShield(w * 0.16f, h * 0.30f, w * 0.16f, h * 0.34f,
        p.primaryTop, p.primaryBottom, Color.White, "lock", t)
    drawPaperPlane(w * 0.84f, h * 0.22f, w * 0.06f, p.primaryTop, p.primaryBottom, t)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.sceneIdentity(
    p: Palette, t: Float, w: Float, h: Float,
) {
    drawBlobs(p.infoFill, t)
    drawSparkleField(p.primarySolid, t)
    drawPerson(w * 0.42f, h * 0.52f, h * 0.42f,
        Color(0xFFE8B08C), p.primarySolid, Color(0xFF243B5E), t)
    drawChip(w * 0.74f, h * 0.30f, w * 0.16f, h * 0.16f, p.card, p.primarySolid, "check", t)
    drawCard(w * 0.72f, h * 0.62f, w * 0.26f, h * 0.20f, p.card, p.textTertiary, t)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.sceneVerify(
    p: Palette, t: Float, w: Float, h: Float,
) {
    drawBlobs(p.infoFill, t)
    drawSparkleField(p.primarySolid, t)
    drawShield(w * 0.36f, h * 0.50f, w * 0.30f, h * 0.62f,
        p.primaryTop, p.primaryBottom, Color.White, "person", t)
    // The badge carries a glyph, not the words "Human Verification": the PNG
    // baked English text into the image, which no screen reader can reach
    // and no translation can change. The real heading is the screen's own.
    drawChip(w * 0.72f, h * 0.58f, w * 0.24f, h * 0.22f, p.card, p.primarySolid, "check", t)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.sceneCreated(
    p: Palette, t: Float, w: Float, h: Float, confetti: Boolean,
) {
    drawBlobs(p.infoFill, t)
    if (confetti) {
        drawConfetti(listOf(p.primarySolid, p.success, p.star, p.error.copy(alpha = 0.7f)), t)
    } else {
        drawSparkleField(p.primarySolid, t)
    }
    drawEnvelope(w * 0.48f, h * 0.50f, w * 0.40f, h * 0.50f,
        p.primaryTop, p.primaryBottom, Color.White, p.card, t,
        letter = false, check = true)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.sceneShieldLock(
    p: Palette, t: Float, w: Float, h: Float, rings: Boolean, badge: Boolean,
) {
    drawBlobs(p.infoFill, t)
    if (rings) drawConcentric(w * 0.42f, h * 0.48f, h * 0.55f, p.primarySolid, t)
    drawShield(w * 0.42f, h * 0.48f, w * 0.34f, h * 0.68f,
        p.primaryTop, p.primaryBottom, Color.White, "lock", t)
    if (badge) {
        drawShield(w * 0.70f, h * 0.62f, w * 0.12f, h * 0.24f,
            p.primaryTop, p.primaryBottom, Color.White, "check", t)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.sceneBackup(
    p: Palette, t: Float, w: Float, h: Float, generating: Boolean = false, saved: Boolean = false,
) {
    drawBlobs(p.infoFill, t)
    drawSparkleField(p.primarySolid, t)
    drawCard(w * 0.42f, h * 0.48f, w * 0.30f, h * 0.56f, p.card, p.textTertiary, t)
    if (generating) {
        drawActivity(w * 0.72f, h * 0.42f, w * 0.20f, p.primarySolid, h * 0.02f, t)
    }
    if (saved) {
        drawChip(w * 0.72f, h * 0.58f, w * 0.18f, h * 0.18f, p.card, p.successInk, "check", t)
    } else {
        drawKey(w * 0.72f, h * 0.44f, h * 0.09f, p.primarySolid, h * 0.02f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.sceneKeyRestore(
    p: Palette, t: Float, w: Float, h: Float, fresh: Boolean = false,
) {
    drawBlobs(p.infoFill, t)
    drawShield(w * 0.38f, h * 0.48f, w * 0.30f, h * 0.60f,
        p.primaryTop, p.primaryBottom, Color.White, if (fresh) "check" else "lock", t)
    drawKey(w * 0.70f, h * 0.52f, h * 0.10f, p.primarySolid, h * 0.022f)
    drawSparkleField(p.primarySolid, t, seed = 1.2f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.sceneCode(
    p: Palette, t: Float, w: Float, h: Float,
) {
    drawBlobs(p.infoFill, t)
    drawCard(w * 0.5f, h * 0.5f, w * 0.44f, h * 0.44f, p.card, p.textTertiary, t)
    val digits = listOf(0.30f, 0.42f, 0.54f, 0.66f)
    digits.forEachIndexed { i, x ->
        val a = 0.4f + 0.6f * artPulse(t, 2.4f, i * 0.3f)
        drawRoundRect(p.primarySolid.copy(alpha = a),
            Offset(w * x - w * 0.03f, h * 0.46f), Size(w * 0.06f, h * 0.10f),
            cornerRadiusX = w * 0.015f, cornerRadiusY = w * 0.015f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.scenePending(
    p: Palette, t: Float, w: Float, h: Float,
) {
    drawBlobs(p.infoFill, t)
    drawEnvelope(w * 0.44f, h * 0.52f, w * 0.34f, h * 0.42f,
        p.primaryTop, p.primaryBottom, Color.White, p.card, t, letter = true)
    drawClock(w * 0.72f, h * 0.34f, h * 0.12f, p.warnInk, h * 0.022f, t)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.sceneFind(
    p: Palette, t: Float, w: Float, h: Float, found: Boolean = false,
) {
    drawBlobs(p.infoFill, t)
    val cx = w * 0.44f
    val cy = h * 0.46f
    val r = h * 0.22f
    drawCircle(p.primarySolid, radius = r, center = Offset(cx, cy),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = h * 0.028f))
    drawLine(p.primarySolid, Offset(cx + r * 0.7f, cy + r * 0.7f),
        Offset(cx + r * 1.3f, cy + r * 1.3f), strokeWidth = h * 0.032f,
        cap = androidx.compose.ui.graphics.StrokeCap.Round)
    if (found) {
        drawCheck(cx, cy, r * 0.45f, p.primarySolid, h * 0.028f)
        drawConfetti(listOf(p.success, p.primarySolid), t)
    } else {
        drawGlobe(cx, cy, r * 0.5f, p.textTertiary, h * 0.014f)
    }
    drawCard(w * 0.76f, h * 0.66f, w * 0.22f, h * 0.18f, p.card, p.textTertiary, t)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.scenePhone(
    p: Palette, t: Float, w: Float, h: Float, ok: Boolean,
) {
    drawBlobs(p.infoFill, t)
    val pw = w * 0.22f
    val ph = h * 0.62f
    val cx = w * 0.42f
    val cy = h * 0.50f
    drawRoundRect(p.card, Offset(cx - pw / 2, cy - ph / 2), Size(pw, ph),
        cornerRadiusX = pw * 0.18f, cornerRadiusY = pw * 0.18f)
    drawRoundRect(brandBrush(pw, ph, p.primaryTop, p.primaryBottom),
        Offset(cx - pw * 0.4f, cy - ph * 0.42f), Size(pw * 0.8f, ph * 0.84f),
        cornerRadiusX = pw * 0.1f, cornerRadiusY = pw * 0.1f)
    if (ok) {
        drawCheck(cx, cy, pw * 0.2f, Color.White, pw * 0.08f)
        drawChip(w * 0.72f, h * 0.36f, w * 0.20f, h * 0.18f, p.card, p.successInk, "check", t)
    } else {
        drawCode(p, t, w, h, cx, cy, pw)
    }
    drawSparkleField(p.primarySolid, t, seed = 0.8f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCode(
    p: Palette, t: Float, w: Float, h: Float, cx: Float, cy: Float, pw: Float,
) {
    val digits = listOf(-0.24f, 0f, 0.24f)
    digits.forEachIndexed { i, dx ->
        val a = 0.5f + 0.5f * artPulse(t, 2f, i * 0.35f)
        drawRoundRect(Color.White.copy(alpha = a),
            Offset(cx + dx * pw - pw * 0.06f, cy - pw * 0.1f),
            Size(pw * 0.12f, pw * 0.2f),
            cornerRadiusX = pw * 0.03f, cornerRadiusY = pw * 0.03f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.sceneTwoFactor(
    p: Palette, t: Float, w: Float, h: Float, state: String,
) {
    drawBlobs(p.infoFill, t)
    val glyph = if (state == "on" || state == "confirm") "check" else "lock"
    drawShield(w * 0.40f, h * 0.48f, w * 0.30f, h * 0.60f,
        p.primaryTop, p.primaryBottom, Color.White, glyph, t)
    when (state) {
        "on" -> drawChip(w * 0.72f, h * 0.36f, w * 0.20f, h * 0.18f, p.card, p.successInk, "check", t)
        "off" -> drawAlert(w * 0.72f, h * 0.38f, h * 0.10f, p.warnInk, h * 0.02f, t)
        "confirm" -> {
            drawAlert(w * 0.74f, h * 0.36f, h * 0.09f, p.warnInk, h * 0.018f, t)
            drawTrash(w * 0.70f, h * 0.66f, h * 0.08f, p.error, h * 0.016f)
        }
        else -> drawKey(w * 0.72f, h * 0.40f, h * 0.08f, p.primarySolid, h * 0.018f)
    }
    drawSparkleField(p.primarySolid, t, seed = 2f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.scenePassword(
    p: Palette, t: Float, w: Float, h: Float, state: String,
) {
    drawBlobs(p.infoFill, t)
    drawCard(w * 0.40f, h * 0.48f, w * 0.30f, h * 0.40f, p.card, p.textTertiary, t)
    val dots = listOf(-0.08f, 0f, 0.08f)
    dots.forEachIndexed { i, dx ->
        drawCircle(p.textTertiary, radius = h * 0.018f,
            center = Offset(w * 0.40f + dx * w, h * 0.48f))
    }
    when (state) {
        "ok", "updated" ->
            drawChip(w * 0.72f, h * 0.42f, w * 0.20f, h * 0.18f, p.card, p.successInk, "check", t)
        "confirm" ->
            drawKey(w * 0.72f, h * 0.42f, h * 0.09f, p.primarySolid, h * 0.02f)
        "after" ->
            drawShield(w * 0.72f, h * 0.44f, w * 0.14f, h * 0.28f,
                p.primaryTop, p.primaryBottom, Color.White, "lock", t)
        else ->
            drawKey(w * 0.72f, h * 0.42f, h * 0.09f, p.textTertiary, h * 0.02f)
    }
    drawSparkleField(p.primarySolid, t, seed = 0.5f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.sceneSignOut(
    p: Palette, t: Float, w: Float, h: Float, ok: Boolean,
) {
    drawBlobs(p.infoFill, t)
    drawDevices(w * 0.44f, h * 0.50f, w * 0.44f, h * 0.60f,
        p.primaryTop, p.primaryBottom, Color.White, p.card, t, checkOn = false)
    drawLogout(w * 0.78f, h * 0.34f, h * 0.10f,
        if (ok) p.successInk else p.primarySolid, h * 0.02f, t)
    if (ok) drawCheck(w * 0.78f, h * 0.62f, h * 0.05f, p.successInk, h * 0.016f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.sceneSessions(
    p: Palette, t: Float, w: Float, h: Float, state: String,
) {
    drawBlobs(p.infoFill, t)
    when (state) {
        "list" -> drawDevices(w * 0.48f, h * 0.50f, w * 0.52f, h * 0.66f,
            p.primaryTop, p.primaryBottom, Color.White, p.card, t, checkOn = true)
        "revoke" -> {
            drawDevices(w * 0.46f, h * 0.52f, w * 0.46f, h * 0.58f,
                p.primaryTop, p.primaryBottom, Color.White, p.card, t, checkOn = false)
            drawTrash(w * 0.78f, h * 0.34f, h * 0.09f, p.error, h * 0.018f)
        }
        "revoked" -> {
            drawDevices(w * 0.46f, h * 0.52f, w * 0.46f, h * 0.58f,
                p.primaryTop, p.primaryBottom, Color.White, p.card, t, checkOn = false)
            drawCheck(w * 0.78f, h * 0.34f, h * 0.06f, p.successInk, h * 0.02f)
        }
        else -> {
            drawDevices(w * 0.46f, h * 0.52f, w * 0.46f, h * 0.58f,
                p.primaryTop, p.primaryBottom, Color.White, p.card, t, checkOn = false)
            drawAlert(w * 0.78f, h * 0.32f, h * 0.09f, p.warnInk, h * 0.018f, t)
        }
    }
    drawSparkleField(p.primarySolid, t, seed = 1.7f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.sceneActivity(
    p: Palette, t: Float, w: Float, h: Float,
) {
    drawBlobs(p.infoFill, t)
    drawCard(w * 0.44f, h * 0.50f, w * 0.34f, h * 0.56f, p.card, p.textTertiary, t)
    drawActivity(w * 0.74f, h * 0.44f, w * 0.22f, p.primarySolid, h * 0.02f, t)
    drawList(w * 0.74f, h * 0.66f, w * 0.16f, h * 0.16f, p.textTertiary, h * 0.016f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.sceneAlert(
    p: Palette, t: Float, w: Float, h: Float,
) {
    drawBlobs(p.infoFill, t)
    drawAlert(w * 0.42f, h * 0.46f, h * 0.16f, p.warnInk, h * 0.026f, t)
    drawBell(w * 0.70f, h * 0.40f, h * 0.10f, p.primarySolid, h * 0.02f)
    drawCard(w * 0.62f, h * 0.68f, w * 0.30f, h * 0.20f, p.card, p.textTertiary, t)
    drawSparkleField(p.primarySolid, t, seed = 0.2f)
}
