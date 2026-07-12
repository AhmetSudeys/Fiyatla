package com.ahmetsudeys.dogalgazteklif.ui.subscription

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 *
 * If Play reports an active subscription at any point (fresh purchase or a restore on the same
 * Google account), the user is sent straight into the app.
 */
class SubscriptionFragment : Fragment(), BillingManager.Listener {

    private var _binding: FragmentSubscriptionBinding? = null
    private val binding: FragmentSubscriptionBinding
        get() = requireNotNull(_binding)

    private lateinit var billing: BillingManager

    private var monthlyPlan: BillingManager.PlanInfo? = null
    private var yearlyPlan: BillingManager.PlanInfo? = null
    private var selectedPlan: BillingManager.PlanInfo? = null

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

        if (startTrialMode) bindStartTrialState() else bindChoosePlanState()

        binding.buttonRestore.setOnSingleClickListener {
            binding.textStatus.visibility = View.VISIBLE
            binding.textStatus.setText(R.string.sub_loading)
            billing.refreshPurchases()
        }
        binding.textPrivacy.setOnSingleClickListener { openLink(getString(R.string.privacy_policy_url)) }
        binding.textTerms.setOnSingleClickListener { openLink(getString(R.string.privacy_policy_url)) }

        binding.cardMonthly.setOnClickListener { selectPlan(monthlyPlan) }
        binding.cardYearly.setOnClickListener { selectPlan(yearlyPlan) }

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
        // Disabled until Play returns the plans.
        binding.buttonPrimary.isEnabled = false
        binding.textStatus.visibility = View.VISIBLE
        binding.textStatus.setText(R.string.sub_loading)
        binding.buttonPrimary.setOnSingleClickListener {
            val plan = selectedPlan ?: return@setOnSingleClickListener
            billing.launchPurchase(requireActivity(), plan)
        }
    }

    private fun selectPlan(plan: BillingManager.PlanInfo?) {
        if (plan == null) return
        selectedPlan = plan
        val selected = R.color.brand_blue
        val normal = R.color.outline_light
        val density = resources.displayMetrics.density
        highlightCard(binding.cardYearly, plan === yearlyPlan, selected, normal, density)
        highlightCard(binding.cardMonthly, plan === monthlyPlan, selected, normal, density)
    }

    private fun highlightCard(
        card: MaterialCardView,
        isSelected: Boolean,
        selectedColorRes: Int,
        normalColorRes: Int,
        density: Float
    ) {
        val colorRes = if (isSelected) selectedColorRes else normalColorRes
        card.strokeColor = requireContext().getColor(colorRes)
        card.strokeWidth = ((if (isSelected) 2 else 1) * density).toInt()
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
            // Default to the best-value yearly plan when available.
            selectPlan(yearlyPlan ?: monthlyPlan)
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
        if (this::billing.isInitialized) billing.destroy()
        _binding = null
    }
}
