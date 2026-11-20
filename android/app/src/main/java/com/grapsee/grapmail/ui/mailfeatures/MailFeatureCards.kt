package com.grapsee.grapmail.ui.mailfeatures

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
}

@Composable
fun ThreadSummaryCard(summary: ThreadSummary, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Thread summary · " + summary.messageCount + " messages",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "Less" else "More",
                    fontSize = 12.sp,
                    modifier = Modifier.clickable(onClickLabel = if (expanded) "Collapse summary" else "Expand summary") { expanded = !expanded },
                )
            }
            Text(summary.headline, fontSize = 13.sp)
            if (expanded) {
                summary.keyPoints.forEach { point ->
                    Text("• " + point, fontSize = 12.sp)
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
        "All" to "",
        "Priority" to "priority",
        "People" to "people",
        "Transactions" to "transactions",
        "Notices" to "notices",
        "Newsletters" to "newsletters",
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
                trackerCount.toString() + " tracker" + (if (trackerCount == 1) "" else "s") +
                    " blocked · " + remoteCount + " remote image" +
                    (if (remoteCount == 1) "" else "s") + " stopped"
            } else {
                "Tracker shield on — tracking pixels stripped before display"
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
            Text(if (verdict.risk == UrlRisk.HIGH) "Dangerous link detected" else "Careful with this link")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Destination: " + verdict.displayHost.ifEmpty { verdict.original },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                verdict.reasons.forEach { reason ->
                    Text("• " + reason, fontSize = 12.sp)
                }
                if (verdict.reasons.isEmpty()) {
                    Text("No known danger signals were found for this link.", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpen() }) { Text("Open anyway") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
        AssistChip(onClick = { showPrompt = true }, label = { Text("Draft", fontSize = 12.sp) })
        AssistChip(
            onClick = { setBody(ComposeAssist.rewrite(getBody())) },
            label = { Text("Rewrite", fontSize = 12.sp) },
        )
        AssistChip(
            onClick = { setBody(ComposeAssist.transform(getBody(), ComposeTone.PROFESSIONAL)) },
            label = { Text("Professional", fontSize = 12.sp) },
        )
        AssistChip(
            onClick = { setBody(ComposeAssist.transform(getBody(), ComposeTone.FRIENDLY)) },
            label = { Text("Friendly", fontSize = 12.sp) },
        )
        AssistChip(
            onClick = { setBody(ComposeAssist.transform(getBody(), ComposeTone.CONCISE)) },
            label = { Text("Concise", fontSize = 12.sp) },
        )
        AssistChip(
            onClick = { issues = ComposeAssist.proofread(getBody()) },
            label = { Text("Proofread", fontSize = 12.sp) },
        )
    }

    if (showPrompt) {
        AlertDialog(
            onDismissRequest = { showPrompt = false },
            title = { Text("Draft with on-device assist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("What should the message say?") },
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                    )
                    Text("Runs fully on-device — nothing leaves the phone.", fontSize = 11.sp)
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
                ) { Text("Draft it") }
            },
            dismissButton = {
                TextButton(onClick = { showPrompt = false }) { Text("Cancel") }
            },
        )
    }

    issues?.let { list ->
        AlertDialog(
            onDismissRequest = { issues = null },
            title = { Text("Proofread") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (list.isEmpty()) {
                        Text("No obvious issues found.", fontSize = 13.sp)
                    } else {
                        list.forEach { issue -> Text("• " + issue, fontSize = 13.sp) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { issues = null }) { Text("Done") }
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
        label = { Text("Templates", fontSize = 12.sp) },
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
            title = { Text("Templates") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search templates") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val filtered = templates.filter {
                        it.name.contains(query, ignoreCase = true) || it.body.contains(query, ignoreCase = true)
                    }
                    if (filtered.isEmpty()) {
                        Text("No templates match.", fontSize = 12.sp)
                    }
                    filtered.forEach { template ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(onClickLabel = "Insert template") {
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
                                ) { Text("Delete", fontSize = 11.sp) }
                            }
                        }
                    }
                    if (adding) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = newBody,
                            onValueChange = { newBody = it },
                            label = { Text("Body — supports {first_name} and {my_name}") },
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
                        ) { Text("Save template") }
                    } else {
                        TextButton(onClick = { adding = true }) { Text("New template") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Close") }
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
            if (count == 1) "1 message awaiting a reply" else count.toString() + " messages awaiting a reply",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onOpen) { Text("Review", fontSize = 12.sp) }
    }
}

@Composable
fun ReadReceiptChip(sent: Boolean, modifier: Modifier = Modifier) {
    Text(
        if (sent) "Receipt noted on this device" else "Read receipt on",
        fontSize = 11.sp,
        modifier = modifier,
    )
}

@Composable
fun MailComposeTools(getBody: () -> String, setBody: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { MailFeaturePrefs(context) }
    remember { prefs.seedDefaultTemplates() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ComposeAssistRow(getBody = getBody, setBody = setBody)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
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
            Text("Assist runs fully on-device", fontSize = 11.sp)
        }
    }
}

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

    Column(
        Modifier.fillMaxWidth().padding(top = 18.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("Privacy & intelligence", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        MailFeatureSwitchRow(
            "On-device intelligence",
            "Categories, summaries and replies stay on this device",
            smart,
        ) {
            smart = it
            prefs.smartFeaturesEnabled = it
        }
        MailFeatureSwitchRow(
            "Tracker shield",
            "Strip tracking pixels and remote images before display",
            shield,
        ) {
            shield = it
            prefs.trackerShieldEnabled = it
        }
        MailFeatureSwitchRow(
            "QR & link warnings",
            "Warn before opening scanned or suspicious links",
            qr,
        ) {
            qr = it
            prefs.qrWarningsEnabled = it
        }
        MailFeatureSwitchRow(
            "Read receipts",
            "Off by default — share read state with GrapMail senders",
            receipts,
        ) {
            receipts = it
            prefs.readReceiptsEnabled = it
        }
        MailFeatureSwitchRow(
            "Follow-up reminders",
            "Remind me when a reply is overdue",
            followups,
        ) {
            followups = it
            prefs.followUpsEnabled = it
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { showFollowUps = true }) { Text("Follow-up center", fontSize = 13.sp) }
            TextButton(onClick = { showImport = true }) { Text("Import assistant", fontSize = 13.sp) }
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
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
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
            if (messages.size >= 3) {
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
            ActionCardExtractor.extract(message.subject.orEmpty(), message.bodyText.orEmpty())
        }
    }
    val verdicts by androidx.compose.runtime.produceState<List<com.grapsee.grapmail.features.UrlVerdict>?>(
        initialValue = null, message.id,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
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
                                (if (v.risk == UrlRisk.HIGH) "! " else "Review: ") + v.displayHost,
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
        if (prefs.readReceiptsEnabled) {
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
