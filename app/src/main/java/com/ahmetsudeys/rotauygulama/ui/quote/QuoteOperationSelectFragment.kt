package com.ahmetsudeys.rotauygulama.ui.quote

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.materials.MaterialListStore
import com.ahmetsudeys.rotauygulama.data.quote.QuoteDraftStore
import com.ahmetsudeys.rotauygulama.databinding.FragmentQuoteOperationSelectBinding
import com.ahmetsudeys.rotauygulama.ui.util.setOnSingleClickListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class QuoteOperationSelectFragment : Fragment() {

    private var _binding: FragmentQuoteOperationSelectBinding? = null
    private val binding: FragmentQuoteOperationSelectBinding
        get() = requireNotNull(_binding)

    private val selectedOperations = linkedSetOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuoteOperationSelectBinding.inflate(inflater, container, false)
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
        // Built-in operation types plus any user-created custom lists.
        val customLists = MaterialListStore.getCustomListNames(requireContext().applicationContext)
        val operations = resources.getStringArray(R.array.operation_types).toList() + customLists

        // Prefill when editing an existing quote
        if (selectedOperations.isEmpty() && QuoteDraftStore.selectedOperations.isNotEmpty()) {
            selectedOperations.addAll(QuoteDraftStore.selectedOperations)
        }

        val adapter = OperationSelectAdapter(
            items = operations,
            selected = selectedOperations,
            onSelectionChanged = { binding.textError.visibility = View.GONE }
        )
        binding.recyclerOperations.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerOperations.adapter = adapter

        binding.buttonContinue.setOnSingleClickListener {
            if (selectedOperations.isEmpty()) {
                binding.textError.visibility = View.VISIBLE
                return@setOnSingleClickListener
            }

            val navController = findNavController()
            val nextSelected = selectedOperations.toList()

            // If user changed operation selection (especially in edit mode), remove stale data
            // so totals and materials reflect the new selection.
            val allowed = nextSelected.toSet()
            QuoteDraftStore.materialsByOperation.keys.retainAll(allowed)
            QuoteDraftStore.materialOverridesByOperation.keys.retainAll(allowed)
            QuoteDraftStore.materialsTotalByOperation.keys.retainAll(allowed)

            QuoteDraftStore.selectedOperations = nextSelected
            navController.navigate(
                R.id.action_quoteOperationSelectFragment_to_quoteMaterialsFragment,
                bundleOf(ARG_SELECTED_OPERATIONS to selectedOperations.toTypedArray())
            )
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val ARG_SELECTED_OPERATIONS = "selectedOperations"
    }
}


