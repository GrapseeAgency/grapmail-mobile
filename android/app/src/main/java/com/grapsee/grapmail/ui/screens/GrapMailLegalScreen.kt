package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.ui.screens.mail.MailScaffold
import com.grapsee.grapmail.ui.theme.LocalPalette

enum class GrapMailLegalDocument(val title: String, val url: String) {
    Terms("Terms of Service", "https://grapsee.com/legal/terms-of-service"),
    Privacy("Privacy Policy", "https://grapsee.com/legal/privacy-policy"),
}

internal fun AnnotatedString.Builder.appendLegalLink(
    document: GrapMailLegalDocument,
    color: Color,
    onOpen: (GrapMailLegalDocument) -> Unit,
) {
    withLink(LinkAnnotation.Clickable(document.name, legalLinkStyles(color)) { onOpen(document) }) {
        append(document.title)
    }
}

private fun legalLinkStyles(color: Color) = TextLinkStyles(
    style = SpanStyle(color = color, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline),
)

@Composable
fun GrapMailLegalScreen(document: GrapMailLegalDocument, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val sections = when (document) {
        GrapMailLegalDocument.Terms -> listOf(
            "Your account" to "You must be at least 13 to create a GrapMail account. Keep your password private and use the account recovery and security settings if you think someone else has accessed your mailbox.",
            "Using GrapMail" to "Use your mailbox for lawful communication. Do not send spam, bulk unsolicited messages, phishing, malware, or messages that harass others. Accounts that misuse the service may be suspended.",
            "Sending and receiving" to "A message can be queued, sent, or fail. Check Sent and any delivery notice when timing matters. A queued message is still waiting to be sent.",
            "Leaving GrapMail" to "You can sign out on this device or request account deletion from Settings → Data & privacy. Save any mail you need before deleting your account.",
        )
        GrapMailLegalDocument.Privacy -> listOf(
            "Account and mailbox data" to "GrapMail uses your account details to create and secure your mailbox. The service stores the addresses, messages, and attachments needed to send, receive, and sync your mail.",
            "Security and notifications" to "Google reCAPTCHA helps protect signup from abuse. If you enable new-mail alerts, GrapMail can register a Firebase device token to deliver notifications to your device. You can change notification access in Android Settings.",
            "Images and device storage" to "Remote images are hidden by default in the message reader. Showing them can contact the sender’s image host. The app also keeps a local mailbox cache so recently loaded mail is available more quickly.",
            "Encryption choices" to "GrapMail offers end-to-end encryption for supported messages. Ordinary email and encrypted messages have different privacy properties. You can manage encryption and review your account details in Settings.",
            "Control and deletion" to "In Settings → Data & privacy, you can review your profile and request deletion. For questions about retention, access, or other privacy requests, use the Grapsee contact link below.",
        )
    }
    MailScaffold(document.title, onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("grapmail-legal-${document.name.lowercase()}"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("GrapMail guide", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                Text("This guide explains how GrapMail works. For Grapsee’s current legal information, use the official page below.",
                    fontSize = 14.sp, lineHeight = 21.sp, color = palette.textSecondary)
                for ((heading, paragraph) in sections) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(heading, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                        Text(paragraph, fontSize = 14.sp, lineHeight = 22.sp, color = palette.textSecondary)
                    }
                }
                Text(buildAnnotatedString {
                    withLink(LinkAnnotation.Url(document.url, legalLinkStyles(palette.primarySolid))) {
                        append("Read the official ${document.title}")
                    }
                }, fontSize = 14.sp)
                Text(buildAnnotatedString {
                    withLink(LinkAnnotation.Url("https://grapsee.com/contact", legalLinkStyles(palette.primarySolid))) {
                        append("Contact Grapsee")
                    }
                }, fontSize = 14.sp)
            }
        }
    }
}
