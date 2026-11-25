package com.grapsee.grapmail.ui.screens.security

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.NotMeRequest
import com.grapsee.grapmail.data.NotMeResultDto
import com.grapsee.grapmail.data.SecurityOverviewCache
import com.grapsee.grapmail.data.SecurityOverviewResponse
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.logic.GMHaptics
import com.grapsee.grapmail.ui.components.AccountScreen
import com.grapsee.grapmail.ui.components.AppTextField
import com.grapsee.grapmail.ui.components.ChipTone
import com.grapsee.grapmail.ui.components.FooterNote
import com.grapsee.grapmail.ui.components.GMAutofill
import com.grapsee.grapmail.ui.components.GMLayout
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.gmIsShortWindow
import com.grapsee.grapmail.ui.components.gmMaxWidth
import com.grapsee.grapmail.ui.theme.Body
import com.grapsee.grapmail.ui.theme.BodyMedium
import com.grapsee.grapmail.ui.theme.Caption
import com.grapsee.grapmail.ui.theme.H2
import kotlinx.coroutines.launch

/*
 * Round 8 — Workstream C: "This wasn't me" (docs/design/round8-spec.md §3.4).
 *
 * confirm sheet → POST /security/not-me (→ password step-up on 403) →
 * "Account secured" → change password (required mode) → "Review your recovery
 * options" → back to the Security Center, which refetches on resume.
 */

/** What the person said was not them: exactly one of a device or an event (§6.3). */
@Immutable
data class NotMeTarget(
    val deviceId: String? = null,
    val eventId: String? = null,
    /** The device label the copy names; null for an event with no device. */
    val deviceLabel: String? = null,
    /** How many of the signed-out sessions belong to the target itself. */
    val targetSessions: Int = 1,
) {
    fun request(proof: String?): NotMeRequest =
        if (deviceId != null) NotMeRequest(deviceId = deviceId, proof = proof)
        else NotMeRequest(eventId = eventId, proof = proof)
}

/** The result the "Account secured" screen reads (§3.4 step 4). */
@Immutable
data class NotMeOutcome(
    val deviceLabel: String?,
    val otherSessions: Int,
    val alreadyReported: Boolean,
)

/**
 * The hand-off between the sheet and the "Account secured" route. Only the
 * device label travels in the route (it is display text, like SEC_REVOKED's);
 * the counts ride here, as SecurityFlow carries the backup codes.
 */
object NotMeFlowState {
    @Volatile
    var lastOutcome: NotMeOutcome? = null

    fun outcomeOf(target: NotMeTarget, result: NotMeResultDto): NotMeOutcome = NotMeOutcome(
        deviceLabel = target.deviceLabel?.takeIf { it.isNotBlank() },
        otherSessions = if (target.deviceLabel.isNullOrBlank()) result.signedOutSessions.coerceAtLeast(0)
        else (result.signedOutSessions - target.targetSessions.coerceAtLeast(1)).coerceAtLeast(0),
        alreadyReported = result.alreadyReported,
    )
}

// ── Confirm sheet ───────────────────────────────────────────────────────────

/**
 * The §3.4 confirm sheet. It cannot be dismissed while the request runs; a
 * `403 STEP_UP_REQUIRED` turns it into the password step-up in place, and the
 * retry carries the proof. Every error plays [GMHaptics.failure].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotMeConfirmSheet(
    target: NotMeTarget,
    onDismiss: () -> Unit,
    onSecured: (NotMeOutcome) -> Unit,
    /** 404: the device is already gone — the caller refetches. */
    onTargetGone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = secColors()
    var busy by remember { mutableStateOf(false) }
    var stepUp by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var networkFailed by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // A swipe (or the scrim) cannot close the sheet mid-request.
        confirmValueChange = { it != SheetValue.Hidden || !busy },
    )

    suspend fun runSecure(proof: String?) {
        error = null
        networkFailed = false
        try {
            val session = SessionStore.snapshot(context)
                ?: throw IllegalStateException(context.getString(R.string.seccenter_err_generic))
            val resp = Api.reportNotMe(session.token, target.request(proof))
            if (!SessionStore.isCurrent(context, session.id)) return
            val result = resp.result ?: NotMeResultDto()
            SecurityOverviewCache.setPasswordChangeRequired(context, session.address, result.passwordChangeRequired)
            GMHaptics.success()
            val outcome = NotMeFlowState.outcomeOf(target, result)
            NotMeFlowState.lastOutcome = outcome
            onSecured(outcome)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ApiException) {
            when {
                e.code == "STEP_UP_REQUIRED" && proof == null -> {
                    // Not an error: the server wants the password first.
                    stepUp = true
                    password = ""
                    return
                }
                e.status == 404 || e.code == "NOT_FOUND" -> {
                    error = context.getString(R.string.seccenter_err_gone)
                    onTargetGone()
                }
                e.code == "CANNOT_TARGET_CURRENT" || e.status == 409 ->
                    error = context.getString(R.string.seccenter_err_current)
                e.status == 429 || e.code == "RATE_LIMITED" ->
                    error = context.getString(R.string.seccenter_err_rate_limited)
                else -> error = context.getString(R.string.seccenter_err_generic)
            }
            GMHaptics.failure()
        } catch (e: java.io.IOException) {
            error = context.getString(R.string.seccenter_err_network)
            networkFailed = true
            GMHaptics.failure()
        } catch (e: Exception) {
            error = context.getString(R.string.seccenter_err_generic)
            GMHaptics.failure()
        }
    }

    fun secure() {
        if (busy) return
        busy = true
        scope.launch {
            try { runSecure(proof = null) } finally { busy = false }
        }
    }

    fun confirmPassword() {
        if (busy) return
        if (password.isBlank()) {
            error = context.getString(R.string.seccenter_stepup_empty)
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                val session = SessionStore.snapshot(context)
                    ?: throw IllegalStateException(context.getString(R.string.seccenter_err_generic))
                val verified = Api.verifyPasswordForSecureAccount(session.token, password)
                val proof = verified.proof?.takeIf { it.isNotBlank() }
                if (proof == null) {
                    error = verified.error ?: verified.message ?: context.getString(R.string.seccenter_err_generic)
                    GMHaptics.failure()
                } else {
                    password = ""
                    runSecure(proof)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ApiException) {
                // WRONG_PASSWORD / TOO_MANY_ATTEMPTS carry the app's own wording already.
                error = e.message
                GMHaptics.failure()
            } catch (e: java.io.IOException) {
                error = context.getString(R.string.seccenter_err_network)
                GMHaptics.failure()
            } catch (e: Exception) {
                error = context.getString(R.string.seccenter_err_generic)
                GMHaptics.failure()
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        sheetMaxWidth = GMLayout.Form,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            if (!stepUp) {
                if (!gmIsShortWindow()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        com.grapsee.grapmail.ui.artwork.Artwork(
                            "ill_sec_alert",
                            modifier = Modifier.height(120.dp).aspectRatio(2f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    stringResource(R.string.seccenter_notme_title),
                    style = H2,
                    color = colors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(8.dp))
                val label = target.deviceLabel?.takeIf { it.isNotBlank() }
                val body = if (label != null) stringResource(R.string.seccenter_notme_body, label)
                else stringResource(R.string.seccenter_notme_body_no_device)
                Text(
                    buildAnnotatedString {
                        val at = if (label != null) body.indexOf(label) else -1
                        if (at < 0) append(body) else {
                            append(body.substring(0, at))
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.textPrimary)) { append(label!!) }
                            append(body.substring(at + label!!.length))
                        }
                    },
                    style = Body,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(14.dp))
                listOf(
                    if (label != null) stringResource(R.string.seccenter_notme_bullet_device, label)
                    else stringResource(R.string.seccenter_notme_bullet_no_device),
                    stringResource(R.string.seccenter_notme_bullet_others),
                    stringResource(R.string.seccenter_notme_bullet_password),
                ).forEach { line ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(10.dp))
                        Text(line, style = Body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    }
                }
                SheetError(error)
                Spacer(Modifier.height(18.dp))
                ErrorFillButton(
                    text = when {
                        busy -> stringResource(R.string.seccenter_notme_securing)
                        networkFailed -> stringResource(R.string.seccenter_try_again)
                        else -> stringResource(R.string.seccenter_notme_confirm)
                    },
                    enabled = !busy,
                    onClick = { secure() },
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.seccenter_stepup_title),
                    style = H2,
                    color = colors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(16.dp))
                AppTextField(
                    label = stringResource(R.string.seccenter_stepup_password),
                    value = password,
                    onValueChange = { password = it; error = null },
                    password = true,
                    autofill = GMAutofill.Password,
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = { confirmPassword() }),
                )
                SheetError(error)
                Spacer(Modifier.height(18.dp))
                GradientButton(
                    if (busy) stringResource(R.string.seccenter_notme_securing) else stringResource(R.string.seccenter_stepup_continue),
                    onClick = { confirmPassword() },
                    busy = busy,
                )
            }
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = { if (!busy) onDismiss() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.seccenter_cancel), style = BodyMedium, color = colors.primaryText) }
        }
    }
}

@Composable
private fun SheetError(error: String?) {
    if (error == null) return
    val colors = secColors()
    Spacer(Modifier.height(12.dp))
    Text(
        error,
        style = Caption,
        color = colors.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** `errorFill`, white text, full width, 54 (§3.4 "Secure my account"). */
@Composable
internal fun ErrorFillButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = secColors()
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) colors.errorFill else colors.errorFill.copy(alpha = 0.55f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onFill)
    }
}

// ── Account secured (§3.4 step 4) ───────────────────────────────────────────

/** The layout of SessionRevokedSuspiciousScreen (SEC_REVOKED_ALERT), with the not-me copy. */
@Composable
fun NotMeSecuredScreen(
    deviceLabel: String,
    onChangePassword: () -> Unit,
    onClose: () -> Unit,
) {
    val outcome = NotMeFlowState.lastOutcome
    val label = deviceLabel.takeIf { it.isNotBlank() } ?: outcome?.deviceLabel
    val others = outcome?.otherSessions ?: 0
    val body = when {
        outcome?.alreadyReported == true -> stringResource(R.string.seccenter_secured_already)
        label != null -> pluralStringResource(R.plurals.seccenter_secured_body, others, label, others)
        else -> pluralStringResource(R.plurals.seccenter_secured_body_no_device, others, others)
    }
    BackHandler { onClose() }
    AccountScreen(
        onBack = onClose,
        illustration = "ill_sec_revoked_suspicious",
        illustrationWidth = 170,
        title = stringResource(R.string.seccenter_secured_title),
        body = body,
        footer = {
            Column {
                GradientButton(stringResource(R.string.seccenter_change_password), onChangePassword)
                Spacer(Modifier.height(6.dp))
                FooterNote(text = stringResource(R.string.seccenter_banner_body), divider = true)
            }
        },
    )
}

// ── Change password, required mode (§3.4 step 4) ────────────────────────────

/**
 * The existing change-password screen, unchanged inside: its title stays
 * "Change password". Only leaving is intercepted — the top-bar back and the
 * system back both ask "Leave without changing your password?" first.
 */
@Composable
fun RequiredChangePasswordScreen(
    onLeave: () -> Unit,
    onForgot: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = secColors()
    var confirmLeave by remember { mutableStateOf(false) }
    BackHandler { confirmLeave = true }
    ChangePasswordScreen(
        afterSuspicious = false,
        onBack = { confirmLeave = true },
        onForgot = onForgot,
        onChanged = {
            // The server cleared password_change_required with the change (§6.2);
            // say so locally at once so the §3.6 banners go without a refetch.
            scope.launch {
                SessionStore.snapshot(context)?.let {
                    SecurityOverviewCache.setPasswordChangeRequired(context, it.address, false)
                }
                onChanged()
            }
        },
    )
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.seccenter_leave_title)) },
            text = { Text(stringResource(R.string.seccenter_leave_body), fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false }) {
                    Text(stringResource(R.string.seccenter_leave_stay), color = colors.primaryText)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false; onLeave() }) {
                    Text(stringResource(R.string.seccenter_leave_leave), color = colors.error)
                }
            },
            containerColor = colors.surface,
        )
    }
}

// ── Review your recovery options (§3.4 step 5) ──────────────────────────────

/**
 * One row each for recovery email, phone, recovery codes and passkeys, with
 * their status from the overview (Done / Not set) and the §4.2 deep links.
 * Reads the cached overview at once and refetches on every resume, so a row
 * fixed in a child screen reads Done on the way back.
 */
@Composable
fun RecoveryReviewScreen(
    onAction: (SafetyAction) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var overview by remember { mutableStateOf<SecurityOverviewResponse?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload += 1 }
    LaunchedEffect(reload) {
        if (reload == 0) return@LaunchedEffect
        val session = SessionStore.snapshot(context) ?: return@LaunchedEffect
        if (overview == null) overview = SecurityOverviewCache.read(context, session.address)?.overview
        runCatching { Api.securityOverview(session.token) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()
            ?.let { fresh ->
                if (SessionStore.isCurrent(context, session.id)) {
                    SecurityOverviewCache.publish(context, session.address, fresh)
                    overview = fresh
                }
            }
    }
    val rows = listOf(
        Triple(SafetyItem.RecoveryEmail, Icons.Rounded.Email, R.string.seccenter_review_recovery_email),
        Triple(SafetyItem.RecoveryPhone, Icons.Rounded.PhoneAndroid, R.string.seccenter_review_phone),
        Triple(SafetyItem.RecoveryCodes, Icons.Rounded.Password, R.string.seccenter_review_recovery_codes),
        Triple(SafetyItem.Passkey, Icons.Rounded.Fingerprint, R.string.seccenter_review_passkeys),
    )
    BackHandler { onDone() }
    AccountScreen(
        onBack = onDone,
        illustration = "ill_sec_secure",
        illustrationWidth = 150,
        title = stringResource(R.string.seccenter_review_title),
        body = stringResource(R.string.seccenter_review_body),
        footer = { GradientButton(stringResource(R.string.seccenter_done), onDone) },
    ) {
        Column(Modifier.gmMaxWidth(GMLayout.Form), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            rows.forEachIndexed { index, (item, icon, title) ->
                val dto = overview?.checklist?.firstOrNull { it.id == item.key }
                val done = dto?.state == "done"
                val known = dto != null && (dto.state == "done" || dto.state == "todo")
                if (index > 0) RowDivider()
                SecurityRow(
                    icon = icon,
                    title = stringResource(title),
                    chip = when {
                        overview == null -> null
                        !known -> stringResource(R.string.seccenter_review_unknown)
                        done -> stringResource(R.string.seccenter_review_done_chip)
                        else -> stringResource(R.string.seccenter_review_not_set)
                    },
                    chipTone = if (done) ChipTone.Success else ChipTone.Warning,
                    onClick = { onAction(actionFor(item, dto?.action, done)) },
                )
            }
        }
    }
}

// ── Password-change-required banner (§3.6) ──────────────────────────────────

/**
 * Full width, `errorContainer`, radius 16, padding 16. Not dismissible: it goes
 * when the server flag clears. Shared by the Security Center and the mailbox.
 */
@Composable
fun PasswordChangeRequiredBanner(onChangePassword: () -> Unit, modifier: Modifier = Modifier) {
    val colors = secColors()
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.errorContainer)
            .padding(16.dp),
    ) {
        Text(
            stringResource(R.string.seccenter_banner_title),
            style = BodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.seccenter_banner_body), style = Caption, color = colors.textSecondary)
        Spacer(Modifier.height(10.dp))
        ErrorFillButton(
            text = stringResource(R.string.seccenter_change_password),
            enabled = true,
            onClick = onChangePassword,
            modifier = Modifier.heightIn(min = 44.dp),
        )
    }
}
