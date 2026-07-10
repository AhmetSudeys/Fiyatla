package com.ahmetsudeys.dogalgazteklif.ui.launch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ahmetsudeys.dogalgazteklif.R
import com.ahmetsudeys.dogalgazteklif.data.Prefs
import com.ahmetsudeys.dogalgazteklif.databinding.FragmentLaunchBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class LaunchFragment : Fragment() {

    private var _binding: FragmentLaunchBinding? = null
    private val binding: FragmentLaunchBinding
        get() = requireNotNull(_binding)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLaunchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Small post() prevents rare jank/transaction timing issues at app start.
        view.post {
            if (!isAdded) return@post
            // On the very first launch, show the data-storage responsibility notice before anything
            // else (before the user enters company name / logo).
            if (!Prefs.isDisclaimerAccepted(requireContext())) {
                showDisclaimerThenProceed()
            } else {
                proceed()
            }
        }
    }

    private fun showDisclaimerThenProceed() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_message)
            .setCancelable(false)
            .setPositiveButton(R.string.disclaimer_accept) { _, _ ->
                if (!isAdded) return@setPositiveButton
                Prefs.setDisclaimerAccepted(requireContext())
                proceed()
            }
            .show()
    }

    private fun proceed() {
        if (!isAdded) return
        val navController = findNavController()
        if (!Prefs.hasCompanyName(requireContext())) {
            navController.navigate(R.id.action_launchFragment_to_companySetupFragment)
        } else {
            navController.navigate(R.id.action_launchFragment_to_welcomeFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}


