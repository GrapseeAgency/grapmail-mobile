package com.grapsee.grapmail.ui.screens.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.ui.theme.Body
import com.grapsee.grapmail.ui.theme.BodyMedium
import com.grapsee.grapmail.ui.theme.Caption
import com.grapsee.grapmail.ui.theme.LocalPalette
import com.grapsee.grapmail.ui.theme.Title

/**
 * The sign-out confirmation (round8-spec §2.4): "Sign out of {address}?"
 * with one checkbox row, "Also remove from this device" (default OFF) and
 * its helper "You'll need to type your address next time."
 *
 * Unticked, the account stays remembered and the next sign-in is the
 * personal welcome-back ([com.grapsee.grapmail.data.RememberedAccounts],
 * reason `signed_out`). Ticked, the caller forgets the record and its photo
 * after the session is cleared. [onConfirm] receives the tick.
 *
 * The app's sign-out controls (mailbox drawer, account sheet, settings) all
 * call MainActivity's sign-out; that owner shows this dialog in front of it
 * — see docs/design/round8-wiring-android-B.md.
 */
@Composable
fun SignOutConfirmDialog(
    address: String,
    onConfirm: (removeFromDevice: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    var remove by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = palette.card,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textSecondary,
        title = { Text(stringResource(R.string.wb_signout_title, address), style = Title) },
        text = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = remove, role = Role.Checkbox, onValueChange = { remove = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The row is the toggle (and the TalkBack node); the box only draws.
                Checkbox(
                    checked = remove,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(
                        checkedColor = palette.primarySolid,
                        uncheckedColor = palette.textSecondary,
                        checkmarkColor = palette.onPrimary,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(stringResource(R.string.wb_signout_also_remove), style = Body, color = palette.textPrimary)
                    Text(stringResource(R.string.wb_signout_also_remove_helper), style = Caption, color = palette.textSecondary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(remove) }) {
                Text(stringResource(R.string.wb_signout_confirm), style = BodyMedium, color = palette.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.wb_cancel), style = BodyMedium, color = palette.textPrimary)
            }
        },
    )
}
