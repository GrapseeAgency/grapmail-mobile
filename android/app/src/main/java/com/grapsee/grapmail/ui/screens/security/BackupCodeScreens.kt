package com.grapsee.grapmail.ui.screens.security

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.ui.components.AccountLink
import com.grapsee.grapmail.ui.components.AccountScreen
import com.grapsee.grapmail.ui.components.FooterNote
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.InfoCard
import com.grapsee.grapmail.ui.components.TermsCheckbox
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * The ten codes, between generation and the screen that shows them.
 *
 * They are deliberately not a navigation argument: a route string is restored
 * with the back stack, and a backup code in a saved route is a backup code in a
 * file. The list exists in memory for exactly as long as the screen chain does.
 */
object SecurityFlow {
    var backupCodes: List<String> = emptyList()

    /**
     * The proof minted when the password was checked on screen 04, spent by the
     * enable call on screen 05 and cleared immediately after. It lives in memory
     * only: a server-issued token in a saved navigation argument is a token on
     * disk.
     */
    var enableProof: String? = null

    /** The phone step's proof, minted when the texted code was redeemed. */
    var phoneProof: String? = null

    /**
     * Wipes everything the sector holds in memory. Sign-out clears the token but
     * used to leave the previous person's ten plaintext recovery codes and a live
     * proof sitting in the process for whoever signed in next.
     */
    fun reset() {
        backupCodes = emptyList()
        enableProof = null
        phoneProof = null
    }
}

/**
 * 07 — generate the ten one-time codes.
 *
 * Generation is the moment the old set dies, so it is a deliberate press with
 * the consequence written above it, not a background refresh on open.
 */
@Composable
fun GenerateBackupCodesScreen(
    onBack: () -> Unit,
    onGenerated: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_backup_generate",
        illustrationWidth = 180,
        title = "Generate & Save Backup Codes",
        body = "Create one-time backup codes to access your account if you lose your phone.",
        footer = {
            Column {
                GradientButton(
                    "Generate Codes",
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val token = SessionStore.token(context) ?: throw IllegalStateException("Sign in again.")
                                val resp = Api.generateBackupCodes(token)
                                onGenerated(resp.newBackupCodes)
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                    busy = busy,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AccountLink("Cancel", onBack)
                }
            }
        },
    ) {
        InfoCard {
            Text("What's Included", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(10.dp))
            BackupPoint(Icons.Rounded.Numbers, "10 One-Time Codes", "Each code can be used once.")
            BackupPoint(Icons.Rounded.Lock, "Use When Needed", "Sign in when your phone is unavailable.")
            BackupPoint(Icons.Rounded.VerifiedUser, "Each Code is Unique", "Generating a new set retires the old one.")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Store the codes somewhere only you can reach. Anyone with a code can pass your second factor.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = palette.textSecondary,
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, fontSize = 12.sp, color = palette.error)
        }
    }
}

@Composable
private fun BackupPoint(icon: ImageVector, title: String, body: String) {
    val palette = LocalPalette.current
    Row(Modifier.padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(18.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Text(body, fontSize = 12.sp, color = palette.textSecondary)
        }
    }
}

/**
 * 08 — the only time the codes exist in readable form.
 *
 * The server keeps hashes, so there is no "view them again" path anywhere in the
 * product; this screen says so instead of implying otherwise.
 */
@Composable
fun BackupCodesSavedScreen(
    codes: List<String>,
    onDone: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var copiedOne by remember { mutableStateOf<String?>(null) }
    // Codes are shown once and the server keeps only hashes, so the only copy
    // that will ever exist is the one the person takes from this screen. A
    // "Download" that did nothing, or no export at all, meant backgrounding the
    // app lost the codes with no way back except regenerating a new set.
    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(codes.joinToString("\r\n").toByteArray())
                }
            }
        }
    }
    // Codes are shown once. Leaving without saving them is a real loss, so it
    // takes an explicit confirmation rather than a tap on a button that happens
    // to be closest to the thumb.
    var confirmed by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    // The header's chevron is not the only way out: the system back gesture
    // bypassed the dialog entirely, which made the guard decorative.
    BackHandler(enabled = !leaving) {
        if (confirmed) onDone() else leaving = true
    }

    AccountScreen(
        onBack = { if (confirmed) onDone() else leaving = true },
        illustration = "ill_sec_backup_saved",
        illustrationWidth = 150,
        title = "Backup Codes Saved",
        body = "Store these codes somewhere safe. They won't be shown again.",
        footer = {
            Column {
                GradientButton(
                    "Download Backup Codes",
                    trailing = { Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.padding(start = 6.dp)) },
                    onClick = {
                        try {
                            download.launch("grapmail-backup-codes.txt")
                        } catch (e: ActivityNotFoundException) {
                            // No app to take a file: fall back to the clipboard so
                            // the codes are never stranded on a screen with no exit.
                            clipboard.setText(AnnotatedString(codes.joinToString("\n")))
                            copied = true
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                GradientButton("Done", onDone, enabled = confirmed)
                Spacer(Modifier.height(6.dp))
                FooterNote(
                    text = "Each code can be used once. Generating new codes retires every code on this list.",
                    divider = true,
                )
            }
        },
    ) {
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your Backup Codes", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(codes.joinToString("\n")))
                            copied = true
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        if (copied) Icons.Rounded.CheckCircle else Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        tint = palette.primarySolid,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(if (copied) "Copied" else "Copy all", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = palette.primarySolid)
                }
            }
            Spacer(Modifier.height(12.dp))
            // Two columns, because ten codes in one column pushes the footer off
            // a 640dp phone and the person then scrolls instead of saving.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                codes.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { code ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.softFill)
                                    .clickable {
                                        // Per-code copy: grabbing one code to use
                                        // now should not require copying all ten.
                                        clipboard.setText(AnnotatedString(code))
                                        copiedOne = code
                                    }
                                    .padding(vertical = 9.dp, horizontal = 8.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        code,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                        color = palette.textPrimary,
                                    )
                                    Icon(
                                        if (copiedOne == code) Icons.Rounded.CheckCircle else Icons.Rounded.ContentCopy,
                                        contentDescription = "Copy $code",
                                        tint = palette.textTertiary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        BulletCard(
            "Keep them safe",
            listOf(
                "Store safely — use a password manager or print them out.",
                "Don't share — backup codes are for your eyes only.",
                "One time use — each code can only be used once.",
            ),
        )
        Spacer(Modifier.height(12.dp))
        TermsCheckbox(confirmed, { confirmed = it }) {
            Text(
                "I have saved these codes somewhere safe.",
                fontSize = 13.sp,
                color = palette.textPrimary,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Rounded.Download, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(15.dp))
            Text("Copy them into your password manager, or write them down.", fontSize = 12.sp, color = palette.textSecondary)
        }
    }

    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("These codes will not be shown again") },
            text = { Text("Leave now and the list is gone. You can generate a new set later, but the old codes stop working.") },
            confirmButton = {
                TextButton(onClick = { leaving = false; onDone() }) { Text("Leave anyway") }
            },
            dismissButton = {
                TextButton(onClick = { leaving = false }) { Text("Stay") }
            },
        )
    }
}
