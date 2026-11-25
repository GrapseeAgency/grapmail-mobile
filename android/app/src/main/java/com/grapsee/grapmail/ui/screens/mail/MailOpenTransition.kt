@file:OptIn(
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    androidx.compose.animation.ExperimentalAnimationApi::class,
)

package com.grapsee.grapmail.ui.screens.mail

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * The phone's "email grows out of its row" opening.
 *
 * The NavHost sits in a `SharedTransitionLayout` (MainActivity) which provides
 * [LocalMailSharedScope]; each destination that takes part provides its own
 * `AnimatedContentScope` as [LocalMailAnimatedScope]. The tapped row and the
 * reader's surface then share bounds under one key, `mail-<thread id>`: the
 * row's container grows into the reader, the reader's body fades in a beat
 * later, and Back (including the predictive back gesture, which seeks the same
 * transition) shrinks it back into the row.
 *
 * Only ONE row ever carries the modifier — the one named in [MailOpenTransition]
 * when it was tapped — so scrolling the list pays nothing, and two rows of the
 * same conversation can never claim the same key. A thread opened from a
 * notification, a deep link, the widget or the reader's own previous / next
 * has no source row: the holder is cleared and the ordinary slide + fade runs.
 *
 * With "Remove animations" (ANIMATOR_DURATION_SCALE == 0) nothing here is
 * applied and the reader route switches instantly.
 */
internal const val MailOpenMillis = 320

/** Material's emphasized easing, for the body that fades in after the bounds. */
private val MailOpenEmphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

internal val LocalMailSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
internal val LocalMailAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Which row the reader is being opened from. Set by the list's tap on phones,
 * cleared by every other door into the reader. Read inside the NavHost's
 * transition lambdas, so it is snapshot state rather than a captured value.
 */
@Stable
internal class MailOpenTransition {
    /** The list row's own id (unique per row). */
    var rowId by mutableStateOf<String?>(null)
        private set

    /** The id the reader route carries (the thread id, or the message id for a lone message). */
    var threadKey by mutableStateOf<String?>(null)
        private set

    /** Settings → Developer options / Accessibility → "Remove animations". */
    var reducedMotion by mutableStateOf(false)

    /** A tap is waiting one frame for its row to get the modifier before navigating. */
    var navigating = false

    fun fromRow(threadKey: String, rowId: String) {
        if (reducedMotion) { clear(); return }
        this.threadKey = threadKey
        this.rowId = rowId
    }

    fun clear() {
        threadKey = null
        rowId = null
    }

    /** True when the reader for [threadKey] has a source row to grow out of. */
    fun opensFromRow(threadKey: String?): Boolean =
        !reducedMotion && rowId != null && threadKey != null && this.threadKey == threadKey

    /** The shared key for the reader of [threadKey], or null when it has no source row. */
    fun readerKey(threadKey: String): String? =
        if (opensFromRow(threadKey)) sharedKey(threadKey) else null

    companion object {
        fun sharedKey(threadKey: String) = "mail-$threadKey"
    }
}

internal fun readReducedMotion(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

/** The system "Remove animations" switch, re-read whenever the app resumes. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember(context) { mutableStateOf(readReducedMotion(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reduced = readReducedMotion(context) }
    return reduced
}

private val MailOpenBounds = BoundsTransform { _, _ ->
    tween<Rect>(MailOpenMillis, easing = FastOutSlowInEasing)
}

/**
 * Share bounds under [key] with the other side of the opening. A null key, or a
 * host that provides no scopes (two-pane home, snapshot host, previews), leaves
 * the modifier untouched.
 *
 * ScaleToBounds(FillWidth, TopCenter) + a rectangular overlay clip is the clean
 * resize: neither side is re-measured per frame (the reader keeps its full-size
 * layout and is revealed by the growing clip; the row keeps its own), and the
 * widths are nearly equal, so nothing visibly stretches.
 */
@Composable
internal fun Modifier.mailOpenSharedBounds(
    key: String?,
    enter: EnterTransition,
    exit: ExitTransition,
): Modifier {
    if (key == null) return this
    val shared = LocalMailSharedScope.current ?: return this
    val animated = LocalMailAnimatedScope.current ?: return this
    val state = with(shared) { rememberSharedContentState(key) }
    return with(shared) {
        this@mailOpenSharedBounds.sharedBounds(
            sharedContentState = state,
            animatedVisibilityScope = animated,
            enter = enter,
            exit = exit,
            boundsTransform = MailOpenBounds,
            resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(ContentScale.FillWidth, Alignment.TopCenter),
            clipInOverlayDuringTransition = OverlayClip(RectangleShape),
        )
    }
}

/** The row's side: its content fades out fast as it grows, and back in late as it lands. */
@Composable
internal fun Modifier.mailOpenRowBounds(key: String?): Modifier = mailOpenSharedBounds(
    key,
    enter = fadeIn(tween(MailOpenMillis - 120, delayMillis = 120)),
    exit = fadeOut(tween(120)),
)

/** The reader's side: its surface arrives quickly so the growing container reads as one card. */
@Composable
internal fun Modifier.mailOpenReaderBounds(key: String?): Modifier = mailOpenSharedBounds(
    key,
    enter = fadeIn(tween(120)),
    exit = fadeOut(tween(120, delayMillis = MailOpenMillis - 160)),
)

/**
 * The reader's body (toolbar, messages, reply bar) fades in slightly after the
 * container has started to grow, and leaves first on the way back.
 */
@Composable
internal fun Modifier.mailOpenBodyFade(active: Boolean): Modifier {
    if (!active) return this
    if (LocalMailSharedScope.current == null) return this
    val animated = LocalMailAnimatedScope.current ?: return this
    return with(animated) {
        this@mailOpenBodyFade.animateEnterExit(
            enter = fadeIn(tween(MailOpenMillis - 110, delayMillis = 110, easing = MailOpenEmphasized)),
            exit = fadeOut(tween(100)),
        )
    }
}
