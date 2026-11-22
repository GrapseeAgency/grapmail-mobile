package com.grapsee.grapmail.ui.screens.mail

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.BillingEntitlementDto
import com.grapsee.grapmail.data.PlayBilling
import com.grapsee.grapmail.data.SessionStore
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

    suspend fun verify(purchases: List<Purchase>) {
        val token = SessionStore.token(context) ?: return
        for (p in purchases) {
            if (p.purchaseState == Purchase.PurchaseState.PENDING) {
                note = "Google Play is still processing your payment. Your plan changes when it completes."
                noteBad = false
                continue
            }
            if (p.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            val productId = p.products.firstOrNull() ?: continue
            note = "Confirming your purchase…"; noteBad = false
            runCatching { Api.billingVerifyGoogle(token, productId, p.purchaseToken) }
                .onSuccess { ent = it; note = "You're on ${it.planName}."; noteBad = false }
                .onFailure { note = it.message ?: "The purchase could not be confirmed yet. It will be retried."; noteBad = true }
        }
    }

    suspend fun reload() {
        val token = SessionStore.token(context) ?: return
        runCatching { Api.billingEntitlement(token) }
            .onSuccess { ent = it }
            .onFailure { note = it.message ?: "Your plan could not be read."; noteBad = true }
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

    MailScaffold("Your plan", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            note?.let {
                Text(it, fontSize = 13.sp, color = if (noteBad) palette.error else palette.success,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            val e = ent
            if (e == null) {
                Text("Loading…", fontSize = 14.sp, color = palette.textTertiary,
                    modifier = Modifier.padding(20.dp))
                return@Column
            }

            SectionLabel("Current plan", palette)
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(e.planName, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                val renewal = e.expiresAt?.take(10)
                if (e.plan != "free" && renewal != null) {
                    Text(if (e.autoRenewing) "Renews $renewal" else "Ends $renewal — it won't renew",
                        fontSize = 13.sp, color = palette.textSecondary)
                }
                if ("subscriber" in e.badges) {
                    Text("Your messages show a Subscriber badge to other GrapMail users. " +
                        "It means you support GrapMail; it is not identity verification.",
                        fontSize = 12.sp, color = palette.textTertiary, modifier = Modifier.padding(top = 4.dp))
                }
            }

            SectionLabel("This month", palette)
            Column(Modifier.padding(horizontal = 20.dp)) {
                val a = e.allowance
                Text("${a.used} of ${a.monthlyRecipients} outside recipients",
                    fontSize = 15.sp, color = palette.textPrimary)
                LinearProgressIndicator(
                    progress = { if (a.monthlyRecipients > 0) (a.used.toFloat() / a.monthlyRecipients).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Text("Counts people outside GrapMail. Mail to GrapMail addresses is never limited. " +
                    "Resets ${a.resetsAt.take(10)}.", fontSize = 12.sp, color = palette.textTertiary)
            }

            SectionLabel("Plans", palette)
            if (playMissing) {
                Text("Google Play isn't available on this device, so plans can't be bought here.",
                    fontSize = 13.sp, color = palette.textSecondary, modifier = Modifier.padding(horizontal = 20.dp))
            }
            e.plans.forEach { plan ->
                val details = plan.playProducts.firstNotNullOfOrNull { products[it] }
                val current = plan.id == e.plan
                Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth()
                    .border(1.dp, if (current) palette.primarySolid else palette.border, RoundedCornerShape(14.dp))
                    .padding(16.dp)) {
                    Row {
                        Text(plan.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary, modifier = Modifier.weight(1f))
                        Text(if (plan.id == "free") "Free" else details?.let { PlayBilling.priceOf(it) } ?: "—",
                            fontSize = 15.sp, color = palette.textSecondary)
                    }
                    Text("${plan.monthlyRecipients} outside recipients a month", fontSize = 13.sp,
                        color = palette.textSecondary, modifier = Modifier.padding(top = 2.dp))
                    when {
                        current -> Text("Your plan", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = palette.primarySolid, modifier = Modifier.padding(top = 10.dp))
                        plan.id == "free" -> {}
                        details == null -> Text(if (playMissing) "Not available here" else "Not available yet",
                            fontSize = 13.sp, color = palette.textTertiary, modifier = Modifier.padding(top = 10.dp))
                        else -> Text(if (e.plan == "free") "Subscribe" else "Switch to ${plan.name}",
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = if (busy) palette.textTertiary else palette.primarySolid,
                            modifier = Modifier.padding(top = 10.dp).clickable(enabled = !busy) {
                                val activity = context.activity() ?: return@clickable
                                busy = true
                                scope.launch {
                                    val replacing = owned.firstOrNull()?.purchaseToken
                                    if (!PlayBilling.launch(activity, details, e.playAccountReference, replacing)) {
                                        note = "Google Play couldn't open the purchase sheet. Try again."; noteBad = true
                                    }
                                    busy = false
                                }
                            })
                    }
                }
            }

            if (e.plan != "free") {
                Text("Manage or cancel in Google Play", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = palette.primarySolid,
                    modifier = Modifier.padding(20.dp).clickable {
                        val uri = Uri.parse("https://play.google.com/store/account/subscriptions" +
                            (e.productId?.let { "?sku=$it&package=${context.packageName}" } ?: ""))
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    })
            }
            Text("Prices are set and charged by Google Play in your currency. GrapMail checks every " +
                "purchase with Google before your plan changes.", fontSize = 12.sp, color = palette.textTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}
