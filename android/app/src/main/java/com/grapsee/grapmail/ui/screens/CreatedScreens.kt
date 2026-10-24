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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.grapsee.grapmail.ui.components.illRes
import com.grapsee.grapmail.ui.theme.H1
import com.grapsee.grapmail.ui.theme.LocalPalette

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
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        com.grapsee.grapmail.ui.components.GrapMailLogo(40.dp, wordmark = true)

        Spacer(Modifier.height(18.dp))
        Image(
            painter = painterResource(illRes("created_a")),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(0.74f),
        )

        Spacer(Modifier.height(10.dp))
        Text("ACCOUNT CREATED", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, color = palette.primarySolid)
        Spacer(Modifier.height(8.dp))
        Text("You're all set!", style = H1.copy(fontSize = 28.sp), color = palette.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your GrapMail account has been successfully\ncreated and you're now signed in.",
            fontSize = 14.sp, lineHeight = 21.sp, color = palette.textSecondary, textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        // Profile card with the Active chip
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, palette.border, RoundedCornerShape(16.dp))
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
                Text("Active", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = palette.success)
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("WHAT'S NEXT?", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, color = palette.textTertiary, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))

        NextTile(Icons.Rounded.Mail, Color(0xFFE3ECFB), Color(0xFF1A6DF0), "Explore your mailbox", "Check your inbox, send emails, and more.")
        NextTile(Icons.Rounded.Contacts, Color(0xFFEDE7FD), Color(0xFF7C5CE0), "Add your contacts", "Stay connected with friends and colleagues.")
        NextTile(Icons.Rounded.Settings, Color(0xFFDFF5EA), Color(0xFF10B981), "Personalize your experience", "Set up your preferences and get the most out of GrapMail.")

        Spacer(Modifier.weight(1f))

        GradientButton("Continue to Onboarding", onContinueOnboarding, trailing = {
            Spacer(Modifier.size(10.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        })
        Spacer(Modifier.height(10.dp))
        Text("We'll take you through a quick setup to get you started.", fontSize = 12.sp, color = palette.textTertiary)
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(7.dp).background(palette.primarySolid, CircleShape))
            repeat(4) { Box(Modifier.size(7.dp).background(palette.stepInactive, CircleShape)) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

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
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(36.dp))
        com.grapsee.grapmail.ui.components.GrapMailLogo(40.dp, wordmark = true)

        Spacer(Modifier.height(16.dp))
        Image(
            painter = painterResource(illRes("created_b")),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(0.72f),
        )

        Spacer(Modifier.height(8.dp))
        Text("ACCOUNT CREATED", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, color = palette.primarySolid)
        Spacer(Modifier.height(8.dp))
        Text("You're all set!", style = H1.copy(fontSize = 28.sp), color = palette.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your GrapMail account has been successfully\ncreated and you're now signed in.",
            fontSize = 14.sp, lineHeight = 21.sp, color = palette.textSecondary, textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, palette.border, RoundedCornerShape(16.dp))
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
                contentDescription = "Copy address",
                tint = palette.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }

        Spacer(Modifier.height(18.dp))

        SimpleRow(Icons.Rounded.Mail, "Your secure inbox", "Send, receive and manage your emails.")
        SimpleRow(Icons.Rounded.Lock, "Built for your privacy", "Your data stays yours.")
        SimpleRow(Icons.Rounded.Bolt, "Ready for what's next", "Let's get you set up and personalized.")

        Spacer(Modifier.weight(1f))

        GradientButton("Go to Mailbox", onGoToMailbox, trailing = {
            Spacer(Modifier.size(10.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        })
        Spacer(Modifier.height(12.dp))
        OutlineButton("Continue to Onboarding", onContinueOnboarding, trailing = {
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
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(18.dp))
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
