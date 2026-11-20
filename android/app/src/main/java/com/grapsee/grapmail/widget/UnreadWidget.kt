package com.grapsee.grapmail.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.grapsee.grapmail.MainActivity

/**
 * The home-screen widget — unread count, the newest three messages (sender and
 * subject) from the last real read, tapping anywhere opens GrapMail, and the
 * bottom row starts a new message (roadmap F02: unread badge, latest three,
 * compose shortcut). The widget itself never asks the server; what it draws can
 * never be fresher than a real read (see [MailWidgetState]).
 */
class UnreadWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = MailWidgetState.load(context)
        provideContent { UnreadContent(snapshot) }
    }
}

/** The receiver the launcher talks to. */
class UnreadWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UnreadWidget()
}

/** The compose shortcut row — opens GrapMail on the compose surface. */
class StartComposeCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("grapmail_action", "compose")
            },
        )
    }
}

// Brand tokens as the widget draws them — the same measured values the app's
// palettes carry, in their light and dark pairs.
private val canvas = ColorProvider(day = Color(0xFFFBFCFE), night = Color(0xFF0A0A0C))
private val ink = ColorProvider(day = Color(0xFF0F172A), night = Color(0xFFEAEAEA))
private val sub = ColorProvider(day = Color(0xFF5B6B84), night = Color(0xFF9E9EA5))
private val accent = ColorProvider(day = Color(0xFF1A6DF0), night = Color(0xFF8AB4F8))

@Composable
private fun UnreadContent(snapshot: MailWidgetState.Snapshot) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(canvas)
            .clickable(actionStartActivity<MainActivity>())
            .padding(14.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth()) {
            Text(
                "GrapMail",
                style = TextStyle(color = sub, fontSize = 11.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.fillMaxWidth())
            Text(
                snapshot.unread?.toString() ?: "—",
                style = TextStyle(color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold),
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        Text(
            when (snapshot.unread) {
                null -> "not read yet"
                0 -> "no unread mail"
                1 -> "1 unread message"
                else -> snapshot.unread.toString() + " unread messages"
            },
            style = TextStyle(color = ink, fontSize = 12.sp),
        )
        Spacer(GlanceModifier.height(6.dp))
        if (snapshot.recent.isEmpty()) {
            Text(
                snapshot.latest ?: "Open GrapMail to check",
                style = TextStyle(color = sub, fontSize = 11.sp),
                maxLines = 2,
            )
        } else {
            snapshot.recent.forEach { pair ->
                Text(
                    pair.first + " — " + pair.second,
                    style = TextStyle(color = ink, fontSize = 11.sp),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(3.dp))
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        Text(
            "✉  New message",
            style = TextStyle(color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(actionRunCallback<StartComposeCallback>())
                .padding(top = 4.dp, bottom = 2.dp),
        )
    }
}
