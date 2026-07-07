package com.ahmetsudeys.rotauygulama.ui.quote

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.quote.QuoteDraftStore
import com.ahmetsudeys.rotauygulama.databinding.FragmentQuoteOutputBinding
import com.ahmetsudeys.rotauygulama.ui.util.setOnSingleClickListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

class QuoteOutputFragment : Fragment() {

    private var _binding: FragmentQuoteOutputBinding? = null
    private val binding: FragmentQuoteOutputBinding
        get() = requireNotNull(_binding)

    private val money: NumberFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("tr-TR")).apply {
        currency = Currency.getInstance("TRY")
        maximumFractionDigits = 2
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuoteOutputBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.toolbar.menu.clear()
        binding.toolbar.inflateMenu(R.menu.menu_quote_flow)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_cancel_quote -> {
                    confirmDiscardAndExit()
                    true
                }
                else -> false
            }
        }

        binding.textTitle.setText(R.string.quote_output_title)
        val customer = QuoteDraftStore.customerName.trim()
        binding.textCustomer.isVisible = customer.isNotBlank()
        binding.textCustomer.text = "Müşteri: $customer"

        bindOperations()
        bindServices()
        bindGrandTotal()

        binding.buttonTakeOutput.setOnSingleClickListener {
            Toast.makeText(requireContext(), getString(R.string.take_output_hint), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDiscardAndExit() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.discard_quote_title)
            .setMessage(R.string.discard_quote_message)
            .setNegativeButton(R.string.keep_editing, null)
            .setPositiveButton(R.string.cancel_quote) { _, _ ->
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

    private fun bindOperations() {
        val ops = QuoteDraftStore.selectedOperations
            .map { it.trim() }
            .filter { it.isNotBlank() }

        binding.textOperations.text = if (ops.isEmpty()) {
            "-"
        } else {
            ops.joinToString(separator = "\n") { "• $it" }
        }
    }

    private fun bindServices() {
        val lines = buildList {
            add(getString(R.string.workmanship))
            add(getString(R.string.project))
            add(getString(R.string.other_expenses))

            if (QuoteDraftStore.boilerPrice > 0.0) add(getString(R.string.boiler))
            if (QuoteDraftStore.radiatorTotal > 0.0) add(getString(R.string.radiator))
        }

        binding.textServices.text = lines.joinToString(separator = "\n") { "• $it" }
    }

    private fun bindGrandTotal() {
        val materialsTotal = QuoteDraftStore.currentMaterialsTotal()
        val subtotal = materialsTotal +
            QuoteDraftStore.projectTotal +
            QuoteDraftStore.laborTotal +
            QuoteDraftStore.radiatorTotal +
            QuoteDraftStore.boilerPrice +
            QuoteDraftStore.profit

        val grandTotal = (subtotal - QuoteDraftStore.discountAmount).coerceAtLeast(0.0)
        binding.textGrandTotal.text = "${getString(R.string.grand_total)}: ${money.format(grandTotal)}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}


