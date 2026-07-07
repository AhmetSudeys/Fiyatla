package com.ahmetsudeys.rotauygulama.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.CompanyBrandingStore
import com.ahmetsudeys.rotauygulama.data.Prefs
import com.ahmetsudeys.rotauygulama.databinding.FragmentCompanySetupBinding
import com.ahmetsudeys.rotauygulama.ui.util.setOnSingleClickListener

class CompanySetupFragment : Fragment() {

    private var _binding: FragmentCompanySetupBinding? = null
    private val binding: FragmentCompanySetupBinding
        get() = requireNotNull(_binding)

    private val pickLogo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                val file = CompanyBrandingStore.saveCompanyLogoFromUri(requireContext(), uri)
                if (file != null) {
                    binding.imageCompanyLogo.setImageURI(android.net.Uri.fromFile(file))
                    binding.imageCompanyLogo.visibility = View.VISIBLE
                    binding.textLogoHint.visibility = View.GONE
                } else {
                    Toast.makeText(requireContext(), "Logo yüklenemedi", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCompanySetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Pre-fill UI if user already configured branding before.
        Prefs.getCompanyLogoFile(requireContext())?.let { file ->
            binding.imageCompanyLogo.setImageURI(android.net.Uri.fromFile(file))
            binding.imageCompanyLogo.visibility = View.VISIBLE
            binding.textLogoHint.visibility = View.GONE
        }
        Prefs.getCompanyName(requireContext()).takeIf { it.isNotBlank() }?.let { name ->
            binding.editCompanyName.setText(name)
        }

        binding.cardLogoPicker.setOnClickListener {
            pickLogo.launch("image/*")
        }

        binding.buttonSaveCompany.setOnSingleClickListener {
            val name = binding.editCompanyName.text?.toString().orEmpty().trim()

            var hasError = false
            if (name.isBlank()) {
                binding.editCompanyName.error = getString(R.string.error_company_name_required)
                hasError = true
            }
            if (hasError) return@setOnSingleClickListener

            Prefs.setCompanyName(requireContext(), name)
            findNavController().navigate(R.id.action_companySetupFragment_to_welcomeFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}


