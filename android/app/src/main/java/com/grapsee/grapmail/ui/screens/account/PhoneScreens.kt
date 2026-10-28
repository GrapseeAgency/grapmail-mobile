package com.grapsee.grapmail.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.logic.AccountFlowViewModel
import com.grapsee.grapmail.ui.components.AccountLink
import com.grapsee.grapmail.ui.components.AccountScreen
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.ChipTone
import com.grapsee.grapmail.ui.components.CodeCells
import com.grapsee.grapmail.ui.components.CodeExpiry
import com.grapsee.grapmail.ui.components.FooterNote
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.InfoCard
import com.grapsee.grapmail.ui.components.StatusChip
import com.grapsee.grapmail.ui.components.TwoStepRibbon
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

private data class Country(val flag: String, val dial: String, val name: String)

// The numbers GrapMail actually sees. The picker is a short, honest list rather
// than an unbounded database nobody maintains; the dial code is the only part
// that matters to the API, which stores E.164.
private val COUNTRIES = listOf(
    Country("\uD83C\uDDE7\uD83C\uDDE9", "+880", "Bangladesh"),
    Country("\uD83C\uDDEE\uD83C\uDDF3", "+91", "India"),
    Country("\uD83C\uDDF5\uD83C\uDDF0", "+92", "Pakistan"),
    Country("\uD83C\uDDFA\uD83C\uDDF8", "+1", "United States"),
    Country("\uD83C\uDDEC\uD83C\uDDE7", "+44", "United Kingdom"),
    Country("\uD83C\uDDE6\uD83C\uDDEA", "+971", "United Arab Emirates"),
    Country("\uD83C\uDDF8\uD83C\uDDE6", "+966", "Saudi Arabia"),
    Country("\uD83C\uDDF2\uD83C\uDDFE", "+60", "Malaysia"),
)

/** E.164 from the dial code and what the person typed, the way the API wants it. */
private fun toE164(dial: String, national: String): String? {
    val digits = national.filter(Char::isDigit)
    if (digits.length < 6) return null
    // Local convention: 01XXXXXXXXX is +880 1XXXXXXXXX. Dropping the trunk zero
    // is the difference between a delivered code and a "no such number".
    val trimmed = if (dial != "+1" && digits.startsWith("0")) digits.dropWhile { it == '0' } else digits
    return dial + trimmed
}

/** "+880 1XXXXXXXXX" — how a number is shown once it is known. */
private fun prettyPhone(e164: String): String {
    for (c in COUNTRIES) {
        if (e164.startsWith(c.dial)) {
            return c.dial + " " + e164.removePrefix(c.dial)
        }
    }
    return e164
}

/**
 * Phone verification, both steps in one screen: the design's two states share
 * the artwork, the ribbon and the footer, and only the middle changes.
 *
 * Verifying a number is only half the job — the number has to end up on the
 * account. On success the phone token is handed to the profile endpoint, so
 * "verified" means saved, not just proven.
 */
@Composable
fun PhoneVerificationScreen(
    flow: AccountFlowViewModel,
    onBack: () -> Unit,
    onVerified: (phone: String) -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by flow.state.collectAsState()

    var step by rememberSaveable { mutableIntStateOf(1) }
    var countryIndex by rememberSaveable { mutableIntStateOf(0) }
    var national by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var pickerOpen by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resending by remember { mutableStateOf(false) }

    val country = COUNTRIES[countryIndex]

    fun sendCode(isResend: Boolean) {
        val e164 = if (isResend) state.phone else toE164(country.dial, national)
        if (e164 == null) {
            error = "Enter a valid phone number."
            return
        }
        if (isResend) resending = true else busy = true
        error = null
        scope.launch {
            try {
                val resp = Api.startPhoneVerification(e164)
                busy = false; resending = false
                if (resp.challengeId == null) {
                    error = resp.message ?: "We couldn't send a code to that number."
                    return@launch
                }
                code = ""
                flow.update {
                    copy(phone = e164, phoneChallengeId = resp.challengeId, phoneExpiresAt = resp.expiresAt ?: 0L)
                }
                step = 2
            } catch (e: ApiException) {
                busy = false; resending = false
                error = when (e.code) {
                    "PHONE_IN_USE" -> "That number is already linked to another account."
                    "RATE_LIMITED" -> e.message ?: "Please wait a moment before asking for another code."
                    else -> e.message ?: "We couldn't send your code. Try again."
                }
            } catch (_: Exception) {
                busy = false; resending = false
                error = "Network error. Please try again."
            }
        }
    }

    fun verify() {
        val challenge = state.phoneChallengeId ?: return
        busy = true; error = null
        scope.launch {
            try {
                // The token is checked BEFORE the code is redeemed. Checking it
                // afterwards spent the challenge and then told the person to sign
                // in, so their retry failed with "wrong code" — an instruction
                // that could not work.
                val token = SessionStore.token(context)
                if (token == null) {
                    busy = false
                    error = "Sign in first so the number can be saved to your account."
                    return@launch
                }
                val verified = Api.verifyPhone(challenge, code)
                val proof = verified.phoneToken
                if (proof == null) {
                    busy = false
                    error = "That code is not correct."
                    return@launch
                }
                Api.linkPhone(token, proof)
                busy = false
                val phone = verified.phone ?: state.phone
                flow.update { copy(verifiedPhone = phone) }
                onVerified(phone)
            } catch (e: ApiException) {
                busy = false
                error = when (e.code) {
                    "PHONE_IN_USE" -> "That number is already linked to another account."
                    "VERIFY_FAILED", "BAD_CODE" -> "That code is not correct."
                    else -> e.message ?: "We couldn't verify that code. Try again."
                }
            } catch (_: Exception) {
                busy = false
                error = "Network error. Please try again."
            }
        }
    }

    AccountScreen(
        onBack = { if (step == 2) step = 1 else onBack() },
        illustration = "account-phone",
        eyebrow = "Phone verification",
        title = if (step == 1) "Add or verify your phone number" else "Enter the 6-digit code",
        body = if (step == 1) {
            "We'll send a 6-digit verification code to your phone number. This helps you recover your account and enable two-step verification."
        } else {
            "We've sent a verification code to your phone number ${prettyPhone(state.phone)}. The code will expire in 5 minutes."
        },
        footer = {
            if (step == 1) {
                GradientButton(
                    "Send Verification Code",
                    onClick = { sendCode(false) },
                    enabled = national.isNotBlank(),
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
                FooterNote(
                    text = "Your phone number is only used for security purposes and won't be shared with anyone.",
                    divider = false,
                )
            } else {
                GradientButton(
                    "Verify & Continue",
                    onClick = { verify() },
                    enabled = code.length == 6,
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
                Spacer(Modifier.height(14.dp))
                FooterNote(
                    text = "Didn't receive the code? Check your spam folder or try resending after 1 minute.",
                    divider = false,
                )
            }
            Spacer(Modifier.height(4.dp))
        },
    ) {
        TwoStepRibbon(current = step, labels = listOf("Enter Phone Number", "Verify Code"))
        Spacer(Modifier.height(20.dp))

        if (step == 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.inputFill)
                    .border(1.dp, palette.border, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { pickerOpen = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(country.flag, fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(country.dial, fontSize = 15.sp, color = palette.textPrimary)
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = "Country code",
                        tint = palette.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(1.dp).height(28.dp).background(palette.border))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
                    Text("Phone number", fontSize = 12.sp, color = palette.textTertiary)
                    BasicTextField(
                        value = national,
                        onValueChange = { national = it.filter { c -> c.isDigit() || c == ' ' }.take(16); error = null },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp),
                        textStyle = TextStyle(fontSize = 15.sp, color = palette.textPrimary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        decorationBox = { inner ->
                            if (national.isEmpty()) {
                                Text("01XXXXXXXXX", fontSize = 15.sp, color = palette.textTertiary)
                            }
                            inner()
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        } else {
            CodeCells(
                length = 6,
                value = code,
                onValueChange = {
                    code = it.filter(Char::isDigit).take(6)
                    error = null
                },
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CodeExpiry(expiresAt = state.phoneExpiresAt, onExpired = { })
                Spacer(Modifier.weight(1f))
                AccountLink(if (resending) "Sending…" else "Resend Code", onClick = { sendCode(true) })
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text("Country code", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    COUNTRIES.forEachIndexed { index, c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { countryIndex = index; pickerOpen = false }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.flag, fontSize = 18.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(c.name, fontSize = 15.sp, color = palette.textPrimary, modifier = Modifier.weight(1f))
                            Text(c.dial, fontSize = 14.sp, color = palette.textSecondary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerOpen = false }) { Text("Close") }
            },
        )
    }
}

/** Phone verified — the number in a card with its green badge. */
@Composable
fun PhoneVerifiedScreen(
    flow: AccountFlowViewModel,
    onGoToSecurity: () -> Unit,
    onBackHome: () -> Unit,
) {
    val palette = LocalPalette.current
    val state by flow.state.collectAsState()
    val phone = state.verifiedPhone ?: state.phone

    AccountScreen(
        onBack = null,
        illustration = "account-phone-ok",
        footer = {
            GradientButton(
                "Go to Account Security",
                onClick = onGoToSecurity,
                trailing = {
                    Spacer(Modifier.size(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                AccountLink("Back to Home", onClick = onBackHome)
            }
            Spacer(Modifier.height(14.dp))
            FooterNote(
                text = "Your phone number is securely stored and only used for account recovery and two-step verification.",
                divider = false,
            )
            Spacer(Modifier.height(4.dp))
        },
    ) {
        StatusChip("Phone Verified", ChipTone.Success)
        Spacer(Modifier.height(16.dp))
        Text(
            "Your phone number\nhas been verified!",
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = palette.textPrimary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Your phone number ${prettyPhone(phone)} is now linked to your GrapMail account. You can use it for account recovery and two-step verification.",
            fontSize = 15.sp,
            lineHeight = 23.sp,
            color = palette.textSecondary,
        )
        Spacer(Modifier.height(18.dp))
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.softFill),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Phone, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Verified Phone Number", fontSize = 12.sp, color = palette.textSecondary)
                    Text(prettyPhone(phone), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.successChipBg)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = palette.successInk, modifier = Modifier.size(14.dp))
                    Text("Verified", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = palette.successInk)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
