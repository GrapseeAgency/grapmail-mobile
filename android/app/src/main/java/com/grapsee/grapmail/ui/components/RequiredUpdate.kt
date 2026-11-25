package com.grapsee.grapmail.ui.components

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Shown instead of the app when this build is older than the server's minimum
 * (logic/RemoteConfig.kt). It covers everything, so a build the server no longer
 * supports never reaches a screen that would fail without explaining why.
 */
@Composable
fun RequiredUpdateScreen() {
    val palette = LocalPalette.current
    val context = LocalContext.current
    // Scrolls (a landscape phone at 200% text cannot fit it otherwise, and the
    // Update button is the only way forward), and stays centred both ways:
    // the scroll keeps the viewport as its minimum height. The column is
    // capped so a tablet or desktop window does not stretch the button.
    val short = gmIsShortWindow()
    Column(
        Modifier.fillMaxSize().background(palette.bg).verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = if (short) 16.dp else 32.dp)
            .testTag("gm-update-required"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(Modifier.widthIn(max = GMLayout.Form), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.SystemUpdate, null, tint = palette.primarySolid, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(if (short) 12.dp else 20.dp))
        Text(stringResource(R.string.mail_update_required_title), fontSize = 20.sp, fontWeight = FontWeight.Bold,
            color = palette.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.mail_update_required_body),
            fontSize = 14.sp, lineHeight = 20.sp, color = palette.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        val storeUrl = com.grapsee.grapmail.logic.RemoteConfig.config.value.storeUrls.android
        GradientButton(stringResource(R.string.mail_update_now), onClick = {
            if (storeUrl.isNotBlank() && runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(storeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess) return@GradientButton
            val pkg = context.packageName
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(market) }.onFailure {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        })
      }
    }
}

/** The server's notice (logic/RemoteConfig.kt), e.g. "Sending is delayed today." */
@Composable
fun ServerNoticeBanner(text: String, warning: Boolean, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        // Capped and centred on wide windows; unchanged on phones.
        Modifier.gmMaxWidth(GMLayout.Readable).padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (warning) palette.warnChipBg else palette.softFill)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("gm-server-notice"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Info, null, tint = if (warning) palette.warnInk else palette.primarySolid,
            modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, color = if (warning) palette.warnInk else palette.textPrimary,
            modifier = Modifier.weight(1f))
        androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.mail_common_ok)) }
    }
}
