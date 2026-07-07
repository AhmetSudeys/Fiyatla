package com.ahmetsudeys.rotauygulama.ui.materials

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.excel.ExcelPriceListRepository
import com.ahmetsudeys.rotauygulama.databinding.DialogListNameBinding
import com.ahmetsudeys.rotauygulama.databinding.FragmentMaterialsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import java.util.Locale

class MaterialsFragment : Fragment() {

    private var _binding: FragmentMaterialsBinding? = null
    private val binding: FragmentMaterialsBinding
        get() = requireNotNull(_binding)

    private lateinit var repo: ExcelPriceListRepository
    private var mediator: TabLayoutMediator? = null
    private var sheets: List<String> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMaterialsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        repo = ExcelPriceListRepository(requireContext().applicationContext)
        binding.buttonAddList.setOnClickListener { showCreateListDialog() }
        buildTabs(selectName = null)
    }

    /** Rebuilds the tabs from the repository and optionally selects the tab whose name matches. */
    fun refreshLists(selectName: String?) {
        if (_binding == null) return
        buildTabs(selectName)
    }

    private fun buildTabs(selectName: String?) {
        sheets = repo.getAllSheetNames()

        mediator?.detach()
        val pagerAdapter = MaterialsPagerAdapter(this, sheets)
        binding.viewPager.adapter = pagerAdapter

        mediator = TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = sheets[position].uppercase(TR)
        }.also { it.attach() }

        val target = selectName?.let { name ->
            sheets.indexOfFirst { it.equals(name, ignoreCase = true) }
        } ?: -1
        if (target >= 0) {
            binding.viewPager.setCurrentItem(target, false)
        }
    }

    private fun showCreateListDialog() {
        val dialogBinding = DialogListNameBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.new_list_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.editListName.text?.toString().orEmpty().trim().uppercase(TR)
                when {
                    name.isBlank() -> {
                        dialogBinding.inputListName.error = getString(R.string.error_list_name_required)
                    }
                    !repo.createCustomList(name) -> {
                        dialogBinding.inputListName.error = getString(R.string.error_list_name_exists)
                    }
                    else -> {
                        dialog.dismiss()
                        refreshLists(selectName = name)
                    }
                }
            }
        }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mediator?.detach()
        mediator = null
        _binding = null
    }

    companion object {
        private val TR: Locale = Locale.forLanguageTag("tr-TR")
    }
}
