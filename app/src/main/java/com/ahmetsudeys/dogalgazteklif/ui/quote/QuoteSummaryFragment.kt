package com.ahmetsudeys.dogalgazteklif.ui.quote

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.ahmetsudeys.dogalgazteklif.R
import com.ahmetsudeys.dogalgazteklif.data.quote.QuoteDraftStore
import com.ahmetsudeys.dogalgazteklif.data.quote.QuoteStorage
import com.ahmetsudeys.dogalgazteklif.databinding.FragmentQuoteSummaryBinding
import com.ahmetsudeys.dogalgazteklif.databinding.BottomsheetDiscountBinding
import com.ahmetsudeys.dogalgazteklif.ui.util.setOnSingleClickListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QuoteSummaryFragment : Fragment() {

    private var _binding: FragmentQuoteSummaryBinding? = null
    private val binding: FragmentQuoteSummaryBinding
        get() = requireNotNull(_binding)

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sharing = false

    private val money: NumberFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("tr-TR")).apply {
        currency = Currency.getInstance("TRY")
        maximumFractionDigits = 2
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuoteSummaryBinding.inflate(inflater, container, false)
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
        bindUi()
        applyInsets()

        binding.buttonDiscount.setOnSingleClickListener { showDiscountSheet() }
        binding.buttonOutput.setOnSingleClickListener {
            if (sharing) return@setOnSingleClickListener
            sharing = true
            val appCtx = requireContext().applicationContext
            ioExecutor.execute {
                val uri = try {
                    QuoteShareHelper.buildDraftPdf(appCtx)
                } catch (t: Throwable) {
                    null
                }
                mainHandler.post {
                    sharing = false
                    if (_binding == null || !isAdded) return@post
                    if (uri == null) {
                        Toast.makeText(requireContext(), "Paylaşım açılamadı", Toast.LENGTH_SHORT).show()
                    } else {
                        try {
                            QuoteShareHelper.startShare(requireContext(), uri)
                        } catch (t: Throwable) {
                            Toast.makeText(requireContext(), "Paylaşım açılamadı", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        binding.buttonComplete.setText(
            if (QuoteDraftStore.editingCreatedAtMillis != null) R.string.update_quote else R.string.complete_quote
        )
        binding.buttonComplete.setOnSingleClickListener { completeQuote() }
    }

    private fun confirmDiscardAndExit() {
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

    private fun applyInsets() {
        val initialBottomPadding = binding.buttonsContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.buttonsContainer) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, initialBottomPadding + systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.buttonsContainer)
    }

    private fun bindUi() {
        val materialsTotal = QuoteDraftStore.currentMaterialsTotal()
        val project = QuoteDraftStore.projectTotal
        val labor = QuoteDraftStore.laborTotal
        val radiator = QuoteDraftStore.radiatorTotal
        val boiler = QuoteDraftStore.boilerPrice
        val profit = QuoteDraftStore.profit
        val discount = QuoteDraftStore.discountAmount

        val subtotal = materialsTotal + project + labor + radiator + boiler + profit
        val grandTotal = (subtotal - discount).coerceAtLeast(0.0)

        binding.textCustomer.text = "Müşteri: ${QuoteDraftStore.customerName.ifBlank { "-" }}"
        binding.textMaterialsTotalValue.text = money.format(materialsTotal)
        binding.textLaborValue.text = money.format(labor)
        binding.textRadiatorValue.text = money.format(radiator)
        binding.textBoilerValue.text = money.format(boiler)
        binding.textProfitValue.text = money.format(profit)

        // Always show project row; if not entered it should be 0.
        binding.textProjectLabel.visibility = View.VISIBLE
        binding.textProjectValue.visibility = View.VISIBLE
        binding.textProjectValue.text = money.format(project)

        val showDiscount = discount > 0.0
        binding.textDiscountLabel.visibility = if (showDiscount) View.VISIBLE else View.GONE
        binding.textDiscountValue.visibility = if (showDiscount) View.VISIBLE else View.GONE
        if (showDiscount) binding.textDiscountValue.text = "- ${money.format(discount)}"

        binding.textGrandTotalValue.text = money.format(grandTotal)
    }

    private fun showDiscountSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = BottomsheetDiscountBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        val materialsTotal = QuoteDraftStore.currentMaterialsTotal()
        val subtotal = materialsTotal +
            QuoteDraftStore.laborTotal +
            QuoteDraftStore.radiatorTotal +
            QuoteDraftStore.boilerPrice +
            QuoteDraftStore.profit

        fun updatePreview(discount: Double) {
            val total = (subtotal - discount).coerceAtLeast(0.0)
            sheetBinding.textPreview.text = "Yeni toplam: ${money.format(total)}"
        }

        fun applyPercent(percent: Double) {
            val discount = (subtotal * percent).coerceAtMost(subtotal)
            sheetBinding.editDiscountAmount.setText(discount.toString())
            sheetBinding.editDiscountAmount.setSelection(sheetBinding.editDiscountAmount.text?.length ?: 0)
            updatePreview(discount)
        }

        sheetBinding.editDiscountAmount.setText(
            if (QuoteDraftStore.discountAmount > 0.0) QuoteDraftStore.discountAmount.toString() else ""
        )
        updatePreview(QuoteDraftStore.discountAmount)

        sheetBinding.buttonPct5.setOnClickListener { applyPercent(0.05) }
        sheetBinding.buttonPct10.setOnClickListener { applyPercent(0.10) }
        sheetBinding.buttonPct15.setOnClickListener { applyPercent(0.15) }
        sheetBinding.buttonPct20.setOnClickListener { applyPercent(0.20) }

        sheetBinding.editDiscountAmount.doAfterTextChanged {
            val d = it?.toString().orEmpty().toDoubleOrZero()
            updatePreview(d.coerceAtMost(subtotal))
        }

        sheetBinding.buttonApply.setOnClickListener {
            val d = sheetBinding.editDiscountAmount.text?.toString().orEmpty().toDoubleOrZero()
            QuoteDraftStore.discountAmount = d.coerceAtMost(subtotal).coerceAtLeast(0.0)
            bindUi()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun completeQuote() {
        val materialsTotal = QuoteDraftStore.currentMaterialsTotal()
        val project = QuoteDraftStore.projectTotal
        val labor = QuoteDraftStore.laborTotal
        val radiator = QuoteDraftStore.radiatorTotal
        val boiler = QuoteDraftStore.boilerPrice
        val profit = QuoteDraftStore.profit
        val subtotal = materialsTotal + project + labor + radiator + boiler + profit
        val total = (subtotal - QuoteDraftStore.discountAmount).coerceAtLeast(0.0)

        val createdAtMillis = QuoteDraftStore.editingCreatedAtMillis ?: System.currentTimeMillis()
        val status = QuoteDraftStore.editingStatus ?: com.ahmetsudeys.dogalgazteklif.data.quote.QuoteStatus.PENDING
        val overrides = QuoteDraftStore.materialOverridesByOperation.mapValues { (_, byKey) ->
            byKey.mapValues { (_, o) ->
                QuoteStorage.QuoteRecord.MaterialOverride(quantity = o.quantity, price = o.price)
            }
        }

        QuoteStorage.upsertQuote(
            requireContext(),
            QuoteStorage.QuoteRecord(
                customerName = QuoteDraftStore.customerName,
                note = QuoteDraftStore.customerNote.takeIf { it.isNotBlank() },
                customerPhone = QuoteDraftStore.customerPhone.takeIf { it.isNotBlank() },
                customerTcNo = QuoteDraftStore.customerTcNo.takeIf { it.isNotBlank() },
                customerBuildingCode = QuoteDraftStore.customerBuildingCode.takeIf { it.isNotBlank() },
                customerInstallationNo = QuoteDraftStore.customerInstallationNo.takeIf { it.isNotBlank() },
                customerAddress = com.ahmetsudeys.dogalgazteklif.data.customer.CustomerStorage.AddressParts(
                    city = QuoteDraftStore.customerCity.takeIf { it.isNotBlank() },
                    district = QuoteDraftStore.customerDistrict.takeIf { it.isNotBlank() },
                    neighborhood = QuoteDraftStore.customerNeighborhood.takeIf { it.isNotBlank() },
                    street = QuoteDraftStore.customerStreet.takeIf { it.isNotBlank() },
                    buildingNo = QuoteDraftStore.customerBuildingNo.takeIf { it.isNotBlank() },
                    apartmentNo = QuoteDraftStore.customerApartmentNo.takeIf { it.isNotBlank() }
                ),
                customerPainted = QuoteDraftStore.customerPainted,
                operations = QuoteDraftStore.selectedOperations,
                materialOverridesByOperation = overrides,
                pipeMeters = QuoteDraftStore.pipeMeters,
                radiatorMeters = QuoteDraftStore.radiatorMeters,
                materialsTotal = materialsTotal,
                projectTotal = project,
                laborTotal = labor,
                radiatorTotal = radiator,
                boilerPresent = QuoteDraftStore.boilerPresent,
                boilerBrand = QuoteDraftStore.boilerBrand.takeIf { it.isNotBlank() },
                boilerModel = QuoteDraftStore.boilerModel.takeIf { it.isNotBlank() },
                boilerPrice = boiler,
                profit = profit,
                total = total,
                discount = QuoteDraftStore.discountAmount,
                createdAtMillis = createdAtMillis,
                status = status
            )
        )
        val msg = if (QuoteDraftStore.editingCreatedAtMillis != null) {
            getString(R.string.quote_updated)
        } else {
            getString(R.string.quote_completed)
        }
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        QuoteDraftStore.reset()

        // Go back to home tab
        findNavController().popBackStack(R.id.homeFragment, false)
    }

    private fun String.toDoubleOrZero(): Double {
        val raw = trim()
        if (raw.isBlank()) return 0.0
        return raw.replace(",", ".").toDoubleOrNull() ?: 0.0
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


