package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.TermsCheckbox
import com.grapsee.grapmail.ui.components.rememberScrolledToEnd
import com.grapsee.grapmail.ui.theme.LocalPalette

private val TERMS_BODY = listOf(
    "A GrapMail address is your identity on Grapsee. It comes with a mailbox, and it is the name you sign in with.",
    "What we keep — your name, birthday and gender. The birthday is used only to check you are old enough for the account. Nothing else is inferred from it.",
    "How your mailbox is used — mail is stored for your account only. The contents of your messages are never read to build advertising profiles, and your address is never sold.",
    "Your responsibilities — keep your password to yourself. Do not use the mailbox for spam, bulk unsolicited mail, fraud, or anything unlawful. Accounts that do may be suspended.",
    "You can delete the account and its mailbox at any time from your account settings.",
)

/**
 * Step 5 — "Terms & Privacy": the design's scroll-gate — the checkbox only
 * unlocks after the document is scrolled to the end, and Create Account
 * stays disabled until it is checked. On agree, the real account is created.
 */
@Composable
fun TermsStepScreen(vm: SignUpViewModel, onBack: () -> Unit, onCreated: (address: String, name: String) -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsState()
    val scroll = rememberScrollState()
    val scrolledToEnd = rememberScrolledToEnd(scroll)

    LaunchedEffect(state.createdAddress) {
        val addr = state.createdAddress
        if (addr != null) onCreated(addr, state.createdName ?: state.firstName)
    }

    WizardScaffold(
        step = 5,
        title = "Review & agree",
        subtitle = "One last step to create your account.",
        onBack = onBack,
        bottomBar = {
            GradientButton(
                if (state.creating) "Creating…" else "Create Account",
                enabled = state.termsAgreed && scrolledToEnd && !state.creating,
                busy = state.creating,
                onClick = {
                    vm.createAccount(
                        onCreated = { address, name -> onCreated(address, name) },
                        onError = { vm.update { copy(error = it) } },
                    )
                },
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(250.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(palette.softFill)
                .verticalScroll(scroll)
                .padding(16.dp),
        ) {
            Text("Terms of Service & Privacy Policy", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(10.dp))
            TERMS_BODY.forEach { para ->
                Text(para, fontSize = 13.sp, lineHeight = 19.sp, color = palette.textSecondary)
                Spacer(Modifier.height(10.dp))
            }
            Text(
                "The binding documents are the full Terms of Service and Privacy Policy, published at grapsee.com/legal.",
                fontSize = 13.sp, lineHeight = 19.sp, color = palette.textSecondary,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            if (scrolledToEnd) "Thanks — you can agree now." else "Scroll to the bottom to continue.",
            fontSize = 12.sp, color = palette.textTertiary,
        )
        Spacer(Modifier.height(8.dp))
        TermsCheckbox(
            checked = state.termsAgreed,
            onCheckedChange = { vm.update { copy(termsAgreed = it) } },
        ) {
            Text(
                "I have read and agree to the Terms of Service and the Privacy Policy",
                fontSize = 13.sp, lineHeight = 18.sp, color = palette.textSecondary,
            )
        }
        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(state.error ?: "", fontSize = 13.sp, color = palette.error)
        }
    }
}
