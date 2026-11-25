package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import com.grapsee.grapmail.ui.components.gmIsShortWindow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.OutlineButton
import com.grapsee.grapmail.ui.theme.H1
import com.grapsee.grapmail.ui.theme.LocalPalette
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R

/**
 * Account Created (A) — founder design verbatim: envelope+check illustration
 * with confetti, ACCOUNT CREATED eyebrow, "You're all set!", the profile
 * card with the Active chip, WHAT'S NEXT? rows with colored icon tiles,
 * "Continue to Onboarding →", the setup caption, and the 5-dot pager.
 */
@Composable
fun AccountCreatedScreen(
    address: String,
    name: String,
    onContinueOnboarding: () -> Unit,
) {
    val palette = LocalPalette.current
    val short = gmIsShortWindow()
    // Phones, portrait tablets and tall windows: one centred column, exactly
    // the founder's stack. Wide landscape windows: the scene and the headline
    // on the left, the card and the action on the right (see ArtFormLayout).
    ArtFormLayout(
        modifier = Modifier.background(palette.bg),
        art = { split ->
        Spacer(Modifier.height(if (split) 0.dp else 40.dp))
        com.grapsee.grapmail.ui.components.GrapMailLogo(40.dp, wordmark = true)

        Spacer(Modifier.height(18.dp))
        com.grapsee.grapmail.ui.artwork.Artwork(
            "created_a",
            // Bounded so the scene can never push the action below the fold
            // on a short device — the same regression the verify screen had.
            // In the two-pane layout the pane is the bound, so it may grow.
            modifier = createdArtSize(split, short).aspectRatio(2f),
        )

        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.acct_account_created), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, color = palette.primarySolid, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.acct_youre_all_set), style = H1.copy(fontSize = 28.sp), color = palette.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.acct_your_grapmail_account_has_been),
            fontSize = 14.sp, lineHeight = 21.sp, color = palette.textSecondary, textAlign = TextAlign.Center,
        )
        },
    ) {
        Spacer(Modifier.height(20.dp))

        // Profile card with the Active chip
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, palette.border, RoundedCornerShape(20.dp))
                .background(palette.card)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(palette.primarySolid),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (name.firstOrNull() ?: 'A').uppercase(),
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = palette.onPrimary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                Text(address, fontSize = 13.sp, color = palette.textSecondary)
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.success.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.success, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.acct_active), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = palette.success)
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.acct_whats_next), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, color = palette.textTertiary, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))

        NextTile(Icons.Rounded.Mail, Color(0xFFE3ECFB), Color(0xFF1A6DF0), stringResource(R.string.acct_explore_your_mailbox), stringResource(R.string.acct_check_your_inbox_send_emails_and))
        NextTile(Icons.Rounded.Contacts, Color(0xFFEDE7FD), Color(0xFF7C5CE0), stringResource(R.string.acct_add_your_contacts), stringResource(R.string.acct_stay_connected_with_friends_and))
        NextTile(Icons.Rounded.Settings, Color(0xFFDFF5EA), Color(0xFF10B981), stringResource(R.string.acct_personalize_your_experience), stringResource(R.string.acct_set_up_your_preferences_and_get_the))

        Spacer(Modifier.height(24.dp))

        GradientButton(stringResource(R.string.acct_continue_to_onboarding), onContinueOnboarding, trailing = {
            Spacer(Modifier.size(10.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        })
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.acct_well_take_you_through_a_quick_setup), fontSize = 12.sp, color = palette.textTertiary)
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(7.dp).background(palette.primarySolid, CircleShape))
            repeat(4) { Box(Modifier.size(7.dp).background(palette.stepInactive, CircleShape)) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 180dp in the single column (as designed); up to 360dp (240dp in a short window) beside the form. */
private fun createdArtSize(split: Boolean, short: Boolean): Modifier =
    if (split) Modifier.fillMaxWidth().widthIn(max = if (short) 240.dp else 360.dp) else Modifier.width(180.dp)

/**
 * Account Created (B) — founder design verbatim: glowing envelope
 * illustration, profile card with the copy icon, the three plain rows,
 * "Go to Mailbox →" primary and "Continue to Onboarding →" outline.
 */
@Composable
fun AccountCreatedAltScreen(
    address: String,
    name: String,
    onGoToMailbox: () -> Unit,
    onContinueOnboarding: () -> Unit,
) {
    val palette = LocalPalette.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val short = gmIsShortWindow()
    ArtFormLayout(
        modifier = Modifier.background(palette.bg),
        art = { split ->
        Spacer(Modifier.height(if (split) 0.dp else 36.dp))
        com.grapsee.grapmail.ui.components.GrapMailLogo(40.dp, wordmark = true)

        Spacer(Modifier.height(16.dp))
        com.grapsee.grapmail.ui.artwork.Artwork(
            "created_b",
            // Bounded — same reason as created_a.
            modifier = createdArtSize(split, short).aspectRatio(2f),
        )

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.acct_account_created), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, color = palette.primarySolid, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.acct_youre_all_set), style = H1.copy(fontSize = 28.sp), color = palette.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.acct_your_grapmail_account_has_been),
            fontSize = 14.sp, lineHeight = 21.sp, color = palette.textSecondary, textAlign = TextAlign.Center,
        )
        },
    ) {
        Spacer(Modifier.height(20.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, palette.border, RoundedCornerShape(20.dp))
                .background(palette.card)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(palette.primarySolid),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (name.firstOrNull() ?: 'A').uppercase(),
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = palette.onPrimary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                Text(address, fontSize = 13.sp, color = palette.textSecondary)
            }
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.acct_copy_address),
                tint = palette.textTertiary,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(address))
                    }
                    .padding(9.dp),
            )
        }

        Spacer(Modifier.height(18.dp))

        SimpleRow(Icons.Rounded.Mail, stringResource(R.string.acct_your_secure_inbox), stringResource(R.string.acct_send_receive_and_manage_your_emails))
        SimpleRow(Icons.Rounded.Lock, stringResource(R.string.acct_built_for_your_privacy), stringResource(R.string.acct_your_data_stays_yours))
        SimpleRow(Icons.Rounded.Bolt, stringResource(R.string.acct_ready_for_whats_next), stringResource(R.string.acct_lets_get_you_set_up_and_personalized))

        Spacer(Modifier.height(24.dp))

        GradientButton(stringResource(R.string.acct_go_to_mailbox), onGoToMailbox, trailing = {
            Spacer(Modifier.size(10.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        })
        Spacer(Modifier.height(12.dp))
        OutlineButton(stringResource(R.string.acct_continue_to_onboarding), onContinueOnboarding, trailing = {
            Spacer(Modifier.size(10.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp), tint = palette.primarySolid)
        })
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun NextTile(
    icon: ImageVector,
    tileColor: Color,
    iconColor: Color,
    title: String,
    subtitle: String,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tileColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Text(subtitle, fontSize = 12.sp, color = palette.textSecondary)
        }
        // No chevron: this tile has no destination. The screen's one action is
        // "Continue to Onboarding", and a row that draws a chevron promises a
        // tap that does nothing — the exact class the audit called out on the
        // label chips and the box tabs. Removing the affordance is the repo's
        // rule; it is not made tappable until a destination is decided.
    }
}

@Composable
private fun SimpleRow(icon: ImageVector, title: String, subtitle: String) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Text(subtitle, fontSize = 12.sp, color = palette.textSecondary)
        }
    }
}
