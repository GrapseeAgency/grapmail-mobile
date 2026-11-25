package com.grapsee.grapmail.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * The "a new version is available" overlay, offered only when the Play Store
 * itself says an update exists for this install. The person can update from
 * here or close it — it returns with tomorrow's daily check, never nagging.
 */
@Composable
fun UpdateBanner(onUpdate: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    Column(
        // Capped and centred on wide windows; unchanged on phones.
        Modifier
            .gmMaxWidth(GMLayout.Form)
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(1.dp, palette.border, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.SystemUpdate, null,
                tint = palette.primarySolid, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.mail_update_available_title),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.IconButton(onClick = onDismiss, modifier = Modifier.size(22.dp)) {
                Icon(Icons.Rounded.Close, stringResource(R.string.mail_update_dismiss),
                    tint = palette.textTertiary, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        val updateAction: @Composable () -> Unit = {
            androidx.compose.material3.TextButton(onClick = onUpdate) {
                Text(stringResource(R.string.mail_update_action), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = palette.primarySolid)
            }
        }
        // At large text the action goes under the sentence instead of
        // squeezing it to a column of single words.
        if (gmPrefersStackedRows()) {
            Text(
                stringResource(R.string.mail_update_available_body),
                fontSize = 12.sp, color = palette.textSecondary,
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { updateAction() }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.mail_update_available_body),
                fontSize = 12.sp, color = palette.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            updateAction()
        }
    }
}
