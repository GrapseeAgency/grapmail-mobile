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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.logic.SignUpViewModel
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.gmIsShortWindow
import com.grapsee.grapmail.ui.components.rememberScrolledToEnd
import com.grapsee.grapmail.ui.theme.LocalPalette
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R

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
    onLegal: (GrapMailLegalDocument) -> Unit,
) {
    val palette = LocalPalette.current
    val termsTitle = stringResource(R.string.acct_terms_of_service)
    val privacyTitle = stringResource(R.string.acct_privacy_policy)
    val state by vm.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val scrolledToEnd = rememberScrolledToEnd(scroll)
    // The terms card scrolls inside the step. In a short window (a phone in
    // landscape, a split-screen half, a cover screen) a 320dp card would be
    // the whole window, hiding the agree box and the action; it gets 200dp.
    val termsMaxHeight = if (gmIsShortWindow()) 200.dp else 320.dp

    // Navigation fires once, from the VM's onCreated callback (wired in the
    // bottom bar). The old LaunchedEffect(createdAddress) fired on the same
    // success too, pushing a duplicate verify screen onto the backstack.

    WizardScaffold(
        step = 5,
        title = stringResource(R.string.acct_terms_privacy),
        subtitle = AnnotatedSubtitle(rest = stringResource(R.string.acct_please_read_and_agree_to_our_terms)),
        onBack = onBack,
        onSignIn = onSignIn,
        bottomBar = {
            GradientButton(
                if (state.creating) stringResource(R.string.acct_creating) else stringResource(R.string.acct_create_account_button),
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
                .heightIn(max = termsMaxHeight)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, palette.border, RoundedCornerShape(20.dp))
                .background(palette.card)
                .verticalScroll(scroll)
                .padding(18.dp),
        ) {
            Text(stringResource(R.string.acct_grapmail_terms_of_service), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.acct_1_your_account), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                buildAnnotatedString {
                    appendWithLegalLinks(
                        stringResource(R.string.acct_terms_section_account_body), palette.primarySolid, onLegal,
                        GrapMailLegalDocument.Terms to termsTitle,
                    )
                },
                fontSize = 13.sp, lineHeight = 20.sp, color = palette.textSecondary,
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.acct_2_privacy_policy), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                buildAnnotatedString {
                    appendWithLegalLinks(
                        stringResource(R.string.acct_terms_section_privacy_body), palette.primarySolid, onLegal,
                        GrapMailLegalDocument.Privacy to privacyTitle,
                    )
                },
                fontSize = 13.sp, lineHeight = 20.sp, color = palette.textSecondary,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(buildAnnotatedString {
            appendLegalLink(GrapMailLegalDocument.Terms, palette.primarySolid, onLegal, termsTitle)
            append("  ·  ")
            appendLegalLink(GrapMailLegalDocument.Privacy, palette.primarySolid, onLegal, privacyTitle)
        }, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 6.dp))

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
                    stringResource(R.string.acct_i_agree_to_the_terms_of_service_and),
                    fontSize = 13.sp, color = palette.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.acct_you_must_scroll_to_the_bottom_of),
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
