package com.ahmetsudeys.dogalgazteklif.billing

import android.content.Context
import com.ahmetsudeys.dogalgazteklif.data.Prefs

/**
 * Single source of truth for "is the user allowed to use the app".
 *
 * Access is granted if EITHER:
 *  - there is an active Google Play subscription (verified by Play, cached in [Prefs]), OR
 *  - the on-device 7-day free trial is still running.
 *
 * This is intentionally synchronous so the launch screen can route instantly. The subscription part
 * reads a cached flag that [BillingManager] refreshes from Google Play on every screen entry.
 */
object EntitlementManager {

    fun isEntitled(context: Context): Boolean =
        Prefs.isSubscriptionActive(context) || Prefs.isTrialActive(context)

    /**
     * True for a genuinely new user: never started the trial, never paid, not subscribed now
     * (state A on the paywall). A lapsed subscriber is deliberately excluded — they must renew
     * rather than getting another free week.
     */
    fun canStartTrial(context: Context): Boolean =
        !Prefs.isTrialStarted(context) &&
            !Prefs.hasEverSubscribed(context) &&
            !Prefs.isSubscriptionActive(context)

    /** True when the free access is used up and there is no active subscription (state B on the paywall). */
    fun isTrialExpired(context: Context): Boolean =
        !canStartTrial(context) && !isEntitled(context)
}
