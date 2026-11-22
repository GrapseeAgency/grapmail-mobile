package com.grapsee.grapmail.ui.screens.security

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.PasskeyDto
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.findActivity
import com.grapsee.grapmail.security.DeviceKeyManager
import com.grapsee.grapmail.security.PasskeyManager
import com.grapsee.grapmail.ui.components.AccountLink
import com.grapsee.grapmail.ui.components.AccountScreen
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.InfoNoteCard
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The account's passkeys — WebAuthn credentials created here (signed in, so
 * the ceremony runs under the bearer) and used at sign-in, where the
 * authenticator proves the account without any password.
 *
 * ── ACTIVITY REQUIREMENT ───────────────────────────────────────────────────
 * Both Credential Manager ceremonies need a FragmentActivity, exactly like
 * BiometricPrompt. MainActivity extends FragmentActivity, so every real
 * entry into this screen has one; the guard below exists so a future host
 * that downgrades the base class draws "Passkeys need an app update" instead
 * of crashing mid-ceremony. The same guard sits on the sign-in screen's
 * "Use a passkey" action.
 *
 * ── WHAT THE CEREMONY NEEDS ────────────────────────────────────────────────
 * The server must publish a Digital Asset Links statement on the rp id (the
 * deployment origin hostname) naming this app and its signing certificate —
 * see [PasskeyManager]. Without it the platform refuses the ceremony and the
 * failure shows here as the message it carries.
 */
@Composable
fun PasskeysScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var passkeys by remember { mutableStateOf<List<PasskeyDto>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<PasskeyDto?>(null) }
    // The host activity, resolved once off the render pass: null means this
    // app build cannot run the ceremonies at all, which the list is replaced
    // with an honest note about.
    val activity = remember { context.findActivity() as? FragmentActivity }

    suspend fun load() {
        val session = SessionStore.snapshot(context) ?: run {
            failed = true; loading = false
            return
        }
        loading = true
        failed = false
        try {
            val resp = Api.listPasskeys(session.token)
            if (!SessionStore.isCurrent(context, session.id)) return
            passkeys = resp.passkeys
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (SessionStore.isCurrent(context, session.id)) failed = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    fun addPasskey() {
        val host = activity
        if (host == null) {
            error = "Passkeys need an app update."
            return
        }
        // The ceremony cannot run twice at once: a second sheet over the
        // first would cancel the first and look like a refusal.
        if (creating) return
        creating = true
        error = null
        notice = null
        scope.launch {
            try {
                val result = PasskeyManager.createPasskey(
                    host,
                    "GrapMail on " + DeviceKeyManager.deviceDisplayName(),
                )
                when (result) {
                    is PasskeyManager.RegistrationResult.Completed -> {
                        notice = "Passkey added. Use it the next time you sign in."
                        load()
                    }
                    PasskeyManager.RegistrationResult.NoCredential ->
                        error = "No passkey was created on this device. Make sure a screen lock is set up."
                    PasskeyManager.RegistrationResult.Cancelled -> {
                        // The person closed the sheet; nothing to report.
                    }
                    is PasskeyManager.RegistrationResult.Failed ->
                        error = result.message ?: "The passkey could not be added. Try again."
                }
            } finally {
                creating = false
            }
        }
    }

    fun delete(passkey: PasskeyDto) {
        scope.launch {
            busyId = passkey.id
            try {
                val session = SessionStore.snapshot(context) ?: throw IllegalStateException("Sign in again.")
                Api.deletePasskey(session.token, passkey.id)
                if (!SessionStore.isCurrent(context, session.id)) return@launch
                passkeys = passkeys?.filterNot { it.id == passkey.id }
                deleteTarget = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                busyId = null
            }
        }
    }

    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_sessions",
        illustrationWidth = 150,
        title = "Passkeys",
        body = "Sign in with a biometric credential instead of a password. A passkey stays on the device that created it.",
        footer = {
            Column {
                GradientButton(
                    if (creating) "Creating…" else "Add passkey",
                    onClick = { addPasskey() },
                    busy = creating,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink("Back to Account Security", onBack)
                }
            }
        },
    ) {
        if (activity == null) {
            Text(
                "Passkeys need an app update.",
                fontSize = 13.sp,
                color = palette.textSecondary,
            )
        } else {
            LoadStateBlock(loading = loading, failed = failed, onRetry = { scope.launch { load() } }) {
                val rows = passkeys ?: return@LoadStateBlock
                if (rows.isEmpty()) {
                    Text(
                        "No passkeys yet. Add one and you can sign in without your password.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = palette.textSecondary,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        rows.forEach { passkey ->
                            PasskeyCard(
                                passkey = passkey,
                                busy = busyId == passkey.id,
                                onDelete = { deleteTarget = passkey },
                            )
                        }
                    }
                }
            }
        }
        if (notice != null) {
            Spacer(Modifier.height(8.dp))
            Text(notice ?: "", fontSize = 12.sp, color = palette.success)
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error ?: "", fontSize = 12.sp, color = palette.error)
        }
        Spacer(Modifier.height(12.dp))
        InfoNoteCard(
            title = "What a passkey is",
            body = "A passkey is a credential this device holds in its hardware and proves with your fingerprint or face. Removing it here stops it signing in to this account — the copy on this device itself is removed through the device's own settings.",
        )
    }

    deleteTarget?.let { passkey ->
        AlertDialog(
            onDismissRequest = { if (busyId == null) deleteTarget = null },
            title = { Text("Remove passkey?") },
            text = {
                Text(
                    "“" + passkey.name.ifBlank { "This passkey" } + "” will stop signing you in to this account.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = busyId == null,
                    onClick = { delete(passkey) },
                ) { Text("Remove", color = palette.error) }
            },
            dismissButton = {
                TextButton(enabled = busyId == null, onClick = { deleteTarget = null }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun PasskeyCard(
    passkey: PasskeyDto,
    busy: Boolean,
    onDelete: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, palette.border, RoundedCornerShape(16.dp))
            .background(palette.card)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                passkey.name.ifBlank { "Passkey" },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            listOfNotNull(
                passkey.createdAt?.takeIf(String::isNotBlank)?.let { "Added ${absoluteDateTime(it)}" },
                passkey.lastUsedAt?.takeIf(String::isNotBlank)?.let { "Last used ${relativeTime(it)}" },
            ).joinToString(" · ").ifBlank { "This credential signs you in without a password." },
            fontSize = 11.sp,
            color = palette.textTertiary,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !busy, onClick = onDelete)
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (busy) "Removing…" else "Remove passkey",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (busy) palette.textTertiary else palette.error,
            )
        }
    }
}
