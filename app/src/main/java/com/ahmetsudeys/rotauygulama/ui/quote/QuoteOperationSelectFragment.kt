package com.ahmetsudeys.rotauygulama.ui.quote

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.excel.ExcelPriceListRepository
import com.ahmetsudeys.rotauygulama.data.quote.QuoteDraftStore
import com.ahmetsudeys.rotauygulama.databinding.FragmentQuoteOperationSelectBinding
import com.ahmetsudeys.rotauygulama.ui.util.setOnSingleClickListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QuoteOperationSelectFragment : Fragment() {

    private var _binding: FragmentQuoteOperationSelectBinding? = null
    private val binding: FragmentQuoteOperationSelectBinding
        get() = requireNotNull(_binding)

    private lateinit var repo: ExcelPriceListRepository
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

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
        repo = ExcelPriceListRepository(requireContext().applicationContext)
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

        // Prefill when editing an existing quote
        if (selectedOperations.isEmpty() && QuoteDraftStore.selectedOperations.isNotEmpty()) {
            selectedOperations.addAll(QuoteDraftStore.selectedOperations)
        }

        binding.recyclerOperations.layoutManager = LinearLayoutManager(requireContext())
        loadOperations()

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

    /**
     * The selectable operation types are exactly the material lists from the Materials screen
     * (built-in Excel lists that haven't been deleted, plus user-created custom lists). This keeps
     * the quote flow perfectly in sync with whatever the user has added/renamed/deleted there.
     * Reading the list may parse the .xlsx on first use, so it runs off the main thread.
     */
    private fun loadOperations() {
        ioExecutor.execute {
            val operations = repo.getAllSheetNames()
            mainHandler.post {
                if (_binding == null) return@post
                val adapter = OperationSelectAdapter(
                    items = operations,
                    selected = selectedOperations,
                    onSelectionChanged = { binding.textError.visibility = View.GONE }
                )
                binding.recyclerOperations.adapter = adapter
            }
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

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }

    private companion object {
        const val ARG_SELECTED_OPERATIONS = "selectedOperations"
    }
}


