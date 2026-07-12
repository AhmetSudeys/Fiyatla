package com.ahmetsudeys.dogalgazteklif.ui.onboarding

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ahmetsudeys.dogalgazteklif.R
import com.ahmetsudeys.dogalgazteklif.billing.EntitlementManager
import com.ahmetsudeys.dogalgazteklif.data.CompanyBrandingStore
import com.ahmetsudeys.dogalgazteklif.data.Prefs
import com.ahmetsudeys.dogalgazteklif.databinding.FragmentCompanySetupBinding
import com.ahmetsudeys.dogalgazteklif.ui.util.setOnSingleClickListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class CompanySetupFragment : Fragment() {

    private var _binding: FragmentCompanySetupBinding? = null
    private val binding: FragmentCompanySetupBinding
        get() = requireNotNull(_binding)

    /**
     * Cropped logo waiting to be persisted. It is previewed immediately on the picker but only
     * written to storage when the user taps "Kaydet", so a picked-then-abandoned change is discarded.
     */
    private var pendingLogoBitmap: Bitmap? = null

    private val pickLogo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) showLogoCropper(uri)
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
            binding.imageCompanyLogo.setImageURI(Uri.fromFile(file))
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

            if (name.isBlank()) {
                binding.editCompanyName.error = getString(R.string.error_company_name_required)
                return@setOnSingleClickListener
            }

            Prefs.setCompanyName(requireContext(), name)
            // Persist the newly cropped logo only now, on save.
            pendingLogoBitmap?.let { CompanyBrandingStore.saveCompanyLogoBitmap(requireContext(), it) }
            // First-time setup lands on the paywall; an already-entitled user just editing their
            // company info goes back to the welcome screen.
            if (EntitlementManager.isEntitled(requireContext())) {
                findNavController().navigate(R.id.action_companySetupFragment_to_welcomeFragment)
            } else {
                findNavController().navigate(R.id.action_companySetupFragment_to_subscriptionFragment)
            }
        }
    }

    /** Shows a circular cropper for the picked image; result is previewed but not yet saved. */
    private fun showLogoCropper(uri: Uri) {
        val bitmap = CompanyBrandingStore.loadBitmapForCrop(requireContext(), uri)
        if (bitmap == null) {
            Toast.makeText(requireContext(), R.string.logo_load_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val density = resources.displayMetrics.density
        val cropView = CircleCropView(requireContext()).apply { setBitmap(bitmap) }
        val hint = TextView(requireContext()).apply {
            text = getString(R.string.logo_crop_hint)
            gravity = Gravity.CENTER
            alpha = 0.7f
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(
                cropView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (300 * density).toInt()
                )
            )
            addView(
                hint,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (12 * density).toInt() }
            )
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.logo_crop_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.logo_crop_apply) { _, _ ->
                val cropped = cropView.getCroppedCircleBitmap(512) ?: return@setPositiveButton
                pendingLogoBitmap = cropped
                binding.imageCompanyLogo.setImageBitmap(cropped)
                binding.imageCompanyLogo.visibility = View.VISIBLE
                binding.textLogoHint.visibility = View.GONE
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
