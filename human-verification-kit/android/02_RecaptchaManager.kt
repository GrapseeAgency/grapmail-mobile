package com.grapsee.grapmail.logic

import android.content.Context
import com.google.android.recaptcha.Recaptcha
import com.google.android.recaptcha.RecaptchaAction
import com.google.android.recaptcha.RecaptchaClient

/**
 * OPTIONAL (P2): reCAPTCHA Enterprise native client — a frictionless, score-based
 * layer on top of the REQUIRED visible challenge (the /verify/human checkpoint
 * WebView in HumanVerifyScreen). The native SDK never shows UI; it returns a
 * token you can attach to GrapMail API calls as an extra abuse signal.
 *
 * Dependency (add to app/build.gradle.kts — see PATCHES-android.md):
 *   implementation("com.google.android.recaptcha:recaptcha:18.9.4")
 * Requirements: minSdk 23+, INTERNET permission, google() Maven repo.
 *
 * Version note: use >= 18.9.4 (older 18.x builds have known issues). The
 * signatures below follow docs.cloud.google.com/recaptcha/docs/instrument-android-apps —
 * re-check them against the SDK version you adopt.
 */
object RecaptchaManager {

    // TODO(deploy): the Enterprise site key for this app (reCAPTCHA admin console).
    private const val SITE_KEY = "YOUR_ENTERPRISE_SITE_KEY"

    @Volatile
    private var client: RecaptchaClient? = null

    /**
     * Warm up early — e.g. from Application.onCreate. Initialization runs in
     * the background and retries on network failure; never block UI on it.
     */
    fun warmup(context: Context) {
        if (client != null) return
        Recaptcha.fetchClient(context.applicationContext, SITE_KEY)
            .onSuccess { fetched -> client = fetched }
        // Failure is silent by design: the visible checkpoint remains the primary gate.
    }

    /**
     * Execute for a sensitive action. Tokens are single-use, valid ~2 minutes —
     * fetch right before the call that consumes them.
     */
    fun execute(
        action: RecaptchaAction = RecaptchaAction.LOGIN,
        timeoutMs: Long = 10_000L,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val current = client
        if (current == null) {
            onFailure(IllegalStateException("reCAPTCHA client not warmed up yet — call warmup() early."))
            return
        }
        current.execute(action, timeoutMs)
            .onSuccess(onSuccess)
            .onFailure(onFailure)
    }
}
