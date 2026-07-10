package com.ahmetsudeys.dogalgazteklif.ui.quote

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ahmetsudeys.dogalgazteklif.databinding.FragmentQuoteMaterialsPageBinding
import com.ahmetsudeys.dogalgazteklif.databinding.DialogEditMaterialBinding
import com.ahmetsudeys.dogalgazteklif.R
import com.ahmetsudeys.dogalgazteklif.data.model.MaterialItem
import com.ahmetsudeys.dogalgazteklif.data.quote.QuoteDraftStore

class QuoteMaterialsPageFragment : Fragment() {

    private var _binding: FragmentQuoteMaterialsPageBinding? = null
    private val binding: FragmentQuoteMaterialsPageBinding
        get() = requireNotNull(_binding)

    private lateinit var viewModel: QuoteMaterialsPageViewModel
    private val adapter = MaterialsAdapter()
    private val currentItems = ArrayList<MaterialItem>()
    private val allItems = ArrayList<MaterialItem>()
    private val filterHandler = Handler(Looper.getMainLooper())
    private var filterRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuoteMaterialsPageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val operation = requireArguments().getString(ARG_OPERATION).orEmpty()
        binding.textPageTitle.text = operation

        binding.recyclerMaterials.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerMaterials.adapter = adapter
        binding.recyclerMaterials.setHasFixedSize(true)
        binding.recyclerMaterials.itemAnimator = null
        adapter.onItemClick = { position, item ->
            showEditDialog(operation, position, item)
        }

        binding.editSearch.doAfterTextChanged { editable ->
            scheduleFilter(editable?.toString().orEmpty())
        }

        viewModel = ViewModelProvider(this)[QuoteMaterialsPageViewModel::class.java]
        viewModel.state.observe(viewLifecycleOwner) { state ->
            when (state) {
                is QuoteMaterialsPageViewModel.UiState.Loading -> {
                    // no-op for now (keeps UI simple)
                }

                is QuoteMaterialsPageViewModel.UiState.Content -> {
                    if (state.operationName == operation) {
                        currentItems.clear()
                        allItems.clear()
                        // Always start from repository list so permanent edits in "Malzeme Listesi" are reflected.
                        allItems.addAll(state.items)

                        // Apply quote-only overrides on top (keeps in-quote edits stable while still using fresh base list)
                        val overrides = QuoteDraftStore.materialOverridesByOperation[operation].orEmpty()
                        if (overrides.isNotEmpty()) {
                            for (i in allItems.indices) {
                                val key = materialKey(allItems[i].name)
                                val o = overrides[key] ?: continue
                                allItems[i] = allItems[i].copy(
                                    quantity = o.quantity ?: allItems[i].quantity,
                                    price = o.price ?: allItems[i].price
                                )
                            }
                        }

                        QuoteDraftStore.materialsByOperation[operation] = allItems.toMutableList()
                        val opTotal = allItems.sumOf { it.total }
                        QuoteDraftStore.materialsTotalByOperation[operation] = opTotal
                        QuoteDraftStore.materialsTotalCached = QuoteDraftStore.currentMaterialsTotal()
                        adapter.setTotal(opTotal)
                        scheduleFilter(binding.editSearch.text?.toString().orEmpty())
                    }
                }

                is QuoteMaterialsPageViewModel.UiState.Error -> {
                    if (state.operationName == operation) {
                        currentItems.clear()
                        allItems.clear()
                        adapter.setTotal(0.0)
                        adapter.submitList(emptyList())
                    }
                }
            }
        }

        viewModel.load(operation)
    }

    private fun scheduleFilter(query: String) {
        filterRunnable?.let { filterHandler.removeCallbacks(it) }
        val r = Runnable { applyFilterNow(query) }
        filterRunnable = r
        filterHandler.postDelayed(r, 140L)
    }

    private fun applyFilterNow(query: String) {
        val q = query.trim()
        val filtered = if (q.isBlank()) {
            allItems
        } else {
            allItems.filter { it.name.contains(q, ignoreCase = true) }
        }
        currentItems.clear()
        currentItems.addAll(filtered)
        adapter.submitList(currentItems)
    }

    private fun showEditDialog(operation: String, position: Int, item: MaterialItem) {
        val dialogBinding = DialogEditMaterialBinding.inflate(layoutInflater)
        dialogBinding.textMaterialName.text = item.name
        dialogBinding.editQuantity.setText(item.quantity.toString())
        dialogBinding.editPrice.setText(item.price.toString())

        dialogBinding.buttonQtyMinus.setOnClickListener {
            adjustNumberField(dialogBinding.editQuantity, delta = -1.0, min = 0.0)
        }
        dialogBinding.buttonQtyPlus.setOnClickListener {
            adjustNumberField(dialogBinding.editQuantity, delta = 1.0, min = 0.0)
        }
        dialogBinding.buttonPriceMinus.setOnClickListener {
            adjustNumberField(dialogBinding.editPrice, delta = -10.0, min = 0.0)
        }
        dialogBinding.buttonPricePlus.setOnClickListener {
            adjustNumberField(dialogBinding.editPrice, delta = 10.0, min = 0.0)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.edit_material_title))
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_changes) { _, _ ->
                val newQty = dialogBinding.editQuantity.text?.toString().orEmpty().toDoubleOrNullSafe()
                val newPrice = dialogBinding.editPrice.text?.toString().orEmpty().toDoubleOrNullSafe()
                val updated = item.copy(
                    quantity = newQty ?: item.quantity,
                    price = newPrice ?: item.price
                )

                // Persist quote-only overrides (so we can always reload base list fresh from repo)
                val key = materialKey(item.name)
                val opOverrides = QuoteDraftStore.materialOverridesByOperation
                    .getOrPut(operation) { linkedMapOf() }
                opOverrides[key] = QuoteDraftStore.MaterialOverride(quantity = updated.quantity, price = updated.price)

                // Update in full list (match by normalized key to avoid float equality issues)
                val idxAll = allItems.indexOfFirst { materialKey(it.name) == key }
                if (idxAll != -1) allItems[idxAll] = updated
                QuoteDraftStore.materialsByOperation[operation] = allItems.toMutableList()
                val opTotal = allItems.sumOf { it.total }
                QuoteDraftStore.materialsTotalByOperation[operation] = opTotal
                QuoteDraftStore.materialsTotalCached = QuoteDraftStore.currentMaterialsTotal()

                // Update in filtered list
                if (position in 0 until currentItems.size) currentItems[position] = updated

                adapter.updateItem(position, updated)
                adapter.setTotal(opTotal)
            }
            .show()
    }

    private fun materialKey(name: String): String {
        // Keep consistent-ish matching for Turkish characters and whitespace.
        return name
            .trim()
            .lowercase()
            .replace('ı', 'i')
            .replace('İ', 'i')
            .replace('ş', 's')
            .replace('Ş', 's')
            .replace('ğ', 'g')
            .replace('Ğ', 'g')
            .replace('ç', 'c')
            .replace('Ç', 'c')
            .replace('ö', 'o')
            .replace('Ö', 'o')
            .replace('ü', 'u')
            .replace('Ü', 'u')
    }

    private fun adjustNumberField(
        editText: com.google.android.material.textfield.TextInputEditText,
        delta: Double,
        min: Double
    ) {
        val current = editText.text?.toString().orEmpty().toDoubleOrNullSafe() ?: 0.0
        val next = (current + delta).coerceAtLeast(min)
        val normalized = if (next % 1.0 == 0.0) next.toInt().toString() else next.toString()
        editText.setText(normalized)
        editText.setSelection(normalized.length)
    }

    private fun String.toDoubleOrNullSafe(): Double? {
        val raw = trim()
        if (raw.isBlank()) return null
        return raw.replace(",", ".").toDoubleOrNull()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        filterRunnable?.let { filterHandler.removeCallbacks(it) }
        filterRunnable = null
        _binding = null
    }

    companion object {
        private const val ARG_OPERATION = "operation"

        fun newInstance(operation: String): QuoteMaterialsPageFragment {
            return QuoteMaterialsPageFragment().apply {
                arguments = Bundle().apply { putString(ARG_OPERATION, operation) }
            }
        }
    }
}


