package com.ahmetsudeys.rotauygulama.ui.quotes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.quote.QuoteStatus
import com.ahmetsudeys.rotauygulama.data.quote.QuoteDraftStore
import com.ahmetsudeys.rotauygulama.data.quote.QuoteStorage
import com.ahmetsudeys.rotauygulama.databinding.FragmentQuoteDetailBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QuoteDetailFragment : Fragment() {

    private var _binding: FragmentQuoteDetailBinding? = null
    private val binding: FragmentQuoteDetailBinding
        get() = requireNotNull(_binding)

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentRecord: QuoteStorage.QuoteRecord? = null

    private val money: NumberFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("tr-TR")).apply {
        currency = Currency.getInstance("TRY")
        maximumFractionDigits = 2
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuoteDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        val createdAtMillis = requireArguments().getLong("createdAtMillis", 0L)
        ioExecutor.execute {
            val record = QuoteStorage.getQuotes(requireContext()).firstOrNull { it.createdAtMillis == createdAtMillis }
            mainHandler.post {
                if (_binding == null) return@post

                if (record == null) {
                    binding.textCustomer.text = "Müşteri: -"
                    binding.textOperations.text = "İşlemler: -"
                    return@post
                }

                currentRecord = record
                bindRecord(record)

                binding.layoutStatus.setOnClickListener { anchor ->
                    val current = currentRecord ?: return@setOnClickListener
                    QuoteStatusUi.showPicker(anchor.context, anchor, current.status) { picked ->
                        QuoteApproval.changeStatus(this, ioExecutor, mainHandler, current, picked) {
                            val updated = current.copy(status = picked)
                            currentRecord = updated
                            bindRecord(updated)
                        }
                    }
                }

                binding.buttonAddCustomer.setOnClickListener {
                    val current = currentRecord ?: return@setOnClickListener
                    // Adding the customer from the detail screen also approves the quote.
                    QuoteApproval.promoteToCustomer(this, ioExecutor, mainHandler, current) {
                        markApproved()
                    }
                }

                binding.buttonDeleteQuote.setOnClickListener {
                    val current = currentRecord ?: return@setOnClickListener
                    confirmDeleteQuote(current)
                }

                binding.toolbar.menu.clear()
                binding.toolbar.inflateMenu(com.ahmetsudeys.rotauygulama.R.menu.menu_quote_detail)
                binding.toolbar.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        com.ahmetsudeys.rotauygulama.R.id.action_edit_quote -> {
                            QuoteDraftStore.loadFromRecord(record)
                            findNavController().navigate(com.ahmetsudeys.rotauygulama.R.id.action_quoteDetailFragment_to_quote_nav_graph)
                            true
                        }

                        else -> false
                    }
                }
            }
        }
    }

    private fun confirmDeleteQuote(record: QuoteStorage.QuoteRecord) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_quote_confirm_title)
            .setMessage(R.string.delete_quote_confirm_message)
            .setNegativeButton(R.string.cancel_quote, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                ioExecutor.execute {
                    QuoteStorage.deleteQuote(requireContext(), record.createdAtMillis)
                    mainHandler.post {
                        if (isAdded) findNavController().navigateUp()
                    }
                }
            }
            .show()
    }

    /** Sets the quote to APPROVED and refreshes the UI, without re-offering the add-customer flow. */
    private fun markApproved() {
        val current = currentRecord ?: return
        if (current.status == QuoteStatus.APPROVED) return
        ioExecutor.execute {
            QuoteStorage.updateStatus(requireContext(), current.createdAtMillis, QuoteStatus.APPROVED)
            mainHandler.post {
                if (_binding == null) return@post
                val updated = current.copy(status = QuoteStatus.APPROVED)
                currentRecord = updated
                bindRecord(updated)
            }
        }
    }

    private fun bindRecord(record: QuoteStorage.QuoteRecord) {
        binding.textCustomer.text = "Müşteri: ${record.customerName.ifBlank { "-" }}"
        binding.textOperations.text = "İşlemler: ${record.operations.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "-"}"

        QuoteStatusUi.bindPill(
            container = binding.layoutStatus,
            icon = binding.imageStatus,
            label = binding.textStatusLabel,
            caret = binding.imageCaret,
            status = record.status
        )

        binding.textMaterialsTotalValue.text = money.format(record.materialsTotal)
        binding.textLaborValue.text = money.format(record.laborTotal)
        binding.textRadiatorValue.text = money.format(record.radiatorTotal)
        binding.textBoilerValue.text = money.format(record.boilerPrice)
        binding.textProfitValue.text = money.format(record.profit)

        val showDiscount = record.discount > 0.0
        binding.textDiscountLabel.isVisible = showDiscount
        binding.textDiscountValue.isVisible = showDiscount
        if (showDiscount) binding.textDiscountValue.text = "- ${money.format(record.discount)}"

        binding.textGrandTotalValue.text = money.format(record.total)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }
}


