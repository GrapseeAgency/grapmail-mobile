package com.grapsee.grapmail.ui.mailfeatures

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.logic.MailHandledToday
import com.grapsee.grapmail.ui.artwork.InboxZeroArt
import com.grapsee.grapmail.ui.components.GMMotion
import com.grapsee.grapmail.ui.components.gmIsShortWindow
import com.grapsee.grapmail.ui.components.rememberMotionReduced
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * The Inbox Zero moment: what the primary Inbox shows when a successful load
 * left nothing in it (MailboxScreen decides "successful": not first load, not
 * failed, not offline). Other empty boxes keep MailStateBlock's own states.
 *
 * "You handled N emails today." counts what this device filed out of the
 * inbox today for this account (logic/MailHandledToday.kt); with nothing
 * handled the line is "Nothing waiting for you." The illustration arrives
 * once (off under reduced motion), and the very first Inbox Zero of the day
 * may add a short, subtle confetti fall — never under reduced motion.
 *
 * Scrollable on purpose: it sits inside the list's pull-to-refresh, which
 * needs a scrolling child to pull, and at 200% text it must not clip.
 */
@Composable
fun InboxZeroState(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val reduced = rememberMotionReduced()
    val changes by MailHandledToday.changes.collectAsState()
    var account by remember { mutableStateOf<String?>(null) }
    // The line waits for the account read, so it never flashes "Nothing
    // waiting" before the real count lands.
    var accountRead by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        account = SessionStore.snapshot(context)?.address
        accountRead = true
    }
    val handled = remember(account, changes) { MailHandledToday.count(context, account) }
    // Asked once per account per composition: the store answers true only for
    // the first Inbox Zero of the day, so a later one stays calm.
    val celebrate = remember(account) {
        account != null && !reduced && MailHandledToday.count(context, account) > 0 &&
            MailHandledToday.takeCelebration(context, account)
    }

    val entrance = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (entrance.value < 1f) {
            entrance.animateTo(1f, tween(GMMotion.LongMs * 2, easing = GMMotion.EmphasizedDecelerate))
        }
    }
    val fall = remember { Animatable(0f) }
    LaunchedEffect(celebrate) {
        if (celebrate && !reduced) fall.animateTo(1f, tween(1_800, easing = LinearEasing))
    }

    val short = gmIsShortWindow()
    Box(
        modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 88.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("mail-inbox-zero"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            InboxZeroArt(
                Modifier.fillMaxWidth(0.86f).height(if (short) 120.dp else 190.dp),
                entrance = { entrance.value },
                confetti = if (celebrate) ({ fall.value }) else null,
            )
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.graphicsLayer {
                    val p = ((entrance.value - 0.25f) / 0.75f).coerceIn(0f, 1f)
                    alpha = p
                    translationY = (1f - p) * 8.dp.toPx()
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.mailbox_feat_zero_title),
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(6.dp))
                if (accountRead) Text(
                    if (handled > 0) pluralStringResource(R.plurals.mailbox_feat_zero_handled, handled, handled)
                    else stringResource(R.string.mailbox_feat_zero_nothing),
                    fontSize = 14.sp, lineHeight = 21.sp,
                    color = palette.textSecondary, textAlign = TextAlign.Center,
                )
            }
        }
    }
}
