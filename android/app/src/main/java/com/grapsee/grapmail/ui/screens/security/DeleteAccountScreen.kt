package com.grapsee.grapmail.ui.screens.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.ui.components.AccountLink
import com.grapsee.grapmail.ui.components.AccountScreen
import com.grapsee.grapmail.ui.components.FooterNote
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.InfoCard
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * In-app account deletion — the store requirement, wired to the only route
 * that can honestly back it: `POST /auth/account/delete` with the bearer and
 * the password, which the gateway proves itself. A 403 here is the documented
 * wrong-password answer; a 404 means the deployment has not caught up with the
 * route yet, and the server's own refusal is shown rather than a success the
 * client invented.
 *
 * The password is sent to the delete call directly — there is no separate
 * verify-password step, because a proof this call would spend does not exist:
 * the deletion request must carry the credential, not a derived ticket, so a
 * replayed proof cannot delete an account on its own.
 */
@Composable
fun DeleteAccountScreen(onBack: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AccountScreen(
        onBack = onBack,
        illustration = "ill_sec_2fa_off_confirm",
        illustrationWidth = 150,
        title = "Delete Account",
        body = "Deleting this account is permanent. Please read what is removed before continuing.",
        footer = {
            Column {
                GradientButton(
                    "Delete my account",
                    onClick = {
                        if (password.isBlank()) { error = "Enter your password."; return@GradientButton }
                        busy = true; error = null
                        scope.launch {
                            try {
                                val token = SessionStore.token(context)
                                    ?: throw IllegalStateException("Sign in again.")
                                Api.deleteAccount(token, password)
                                onDeleted()
                            } catch (e: ApiException) {
                                // The route's contract names 403 as a wrong
                                // password; the rest is the server's own word,
                                // including the not-yet-deployed 404.
                                error = when (e.status) {
                                    403 -> "That password is not correct."
                                    else -> e.message ?: "The request could not be confirmed."
                                }
                            } catch (e: Exception) {
                                error = e.message ?: "Network error. Please try again."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = password.isNotBlank(),
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
            Text("What is deleted", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            listOf(
                "Your GrapMail mailbox — the address itself, so mail addressed to it " +
                    "will no longer reach anyone.",
                "Every message: sent, received, drafted, archived and trashed.",
                "Your aliases — the extra addresses that delivered into this mailbox.",
            ).forEach {
                Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Rounded.DeleteForever,
                        contentDescription = null,
                        tint = palette.error,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = palette.textSecondary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = null,
                tint = palette.error,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "This cannot be undone and cannot be recovered. The address is not " +
                    "held for you — a deleted address may not be re-created.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = palette.textSecondary,
            )
        }
        Spacer(Modifier.height(14.dp))
        if (error != null) {
            Text(error!!, fontSize = 12.sp, color = palette.error)
            Spacer(Modifier.height(8.dp))
        }
        com.grapsee.grapmail.ui.components.AppTextField(
            label = "Password",
            value = password,
            onValueChange = { password = it; error = null },
            password = true,
            hint = "Enter your password to confirm",
        )
        Spacer(Modifier.height(14.dp))
        FooterNote(
            text = "Only the password on this account can start the deletion.",
            divider = false,
        )
    }
}
