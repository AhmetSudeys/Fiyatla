package com.ahmetsudeys.rotauygulama.ui.onboarding

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.Prefs
import com.ahmetsudeys.rotauygulama.databinding.FragmentWelcomeBinding
import com.ahmetsudeys.rotauygulama.ui.util.setOnSingleClickListener

class WelcomeFragment : Fragment() {

    private var _binding: FragmentWelcomeBinding? = null
    private val binding: FragmentWelcomeBinding
        get() = requireNotNull(_binding)

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

        binding.buttonLogin.setOnClickListener {
            findNavController().navigate(R.id.action_welcomeFragment_to_mainShellFragment)
        }

        binding.textEditCompany.setOnSingleClickListener {
            findNavController().navigate(R.id.action_welcomeFragment_to_companySetupFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}


