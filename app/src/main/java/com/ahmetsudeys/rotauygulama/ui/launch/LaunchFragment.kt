package com.ahmetsudeys.rotauygulama.ui.launch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.Prefs
import com.ahmetsudeys.rotauygulama.databinding.FragmentLaunchBinding

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
            val navController = findNavController()
            if (!Prefs.hasCompanyName(requireContext())) {
                navController.navigate(R.id.action_launchFragment_to_companySetupFragment)
            } else {
                navController.navigate(R.id.action_launchFragment_to_welcomeFragment)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}


