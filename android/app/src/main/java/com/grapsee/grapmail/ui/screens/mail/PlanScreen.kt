package com.grapsee.grapmail.ui.screens.mail

import com.grapsee.grapmail.ui.components.gmPrefersStackedRows
import com.grapsee.grapmail.ui.components.GMLayout
import androidx.compose.material3.minimumInteractiveComponentSize
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.BillingEntitlementDto
import com.grapsee.grapmail.data.PlayBilling
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.ui.components.DetailSkeleton
import com.grapsee.grapmail.ui.components.ErrorState
import com.grapsee.grapmail.ui.components.OfflineBanner
import com.grapsee.grapmail.ui.theme.LocalPalette
import kotlinx.coroutines.launch

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Your plan: what it allows, what this month has used, and Google Play's own
 * purchase sheet for the paid plans.
 *
 * Everything on this screen comes from the server (`GET /billing/entitlement`).
 * A purchase changes nothing here until the server has checked it with Google
 * (`POST /billing/google/verify`), so a purchase that Play reports but the server
 * has not confirmed shows as "confirming", never as the new plan.
 */
@Composable
fun PlanScreen(onBack: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ent by remember { mutableStateOf<BillingEntitlementDto?>(null) }
    var products by remember { mutableStateOf<Map<String, ProductDetails>>(emptyMap()) }
    var owned by remember { mutableStateOf<List<Purchase>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }
    var noteBad by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var playMissing by remember { mutableStateOf(false) }
    // The entitlement read failed with nothing on screen: draw the error
    // state with Retry instead of a "Loading" line that never ends.
    var loadFailed by remember { mutableStateOf(false) }

    suspend fun verify(purchases: List<Purchase>) {
        val token = SessionStore.token(context) ?: return
        for (p in purchases) {
            if (p.purchaseState == Purchase.PurchaseState.PENDING) {
                note = context.getString(R.string.mail_plan_pending)
                noteBad = false
                continue
            }
            if (p.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            val productId = p.products.firstOrNull() ?: continue
            note = context.getString(R.string.mail_plan_confirming); noteBad = false
            runCatching { Api.billingVerifyGoogle(token, productId, p.purchaseToken) }
                .onSuccess { ent = it; note = context.getString(R.string.mail_plan_youre_on, it.planName); noteBad = false }
                .onFailure { note = it.message ?: context.getString(R.string.mail_plan_verify_failed); noteBad = true }
        }
    }

    suspend fun reload() {
        loadFailed = false
        val token = SessionStore.token(context)
        if (token == null) { loadFailed = ent == null; return }
        runCatching { Api.billingEntitlement(token) }
            .onSuccess { ent = it; if (noteBad) note = null }
            .onFailure {
                note = it.message ?: context.getString(R.string.mail_plan_read_failed); noteBad = true
                loadFailed = ent == null
            }
        val ids = ent?.plans?.flatMap { it.playProducts }.orEmpty()
        val details = PlayBilling.products(context, ids)
        playMissing = ids.isNotEmpty() && PlayBilling.connect(context) == null
        products = details.associateBy { it.productId }
        owned = PlayBilling.owned(context)
        // Restore: a purchase Play knows about that the server never confirmed
        // (the app closed mid-purchase, or another device bought it).
        val unconfirmed = owned.filter { !it.isAcknowledged }
        if (unconfirmed.isNotEmpty()) verify(unconfirmed)
    }

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(Unit) { PlayBilling.updates.collect { verify(it); owned = PlayBilling.owned(context) } }

    val largeText = gmPrefersStackedRows()
    MailScaffold(stringResource(R.string.mail_plan_title), onBack, maxContentWidth = GMLayout.Form) {
        OfflineBanner()
        // First load and a failed first load are drawn outside the scrolling
        // column: the shared states size and scroll themselves.
        if (ent == null) {
            if (loadFailed) {
                ErrorState(onRetry = { scope.launch { reload() } },
                    message = note ?: stringResource(R.string.mail_plan_read_failed))
            } else {
                DetailSkeleton(label = stringResource(R.string.mail_common_loading))
            }
            return@MailScaffold
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            note?.let {
                Text(it, fontSize = 13.sp, color = if (noteBad) palette.error else palette.success,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            val e = ent
            if (e == null) return@Column

            SectionLabel(stringResource(R.string.mail_plan_current), palette)
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(e.planName, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                val renewal = e.expiresAt?.take(10)
                if (e.plan != "free" && renewal != null) {
                    Text(if (e.autoRenewing) stringResource(R.string.mail_plan_renews, renewal)
                        else stringResource(R.string.mail_plan_ends, renewal),
                        fontSize = 13.sp, color = palette.textSecondary)
                }
                if ("subscriber" in e.badges) {
                    Text(stringResource(R.string.mail_plan_badge_note),
                        fontSize = 12.sp, color = palette.textTertiary, modifier = Modifier.padding(top = 4.dp))
                }
            }

            SectionLabel(stringResource(R.string.mail_plan_this_month), palette)
            Column(Modifier.padding(horizontal = 20.dp)) {
                val a = e.allowance
                Text(stringResource(R.string.mail_plan_used_of, a.used, a.monthlyRecipients),
                    fontSize = 15.sp, color = palette.textPrimary)
                LinearProgressIndicator(
                    progress = { if (a.monthlyRecipients > 0) (a.used.toFloat() / a.monthlyRecipients).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Text(stringResource(R.string.mail_plan_counts_note, a.resetsAt.take(10)), fontSize = 12.sp, color = palette.textTertiary)
            }

            SectionLabel(stringResource(R.string.mail_plan_plans), palette)
            if (playMissing) {
                Text(stringResource(R.string.mail_plan_play_missing),
                    fontSize = 13.sp, color = palette.textSecondary, modifier = Modifier.padding(horizontal = 20.dp))
            }
            e.plans.forEach { plan ->
                val details = plan.playProducts.firstNotNullOfOrNull { products[it] }
                val current = plan.id == e.plan
                Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth()
                    .border(1.dp, if (current) palette.primarySolid else palette.border, RoundedCornerShape(14.dp))
                    .padding(16.dp)) {
                    val price = if (plan.id == "free") stringResource(R.string.mail_plan_free)
                        else details?.let { PlayBilling.priceOf(it) } ?: "—"
                    // At large text the price goes under the name rather than
                    // squeezing it; at the default size it sits on the right.
                    if (largeText) {
                        Text(plan.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary)
                        Text(price, fontSize = 15.sp, color = palette.textSecondary)
                    } else Row {
                        Text(plan.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary, modifier = Modifier.weight(1f))
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 8.dp))
                        Text(price, fontSize = 15.sp, color = palette.textSecondary)
                    }
                    Text(pluralStringResource(R.plurals.mail_plan_recipients_month, plan.monthlyRecipients, plan.monthlyRecipients), fontSize = 13.sp,
                        color = palette.textSecondary, modifier = Modifier.padding(top = 2.dp))
                    val sheetFailed = stringResource(R.string.mail_plan_sheet_failed)
                    when {
                        current -> Text(stringResource(R.string.mail_plan_title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = palette.primarySolid, modifier = Modifier.padding(top = 10.dp))
                        plan.id == "free" -> {}
                        details == null -> Text(if (playMissing) stringResource(R.string.mail_plan_not_available_here)
                            else stringResource(R.string.mail_plan_not_available_yet),
                            fontSize = 13.sp, color = palette.textTertiary, modifier = Modifier.padding(top = 10.dp))
                        else -> Text(if (e.plan == "free") stringResource(R.string.mail_plan_subscribe)
                            else stringResource(R.string.mail_plan_switch_to, plan.name),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = if (busy) palette.textTertiary else palette.primarySolid,
                            // 48dp target; it replaces the 10dp gap above.
                            modifier = Modifier.minimumInteractiveComponentSize()
                                .clickable(enabled = !busy, role = androidx.compose.ui.semantics.Role.Button) {
                                val activity = context.activity() ?: return@clickable
                                busy = true
                                scope.launch {
                                    val replacing = owned.firstOrNull()?.purchaseToken
                                    if (!PlayBilling.launch(activity, details, e.playAccountReference, replacing)) {
                                        note = sheetFailed; noteBad = true
                                    }
                                    busy = false
                                }
                            })
                    }
                }
            }

            if (e.plan != "free") {
                Text(stringResource(R.string.mail_plan_manage), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = palette.primarySolid,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                        .minimumInteractiveComponentSize()
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                        val uri = Uri.parse("https://play.google.com/store/account/subscriptions" +
                            (e.productId?.let { "?sku=$it&package=${context.packageName}" } ?: ""))
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    })
            }
            Text(stringResource(R.string.mail_plan_prices_note), fontSize = 12.sp, color = palette.textTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}
