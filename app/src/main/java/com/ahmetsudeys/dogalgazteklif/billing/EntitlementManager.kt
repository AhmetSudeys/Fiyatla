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

    /** True when the user has never started the trial and is not subscribed (state A on the paywall). */
    fun canStartTrial(context: Context): Boolean =
        !Prefs.isTrialStarted(context) && !Prefs.isSubscriptionActive(context)

    /** True when the trial has been used up and there is no active subscription (state B on the paywall). */
    fun isTrialExpired(context: Context): Boolean =
        Prefs.isTrialStarted(context) && !Prefs.isTrialActive(context) && !Prefs.isSubscriptionActive(context)
}
