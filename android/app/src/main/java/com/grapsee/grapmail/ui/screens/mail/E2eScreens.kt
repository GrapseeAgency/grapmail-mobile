package com.grapsee.grapmail.ui.screens.mail

import com.grapsee.grapmail.ui.components.gmIsNarrowWindow
import com.grapsee.grapmail.ui.components.gmPrefersStackedRows
import com.grapsee.grapmail.ui.components.GMLayout
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.logic.E2eViewModel
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.absoluteDateTimeOrRaw
import com.grapsee.grapmail.ui.theme.LocalPalette
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R

/**
 * The encryption sector — the mobile form of the web's
 * `/mail/account/encryption`, split into the screens a phone needs: status,
 * setup, unlock, key details, publishing, passphrase change, removal, and the
 * recipient-key lookup compose asks for.
 *
 * The wording is the web page's, nearly line for line, including the parts it
 * REFUSES to promise: there is no passphrase recovery and the page says so
 * before you set one, not after you have lost it; removing the key makes every
 * message sealed to it unreadable, permanently and from everywhere, and the
 * screen says that before the destructive tap. A phone screen that softened
 * either line would be a phone screen that lies under pressure.
 */

// ── shared pieces ────────────────────────────────────────────────────────────

@Composable
private fun E2eCard(
    title: String? = null,
    tag: String? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(palette.card).border(1.dp, palette.border, RoundedCornerShape(18.dp))
            .padding(18.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        if (title != null) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
        }
        body()
    }
}

@Composable
private fun E2eNote(text: String, bad: Boolean) {
    val palette = LocalPalette.current
    Text(text, fontSize = 13.sp, lineHeight = 20.sp,
        color = if (bad) palette.error else palette.textPrimary,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (bad) palette.warnChipBg else palette.softFill)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("gm-e2e-note"))
}

@Composable
private fun E2eRow(label: String, value: String, mono: Boolean = true) {
    val palette = LocalPalette.current
    // A 150dp label column leaves a fingerprint too little room at large text
    // or on a cover screen: there the value goes under its label instead.
    if (gmPrefersStackedRows() || gmIsNarrowWindow()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 13.sp, color = palette.textSecondary)
            Text(value, fontSize = 13.sp, color = palette.textPrimary,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, fontSize = 13.sp, color = palette.textSecondary, modifier = Modifier.width(150.dp))
        Text(value, fontSize = 13.sp, color = palette.textPrimary,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(1f))
    }
}

/**
 * The fingerprint the way it reads: eight byte-groups to a line, so a wrap
 * never lands mid-pair. The old single Text broke wherever the width ran out
 * ("...C:05:" over "AA:...") and read as a rendering fault on three screens.
 */
private fun fingerprintBlock(fp: String): String =
    fp.split(":").chunked(8).joinToString("\n") { it.joinToString(":") }

@Composable
private fun E2eButton(text: String, enabled: Boolean, quiet: Boolean = false,
                      fill: Boolean = true, destructive: Boolean = false,
                      tag: String? = null, onClick: () -> Unit) {
    val palette = LocalPalette.current
    // `fill = false` exists because a fillMaxWidth child inside a Row eats the
    // whole width before a weighted sibling is measured: the recipients
    // screen's address field was being squeezed to zero and only its Check
    // button showed. `destructive` carries the error colour: an action that
    // permanently deletes a private key must not wear the same face as
    // "Lock now" (Material/HIG both reserve red for the irreversible).
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        color = when {
            destructive -> if (enabled) palette.onPrimary else palette.textTertiary
            quiet -> if (enabled) palette.textPrimary else palette.textTertiary
            else -> palette.onPrimary
        },
        textAlign = TextAlign.Center,
        modifier = (if (fill) Modifier.fillMaxWidth() else Modifier)
            .clip(RoundedCornerShape(24.dp))
            .background(if (destructive) { if (enabled) palette.error else palette.buttonDisabled }
                        else if (quiet) palette.card
                        else if (enabled) palette.primarySolid else palette.buttonDisabled)
            .border(1.dp, if (destructive) palette.error else if (quiet) palette.border else palette.primarySolid, RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            // 14dp + a 20dp line = the 48dp minimum target.
            .padding(vertical = 14.dp)
            // A non-filling button has no intrinsic horizontal padding, so a
            // short label like "Check" was clipped to a circle by the 24dp
            // corner shape. The iOS twin carries the same conditional inset.
            .padding(horizontal = if (fill) 0.dp else 22.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier))
}

@Composable
private fun E2eLede(text: String) {
    val palette = LocalPalette.current
    Text(text, fontSize = 13.sp, lineHeight = 20.sp, color = palette.textSecondary,
        modifier = Modifier.padding(top = 6.dp))
}

// ── 100 · status ─────────────────────────────────────────────────────────────

/**
 * The hub. The three states the web keeps apart are stated here in one
 * sentence each, and every card below is gated on the state it needs — a
 * screen that offered an action against a locked key would offer a failure.
 */
@Composable
fun E2eStatusScreen(vm: E2eViewModel, onBack: () -> Unit, onSetup: () -> Unit,
                    onUnlock: () -> Unit, onDetails: () -> Unit, onPublish: () -> Unit,
                    onChangePassphrase: () -> Unit, onRemove: () -> Unit,
                    onRecipients: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    var showRestore by remember { mutableStateOf(false) }
    var confirmRemoveKit by remember { mutableStateOf(false) }
    state.newRecoveryKey?.let { key -> RecoveryKeyShownOnce(key, onDone = { vm.dismissRecoveryKey() }) }
    if (showRestore) RecoveryRestoreDialog(vm, onClose = { showRestore = false })
    if (confirmRemoveKit) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRemoveKit = false },
            title = { Text(stringResource(R.string.mail_e2e_remove_recovery_key)) },
            text = { Text(stringResource(R.string.mail_e2e_if_then_lose_device),
                modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { confirmRemoveKit = false; vm.removeRecoveryKit() }) {
                    Text(stringResource(R.string.mail_common_remove), color = palette.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmRemoveKit = false }) { Text(stringResource(R.string.mail_e2e_keep)) }
            },
        )
    }
    MailScaffold(stringResource(R.string.mail_e2e_encryption), onBack, maxContentWidth = GMLayout.Form) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(8.dp))
            when (state.available) {
                null -> Text(stringResource(R.string.mail_e2e_checking_device), fontSize = 14.sp, color = palette.textTertiary,
                    modifier = Modifier.padding(vertical = 16.dp).testTag("gm-e2e-probing"))
                false -> E2eCard(stringResource(R.string.mail_e2e_encryption_not_available_device)) {
                    Row {
                        Icon(Icons.Rounded.Warning, null, tint = palette.error,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            E2eLede(state.unavailableReason ?: "")
                            E2eLede(stringResource(R.string.mail_e2e_nothing_wrong_account_mail))
                        }
                    }
                }
                else -> {
                    state.note?.let { E2eNote(it, state.noteIsBad); Spacer(Modifier.height(10.dp)) }
                    // The state card: one sentence, and the sentence is true.
                    val headline = when (state.state3) {
                        "unlocked" -> stringResource(R.string.mail_e2e_key_unlocked_session)
                        "locked" -> stringResource(R.string.mail_e2e_key_locked)
                        else -> stringResource(R.string.mail_e2e_encryption_not_set_up)
                    }
                    val lede = when (state.state3) {
                        "unlocked" -> stringResource(R.string.mail_e2e_held_memory_only_never)
                        "locked" -> stringResource(R.string.mail_e2e_locks_again_whenever_app)
                        else -> stringResource(R.string.mail_e2e_without_key_mail_cannot)
                    }
                    E2eCard(headline, tag = "gm-e2e-state-${state.state3}") {
                        Row {
                            Icon(if (state.state3 == "unlocked") Icons.Rounded.Shield
                            else Icons.Rounded.Lock,
                                null, tint = if (state.state3 == "unlocked") palette.primarySolid
                                else palette.textSecondary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) { E2eLede(lede) }
                        }
                        Spacer(Modifier.height(12.dp))
                        when (state.state3) {
                            "none" -> E2eButton(stringResource(R.string.mail_e2e_create_my_key), enabled = !state.busy,
                                tag = "gm-e2e-setup-open", onClick = onSetup)
                            "locked" -> E2eButton(stringResource(R.string.mail_e2e_unlock), enabled = !state.busy,
                                tag = "gm-e2e-unlock-open", onClick = onUnlock)
                            else -> E2eButton(stringResource(R.string.mail_e2e_lock_now), enabled = true, quiet = true,
                                tag = "gm-e2e-lock", onClick = { vm.lock() })
                        }
                    }
                    if (state.record == null && state.recoveryKitExists == true) {
                        Spacer(Modifier.height(14.dp))
                        E2eCard(stringResource(R.string.mail_e2e_restore_key_from_recovery), tag = "gm-e2e-restore-card") {
                            E2eLede(stringResource(R.string.mail_e2e_recovery_kit_account_stored))
                            Spacer(Modifier.height(12.dp))
                            E2eButton(stringResource(R.string.mail_e2e_use_my_recovery_key), enabled = !state.busy,
                                tag = "gm-e2e-restore-open", onClick = { showRestore = true })
                        }
                    }
                    if (state.record != null) {
                        Spacer(Modifier.height(14.dp))
                        E2eCard(stringResource(R.string.mail_e2e_recovery_key), tag = "gm-e2e-recovery-card") {
                            E2eLede(when (state.recoveryKitExists) {
                                null -> stringResource(R.string.mail_e2e_checking_whether_recovery_key)
                                true -> state.recoveryKitCreatedAt?.let { stringResource(R.string.mail_e2e_recovery_set_up_on, absoluteDateTimeOrRaw(it)) }
                                    ?: stringResource(R.string.mail_e2e_recovery_set_up)
                                false -> stringResource(R.string.mail_e2e_not_set_up_if)
                            })
                            Spacer(Modifier.height(12.dp))
                            when {
                                state.recoveryKitExists == null -> Unit
                                !state.unlocked -> E2eLede(if (state.recoveryKitExists == true) stringResource(R.string.mail_e2e_unlock_to_replace_recovery)
                                    else stringResource(R.string.mail_e2e_unlock_to_create_recovery))
                                else -> E2eButton(
                                    if (state.recoveryKitExists == true) stringResource(R.string.mail_e2e_replace_recovery_key) else stringResource(R.string.mail_e2e_create_recovery_key),
                                    enabled = !state.busy, quiet = state.recoveryKitExists == true,
                                    tag = "gm-e2e-recovery-create", onClick = { vm.createRecoveryKey() })
                            }
                            if (state.recoveryKitExists == true) {
                                Spacer(Modifier.height(8.dp))
                                E2eButton(stringResource(R.string.mail_e2e_remove_recovery_key_button), enabled = !state.busy, quiet = true,
                                    tag = "gm-e2e-recovery-remove", onClick = { confirmRemoveKit = true })
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        E2eRow(stringResource(R.string.mail_e2e_fingerprint), fingerprintBlock(state.fingerprint ?: "-"))
                        Spacer(Modifier.height(8.dp))
                        E2eButton(stringResource(R.string.mail_e2e_key_details), enabled = true, quiet = true,
                            tag = "gm-e2e-details-open", onClick = onDetails)
                        Spacer(Modifier.height(8.dp))
                        E2eButton(stringResource(R.string.mail_e2e_who_can_find_key), enabled = true, quiet = true,
                            tag = "gm-e2e-publish-open", onClick = onPublish)
                        if (state.unlocked) {
                            Spacer(Modifier.height(8.dp))
                            E2eButton(stringResource(R.string.mail_e2e_change_passphrase), enabled = true, quiet = true,
                                tag = "gm-e2e-change-open", onClick = onChangePassphrase)
                        }
                        Spacer(Modifier.height(8.dp))
                        E2eButton(stringResource(R.string.mail_e2e_recipient_keys), enabled = true, quiet = true,
                            tag = "gm-e2e-recipients-open", onClick = onRecipients)
                        Spacer(Modifier.height(8.dp))
                        E2eButton(stringResource(R.string.mail_e2e_remove_key_from_device), enabled = true, quiet = true,
                            tag = "gm-e2e-remove-open", onClick = onRemove)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 101 · setup ──────────────────────────────────────────────────────────────

/** Create the key. The no-recovery warning sits above the fields: a forgotten
 *  passphrase is never recoverable. The key itself can be, with a recovery key
 *  the person makes after setup (the kit the server keeps cannot be opened
 *  without it). */
@Composable
fun E2eSetupScreen(vm: E2eViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var publishOnSetup by remember { mutableStateOf(true) }
    MailScaffold(stringResource(R.string.mail_e2e_create_key), onBack, maxContentWidth = GMLayout.Form) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            state.note?.let { E2eNote(it, state.noteIsBad); Spacer(Modifier.height(10.dp)) }
            E2eLede(stringResource(R.string.mail_e2e_key_made_here_device))
            Text(stringResource(R.string.mail_e2e_there_no_way_recover), fontSize = 13.sp,
                fontWeight = FontWeight.Medium, color = LocalPalette.current.textPrimary,
                modifier = Modifier.padding(top = 10.dp))
            E2eLede(stringResource(R.string.mail_e2e_not_us_not_after))
            Spacer(Modifier.height(14.dp))
            AppTextField(stringResource(R.string.mail_e2e_passphrase), passphrase, { passphrase = it }, password = true)
            AppTextField(stringResource(R.string.mail_e2e_repeat_passphrase), confirm, { confirm = it }, password = true,
                modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = androidx.compose.ui.semantics.Role.Checkbox) { publishOnSetup = !publishOnSetup },
                verticalAlignment = Alignment.Top) {
                Box(Modifier.size(20.dp).clip(CircleShape)
                    .border(1.5.dp, if (publishOnSetup) LocalPalette.current.primarySolid
                    else LocalPalette.current.border, CircleShape)
                    .background(if (publishOnSetup) LocalPalette.current.primarySolid
                    else LocalPalette.current.card),
                    contentAlignment = Alignment.Center) {
                    if (publishOnSetup) Text("✓", color = LocalPalette.current.onPrimary, fontSize = 12.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.mail_e2e_publish_public_half_other),
                        fontSize = 13.sp, color = LocalPalette.current.textPrimary)
                    Text(stringResource(R.string.mail_e2e_shares_public_key_private), fontSize = 12.sp,
                        color = LocalPalette.current.textTertiary)
                }
            }
            Spacer(Modifier.height(16.dp))
            E2eButton(if (state.busy) stringResource(R.string.mail_e2e_creating) else stringResource(R.string.mail_e2e_create_my_key),
                enabled = !state.busy, tag = "gm-e2e-setup-submit",
                onClick = { vm.setup(passphrase, confirm, publishOnSetup) })
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 102 · unlock ─────────────────────────────────────────────────────────────

@Composable
fun E2eUnlockScreen(vm: E2eViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var phrase by remember { mutableStateOf("") }
    MailScaffold(stringResource(R.string.mail_e2e_unlock_key), onBack, maxContentWidth = GMLayout.Form) {
        // Scrolls, so Unlock stays reachable above the keyboard in a short window.
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            state.note?.let { E2eNote(it, state.noteIsBad); Spacer(Modifier.height(10.dp)) }
            E2eLede(stringResource(R.string.mail_e2e_passphrase_decrypts_key_device))
            Spacer(Modifier.height(14.dp))
            AppTextField(stringResource(R.string.mail_e2e_passphrase), phrase, { phrase = it }, password = true)
            Spacer(Modifier.height(16.dp))
            E2eButton(if (state.busy) stringResource(R.string.mail_e2e_unlocking) else stringResource(R.string.mail_e2e_unlock),
                enabled = !state.busy && phrase.isNotEmpty(), tag = "gm-e2e-unlock-submit",
                onClick = { vm.unlock(phrase); phrase = "" })
            // Biometric-bound unlock (security/BiometricKeyWrap.kt): offered only
            // when app lock is on and a bound copy exists. A passphrase unlock
            // while app lock is on creates that copy (the safe re-wrap).
            val unlockContext = androidx.compose.ui.platform.LocalContext.current
            val unlockActivity = remember(unlockContext) {
                generateSequence(unlockContext) { (it as? android.content.ContextWrapper)?.baseContext }
                    .filterIsInstance<androidx.fragment.app.FragmentActivity>().firstOrNull()
            }
            LaunchedEffect(state.unlocked) {
                if (state.unlocked) unlockActivity?.let { vm.bindAfterPassphraseUnlock(it) }
            }
            if (!state.unlocked && unlockActivity != null && vm.canBiometricUnlock()) {
                Spacer(Modifier.height(10.dp))
                E2eButton(androidx.compose.ui.res.stringResource(com.grapsee.grapmail.R.string.core_e2e_bio_unlock_button),
                    enabled = !state.busy, tag = "gm-e2e-unlock-biometric",
                    onClick = { vm.unlockWithBiometrics(unlockActivity) })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 103 · key details ────────────────────────────────────────────────────────

@Composable
fun E2eDetailsScreen(vm: E2eViewModel, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    MailScaffold(stringResource(R.string.mail_e2e_devices_key), onBack, maxContentWidth = GMLayout.Form) {
        // Top-anchored like every other detail screen (subscriptions, the
        // connection page): Arrangement.Center stranded the whole record in
        // the screen's middle with a dead band under the toolbar -- frame
        // 104's photograph. Centering belongs to status cards, not tables.
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        ) {
            E2eLede(stringResource(R.string.mail_e2e_read_fingerprint_aloud_send))
            Spacer(Modifier.height(10.dp))
            E2eRow(stringResource(R.string.mail_e2e_fingerprint), fingerprintBlock(state.fingerprint ?: "-"))
            E2eRow(stringResource(R.string.mail_e2e_created), state.record?.createdAt?.let { absoluteDateTimeOrRaw(it) } ?: "-")
            E2eRow(stringResource(R.string.mail_e2e_signing_key), if (state.record?.signing != null)
                stringResource(R.string.mail_e2e_yes_recipients_can_verify) else stringResource(R.string.mail_e2e_none), mono = false)
            Spacer(Modifier.height(12.dp))
            Text(vm.openpgpExportNote(), fontSize = 12.sp, lineHeight = 18.sp,
                color = palette.textTertiary)
        }
    }
}

// ── 104 · publishing ─────────────────────────────────────────────────────────

@Composable
fun E2ePublishScreen(vm: E2eViewModel, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadPublished() }
    MailScaffold(stringResource(R.string.mail_e2e_who_can_find_key), onBack, maxContentWidth = GMLayout.Form) {
        com.grapsee.grapmail.ui.components.OfflineBanner()
        // First read and a failed read use the shared states; the failure
        // keeps the server's own sentence and adds the way back.
        if (!state.publishedLoaded) {
            com.grapsee.grapmail.ui.components.DetailSkeleton(
                Modifier.testTag("gm-e2e-publish-checking"),
                label = stringResource(R.string.mail_e2e_checking))
            return@MailScaffold
        }
        if (state.publishedError != null && state.published == null) {
            com.grapsee.grapmail.ui.components.ErrorState(onRetry = { vm.loadPublished() },
                message = state.publishedError)
            return@MailScaffold
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            state.note?.let { E2eNote(it, state.noteIsBad); Spacer(Modifier.height(10.dp)) }
            if (!state.publishedLoaded) {
                Text(stringResource(R.string.mail_e2e_checking), fontSize = 13.sp, color = palette.textTertiary,
                    modifier = Modifier.padding(vertical = 10.dp).testTag("gm-e2e-publish-checking"))
            } else if (state.publishedError != null) {
                // The failed read says so. "Nothing is published" would be a
                // claim about the server that the server never made.
                E2eNote(state.publishedError!!, true)
            } else {
                val p = state.published
                if (p != null) {
                    E2eLede(stringResource(R.string.mail_e2e_public_key_published_anyone))
                    Spacer(Modifier.height(8.dp))
                    E2eRow(stringResource(R.string.mail_e2e_published_fingerprint), fingerprintBlock(p.encryption?.fingerprint ?: "-"))
                    E2eRow(stringResource(R.string.mail_e2e_updated), (p.updatedAt ?: p.publishedAt)?.let { absoluteDateTimeOrRaw(it) } ?: "-")
                    val mismatch = state.fingerprint != null && p.encryption?.fingerprint != null &&
                        p.encryption?.fingerprint != state.fingerprint
                    if (mismatch) {
                        Spacer(Modifier.height(10.dp))
                        E2eNote(stringResource(R.string.mail_e2e_key_published_account_not), true)
                    }
                    Spacer(Modifier.height(14.dp))
                    E2eButton(stringResource(R.string.mail_e2e_unpublish), enabled = !state.busy, quiet = true,
                        tag = "gm-e2e-unpublish", onClick = { vm.unpublish() })
                } else {
                    E2eLede(stringResource(R.string.mail_e2e_nothing_published_account_senders))
                    Spacer(Modifier.height(14.dp))
                    E2eButton(if (state.busy) stringResource(R.string.mail_e2e_publishing) else stringResource(R.string.mail_e2e_publish_my_public_key),
                        enabled = !state.busy && state.record != null,
                        tag = "gm-e2e-publish-submit", onClick = { vm.publish() })
                    if (state.record == null) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.mail_e2e_key_device_comes_first),
                            fontSize = 12.sp, color = palette.textTertiary)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 105 · passphrase change ──────────────────────────────────────────────────

@Composable
fun E2ePassphraseScreen(vm: E2eViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var oldPhrase by remember { mutableStateOf("") }
    var newPhrase by remember { mutableStateOf("") }
    MailScaffold(stringResource(R.string.mail_e2e_change_your_passphrase), onBack, maxContentWidth = GMLayout.Form) {
        // Scrolls, so the button stays reachable above the keyboard in a short window.
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            state.note?.let { E2eNote(it, state.noteIsBad); Spacer(Modifier.height(10.dp)) }
            E2eLede(stringResource(R.string.mail_e2e_re_locks_same_key))
            Spacer(Modifier.height(14.dp))
            AppTextField(stringResource(R.string.mail_e2e_current_passphrase), oldPhrase, { oldPhrase = it }, password = true)
            AppTextField(stringResource(R.string.mail_e2e_new_passphrase), newPhrase, { newPhrase = it }, password = true,
                modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            E2eButton(if (state.busy) stringResource(R.string.mail_e2e_changing) else stringResource(R.string.mail_e2e_change_passphrase),
                enabled = !state.busy && state.unlocked, tag = "gm-e2e-change-submit",
                onClick = { vm.changePassphrase(oldPhrase, newPhrase); oldPhrase = ""; newPhrase = "" })
            if (!state.unlocked) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.mail_e2e_unlocking_key_comes_first),
                    fontSize = 12.sp, color = LocalPalette.current.textTertiary)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 106 · removal ────────────────────────────────────────────────────────────

@Composable
fun E2eRemoveScreen(vm: E2eViewModel, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    MailScaffold(stringResource(R.string.mail_e2e_remove_key_from_device), onBack, maxContentWidth = GMLayout.Form) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        ) {
            state.note?.let { E2eNote(it, state.noteIsBad); Spacer(Modifier.height(10.dp)) }
            // The warning is the consequence, stated before the tap, in the
            // web's own words. Not a scare line: permanent and everywhere.
            E2eLede(stringResource(R.string.mail_e2e_deletes_private_key_here))
            Spacer(Modifier.height(16.dp))
            if (!confirmRemove) {
                E2eButton(stringResource(R.string.mail_e2e_remove_from_device), enabled = state.record != null, quiet = true,
                    tag = "gm-e2e-remove-start", onClick = { confirmRemove = true })
            } else {
                E2eButton(if (state.busy) stringResource(R.string.mail_e2e_removing) else stringResource(R.string.mail_e2e_yes_delete_key_from),
                    enabled = !state.busy, destructive = true, tag = "gm-e2e-remove-confirm",
                    onClick = { vm.removeKey(); confirmRemove = false; onBack() })
                Spacer(Modifier.height(8.dp))
                E2eButton(stringResource(R.string.mail_e2e_keep), enabled = true, quiet = true,
                    tag = "gm-e2e-remove-keep", onClick = { confirmRemove = false })
            }
        }
    }
}

// ── 107 · recipient keys ─────────────────────────────────────────────────────

/**
 * The compose-time verification: what the directory publishes for these
 * addresses. Each entry's reason is the server's own word — "this person has
 * not set up encryption" and "this is not a GrapMail address" lead to
 * different actions, and the list says which is which instead of a blanket
 * "unavailable".
 */
@Composable
fun E2eRecipientsScreen(vm: E2eViewModel, onBack: () -> Unit, onCompose: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val stacked = gmPrefersStackedRows()
    MailScaffold(stringResource(R.string.mail_e2e_recipient_keys), onBack, maxContentWidth = GMLayout.Form) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            E2eLede(stringResource(R.string.mail_e2e_check_people_about_write))
            Spacer(Modifier.height(8.dp))
            // At large text the field gets the full width and Check goes under it.
            if (stacked) {
                AppTextField("", draft, { draft = it }, hint = stringResource(R.string.mail_e2e_someone_example_com))
                Spacer(Modifier.height(8.dp))
                E2eButton(stringResource(R.string.mail_e2e_check), enabled = !state.busy && draft.isNotBlank(),
                    tag = "gm-e2e-lookup-submit",
                    onClick = { vm.lookup(listOf(draft.trim())); draft = "" })
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    AppTextField("", draft, { draft = it }, hint = stringResource(R.string.mail_e2e_someone_example_com))
                }
                Spacer(Modifier.width(10.dp))
                E2eButton(stringResource(R.string.mail_e2e_check), enabled = !state.busy && draft.isNotBlank(), fill = false,
                    tag = "gm-e2e-lookup-submit",
                    onClick = { vm.lookup(listOf(draft.trim())); draft = "" })
            }
            state.note?.let { Spacer(Modifier.height(8.dp)); E2eNote(it, state.noteIsBad) }
            if (state.lookup.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                state.lookup.forEach { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)
                        .testTag("gm-e2e-lookup-row")) {
                        val verdict = if (e.status == "found") stringResource(R.string.mail_e2e_found) else stringResource(R.string.mail_e2e_cannot_encrypt)
                        if (stacked) {
                            Text(e.address, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.textPrimary)
                            Text(verdict, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = if (e.status == "found") palette.success else palette.warnInk)
                        } else Row {
                            Text(e.address, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                color = palette.textPrimary, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Text(if (e.status == "found") stringResource(R.string.mail_e2e_found) else stringResource(R.string.mail_e2e_cannot_encrypt),
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = if (e.status == "found") palette.success else palette.warnInk)
                        }
                        if (e.status == "found" && e.signing != null) {
                            Text(stringResource(R.string.mail_e2e_signing_fingerprint, fingerprintBlock(e.signing?.fingerprint ?: "-")),
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = palette.textSecondary)
                        } else if (e.status != "found") {
                            Text(when (e.why) {
                                com.grapsee.grapmail.data.MailE2eBlockReason.NotAnAddress ->
                                    stringResource(R.string.mail_e2e_not_address_directory_can)
                                com.grapsee.grapmail.data.MailE2eBlockReason.RemoteDomain ->
                                    stringResource(R.string.mail_e2e_not_grapmail_address_directory)
                                com.grapsee.grapmail.data.MailE2eBlockReason.NoSuchAccount ->
                                    stringResource(R.string.mail_e2e_there_no_grapmail_account)
                                com.grapsee.grapmail.data.MailE2eBlockReason.NoKeyPublished ->
                                    stringResource(R.string.mail_e2e_person_has_not_published)
                                com.grapsee.grapmail.data.MailE2eBlockReason.Unknown ->
                                    e.reason ?: stringResource(R.string.mail_e2e_directory_gave_no_reason)
                            }, fontSize = 12.sp, color = palette.textTertiary)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                E2eButton(stringResource(R.string.mail_e2e_back_compose), enabled = true, quiet = true,
                    tag = "gm-e2e-lookup-compose", onClick = onCompose)
            } else if (state.lookupFor.isNotEmpty() && !state.busy) {
                Text(stringResource(R.string.mail_e2e_directory_answered_nothing_check), fontSize = 13.sp,
                    color = palette.textTertiary, modifier = Modifier.padding(vertical = 10.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── recovery key ─────────────────────────────────────────────────────────────

/** The new recovery key, shown once. It is kept nowhere on this device. */
@Composable
private fun RecoveryKeyShownOnce(key: String, onDone: () -> Unit) {
    val palette = LocalPalette.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.mail_e2e_your_recovery_key)) },
        text = {
            // Scrolls, so the key and both buttons fit a landscape window.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.mail_e2e_write_down_keep_password),
                    fontSize = 13.sp, lineHeight = 19.sp, color = palette.textSecondary)
                Spacer(Modifier.height(14.dp))
                Text(key, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary, modifier = Modifier.testTag("gm-e2e-recovery-key"))
                if (copied) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.mail_e2e_copied_paste_somewhere_safe),
                        fontSize = 12.sp, color = palette.textSecondary)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDone, modifier = Modifier.testTag("gm-e2e-recovery-saved")) {
                Text(stringResource(R.string.mail_e2e_i_have_saved))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(key))
                copied = true
            }) { Text(stringResource(R.string.mail_e2e_copy)) }
        },
    )
}

/** Restore the key from the stored kit: the recovery key, then a new passphrase for this device. */
@Composable
private fun RecoveryRestoreDialog(vm: E2eViewModel, onClose: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var recoveryKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    LaunchedEffect(state.record) { if (state.record != null) onClose() }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MailScaffold(stringResource(R.string.mail_e2e_restore_key), onClose, maxContentWidth = GMLayout.Form) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                state.note?.let { E2eNote(it, state.noteIsBad); Spacer(Modifier.height(10.dp)) }
                E2eLede(stringResource(R.string.mail_e2e_enter_recovery_key_saved))
                Spacer(Modifier.height(12.dp))
                AppTextField(stringResource(R.string.mail_e2e_recovery_key), recoveryKey, { recoveryKey = it })
                Spacer(Modifier.height(14.dp))
                E2eLede(stringResource(R.string.mail_e2e_then_choose_passphrase_device))
                Spacer(Modifier.height(8.dp))
                AppTextField(stringResource(R.string.mail_e2e_new_passphrase), passphrase, { passphrase = it }, password = true)
                AppTextField(stringResource(R.string.mail_e2e_repeat_passphrase), confirm, { confirm = it }, password = true,
                    modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(16.dp))
                E2eButton(if (state.busy) stringResource(R.string.mail_e2e_restoring) else stringResource(R.string.mail_e2e_restore_my_key),
                    enabled = !state.busy && recoveryKey.isNotBlank() && passphrase.isNotEmpty(),
                    tag = "gm-e2e-restore-submit",
                    onClick = { vm.restoreWithRecoveryKey(recoveryKey, passphrase, confirm) })
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
