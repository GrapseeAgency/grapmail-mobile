package com.grapsee.grapmail.ui.screens.security

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Test-only entry into the screens a read-only CI walk can never reach — the
 * Android twin of the iOS `SecuritySnapshotView`.
 *
 * Every screen here is a confirmation that exists only AFTER a write: two-step
 * switched on or off, codes generated, a password changed, a session revoked.
 * Doing those writes in CI would destroy the account the rest of the walk
 * depends on — signing out everywhere ends the session the restore leg proves,
 * and a password change breaks the sign-in leg — so navigation cannot reach
 * them. The walk starts the activity with `--es gmSnapshot <name>` and the app
 * renders that one screen with closures that do nothing, exactly like a
 * navigation stub: no fetch, no submit.
 *
 * Nothing in the real flow ever passes the extra. The screens that fetch on
 * appear (the suspicious-sign-in alert and the flow behind it) are deliberately
 * absent — rendering them needs a server-seeded event, and a half-populated
 * screen photographed as if it were the real thing is worse than no screenshot
 * at all. The per-session revoke confirmations used to be excluded on the same
 * grounds: they refetched the session list to print one device label, until
 * that label began travelling with the route that already had it.
 *
 * The case list below IS the vocabulary; keep scripts/android-walk.sh in sync
 * with it.
 */
@Composable
fun SecuritySnapshotScreen(name: String) {
    // The marker the walk looks for to prove THIS screen is the one on screen.
    // iOS shipped that bug: a stored session pushed the signed-in home over the
    // stub, and an assertion that only checked for the absence of "Unknown
    // snapshot" passed eleven photographs of the wrong screen. Absence of a
    // failure message is not evidence; this node is.
    Box(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = "gm-snapshot" }
    ) {
        when (name) {
            "two-factor-on" -> TwoFactorEnabledScreen(onContinue = {}, onBackHome = {})
            "two-factor-off-form" -> DisableTwoFactorScreen(onBack = {}, onDisabled = {})
            "two-factor-off-done" -> TwoFactorDisabledScreen(onDone = {}, onSecureNow = {})
            "backup-codes-form" -> GenerateBackupCodesScreen(onBack = {}, onGenerated = {})
            "backup-codes-saved" -> BackupCodesSavedScreen(codes = sampleCodes, onDone = {})
            "change-password" ->
                ChangePasswordScreen(afterSuspicious = false, onBack = {}, onForgot = {}, onChanged = {})
            "password-changed" -> PasswordChangedScreen(updated = false, onDone = {}, onSupport = {})
            "sign-out-all-form" -> SignOutAllDevicesScreen(onBack = {}, onDone = {})
            "sign-out-all-done" -> SignedOutAllDevicesScreen(onDone = {})
            "session-revoked" ->
                SessionRevokedScreen(device = "Chrome on Windows · Dhaka, Bangladesh", onDone = {})
            "session-revoked-suspicious" ->
                SessionRevokedSuspiciousScreen(device = "Firefox on Linux · Lagos, Nigeria", onDone = {})
            "revoke-session-form" ->
                RevokeSessionScreen(sessionId = "snapshot-session",
                    device = "Chrome on Windows · Dhaka, Bangladesh", onBack = {}, onRevoke = {})
            "revoke-session-confirm" ->
                ConfirmSessionRevocationScreen(sessionId = "snapshot-session",
                    device = "Firefox on Linux · Lagos, Nigeria", onBack = {}, onRevoke = {})
            // The walk asserts this label is ABSENT, so an unrecognised name cannot
            // hand the artifact a placeholder photographed as if it were the screen.
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Unknown snapshot: $name")
            }
        }
    }
}

/** Codes in the format GrapMail actually issues — ten characters, two groups of five. */
private val sampleCodes = listOf(
    "A1B2C-D3E4F", "G5H6I-J7K8L", "M9N0P-Q1R2S", "T3U4V-W5X6Y", "Z7A8B-C9D0E",
    "F1G2H-J3K4L", "M5N6P-Q7R8S", "T9U0V-W1X2Y", "Z3A4B-C5D6E", "F7G8H-J9K0L",
)
