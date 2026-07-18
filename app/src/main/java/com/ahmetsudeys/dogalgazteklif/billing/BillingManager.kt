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

    /** Result of an explicit "restore purchases" request (see [refreshPurchases]). */
    enum class RestoreOutcome {
        /** Play reported an active subscription; the user is now entitled. */
        RESTORED,
        /** Play responded, but there is no active subscription on this account. */
        NONE,
        /** Play could not be reached (offline / not signed in / billing unavailable). */
        UNAVAILABLE
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var destroyed = false

    // On-demand connection handling. A single connection attempt may have several waiters
    // (e.g. initial start + a restore tap while still connecting); they are all notified once.
    private var connecting = false
    private val pendingConnect = mutableListOf<(ready: Boolean) -> Unit>()

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
        connect { ready ->
            if (destroyed) return@connect
            if (ready) {
                queryPurchases()
                queryPlans()
            } else {
                post { listener.onBillingUnavailable() }
            }
        }
    }

    /**
     * Explicitly re-checks whether Play reports an active subscription — the "restore purchases"
     * action. Reconnects first if the client is not ready, so it works even when the initial
     * connection dropped or never completed. [onResult] is always delivered once, on the main
     * thread, with a definitive [RestoreOutcome] — it never silently hangs.
     */
    fun refreshPurchases(onResult: ((RestoreOutcome) -> Unit)? = null) {
        connect { ready ->
            if (destroyed) return@connect
            if (!ready) {
                onResult?.let { cb -> post { cb(RestoreOutcome.UNAVAILABLE) } }
                return@connect
            }
            queryPurchases { success, active ->
                val outcome = when {
                    !success -> RestoreOutcome.UNAVAILABLE
                    active -> RestoreOutcome.RESTORED
                    else -> RestoreOutcome.NONE
                }
                onResult?.let { cb -> post { cb(outcome) } }
            }
        }
    }

    /**
     * Ensures the billing client is connected, then invokes [onReady] with whether it is usable.
     * Safe to call repeatedly: concurrent callers share a single in-flight connection attempt.
     */
    private fun connect(onReady: (ready: Boolean) -> Unit) {
        if (destroyed) return
        if (billingClient.isReady) {
            onReady(true)
            return
        }
        pendingConnect.add(onReady)
        if (connecting) return
        connecting = true
        runCatching {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    connecting = false
                    if (destroyed) return
                    flushPending(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    // The current attempt ended without a usable client; notify waiters so they
                    // can surface an error instead of hanging. A later call reconnects on demand.
                    connecting = false
                    if (!destroyed) flushPending(false)
                }
            })
        }.onFailure {
            connecting = false
            flushPending(false)
        }
    }

    private fun flushPending(ready: Boolean) {
        val callbacks = pendingConnect.toList()
        pendingConnect.clear()
        callbacks.forEach { it(ready) }
    }

    private fun queryPurchases(onResult: ((success: Boolean, active: Boolean) -> Unit)? = null) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (destroyed) return@queryPurchasesAsync
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val active = handlePurchases(purchases, fromPurchaseFlow = false)
                onResult?.invoke(true, active)
            } else {
                onResult?.invoke(false, false)
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>, fromPurchaseFlow: Boolean): Boolean {
        val activePurchase = purchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val active = activePurchase != null
        purchases.forEach { acknowledgeIfNeeded(it) }
        Prefs.setSubscriptionActive(appContext, active)
        // Keep the purchase date so the welcome screen can show days left in the billing period.
        if (activePurchase != null) {
            Prefs.setSubscriptionPurchaseTime(appContext, activePurchase.purchaseTime)
        } else {
            Prefs.clearSubscriptionDetails(appContext)
        }
        post { listener.onEntitlementChanged(active) }
        return active
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
        // Play's purchase record does not say which base plan was bought, so remember the period now.
        Prefs.setSubscriptionPeriodIso(appContext, plan.billingPeriodIso)
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
