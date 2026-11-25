package com.grapsee.grapmail.logic

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.first
import com.grapsee.grapmail.R

/**
 * The app lock's state and the platform's own prompt.
 *
 * Nothing here invents an authentication method: the mailbox is opened with
 * whatever the device already trusts — face, fingerprint, or the device
 * credential where the OS folds them together — through androidx.biometric's
 * prompt, the same one every other serious app shows. The preference is
 * per-device state, not an account setting: it guards this handset's view of
 * the mailbox, and remembering it is the only job this file has.
 */
object AppLock {
    private const val PREFS = "grapmail-applock"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Whether the door (`AppLockGate`) is drawn closed right now.
     *
     * The gate owns the decision; this only publishes it, so code that is not
     * a composable under the gate can wait for it. The readers are a deep link
     * (`logic/DeepLinks.kt`) and a notification tap: either may arrive while
     * the door is shut, and opening the thread under the overlay would still
     * fetch it and mark it read for a person who has not proved they are the
     * owner. They park until [awaitOpen] returns instead.
     *
     * Written by the gate at the three moments it changes its mind -- first
     * composition, ON_STOP, a verified prompt -- directly rather than through
     * recomposition, because the frame clock is paused while the activity is
     * stopped and a link delivered by onNewIntent must already see "closed".
     */
    private val gateClosed = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun markGateClosed(closed: Boolean) {
        gateClosed.value = closed
    }

    /** Suspends until the gate is open. Returns at once when the lock is off. */
    suspend fun awaitOpen() {
        gateClosed.first { !it }
    }

    /** True only when the platform itself says a prompt can be shown. */
    fun available(context: Context): Boolean =
        androidx.biometric.BiometricManager.from(context).canAuthenticate(authenticators()) ==
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS

    /** BIOMETRIC_WEAK keeps face unlock in play; the credential joins on 30+. */
    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= 30) {
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
        }

    /**
     * Show the platform's prompt. [onResult] hears only the two facts the gate
     * needs: the person was verified, or the prompt closed without a verdict.
     */
    fun prompt(activity: androidx.fragment.app.FragmentActivity, onResult: (Boolean) -> Unit) {
        val prompt = androidx.biometric.BiometricPrompt(
            activity,
            androidx.core.content.ContextCompat.getMainExecutor(activity),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: androidx.biometric.BiometricPrompt.AuthenticationResult,
                ) {
                    onResult(true)
                }

                override fun onAuthenticationError(code: Int, errString: CharSequence) {
                    onResult(false)
                }

                // A single unreadable scan is not a refusal: the platform keeps
                // the prompt up and tries again on its own.
                override fun onAuthenticationFailed() = Unit
            },
        )
        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.core_applock_prompt_title))
            .setSubtitle(activity.getString(R.string.core_applock_prompt_subtitle))
        if (Build.VERSION.SDK_INT >= 30) {
            info.setAllowedAuthenticators(authenticators())
        } else {
            info.setNegativeButtonText(activity.getString(R.string.core_applock_prompt_cancel))
        }
        prompt.authenticate(info.build())
    }
}
