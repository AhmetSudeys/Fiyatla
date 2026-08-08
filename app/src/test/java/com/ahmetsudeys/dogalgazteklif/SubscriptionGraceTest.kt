package com.ahmetsudeys.dogalgazteklif

import com.ahmetsudeys.dogalgazteklif.data.Prefs
import com.ahmetsudeys.dogalgazteklif.data.Prefs.NegativeOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides whether a "no active subscription" answer from Google Play is allowed to
 * throw a paying user onto the paywall.
 *
 * `queryPurchasesAsync` reads the Play Store app's local cache without forcing a network request, so
 * it can come back empty while the subscription is very much alive (fresh reinstall, device-to-device
 * restore, Play Store updating or its data cleared, account list in flux). Acting on the first such
 * answer is exactly how a subscriber ends up being asked to subscribe again.
 */
class SubscriptionGraceTest {

    private val hour = 60L * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun `a first negative never revokes a verified subscriber`() {
        assertEquals(
            NegativeOutcome.START_GRACE,
            Prefs.negativeOutcome(wasActive = true, negativeSince = 0L, now = now)
        )
    }

    @Test
    fun `access is kept while the negative is younger than the grace`() {
        assertEquals(
            NegativeOutcome.IN_GRACE,
            Prefs.negativeOutcome(
                wasActive = true,
                negativeSince = now - (Prefs.VERIFY_GRACE_MS - hour),
                now = now
            )
        )
    }

    @Test
    fun `access ends once the negative has persisted past the grace`() {
        assertEquals(
            NegativeOutcome.REVOKE,
            Prefs.negativeOutcome(
                wasActive = true,
                negativeSince = now - (Prefs.VERIFY_GRACE_MS + hour),
                now = now
            )
        )
    }

    @Test
    fun `the boundary itself still revokes`() {
        assertEquals(
            NegativeOutcome.REVOKE,
            Prefs.negativeOutcome(
                wasActive = true,
                negativeSince = now - Prefs.VERIFY_GRACE_MS,
                now = now
            )
        )
    }

    @Test
    fun `someone who never subscribed is simply not entitled`() {
        assertEquals(
            NegativeOutcome.NOTHING_TO_LOSE,
            Prefs.negativeOutcome(wasActive = false, negativeSince = 0L, now = now)
        )
        assertEquals(
            NegativeOutcome.NOTHING_TO_LOSE,
            Prefs.negativeOutcome(wasActive = false, negativeSince = now - 10 * hour, now = now)
        )
    }

    /** A marker in the future means the clock moved; restart the grace rather than trusting it. */
    @Test
    fun `a future marker restarts the grace instead of shortening or extending it`() {
        assertEquals(
            NegativeOutcome.START_GRACE,
            Prefs.negativeOutcome(wasActive = true, negativeSince = now + 10 * hour, now = now)
        )
    }

    /** The grace has to be long enough to outlast a transient Play Store problem. */
    @Test
    fun `the grace spans multiple days`() {
        assertEquals(72 * hour, Prefs.VERIFY_GRACE_MS)
    }
}
