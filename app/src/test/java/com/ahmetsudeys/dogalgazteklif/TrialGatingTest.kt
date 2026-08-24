package com.ahmetsudeys.dogalgazteklif

import com.ahmetsudeys.dogalgazteklif.billing.EntitlementManager
import com.ahmetsudeys.dogalgazteklif.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that make the 7-day free trial a one-time offer.
 *
 * The trial clock lives in `rota_prefs`, which Auto Backup carries across an uninstall (see
 * `backup_rules.xml` / `data_extraction_rules.xml`). Reinstalling therefore restores the original
 * start date instead of minting a fresh week — these tests pin the arithmetic and the "may this user
 * start a trial" decision that guarantee rests on.
 */
class TrialGatingTest {

    private val day = 24L * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000
    private val start = 1_800_000_000_000L

    // --- The trial clock -------------------------------------------------------------------------

    @Test
    fun `a trial that has just started has the full week left`() {
        assertEquals(Prefs.TRIAL_DURATION_MS, Prefs.trialRemaining(startMillis = start, now = start))
        assertEquals(7, Prefs.trialDaysOf(Prefs.trialRemaining(start, start)))
    }

    @Test
    fun `three days in, four are left`() {
        val remaining = Prefs.trialRemaining(startMillis = start, now = start + 3 * day)
        assertEquals(4 * day, remaining)
        assertEquals(4, Prefs.trialDaysOf(remaining))
    }

    @Test
    fun `the last partial day still shows as one`() {
        val remaining = Prefs.trialRemaining(startMillis = start, now = start + 7 * day - hour)
        assertEquals(1, Prefs.trialDaysOf(remaining))
    }

    @Test
    fun `an exhausted trial reports zero rather than going negative`() {
        val remaining = Prefs.trialRemaining(startMillis = start, now = start + 30 * day)
        assertEquals(0L, remaining)
        assertEquals(0, Prefs.trialDaysOf(remaining))
    }

    @Test
    fun `a trial that was never started is not handed a free week`() {
        // 0 means "no start recorded" — a fresh install of someone who never tapped the button. It
        // must not be read as "started at the epoch", nor as a running trial.
        assertEquals(0L, Prefs.trialRemaining(startMillis = 0L, now = start))
        assertEquals(0, Prefs.trialDaysOf(Prefs.trialRemaining(0L, start)))
    }

    // --- The clock cannot be wound back ----------------------------------------------------------

    @Test
    fun `winding the device clock back does not extend the trial`() {
        // Five days of trial have been seen; the user then sets the device clock back to day one.
        val seen = start + 5 * day
        val effective = Prefs.monotonicOf(realNow = start + day, lastSeen = seen)

        assertEquals(seen, effective)
        assertEquals(2 * day, Prefs.trialRemaining(startMillis = start, now = effective))
    }

    @Test
    fun `a clock that moves forward normally is followed`() {
        val realNow = start + 6 * day
        assertEquals(realNow, Prefs.monotonicOf(realNow = realNow, lastSeen = start + 5 * day))
    }

    // --- One free week, ever ---------------------------------------------------------------------

    @Test
    fun `a genuinely new user is offered the trial`() {
        assertTrue(
            EntitlementManager.canStartTrial(
                trialStarted = false,
                everSubscribed = false,
                subscribed = false
            )
        )
    }

    @Test
    fun `reinstalling does not hand out a second free trial`() {
        // Deleting the app wipes rota_prefs, but Auto Backup restores it — so trial_start_millis is
        // back and the paywall must open in "choose a plan" state, not "start your free trial".
        assertFalse(
            EntitlementManager.canStartTrial(
                trialStarted = true,
                everSubscribed = false,
                subscribed = false
            )
        )
    }

    @Test
    fun `reinstalling after the trial expired still leads to the paywall`() {
        val restoredStart = start
        val nowAfterReinstall = start + 10 * day

        assertEquals(0L, Prefs.trialRemaining(restoredStart, nowAfterReinstall))
        assertFalse(
            EntitlementManager.canStartTrial(
                trialStarted = true,
                everSubscribed = false,
                subscribed = false
            )
        )
    }

    @Test
    fun `a lapsed subscriber is not offered the trial again`() {
        // Someone who paid and then cancelled must renew. This also covers the user who arrived via
        // "restore purchases" on a fresh install and so never started a trial on this device.
        assertFalse(
            EntitlementManager.canStartTrial(
                trialStarted = false,
                everSubscribed = true,
                subscribed = false
            )
        )
    }

    @Test
    fun `an active subscriber is never shown the start-trial button`() {
        assertFalse(
            EntitlementManager.canStartTrial(
                trialStarted = false,
                everSubscribed = false,
                subscribed = true
            )
        )
    }
}
