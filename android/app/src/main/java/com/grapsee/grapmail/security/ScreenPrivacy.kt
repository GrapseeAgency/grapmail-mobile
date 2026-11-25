package com.grapsee.grapmail.security

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-switcher and screenshot privacy (OWASP MASVS-PLATFORM-3 / MASVS-L2).
 *
 * While a session exists the window carries FLAG_SECURE: the system refuses
 * screenshots and screen recording of it and draws a blank Recents thumbnail,
 * so an open thread is never left in the task switcher. The person can opt
 * out with the "Allow screenshots" setting (per device, default OFF). Signed
 * out (welcome, sign-in, sign-up) the flag is cleared: there is no mail on
 * those screens, and support screenshots of onboarding stay possible.
 *
 * The decision is [shouldSecure], a pure function, so its defaults are unit
 * tested (ScreenPrivacyTest). [apply] is the only place the window is touched
 * and must run on the main thread (MainActivity collects on lifecycleScope).
 */
object ScreenPrivacy {
    private const val PREFS = "grapmail-screen-privacy"
    private const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"

    /** The shipped default: screenshots are blocked while signed in. */
    const val DEFAULT_ALLOW_SCREENSHOTS = false

    private val allow = MutableStateFlow(DEFAULT_ALLOW_SCREENSHOTS)
    private var loaded = false

    /** The setting as a flow, so the window follows a toggle at once. */
    fun allowScreenshotsFlow(context: Context): StateFlow<Boolean> {
        load(context)
        return allow.asStateFlow()
    }

    fun allowScreenshots(context: Context): Boolean {
        load(context)
        return allow.value
    }

    fun setAllowScreenshots(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ALLOW_SCREENSHOTS, value).apply()
        loaded = true
        allow.value = value
    }

    private fun load(context: Context) {
        if (loaded) return
        allow.value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALLOW_SCREENSHOTS, DEFAULT_ALLOW_SCREENSHOTS)
        loaded = true
    }

    /** Pure: secure the window exactly when signed in and not opted out. */
    fun shouldSecure(signedIn: Boolean, allowScreenshots: Boolean): Boolean =
        signedIn && !allowScreenshots

    /** Main thread only. Idempotent: setting the same flag twice is a no-op. */
    fun apply(activity: Activity, signedIn: Boolean, allowScreenshots: Boolean) {
        if (shouldSecure(signedIn, allowScreenshots)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
