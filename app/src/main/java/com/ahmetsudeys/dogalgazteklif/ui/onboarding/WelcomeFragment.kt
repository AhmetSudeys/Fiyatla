package com.ahmetsudeys.dogalgazteklif.ui.onboarding

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.ahmetsudeys.dogalgazteklif.R
import com.ahmetsudeys.dogalgazteklif.billing.BillingManager
import com.ahmetsudeys.dogalgazteklif.data.Prefs
import com.ahmetsudeys.dogalgazteklif.data.backup.BackupManager
import com.ahmetsudeys.dogalgazteklif.databinding.FragmentWelcomeBinding
import com.ahmetsudeys.dogalgazteklif.ui.util.navigateOnceFrom
import com.ahmetsudeys.dogalgazteklif.ui.util.setOnSingleClickListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WelcomeFragment : Fragment() {

    private var _binding: FragmentWelcomeBinding? = null
    private val binding: FragmentWelcomeBinding
        get() = requireNotNull(_binding)

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressDialog: androidx.appcompat.app.AlertDialog? = null

    /**
     * Background re-verification of Play subscription state. Every launch of an entitled user passes
     * through this screen, so it is the natural place to catch a subscription that has lapsed since
     * the cached flag was written.
     *
     * It never locks out a user when Play is simply unreachable (offline / billing unavailable), and
     * — because [BillingManager] reports the *effective* entitlement — not on a one-off "no
     * subscription" answer either: that only ejects once it has persisted past
     * [Prefs.VERIFY_GRACE_MS]. Until then the user is warned but keeps working.
     */
    private var billing: BillingManager? = null
    private val entitlementVerifier = object : BillingManager.Listener {
        override fun onEntitlementChanged(subscribed: Boolean) {
            if (_binding == null || !isAdded) return
            if (!subscribed && !Prefs.isTrialActive(requireContext())) {
                // Guarded: this can land in the same main-loop pass as the user tapping "Giriş",
                // where a plain navigate would resolve against the new destination and crash.
                navigateOnceFrom(
                    R.id.welcomeFragment,
                    R.id.action_welcomeFragment_to_subscriptionFragment
                )
                return
            }
            // Play may have refined what we knew (e.g. purchase date) — refresh the countdown.
            showEntitlementStatus()
        }
        override fun onPlansLoaded(plans: List<BillingManager.PlanInfo>) {}
        override fun onPurchaseFailed(userCancelled: Boolean, message: String?) {}
        override fun onBillingUnavailable() {}
    }

    private val pickBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmAndRestore(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val companyName = Prefs.getCompanyName(requireContext())
        binding.textWelcome.text = getString(R.string.welcome_with_company, companyName)

        Prefs.getCompanyLogoFile(requireContext())?.let { file ->
            binding.imageLogo.setImageURI(Uri.fromFile(file))
        }

        binding.buttonLogin.setOnSingleClickListener {
            navigateOnceFrom(R.id.welcomeFragment, R.id.action_welcomeFragment_to_mainShellFragment)
        }

        binding.buttonBackup.setOnSingleClickListener { startBackup() }
        binding.buttonRestore.setOnSingleClickListener {
            // Accept any file type; some providers (WhatsApp/Drive) report a generic MIME.
            pickBackup.launch(arrayOf("*/*"))
        }

        binding.textEditCompany.setOnSingleClickListener {
            navigateOnceFrom(R.id.welcomeFragment, R.id.action_welcomeFragment_to_companySetupFragment)
        }

        showEntitlementStatus()

        binding.textPrivacy.setOnSingleClickListener { openUrl(getString(R.string.privacy_policy_url)) }
        binding.textTerms.setOnSingleClickListener { openUrl(getString(R.string.terms_of_use_url)) }

        // Re-verify Play subscription in the background; may bounce a lapsed subscriber to the paywall.
        billing = BillingManager(requireContext(), entitlementVerifier).also { it.start() }
    }

    /**
     * Shows how much access time is left: the remaining free-trial days, or — for a subscriber —
     * the days until the next renewal. Falls back to a plain "subscription active" line when the
     * renewal date is not known locally (e.g. the app was reinstalled on another device).
     */
    private fun showEntitlementStatus() {
        val context = requireContext()
        val text = when {
            // Access is only being held open by the verification grace — tell the user, so they can
            // fix it (Play Store login / connection) before the grace runs out.
            Prefs.isSubscriptionUnverified(context) -> getString(R.string.welcome_sub_unverified)
            Prefs.isSubscriptionActive(context) ->
                Prefs.subscriptionDaysRemaining(context)
                    ?.let { getString(R.string.welcome_sub_days_left, it) }
                    ?: getString(R.string.welcome_sub_active)
            Prefs.isTrialActive(context) ->
                getString(R.string.welcome_trial_days_left, Prefs.trialDaysRemaining(context))
            else -> null
        }
        binding.textEntitlementStatus.text = text.orEmpty()
        binding.textEntitlementStatus.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun openUrl(url: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(requireContext(), url, Toast.LENGTH_LONG).show()
            }
    }

    private fun startBackup() {
        showProgress(getString(R.string.backup_in_progress))
        ioExecutor.execute {
            val result = runCatching { BackupManager.buildBackup(requireContext()) }
            mainHandler.post {
                if (_binding == null) return@post
                dismissProgress()
                result.onSuccess { uri ->
                    BackupManager.startShare(requireContext(), uri, getString(R.string.backup_share_title))
                }.onFailure {
                    Toast.makeText(requireContext(), R.string.backup_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmAndRestore(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_confirm_title)
            .setMessage(R.string.restore_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.restore_data) { _, _ -> performRestore(uri) }
            .show()
    }

    private fun performRestore(uri: Uri) {
        showProgress(getString(R.string.restore_in_progress))
        ioExecutor.execute {
            val result = runCatching { BackupManager.restore(requireContext(), uri) }
                .getOrDefault(BackupManager.RestoreResult.Error)
            mainHandler.post {
                if (_binding == null) return@post
                dismissProgress()
                when (result) {
                    BackupManager.RestoreResult.Success -> showRestoreSuccess()
                    BackupManager.RestoreResult.InvalidFile ->
                        Toast.makeText(requireContext(), R.string.restore_invalid, Toast.LENGTH_LONG).show()
                    BackupManager.RestoreResult.Error ->
                        Toast.makeText(requireContext(), R.string.restore_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showRestoreSuccess() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_success)
            .setCancelable(false)
            .setPositiveButton(R.string.restart_now) { _, _ ->
                // Re-inflate so the restored company name/logo (and everything else) is picked up.
                if (isAdded) requireActivity().recreate()
            }
            .show()
    }

    private fun showProgress(message: String) {
        dismissProgress()
        progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setView(
                android.widget.LinearLayout(requireContext()).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    val pad = (24 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(android.widget.ProgressBar(requireContext()))
                    addView(
                        android.widget.TextView(requireContext()).apply {
                            text = message
                            val lp = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            lp.marginStart = pad
                            layoutParams = lp
                        }
                    )
                }
            )
            .setCancelable(false)
            .show()
    }

    private fun dismissProgress() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dismissProgress()
        billing?.destroy()
        billing = null
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }
}
