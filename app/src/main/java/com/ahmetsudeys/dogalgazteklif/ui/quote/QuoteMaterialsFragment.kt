package com.ahmetsudeys.dogalgazteklif.ui.quote

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.tabs.TabLayoutMediator
import com.ahmetsudeys.dogalgazteklif.R
import com.ahmetsudeys.dogalgazteklif.data.quote.QuoteDraftStore
import com.ahmetsudeys.dogalgazteklif.databinding.FragmentQuoteMaterialsBinding
import com.ahmetsudeys.dogalgazteklif.ui.util.setOnSingleClickListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class QuoteMaterialsFragment : Fragment() {

    private var _binding: FragmentQuoteMaterialsBinding? = null
    private val binding: FragmentQuoteMaterialsBinding
        get() = requireNotNull(_binding)

    private var tabMediator: TabLayoutMediator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuoteMaterialsBinding.inflate(inflater, container, false)
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
        val selected = requireArguments().getStringArray(ARG_SELECTED_OPERATIONS)?.toList().orEmpty()
        val pagerAdapter = QuoteMaterialsPagerAdapter(this, selected)
        binding.pagerOperations.adapter = pagerAdapter

        tabMediator = TabLayoutMediator(binding.tabOperations, binding.pagerOperations) { tab, position ->
            tab.text = selected.getOrNull(position).orEmpty()
        }.also { it.attach() }

        binding.textChangeSelection.setOnSingleClickListener {
            findNavController().popBackStack()
        }

        binding.buttonContinue.setOnSingleClickListener {
            findNavController().navigate(R.id.action_quoteMaterialsFragment_to_quoteExtrasFragment)
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
        tabMediator?.detach()
        tabMediator = null
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val ARG_SELECTED_OPERATIONS = "selectedOperations"
    }
}


