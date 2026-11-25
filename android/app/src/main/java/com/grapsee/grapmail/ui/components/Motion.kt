package com.grapsee.grapmail.ui.components

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * The app's micro-motion vocabulary: one reduced-motion gate and the few
 * durations and curves the small icon animations use.
 *
 * The gate is the system's own "Remove animations" switch, which sets
 * `ANIMATOR_DURATION_SCALE` to 0 — the same reading [com.grapsee.grapmail.ui.artwork.rememberArtClock]
 * already honours for the artwork. When it is on, every caller here skips the
 * motion and shows the end state straight away; nothing is hidden behind an
 * animation, so state is never lost by skipping one. Previews and the CI
 * snapshot host's inspection mode count as reduced, so a photograph is always
 * of a settled frame.
 *
 * Rules every user of this file keeps: 150–300 ms, standard or emphasized
 * easing, springs damped enough not to wobble, and motion only in response to
 * a state change — never a loop, never on scroll.
 */
object GMMotion {
    const val ShortMs = 150
    const val MediumMs = 220
    const val LongMs = 300

    /** Material 3 standard easing. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Material 3 emphasized-decelerate: things arriving. */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Material 3 emphasized-accelerate: things leaving. */
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** A tween, or an instant snap when motion is reduced. */
    fun <T> tween(reduced: Boolean, durationMillis: Int = MediumMs, easing: Easing = Standard): AnimationSpec<T> =
        if (reduced) snap() else androidx.compose.animation.core.tween(durationMillis, easing = easing)

    /** A settle spring with at most one small overshoot, or a snap. */
    fun <T> settle(reduced: Boolean): AnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
}

/** True when the system animator scale is 0 ("Remove animations"). */
fun isReducedMotion(context: android.content.Context): Boolean =
    runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

/**
 * The reduced-motion reading for the small icon animations, re-read whenever
 * the app resumes (the switch lives in system settings, so a change can only
 * happen while the app is in the background). The same reading, and the same
 * resume refresh, as the mail-open transition's own `rememberReducedMotion`
 * in ui/screens/mail/MailOpenTransition.kt; named apart so the two never
 * resolve ambiguously in that package.
 */
@Composable
fun rememberMotionReduced(): Boolean {
    val context = LocalContext.current
    if (LocalInspectionMode.current) return true
    var reduced by remember(context) { mutableStateOf(isReducedMotion(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reduced = isReducedMotion(context) }
    return reduced
}
