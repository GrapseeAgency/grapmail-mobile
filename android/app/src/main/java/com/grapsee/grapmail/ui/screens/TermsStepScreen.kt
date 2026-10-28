package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.rememberScrolledToEnd
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Step 5 — "Terms & Privacy" — founder design verbatim: the scrollable
 * "GrapMail Terms of Service" card with sections 1. Your Account and
 * 2. Privacy Policy (brand-blue inline links), the "Read our … ↗" links, the
 * agree checkbox card with the must-scroll caption, and the disabled
 * "Create Account" action until both conditions hold.
 */
@Composable
fun TermsStepScreen(
    vm: SignUpViewModel,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onCreated: (address: String, name: String) -> Unit,
) {
    val palette = LocalPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by vm.state.collectAsState()
    val scroll = rememberScrollState()
    val scrolledToEnd = rememberScrolledToEnd(scroll)

    // Navigation fires once, from the VM's onCreated callback (wired in the
    // bottom bar). The old LaunchedEffect(createdAddress) fired on the same
    // success too, pushing a duplicate verify screen onto the backstack.

    WizardScaffold(
        step = 5,
        title = "Terms & Privacy",
        subtitle = AnnotatedSubtitle(rest = "Please read and agree to our Terms of Service and Privacy Policy to continue."),
        onBack = onBack,
        onSignIn = onSignIn,
        bottomBar = {
            GradientButton(
                if (state.creating) "Creating…" else "Create Account",
                onClick = {
                    vm.createAccount(
                        onCreated = { address, name -> onCreated(address, name) },
                        onError = { vm.update { copy(error = it) } },
                    )
                },
                enabled = state.termsAgreed && scrolledToEnd && !state.creating,
                busy = state.creating,
            )
        },
    ) {
        // ── The scrollable terms document ───────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, palette.border, RoundedCornerShape(20.dp))
                .background(palette.card)
                .verticalScroll(scroll)
                .padding(18.dp),
        ) {
            Text("GrapMail Terms of Service", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(14.dp))
            Text("1.  Your Account", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                buildAnnotatedString {
                    append("By creating an account, you agree to our ")
                    withStyle(SpanStyle(color = palette.primarySolid)) { append("Terms of Service") }
                    append(" and confirm that you are at least 13 years old. You are responsible for keeping your account secure and for all activity that happens under your account.")
                },
                fontSize = 13.sp, lineHeight = 20.sp, color = palette.textSecondary,
            )
            Spacer(Modifier.height(16.dp))
            Text("2.  Privacy Policy", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                buildAnnotatedString {
                    append("We collect and process your data to provide and improve GrapMail services. Your information will not be shared with third parties without your consent, except as required by law. You can read the full details in our ")
                    withStyle(SpanStyle(color = palette.primarySolid)) { append("Privacy Policy") }
                    append(".")
                },
                fontSize = 13.sp, lineHeight = 20.sp, color = palette.textSecondary,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text("Read our Terms of Service ↗", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.primarySolid,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    context.startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://grapsee.com/legal/terms-of-service"),
                    ))
                }
                .padding(6.dp))
        Spacer(Modifier.height(8.dp))
        Text("Read our Privacy Policy ↗", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.primarySolid,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    context.startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://grapsee.com/legal/privacy-policy"),
                    ))
                }
                .padding(6.dp))

        Spacer(Modifier.height(16.dp))

        // ── The agree card ──────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, palette.border, RoundedCornerShape(20.dp))
                .background(palette.card)
                .clickable(enabled = scrolledToEnd) { vm.update { copy(termsAgreed = !termsAgreed) } }
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (state.termsAgreed) palette.primarySolid else palette.card)
                    .border(1.5.dp, if (state.termsAgreed) palette.primarySolid else palette.textTertiary, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (state.termsAgreed) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.onPrimary, modifier = Modifier.size(15.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    buildAnnotatedString {
                        append("I agree to the ")
                        withStyle(SpanStyle(color = palette.primarySolid, fontWeight = FontWeight.Medium)) { append("Terms of Service") }
                        append(" and ")
                        withStyle(SpanStyle(color = palette.primarySolid, fontWeight = FontWeight.Medium)) { append("Privacy Policy") }
                    },
                    fontSize = 13.sp, color = palette.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "You must scroll to the bottom of this document to enable the checkbox.",
                    fontSize = 11.sp, color = palette.textTertiary,
                )
            }
        }

        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(state.error ?: "", fontSize = 13.sp, color = palette.error)
        }
    }
}
