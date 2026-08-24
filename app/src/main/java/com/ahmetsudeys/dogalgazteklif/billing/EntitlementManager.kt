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
        canStartTrial(
            trialStarted = Prefs.isTrialStarted(context),
            everSubscribed = Prefs.hasEverSubscribed(context),
            subscribed = Prefs.isSubscriptionActive(context)
        )

    /**
     * The pure rule behind [canStartTrial], kept separate so the "one free week, ever" guarantee can
     * be tested directly (same reason as [Prefs.negativeOutcome]).
     *
     * [trialStarted] is the load-bearing term: after an uninstall it comes back from the Auto Backup
     * copy of `rota_prefs`, which is what stops delete → reinstall from minting a second trial.
     */
    fun canStartTrial(trialStarted: Boolean, everSubscribed: Boolean, subscribed: Boolean): Boolean =
        !trialStarted && !everSubscribed && !subscribed

    /** True when the free access is used up and there is no active subscription (state B on the paywall). */
    fun isTrialExpired(context: Context): Boolean =
        !canStartTrial(context) && !isEntitled(context)
}
