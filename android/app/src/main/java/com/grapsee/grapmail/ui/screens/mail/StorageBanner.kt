package com.grapsee.grapmail.ui.screens.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.MailStorageResponse
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * The storage banner above the mail list, the way Gmail warns as an account fills.
 *
 * Read when the mailbox comes to the front (at most once a minute, the server
 * caches the sum for that long anyway). Drawn only at 80% and above; "Not now"
 * hides it until the level rises. A failed read draws nothing: no banner is a
 * smaller claim than a wrong one.
 */
@Composable
fun StorageBanner(onOpenFiles: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<MailStorageResponse?>(null) }
    var dismissedLevel by rememberSaveable { mutableStateOf<String?>(null) }
    var lastRead by remember { mutableStateOf(0L) }
    val owner = LocalLifecycleOwner.current

    fun read() {
        val now = System.currentTimeMillis()
        if (now - lastRead < 60_000) return
        lastRead = now
        scope.launch {
            val token = SessionStore.token(context) ?: return@launch
            usage = runCatching { Api.mailStorage(token) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()?.takeIf { it.success }
        }
    }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) read() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val u = usage ?: return
    if (u.level == "ok" || u.level == dismissedLevel) return
    val full = u.level == "full"
    val title = if (full) "Your storage is full" else "Your storage is almost full"
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (u.level == "warning") palette.warnChipBg else palette.error.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("mail-storage-banner"),
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (u.level == "warning") palette.warnInk else palette.error)
        Text("${u.usedLabel} of ${u.limitLabel} used (${formatPercent(u.percent)}). Delete large attachments, then empty Trash and Spam.",
            fontSize = 12.sp, color = palette.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpenFiles) { Text("See large files") }
            TextButton(onClick = { dismissedLevel = u.level }) { Text("Not now") }
        }
    }
}

private fun formatPercent(p: Double): String =
    if (p % 1.0 == 0.0) "${p.toInt()}%" else "%.1f%%".format(p)
