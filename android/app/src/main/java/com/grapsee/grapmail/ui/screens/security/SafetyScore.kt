package com.grapsee.grapmail.ui.screens.security

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.SafetyChecklistItemDto
import com.grapsee.grapmail.data.SecurityOverviewCache
import com.grapsee.grapmail.data.SecurityOverviewResponse
import com.grapsee.grapmail.logic.FeatureTipId
import com.grapsee.grapmail.logic.FeatureTipsStore
import com.grapsee.grapmail.logic.GMHaptics
import com.grapsee.grapmail.ui.components.FeatureTipAnchor
import com.grapsee.grapmail.ui.components.GMMotion
import com.grapsee.grapmail.ui.components.SkeletonBone
import com.grapsee.grapmail.ui.components.rememberFeatureTipsAllowed
import com.grapsee.grapmail.ui.components.rememberMotionReduced
import com.grapsee.grapmail.ui.theme.BodyMedium
import com.grapsee.grapmail.ui.theme.Caption
import com.grapsee.grapmail.ui.theme.CaptionMedium
import com.grapsee.grapmail.ui.theme.LocalPalette
import com.grapsee.grapmail.ui.theme.Title
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/*
 * Round 8 — Workstream C: the account safety score (docs/design/round8-spec.md §4).
 *
 * The server computes the score (§4.1); nothing here recomputes it. What the
 * client derives is presentation only: the order of the checklist (§4.2), the
 * "{k} steps to Strong" hint (from the server's own points), and the colours.
 */

// ── Colour roles ─────────────────────────────────────────────────────────────

/**
 * The spec's colour roles (§1.4/§1.5), resolved from the live theme in ONE place.
 *
 * Every value is a theme token — [LocalPalette] or [MaterialTheme.colorScheme] —
 * never a literal: the Round 8 brand update (Workstream A) lands in parallel and
 * adds `successFill`, `errorFill`, `errorContainer`, `warning`, `primaryText`
 * and the signature gradient to the palette. Until it does, the nearest
 * existing token stands in, and when it lands only this function changes.
 * The containers that have no token yet are the ink composited over the card at
 * a low alpha, so they stay opaque and follow light/dark on their own.
 */
@Immutable
data class SecColors(
    val surface: Color,
    val softFill: Color,
    val border: Color,
    val stepInactive: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val primaryText: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val success: Color,
    val successFill: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val error: Color,
    val errorFill: Color,
    val errorContainer: Color,
    /** Text and icons on a filled (primary / error) surface. */
    val onFill: Color,
    /** The signature gradient's stops (§1.3), as theme tokens. */
    val signature: List<Color>,
    /** The celebration's particle colours (§4.4), as theme tokens. */
    val confetti: List<Color>,
)

@Composable
fun secColors(): SecColors {
    val p = LocalPalette.current
    val scheme = MaterialTheme.colorScheme
    return remember(p, scheme.primaryContainer, scheme.onPrimaryContainer) {
        SecColors(
            surface = p.card,
            softFill = p.softFill,
            border = p.border,
            stepInactive = p.stepInactive,
            textPrimary = p.textPrimary,
            textSecondary = p.textSecondary,
            textTertiary = p.textTertiary,
            primaryText = p.primarySolid,
            primaryContainer = scheme.primaryContainer,
            onPrimaryContainer = scheme.onPrimaryContainer,
            success = p.successInk,
            successFill = p.success,
            successContainer = p.successChipBg,
            warning = p.warnInk,
            warningContainer = p.warnChipBg,
            error = p.error,
            errorFill = p.error,
            errorContainer = p.error.copy(alpha = 0.14f).compositeOver(p.card),
            onFill = p.onPrimary,
            signature = listOf(p.primaryTop, p.primarySolid, p.tabTintSent),
            confetti = listOf(p.primaryTop, p.primarySolid, p.tabTintSent, p.success, p.star),
        )
    }
}

// ── Levels ───────────────────────────────────────────────────────────────────

enum class SafetyLevel { NeedsAttention, Good, Strong }

/** The server's `level`, or — for an unknown word (open enum) — the §4.1 range. */
fun safetyLevelOf(level: String?, value: Int): SafetyLevel = when (level) {
    "needs_attention" -> SafetyLevel.NeedsAttention
    "good" -> SafetyLevel.Good
    "strong" -> SafetyLevel.Strong
    else -> when {
        value >= 85 -> SafetyLevel.Strong
        value >= 60 -> SafetyLevel.Good
        else -> SafetyLevel.NeedsAttention
    }
}

@Composable
fun safetyLevelWord(level: SafetyLevel): String = when (level) {
    SafetyLevel.NeedsAttention -> stringResource(R.string.seccenter_level_needs_attention)
    SafetyLevel.Good -> stringResource(R.string.seccenter_level_good)
    SafetyLevel.Strong -> stringResource(R.string.seccenter_level_strong)
}

fun safetyLevelColor(level: SafetyLevel, colors: SecColors): Color = when (level) {
    SafetyLevel.NeedsAttention -> colors.warning
    SafetyLevel.Good -> colors.primaryText
    SafetyLevel.Strong -> colors.success
}

// ── Checklist vocabulary (§4.2) ──────────────────────────────────────────────

/** The eight known checklist ids, in table order. An unknown id is hidden (§6.1). */
enum class SafetyItem(val key: String) {
    TwoStep("two_step"),
    Passkey("passkey"),
    RecoveryCodes("recovery_codes"),
    RecoveryEmail("recovery_email"),
    SecondDevice("second_device"),
    PasswordHealth("password_health"),
    RecoveryPhone("recovery_phone"),
    AlertsReviewed("alerts_reviewed");

    companion object {
        fun of(id: String): SafetyItem? = entries.firstOrNull { it.key == id }
    }
}

/**
 * Where a checklist row (or a recovery-review row) leads. The navigation graph
 * maps each to an existing screen; [Devices] and [Activity] stay inside the
 * Security Center.
 */
enum class SafetyAction {
    TwoStep,
    /** Two-step is already on: the hub, where its switch lives. */
    TwoStepManage,
    Passkeys,
    RecoveryCodes,
    RecoveryEmail,
    Password,
    Phone,
    Devices,
    Activity,
}

/** One visible checklist row: the known item, the server's row, and where it leads. */
@Immutable
data class SafetyChecklistEntry(
    val item: SafetyItem,
    val dto: SafetyChecklistItemDto,
    val done: Boolean,
    val action: SafetyAction,
)

/** The server's `action` string first (§4.2 "Action"), the item's own default otherwise. */
internal fun actionFor(item: SafetyItem, action: String?, done: Boolean): SafetyAction {
    val fromServer = when (action) {
        "security.two_step" -> SafetyAction.TwoStep
        "security.passkeys" -> SafetyAction.Passkeys
        "security.recovery_codes" -> SafetyAction.RecoveryCodes
        "account.recovery_email" -> SafetyAction.RecoveryEmail
        "security.devices" -> SafetyAction.Devices
        "security.password" -> SafetyAction.Password
        "account.phone" -> SafetyAction.Phone
        "security.activity" -> SafetyAction.Activity
        else -> null
    }
    val resolved = fromServer ?: when (item) {
        SafetyItem.TwoStep -> SafetyAction.TwoStep
        SafetyItem.Passkey -> SafetyAction.Passkeys
        SafetyItem.RecoveryCodes -> SafetyAction.RecoveryCodes
        SafetyItem.RecoveryEmail -> SafetyAction.RecoveryEmail
        SafetyItem.SecondDevice -> SafetyAction.Devices
        SafetyItem.PasswordHealth -> SafetyAction.Password
        SafetyItem.RecoveryPhone -> SafetyAction.Phone
        SafetyItem.AlertsReviewed -> SafetyAction.Activity
    }
    // A done two-step row taps through "for management" (§4.2): the enable flow
    // would only re-run a setup that is already finished.
    return if (resolved == SafetyAction.TwoStep && done) SafetyAction.TwoStepManage else resolved
}

/**
 * §4.2 "Checklist rows on the client": unknown ids and `not_applicable` rows
 * hidden; todo first by points (desc), then done in table order.
 */
fun visibleChecklist(overview: SecurityOverviewResponse?): List<SafetyChecklistEntry> {
    val rows = overview?.checklist.orEmpty().mapNotNull { dto ->
        val item = SafetyItem.of(dto.id) ?: return@mapNotNull null
        when (dto.state) {
            "done" -> SafetyChecklistEntry(item, dto, true, actionFor(item, dto.action, true))
            "todo" -> SafetyChecklistEntry(item, dto, false, actionFor(item, dto.action, false))
            else -> null // not_applicable, or a state this build does not know
        }
    }.distinctBy { it.item }
    val todo = rows.filter { !it.done }.sortedWith(compareByDescending<SafetyChecklistEntry> { it.dto.points }.thenBy { it.item.ordinal })
    val done = rows.filter { it.done }.sortedBy { it.item.ordinal }
    return todo + done
}

/**
 * The fewest todo steps (most points first) that lift the server's own
 * earned/possible ratio to Strong (≥ 85), using the §4.1 rounding. Null when
 * no number of remaining steps gets there, or the score is not known.
 */
fun stepsToStrong(overview: SecurityOverviewResponse?, entries: List<SafetyChecklistEntry>): Int? {
    val score = overview?.score ?: return null
    val possible = score.possiblePoints
    if (possible <= 0) return null
    var earned = score.earnedPoints
    fun value(e: Int) = kotlin.math.floor(100.0 * e / possible + 0.5).toInt()
    if (value(earned) >= 85) return 0
    var steps = 0
    for (row in entries.filter { !it.done }) {
        earned += row.dto.points
        steps += 1
        if (value(earned) >= 85) return steps
    }
    return null
}

@Composable
fun safetyItemTitle(entry: SafetyChecklistEntry): String {
    val done = entry.done
    val count = entry.dto.meta?.count
    return when (entry.item) {
        SafetyItem.TwoStep -> stringResource(if (done) R.string.seccenter_item_two_step_done else R.string.seccenter_item_two_step_todo)
        SafetyItem.Passkey -> stringResource(if (done) R.string.seccenter_item_passkey_done else R.string.seccenter_item_passkey_todo)
        SafetyItem.RecoveryCodes -> stringResource(if (done) R.string.seccenter_item_recovery_codes_done else R.string.seccenter_item_recovery_codes_todo)
        SafetyItem.RecoveryEmail -> stringResource(if (done) R.string.seccenter_item_recovery_email_done else R.string.seccenter_item_recovery_email_todo)
        SafetyItem.SecondDevice -> if (done) {
            val n = (count ?: 2).coerceAtLeast(2)
            pluralStringResource(R.plurals.seccenter_item_second_device_done, n, n)
        } else stringResource(R.string.seccenter_item_second_device_todo)
        SafetyItem.PasswordHealth -> stringResource(if (done) R.string.seccenter_item_password_health_done else R.string.seccenter_item_password_health_todo)
        SafetyItem.RecoveryPhone -> stringResource(if (done) R.string.seccenter_item_recovery_phone_done else R.string.seccenter_item_recovery_phone_todo)
        SafetyItem.AlertsReviewed -> if (done) stringResource(R.string.seccenter_item_alerts_reviewed_done) else {
            val n = (count ?: 1).coerceAtLeast(1)
            pluralStringResource(R.plurals.seccenter_item_alerts_reviewed_todo, n, n)
        }
    }
}

@Composable
fun safetyItemWhy(item: SafetyItem): String = stringResource(
    when (item) {
        SafetyItem.TwoStep -> R.string.seccenter_item_two_step_why
        SafetyItem.Passkey -> R.string.seccenter_item_passkey_why
        SafetyItem.RecoveryCodes -> R.string.seccenter_item_recovery_codes_why
        SafetyItem.RecoveryEmail -> R.string.seccenter_item_recovery_email_why
        SafetyItem.SecondDevice -> R.string.seccenter_item_second_device_why
        SafetyItem.PasswordHealth -> R.string.seccenter_item_password_health_why
        SafetyItem.RecoveryPhone -> R.string.seccenter_item_recovery_phone_why
        SafetyItem.AlertsReviewed -> R.string.seccenter_item_alerts_reviewed_why
    },
)

// ── Local flags (§4.3 B, §4.4) ───────────────────────────────────────────────

/**
 * The per-account local flags the spec names, in their own preferences file
 * (not wiped at sign-out: "the card has not been shown to this account in the
 * last 7 days" has to survive a sign-out and back in).
 */
object SafetyLocalFlags {
    private const val PREFS = "grapmail-safety"
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun celebratedKey(address: String) = "gm.safety.celebrated." + SecurityOverviewCache.accountKey(address)
    private fun lastShownKey(address: String) = "gm.safetyCard.lastShownAt." + SecurityOverviewCache.accountKey(address)
    private fun backgroundFetchKey(address: String) = "gm.safety.backgroundFetchAt." + SecurityOverviewCache.accountKey(address)
    private fun createdAtKey(address: String) = "gm.safety.accountCreatedAt." + SecurityOverviewCache.accountKey(address)

    fun celebrated(context: Context, address: String): Boolean = prefs(context).getBoolean(celebratedKey(address), false)
    fun setCelebrated(context: Context, address: String, value: Boolean) {
        if (celebrated(context, address) != value) prefs(context).edit().putBoolean(celebratedKey(address), value).apply()
    }

    fun nudgeLastShownAt(context: Context, address: String): Long = prefs(context).getLong(lastShownKey(address), 0L)
    fun markNudgeShown(context: Context, address: String, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(lastShownKey(address), at).apply()
    }

    fun backgroundFetchAt(context: Context, address: String): Long = prefs(context).getLong(backgroundFetchKey(address), 0L)
    fun markBackgroundFetch(context: Context, address: String, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(backgroundFetchKey(address), at).apply()
    }

    /** 0 when unknown. */
    fun accountCreatedAt(context: Context, address: String): Long = prefs(context).getLong(createdAtKey(address), 0L)
    fun setAccountCreatedAt(context: Context, address: String, at: Long) {
        if (at > 0L) prefs(context).edit().putLong(createdAtKey(address), at).apply()
    }

    /**
     * §4.4: the celebration plays when a refetch shows 100 and the previous
     * cached value was below 100, once per account; the flag is cleared when
     * the score drops below 100 so it can play again after it is re-earned.
     * Returns whether to play now (and records it).
     */
    fun shouldCelebrate(context: Context, address: String, previous: Int?, fresh: Int): Boolean {
        if (fresh < 100) {
            setCelebrated(context, address, false)
            return false
        }
        if (previous == null || previous >= 100) return false
        if (celebrated(context, address)) return false
        setCelebrated(context, address, true)
        return true
    }
}

// ── The ring ────────────────────────────────────────────────────────────────

/**
 * The score ring (§4.3 A): track in `stepInactive`, progress in the level
 * colour with round caps, the signature gradient at 100. Animates from
 * [animateFrom] to [value] over 450 ms EmphasizedDecelerate; reduce motion
 * shows the final value. One accessibility element with progress semantics.
 */
@Composable
fun SafetyRing(
    value: Int,
    level: SafetyLevel,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    stroke: Dp = 10.dp,
    animateFrom: Int? = null,
    showCenter: Boolean = true,
    /** Crossfades the centre number to a check (the §4.4 celebration). */
    showCheck: Boolean = false,
    checkScale: Float = 1f,
) {
    val colors = secColors()
    val reduced = rememberMotionReduced()
    val target = value.coerceIn(0, 100) / 100f
    val progress = remember { Animatable(((animateFrom ?: value).coerceIn(0, 100)) / 100f) }
    LaunchedEffect(target, reduced) {
        progress.animateTo(target, GMMotion.tween(reduced, EXTRA_LONG_MS, GMMotion.EmphasizedDecelerate))
    }
    val levelWord = safetyLevelWord(level)
    val description = stringResource(R.string.seccenter_ring_description, value.coerceIn(0, 100), levelWord)
    val tint = safetyLevelColor(level, colors)
    val full = value >= 100
    // The centre sits inside a fixed-size ring: it grows with the font setting
    // up to 130 %, never past the ring (200 % text would spill out of it). The
    // ring's own TalkBack label carries the value at any size.
    val shrink = (1.3f / LocalDensity.current.fontScale).coerceAtMost(1f)
    Box(
        modifier
            .size(size)
            .clearAndSetSemantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2f
            val arc = Size(this.size.width - strokePx, this.size.height - strokePx)
            drawArc(
                color = colors.stepInactive,
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arc,
                style = Stroke(strokePx, cap = StrokeCap.Round),
            )
            val sweep = 360f * progress.value
            if (sweep > 0f) {
                // Rotated so the sweep (and a sweep gradient's seam) starts at 12 o'clock.
                rotate(-90f) {
                    if (full) {
                        drawArc(
                            brush = Brush.sweepGradient(colors.signature + colors.signature.first()),
                            startAngle = 0f, sweepAngle = sweep, useCenter = false,
                            topLeft = Offset(inset, inset), size = arc,
                            style = Stroke(strokePx, cap = StrokeCap.Round),
                        )
                    } else {
                        drawArc(
                            color = tint,
                            startAngle = 0f, sweepAngle = sweep, useCenter = false,
                            topLeft = Offset(inset, inset), size = arc,
                            style = Stroke(strokePx, cap = StrokeCap.Round),
                        )
                    }
                }
            }
        }
        if (showCenter) {
            Crossfade(
                targetState = showCheck,
                animationSpec = tween(if (reduced) GMMotion.ShortMs else GMMotion.MediumMs),
                label = "safety-centre",
            ) { check ->
                if (check) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = colors.success,
                        modifier = Modifier.size(size * 0.42f).scale(checkScale),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            value.coerceIn(0, 100).toString(),
                            fontSize = (if (size >= 80.dp) 28f else 13f).times(shrink).sp,
                            lineHeight = (if (size >= 80.dp) 32f else 15f).times(shrink).sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            maxLines = 1,
                        )
                        if (size >= 80.dp) {
                            Text(
                                stringResource(R.string.seccenter_score_of_100),
                                style = Caption.copy(fontSize = (13f * shrink).sp, lineHeight = (18f * shrink).sp),
                                color = colors.textSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** `extraLong` (§1.10); GMMotion has no constant for it yet (Motion.kt is not this workstream's). */
internal const val EXTRA_LONG_MS = 450
/** `celebration` (§1.10). */
internal const val CELEBRATION_MS = 1200

// ── The header (§4.3 A) ─────────────────────────────────────────────────────

/**
 * The "Account safety" card: ring, level word, next-step line, then the
 * checklist collapsed to the top three todo rows with "See all {n} steps".
 *
 * [celebrate] plays the §4.4 sequence once per true value; the caller decides
 * (via [SafetyLocalFlags.shouldCelebrate]) after a refetch.
 */
@Composable
fun SafetyScoreHeader(
    overview: SecurityOverviewResponse?,
    loading: Boolean,
    onItem: (SafetyChecklistEntry) -> Unit,
    modifier: Modifier = Modifier,
    animateFrom: Int? = null,
    celebrate: Boolean = false,
    onCelebrationShown: () -> Unit = {},
    /** The hub's compact form: no checklist, the whole card opens the center. */
    onOpen: (() -> Unit)? = null,
    /** The Security Center's header: offers the one-time "safety score" tip on
     *  the ring (round8-spec §4.5). False everywhere else. */
    featureTip: Boolean = false,
) {
    val colors = secColors()
    val score = overview?.score
    if (score == null) {
        if (loading) SafetyHeaderSkeleton(modifier)
        return
    }
    val reduced = rememberMotionReduced()
    if (featureTip) SafetyScoreTipEffect()
    val value = score.value.coerceIn(0, 100)
    val level = safetyLevelOf(score.level, value)
    val entries = remember(overview) { visibleChecklist(overview) }
    val todo = entries.filter { !it.done }
    val steps = remember(overview, entries) { stepsToStrong(overview, entries) }

    var showCheck by remember { mutableStateOf(false) }
    val checkScale = remember { Animatable(1f) }
    val confetti = remember { Animatable(0f) }
    var celebrated by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(celebrate) {
        if (!celebrate) return@LaunchedEffect
        // 1. the ring fills to 100 (SafetyRing animates on its own, 450 ms)
        if (!reduced) delay(EXTRA_LONG_MS.toLong())
        // 2. the number crossfades to a check, 0.6 → 1 on the settle spring
        if (!reduced) checkScale.snapTo(0.6f)
        showCheck = true
        celebrated = true
        launch { checkScale.animateTo(1f, GMMotion.settle(reduced)) }
        // 4. haptic (not motion: plays under reduce motion too)
        GMHaptics.success()
        onCelebrationShown()
        // 3. 14 particles drift up and out over 1200 ms, then fade; none when reduced
        if (!reduced) {
            confetti.snapTo(0f)
            confetti.animateTo(1f, tween(CELEBRATION_MS, easing = LinearEasing))
        }
    }

    val nextLine = when {
        value >= 100 && (celebrated || showCheck) -> stringResource(R.string.seccenter_next_fully_protected)
        todo.isEmpty() -> stringResource(R.string.seccenter_next_all_done)
        level == SafetyLevel.Good && steps != null && steps > 0 ->
            pluralStringResource(R.plurals.seccenter_steps_to_strong, steps, steps)
        else -> stringResource(R.string.seccenter_next_start_with, safetyItemTitle(todo.first()))
    }
    val announcement = stringResource(R.string.seccenter_celebration_announcement)

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                FeatureTipAnchor(FeatureTipId.SafetyScore, enabled = featureTip) {
                    SafetyRing(
                        value = value,
                        level = level,
                        animateFrom = animateFrom,
                        showCheck = showCheck,
                        checkScale = checkScale.value,
                    )
                }
                if (!reduced && confetti.value > 0f && confetti.value < 1f) {
                    ConfettiBurst(progress = confetti.value, colors = colors.confetti, ringSize = 96.dp)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.seccenter_score_title),
                    style = Title,
                    color = colors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(2.dp))
                Text(safetyLevelWord(level), style = CaptionMedium, color = safetyLevelColor(level, colors))
                Spacer(Modifier.height(4.dp))
                Text(
                    nextLine,
                    style = Caption,
                    color = colors.textSecondary,
                    // The live region carries the §4.4 announcement when the line flips.
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        if (showCheck) contentDescription = announcement
                    },
                )
            }
        }
        if (onOpen == null && entries.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SafetyChecklist(entries = entries, onItem = onItem)
        }
        if (value >= 100 && celebrated) {
            Spacer(Modifier.height(12.dp))
            com.grapsee.grapmail.ui.artwork.Artwork(
                "ill_sec_hub_secure",
                modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).aspectRatio(2f),
            )
        }
    }
}

/**
 * round8-spec §4.5: the first Security Center visit offers the "Your account
 * safety score" tip on the ring (FeatureTipsStore enforces once-ever and
 * one-tip-per-session); leaving the screen closes it.
 */
@Composable
private fun SafetyScoreTipEffect() {
    val context = LocalContext.current
    val allowed = rememberFeatureTipsAllowed()
    LaunchedEffect(allowed) {
        if (allowed) FeatureTipsStore.onSecurityCenterVisited(context)
    }
    DisposableEffect(Unit) {
        onDispose { FeatureTipsStore.closeIfShowing(FeatureTipId.SafetyScore) }
    }
}

/** §4.2 rows, collapsed to the top three todo items with "See all {n} steps". */
@Composable
fun SafetyChecklist(entries: List<SafetyChecklistEntry>, onItem: (SafetyChecklistEntry) -> Unit) {
    val colors = secColors()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val todo = entries.filter { !it.done }
    val collapsed = if (todo.isNotEmpty()) todo.take(3) else emptyList()
    val shown = if (expanded || collapsed.isEmpty()) entries else collapsed
    Column {
        shown.forEach { entry -> SafetyChecklistRow(entry, onClick = { onItem(entry) }) }
        if (shown.size < entries.size || expanded) {
            Text(
                if (expanded) stringResource(R.string.seccenter_show_fewer_steps)
                else pluralStringResource(R.plurals.seccenter_see_all_steps, entries.size, entries.size),
                style = CaptionMedium,
                color = colors.primaryText,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 4.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
fun SafetyChecklistRow(entry: SafetyChecklistEntry, onClick: () -> Unit) {
    val colors = secColors()
    val title = safetyItemTitle(entry)
    val why = safetyItemWhy(entry.item)
    val label = if (entry.done) stringResource(R.string.seccenter_item_done_description, title)
    else stringResource(R.string.seccenter_item_todo_description, title, why, entry.dto.points)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row speaks once ("{title}. {why}. Worth {points} points."); its
        // pieces are hidden so TalkBack does not read them a second time.
        Row(Modifier.weight(1f).clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (entry.done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (entry.done) colors.successFill else colors.border,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = BodyMedium, color = colors.textPrimary)
                if (!entry.done) {
                    Text(why, style = Caption, color = colors.textSecondary)
                }
            }
            if (!entry.done) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.seccenter_points_pill, entry.dto.points),
                    style = CaptionMedium,
                    color = colors.onPrimaryContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.primaryContainer)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** §3.7 loading: a 96 ring outline and two text bars, in `softFill`. */
@Composable
fun SafetyHeaderSkeleton(modifier: Modifier = Modifier) {
    val colors = secColors()
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .padding(20.dp)
            .clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(96.dp)) {
            val strokePx = 10.dp.toPx()
            drawCircle(colors.softFill, radius = (size.minDimension - strokePx) / 2f, style = Stroke(strokePx))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonBone(Modifier.fillMaxWidth(0.6f), 14.dp)
            SkeletonBone(Modifier.fillMaxWidth(0.4f), 10.dp)
        }
    }
}

/**
 * §4.4 step 3: 14 particles drifting up and out from the ring, fading as they
 * go. Drawn around the ring's box (Compose does not clip a Canvas to its
 * bounds; the header card's own clip is the outer edge). Callers skip it under
 * reduce motion.
 */
@Composable
private fun ConfettiBurst(progress: Float, colors: List<Color>, ringSize: Dp) {
    Canvas(Modifier.size(ringSize)) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val start = size.minDimension * 0.30f
        val travel = size.minDimension * 0.55f
        val lift = 24.dp.toPx() * progress
        val alpha = (1f - progress).coerceIn(0f, 1f)
        val dot = 3.5.dp.toPx()
        for (i in 0 until 14) {
            // Spread over the upper 300°, so the burst reads as rising.
            val angle = Math.toRadians((-240.0 + i * (300.0 / 13.0)))
            val r = start + travel * progress * (0.75f + (i % 3) * 0.15f)
            val p = Offset(
                centre.x + (cos(angle) * r).toFloat(),
                centre.y + (sin(angle) * r).toFloat() - lift,
            )
            drawCircle(colors[i % colors.size].copy(alpha = alpha), radius = dot, center = p)
        }
    }
}

/** The mini ring the mailbox nudge carries (§4.3 B: 40, stroke 5). */
@Composable
fun SafetyMiniRing(value: Int, level: String?, modifier: Modifier = Modifier) {
    SafetyRing(
        value = value,
        level = safetyLevelOf(level, value),
        modifier = modifier,
        size = 40.dp,
        stroke = 5.dp,
        showCenter = true,
    )
}
