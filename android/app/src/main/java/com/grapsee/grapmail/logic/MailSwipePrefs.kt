package com.grapsee.grapmail.logic

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What a swipe on a mailbox row does: one choice per side, kept on THIS
 * device (SharedPreferences, the same pattern as MailDisplayPrefs and
 * MailFeaturePrefs). It is a hand preference, not an account fact, so it is
 * not written to the profile and a second phone keeps its own pair.
 *
 * "Right" is the physical direction the finger travels (the row's leading
 * reveal in MailSwipeRow.kt, `offset > 0`), in LTR and RTL alike, because a
 * gesture is learned by the hand, not by the reading direction.
 *
 * Defaults are the behaviour that shipped before the setting existed:
 * swipe right archives, swipe left moves to Trash — the same pair the
 * swipe feature tip (strings_tips.xml `tip_swipe_body`) describes. The tip
 * is retired the moment either side is changed (see [set]), so its text is
 * only ever shown while it is true.
 *
 * Snooze is offered on Android: the app has a snooze sheet (SnoozeContent in
 * MailboxScreen.kt) and the `snooze` action route, so a swipe opens that
 * sheet for the row.
 */
enum class SwipeAction(val key: String) {
    Archive("archive"),
    Trash("trash"),
    ReadToggle("read"),
    Star("star"),
    Snooze("snooze"),
    Move("move"),
    None("none"),
    ;

    companion object {
        fun fromKey(key: String?, fallback: SwipeAction): SwipeAction =
            entries.firstOrNull { it.key == key } ?: fallback
    }
}

object MailSwipePrefs {
    private const val PREFS = "grapmail-swipe"
    private const val K_RIGHT = "right"
    private const val K_LEFT = "left"

    val DefaultRight = SwipeAction.Archive
    val DefaultLeft = SwipeAction.Trash

    /** The options the setting lists, in the order it lists them. */
    val choices: List<SwipeAction> = listOf(
        SwipeAction.Archive, SwipeAction.Trash, SwipeAction.ReadToggle, SwipeAction.Star,
        SwipeAction.Snooze, SwipeAction.Move, SwipeAction.None,
    )

    /** Snapshot state, so a change in Settings redraws the list on the way back. */
    var right by mutableStateOf(DefaultRight)
        private set
    var left by mutableStateOf(DefaultLeft)
        private set

    @Volatile private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        right = SwipeAction.fromKey(prefs.getString(K_RIGHT, null), DefaultRight)
        left = SwipeAction.fromKey(prefs.getString(K_LEFT, null), DefaultLeft)
        loaded = true
    }

    val isDefault: Boolean get() = right == DefaultRight && left == DefaultLeft

    fun setRight(context: Context, action: SwipeAction) = set(context, rightSide = true, action = action)

    fun setLeft(context: Context, action: SwipeAction) = set(context, rightSide = false, action = action)

    private fun set(context: Context, rightSide: Boolean, action: SwipeAction) {
        ensureLoaded(context)
        if (rightSide) right = action else left = action
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(if (rightSide) K_RIGHT else K_LEFT, action.key)
            .apply()
        // The swipe tip says "right to archive, left to Trash". Once the pair
        // is the person's own, that sentence is no longer true, and they have
        // plainly found the gesture — so the tip is retired for good.
        if (!isDefault) FeatureTipsStore.dismiss(context, FeatureTipId.Swipe)
    }
}
