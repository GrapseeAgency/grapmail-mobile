package com.grapsee.grapmail.ui.screens.mail

import com.grapsee.grapmail.ui.components.GMLayout
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ImportJobDto
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Import mail from another account over IMAP (backend lib/imap-import/job.ts).
 *
 * The other account is only read; nothing there changes. The app password is sent
 * once over TLS to start the import and is not kept, here or on the server.
 */
/**
 * `label` is the provider's own brand name and also builds the chip's test tag
 * (`import-provider-other`), so it stays English; `labelRes` translates the one
 * label that is a word rather than a brand.
 */
private data class Provider(
    val label: String, val host: String, val help: Int, val helpUrl: String?, val labelRes: Int? = null,
)

private val PROVIDERS = listOf(
    Provider("Gmail", "imap.gmail.com",
        R.string.mail_import_help_gmail,
        "https://myaccount.google.com/apppasswords"),
    Provider("Yahoo", "imap.mail.yahoo.com",
        R.string.mail_import_help_yahoo,
        "https://login.yahoo.com/account/security"),
    Provider("iCloud", "imap.mail.me.com",
        R.string.mail_import_help_icloud,
        "https://account.apple.com"),
    Provider("Other", "",
        R.string.mail_import_help_other,
        null, labelRes = R.string.mail_import_provider_other),
)

private val PERIODS = listOf(3 to R.string.mail_import_period_3m, 12 to R.string.mail_import_period_1y, 60 to R.string.mail_import_period_5y, 0 to R.string.mail_import_period_all)

@Composable
fun ImportMailDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImportMailScreen(onBack = onDismiss)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportMailScreen(onBack: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(PROVIDERS[0]) }
    var host by remember { mutableStateOf(PROVIDERS[0].host) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var inbox by remember { mutableStateOf(true) }
    var sent by remember { mutableStateOf(true) }
    var archive by remember { mutableStateOf(false) }
    var months by remember { mutableStateOf(12) }
    var job by remember { mutableStateOf<ImportJobDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val active = job != null && job?.finishedAt == null

    // The current job, and every two seconds while one runs.
    LaunchedEffect(active) {
        do {
            val token = SessionStore.token(context) ?: break
            runCatching { Api.importStatus(token) }.getOrNull()?.takeIf { it.success }?.let { job = it.job }
            if (job?.finishedAt != null || job == null) break
            delay(2_000)
        } while (true)
    }

    MailScaffold(stringResource(R.string.mail_import_title), onBack, maxContentWidth = GMLayout.Form) {
        com.grapsee.grapmail.ui.components.OfflineBanner()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            val j = job
            if (j != null) ImportProgress(j, onCancel = {
                scope.launch {
                    SessionStore.token(context)?.let { t -> runCatching { Api.importCancel(t) }.getOrNull()?.job?.let { job = it } }
                }
            })
            if (j != null && j.finishedAt == null) return@Column

            Text(stringResource(R.string.mail_import_intro),
                fontSize = 13.sp, lineHeight = 19.sp, color = palette.textSecondary)
            Spacer(Modifier.height(14.dp))
            // Chips keep their look; each sits in a 48dp touch target, which
            // also supplies the gap between wrapped lines.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PROVIDERS.forEach { p ->
                    val selected = p == provider
                    Text(p.labelRes?.let { stringResource(it) } ?: p.label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) palette.onPrimary else palette.textPrimary,
                        modifier = Modifier.align(Alignment.CenterVertically).minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selected) palette.primarySolid else palette.card)
                            .border(1.dp, if (selected) palette.primarySolid else palette.border, RoundedCornerShape(20.dp))
                            .clickable(role = Role.RadioButton) { provider = p; host = p.host }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("import-provider-${p.label.lowercase()}"))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(provider.help), fontSize = 12.sp, lineHeight = 18.sp, color = palette.textSecondary)
            provider.helpUrl?.let { url ->
                TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(stringResource(R.string.mail_import_create_app_password)) }
            }
            if (provider.host.isEmpty()) {
                OutlinedTextField(value = host, onValueChange = { host = it.trim() }, singleLine = true,
                    label = { Text(stringResource(R.string.mail_import_imap_server)) }, placeholder = { Text("imap.example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("import-host"))
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(value = username, onValueChange = { username = it.trim() }, singleLine = true,
                label = { Text(stringResource(R.string.mail_import_email_address)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth().testTag("import-username"))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true,
                label = { Text(stringResource(R.string.mail_import_app_password)) }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("import-password"))

            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.mail_import_folders), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            listOf(Triple(stringResource(R.string.mail_box_inbox), inbox) { v: Boolean -> inbox = v },
                Triple(stringResource(R.string.mail_box_sent), sent) { v: Boolean -> sent = v },
                Triple(stringResource(R.string.mail_import_folder_archive), archive) { v: Boolean -> archive = v }).forEach { (label, checked, set) ->
                // The whole row is the target, at least 48dp tall.
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) { set(!checked) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = palette.primarySolid))
                    Text(label, fontSize = 14.sp, color = palette.textPrimary, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.mail_import_how_far_back), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PERIODS.forEach { (value, label) ->
                    val selected = months == value
                    Text(stringResource(label), fontSize = 13.sp, color = if (selected) palette.onPrimary else palette.textPrimary,
                        modifier = Modifier.align(Alignment.CenterVertically).minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) palette.primarySolid else palette.card)
                            .border(1.dp, if (selected) palette.primarySolid else palette.border, RoundedCornerShape(16.dp))
                            .clickable(role = Role.RadioButton) { months = value }
                            .padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 13.sp, color = palette.error, modifier = Modifier.testTag("import-error"))
            }
            Spacer(Modifier.height(16.dp))
            val errHost = stringResource(R.string.mail_import_err_host)
            val errUser = stringResource(R.string.mail_import_err_user)
            val errPassword = stringResource(R.string.mail_import_err_password)
            val errFolder = stringResource(R.string.mail_import_err_folder)
            GradientButton(if (busy) stringResource(R.string.mail_import_starting) else stringResource(R.string.mail_import_start), onClick = {
                if (busy) return@GradientButton
                error = when {
                    host.isBlank() -> errHost
                    username.isBlank() -> errUser
                    password.isBlank() -> errPassword
                    !inbox && !sent && !archive -> errFolder
                    else -> null
                }
                if (error != null) return@GradientButton
                busy = true
                scope.launch {
                    val token = SessionStore.token(context)
                    if (token == null) { busy = false; error = context.getString(R.string.mail_notify_sign_in_again); return@launch }
                    runCatching { Api.importStart(token, host, username, password, inbox, sent, archive, months) }
                        .onSuccess { r -> if (r.success) { job = r.job; password = "" } else error = r.error ?: context.getString(R.string.mail_import_could_not_start) }
                        .onFailure { e -> error = e.message ?: context.getString(R.string.mail_import_could_not_start) }
                    busy = false
                }
            })
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.mail_import_outlook_note),
                fontSize = 12.sp, lineHeight = 18.sp, color = palette.textTertiary)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ImportProgress(job: ImportJobDto, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    val total = job.folders.sumOf { it.total }
    val done = job.folders.sumOf { it.done }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.card)
            .border(1.dp, palette.border, RoundedCornerShape(16.dp)).padding(16.dp).testTag("import-progress"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(when (job.state) {
            "connecting" -> stringResource(R.string.mail_import_connecting, job.host)
            "importing" -> stringResource(R.string.mail_import_importing, job.username)
            "done" -> stringResource(R.string.mail_import_done)
            "cancelled" -> stringResource(R.string.mail_import_cancelled)
            else -> stringResource(R.string.mail_import_problem)
        }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
        if (total > 0) {
            LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(), color = palette.primarySolid)
            Text(if (job.failed > 0) stringResource(R.string.mail_import_progress_failed, done, total, job.imported, job.skipped, job.failed)
                else stringResource(R.string.mail_import_progress, done, total, job.imported, job.skipped),
                fontSize = 12.sp, color = palette.textSecondary)
        }
        job.folders.forEach { f -> Text(stringResource(R.string.mail_import_folder_progress, f.name, f.done, f.total), fontSize = 12.sp, color = palette.textTertiary) }
        job.error?.let { Text(it, fontSize = 13.sp, color = palette.error) }
        if (job.finishedAt == null) {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("import-cancel")) { Text(stringResource(R.string.mail_import_stop)) }
        } else if (job.state == "done") {
            Text(stringResource(R.string.mail_import_done_note),
                fontSize = 12.sp, color = palette.textSecondary)
        }
    }
    Spacer(Modifier.height(16.dp))
}
