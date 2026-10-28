package com.grapsee.grapmail.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.grapsee.grapmail.R

/**
 * Theme-aware illustration picker: the founder's designs ship paired light
 * and dark artwork, so dark mode uses the dark crops — never a tinted light
 * image glaring on a dark surface.
 */
@Composable
fun illRes(name: String): Int {
    val dark = isSystemInDarkTheme()
    return when (name) {
        "verify" -> if (dark) R.drawable.ill_verify_dark else R.drawable.ill_verify
        "welcome" -> if (dark) R.drawable.ill_welcome_dark else R.drawable.ill_welcome
        "step1_name" -> if (dark) R.drawable.ill_step1_name_dark else R.drawable.ill_step1_name
        "created_a" -> if (dark) R.drawable.ill_created_a_dark else R.drawable.ill_created_a
        "created_b" -> if (dark) R.drawable.ill_created_b_dark else R.drawable.ill_created_b
        "puzzle" -> if (dark) R.drawable.puzzle_photo_dark else R.drawable.puzzle_photo
        // Recovery/security sector — cropped from raw-designs/account/ by
        // scripts/crop-account-illustrations.py.
        "account-2fa" -> if (dark) R.drawable.ill_account_2fa_dark else R.drawable.ill_account_2fa
        "account-backup" -> if (dark) R.drawable.ill_account_backup_dark else R.drawable.ill_account_backup
        "account-forgot" -> if (dark) R.drawable.ill_account_forgot_dark else R.drawable.ill_account_forgot
        "account-recovery-code" -> if (dark) R.drawable.ill_account_recovery_code_dark else R.drawable.ill_account_recovery_code
        "account-new-password" -> if (dark) R.drawable.ill_account_new_password_dark else R.drawable.ill_account_new_password
        "account-reset-ok" -> if (dark) R.drawable.ill_account_reset_ok_dark else R.drawable.ill_account_reset_ok
        "account-pending" -> if (dark) R.drawable.ill_account_pending_dark else R.drawable.ill_account_pending
        "account-find-address" -> if (dark) R.drawable.ill_account_find_address_dark else R.drawable.ill_account_find_address
        "account-address-found" -> if (dark) R.drawable.ill_account_address_found_dark else R.drawable.ill_account_address_found
        "account-phone" -> if (dark) R.drawable.ill_account_phone_dark else R.drawable.ill_account_phone
        "account-phone-ok" -> if (dark) R.drawable.ill_account_phone_ok_dark else R.drawable.ill_account_phone_ok
        // Security sector — cropped from .local/refs-security/ by
        // scripts/crop-security-illustrations.py.
        "ill_sec_hub" -> if (dark) R.drawable.ill_sec_hub_dark else R.drawable.ill_sec_hub
        "ill_sec_hub_secure" -> if (dark) R.drawable.ill_sec_hub_secure_dark else R.drawable.ill_sec_hub_secure
        "ill_sec_2fa" -> if (dark) R.drawable.ill_sec_2fa_dark else R.drawable.ill_sec_2fa
        "ill_sec_password_confirm" -> if (dark) R.drawable.ill_sec_password_confirm_dark else R.drawable.ill_sec_password_confirm
        "ill_sec_phone" -> if (dark) R.drawable.ill_sec_phone_dark else R.drawable.ill_sec_phone
        "ill_sec_2fa_on" -> if (dark) R.drawable.ill_sec_2fa_on_dark else R.drawable.ill_sec_2fa_on
        "ill_sec_backup_generate" -> if (dark) R.drawable.ill_sec_backup_generate_dark else R.drawable.ill_sec_backup_generate
        "ill_sec_backup_saved" -> if (dark) R.drawable.ill_sec_backup_saved_dark else R.drawable.ill_sec_backup_saved
        "ill_sec_2fa_off_confirm" -> if (dark) R.drawable.ill_sec_2fa_off_confirm_dark else R.drawable.ill_sec_2fa_off_confirm
        "ill_sec_2fa_off" -> if (dark) R.drawable.ill_sec_2fa_off_dark else R.drawable.ill_sec_2fa_off
        "ill_sec_password" -> if (dark) R.drawable.ill_sec_password_dark else R.drawable.ill_sec_password
        "ill_sec_password_ok" -> if (dark) R.drawable.ill_sec_password_ok_dark else R.drawable.ill_sec_password_ok
        "ill_sec_signout_all" -> if (dark) R.drawable.ill_sec_signout_all_dark else R.drawable.ill_sec_signout_all
        "ill_sec_signout_all_ok" -> if (dark) R.drawable.ill_sec_signout_all_ok_dark else R.drawable.ill_sec_signout_all_ok
        "ill_sec_sessions" -> if (dark) R.drawable.ill_sec_sessions_dark else R.drawable.ill_sec_sessions
        "ill_sec_session_revoke" -> if (dark) R.drawable.ill_sec_session_revoke_dark else R.drawable.ill_sec_session_revoke
        "ill_sec_session_revoked" -> if (dark) R.drawable.ill_sec_session_revoked_dark else R.drawable.ill_sec_session_revoked
        "ill_sec_activity" -> if (dark) R.drawable.ill_sec_activity_dark else R.drawable.ill_sec_activity
        "ill_sec_alert" -> if (dark) R.drawable.ill_sec_alert_dark else R.drawable.ill_sec_alert
        "ill_sec_secure" -> if (dark) R.drawable.ill_sec_secure_dark else R.drawable.ill_sec_secure
        "ill_sec_revoke_confirm" -> if (dark) R.drawable.ill_sec_revoke_confirm_dark else R.drawable.ill_sec_revoke_confirm
        "ill_sec_revoked_suspicious" -> if (dark) R.drawable.ill_sec_revoked_suspicious_dark else R.drawable.ill_sec_revoked_suspicious
        "ill_sec_password_after" -> if (dark) R.drawable.ill_sec_password_after_dark else R.drawable.ill_sec_password_after
        "ill_sec_password_updated" -> if (dark) R.drawable.ill_sec_password_updated_dark else R.drawable.ill_sec_password_updated
        else -> R.drawable.ill_welcome
    }
}
