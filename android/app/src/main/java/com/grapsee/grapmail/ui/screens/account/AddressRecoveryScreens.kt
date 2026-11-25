package com.grapsee.grapmail.ui.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.logic.AccountFlowViewModel
import com.grapsee.grapmail.ui.components.AccountScreen
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.ChipTone
import com.grapsee.grapmail.ui.components.CopyButton
import com.grapsee.grapmail.ui.components.FooterNote
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.IconTile
import com.grapsee.grapmail.ui.components.InfoCard
import com.grapsee.grapmail.ui.components.OrDivider
import com.grapsee.grapmail.ui.components.StatusChip
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.compose.ui.platform.LocalContext

/**
 * Find your GrapMail address — founder design verbatim. One number in, a
 * partially hidden address back.
 *
 * The number is normalised on the way out and the reply is masked on the way
 * back: the endpoint behind this screen never returns a full address, so a
 * stolen phone number cannot be turned into a mailbox to attack.
 */
@Composable
fun FindAddressScreen(
    flow: AccountFlowViewModel,
    onBack: () -> Unit,
    onFound: () -> Unit,
    onSupport: () -> Unit,
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state by flow.state.collectAsStateWithLifecycle()

    var phone by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (phone.isBlank()) return
        busy = true; error = null
        scope.launch {
            try {
                val resp = Api.findAddress(phone.trim())
                busy = false
                if (resp.maskedAddress.isNullOrBlank()) {
                    error = context.getString(R.string.acct_we_couldnt_find_a_grapmail_account)
                    return@launch
                }
                flow.update {
                    copy(
                        foundAddress = resp.maskedAddress,
                        foundAddressFull = resp.address,
                    )
                }
                onFound()
            } catch (e: ApiException) {
                busy = false
                error = when (e.code) {
                    "NOT_FOUND" -> context.getString(R.string.acct_we_couldnt_find_a_grapmail_account)
                    "RATE_LIMITED" -> context.getString(R.string.acct_too_many_lookups_try_again_in_a)
                    else -> e.message ?: context.getString(R.string.acct_we_couldnt_check_that_number_try)
                }
            } catch (_: Exception) {
                busy = false
                error = context.getString(R.string.acct_network_error)
            }
        }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "account-find-address",
        eyebrow = stringResource(R.string.acct_recover_your_grapmail_address),
        title = stringResource(R.string.acct_find_your_grapmail_address),
        body = stringResource(R.string.acct_enter_the_phone_number_linked_to),
        footer = {
            GradientButton(
                stringResource(R.string.acct_find_my_email),
                onClick = { submit() },
                enabled = phone.isNotBlank(),
                busy = busy,
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error ?: "", fontSize = 13.sp, color = palette.error)
            }
            Spacer(Modifier.height(16.dp))
            OrDivider()
            Spacer(Modifier.height(16.dp))
            FooterNote(
                text = stringResource(R.string.acct_need_help_contact_support),
                linkText = stringResource(R.string.acct_support_team),
                onLink = onSupport,
                divider = false,
            )
            Spacer(Modifier.height(4.dp))
        },
    ) {
        AppTextField(
            label = stringResource(R.string.acct_phone_number),
            value = phone,
            onValueChange = { phone = it; error = null },
            hint = stringResource(R.string.acct_enter_your_verified_phone_number),
            showLabel = false,
            keyboard = KeyboardType.Phone,
            leading = {
                Icon(Icons.Rounded.Phone, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(20.dp))
            },
        )
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Address found — the masked address in a card with its copy affordance, and
 * the way back to sign-in with that address already filled in.
 */
@Composable
fun AddressFoundScreen(
    flow: AccountFlowViewModel,
    onBackToSignIn: (address: String?) -> Unit,
) {
    val palette = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    val state by flow.state.collectAsStateWithLifecycle()
    val shown = state.foundAddress ?: ""
    // What Copy puts on the clipboard: the note promises it can be used to sign
    // in, and the mask on screen cannot. Nothing else reads the full value.
    val copyable = state.foundAddressFull ?: shown

    AccountScreen(
        onBack = null,
        illustration = "account-address-found",
        footer = {
            GradientButton(
                stringResource(R.string.acct_back_to_sign_in),
                onClick = { onBackToSignIn(state.foundAddressFull) },
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
            Spacer(Modifier.height(4.dp))
            FooterNote(
                text = stringResource(R.string.acct_for_your_security_this_address_is),
                divider = false,
            )
            Spacer(Modifier.height(4.dp))
        },
    ) {
        StatusChip(stringResource(R.string.acct_email_found), ChipTone.Success)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.acct_your_grapmail_address_has_been_found),
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = palette.textPrimary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.acct_here_is_the_email_address_associated),
            fontSize = 15.sp,
            lineHeight = 23.sp,
            color = palette.textSecondary,
        )
        Spacer(Modifier.height(18.dp))
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Mail)
                Spacer(Modifier.size(12.dp))
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.acct_your_grapmail_address), fontSize = 12.sp, color = palette.textSecondary)
                    Text(
                        shown,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary,
                    )
                }
                CopyButton(stringResource(R.string.acct_copy)) {
                    clipboard.setText(AnnotatedString(copyable))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
