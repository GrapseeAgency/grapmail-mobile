package com.grapsee.grapmail.data

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google Play Billing for GrapMail plans.
 *
 * This object only talks to Play. It never decides what the account has: every
 * purchase it sees is handed to the server (`Api.billingVerifyGoogle`), which
 * asks Google, acknowledges it, and answers with the entitlement the screen shows.
 * A purchase is launched with the account reference the server gave, so a token
 * copied to another account is refused there.
 */
object PlayBilling {
    private var client: BillingClient? = null
    private val _updates = MutableSharedFlow<List<Purchase>>(extraBufferCapacity = 8)
    /** Purchases Play reported while the app was open (new, pending, or restored). */
    val updates: SharedFlow<List<Purchase>> get() = _updates

    private fun clientFor(context: Context): BillingClient =
        client ?: BillingClient.newBuilder(context.applicationContext)
            .setListener { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    _updates.tryEmit(purchases)
                }
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
            .also { client = it }

    /** Connected client, or null when Play is unavailable on this device. */
    suspend fun connect(context: Context): BillingClient? {
        val c = clientFor(context)
        if (c.isReady) return c
        val ok = suspendCancellableCoroutine { cont ->
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }
                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
        return if (ok) c else null
    }

    suspend fun products(context: Context, ids: List<String>): List<ProductDetails> {
        if (ids.isEmpty()) return emptyList()
        val c = connect(context) ?: return emptyList()
        val params = QueryProductDetailsParams.newBuilder().setProductList(ids.distinct().map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it)
                .setProductType(BillingClient.ProductType.SUBS).build()
        }).build()
        // Billing 8 ships its coroutine extensions in the separate `billing-ktx`
        // artifact; this file depends on `billing` alone, so the callback API is
        // wrapped here instead of pulling in another dependency.
        return suspendCancellableCoroutine { cont ->
            c.queryProductDetailsAsync(params) { _, result ->
                if (cont.isActive) cont.resume(result.productDetailsList.orEmpty())
            }
        }
    }

    /** Subscriptions this Play account already owns (for restore and upgrades). */
    suspend fun owned(context: Context): List<Purchase> {
        val c = connect(context) ?: return emptyList()
        val purchases = suspendCancellableCoroutine { cont ->
            c.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
            ) { _, list -> if (cont.isActive) cont.resume(list) }
        }
        return purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
    }

    /** The first offer's display price, e.g. "BDT 250.00/month". */
    fun priceOf(details: ProductDetails): String? {
        val phase = details.subscriptionOfferDetails?.firstOrNull()
            ?.pricingPhases?.pricingPhaseList?.lastOrNull() ?: return null
        val period = when (phase.billingPeriod) {
            "P1M" -> "/month"; "P1Y" -> "/year"; "P1W" -> "/week"; "P3M" -> "/3 months"; "P6M" -> "/6 months"
            else -> ""
        }
        return phase.formattedPrice + period
    }

    /**
     * Open Play's purchase sheet. [replacing] is the purchase token of a plan the
     * account already has, so Play switches plans instead of selling a second one.
     */
    fun launch(activity: Activity, details: ProductDetails, accountReference: String, replacing: String?): Boolean {
        val c = client ?: return false
        val offer = details.subscriptionOfferDetails?.firstOrNull() ?: return false
        val builder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details).setOfferToken(offer.offerToken).build()))
            .setObfuscatedAccountId(accountReference)
        if (replacing != null) {
            builder.setSubscriptionUpdateParams(
                BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                    .setOldPurchaseToken(replacing)
                    .setSubscriptionReplacementMode(
                        BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION)
                    .build())
        }
        return c.launchBillingFlow(activity, builder.build()).responseCode == BillingClient.BillingResponseCode.OK
    }
}
