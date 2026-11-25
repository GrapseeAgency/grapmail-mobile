package com.grapsee.grapmail.ui.screens.mail

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.MainActivity
import com.grapsee.grapmail.R
import com.grapsee.grapmail.logic.MailboxViewModel
import com.grapsee.grapmail.ui.components.GMLayout
import com.grapsee.grapmail.ui.components.gmIsNarrowWindow
import com.grapsee.grapmail.ui.components.gmMaxWidth
import com.grapsee.grapmail.ui.theme.GrapMailTheme
import kotlinx.coroutines.delay

/**
 * The receipt after a send-later: "Scheduled for Tue 8:00 AM" with Undo and
 * View, shown over whatever screen the closed composer returned to.
 *
 * ── WHY AN ACTIVITY OVERLAY ─────────────────────────────────────────────────
 * The composer closes the moment the server answers `scheduled`, so the
 * snackbar has to outlive it, and the app has no shared snackbar host (each
 * screen draws its own banners). This adds one small ComposeView to the
 * Activity's content frame and removes it when done; it takes the Activity's
 * lifecycle / saved-state owners from the decor view `setContent` set up.
 *
 *  - Undo is `DELETE /scheduled/:id` via [MailboxViewModel.undoScheduledSend]
 *    (HOME-scoped, so it survives the composer). The server returns the
 *    message to Drafts; the bar then says so, or gives the server's reason.
 *  - View opens the Scheduled screen through MainActivity's own
 *    [MainActivity.SCHEDULED_ACTION] contract (the launcher shortcut's),
 *    delivered to the running Activity with FLAG_ACTIVITY_SINGLE_TOP.
 *  - It stays up for the accessibility-recommended time (longer when the
 *    person asked for more time to act), announces itself politely to
 *    TalkBack, and stacks its actions under the text at large font sizes or
 *    in very narrow windows.
 *  - It sits inside the safe drawing area on the sides and the bottom: above
 *    the navigation bar, clear of a landscape display cutout, and above the
 *    keyboard if one is open. This overlay is outside the app's root insets
 *    padding (MainActivity), so it applies its own.
 */
internal object SendLaterReceipt {
    private const val TAG = "gm-send-later-receipt"
    const val OFFER_MS = 8_000
    const val DONE_MS = 4_000

    fun show(activity: Activity, scheduledId: String, message: String, vm: MailboxViewModel) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.findViewWithTag<View>(TAG)?.let { content.removeView(it) }
        val view = ComposeView(activity).apply {
            tag = TAG
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }
        val remove: () -> Unit = { view.post { (view.parent as? ViewGroup)?.removeView(view) } }
        view.setContent {
            GrapMailTheme {
                ReceiptBar(
                    initial = message,
                    onUndo = { done -> vm.undoScheduledSend(scheduledId, done) },
                    onView = {
                        activity.startActivity(
                            Intent(activity, MainActivity::class.java)
                                .setAction(MainActivity.SCHEDULED_ACTION)
                                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                        remove()
                    },
                    onGone = remove,
                )
            }
        }
        content.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
    }

    fun recommendedTimeout(context: Context, ms: Int): Long {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return ms.toLong()
        if (Build.VERSION.SDK_INT < 29) return ms.toLong()
        return manager.getRecommendedTimeoutMillis(
            ms, AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
        ).toLong()
    }
}

private enum class ReceiptPhase { Offer, Undoing, Done }

@Composable
private fun ReceiptBar(
    initial: String,
    onUndo: (done: (String?) -> Unit) -> Unit,
    onView: () -> Unit,
    onGone: () -> Unit,
) {
    val context = LocalContext.current
    var message by remember { mutableStateOf(initial) }
    var phase by remember { mutableStateOf(ReceiptPhase.Offer) }
    val undoing = stringResource(R.string.compose_send_later_undoing)
    val undone = stringResource(R.string.compose_send_later_undone)
    LaunchedEffect(phase) {
        when (phase) {
            ReceiptPhase.Offer -> { delay(SendLaterReceipt.recommendedTimeout(context, SendLaterReceipt.OFFER_MS)); onGone() }
            ReceiptPhase.Done -> { delay(SendLaterReceipt.recommendedTimeout(context, SendLaterReceipt.DONE_MS)); onGone() }
            ReceiptPhase.Undoing -> Unit
        }
    }
    val stacked = LocalDensity.current.fontScale > 1.3f || gmIsNarrowWindow()
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 12.dp, vertical = 16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.inverseSurface,
            contentColor = colors.inverseOnSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.gmMaxWidth(GMLayout.Form)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("gm-send-later-receipt"),
        ) {
            val actions: @Composable () -> Unit = {
                if (phase == ReceiptPhase.Offer) {
                    TextButton(onClick = {
                        phase = ReceiptPhase.Undoing
                        message = undoing
                        onUndo { error ->
                            message = if (error == null) undone
                                else context.getString(R.string.compose_send_later_undo_failed, error)
                            phase = ReceiptPhase.Done
                        }
                    }, modifier = Modifier.testTag("gm-send-later-undo")) {
                        Text(stringResource(R.string.compose_send_later_undo), color = colors.inversePrimary)
                    }
                    TextButton(onClick = onView, modifier = Modifier.testTag("gm-send-later-view")) {
                        Text(stringResource(R.string.compose_send_later_view), color = colors.inversePrimary)
                    }
                }
                if (phase != ReceiptPhase.Undoing) {
                    IconButton(onClick = onGone) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.compose_send_later_dismiss))
                    }
                }
            }
            if (stacked) {
                Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
                    Text(message, fontSize = 14.sp)
                    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) { actions() }
                }
            } else {
                Row(
                    Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(message, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                    actions()
                }
            }
        }
    }
}
