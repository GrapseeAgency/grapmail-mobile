package com.grapsee.grapmail.logic

import android.app.Activity
import android.content.Context
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The "a new version is available" overlay, driven by the Play Store itself.
 *
 * The store is the source of truth: an overlay is offered only when Play says
 * a compatible update exists for this install — honest on day one (nothing to
 * update → nothing shows) and correct forever after. The check runs when the
 * app opens, throttled to once per day; the store's cached answer is reused
 * inside that window.
 */
object AppUpdate {
    private const val PREFS = "grapmail-update"
    private const val KEY_LAST_CHECK = "last-check-millis"
    private const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    private const val UPDATE_REQUEST_CODE = 1001

    private val _available = MutableStateFlow(false)
    private val _bannerDismissed = MutableStateFlow(false)

    /** True only while the overlay should be on screen this session. */
    val bannerVisible: StateFlow<Boolean> = combine(
        _available, _bannerDismissed,
    ) { available, dismissed -> available && !dismissed }
        .stateIn(
            kotlinx.coroutines.GlobalScope,
            kotlinx.coroutines.flow.SharingStarted.Lazily, false,
        )

    @Volatile private var pendingInfo: AppUpdateInfo? = null

    /** The person closed the overlay — it comes back with tomorrow's daily
     *  check, not with every screen change. */
    fun dismiss() {
        _bannerDismissed.value = true
    }

    suspend fun checkOnOpen(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        _bannerDismissed.value = false
        if (_available.value && System.currentTimeMillis() - last < CHECK_INTERVAL_MILLIS) return
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        val manager = AppUpdateManagerFactory.create(activity)
        manager.appUpdateInfo.addOnSuccessListener { info ->
            val canUpdate = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
            if (canUpdate) {
                pendingInfo = info
                _available.value = true
            } else {
                pendingInfo = null
                _available.value = false
            }
        }
    }

    /** The overlay's Update button hands control to the store's own flow. */
    fun startUpdate(activity: Activity) {
        val info = pendingInfo ?: return
        val manager = AppUpdateManagerFactory.create(activity)
        val options = AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
        runCatching { manager.startUpdateFlowForResult(info, activity, options, UPDATE_REQUEST_CODE) }
    }
}
