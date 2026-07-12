package com.ahmetsudeys.dogalgazteklif.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.ahmetsudeys.dogalgazteklif.data.Prefs

/**
 * Thin wrapper around Google Play Billing for the single subscription product used by the app.
 *
 * The product ([PRODUCT_ID]) has two base plans configured in Play Console: a monthly and a yearly
 * plan. Their IDs below MUST match the Play Console exactly, otherwise no plans will load.
 *
 * The free trial is NOT a Play trial offer — it is handled on-device (see [Prefs]/[EntitlementManager]).
 * So each base plan here is a plain recurring subscription with a single pricing phase.
 *
 * Owned by a Fragment: create in onViewCreated, call [start], and call [destroy] in onDestroyView.
 * All [Listener] callbacks are delivered on the main thread.
 */
class BillingManager(
    context: Context,
    private val listener: Listener
) {
    /** A purchasable plan, resolved from Play, ready to show and to launch a purchase with. */
    data class PlanInfo(
        val basePlanId: String,
        val productDetails: ProductDetails,
        val offerToken: String,
        val formattedPrice: String,
        val billingPeriodIso: String
    )

    interface Listener {
        /** Verified subscription state (from a purchase query or a fresh purchase). */
        fun onEntitlementChanged(subscribed: Boolean)
        /** The monthly/yearly plans are ready to display. */
        fun onPlansLoaded(plans: List<PlanInfo>)
        /** A purchase attempt did not complete. [userCancelled] distinguishes a benign cancel. */
        fun onPurchaseFailed(userCancelled: Boolean, message: String?)
        /** Google Play billing is not available on this device / account. */
        fun onBillingUnavailable()
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var destroyed = false

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases != null) handlePurchases(purchases, fromPurchaseFlow = true)
            }
            BillingClient.BillingResponseCode.USER_CANCELED ->
                post { listener.onPurchaseFailed(userCancelled = true, message = null) }
            else ->
                post { listener.onPurchaseFailed(userCancelled = false, message = result.debugMessage) }
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    /** Connects to Play, then refreshes purchases and loads the plans. */
    fun start() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (destroyed) return
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPurchases()
                    queryPlans()
                } else {
                    post { listener.onBillingUnavailable() }
                }
            }

            override fun onBillingServiceDisconnected() {
                // Best-effort single reconnect; if it keeps failing the paywall shows an error state.
                if (!destroyed) runCatching { billingClient.startConnection(this) }
            }
        })
    }

    /** Re-checks whether Play reports an active subscription (used to auto-detect restores). */
    fun refreshPurchases() {
        if (billingClient.isReady) queryPurchases()
    }

    private fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (destroyed) return@queryPurchasesAsync
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases, fromPurchaseFlow = false)
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>, fromPurchaseFlow: Boolean) {
        val active = purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        purchases.forEach { acknowledgeIfNeeded(it) }
        Prefs.setSubscriptionActive(appContext, active)
        post { listener.onEntitlementChanged(active) }
    }

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { /* best-effort; Play retries via re-query */ }
    }

    private fun queryPlans() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()

        billingClient.queryProductDetailsAsync(params) { result, productDetailsList ->
            if (destroyed) return@queryProductDetailsAsync
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                post { listener.onBillingUnavailable() }
                return@queryProductDetailsAsync
            }
            val details = productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
            if (details == null) {
                post { listener.onPlansLoaded(emptyList()) }
                return@queryProductDetailsAsync
            }
            val plans = buildPlans(details)
            post { listener.onPlansLoaded(plans) }
        }
    }

    private fun buildPlans(details: ProductDetails): List<PlanInfo> {
        val offers = details.subscriptionOfferDetails ?: return emptyList()
        return listOf(BASE_PLAN_MONTHLY, BASE_PLAN_YEARLY).mapNotNull { basePlanId ->
            // For a base plan with no promo offer there is exactly one recurring pricing phase.
            val offer = offers
                .filter { it.basePlanId == basePlanId }
                .minByOrNull { it.pricingPhases.pricingPhaseList.size }
                ?: return@mapNotNull null
            val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
            PlanInfo(
                basePlanId = basePlanId,
                productDetails = details,
                offerToken = offer.offerToken,
                formattedPrice = phase.formattedPrice,
                billingPeriodIso = phase.billingPeriod
            )
        }
    }

    /** Opens Google Play's purchase sheet for the given plan. */
    fun launchPurchase(activity: Activity, plan: PlanInfo) {
        if (!billingClient.isReady) {
            listener.onPurchaseFailed(userCancelled = false, message = "Billing not ready")
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(plan.productDetails)
            .setOfferToken(plan.offerToken)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        billingClient.launchBillingFlow(activity, flowParams)
    }

    fun destroy() {
        destroyed = true
        runCatching { billingClient.endConnection() }
    }

    private fun post(block: () -> Unit) {
        main.post { if (!destroyed) block() }
    }

    companion object {
        /** Subscription product ID — must match Play Console. */
        const val PRODUCT_ID = "fiyatla_pro"
        /** Base plan IDs — must match Play Console. */
        const val BASE_PLAN_MONTHLY = "fiyatla-pro-monthly"
        const val BASE_PLAN_YEARLY = "fiyatla-pro-yearly"
    }
}
