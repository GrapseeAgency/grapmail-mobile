package com.grapsee.grapmail.logic

import android.app.Activity
import android.content.Context
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * The rating ask, in the store's own voice.
 *
 * The trigger is a natural moment, not a timer: the third send since the last
 * ask. Someone who has sent three mails has used the app enough to have an
 * opinion, and the moment after a send is when the app just proved itself.
 * The store decides who actually sees the dialog (quota) — this only decides
 * when to offer, never more than once per 30 days either way.
 */
object AppReview {
    private const val PREFS = "grapmail-review"
    private const val KEY_SENDS = "sends-since-ask"
    private const val KEY_LAST = "last-ask-millis"
    private const val SEND_THRESHOLD = 3
    private const val ASK_INTERVAL_MILLIS = 30L * 24 * 60 * 60 * 1000

    fun countSend(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_SENDS, prefs.getInt(KEY_SENDS, 0) + 1).apply()
    }

    /** Launch the store review flow when the moment is earned. The store may
     *  decline to show anything (quota) and the flow completes silently. */
    fun maybeAsk(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_SENDS, 0) < SEND_THRESHOLD) return
        val last = prefs.getLong(KEY_LAST, 0L)
        if (last != 0L && System.currentTimeMillis() - last < ASK_INTERVAL_MILLIS) return
        prefs.edit()
            .putInt(KEY_SENDS, 0)
            .putLong(KEY_LAST, System.currentTimeMillis())
            .apply()
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnSuccessListener { reviewInfo ->
            manager.launchReviewFlow(activity, reviewInfo)
        }
    }
}
