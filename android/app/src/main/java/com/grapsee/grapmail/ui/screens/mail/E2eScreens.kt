package com.grapsee.grapmail.ui.screens.mail

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
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp)
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
    MailScaffold("Encryption", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(8.dp))
            when (state.available) {
                null -> Text("Checking this device…", fontSize = 14.sp, color = palette.textTertiary,
                    modifier = Modifier.padding(vertical = 16.dp).testTag("gm-e2e-probing"))
                false -> E2eCard("Encryption is not available on this device") {
                    Row {
                        Icon(Icons.Rounded.Warning, null, tint = palette.error,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            E2eLede(state.unavailableReason ?: "")
                            E2eLede("Nothing is wrong with your account. Your mail is unaffected; " +
                                "it simply cannot be encrypted from here.")
                        }
                    }
                }
                else -> {
                    state.note?.let { E2eNote(it, state.noteIsBad); Spacer(Modifier.height(10.dp)) }
                    // The state card: one sentence, and the sentence is true.
                    val headline = when (state.state3) {
                        "unlocked" -> "Your key is unlocked for this session"
                        "locked" -> "Your key is locked"
                        else -> "Encryption is not set up on this device"
                    }
                    val lede = when (state.state3) {
                        "unlocked" -> "It is held in memory only and is never written down. " +
                            "Closing the app locks it."
                        "locked" -> "It locks again whenever the app is closed. Unlock it to send " +
                            "or read encrypted mail."
                        else -> "Without a key, mail to you cannot be encrypted and encrypted mail " +
                            "cannot be read here."
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
                            "none" -> E2eButton("Create my key", enabled = !state.busy,
                                tag = "gm-e2e-setup-open", onClick = onSetup)
                            "locked" -> E2eButton("Unlock", enabled = !state.busy,
                                tag = "gm-e2e-unlock-open", onClick = onUnlock)
                            else -> E2eButton("Lock now", enabled = true, quiet = true,
                                tag = "gm-e2e-lock", onClick = { vm.lock() })
                        }
                    }
                    if (state.record != null) {
                        Spacer(Modifier.height(14.dp))
                        E2eRow("Fingerprint", fingerprintBlock(state.fingerprint ?: "-"))
                        Spacer(Modifier.height(8.dp))
                        E2eButton("Key details", enabled = true, quiet = true,
                            tag = "gm-e2e-details-open", onClick = onDetails)
                        Spacer(Modifier.height(8.dp))
                        E2eButton("Who can find your key", enabled = true, quiet = true,
                            tag = "gm-e2e-publish-open", onClick = onPublish)
                        if (state.unlocked) {
                            Spacer(Modifier.height(8.dp))
                            E2eButton("Change passphrase", enabled = true, quiet = true,
                                tag = "gm-e2e-change-open", onClick = onChangePassphrase)
                        }
                        Spacer(Modifier.height(8.dp))
                        E2eButton("Recipient keys", enabled = true, quiet = true,
                            tag = "gm-e2e-recipients-open", onClick = onRecipients)
                        Spacer(Modifier.height(8.dp))
                        E2eButton("Remove the key from this device", enabled = true, quiet = true,
                            tag = "gm-e2e-remove-open", onClick = onRemove)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 101 · setup ──────────────────────────────────────────────────────────────

/** Create the key. The no-recovery warning sits above the fields, in the
 *  web's own words: a recovery route would mean a second copy of the key held
 *  somewhere else, which is the one thing this feature exists to prevent. */
@Composable
fun E2eSetupScreen(vm: E2eViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var publishOnSetup by remember { mutableStateOf(true) }
    MailScaffold("Create your key", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            state.note?.let { E2eNote(it, state.noteIsBad); Spacer(Modifier.height(10.dp)) }
            E2eLede("The key is made here, on this device. The private half never leaves it: that is " +
                "what makes this end-to-end rather than a promise. It is stored encrypted under a " +
                "passphrase you choose.")
            Text("There is no way to recover a forgotten passphrase.", fontSize = 13.sp,
                fontWeight = FontWeight.Medium, color = LocalPalette.current.textPrimary,
                modifier = Modifier.padding(top = 10.dp))
            E2eLede("Not by us, and not by you. A recovery route would mean a second copy of your " +
                "key held somewhere else, which is the one thing this feature exists to prevent.")
            Spacer(Modifier.height(14.dp))
            AppTextField("Passphrase", passphrase, { passphrase = it }, password = true)
            AppTextField("Repeat passphrase", confirm, { confirm = it }, password = true,
                modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 12.dp).clickable { publishOnSetup = !publishOnSetup },
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
                    Text("Publish the public half, so other people can send me encrypted mail.",
                        fontSize = 13.sp, color = LocalPalette.current.textPrimary)
                    Text("This shares your public key. Your private key is not shared, and never " +
                        "leaves this device.", fontSize = 12.sp,
                        color = LocalPalette.current.textTertiary)
                }
            }
            Spacer(Modifier.height(16.dp))
            E2eButton(if (state.busy) "Creating…" else "Create my key",
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
    MailScaffold("Unlock your key", onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            state.note?.let { E2eNote(it, state.noteIsBad); Spacer(Modifier.height(10.dp)) }
            E2eLede("Your passphrase decrypts the key on this device. It is never sent anywhere.")
            Spacer(Modifier.height(14.dp))
            AppTextField("Passphrase", phrase, { phrase = it }, password = true)
            Spacer(Modifier.height(16.dp))
            E2eButton(if (state.busy) "Unlocking…" else "Unlock",
                enabled = !state.busy && phrase.isNotEmpty(), tag = "gm-e2e-unlock-submit",
                onClick = { vm.unlock(phrase); phrase = "" })
        }
    }
}

// ── 103 · key details ────────────────────────────────────────────────────────

@Composable
fun E2eDetailsScreen(vm: E2eViewModel, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    MailScaffold("This device's key", onBack) {
        // Top-anchored like every other detail screen (subscriptions, the
        // connection page): Arrangement.Center stranded the whole record in
        // the screen's middle with a dead band under the toolbar -- frame
        // 104's photograph. Centering belongs to status cards, not tables.
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        ) {
            E2eLede("Read the fingerprint aloud, or send it through another channel, and have the " +
                "other person confirm it. If the two do not match, the key you have is not theirs.")
            Spacer(Modifier.height(10.dp))
            E2eRow("Fingerprint", fingerprintBlock(state.fingerprint ?: "-"))
            E2eRow("Created", state.record?.createdAt?.let { absoluteDateTimeOrRaw(it) } ?: "-")
            E2eRow("Signing key", if (state.record?.signing != null)
                "Yes: recipients can verify it was you" else "None", mono = false)
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
    MailScaffold("Who can find your key", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            state.note?.let { E2eNote(it, state.noteIsBad); Spacer(Modifier.height(10.dp)) }
            if (!state.publishedLoaded) {
                Text("Checking…", fontSize = 13.sp, color = palette.textTertiary,
                    modifier = Modifier.padding(vertical = 10.dp).testTag("gm-e2e-publish-checking"))
            } else if (state.publishedError != null) {
                // The failed read says so. "Nothing is published" would be a
                // claim about the server that the server never made.
                E2eNote(state.publishedError!!, true)
            } else {
                val p = state.published
                if (p != null) {
                    E2eLede("Your public key is published, so anyone sending you mail can encrypt to " +
                        "it. Your private key is not published and never will be.")
                    Spacer(Modifier.height(8.dp))
                    E2eRow("Published fingerprint", fingerprintBlock(p.encryption?.fingerprint ?: "-"))
                    E2eRow("Updated", (p.updatedAt ?: p.publishedAt)?.let { absoluteDateTimeOrRaw(it) } ?: "-")
                    val mismatch = state.fingerprint != null && p.encryption?.fingerprint != null &&
                        p.encryption?.fingerprint != state.fingerprint
                    if (mismatch) {
                        Spacer(Modifier.height(10.dp))
                        E2eNote("The key published for your account is NOT the key on this device. " +
                            "Anyone encrypting to you right now is encrypting to a key this device " +
                            "cannot open. If you did not expect this — for example if you set " +
                            "encryption up on another device — publish from this one, or restore " +
                            "that device's key.", true)
                    }
                    Spacer(Modifier.height(14.dp))
                    E2eButton("Unpublish", enabled = !state.busy, quiet = true,
                        tag = "gm-e2e-unpublish", onClick = { vm.unpublish() })
                } else {
                    E2eLede("Nothing is published for this account, so senders cannot encrypt to " +
                        "you yet. Publishing shares only the public half.")
                    Spacer(Modifier.height(14.dp))
                    E2eButton(if (state.busy) "Publishing…" else "Publish my public key",
                        enabled = !state.busy && state.record != null,
                        tag = "gm-e2e-publish-submit", onClick = { vm.publish() })
                    if (state.record == null) {
                        Spacer(Modifier.height(8.dp))
                        Text("A key on this device comes first — create one from the Encryption screen.",
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
    MailScaffold("Change your passphrase", onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            state.note?.let { E2eNote(it, state.noteIsBad); Spacer(Modifier.height(10.dp)) }
            E2eLede("This re-locks the same key under a new passphrase. It does not change the key, " +
                "so messages already encrypted to you stay readable.")
            Spacer(Modifier.height(14.dp))
            AppTextField("Current passphrase", oldPhrase, { oldPhrase = it }, password = true)
            AppTextField("New passphrase", newPhrase, { newPhrase = it }, password = true,
                modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            E2eButton(if (state.busy) "Changing…" else "Change passphrase",
                enabled = !state.busy && state.unlocked, tag = "gm-e2e-change-submit",
                onClick = { vm.changePassphrase(oldPhrase, newPhrase); oldPhrase = ""; newPhrase = "" })
            if (!state.unlocked) {
                Spacer(Modifier.height(8.dp))
                Text("Unlocking the key comes first — the re-wrap needs the old passphrase to open it.",
                    fontSize = 12.sp, color = LocalPalette.current.textTertiary)
            }
        }
    }
}

// ── 106 · removal ────────────────────────────────────────────────────────────

@Composable
fun E2eRemoveScreen(vm: E2eViewModel, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    MailScaffold("Remove the key from this device", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        ) {
            state.note?.let { E2eNote(it, state.noteIsBad); Spacer(Modifier.height(10.dp)) }
            // The warning is the consequence, stated before the tap, in the
            // web's own words. Not a scare line: permanent and everywhere.
            E2eLede("This deletes the private key here. Every message already encrypted to it " +
                "becomes unreadable — here and anywhere else, permanently. Do this only if you " +
                "have the key somewhere else, or you accept losing access to that mail.")
            Spacer(Modifier.height(16.dp))
            if (!confirmRemove) {
                E2eButton("Remove from this device", enabled = state.record != null, quiet = true,
                    tag = "gm-e2e-remove-start", onClick = { confirmRemove = true })
            } else {
                E2eButton(if (state.busy) "Removing…" else "Yes, delete the key from this device",
                    enabled = !state.busy, destructive = true, tag = "gm-e2e-remove-confirm",
                    onClick = { vm.removeKey(); confirmRemove = false; onBack() })
                Spacer(Modifier.height(8.dp))
                E2eButton("Keep it", enabled = true, quiet = true,
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
    MailScaffold("Recipient keys", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            E2eLede("Check that the people you are about to write to have published keys. A found " +
                "entry shows the fingerprint to compare; an unavailable one says why, in the " +
                "directory's own words.")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    AppTextField("", draft, { draft = it }, hint = "someone@example.com")
                }
                Spacer(Modifier.width(10.dp))
                E2eButton("Check", enabled = !state.busy && draft.isNotBlank(), fill = false,
                    tag = "gm-e2e-lookup-submit",
                    onClick = { vm.lookup(listOf(draft.trim())); draft = "" })
            }
            state.note?.let { Spacer(Modifier.height(8.dp)); E2eNote(it, state.noteIsBad) }
            if (state.lookup.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                state.lookup.forEach { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)
                        .testTag("gm-e2e-lookup-row")) {
                        Row {
                            Text(e.address, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                color = palette.textPrimary, modifier = Modifier.weight(1f))
                            Text(if (e.status == "found") "Found" else "Cannot encrypt",
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = if (e.status == "found") palette.success else palette.warnInk)
                        }
                        if (e.status == "found" && e.signing != null) {
                            Text("Signing fingerprint ${fingerprintBlock(e.signing?.fingerprint ?: "-")}",
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = palette.textSecondary)
                        } else if (e.status != "found") {
                            Text(when (e.why) {
                                com.grapsee.grapmail.data.MailE2eBlockReason.NotAnAddress ->
                                    "That is not an address the directory can look up."
                                com.grapsee.grapmail.data.MailE2eBlockReason.RemoteDomain ->
                                    "Not a GrapMail address — the directory only knows GrapMail keys."
                                com.grapsee.grapmail.data.MailE2eBlockReason.NoSuchAccount ->
                                    "There is no GrapMail account at that address."
                                com.grapsee.grapmail.data.MailE2eBlockReason.NoKeyPublished ->
                                    "This person has not published an encryption key yet."
                                com.grapsee.grapmail.data.MailE2eBlockReason.Unknown ->
                                    e.reason ?: "The directory gave no reason."
                            }, fontSize = 12.sp, color = palette.textTertiary)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                E2eButton("Back to compose", enabled = true, quiet = true,
                    tag = "gm-e2e-lookup-compose", onClick = onCompose)
            } else if (state.lookupFor.isNotEmpty() && !state.busy) {
                Text("The directory answered nothing for that check.", fontSize = 13.sp,
                    color = palette.textTertiary, modifier = Modifier.padding(vertical = 10.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
