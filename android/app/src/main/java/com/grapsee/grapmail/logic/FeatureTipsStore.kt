package com.grapsee.grapmail.logic

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The first-time feature tips (ui/components/FeatureTip.kt), in the order a
 * session offers them. [key] is the stored name, so reordering or renaming an
 * entry never resurrects a tip somebody already dismissed.
 *
 * [SafetyScore] is not a mailbox tip: it is offered only by the Security
 * Center's first visit (round8-spec §4.5), never picked by [FeatureTipsStore.onMailboxSettled].
 */
enum class FeatureTipId(val key: String) {
    Swipe("swipe"),
    LongPress("long_press"),
    Categories("categories"),
    Screener("screener"),
    Accounts("accounts"),
    Snooze("snooze"),
    SafetyScore("safety_score");

    /** Picked by the mailbox's once-per-session choice. */
    val mailbox: Boolean get() = this != SafetyScore
}

/**
 * Which tip, if any, this app session shows — and which ones are done for good.
 *
 * Rules, all enforced here so no screen can break them:
 *  - At most ONE tip per app session (process). The choice is made once, when
 *    the mailbox has finished loading and sat still for a moment
 *    ([onMailboxSettled]); after that tip is dismissed nothing else appears
 *    until the next launch.
 *  - Never in the first session that reaches the mailbox. That session is the
 *    sign-up / onboarding one and the first mailbox load, so the counter below
 *    starts tips from the second.
 *  - A tip dismissed ("Got it", the close X, or using the feature itself) is
 *    stored per install and never shown again.
 *
 * SharedPreferences, like [MailFeaturePrefs] — no new dependency. Its own file,
 * so clearing tips can never touch a real setting.
 */
object FeatureTipsStore {
    private const val PREFS_NAME = "grapmail_feature_tips"
    private const val K_SESSIONS = "mailbox_sessions"
    private const val K_DISMISSED = "dismissed_"

    private val _active = MutableStateFlow<FeatureTipId?>(null)

    /** The tip this session is showing, or null. Anchors observe this. */
    val active: StateFlow<FeatureTipId?> = _active.asStateFlow()

    @Volatile private var sessionDecided = false
    /** A tip has been put on screen in this session (process). */
    @Volatile private var sessionTipShown = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDismissed(context: Context, tip: FeatureTipId): Boolean =
        prefs(context).getBoolean(K_DISMISSED + tip.key, false)

    /**
     * Called once the mailbox has loaded and settled. The first call of a
     * process counts the session and picks this session's tip; every later
     * call does nothing.
     */
    @Synchronized
    fun onMailboxSettled(context: Context) {
        if (sessionDecided) return
        sessionDecided = true
        val p = prefs(context)
        val sessions = p.getInt(K_SESSIONS, 0) + 1
        p.edit().putInt(K_SESSIONS, sessions).apply()
        if (sessions < 2) return
        if (sessionTipShown) return
        val pick = FeatureTipId.entries.firstOrNull { it.mailbox && !p.getBoolean(K_DISMISSED + it.key, false) }
        if (pick != null) sessionTipShown = true
        _active.value = pick
    }

    /**
     * round8-spec §4.5: the Security Center's first visit offers the
     * "Your account safety score" tip, anchored to the ring — once, and never in
     * a session that already showed another tip. It is stored as done the
     * moment it is shown, so leaving the screen without tapping "Got it" does
     * not bring it back; and no mailbox tip follows it in the same session.
     */
    @Synchronized
    fun onSecurityCenterVisited(context: Context) {
        if (sessionTipShown) return
        val p = prefs(context)
        val key = K_DISMISSED + FeatureTipId.SafetyScore.key
        if (p.getBoolean(key, false)) return
        p.edit().putBoolean(key, true).apply()
        // onMailboxSettled still counts this session; it just picks nothing,
        // because sessionTipShown is set.
        sessionTipShown = true
        _active.value = FeatureTipId.SafetyScore
    }

    /** The screen holding [tip]'s anchor went away: close it without picking another. */
    fun closeIfShowing(tip: FeatureTipId) {
        _active.compareAndSet(tip, null)
    }

    /**
     * The tip is done: dismissed from its card, or the person used the feature
     * it describes. Stored for good; if it was this session's tip it closes,
     * and no other tip replaces it this session.
     */
    fun dismiss(context: Context, tip: FeatureTipId) {
        val p = prefs(context)
        if (!p.getBoolean(K_DISMISSED + tip.key, false)) {
            p.edit().putBoolean(K_DISMISSED + tip.key, true).apply()
        }
        _active.compareAndSet(tip, null)
    }
}
