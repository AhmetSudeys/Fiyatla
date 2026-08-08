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
        /**
         * Effective paid-access state after a purchase query or a fresh purchase. This is the value
         * to route on — it already accounts for the verification grace in [Prefs.onPlayReportsInactive],
         * so a single unreliable "no subscription" answer from Play does not read as `false` here.
         */
        fun onEntitlementChanged(subscribed: Boolean)
        /** The monthly/yearly plans are ready to display. */
        fun onPlansLoaded(plans: List<PlanInfo>)
        /** A purchase attempt did not complete. [userCancelled] distinguishes a benign cancel. */
        fun onPurchaseFailed(userCancelled: Boolean, message: String?)
        /** Google Play billing is not available on this device / account. */
        fun onBillingUnavailable()
        /**
         * Play is holding a purchase in [Purchase.PurchaseState.PENDING] (a slow payment method).
         * Access is not granted yet; the user should be told to wait rather than left staring at an
         * unexplained paywall.
         */
        fun onPurchasePending() {}
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
    /** Set by [destroy]: stop delivering callbacks to the (gone) owner. */
    private var destroyed = false
    /** Set once [billingClient] has actually been shut down; no further calls may be issued. */
    private var connectionClosed = false

    // Purchase tokens with an acknowledgement sequence in flight. Google auto-refunds and revokes any
    // subscription that is not acknowledged within 3 days, so the connection must outlive the owning
    // fragment until these drain (see [destroy]). Only touched on the main thread.
    private val acknowledging = mutableSetOf<String>()
    private val hardCloseConnection = Runnable {
        acknowledging.clear()
        closeConnection()
    }

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
        // Play Billing 8+: the client re-establishes a dropped service connection by itself, so a
        // query issued right after a disconnect no longer fails outright.
        .enableAutoServiceReconnection()
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
        if (destroyed || connectionClosed) return
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

    /**
     * Folds a purchase list from Play into the local entitlement cache and returns the resulting
     * *effective* access (which, on a negative answer, may still be `true` — see
     * [Prefs.onPlayReportsInactive]).
     */
    private fun handlePurchases(purchases: List<Purchase>, fromPurchaseFlow: Boolean): Boolean {
        val activePurchase = purchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        purchases.forEach { p -> main.post { acknowledgeIfNeeded(p) } }

        val entitled = if (activePurchase != null) {
            Prefs.onPlayReportsActive(appContext, activePurchase.purchaseTime)
            true
        } else {
            Prefs.onPlayReportsInactive(appContext)
        }
        post { listener.onEntitlementChanged(entitled) }
        // Delivered *after* the entitlement callback so the paywall's own status handling cannot
        // overwrite it: nothing is owned yet, but money is on its way.
        if (activePurchase == null && purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }) {
            post { listener.onPurchasePending() }
        }
        return entitled
    }

    /**
     * Acknowledges a purchase, retrying on failure. Google **auto-refunds and revokes** a
     * subscription that has not been acknowledged within 3 days, so this must not be fire-and-forget:
     * the result is checked, retried with backoff, and [destroy] holds the connection open until the
     * sequence finishes. Idempotent per purchase token.
     */
    private fun acknowledgeIfNeeded(purchase: Purchase, attempt: Int = 0) {
        if (connectionClosed) return
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (purchase.isAcknowledged) return
        val token = purchase.purchaseToken
        if (attempt == 0 && !acknowledging.add(token)) return // already being acknowledged

        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(token)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            main.post {
                val retriable = result.responseCode != BillingClient.BillingResponseCode.OK &&
                    attempt + 1 < MAX_ACK_ATTEMPTS &&
                    !connectionClosed
                if (retriable) {
                    main.postDelayed({ acknowledgeIfNeeded(purchase, attempt + 1) }, ACK_RETRY_MS)
                } else {
                    acknowledging.remove(token)
                    closeConnectionIfDrained()
                }
            }
        }
    }

    private fun queryPlans() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()

        // Play Billing 8+ hands back a QueryProductDetailsResult (fetched + unfetched products)
        // instead of a bare list.
        billingClient.queryProductDetailsAsync(params) { result, queryResult ->
            if (destroyed) return@queryProductDetailsAsync
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                post { listener.onBillingUnavailable() }
                return@queryProductDetailsAsync
            }
            val details = queryResult.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
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
        if (connectionClosed || !billingClient.isReady) {
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

    /**
     * Detaches the owner (no further [Listener] callbacks) and closes the Play connection.
     *
     * The connection is *not* torn down while an acknowledgement is still in flight: ending it there
     * would cancel the in-flight IPC, and a purchase left unacknowledged for 3 days is automatically
     * refunded and revoked by Google. [ACK_DRAIN_TIMEOUT_MS] bounds the wait so the client can never
     * be leaked.
     */
    fun destroy() {
        destroyed = true
        if (acknowledging.isEmpty()) {
            closeConnection()
        } else {
            main.postDelayed(hardCloseConnection, ACK_DRAIN_TIMEOUT_MS)
        }
    }

    private fun closeConnectionIfDrained() {
        if (destroyed && acknowledging.isEmpty()) closeConnection()
    }

    private fun closeConnection() {
        if (connectionClosed) return
        connectionClosed = true
        main.removeCallbacks(hardCloseConnection)
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

        /** Acknowledgement attempts before giving up for this session (the next launch re-queries). */
        private const val MAX_ACK_ATTEMPTS = 4
        private const val ACK_RETRY_MS = 2_000L
        /** Upper bound on how long [destroy] keeps the connection alive to drain acknowledgements. */
        private const val ACK_DRAIN_TIMEOUT_MS = 20_000L
    }
}
