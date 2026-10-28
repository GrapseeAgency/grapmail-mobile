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
import androidx.compose.runtime.collectAsState
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
    val state by flow.state.collectAsState()

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
                    error = "We couldn't find a GrapMail account with that number."
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
                    "NOT_FOUND" -> "We couldn't find a GrapMail account with that number."
                    "RATE_LIMITED" -> "Too many lookups. Try again in a little while."
                    else -> e.message ?: "We couldn't check that number. Try again."
                }
            } catch (_: Exception) {
                busy = false
                error = "Network error. Please try again."
            }
        }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "account-find-address",
        eyebrow = "Recover your GrapMail address",
        title = "Find your GrapMail address",
        body = "Enter the phone number linked to your GrapMail account. We'll check our records and show you the email address associated with it.",
        footer = {
            GradientButton(
                "Find My Email",
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
                text = "Need help? Contact our support team.",
                linkText = "support team",
                onLink = onSupport,
                divider = false,
            )
            Spacer(Modifier.height(4.dp))
        },
    ) {
        AppTextField(
            label = "Phone number",
            value = phone,
            onValueChange = { phone = it; error = null },
            hint = "Enter your verified phone number",
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
    val state by flow.state.collectAsState()
    val shown = state.foundAddress ?: ""
    // What Copy puts on the clipboard: the note promises it can be used to sign
    // in, and the mask on screen cannot. Nothing else reads the full value.
    val copyable = state.foundAddressFull ?: shown

    AccountScreen(
        onBack = null,
        illustration = "account-address-found",
        footer = {
            GradientButton(
                "Back to Sign In",
                onClick = { onBackToSignIn(state.foundAddressFull) },
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
            Spacer(Modifier.height(4.dp))
            FooterNote(
                text = "For your security, this address is partially hidden. You can copy it to use for sign in.",
                divider = false,
            )
            Spacer(Modifier.height(4.dp))
        },
    ) {
        StatusChip("Email Found", ChipTone.Success)
        Spacer(Modifier.height(16.dp))
        Text(
            "Your GrapMail address\nhas been found!",
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = palette.textPrimary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Here is the email address associated with your account. You can copy it or use it to sign in.",
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
                    Text("Your GrapMail address", fontSize = 12.sp, color = palette.textSecondary)
                    Text(
                        shown,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary,
                    )
                }
                CopyButton("Copy") {
                    clipboard.setText(AnnotatedString(copyable))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
