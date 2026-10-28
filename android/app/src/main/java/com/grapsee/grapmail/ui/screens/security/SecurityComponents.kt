package com.grapsee.grapmail.ui.screens.security

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.ActivityDto
import com.grapsee.grapmail.data.SessionDto
import com.grapsee.grapmail.ui.components.AccountLink
import com.grapsee.grapmail.ui.components.ChipTone
import com.grapsee.grapmail.ui.components.StatusChip
import com.grapsee.grapmail.ui.theme.LocalPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The security hub's list row: a soft icon tile, a title, an optional second
 * line, and either a status chip or a chevron.
 *
 * Every one of the 24 designs uses this same row for its settings, so it lives
 * here rather than being redrawn per screen — a row that is 2dp taller on the
 * sessions screen than on the hub reads as a mistake, not a variation.
 */
@Composable
fun SecurityRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    chip: String? = null,
    chipTone: ChipTone = ChipTone.Success,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.softFill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = palette.textSecondary)
            }
        }
        when {
            trailing != null -> trailing()
            chip != null -> StatusChip(chip, chipTone)
            onClick != null -> Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.textTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Hairline between rows, inset past the icon tile so the list reads as a list. */
@Composable
fun RowDivider() {
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 52.dp)
            .height(1.dp)
            .background(palette.border),
    )
}

/** A destructive action: solid red, the same height as the primary button. */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .then(modifier)
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled && !busy) palette.error else palette.error.copy(alpha = 0.45f))
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (busy) "Working…" else text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

/** One signed-in device, as the sessions screen prints it. */
@Composable
fun SessionCard(
    session: SessionDto,
    onRevoke: (() -> Unit)?,
) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, palette.border, RoundedCornerShape(16.dp))
            .background(palette.card)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                session.device.ifBlank { "Unknown device" },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.current) {
                Text(
                    "This device",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.successInk,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(palette.successChipBg)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(13.dp))
            // The reference row is "browser · city" over an absolute date and time.
            // There is no GeoIP source, so the city is omitted rather than invented;
            // the browser and the masked IP are real, and an absolute time is what a
            // person compares against a suspicious sign-in.
            Text(
                listOf(session.browser, session.ip).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { absoluteDateTime(session.lastSeenAt) },
                fontSize = 12.sp,
                color = palette.textSecondary,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            listOf(absoluteDateTime(session.createdAt), if (session.ip.isNotBlank()) session.ip else null)
                .filterNotNull().joinToString(" · "),
            fontSize = 11.sp,
            color = palette.textTertiary,
        )
        if (onRevoke != null && !session.current) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onRevoke)
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sign out this device", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.error)
            }
        }
    }
}

/** A row on the activity timeline; the tone is the event's own severity. */
@Composable
fun ActivityRow(event: ActivityDto) {
    val palette = LocalPalette.current
    val (icon, tint) = when {
        event.severity == "critical" -> Icons.Rounded.ErrorOutline to palette.error
        event.severity == "warning" -> Icons.Rounded.WarningAmber to palette.warnInk
        event.type == "signin" -> Icons.Rounded.CheckCircle to palette.success
        else -> Icons.Rounded.Info to palette.primarySolid
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(palette.softFill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(eventTitle(event.type), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            if (event.detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(event.detail, fontSize = 12.sp, lineHeight = 17.sp, color = palette.textSecondary)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                listOf(event.browser.ifBlank { event.device }, event.ip).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 11.sp,
                color = palette.textTertiary,
            )
        }
        // The reference timeline carries an absolute date and time in a right-hand
        // column, so events can be compared to the minute; relative time cannot.
        Column(horizontalAlignment = Alignment.End) {
            Text(absoluteDateTime(event.at), fontSize = 11.sp, color = palette.textTertiary)
        }
    }
}

/** The event's own name, not the backend's identifier. */
fun eventTitle(type: String): String = when (type) {
    "signin" -> "Signed in"
    "password_changed" -> "Password changed"
    "two_factor_enabled" -> "Two-step verification turned on"
    "two_factor_disabled" -> "Two-step verification turned off"
    "backup_codes_generated" -> "Backup codes regenerated"
    "backup_code_used" -> "A backup code was used"
    "session_revoked" -> "A device was signed out"
    "all_sessions_signed_out" -> "Signed out of all devices"
    "phone_changed" -> "Phone number changed"
    else -> "Account activity"
}

/**
 * "2 minutes ago" from the ISO timestamps the API returns.
 *
 * Deliberately coarse. A security list is read to answer "was this me, and was
 * it just now" — seconds and timezones get in the way of that. An unparseable
 * timestamp degrades to "Recently" rather than an exception on a screen that
 * exists to reassure somebody.
 */
fun relativeTime(iso: String): String {
    val millis = parseIso(iso) ?: return "recently"
    val diff = System.currentTimeMillis() - millis
    if (diff < 0) return "just now"
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} hr ago"
        minutes < 48 * 60 -> "yesterday"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

private fun parseIso(iso: String): Long? {
    if (iso.isBlank()) return null
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
    )
    for (pattern in patterns) {
        runCatching {
            val f = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val parsed: Date? = f.parse(iso)
            if (parsed != null) return parsed.time
        }
    }
    return null
}

/**
 * "Apr 26, 2025 · 10:24 AM" in the device's own zone.
 *
 * The sessions and activity references print an absolute date and time, not a
 * relative one: "3 days ago" cannot be compared against a suspicious sign-in's
 * timestamp, which is exactly the judgement these screens ask for. An
 * unparseable value degrades to the raw string rather than a fabricated date.
 */
fun absoluteDateTime(iso: String): String {
    val millis = parseIso(iso) ?: return if (iso.isBlank()) "" else iso
    val date = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.US).format(Date(millis))
    return date
}

/** A hub row with a real on/off switch, as the reference draws the 2FA row. */
@Composable
fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    chip: String? = null,
    chipTone: ChipTone = ChipTone.Success,
    onToggle: (Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(palette.softFill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = palette.textSecondary)
            }
            if (chip != null) {
                Spacer(Modifier.height(6.dp))
                StatusChip(chip, chipTone)
            }
        }
        // The tag is the CI walk's handle for this switch. The hub row's title
        // text is not clickable, so the walk taps the switch itself — and
        // "the first checkable node" only identifies it while the hub has
        // exactly one switch. Name it, so the next toggle row cannot steal
        // the tap.
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            enabled = enabled,
            modifier = Modifier.testTag("sec-2fa-switch"),
        )
    }
}

/** Icon tile + label + value + optional caption, for the alert/confirm detail cards. */
@Composable
fun DetailRow(icon: ImageVector, label: String, value: String, caption: String? = null) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(palette.softFill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp, color = palette.textSecondary)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            if (caption != null) Text(caption, fontSize = 11.sp, color = palette.textTertiary)
        }
    }
}

/** A titled card of check-marked lines: password requirements, benefits, tips. */
@Composable
fun BulletCard(title: String, bullets: List<String>) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .border(1.dp, palette.border, RoundedCornerShape(16.dp)).background(palette.card).padding(16.dp),
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
        Spacer(Modifier.height(10.dp))
        bullets.forEach { line ->
            Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.success, modifier = Modifier.size(15.dp))
                Text(line, fontSize = 13.sp, lineHeight = 18.sp, color = palette.textSecondary, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The red "this cannot be undone" warning the revoke confirmations show. */
@Composable
fun CannotBeUndoneCard(text: String = "This action cannot be undone. The device will be signed out immediately and will need to sign in again to access your account.") {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(palette.error.copy(alpha = 0.08f))
            .border(1.dp, palette.error.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = palette.error, modifier = Modifier.size(17.dp))
        Text(text, fontSize = 12.sp, lineHeight = 17.sp, color = palette.textSecondary, modifier = Modifier.weight(1f))
    }
}

/** A neutral note card: the spam-folder hint, the "keep your account secure" footer. */
@Composable
fun InfoNoteCard(title: String? = null, body: String) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(palette.softFill).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Info, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(17.dp))
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Text(body, fontSize = 12.sp, lineHeight = 17.sp, color = palette.textSecondary)
        }
    }
}

/** The "← Back to X" text link the reference footers use. */
@Composable
fun BackLink(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = palette.primarySolid, modifier = Modifier.size(18.dp))
        Text(" $label", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.primarySolid)
    }
}

/**
 * The loading / failed / content gate every read screen shares.
 *
 * A security screen may never show a guess as a fact, so the three states are
 * spelled out once rather than each screen inventing its own placeholder text.
 */
@Composable
fun LoadStateBlock(loading: Boolean, failed: Boolean, onRetry: () -> Unit, content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    when {
        loading -> Text("Loading…", fontSize = 13.sp, color = palette.textSecondary)
        failed -> {
            Column {
                Text("We couldn't load this right now.", fontSize = 13.sp, color = palette.textSecondary)
                Spacer(Modifier.height(6.dp))
                AccountLink("Try again", onRetry)
            }
        }
        else -> content()
    }
}
