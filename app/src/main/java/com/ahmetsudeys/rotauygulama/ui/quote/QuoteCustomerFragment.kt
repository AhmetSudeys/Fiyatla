package com.ahmetsudeys.rotauygulama.ui.quote

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.quote.QuoteDraftStore
import com.ahmetsudeys.rotauygulama.ui.customers.LocationAutofill
import com.ahmetsudeys.rotauygulama.databinding.FragmentQuoteCustomerBinding
import com.ahmetsudeys.rotauygulama.ui.util.setOnSingleClickListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class QuoteCustomerFragment : Fragment() {

    private var _binding: FragmentQuoteCustomerBinding? = null
    private val binding: FragmentQuoteCustomerBinding
        get() = requireNotNull(_binding)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuoteCustomerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolbar.setNavigationOnClickListener { confirmDiscardAndExitIfNeeded() }
        binding.toolbar.menu.clear()
        binding.toolbar.inflateMenu(R.menu.menu_quote_flow)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_cancel_quote -> {
                    confirmDiscardAndExitIfNeeded(forceConfirm = true)
                    true
                }
                else -> false
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    confirmDiscardAndExitIfNeeded()
                }
            }
        )

        // District dropdown (Isparta districts).
        val districts = resources.getStringArray(R.array.isparta_districts)
        binding.editDistrict.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, districts)
        )

        // "Use current location" auto-fills the address fields.
        binding.textUseCurrentLocation.setOnClickListener {
            LocationAutofill.useCurrentLocation(
                this,
                LocationAutofill.Fields(
                    trigger = binding.textUseCurrentLocation,
                    city = binding.editCity,
                    district = binding.editDistrict,
                    neighborhood = binding.editNeighborhood,
                    street = binding.editStreet,
                    buildingNo = binding.editBuildingNo
                )
            ) {}
        }

        // Prefill from the draft (keeps values when navigating back/forward or editing a quote).
        binding.editCustomerName.setText(QuoteDraftStore.customerName)
        binding.editPhone.setText(QuoteDraftStore.customerPhone)
        binding.editTcNo.setText(QuoteDraftStore.customerTcNo)
        binding.editBuildingCode.setText(QuoteDraftStore.customerBuildingCode)
        binding.editInstallationNo.setText(QuoteDraftStore.customerInstallationNo)
        // İl defaults to ISPARTA unless the draft already has a value.
        binding.editCity.setText(QuoteDraftStore.customerCity.ifBlank { getString(R.string.default_city) })
        binding.editDistrict.setText(QuoteDraftStore.customerDistrict, false)
        binding.editNeighborhood.setText(QuoteDraftStore.customerNeighborhood)
        binding.editStreet.setText(QuoteDraftStore.customerStreet)
        binding.editBuildingNo.setText(QuoteDraftStore.customerBuildingNo)
        binding.editApartmentNo.setText(QuoteDraftStore.customerApartmentNo)
        binding.checkboxPainted.isChecked = QuoteDraftStore.customerPainted
        binding.editCustomerNote.setText(QuoteDraftStore.customerNote)

        binding.buttonContinue.setOnSingleClickListener {
            val name = binding.editCustomerName.text?.toString().orEmpty().trim()
            if (name.isBlank()) {
                binding.inputCustomerName.error = getString(R.string.error_customer_name_required)
                return@setOnSingleClickListener
            }
            binding.inputCustomerName.error = null

            fun field(text: CharSequence?) = text?.toString().orEmpty().trim()

            QuoteDraftStore.customerName = name
            QuoteDraftStore.customerPhone = field(binding.editPhone.text)
            QuoteDraftStore.customerTcNo = field(binding.editTcNo.text)
            QuoteDraftStore.customerBuildingCode = field(binding.editBuildingCode.text)
            QuoteDraftStore.customerInstallationNo = field(binding.editInstallationNo.text)
            QuoteDraftStore.customerCity = field(binding.editCity.text)
            QuoteDraftStore.customerDistrict = field(binding.editDistrict.text)
            QuoteDraftStore.customerNeighborhood = field(binding.editNeighborhood.text)
            QuoteDraftStore.customerStreet = field(binding.editStreet.text)
            QuoteDraftStore.customerBuildingNo = field(binding.editBuildingNo.text)
            QuoteDraftStore.customerApartmentNo = field(binding.editApartmentNo.text)
            QuoteDraftStore.customerPainted = binding.checkboxPainted.isChecked
            QuoteDraftStore.customerNote = field(binding.editCustomerNote.text)
            findNavController().navigate(R.id.action_quoteCustomerFragment_to_quoteOperationSelectFragment)
        }
    }

    private fun confirmDiscardAndExitIfNeeded(forceConfirm: Boolean = false) {
        val hasDraft = forceConfirm ||
            QuoteDraftStore.editingCreatedAtMillis != null ||
            QuoteDraftStore.customerName.isNotBlank() ||
            QuoteDraftStore.customerNote.isNotBlank() ||
            QuoteDraftStore.selectedOperations.isNotEmpty() ||
            QuoteDraftStore.materialsByOperation.isNotEmpty() ||
            QuoteDraftStore.materialOverridesByOperation.isNotEmpty() ||
            QuoteDraftStore.pipeMeters != 0.0 ||
            QuoteDraftStore.radiatorMeters != 0.0 ||
            QuoteDraftStore.boilerPrice != 0.0 ||
            QuoteDraftStore.laborTotal != 0.0 ||
            QuoteDraftStore.radiatorTotal != 0.0 ||
            QuoteDraftStore.profit != 0.0 ||
            QuoteDraftStore.discountAmount != 0.0

        if (!hasDraft) {
            QuoteDraftStore.reset()
            findNavController().navigateUp()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.discard_quote_title)
            .setMessage(R.string.discard_quote_message)
            .setNegativeButton(R.string.cancel_quote, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                QuoteDraftStore.reset()
                exitToPreviousOrHome()
            }
            .show()
    }

    private fun exitToPreviousOrHome() {
        val nav = findNavController()
        val poppedToDetail = nav.popBackStack(R.id.quoteDetailFragment, false)
        if (poppedToDetail) return
        val poppedToHome = nav.popBackStack(R.id.homeFragment, false)
        if (poppedToHome) return
        nav.navigateUp()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}


