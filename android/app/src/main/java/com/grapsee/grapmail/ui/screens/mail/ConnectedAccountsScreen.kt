package com.grapsee.grapmail.ui.screens.mail

import androidx.compose.material3.minimumInteractiveComponentSize
import com.grapsee.grapmail.ui.components.GMLayout
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.ProviderAccountDto
import com.grapsee.grapmail.logic.ConnectedAccountsViewModel
import com.grapsee.grapmail.logic.ConnectedAccountsViewModel.Problem
import com.grapsee.grapmail.logic.ImapFormError
import com.grapsee.grapmail.logic.ProviderPresets
import com.grapsee.grapmail.logic.ProviderStatus
import com.grapsee.grapmail.logic.ProviderUiStatus
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Connected accounts — Outlook (Microsoft Graph OAuth) and IMAP/SMTP mailboxes the
 * backend syncs into the GrapMail mailbox (routes/grapmail-providers.ts).
 *
 * Mail from these accounts is written by the backend's sync job into the same
 * grapmail_messages table as native mail (lib/mail-providers/gmail.ts
 * applyProviderMessage), so it already appears in the existing Inbox; no
 * separate mailbox view is added here.
 *
 * Gmail is not offered (paid assessment is off).
 * Strings: res/values/strings_providers.xml (prefix mail_providers_).
 */
private enum class Pane { LIST, IMAP }

@Composable
fun ConnectedAccountsScreen(
    onBack: () -> Unit,
    vm: ConnectedAccountsViewModel = viewModel(),
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var pane by remember { mutableStateOf(Pane.LIST) }
    var confirmRemove by remember { mutableStateOf<ProviderAccountDto?>(null) }
    var outlookEmail by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(state.connectedNote) { if (state.connectedNote) { pane = Pane.LIST; vm.consumeConnectedNote() } }

    val title = if (pane == Pane.IMAP) stringResource(R.string.mail_providers_imap_title) else stringResource(R.string.mail_providers_title)
    MailScaffold(title, onBack = { if (pane == Pane.IMAP) pane = Pane.LIST else onBack() },
        maxContentWidth = GMLayout.Form) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            state.problem?.let { ProblemBanner(it, state.problemDetail, onRetry = vm::load) }
            when (pane) {
                Pane.LIST -> {
                    Text(stringResource(R.string.mail_providers_intro), fontSize = 13.sp, lineHeight = 19.sp, color = palette.textSecondary)
                    Spacer(Modifier.height(12.dp))
                    when {
                        state.accounts == null && state.loading -> com.grapsee.grapmail.ui.components.ListSkeleton(
                            rows = 3, avatar = false,
                            label = stringResource(R.string.states_providers_loading))
                        state.accounts != null && state.visible.isEmpty() -> Text(stringResource(R.string.mail_providers_empty),
                            fontSize = 14.sp, color = palette.textTertiary, modifier = Modifier.padding(vertical = 16.dp).testTag("providers-empty"))
                        else -> state.visible.forEach { a ->
                            AccountRow(a, busy = state.busyId == a.id,
                                onSync = { vm.syncNow(a.id) }, onRemove = { confirmRemove = a },
                                onReconnect = {
                                    if (a.provider == "microsoft") outlookEmail = a.providerEmail
                                    else { vm.editForm(state.form.copy(email = a.providerEmail)); pane = Pane.IMAP }
                                })
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.mail_providers_add), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                    Spacer(Modifier.height(8.dp))
                    GradientButton(stringResource(R.string.mail_providers_add_outlook), onClick = { outlookEmail = "" },
                        busy = state.outlookStarting, enabled = !state.outlookStarting, modifier = Modifier.testTag("providers-add-outlook"))
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { pane = Pane.IMAP }, modifier = Modifier.testTag("providers-add-imap")) {
                        Text(stringResource(R.string.mail_providers_add_imap))
                    }
                    Spacer(Modifier.height(24.dp))
                }
                Pane.IMAP -> ImapFormPane(vm, state)
            }
        }
    }

    outlookEmail?.let { initial ->
        var email by remember(initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { outlookEmail = null },
            title = { Text(stringResource(R.string.mail_providers_outlook_title)) },
            text = {
                // Scrolls: in a landscape phone or split-screen half the body,
                // the field and the keyboard do not all fit.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.mail_providers_outlook_body), fontSize = 13.sp, color = palette.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(email, { email = it.trim() }, singleLine = true,
                        label = { Text(stringResource(R.string.mail_providers_email)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().testTag("providers-outlook-email"))
                }
            },
            confirmButton = {
                TextButton(enabled = email.contains('@'), onClick = {
                    outlookEmail = null
                    vm.startOutlook(email) { url ->
                        // The system browser: the consent page must not run inside the app.
                        // (androidx.browser Custom Tabs is not a dependency; ACTION_VIEW opens
                        // the default browser, which is what Custom Tabs would fall back to.)
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    }
                }) { Text(stringResource(R.string.mail_providers_continue)) }
            },
            dismissButton = { TextButton(onClick = { outlookEmail = null }) { Text(stringResource(R.string.mail_providers_cancel)) } },
        )
    }

    confirmRemove?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.mail_providers_remove_title, a.providerEmail)) },
            text = { Text(stringResource(R.string.mail_providers_remove_body),
                modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { confirmRemove = null; vm.disconnect(a.id) }, modifier = Modifier.testTag("providers-remove-confirm")) {
                Text(stringResource(R.string.mail_providers_remove), color = palette.error) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.mail_providers_cancel)) } },
        )
    }
}

@Composable
private fun ProblemBanner(p: Problem, detail: String?, onRetry: () -> Unit) {
    val palette = LocalPalette.current
    val text = when (p) {
        Problem.OFFLINE -> stringResource(R.string.mail_providers_offline)
        Problem.SIGNED_OUT -> stringResource(R.string.mail_notify_sign_in_again)
        Problem.OUTLOOK_UNAVAILABLE -> stringResource(R.string.mail_providers_outlook_unavailable)
        Problem.OAUTH_STATE -> stringResource(R.string.mail_providers_oauth_state)
        Problem.OAUTH_DENIED -> stringResource(R.string.mail_providers_oauth_denied)
        Problem.SERVER -> detail ?: stringResource(R.string.mail_providers_error)
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp)).background(palette.card)
        .border(1.dp, palette.error, RoundedCornerShape(12.dp)).padding(12.dp).testTag("providers-problem")) {
        Text(text, fontSize = 13.sp, color = palette.error)
        if (p == Problem.OFFLINE || p == Problem.SERVER) TextButton(onClick = onRetry) { Text(stringResource(R.string.mail_providers_retry)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountRow(a: ProviderAccountDto, busy: Boolean, onSync: () -> Unit, onRemove: () -> Unit, onReconnect: () -> Unit) {
    val palette = LocalPalette.current
    val status = ProviderStatus.of(a.status, a.lastSyncAt, a.lastError)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.card)
        .border(1.dp, palette.border, RoundedCornerShape(16.dp)).padding(14.dp).testTag("provider-row-${a.id}")) {
        Text(a.providerEmail, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
        Text(if (a.provider == "microsoft") "Outlook" else stringResource(R.string.mail_providers_kind_imap), fontSize = 12.sp, color = palette.textTertiary)
        Spacer(Modifier.height(4.dp))
        val (label, bad) = when (status) {
            ProviderUiStatus.SYNCING -> stringResource(R.string.mail_providers_status_syncing) to false
            ProviderUiStatus.OK -> stringResource(R.string.mail_providers_status_ok) to false
            ProviderUiStatus.NEEDS_REAUTH -> stringResource(R.string.mail_providers_status_reauth) to true
            ProviderUiStatus.ERROR -> stringResource(R.string.mail_providers_status_error) to true
            ProviderUiStatus.DISCONNECTED -> stringResource(R.string.mail_providers_status_removed) to false
        }
        Text(label, fontSize = 13.sp, color = if (bad) palette.error else palette.textSecondary)
        if (status == ProviderUiStatus.ERROR) a.lastError?.let { Text(it, fontSize = 12.sp, color = palette.textTertiary) }
        Text(a.lastSyncAt?.let { stringResource(R.string.mail_providers_last_sync, relative(it)) }
            ?: stringResource(R.string.mail_providers_never_synced), fontSize = 12.sp, color = palette.textTertiary)
        // Wraps: at large text or on a narrow cover screen the actions go
        // onto a second line instead of running off the card.
        FlowRow {
            if (busy) CircularProgressIndicator(Modifier.padding(8.dp).align(Alignment.CenterVertically),
                color = palette.primarySolid, strokeWidth = 2.dp)
            if (status == ProviderUiStatus.NEEDS_REAUTH) TextButton(onClick = onReconnect, enabled = !busy) { Text(stringResource(R.string.mail_providers_reconnect)) }
            else TextButton(onClick = onSync, enabled = !busy, modifier = Modifier.testTag("provider-sync-${a.id}")) { Text(stringResource(R.string.mail_providers_sync_now)) }
            TextButton(onClick = onRemove, enabled = !busy, modifier = Modifier.testTag("provider-remove-${a.id}")) {
                Text(stringResource(R.string.mail_providers_remove), color = palette.error)
            }
        }
    }
}

/** Postgres timestamptz arrives as ISO-8601; anything unparsable is shown as sent. */
private fun relative(iso: String): String = runCatching {
    val ms = java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}.getOrDefault(iso)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImapFormPane(vm: ConnectedAccountsViewModel, state: ConnectedAccountsViewModel.State) {
    val palette = LocalPalette.current
    val uri = LocalUriHandler.current
    val f = state.form
    val preset = ProviderPresets.byId(f.presetId)
    val err = state.formErrors

    Text(stringResource(R.string.mail_providers_imap_intro), fontSize = 13.sp, lineHeight = 19.sp, color = palette.textSecondary)
    Spacer(Modifier.height(12.dp))
    // Chips keep their look; each sits in a 48dp touch target, which also
    // supplies the gap between wrapped lines.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderPresets.PRESETS.forEach { p ->
            val selected = p.id == f.presetId
            Text(if (p.isCustom) stringResource(R.string.mail_providers_preset_custom) else p.label,
                fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) palette.onPrimary else palette.textPrimary,
                modifier = Modifier.align(Alignment.CenterVertically).minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) palette.primarySolid else palette.card)
                    .border(1.dp, if (selected) palette.primarySolid else palette.border, RoundedCornerShape(20.dp))
                    .clickable(role = Role.RadioButton) { vm.choosePreset(p.id) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("providers-preset-${p.id}"))
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(stringResource(if (preset.isCustom) R.string.mail_providers_help_custom else R.string.mail_providers_help_app_password),
        fontSize = 12.sp, lineHeight = 18.sp, color = palette.textSecondary)
    preset.appPasswordUrl?.let { url ->
        TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(stringResource(R.string.mail_import_create_app_password)) }
    }

    OutlinedTextField(f.email, { v ->
        val e = v.trim()
        val guess = ProviderPresets.forEmail(e)
        vm.editForm(if (guess != null && f.presetId != guess.id && f.presetId != ProviderPresets.CUSTOM) f.withPreset(guess).copy(email = e) else f.copy(email = e))
    }, singleLine = true, isError = ImapFormError.EMAIL in err,
        label = { Text(stringResource(R.string.mail_providers_email)) },
        supportingText = if (ImapFormError.EMAIL in err) {{ Text(stringResource(R.string.mail_providers_err_email)) }} else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth().testTag("providers-email"))
    OutlinedTextField(f.password, { vm.editForm(f.copy(password = it)) }, singleLine = true, isError = ImapFormError.PASSWORD in err,
        label = { Text(stringResource(R.string.mail_import_app_password)) }, visualTransformation = PasswordVisualTransformation(),
        supportingText = if (ImapFormError.PASSWORD in err) {{ Text(stringResource(R.string.mail_import_err_password)) }} else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag("providers-password"))

    if (preset.isCustom) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.mail_providers_incoming), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
        ServerFields(f.imapHost, f.imapPort, f.imapTls, ImapFormError.IMAP_HOST in err, ImapFormError.IMAP_PORT in err, "imap",
            onHost = { vm.editForm(f.copy(imapHost = it.trim())) }, onPort = { vm.editForm(f.copy(imapPort = it.filter(Char::isDigit))) },
            onTls = { vm.editForm(f.copy(imapTls = it)) })
        Text(stringResource(R.string.mail_providers_outgoing), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
        ServerFields(f.smtpHost, f.smtpPort, f.smtpTls, ImapFormError.SMTP_HOST in err, ImapFormError.SMTP_PORT in err, "smtp",
            onHost = { vm.editForm(f.copy(smtpHost = it.trim())) }, onPort = { vm.editForm(f.copy(smtpPort = it.filter(Char::isDigit))) },
            onTls = { vm.editForm(f.copy(smtpTls = it)) })
    } else {
        Spacer(Modifier.height(6.dp))
        Text("${preset.imapHost}:${preset.imapPort} · ${preset.smtpHost}:${preset.smtpPort}", fontSize = 12.sp, color = palette.textTertiary)
    }
    Spacer(Modifier.height(16.dp))
    GradientButton(stringResource(if (state.submitting) R.string.mail_providers_connecting else R.string.mail_providers_connect),
        onClick = { if (!state.submitting) vm.submitImap() }, busy = state.submitting, modifier = Modifier.testTag("providers-imap-submit"))
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.mail_providers_no_probe_note), fontSize = 12.sp, lineHeight = 18.sp, color = palette.textTertiary)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ServerFields(host: String, port: String, tls: Boolean, hostBad: Boolean, portBad: Boolean, tag: String,
                         onHost: (String) -> Unit, onPort: (String) -> Unit, onTls: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    OutlinedTextField(host, onHost, singleLine = true, isError = hostBad,
        label = { Text(stringResource(R.string.mail_providers_host)) },
        supportingText = if (hostBad) {{ Text(stringResource(R.string.mail_providers_err_host)) }} else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag("providers-$tag-host"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(port, onPort, singleLine = true, isError = portBad,
            label = { Text(stringResource(R.string.mail_providers_port)) },
            supportingText = if (portBad) {{ Text(stringResource(R.string.mail_providers_err_port)) }} else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f).testTag("providers-$tag-port"))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.mail_providers_tls), fontSize = 13.sp, color = palette.textPrimary,
            modifier = Modifier.weight(0.6f, fill = false))
        Switch(checked = tls, onCheckedChange = onTls, modifier = Modifier.testTag("providers-$tag-tls"))
    }
}
