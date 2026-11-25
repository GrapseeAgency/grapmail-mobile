package com.grapsee.grapmail.ui.screens.mail

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.MailSchedule
import com.grapsee.grapmail.ui.components.GMLayout
import com.grapsee.grapmail.ui.components.gmIsShortWindow
import com.grapsee.grapmail.ui.components.gmMaxWidth
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Send later — the compose clock button's sheet and its date/time dialogs.
 *
 * ── WHAT THE QUICK OPTIONS MEAN ─────────────────────────────────────────────
 *  - Later today: now + 3 h, rounded UP to the next :00 / :30. Hidden from
 *    20:00 on, and whenever the rounded time would fall tomorrow.
 *  - Tomorrow morning: tomorrow 08:00.
 *  - Monday morning: the next Monday 08:00 (a week ahead on a Monday). Hidden
 *    on Sunday, where it would be the same instant as "Tomorrow morning".
 *  - Pick date & time: a date from today to the server's 30-day limit, then a
 *    time; refused in the dialog when under a minute or over 30 days ahead
 *    ([MailSchedule.check], the route's own rule).
 *
 * Every time is the person's local wall-clock time in the device zone
 * ([ZoneId.systemDefault]), turned into an instant with java.time (a DST gap
 * moves forward, never silently back), and sent as UTC by [MailSchedule.toWire].
 * The clock is 24 h or 12 h by the system setting, not by the locale.
 *
 * ── ROOM ────────────────────────────────────────────────────────────────────
 * The sheet opens fully expanded and scrolls, so a phone in landscape (a short
 * window, ui/components/Adaptive.kt) reaches every row; rows stack their label
 * over their time and never truncate, so 200 % font only makes them taller. On
 * a tablet the content keeps the form width. A short window or a large font
 * opens the date picker in its text-entry mode and the time picker as
 * [TimeInput] instead of the dial; both have a toggle back.
 */

/** One quick option of the sheet. Pure data, so the rules above are testable. */
internal data class SendLaterOption(val kind: Kind, val at: ZonedDateTime) {
    enum class Kind { LaterToday, TomorrowMorning, MondayMorning }
}

internal object SendLaterTimes {
    const val LATER_TODAY_HOURS: Long = 3
    /** From this hour on, "Later today" is not offered. */
    const val LATER_TODAY_CUTOFF_HOUR: Int = 20
    const val MORNING_HOUR: Int = 8

    fun options(now: ZonedDateTime): List<SendLaterOption> {
        val out = mutableListOf<SendLaterOption>()
        val today = now.toLocalDate()
        if (now.hour < LATER_TODAY_CUTOFF_HOUR) {
            val later = roundUpToHalfHour(now.plusHours(LATER_TODAY_HOURS))
            if (later.toLocalDate() == today) out += SendLaterOption(SendLaterOption.Kind.LaterToday, later)
        }
        val morning = LocalTime.of(MORNING_HOUR, 0)
        out += SendLaterOption(
            SendLaterOption.Kind.TomorrowMorning,
            ZonedDateTime.of(today.plusDays(1), morning, now.zone),
        )
        if (now.dayOfWeek != DayOfWeek.SUNDAY) {
            val monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            out += SendLaterOption(SendLaterOption.Kind.MondayMorning, ZonedDateTime.of(monday, morning, now.zone))
        }
        return out
    }

    /** 14:07 -> 14:30, 14:30:00 -> 14:30, 14:30:05 -> 15:00. */
    fun roundUpToHalfHour(t: ZonedDateTime): ZonedDateTime {
        val minute = t.truncatedTo(ChronoUnit.MINUTES)
        val past = minute.minute % 30
        if (past == 0 && minute == t) return t
        return minute.plusMinutes((30 - past).toLong())
    }

    /** The first time the picker suggests for [day]: an hour from now, rounded, on today; 08:00 otherwise. */
    fun suggestedTime(day: LocalDate, now: ZonedDateTime): LocalTime {
        if (day != now.toLocalDate()) return LocalTime.of(MORNING_HOUR, 0)
        val next = roundUpToHalfHour(now.plusHours(1))
        return if (next.toLocalDate() == day) next.toLocalTime() else LocalTime.of(23, 59)
    }
}

/**
 * "Tue 8:00 AM" / "Tue 08:00" for an instant in the device zone, with the
 * month and day added when it is more than six days away (a weekday alone
 * would then be ambiguous). ICU skeletons, so each locale orders and words
 * it its own way while the 12/24 h choice follows the system setting.
 */
internal fun sendLaterWhen(context: Context, at: Instant, now: Instant = Instant.now()): String {
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    val far = at.isAfter(now.plus(6, ChronoUnit.DAYS))
    val skeleton = (if (far) "MMMdEEE" else "EEE") + (if (is24) "Hmm" else "hmma")
    val locale = context.resources.configuration.locales[0]
    val format = android.icu.text.DateFormat.getInstanceForSkeleton(skeleton, locale)
    format.timeZone = android.icu.util.TimeZone.getDefault()
    return format.format(java.util.Date.from(at))
}

private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private enum class SendLaterStep { Sheet, Date, Time }

/**
 * The whole send-later flow: sheet, then (for "Pick date & time") a date and a
 * time dialog. Shown while the caller keeps it in composition; [onClose] asks
 * the caller to remove it, [onSchedule] hands over a validated instant (the
 * caller removes it then too).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SendLaterFlow(onClose: () -> Unit, onSchedule: (Instant) -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    // "Now" moves while the sheet is open, so 19:59 -> 20:00 drops "Later today".
    var now by remember { mutableStateOf(ZonedDateTime.now(zone)) }
    LaunchedEffect(zone) {
        while (true) {
            delay(30_000)
            now = ZonedDateTime.now(zone)
        }
    }
    var step by rememberSaveable { mutableStateOf(SendLaterStep.Sheet) }
    var pickedEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val compact = gmIsShortWindow() || LocalDensity.current.fontScale > 1.3f

    when (step) {
        SendLaterStep.Sheet -> {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val scope = rememberCoroutineScope()
            val hideThen: (() -> Unit) -> Unit = { action ->
                scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
            }
            ModalBottomSheet(
                onDismissRequest = onClose,
                sheetState = sheetState,
                containerColor = palette.card,
                contentColor = palette.textPrimary,
            ) {
                Column(
                    Modifier.gmMaxWidth(GMLayout.Form)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp)
                        .testTag("gm-send-later-sheet"),
                ) {
                    Text(
                        stringResource(R.string.compose_send_later_title),
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
                    )
                    SendLaterTimes.options(now).forEach { option ->
                        val label = when (option.kind) {
                            SendLaterOption.Kind.LaterToday -> stringResource(R.string.compose_send_later_later_today)
                            SendLaterOption.Kind.TomorrowMorning -> stringResource(R.string.compose_send_later_tomorrow_morning)
                            SendLaterOption.Kind.MondayMorning -> stringResource(R.string.compose_send_later_monday_morning)
                        }
                        val icon = when (option.kind) {
                            SendLaterOption.Kind.LaterToday -> Icons.Rounded.Schedule
                            SendLaterOption.Kind.TomorrowMorning -> Icons.Rounded.LightMode
                            SendLaterOption.Kind.MondayMorning -> Icons.Rounded.DateRange
                        }
                        val instant = option.at.toInstant()
                        SendLaterRow(
                            icon = icon, label = label, detail = sendLaterWhen(context, instant, now.toInstant()),
                            tag = "gm-send-later-${option.kind.name}",
                        ) { hideThen { onSchedule(instant) } }
                    }
                    SendLaterRow(
                        icon = Icons.Rounded.CalendarMonth,
                        label = stringResource(R.string.compose_send_later_pick),
                        detail = stringResource(R.string.compose_send_later_pick_hint),
                        tag = "gm-send-later-pick",
                    ) { hideThen { step = SendLaterStep.Date } }
                    Text(
                        stringResource(R.string.compose_send_later_zone_note,
                            zone.getDisplayName(TextStyle.FULL, context.resources.configuration.locales[0])),
                        fontSize = 12.sp, color = palette.textTertiary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
        }

        SendLaterStep.Date -> {
            val today = now.toLocalDate()
            val last = Instant.ofEpochMilli(System.currentTimeMillis() + MailSchedule.MAX_AHEAD_MS).atZone(zone).toLocalDate()
            val selectable = remember(today, last) {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                        val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                        return !d.isBefore(today) && !d.isAfter(last)
                    }
                    override fun isSelectableYear(year: Int): Boolean = year in today.year..last.year
                }
            }
            val initialDay = pickedEpochDay?.let(LocalDate::ofEpochDay) ?: today.plusDays(1)
            val dateState = rememberDatePickerState(
                initialSelectedDateMillis = initialDay.utcMillis(),
                yearRange = today.year..last.year,
                initialDisplayMode = if (compact) DisplayMode.Input else DisplayMode.Picker,
                selectableDates = selectable,
            )
            val chosen = dateState.selectedDateMillis
                ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                ?.takeIf { !it.isBefore(today) && !it.isAfter(last) }
            DatePickerDialog(
                onDismissRequest = onClose,
                confirmButton = {
                    TextButton(
                        enabled = chosen != null,
                        onClick = {
                            pickedEpochDay = chosen?.toEpochDay()
                            step = SendLaterStep.Time
                        },
                        modifier = Modifier.testTag("gm-send-later-date-next"),
                    ) { Text(stringResource(R.string.compose_send_later_next)) }
                },
                dismissButton = {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.compose_send_later_cancel)) }
                },
            ) {
                DatePicker(
                    state = dateState,
                    title = {
                        Text(stringResource(R.string.compose_send_later_date_title),
                            modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp))
                    },
                    showModeToggle = true,
                )
            }
        }

        SendLaterStep.Time -> {
            val day = pickedEpochDay?.let(LocalDate::ofEpochDay) ?: now.toLocalDate().plusDays(1)
            val suggested = remember(day) { SendLaterTimes.suggestedTime(day, ZonedDateTime.now(zone)) }
            val timeState = rememberTimePickerState(
                initialHour = suggested.hour,
                initialMinute = suggested.minute,
                is24Hour = android.text.format.DateFormat.is24HourFormat(context),
            )
            var useInput by rememberSaveable { mutableStateOf(compact) }
            var error by remember { mutableStateOf<String?>(null) }
            val tooSoon = stringResource(R.string.compose_send_later_too_soon)
            val tooFar = stringResource(R.string.compose_send_later_too_far)
            val dayLabel = remember(day) {
                val format = android.icu.text.DateFormat.getInstanceForSkeleton("MMMMdEEEE", context.resources.configuration.locales[0])
                format.timeZone = android.icu.util.TimeZone.getTimeZone("UTC")
                format.format(java.util.Date(day.utcMillis()))
            }
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(stringResource(R.string.compose_send_later_time_title)) },
                text = {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(dayLabel, fontSize = 14.sp, color = palette.textSecondary)
                        if (useInput) TimeInput(state = timeState)
                        // Vertical always: the dialog is narrower than the
                        // horizontal layout a landscape tablet would pick.
                        else TimePicker(state = timeState, layoutType = TimePickerLayoutType.Vertical)
                        error?.let {
                            Text(it, fontSize = 13.sp, color = palette.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        TextButton(onClick = { useInput = !useInput }) {
                            Icon(if (useInput) Icons.Rounded.Schedule else Icons.Rounded.Keyboard, null,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (useInput) R.string.compose_send_later_use_clock else R.string.compose_send_later_type_time))
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val at = ZonedDateTime.of(day, LocalTime.of(timeState.hour, timeState.minute), zone).toInstant()
                            when (MailSchedule.check(at, System.currentTimeMillis())) {
                                MailSchedule.Verdict.OK -> onSchedule(at)
                                MailSchedule.Verdict.TOO_SOON -> error = tooSoon
                                MailSchedule.Verdict.TOO_FAR -> error = tooFar
                            }
                        },
                        modifier = Modifier.testTag("gm-send-later-time-schedule"),
                    ) { Text(stringResource(R.string.compose_send_later_schedule)) }
                },
                dismissButton = {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.compose_send_later_cancel)) }
                },
            )
        }
    }
}

/** One sheet row: icon, label over detail, the whole row one TalkBack button. */
@Composable
private fun SendLaterRow(icon: ImageVector, label: String, detail: String, tag: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val spoken = stringResource(R.string.compose_send_later_option_description, label, detail)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = palette.primarySolid, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp, color = palette.textPrimary)
            Text(detail, fontSize = 13.sp, color = palette.textSecondary)
        }
    }
}
