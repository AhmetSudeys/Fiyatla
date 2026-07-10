package com.ahmetsudeys.dogalgazteklif.ui.quote

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.navigation.fragment.findNavController
import com.ahmetsudeys.dogalgazteklif.R
import com.ahmetsudeys.dogalgazteklif.data.Prefs
import com.ahmetsudeys.dogalgazteklif.data.quote.QuoteDraftStore
import com.ahmetsudeys.dogalgazteklif.databinding.FragmentQuoteExtrasBinding
import com.ahmetsudeys.dogalgazteklif.ui.util.setOnSingleClickListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class QuoteExtrasFragment : Fragment() {

    private var _binding: FragmentQuoteExtrasBinding? = null
    private val binding: FragmentQuoteExtrasBinding
        get() = requireNotNull(_binding)

    private var isUpdating = false
    private var laborAutoMode = true
    private var radiatorAutoMode = true
    private val calcHandler = Handler(Looper.getMainLooper())
    private var laborRunnable: Runnable? = null
    private var radiatorRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuoteExtrasBinding.inflate(inflater, container, false)
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

        // Prefill when editing
        if (QuoteDraftStore.editingCreatedAtMillis != null) {
            isUpdating = true
            try {
                if (QuoteDraftStore.pipeMeters > 0.0) binding.editPipe.setText(QuoteDraftStore.pipeMeters.toCleanString())
                if (QuoteDraftStore.radiatorMeters > 0.0) binding.editRadiator.setText(QuoteDraftStore.radiatorMeters.toCleanString())
                if (QuoteDraftStore.boilerPrice > 0.0) binding.editBoilerPrice.setText(QuoteDraftStore.boilerPrice.toCleanString())
                binding.editBoilerBrand.setText(QuoteDraftStore.boilerBrand)
                binding.editBoilerModel.setText(QuoteDraftStore.boilerModel)
                if (QuoteDraftStore.projectTotal > 0.0) binding.editProject.setText(QuoteDraftStore.projectTotal.toCleanString())
                if (QuoteDraftStore.profit > 0.0) binding.editProfit.setText(QuoteDraftStore.profit.toCleanString())
                if (QuoteDraftStore.laborTotal > 0.0) binding.editLaborTotal.setText(QuoteDraftStore.laborTotal.toCleanString())
                if (QuoteDraftStore.radiatorTotal > 0.0) binding.editRadiatorTotal.setText(QuoteDraftStore.radiatorTotal.toCleanString())
            } finally {
                isUpdating = false
            }

            // Prevent overwriting existing totals during edit
            laborAutoMode = QuoteDraftStore.laborTotal <= 0.0
            radiatorAutoMode = QuoteDraftStore.radiatorTotal <= 0.0
        }

        // "Kombi var mı?" -> reveal brand/model/price only when "Evet".
        setupBoiler()

        // Labor unit rate: remembered from the last quote (editable).
        if (binding.editLaborRate.text.isNullOrBlank()) {
            binding.editLaborRate.setText(Prefs.getLaborRate(requireContext()))
        }

        binding.editPipe.doAfterTextChanged { scheduleLaborRecalc(fromUser = true) }
        binding.editLaborRate.doAfterTextChanged { scheduleLaborRecalc(fromUser = true) }

        binding.editLaborTotal.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // user intent: may override auto
            }
        }
        binding.editLaborTotal.doAfterTextChanged {
            if (isUpdating) return@doAfterTextChanged
            // If user edits total manually, turn off auto mode.
            if (binding.editLaborTotal.hasFocus()) {
                laborAutoMode = false
                updateLaborUiState()
            }
        }

        binding.textRecalculate.setOnSingleClickListener {
            laborAutoMode = true
            scheduleLaborRecalc(fromUser = false, immediate = true)
            updateLaborUiState()
        }

        // Radiator unit rate: remembered from the last quote (editable).
        if (binding.editRadiatorRate.text.isNullOrBlank()) {
            binding.editRadiatorRate.setText(Prefs.getRadiatorRate(requireContext()))
        }
        binding.editRadiator.doAfterTextChanged { scheduleRadiatorRecalc(fromUser = true) }
        binding.editRadiatorRate.doAfterTextChanged { scheduleRadiatorRecalc(fromUser = true) }
        binding.editRadiatorTotal.doAfterTextChanged {
            if (isUpdating) return@doAfterTextChanged
            if (binding.editRadiatorTotal.hasFocus()) {
                radiatorAutoMode = false
                updateRadiatorUiState()
            }
        }
        binding.textRadiatorRecalculate.setOnSingleClickListener {
            radiatorAutoMode = true
            scheduleRadiatorRecalc(fromUser = false, immediate = true)
            updateRadiatorUiState()
        }

        // Run the one-time initial calculations after the enter transition settles so the screen
        // opens smoothly (no work competes with the navigation animation).
        calcHandler.postDelayed({
            if (_binding == null) return@postDelayed
            updateLaborUiState()
            updateRadiatorUiState()
            recalcLaborIfNeeded(fromUser = false)
            recalcRadiatorIfNeeded(fromUser = false)
        }, 200L)

        binding.buttonContinue.setOnSingleClickListener {
            val pipe = binding.editPipe.text?.toString().orEmpty().toNumberOrZeroString()
            val radiator = binding.editRadiator.text?.toString().orEmpty().toNumberOrZeroString()
            val boilerPresent = binding.toggleBoiler.checkedButtonId == R.id.button_boiler_yes
            val boiler = if (boilerPresent) binding.editBoilerPrice.text?.toString().orEmpty().toNumberOrZeroString() else "0"
            val project = binding.editProject.text?.toString().orEmpty().toNumberOrZeroString()
            val labor = binding.editLaborTotal.text?.toString().orEmpty().toNumberOrZeroString()
            val profit = binding.editProfit.text?.toString().orEmpty().toNumberOrZeroString()
            val radiatorTotal = binding.editRadiatorTotal.text?.toString().orEmpty().toNumberOrZeroString()

            QuoteDraftStore.pipeMeters = pipe.toDoubleOrZero()
            QuoteDraftStore.radiatorMeters = radiator.toDoubleOrZero()
            QuoteDraftStore.boilerPresent = boilerPresent
            QuoteDraftStore.boilerBrand = if (boilerPresent) binding.editBoilerBrand.text?.toString().orEmpty().trim() else ""
            QuoteDraftStore.boilerModel = if (boilerPresent) binding.editBoilerModel.text?.toString().orEmpty().trim() else ""
            QuoteDraftStore.boilerPrice = boiler.toDoubleOrZero()
            QuoteDraftStore.projectTotal = project.toDoubleOrZero()
            QuoteDraftStore.laborTotal = labor.toDoubleOrZero()
            QuoteDraftStore.radiatorTotal = radiatorTotal.toDoubleOrZero()
            QuoteDraftStore.profit = profit.toDoubleOrZero()

            // Remember the unit rates so the next quote starts from the same values.
            binding.editLaborRate.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?.let { Prefs.setLaborRate(requireContext(), it) }
            binding.editRadiatorRate.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?.let { Prefs.setRadiatorRate(requireContext(), it) }

            findNavController().navigate(
                R.id.action_quoteExtrasFragment_to_quoteSummaryFragment,
                bundleOf(
                    ARG_PIPE_METERS to pipe,
                    ARG_RADIATOR_METERS to radiator,
                    ARG_BOILER_PRICE to boiler,
                    ARG_LABOR_COST to labor,
                    ARG_PROFIT to profit,
                    ARG_RADIATOR_TOTAL to radiatorTotal
                )
            )
        }
    }

    private fun setupBoiler() {
        binding.toggleBoiler.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                binding.layoutBoilerFields.visibility =
                    if (checkedId == R.id.button_boiler_yes) View.VISIBLE else View.GONE
            }
        }
        val hasBoiler = QuoteDraftStore.boilerPresent ||
            QuoteDraftStore.boilerPrice > 0.0 ||
            QuoteDraftStore.boilerBrand.isNotBlank() ||
            QuoteDraftStore.boilerModel.isNotBlank()
        when {
            hasBoiler -> binding.toggleBoiler.check(R.id.button_boiler_yes)
            QuoteDraftStore.editingCreatedAtMillis != null -> binding.toggleBoiler.check(R.id.button_boiler_no)
            else -> binding.layoutBoilerFields.visibility = View.GONE
        }
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

    private fun recalcLaborIfNeeded(fromUser: Boolean) {
        if (!laborAutoMode) return
        val meters = binding.editPipe.text?.toString().orEmpty().toDoubleOrZero()
        val rate = binding.editLaborRate.text?.toString().orEmpty().toDoubleOrZero()
        val total = meters * rate

        isUpdating = true
        try {
            val text = total.toCleanString()
            if (binding.editLaborTotal.text?.toString() != text) {
                binding.editLaborTotal.setText(text)
                binding.editLaborTotal.setSelection(text.length)
            }
        } finally {
            isUpdating = false
        }
        if (fromUser) updateLaborUiState()
    }

    private fun updateLaborUiState() {
        // When auto is off, show "Yeniden Hesapla" affordance
        binding.textRecalculate.visibility = if (laborAutoMode) View.GONE else View.VISIBLE
        binding.inputLaborTotal.helperText = if (laborAutoMode) null else getString(R.string.extras_hint)
    }

    private fun recalcRadiatorIfNeeded(fromUser: Boolean) {
        if (!radiatorAutoMode) return
        val meters = binding.editRadiator.text?.toString().orEmpty().toDoubleOrZero()
        val rate = binding.editRadiatorRate.text?.toString().orEmpty().toDoubleOrZero()
        if (meters <= 0.0 || rate <= 0.0) {
            // Keep it consistent with labor: show 0 instead of an empty field/placeholder.
            isUpdating = true
            try {
                if (binding.editRadiatorTotal.text?.toString() != "0") {
                    binding.editRadiatorTotal.setText("0")
                    binding.editRadiatorTotal.setSelection(1)
                }
            } finally {
                isUpdating = false
            }
            if (fromUser) updateRadiatorUiState()
            return
        }

        val total = meters * rate
        isUpdating = true
        try {
            val text = total.toCleanString()
            if (binding.editRadiatorTotal.text?.toString() != text) {
                binding.editRadiatorTotal.setText(text)
                binding.editRadiatorTotal.setSelection(text.length)
            }
        } finally {
            isUpdating = false
        }
        if (fromUser) updateRadiatorUiState()
    }

    private fun scheduleLaborRecalc(fromUser: Boolean, immediate: Boolean = false) {
        laborRunnable?.let { calcHandler.removeCallbacks(it) }
        val r = Runnable { if (_binding != null) recalcLaborIfNeeded(fromUser = fromUser) }
        laborRunnable = r
        if (immediate) calcHandler.post(r) else calcHandler.postDelayed(r, 120L)
    }

    private fun scheduleRadiatorRecalc(fromUser: Boolean, immediate: Boolean = false) {
        radiatorRunnable?.let { calcHandler.removeCallbacks(it) }
        val r = Runnable { if (_binding != null) recalcRadiatorIfNeeded(fromUser = fromUser) }
        radiatorRunnable = r
        if (immediate) calcHandler.post(r) else calcHandler.postDelayed(r, 120L)
    }

    private fun updateRadiatorUiState() {
        binding.textRadiatorRecalculate.visibility = if (radiatorAutoMode) View.GONE else View.VISIBLE
        binding.inputRadiatorTotal.helperText = if (radiatorAutoMode) null else getString(R.string.extras_hint)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        laborRunnable?.let { calcHandler.removeCallbacks(it) }
        radiatorRunnable?.let { calcHandler.removeCallbacks(it) }
        laborRunnable = null
        radiatorRunnable = null
        _binding = null
    }

    private fun String.toNumberOrZeroString(): String {
        val raw = trim()
        if (raw.isBlank()) return "0"
        val parsed = raw.replace(",", ".").toDoubleOrNull() ?: 0.0
        // Keep it compact for args (no currency formatting here)
        return if (parsed % 1.0 == 0.0) parsed.toInt().toString() else parsed.toString()
    }

    private fun String.toDoubleOrZero(): Double {
        val raw = trim()
        if (raw.isBlank()) return 0.0
        return raw.replace(",", ".").toDoubleOrNull() ?: 0.0
    }

    private fun Double.toCleanString(): String {
        return if (this % 1.0 == 0.0) this.toInt().toString() else this.toString()
    }

    private companion object {
        const val ARG_PIPE_METERS = "pipeMeters"
        const val ARG_RADIATOR_METERS = "radiatorMeters"
        const val ARG_BOILER_PRICE = "boilerPrice"
        const val ARG_LABOR_COST = "laborCost"
        const val ARG_PROFIT = "profit"
        const val ARG_RADIATOR_TOTAL = "radiatorTotal"
    }
}


