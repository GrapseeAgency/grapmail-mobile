package com.grapsee.grapmail.ui.screens.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.logic.MailSwipePrefs
import com.grapsee.grapmail.logic.SwipeAction
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Settings → Mail settings → Swipe actions. Two rows, Swipe right and Swipe
 * left, each opening the list of actions; the pick is saved on this device
 * at once (logic/MailSwipePrefs.kt) and the mailbox follows it on return.
 * Every row grows with its text, so nothing clips at 200% font.
 */
@Composable
internal fun SwipeActionsSetting() {
    val context = LocalContext.current
    val palette = LocalPalette.current
    remember { MailSwipePrefs.ensureLoaded(context) }
    Column(Modifier.fillMaxWidth().testTag("gm-swipe-actions")) {
        SectionLabel(stringResource(R.string.mailbox_feat_swipe_title), palette)
        Text(stringResource(R.string.mailbox_feat_swipe_body), fontSize = 12.sp, color = palette.textTertiary)
        SwipeChoiceRow(
            title = stringResource(R.string.mailbox_feat_swipe_right),
            current = MailSwipePrefs.right,
            tag = "gm-swipe-right",
            onPick = { MailSwipePrefs.setRight(context, it) },
        )
        SwipeChoiceRow(
            title = stringResource(R.string.mailbox_feat_swipe_left),
            current = MailSwipePrefs.left,
            tag = "gm-swipe-left",
            onPick = { MailSwipePrefs.setLeft(context, it) },
        )
    }
}

@Composable
private fun SwipeChoiceRow(title: String, current: SwipeAction, tag: String, onPick: (SwipeAction) -> Unit) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val currentLabel = swipeActionLabel(current)
    val changeLabel = stringResource(R.string.mailbox_feat_swipe_change, title)
    Box {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = changeLabel, role = Role.Button) { open = true }
                .semantics { stateDescription = currentLabel }
                .heightIn(min = 56.dp)
                .padding(vertical = 10.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwipeActionBadge(current)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, color = palette.textPrimary)
                Text(currentLabel, fontSize = 13.sp, color = palette.textSecondary)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = palette.textTertiary,
                modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MailSwipePrefs.choices.forEach { action ->
                val selected = action == current
                DropdownMenuItem(
                    text = {
                        Text(swipeActionLabel(action), fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    },
                    leadingIcon = { Icon(swipeActionIcon(action), null, tint = swipeActionTint(action)) },
                    trailingIcon = if (selected) ({ Icon(Icons.Rounded.Check, null, tint = palette.primarySolid) }) else null,
                    onClick = {
                        open = false
                        if (!selected) onPick(action)
                    },
                    modifier = Modifier.testTag("$tag-${action.key}")
                        .semantics { if (selected) stateDescription = currentLabel },
                )
            }
        }
    }
}

@Composable
private fun SwipeActionBadge(action: SwipeAction) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(swipeActionTint(action).copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(swipeActionIcon(action), null, tint = swipeActionTint(action), modifier = Modifier.size(20.dp))
    }
}

/** The name an action is listed under — the same words iOS uses. */
@Composable
internal fun swipeActionLabel(action: SwipeAction): String = stringResource(
    when (action) {
        SwipeAction.Archive -> R.string.mailbox_feat_swipe_archive
        SwipeAction.Trash -> R.string.mailbox_feat_swipe_trash
        SwipeAction.ReadToggle -> R.string.mailbox_feat_swipe_read
        SwipeAction.Star -> R.string.mailbox_feat_swipe_star
        SwipeAction.Snooze -> R.string.mailbox_feat_swipe_snooze
        SwipeAction.Move -> R.string.mailbox_feat_swipe_move
        SwipeAction.None -> R.string.mailbox_feat_swipe_none
    },
)

internal fun swipeActionIcon(action: SwipeAction): ImageVector = when (action) {
    SwipeAction.Archive -> Icons.Rounded.Archive
    SwipeAction.Trash -> Icons.Rounded.Delete
    SwipeAction.ReadToggle -> Icons.Rounded.MarkEmailRead
    SwipeAction.Star -> Icons.Rounded.Star
    SwipeAction.Snooze -> Icons.Rounded.Snooze
    SwipeAction.Move -> Icons.Rounded.MoveToInbox
    SwipeAction.None -> Icons.Rounded.Block
}

/** The ink each action is drawn in, here and behind the row (MailSwipeRow.kt). */
internal fun swipeActionTint(action: SwipeAction): Color = when (action) {
    SwipeAction.Archive -> Color(0xFF1E8E4E)
    SwipeAction.Trash -> Color(0xFFD23B57)
    SwipeAction.ReadToggle -> Color(0xFF2F6FD6)
    SwipeAction.Star -> Color(0xFFC98A00)
    SwipeAction.Snooze -> Color(0xFF7A55D6)
    SwipeAction.Move -> Color(0xFF168C80)
    SwipeAction.None -> Color(0xFF7A808A)
}
