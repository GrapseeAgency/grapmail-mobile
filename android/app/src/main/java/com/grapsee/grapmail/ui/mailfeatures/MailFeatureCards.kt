package com.grapsee.grapmail.ui.mailfeatures

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.MailMessageFullDto
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.features.ActionCard
import com.grapsee.grapmail.features.ActionCardExtractor
import com.grapsee.grapmail.features.ComposeAssist
import com.grapsee.grapmail.features.ComposeTone
import com.grapsee.grapmail.features.ThreadMessageText
import com.grapsee.grapmail.features.ThreadSummarizer
import com.grapsee.grapmail.features.ThreadSummary
import com.grapsee.grapmail.features.TrackerShield
import com.grapsee.grapmail.features.UrlRisk
import com.grapsee.grapmail.features.UrlSafety
import com.grapsee.grapmail.features.UrlVerdict
import com.grapsee.grapmail.logic.MailFeaturePrefs
import com.grapsee.grapmail.logic.MailTemplate
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * GrapMail 2026-11 feature wave — shared Compose surfaces.
 *
 * Covers: F07 category chips, F08 thread summary card, F09 smart reply strip,
 * F10 compose assist row, F11 template picker, F12 follow-up banner,
 * F16 tracker shield banner, F23 QR/link warning dialog, F24 receipt chip.
 * All rendering only; data comes from the pure-Kotlin engines in
 * com.grapsee.grapmail.features and the local stores in
 * com.grapsee.grapmail.logic.MailFeaturePrefs.
 */

object MailFeatureIntents {
    fun openUrl(context: android.content.Context, url: String) {
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
            )
        }
    }

    fun copyText(context: android.content.Context, label: String, text: String) {
        runCatching {
            val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            manager.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        }
    }

    /**
     * A one-time code onto the clipboard, marked sensitive so Android 13+
     * keeps it out of the clipboard preview (the framework key there, the
     * documented literal below it, ignored where unknown) — the same marking
     * the notification's "Copy code" uses (logic/Notifications.kt). The code is
     * re-checked against [com.grapsee.grapmail.logic.CodeFinder.isPlausible]
     * so nothing but a code shape is ever copied. True when the copy landed.
     */
    fun copySensitiveCode(context: android.content.Context, label: String, code: String): Boolean {
        if (!com.grapsee.grapmail.logic.CodeFinder.isPlausible(code)) return false
        return runCatching {
            val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText(label, code)
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                        android.content.ClipDescription.EXTRA_IS_SENSITIVE
                    else "android.content.extra.IS_SENSITIVE",
                    true,
                )
            }
            manager.setPrimaryClip(clip)
            true
        }.getOrDefault(false)
    }
}

@Composable
fun ThreadSummaryCard(summary: ThreadSummary, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.mail_thread_summary_title, summary.messageCount, summary.messageCount),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) stringResource(R.string.mail_summary_less) else stringResource(R.string.mail_summary_more),
                    fontSize = 12.sp,
                    modifier = Modifier.clickable(
                        onClickLabel = if (expanded) stringResource(R.string.mail_summary_collapse) else stringResource(R.string.mail_summary_expand),
                    ) { expanded = !expanded },
                )
            }
            Text(summary.headline, fontSize = 13.sp)
            if (expanded) {
                summary.keyPoints.forEach { point ->
                    Text(stringResource(R.string.mail_bullet_item, point), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun ActionCardsRow(cards: List<ActionCard>, onOpen: (ActionCard) -> Unit, modifier: Modifier = Modifier) {
    if (cards.isEmpty()) return
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cards.forEach { card ->
            AssistChip(
                onClick = { onOpen(card) },
                label = {
                    Text(card.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
        }
    }
}

@Composable
fun SmartReplyStrip(suggestions: List<String>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    if (suggestions.isEmpty()) return
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        suggestions.forEach { suggestion ->
            AssistChip(
                onClick = { onPick(suggestion) },
                label = {
                    Text(suggestion, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
        }
    }
}

@Composable
fun MailCategoryChipsRow(selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(
        stringResource(R.string.mail_category_all) to "",
        stringResource(R.string.mail_category_priority) to "priority",
        stringResource(R.string.mail_category_people) to "people",
        stringResource(R.string.mail_category_transactions) to "transactions",
        stringResource(R.string.mail_category_notices) to "notices",
        stringResource(R.string.mail_category_newsletters) to "newsletters",
    )
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { pair ->
            val key: String? = if (pair.second.isEmpty()) null else pair.second
            FilterChip(
                selected = selected == key,
                onClick = { onSelect(key) },
                label = { Text(pair.first, fontSize = 12.sp) },
            )
        }
    }
}

@Composable
fun TrackerShieldBanner(
    trackerCount: Int,
    remoteCount: Int,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (trackerCount > 0) {
                stringResource(
                    R.string.mail_tracker_summary,
                    pluralStringResource(R.plurals.mail_trackers_blocked, trackerCount, trackerCount),
                    pluralStringResource(R.plurals.mail_remote_images_stopped, remoteCount, remoteCount),
                )
            } else {
                stringResource(R.string.mail_tracker_shield_on)
            },
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
fun QrWarningDialog(verdict: UrlVerdict, onDismiss: () -> Unit, onOpen: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (verdict.risk == UrlRisk.HIGH) stringResource(R.string.mail_link_dangerous)
                else stringResource(R.string.mail_link_careful),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.mail_link_destination, verdict.displayHost.ifEmpty { verdict.original }),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                verdict.reasons.forEach { reason ->
                    Text(stringResource(R.string.mail_bullet_item, reason), fontSize = 12.sp)
                }
                if (verdict.reasons.isEmpty()) {
                    Text(stringResource(R.string.mail_link_no_signals), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpen() }) { Text(stringResource(R.string.mail_link_open_anyway)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.mail_common_cancel)) }
        },
    )
}

@Composable
fun ComposeAssistRow(getBody: () -> String, setBody: (String) -> Unit, modifier: Modifier = Modifier) {
    var showPrompt by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var issues by remember { mutableStateOf<List<String>?>(null) }

    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(onClick = { showPrompt = true }, label = { Text(stringResource(R.string.mail_assist_draft), fontSize = 12.sp) })
        AssistChip(
            onClick = { setBody(ComposeAssist.rewrite(getBody())) },
            label = { Text(stringResource(R.string.mail_assist_rewrite), fontSize = 12.sp) },
        )
        AssistChip(
            onClick = { setBody(ComposeAssist.transform(getBody(), ComposeTone.PROFESSIONAL)) },
            label = { Text(stringResource(R.string.mail_assist_professional), fontSize = 12.sp) },
        )
        AssistChip(
            onClick = { setBody(ComposeAssist.transform(getBody(), ComposeTone.FRIENDLY)) },
            label = { Text(stringResource(R.string.mail_assist_friendly), fontSize = 12.sp) },
        )
        AssistChip(
            onClick = { setBody(ComposeAssist.transform(getBody(), ComposeTone.CONCISE)) },
            label = { Text(stringResource(R.string.mail_assist_concise), fontSize = 12.sp) },
        )
        AssistChip(
            onClick = { issues = ComposeAssist.proofread(getBody()) },
            label = { Text(stringResource(R.string.mail_assist_proofread), fontSize = 12.sp) },
        )
    }

    if (showPrompt) {
        AlertDialog(
            onDismissRequest = { showPrompt = false },
            title = { Text(stringResource(R.string.mail_assist_draft_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text(stringResource(R.string.mail_assist_prompt_label)) },
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                    )
                    Text(stringResource(R.string.mail_assist_on_device_note), fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (prompt.isNotBlank()) {
                            setBody(ComposeAssist.draft(prompt).body)
                            prompt = ""
                            showPrompt = false
                        }
                    },
                ) { Text(stringResource(R.string.mail_assist_draft_it)) }
            },
            dismissButton = {
                TextButton(onClick = { showPrompt = false }) { Text(stringResource(R.string.mail_common_cancel)) }
            },
        )
    }

    issues?.let { list ->
        AlertDialog(
            onDismissRequest = { issues = null },
            title = { Text(stringResource(R.string.mail_assist_proofread)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (list.isEmpty()) {
                        Text(stringResource(R.string.mail_assist_no_issues), fontSize = 13.sp)
                    } else {
                        list.forEach { issue -> Text(stringResource(R.string.mail_bullet_item, issue), fontSize = 13.sp) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { issues = null }) { Text(stringResource(R.string.mail_common_done)) }
            },
        )
    }
}

@Composable
fun TemplatePickerChip(
    prefs: MailFeaturePrefs,
    firstName: String?,
    myName: String?,
    onInsert: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { open = true },
        label = { Text(stringResource(R.string.mail_templates), fontSize = 12.sp) },
        modifier = modifier,
    )
    if (open) {
        var query by remember { mutableStateOf("") }
        var templates by remember { mutableStateOf(prefs.templates()) }
        var adding by remember { mutableStateOf(false) }
        var newName by remember { mutableStateOf("") }
        var newBody by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.mail_templates)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.mail_templates_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val filtered = templates.filter {
                        it.name.contains(query, ignoreCase = true) || it.body.contains(query, ignoreCase = true)
                    }
                    if (filtered.isEmpty()) {
                        Text(stringResource(R.string.mail_templates_no_match), fontSize = 12.sp)
                    }
                    val insertLabel = stringResource(R.string.mail_templates_insert)
                    filtered.forEach { template ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(onClickLabel = insertLabel) {
                                    onInsert(prefs.fillTemplate(template.body, firstName, myName))
                                    open = false
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(template.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    template.body.replace("\n", " "),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (!template.builtin) {
                                TextButton(
                                    onClick = {
                                        val next = templates.filterNot {
                                            it.name == template.name && it.body == template.body
                                        }
                                        prefs.saveTemplates(next)
                                        templates = next
                                    },
                                ) { Text(stringResource(R.string.mail_action_delete), fontSize = 11.sp) }
                            }
                        }
                    }
                    if (adding) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text(stringResource(R.string.mail_templates_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = newBody,
                            onValueChange = { newBody = it },
                            label = { Text(stringResource(R.string.mail_templates_body)) },
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                        )
                        TextButton(
                            onClick = {
                                if (newName.isNotBlank() && newBody.isNotBlank()) {
                                    val next = templates + MailTemplate(newName.trim(), newBody)
                                    prefs.saveTemplates(next)
                                    templates = next
                                    newName = ""
                                    newBody = ""
                                    adding = false
                                }
                            },
                        ) { Text(stringResource(R.string.mail_templates_save)) }
                    } else {
                        TextButton(onClick = { adding = true }) { Text(stringResource(R.string.mail_templates_new)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.mail_common_close)) }
            },
        )
    }
}

@Composable
fun FollowUpBanner(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pluralStringResource(R.plurals.mail_followup_awaiting, count, count),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onOpen) { Text(stringResource(R.string.mail_followup_review), fontSize = 12.sp) }
    }
}

@Composable
fun ReadReceiptChip(sent: Boolean, modifier: Modifier = Modifier) {
    Text(
        if (sent) stringResource(R.string.mail_receipt_opened) else stringResource(R.string.mail_receipt_markers_on),
        fontSize = 11.sp,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MailComposeTools(getBody: () -> String, setBody: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { MailFeaturePrefs(context) }
    remember { prefs.seedDefaultTemplates() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // On-device suggestions belong to the same switch as summaries.
        if (prefs.smartFeaturesEnabled) ComposeAssistRow(getBody = getBody, setBody = setBody)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TemplatePickerChip(
                prefs = prefs,
                firstName = null,
                myName = null,
                onInsert = { text ->
                    val current = getBody()
                    setBody(if (current.isBlank()) text else current + "\n\n" + text)
                },
            )
            Text(stringResource(R.string.mail_assist_runs_on_device), fontSize = 11.sp, color = LocalPalette.current.textSecondary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MailFeatureSettingsSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { MailFeaturePrefs(context) }
    remember { prefs.seedDefaultTemplates() }

    var smart by remember { mutableStateOf(prefs.smartFeaturesEnabled) }
    var shield by remember { mutableStateOf(prefs.trackerShieldEnabled) }
    var qr by remember { mutableStateOf(prefs.qrWarningsEnabled) }
    var receipts by remember { mutableStateOf(prefs.readReceiptsEnabled) }
    var followups by remember { mutableStateOf(prefs.followUpsEnabled) }
    var showFollowUps by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)
            .background(palette.card, RoundedCornerShape(20.dp))
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        com.grapsee.grapmail.ui.screens.mail.SectionLabel(stringResource(R.string.mail_features_section), palette)
        MailFeatureSwitchRow(
            stringResource(R.string.mail_features_smart_title),
            stringResource(R.string.mail_features_smart_body),
            smart,
        ) {
            smart = it
            prefs.smartFeaturesEnabled = it
        }
        MailFeatureSwitchRow(
            stringResource(R.string.mail_features_shield_title),
            stringResource(R.string.mail_features_shield_body),
            shield,
        ) {
            shield = it
            prefs.trackerShieldEnabled = it
        }
        MailFeatureSwitchRow(
            stringResource(R.string.mail_features_qr_title),
            stringResource(R.string.mail_features_qr_body),
            qr,
        ) {
            qr = it
            prefs.qrWarningsEnabled = it
        }
        MailFeatureSwitchRow(
            stringResource(R.string.mail_features_receipts_title),
            stringResource(R.string.mail_features_receipts_body),
            receipts,
        ) {
            receipts = it
            prefs.readReceiptsEnabled = it
        }
        ReadReceiptModeSetting()
        MailFeatureSwitchRow(
            stringResource(R.string.mail_features_followups_title),
            stringResource(R.string.mail_features_followups_body),
            followups,
        ) {
            followups = it
            prefs.followUpsEnabled = it
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { showFollowUps = true }) { Text(stringResource(R.string.mail_features_followup_center), fontSize = 13.sp) }
            TextButton(onClick = { showImport = true }) { Text(stringResource(R.string.mail_features_import_assistant), fontSize = 13.sp) }
        }
    }
    if (showFollowUps) {
        FollowUpCenterScreen(onClose = { showFollowUps = false })
    }
    if (showImport) {
        ImportAssistantScreen(onClose = { showImport = false })
    }
}

@Composable
private fun MailFeatureSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, color = palette.textPrimary)
            Text(subtitle, fontSize = 13.sp, color = palette.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * F07/F08/F16/F23/F24/F27 — the reader's on-device panel: thread summary,
 * extracted action chips, suspicious-link review, tracker-shield status and
 * the opt-in read-receipt chip. Everything renders from data already on the
 * device; nothing here performs a network call.
 */
@Composable
fun MailSmartPanel(message: MailMessageFullDto?, messages: List<MailMessageFullDto>) {
    if (message == null) return
    val context = LocalContext.current
    val prefs = remember { MailFeaturePrefs(context) }

    // The four intelligence passes are pure functions, but they cost real CPU
    // (sentence splitting, ten-plus regexes, a tracker sweep over the full
    // HTML). They used to run synchronously inside remember() — on the main
    // thread, in composition, during the reader's first frame. produceState
    // moves them to Default while the panel shows its previous state.
    val summary by androidx.compose.runtime.produceState<com.grapsee.grapmail.features.ThreadSummary?>(
        initialValue = null, messages.size, message.id,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            // Settings → On-device intelligence: off means no summary.
            if (messages.size >= 3 && prefs.smartFeaturesEnabled) {
                ThreadSummarizer.summarize(
                    messages.map { m ->
                        ThreadMessageText(
                            senderName = "",
                            body = m.bodyText.orEmpty(),
                            fromMe = m.direction == "outbound",
                        )
                    },
                )
            } else null
        }
    }
    val cards by androidx.compose.runtime.produceState<List<com.grapsee.grapmail.features.ActionCard>?>(
        initialValue = null, message.id,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (!prefs.smartFeaturesEnabled) emptyList()
            else ActionCardExtractor.extract(message.subject.orEmpty(), message.bodyText.orEmpty())
        }
    }
    val verdicts by androidx.compose.runtime.produceState<List<com.grapsee.grapmail.features.UrlVerdict>?>(
        initialValue = null, message.id,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            // Settings → QR & link warnings: off means no link review.
            if (!prefs.qrWarningsEnabled) return@withContext emptyList<com.grapsee.grapmail.features.UrlVerdict>()
            Regex("https?://[^\\s<>\"']+")
                .findAll(message.bodyText.orEmpty())
                .map { m -> UrlSafety.analyze(m.value) }
                .filter { v -> v.risk != UrlRisk.LOW }
                .distinctBy { v -> v.displayHost }
                .take(4)
                .toList()
        }
    }
    val trackerScan by androidx.compose.runtime.produceState<com.grapsee.grapmail.features.TrackerScanResult?>(
        initialValue = null, message.id, prefs.allowRemoteImages,
    ) {
        val html = message.bodyHtml
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (message.hasHtml && !html.isNullOrBlank()) TrackerShield.scan(html, prefs.allowRemoteImages) else null
        }
    }
    var warning by remember { mutableStateOf<UrlVerdict?>(null) }

    LaunchedEffect(message.id, prefs.readReceiptsEnabled) {
        if (prefs.readReceiptsEnabled && message.direction != "outbound") {
            prefs.markReceiptSent(message.id)
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        summary?.let { ThreadSummaryCard(it) }
        // Server-backed read receipts: Ask prompt for received mail, "Read <time>" for sent.
        ReadReceiptAskPrompt(messageId = message.id, direction = message.direction)
        val serverRead = SentReadLine(messageId = message.id, direction = message.direction)
        if (!cards.isNullOrEmpty()) {
            // Named, not trailing: ActionCardsRow's last parameter is `modifier`,
            // so a trailing lambda would be read as a Modifier and every field
            // access inside it (card.url/label/value) became an unresolved
            // reference at compile time.
            ActionCardsRow(cards.orEmpty(), onOpen = { card ->
                val url = card.url
                if (url != null) {
                    MailFeatureIntents.openUrl(context, url)
                } else {
                    MailFeatureIntents.copyText(context, card.label, card.value)
                }
            })
        }
        if (!verdicts.isNullOrEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                verdicts.orEmpty().forEach { v ->
                    AssistChip(
                        onClick = { warning = v },
                        label = {
                            Text(
                                if (v.risk == UrlRisk.HIGH) stringResource(R.string.mail_link_chip_high, v.displayHost)
                                else stringResource(R.string.mail_link_chip_review, v.displayHost),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
        trackerScan?.let { scan ->
            if (scan.trackerCount > 0 || scan.remoteImageCount > 0) {
                TrackerShieldBanner(
                    trackerCount = scan.trackerCount,
                    remoteCount = scan.remoteImageCount,
                    enabled = prefs.trackerShieldEnabled,
                    onToggle = { prefs.trackerShieldEnabled = it },
                )
            }
        }
        // The on-device "Opened" marker is hidden once the server has a real receipt.
        if (prefs.readReceiptsEnabled && !serverRead) {
            ReadReceiptChip(sent = prefs.wasReceiptSent(message.id))
        }
    }

    warning?.let { v ->
        QrWarningDialog(
            verdict = v,
            onDismiss = { warning = null },
            onOpen = {
                MailFeatureIntents.openUrl(context, v.original)
                warning = null
            },
        )
    }
}

// ── Round 8 — C: the mailbox safety nudge (docs/design/round8-spec.md §4.3 B) ──

/**
 * One card at the top of the mailbox list, below the greeting: a mini score
 * ring (40, stroke 5), "Make your account harder to break into", the score and
 * the top todo step, an "Improve" text button that opens the Security Center,
 * and a 44 dp close button labelled "Dismiss". `primaryContainer`, radius 20,
 * padding 16. Rendering only: when it shows, the "shown" stamp and the
 * slide-out on dismiss are owned by MailboxSafetySlotContent
 * (ui/screens/security/SecurityCenterScreen.kt).
 */
@Composable
fun SafetyNudgeCard(
    value: Int,
    level: String?,
    nextStep: String,
    onImprove: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(palette.primaryContainer)
            .padding(start = 16.dp, top = 16.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        com.grapsee.grapmail.ui.screens.security.SafetyMiniRing(value = value, level = level)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.seccenter_nudge_title),
                style = com.grapsee.grapmail.ui.theme.BodyMedium,
                color = palette.onPrimaryContainer,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.seccenter_nudge_body, value, nextStep),
                style = com.grapsee.grapmail.ui.theme.Caption,
                color = palette.onPrimaryContainer,
            )
            TextButton(
                onClick = onImprove,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(
                    stringResource(R.string.seccenter_nudge_improve),
                    style = com.grapsee.grapmail.ui.theme.BodyMedium,
                    color = palette.primaryText,
                )
            }
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.seccenter_nudge_dismiss),
                tint = palette.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
