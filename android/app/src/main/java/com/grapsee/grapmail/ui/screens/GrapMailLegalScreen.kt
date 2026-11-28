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
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.annotation.StringRes

enum class GrapMailLegalDocument(
    /** English name, kept for callers outside this file that have not moved to [titleRes] yet. */
    val title: String,
    @StringRes val titleRes: Int,
    val url: String,
) {
    Terms("Terms of Service", R.string.acct_terms_of_service, "https://grapsee.com/legal/terms-of-service"),
    Privacy("Privacy Policy", R.string.acct_privacy_policy, "https://grapsee.com/legal/grapmail-privacy-policy"),
}

internal fun AnnotatedString.Builder.appendLegalLink(
    document: GrapMailLegalDocument,
    color: Color,
    onOpen: (GrapMailLegalDocument) -> Unit,
    title: String = document.title,
) {
    withLink(LinkAnnotation.Clickable(document.name, legalLinkStyles(color)) { onOpen(document) }) {
        append(title)
    }
}

/**
 * Appends a translated sentence whose `%1$s` / `%2$s` placeholders stand for the
 * legal links, so a translation can move the links inside the sentence instead of
 * the sentence being glued together from English fragments around them.
 */
internal fun AnnotatedString.Builder.appendWithLegalLinks(
    template: String,
    color: Color,
    onOpen: (GrapMailLegalDocument) -> Unit,
    vararg links: Pair<GrapMailLegalDocument, String>,
) {
    var last = 0
    for (m in LEGAL_LINK_TOKEN.findAll(template)) {
        append(template.substring(last, m.range.first))
        val link = links.getOrNull(m.groupValues[1].toInt() - 1)
        if (link != null) appendLegalLink(link.first, color, onOpen, link.second) else append(m.value)
        last = m.range.last + 1
    }
    append(template.substring(last))
}

private val LEGAL_LINK_TOKEN = Regex("%(\\d+)\\\$s")

private fun legalLinkStyles(color: Color) = TextLinkStyles(
    style = SpanStyle(color = color, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline),
)

@Composable
fun GrapMailLegalScreen(document: GrapMailLegalDocument, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val docTitle = stringResource(document.titleRes)
    val sections = when (document) {
        GrapMailLegalDocument.Terms -> listOf(
            stringResource(R.string.acct_your_account) to stringResource(R.string.acct_you_must_be_at_least_13_to_create_a),
            stringResource(R.string.acct_using_grapmail) to stringResource(R.string.acct_use_your_mailbox_for_lawful),
            stringResource(R.string.acct_sending_and_receiving) to stringResource(R.string.acct_a_message_can_be_queued_sent_or_fail),
            stringResource(R.string.acct_leaving_grapmail) to stringResource(R.string.acct_you_can_sign_out_on_this_device_or),
        )
        GrapMailLegalDocument.Privacy -> listOf(
            stringResource(R.string.acct_account_and_mailbox_data) to stringResource(R.string.acct_grapmail_uses_your_account_details),
            stringResource(R.string.acct_security_and_notifications) to stringResource(R.string.acct_google_recaptcha_helps_protect),
            stringResource(R.string.acct_images_and_device_storage) to stringResource(R.string.acct_remote_images_are_hidden_by_default),
            stringResource(R.string.acct_encryption_choices) to stringResource(R.string.acct_grapmail_offers_end_to_end),
            stringResource(R.string.acct_control_and_deletion) to stringResource(R.string.acct_in_settings_data_privacy_you_can),
        )
    }
    MailScaffold(docTitle, onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("grapmail-legal-${document.name.lowercase()}"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(stringResource(R.string.acct_grapmail_guide), fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                Text(stringResource(R.string.acct_this_guide_explains_how_grapmail),
                    fontSize = 14.sp, lineHeight = 21.sp, color = palette.textSecondary)
                for ((heading, paragraph) in sections) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(heading, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                        Text(paragraph, fontSize = 14.sp, lineHeight = 22.sp, color = palette.textSecondary)
                    }
                }
                Text(buildAnnotatedString {
                    withLink(LinkAnnotation.Url(document.url, legalLinkStyles(palette.primarySolid))) {
                        append(stringResource(R.string.acct_legal_read_official, docTitle))
                    }
                }, fontSize = 14.sp)
                Text(buildAnnotatedString {
                    withLink(LinkAnnotation.Url("https://grapsee.com/help/products/grapmail", legalLinkStyles(palette.primarySolid))) {
                        append(stringResource(R.string.acct_contact_grapsee))
                    }
                }, fontSize = 14.sp)
            }
        }
    }
}
