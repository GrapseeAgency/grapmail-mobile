package com.grapsee.grapmail.logic

import android.content.Context
import android.os.Build

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
            .setTitle("Unlock GrapMail")
            .setSubtitle("Confirm it is you to open your mailbox")
        if (Build.VERSION.SDK_INT >= 30) {
            info.setAllowedAuthenticators(authenticators())
        } else {
            info.setNegativeButtonText("Cancel")
        }
        prompt.authenticate(info.build())
    }
}
