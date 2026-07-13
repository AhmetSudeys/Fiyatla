package com.ahmetsudeys.dogalgazteklif.ui.subscription

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ahmetsudeys.dogalgazteklif.R
import com.ahmetsudeys.dogalgazteklif.billing.BillingManager
import com.ahmetsudeys.dogalgazteklif.billing.EntitlementManager
import com.ahmetsudeys.dogalgazteklif.data.Prefs
import com.ahmetsudeys.dogalgazteklif.databinding.FragmentSubscriptionBinding
import com.ahmetsudeys.dogalgazteklif.ui.util.setOnSingleClickListener
import com.google.android.material.card.MaterialCardView

/**
 * Paywall shown after company setup (first run) and on later launches once the trial is over.
 *
 * Two states:
 *  - **Start trial** ([EntitlementManager.canStartTrial]): a single button starts the on-device
 *    7-day free trial. No Google Play needed — the trial is entirely local.
 *  - **Choose plan** (trial expired): monthly / yearly Play subscription cards + "subscribe".
 *    The monthly plan is shown first and selected by default.
 *
 * Plan selection is tracked by base-plan id and is independent of billing availability, so the cards
 * are always interactive. The actual purchase resolves the selected id to the [BillingManager.PlanInfo]
 * that Play returned. If Play reports an active subscription at any point (fresh purchase or a restore
 * on the same Google account), the user is sent straight into the app.
 */
class SubscriptionFragment : Fragment(), BillingManager.Listener {

    private var _binding: FragmentSubscriptionBinding? = null
    private val binding: FragmentSubscriptionBinding
        get() = requireNotNull(_binding)

    private lateinit var billing: BillingManager

    private var monthlyPlan: BillingManager.PlanInfo? = null
    private var yearlyPlan: BillingManager.PlanInfo? = null
    private var selectedBasePlanId: String = BillingManager.BASE_PLAN_MONTHLY

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** True while a "restore purchases" request is awaiting its result. */
    private var restoreInProgress = false
    /** Guards the UI from hanging if Play never calls back. */
    private val restoreTimeout = Runnable { finishRestore(BillingManager.RestoreOutcome.UNAVAILABLE) }

    /** State A (start trial) vs state B (choose a paid plan). Decided once on entry. */
    private val startTrialMode: Boolean
        get() = EntitlementManager.canStartTrial(requireContext())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSubscriptionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Prefs.getCompanyLogoFile(requireContext())?.let { file ->
            binding.imageLogo.setImageURI(Uri.fromFile(file))
            binding.imageLogo.visibility = View.VISIBLE
        }

        binding.cardMonthly.setOnClickListener { selectPlan(BillingManager.BASE_PLAN_MONTHLY) }
        binding.cardYearly.setOnClickListener { selectPlan(BillingManager.BASE_PLAN_YEARLY) }

        if (startTrialMode) bindStartTrialState() else bindChoosePlanState()

        binding.buttonRestore.setOnSingleClickListener { restorePurchases() }
        binding.textPrivacy.setOnSingleClickListener { openLink(getString(R.string.privacy_policy_url)) }
        binding.textTerms.setOnSingleClickListener { openLink(getString(R.string.terms_of_use_url)) }

        billing = BillingManager(requireContext(), this)
        billing.start()
    }

    private fun bindStartTrialState() {
        binding.textTitle.setText(R.string.sub_trial_title)
        binding.textSubtitle.setText(R.string.sub_trial_subtitle)
        binding.groupPlans.visibility = View.GONE
        binding.textTrialNote.visibility = View.VISIBLE
        binding.buttonPrimary.setText(R.string.sub_start_trial)
        binding.buttonPrimary.isEnabled = true
        binding.buttonPrimary.setOnSingleClickListener {
            Prefs.startTrial(requireContext())
            proceedIntoApp()
        }
    }

    private fun bindChoosePlanState() {
        binding.textTitle.setText(R.string.sub_expired_title)
        binding.textSubtitle.setText(R.string.sub_expired_subtitle)
        binding.groupPlans.visibility = View.VISIBLE
        binding.textTrialNote.visibility = View.GONE
        binding.buttonPrimary.setText(R.string.sub_subscribe)
        // Enabled once Play returns the plans.
        binding.buttonPrimary.isEnabled = false
        binding.textStatus.visibility = View.VISIBLE
        binding.textStatus.setText(R.string.sub_loading)
        // Lead with monthly, selected by default.
        selectPlan(BillingManager.BASE_PLAN_MONTHLY)
        binding.buttonPrimary.setOnSingleClickListener {
            val plan = planFor(selectedBasePlanId)
            if (plan == null) {
                binding.textStatus.visibility = View.VISIBLE
                binding.textStatus.setText(R.string.sub_unavailable)
                return@setOnSingleClickListener
            }
            billing.launchPurchase(requireActivity(), plan)
        }
    }

    /**
     * "Restore purchases": ask Play whether this Google account already has an active subscription.
     * Works in both states (start-trial and choose-plan) and always resolves with visible feedback —
     * a definitive outcome from billing, or a timeout fallback so the status can never hang.
     */
    private fun restorePurchases() {
        if (restoreInProgress) return
        restoreInProgress = true
        binding.buttonRestore.isEnabled = false
        binding.textStatus.visibility = View.VISIBLE
        binding.textStatus.setText(R.string.sub_restoring)
        mainHandler.postDelayed(restoreTimeout, RESTORE_TIMEOUT_MS)
        billing.refreshPurchases { outcome ->
            if (_binding == null) return@refreshPurchases
            finishRestore(outcome)
        }
    }

    private fun finishRestore(outcome: BillingManager.RestoreOutcome) {
        if (!restoreInProgress || _binding == null) return
        restoreInProgress = false
        mainHandler.removeCallbacks(restoreTimeout)
        binding.buttonRestore.isEnabled = true
        when (outcome) {
            // Entitlement was granted; onEntitlementChanged navigates into the app. Keep status as-is.
            BillingManager.RestoreOutcome.RESTORED -> Unit
            BillingManager.RestoreOutcome.NONE -> {
                binding.textStatus.visibility = View.GONE
                Toast.makeText(requireContext(), R.string.sub_restore_none, Toast.LENGTH_LONG).show()
            }
            BillingManager.RestoreOutcome.UNAVAILABLE -> {
                binding.textStatus.visibility = View.VISIBLE
                binding.textStatus.setText(R.string.sub_unavailable)
            }
        }
    }

    private fun planFor(basePlanId: String): BillingManager.PlanInfo? =
        if (basePlanId == BillingManager.BASE_PLAN_YEARLY) yearlyPlan else monthlyPlan

    private fun selectPlan(basePlanId: String) {
        selectedBasePlanId = basePlanId
        val monthlySelected = basePlanId == BillingManager.BASE_PLAN_MONTHLY
        styleCard(binding.cardMonthly, binding.radioMonthly, monthlySelected)
        styleCard(binding.cardYearly, binding.radioYearly, !monthlySelected)
    }

    private fun styleCard(card: MaterialCardView, radio: ImageView, selected: Boolean) {
        val density = resources.displayMetrics.density
        val ctx = requireContext()
        card.strokeColor = ctx.getColor(if (selected) R.color.brand_blue else R.color.outline_light)
        card.strokeWidth = ((if (selected) 2 else 1) * density).toInt()
        card.setCardBackgroundColor(
            ctx.getColor(if (selected) R.color.brand_blue_soft else R.color.surface_white)
        )
        radio.setImageResource(if (selected) R.drawable.ic_radio_on else R.drawable.ic_radio_off)
    }

    // --- BillingManager.Listener ---

    override fun onEntitlementChanged(subscribed: Boolean) {
        if (_binding == null) return
        if (subscribed) {
            Toast.makeText(requireContext(), R.string.sub_already_active, Toast.LENGTH_SHORT).show()
            proceedIntoApp()
        } else if (!startTrialMode) {
            // A restore attempt that found nothing.
            binding.textStatus.visibility = View.GONE
        }
    }

    override fun onPlansLoaded(plans: List<BillingManager.PlanInfo>) {
        if (_binding == null) return
        monthlyPlan = plans.firstOrNull { it.basePlanId == BillingManager.BASE_PLAN_MONTHLY }
        yearlyPlan = plans.firstOrNull { it.basePlanId == BillingManager.BASE_PLAN_YEARLY }

        monthlyPlan?.let { binding.textMonthlyPrice.text = it.formattedPrice }
        yearlyPlan?.let { binding.textYearlyPrice.text = it.formattedPrice }

        if (!startTrialMode) {
            if (monthlyPlan == null && yearlyPlan == null) {
                binding.textStatus.visibility = View.VISIBLE
                binding.textStatus.setText(R.string.sub_unavailable)
                return
            }
            binding.textStatus.visibility = View.GONE
            binding.buttonPrimary.isEnabled = true
            // Keep the current selection (monthly by default), just refresh visuals.
            selectPlan(selectedBasePlanId)
        }
    }

    override fun onPurchaseFailed(userCancelled: Boolean, message: String?) {
        if (_binding == null) return
        if (!userCancelled) {
            Toast.makeText(requireContext(), R.string.sub_purchase_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onBillingUnavailable() {
        if (_binding == null) return
        // Does not block the free trial (state A); only the paid flow (state B) needs Play.
        if (!startTrialMode) {
            binding.textStatus.visibility = View.VISIBLE
            binding.textStatus.setText(R.string.sub_unavailable)
        }
    }

    private fun proceedIntoApp() {
        if (!isAdded) return
        findNavController().navigate(R.id.action_subscriptionFragment_to_welcomeFragment)
    }

    private fun openLink(url: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(requireContext(), url, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacks(restoreTimeout)
        if (this::billing.isInitialized) billing.destroy()
        _binding = null
    }

    companion object {
        /** If Play has not answered a restore request within this window, stop showing progress. */
        private const val RESTORE_TIMEOUT_MS = 12_000L
    }
}
