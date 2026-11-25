package com.grapsee.grapmail.ui.screens.security

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.ui.components.AccountLink
import com.grapsee.grapmail.ui.components.AccountScreen
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.GMAutofill
import com.grapsee.grapmail.security.PasswordCredentials
import com.grapsee.grapmail.ui.components.FooterNote
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.InfoCard
import com.grapsee.grapmail.ui.components.RuleLine
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R

/**
 * 11 / 23 — change the password.
 *
 * One screen, two entry points: the hub's ordinary "change it" and the alert's
 * "change it now". The fields and the rules are identical; only the framing
 * differs, so the person is never asked to learn a second form under pressure.
 *
 * 23 exists because a password change after suspicious activity has to keep the
 * device that made the change signed in — all the others are cut off by the
 * epoch bump, and the reissued token is written back here.
 */
@Composable
fun ChangePasswordScreen(
    afterSuspicious: Boolean,
    onBack: () -> Unit,
    onForgot: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // These four ticks are exactly the four rules the server now enforces
    // (`passwordProblem`). The previous checklist asked for upper AND lower case,
    // which no route required — a tick that could not fail, next to a rule the
    // API would not honour.
    val longEnough = next.length >= 8
    // ASCII classes, matching `passwordProblem` on the server exactly. Kotlin's
    // isLetter()/isDigit() are Unicode-aware, so a password of `π²²²²²²²²` ticked
    // every box here and was then refused by the route — four green rules above an
    // error the screen could not explain.
    val isLetter = { ch: Char -> ch in 'A'..'Z' || ch in 'a'..'z' }
    val isDigit = { ch: Char -> ch in '0'..'9' }
    val hasLetter = next.any(isLetter)
    val hasNumber = next.any(isDigit)
    val hasSymbol = next.any { !isLetter(it) && !isDigit(it) }
    val matches = next.isNotEmpty() && next == confirm
    val rulesMet = longEnough && hasLetter && hasNumber && hasSymbol

    AccountScreen(
        onBack = onBack,
        illustration = if (afterSuspicious) "ill_sec_password_after" else "ill_sec_password",
        illustrationWidth = if (afterSuspicious) 180 else 150,
        eyebrow = if (afterSuspicious) stringResource(R.string.acct_after_suspicious_activity) else null,
        title = if (afterSuspicious) stringResource(R.string.acct_change_your_password) else stringResource(R.string.acct_change_password),
        body = if (afterSuspicious)
            stringResource(R.string.acct_your_account_was_accessed_from_an)
        else
            stringResource(R.string.acct_choose_a_strong_password_you_have),
        footer = {
            Column {
                GradientButton(
                    if (afterSuspicious) stringResource(R.string.acct_update_password) else stringResource(R.string.acct_change_password),
                    trailing = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 6.dp)) },
                    onClick = {
                        when {
                            current.isBlank() -> { error = context.getString(R.string.acct_enter_your_current_password); return@GradientButton }
                            !longEnough -> { error = context.getString(R.string.acct_use_at_least_8_characters); return@GradientButton }
                            !hasLetter -> { error = context.getString(R.string.acct_add_at_least_one_letter); return@GradientButton }
                            !hasNumber -> { error = context.getString(R.string.acct_add_at_least_one_number); return@GradientButton }
                            !hasSymbol -> { error = context.getString(R.string.acct_add_at_least_one_symbol); return@GradientButton }
                            !matches -> { error = context.getString(R.string.acct_the_two_new_passwords_do_not_match); return@GradientButton }
                        }
                        busy = true; error = null
                        scope.launch {
                            try {
                                val session = SessionStore.snapshot(context)
                                    ?: throw IllegalStateException(context.getString(R.string.acct_sign_in_again))
                                val newPassword = next
                                val resp = Api.changePassword(session.token, current, newPassword)
                                val active = SessionStore.snapshot(context)
                                if (active == null || active.id != session.id) return@launch
                                resp.accessToken?.let { fresh ->
                                    SessionStore.updateTokens(
                                        context, fresh, refreshToken = resp.refreshToken,
                                        expiresAt = resp.tokenExpiresAtMillis,
                                        expectedToken = active.token,
                                    )
                                }
                                // The server took the new password: offer it to
                                // the password manager so the saved one is not
                                // left stale. Fire-and-forget, silent on decline.
                                PasswordCredentials.offerToSave(context, session.address, newPassword)
                                onChanged()
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = current.isNotBlank() && rulesMet && matches,
                    busy = busy,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink(stringResource(R.string.acct_back), onBack)
                }
            }
        },
    ) {
        if (afterSuspicious) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.error.copy(alpha = 0.08f))
                    .border(1.dp, palette.error.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = palette.error, modifier = Modifier.size(17.dp))
                Text(
                    stringResource(R.string.acct_unrecognized_activity_was_found_on),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = palette.error,
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        AppTextField(stringResource(R.string.acct_current_password), current, { current = it; error = null }, password = true, leading = { Icon(Icons.Rounded.Lock, contentDescription = null) },
            autofill = GMAutofill.Password)
        Spacer(Modifier.height(12.dp))
        AppTextField(stringResource(R.string.acct_new_password_2), next, { next = it; error = null }, password = true, leading = { Icon(Icons.Rounded.Lock, contentDescription = null) },
            autofill = GMAutofill.NewPassword)
        Spacer(Modifier.height(12.dp))
        AppTextField(
            stringResource(R.string.acct_confirm_new_password_2), confirm, { confirm = it; error = null },
            password = true, leading = { Icon(Icons.Rounded.Lock, contentDescription = null) },
            error = if (confirm.isNotEmpty() && !matches) stringResource(R.string.acct_passwords_do_not_match) else null,
            autofill = GMAutofill.NewPassword,
        )
        Spacer(Modifier.height(14.dp))
        InfoCard {
            Text(
                stringResource(R.string.acct_password_requirements_2),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            RuleLine(stringResource(R.string.acct_at_least_8_characters), longEnough)
            RuleLine(stringResource(R.string.acct_contains_a_letter), hasLetter)
            RuleLine(stringResource(R.string.acct_contains_a_number), hasNumber)
            RuleLine(stringResource(R.string.acct_contains_a_symbol), hasSymbol)
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, fontSize = 12.sp, color = palette.error)
        }
    }
}

/**
 * 12 / 24 — the confirmation.
 *
 * The copy says what actually happened: every other device is signed out, this
 * one is not. Promising "you are safe" without saying that would leave the
 * person to discover the sign-outs device by device.
 */
@Composable
fun PasswordChangedScreen(
    updated: Boolean,
    onDone: () -> Unit,
    onSupport: () -> Unit,
) {
    val palette = LocalPalette.current
    AccountScreen(
        onBack = onDone,
        illustration = if (updated) "ill_sec_password_updated" else "ill_sec_password_ok",
        illustrationWidth = 170,
        title = if (updated) stringResource(R.string.acct_password_updated_successfully) else stringResource(R.string.acct_password_changed_successfully),
        body = stringResource(R.string.acct_your_password_has_been_updated),
        footer = {
            Column {
                GradientButton(stringResource(R.string.acct_continue), onDone)
                Spacer(Modifier.height(6.dp))
                FooterNote(
                    text = stringResource(R.string.acct_if_you_did_not_make_this_change),
                    linkText = stringResource(R.string.acct_support_team),
                    onLink = onSupport,
                )
            }
        },
    ) {
        InfoCard {
            Text(stringResource(R.string.acct_whats_next_title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.acct_youll_need_to_sign_in_again_on_your),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = palette.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        InfoNoteCard(body = stringResource(R.string.acct_this_helps_keep_your_account_secure))
    }
}
