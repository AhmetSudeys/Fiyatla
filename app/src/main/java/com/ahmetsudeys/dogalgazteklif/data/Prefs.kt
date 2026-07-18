package com.ahmetsudeys.dogalgazteklif.data

import android.content.Context
import java.io.File
import java.util.Locale

object Prefs {
    private const val FILE_NAME = "rota_prefs"
    private const val KEY_COMPANY_NAME = "company_name"
    private const val KEY_COMPANY_LOGO_PATH = "company_logo_path"
    private const val KEY_LABOR_RATE = "labor_rate"
    private const val KEY_RADIATOR_RATE = "radiator_rate"
    private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"

    // --- Subscription / free-trial gating ---
    private const val KEY_TRIAL_START = "trial_start_millis"
    private const val KEY_TRIAL_LAST_SEEN = "trial_last_seen_millis"
    private const val KEY_SUB_ACTIVE = "subscription_active"
    private const val KEY_SUB_PURCHASE_MS = "subscription_purchase_millis"
    private const val KEY_SUB_PERIOD_ISO = "subscription_period_iso"

    /**
     * Entitlement state belongs to the device/Play account, not to the user's business data. It is
     * deliberately excluded from backups and preserved across a restore, so restoring a file can
     * never resurrect a used-up trial nor drop an active subscription.
     */
    val ENTITLEMENT_KEYS = setOf(
        KEY_TRIAL_START,
        KEY_TRIAL_LAST_SEEN,
        KEY_SUB_ACTIVE,
        KEY_SUB_PURCHASE_MS,
        KEY_SUB_PERIOD_ISO
    )

    /** Length of the free trial. 7 days in milliseconds. */
    const val TRIAL_DURATION_MS = 7L * 24 * 60 * 60 * 1000

    const val DEFAULT_LABOR_RATE = "400"
    const val DEFAULT_RADIATOR_RATE = "2600"

    /** True once the user has read + accepted the "data is stored only on this device" notice. */
    fun isDisclaimerAccepted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false)
    }

    fun setDisclaimerAccepted(context: Context) {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, true).apply()
    }

    /** Last-used labor unit rate (₺/m). Remembered across quotes. */
    fun getLaborRate(context: Context): String {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LABOR_RATE, DEFAULT_LABOR_RATE)?.trim().orEmpty().ifBlank { DEFAULT_LABOR_RATE }
    }

    fun setLaborRate(context: Context, rate: String) {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LABOR_RATE, rate.trim()).apply()
    }

    /** Last-used radiator unit rate (₺/m). Remembered across quotes. */
    fun getRadiatorRate(context: Context): String {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_RADIATOR_RATE, DEFAULT_RADIATOR_RATE)?.trim().orEmpty().ifBlank { DEFAULT_RADIATOR_RATE }
    }

    fun setRadiatorRate(context: Context, rate: String) {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RADIATOR_RATE, rate.trim()).apply()
    }

    fun getCompanyName(context: Context): String {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_COMPANY_NAME, "")?.trim().orEmpty()
    }

    fun setCompanyName(context: Context, name: String) {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_COMPANY_NAME, name.trim()).apply()
    }

    fun getCompanyLogoPath(context: Context): String {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_COMPANY_LOGO_PATH, "")?.trim().orEmpty()
    }

    fun setCompanyLogoPath(context: Context, absolutePath: String) {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_COMPANY_LOGO_PATH, absolutePath.trim()).apply()
    }

    fun getCompanyLogoFile(context: Context): File? {
        val path = getCompanyLogoPath(context)
        if (path.isBlank()) return null
        val file = File(path)
        return file.takeIf { it.exists() && it.isFile && it.length() > 0 }
    }

    fun hasCompanyName(context: Context): Boolean {
        return getCompanyName(context).isNotBlank()
    }

    fun hasCompanyBranding(context: Context): Boolean {
        return getCompanyName(context).isNotBlank() && getCompanyLogoFile(context) != null
    }

    // ---------------------------------------------------------------------------------------------
    // Subscription / free-trial gating
    //
    // The 7-day trial is managed entirely on-device (no backend). Paid access is verified by Google
    // Play Billing; [KEY_SUB_ACTIVE] is only a fast local cache of the last verified Play state so the
    // launch screen can route instantly without waiting for a billing round-trip.
    // ---------------------------------------------------------------------------------------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** True once the user has tapped "start free trial" at least once. */
    fun isTrialStarted(context: Context): Boolean =
        prefs(context).getLong(KEY_TRIAL_START, 0L) > 0L

    /** Records the moment the free trial began. No-op if it was already started. */
    fun startTrial(context: Context) {
        val p = prefs(context)
        if (p.getLong(KEY_TRIAL_START, 0L) > 0L) return
        val now = System.currentTimeMillis()
        p.edit().putLong(KEY_TRIAL_START, now).putLong(KEY_TRIAL_LAST_SEEN, now).apply()
    }

    /**
     * A monotonic "now" that never moves backwards, guarding against the user winding the device
     * clock back to extend the trial. Each call advances the stored high-water mark.
     */
    private fun monotonicNow(context: Context): Long {
        val p = prefs(context)
        val realNow = System.currentTimeMillis()
        val lastSeen = p.getLong(KEY_TRIAL_LAST_SEEN, realNow)
        val effective = maxOf(realNow, lastSeen)
        if (effective != lastSeen) p.edit().putLong(KEY_TRIAL_LAST_SEEN, effective).apply()
        return effective
    }

    /** Milliseconds left in the trial (0 if not started or already expired). */
    fun trialMillisRemaining(context: Context): Long {
        val start = prefs(context).getLong(KEY_TRIAL_START, 0L)
        if (start <= 0L) return 0L
        val elapsed = monotonicNow(context) - start
        return (TRIAL_DURATION_MS - elapsed).coerceAtLeast(0L)
    }

    /** Whole days left in the trial, rounded up (so the last partial day still shows as "1"). */
    fun trialDaysRemaining(context: Context): Int {
        val remaining = trialMillisRemaining(context)
        if (remaining <= 0L) return 0
        return Math.ceil(remaining.toDouble() / (24 * 60 * 60 * 1000)).toInt()
    }

    fun isTrialActive(context: Context): Boolean = trialMillisRemaining(context) > 0L

    /** Cached result of the last verified Google Play subscription check. */
    fun isSubscriptionActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SUB_ACTIVE, false)

    fun setSubscriptionActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUB_ACTIVE, active).apply()
    }

    /**
     * Remembers when the active subscription was purchased, so the welcome screen can show how many
     * days are left in the current billing period. Cleared when the subscription is gone.
     */
    fun setSubscriptionPurchaseTime(context: Context, purchaseTimeMillis: Long) {
        prefs(context).edit().putLong(KEY_SUB_PURCHASE_MS, purchaseTimeMillis).apply()
    }

    fun clearSubscriptionDetails(context: Context) {
        prefs(context).edit().remove(KEY_SUB_PURCHASE_MS).remove(KEY_SUB_PERIOD_ISO).apply()
    }

    /**
     * The billing period (ISO-8601, e.g. "P1M" / "P1Y") of the plan the user bought. Play's purchase
     * record does not carry the base plan, so this is recorded when the purchase flow is launched.
     */
    fun setSubscriptionPeriodIso(context: Context, periodIso: String) {
        prefs(context).edit().putString(KEY_SUB_PERIOD_ISO, periodIso.trim()).apply()
    }

    /**
     * Days left until the subscription renews, or null when it cannot be determined (no active
     * subscription, or the plan/purchase date is unknown — e.g. after reinstalling on a new device).
     */
    fun subscriptionDaysRemaining(context: Context): Int? {
        if (!isSubscriptionActive(context)) return null
        val p = prefs(context)
        val purchasedAt = p.getLong(KEY_SUB_PURCHASE_MS, 0L)
        val period = p.getString(KEY_SUB_PERIOD_ISO, "").orEmpty()
        if (purchasedAt <= 0L || period.isBlank()) return null

        val now = maxOf(System.currentTimeMillis(), purchasedAt)
        val renewal = java.util.Calendar.getInstance().apply { timeInMillis = purchasedAt }
        // Roll the purchase date forward one billing period at a time to the next renewal.
        var guard = 0
        while (renewal.timeInMillis <= now) {
            if (!addPeriod(renewal, period) || guard++ > MAX_PERIOD_ROLLS) return null
        }
        val remaining = renewal.timeInMillis - now
        return Math.ceil(remaining.toDouble() / (24 * 60 * 60 * 1000)).toInt().coerceAtLeast(1)
    }

    /** Advances [calendar] by one ISO-8601 billing period. Returns false if [periodIso] is unusable. */
    private fun addPeriod(calendar: java.util.Calendar, periodIso: String): Boolean {
        val match = ISO_PERIOD.matchEntire(periodIso.trim().uppercase(Locale.US)) ?: return false
        val (years, months, weeks, days) = match.destructured
        val y = years.toIntOrNull() ?: 0
        val mo = months.toIntOrNull() ?: 0
        val w = weeks.toIntOrNull() ?: 0
        val d = days.toIntOrNull() ?: 0
        if (y == 0 && mo == 0 && w == 0 && d == 0) return false
        calendar.add(java.util.Calendar.YEAR, y)
        calendar.add(java.util.Calendar.MONTH, mo)
        calendar.add(java.util.Calendar.DAY_OF_MONTH, w * 7 + d)
        return true
    }

    private const val MAX_PERIOD_ROLLS = 600
    private val ISO_PERIOD =
        Regex("""P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?""")

    /** Current entitlement values, for carrying across a backup restore. */
    fun entitlementSnapshot(context: Context): Map<String, Any> =
        prefs(context).all
            .filterKeys { it in ENTITLEMENT_KEYS }
            .mapNotNull { (k, v) -> v?.let { k to it } }
            .toMap()

    /** Writes back a snapshot taken with [entitlementSnapshot]. */
    fun applyEntitlementSnapshot(context: Context, snapshot: Map<String, Any>) {
        val editor = prefs(context).edit()
        for ((key, value) in snapshot) {
            when (value) {
                is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
                else -> Unit
            }
        }
        editor.apply()
    }
}


