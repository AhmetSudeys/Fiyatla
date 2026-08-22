package com.ahmetsudeys.dogalgazteklif.ui.materials

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.ahmetsudeys.dogalgazteklif.R
import com.ahmetsudeys.dogalgazteklif.data.model.MaterialItem
import com.ahmetsudeys.dogalgazteklif.databinding.DialogListNameBinding
import com.ahmetsudeys.dogalgazteklif.databinding.DialogMaterialEditBinding
import com.ahmetsudeys.dogalgazteklif.databinding.FragmentMaterialsPageBinding
import com.ahmetsudeys.dogalgazteklif.ui.quote.MaterialsAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

class MaterialsPageFragment : Fragment() {

    private var _binding: FragmentMaterialsPageBinding? = null
    private val binding: FragmentMaterialsPageBinding
        get() = requireNotNull(_binding)

    private val vm: MaterialsPageViewModel by viewModels()

    private lateinit var adapter: MaterialsAdapter
    private var fullList: List<MaterialItem> = emptyList()
    private var isCustom: Boolean = false
    private val filterHandler = Handler(Looper.getMainLooper())
    private var filterRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMaterialsPageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val sheetName = requireArguments().getString(ARG_SHEET).orEmpty()
        binding.textPageTitle.text = sheetName.uppercase(TR)

        adapter = MaterialsAdapter(excelStyle = false).apply {
            onItemClick = { _, clicked -> showEditDialog(clicked) }
        }

        binding.recyclerMaterials.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerMaterials.adapter = adapter
        binding.recyclerMaterials.setHasFixedSize(true)
        binding.recyclerMaterials.itemAnimator = null
        val divider = DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        ContextCompat.getDrawable(requireContext(), R.drawable.divider_row)?.let { d ->
            divider.setDrawable(d)
        }
        binding.recyclerMaterials.addItemDecoration(divider)

        binding.editSearch.doAfterTextChanged { editable ->
            scheduleFilter(editable?.toString().orEmpty())
        }

        binding.buttonAddMaterial.setOnClickListener { showAddDialog() }
        binding.buttonListMenu.setOnClickListener { showListMenu(it) }

        vm.state.observe(viewLifecycleOwner) { state ->
            when (state) {
                is MaterialsPageViewModel.UiState.Content -> {
                    fullList = state.items
                    isCustom = state.isCustom
                    // Both built-in ("hazır") and custom lists can be renamed/deleted.
                    binding.buttonListMenu.visibility = View.VISIBLE
                    adapter.setTotal(fullList.sumOf { it.total })
                    // Veri geldiği anda göster: gecikmeli filtre yalnız KULLANICI yazarken
                    // anlamlı. Burada da 140 ms beklenince liste açılışta boş kalıp sonradan
                    // "düşüyor", ekran takılıyormuş gibi görünüyordu.
                    applyFilterNow(binding.editSearch.text?.toString().orEmpty())
                }
                else -> Unit
            }
        }

        vm.listEvent.observe(viewLifecycleOwner) { event ->
            when (event) {
                is MaterialsPageViewModel.ListEvent.Renamed -> {
                    vm.consumeListEvent()
                    (parentFragment as? MaterialsFragment)?.refreshLists(event.newName)
                }
                MaterialsPageViewModel.ListEvent.Deleted -> {
                    vm.consumeListEvent()
                    Toast.makeText(requireContext(), R.string.list_deleted, Toast.LENGTH_SHORT).show()
                    (parentFragment as? MaterialsFragment)?.refreshLists(null)
                }
                MaterialsPageViewModel.ListEvent.Failed -> {
                    vm.consumeListEvent()
                    Toast.makeText(requireContext(), R.string.error_list_name_exists, Toast.LENGTH_SHORT).show()
                }
                null -> Unit
            }
        }

        vm.load(sheetName)
    }

    private fun scheduleFilter(query: String) {
        filterRunnable?.let { filterHandler.removeCallbacks(it) }
        val r = Runnable { applyFilterNow(query) }
        filterRunnable = r
        filterHandler.postDelayed(r, 140L)
    }

    private fun applyFilterNow(query: String) {
        if (_binding == null) return
        // Bekleyen gecikmeli filtre varsa iptal: aynı sonucu iki kez uygulamayalım.
        filterRunnable?.let { filterHandler.removeCallbacks(it) }
        filterRunnable = null
        val q = query.trim().lowercase()
        val filtered = if (q.isBlank()) {
            fullList
        } else {
            fullList.filter { it.name.lowercase().contains(q) }
        }
        adapter.submitList(filtered)
    }

    private fun showAddDialog() {
        val dialogBinding = DialogMaterialEditBinding.inflate(layoutInflater)
        wireNumberSteppers(dialogBinding)
        dialogBinding.editQuantity.setText("1")
        dialogBinding.editPrice.setText("0")

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_material_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.editName.text?.toString().orEmpty().trim()
                if (name.isBlank()) {
                    dialogBinding.inputName.error = getString(R.string.error_material_name_required)
                    return@setOnClickListener
                }
                val q = dialogBinding.editQuantity.readDouble()
                val p = dialogBinding.editPrice.readDouble()
                vm.addMaterial(name, q, p)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showEditDialog(item: MaterialItem) {
        val index = fullList.indexOfFirst { it === item }
        if (index < 0) return

        val dialogBinding = DialogMaterialEditBinding.inflate(layoutInflater)
        wireNumberSteppers(dialogBinding)
        dialogBinding.editName.setText(item.name)
        dialogBinding.editQuantity.setText(formatNumber(item.quantity))
        dialogBinding.editPrice.setText(formatNumber(item.price))

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit_material_title)
            .setView(dialogBinding.root)
            .setNeutralButton(R.string.delete, null)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_changes, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.editName.text?.toString().orEmpty().trim()
                if (name.isBlank()) {
                    dialogBinding.inputName.error = getString(R.string.error_material_name_required)
                    return@setOnClickListener
                }
                val q = dialogBinding.editQuantity.readDouble()
                val p = dialogBinding.editPrice.readDouble()
                vm.updateMaterial(index, name, q, p)
                dialog.dismiss()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                confirmDeleteMaterial(index, item.name)
            }
        }
        dialog.show()
    }

    private fun confirmDeleteMaterial(index: Int, name: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_material_title)
            .setMessage(getString(R.string.delete_material_message, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> vm.deleteMaterial(index) }
            .show()
    }

    private fun showListMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_RENAME, 0, R.string.rename_list)
        popup.menu.add(0, MENU_DELETE, 1, R.string.delete_list)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RENAME -> {
                    showRenameDialog()
                    true
                }
                MENU_DELETE -> {
                    confirmDeleteList()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameDialog() {
        val current = requireArguments().getString(ARG_SHEET).orEmpty()
        val dialogBinding = DialogListNameBinding.inflate(layoutInflater)
        dialogBinding.editListName.setText(binding.textPageTitle.text)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rename_list_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.editListName.text?.toString().orEmpty().trim().uppercase(TR)
                if (name.isBlank()) {
                    dialogBinding.inputListName.error = getString(R.string.error_list_name_required)
                    return@setOnClickListener
                }
                if (!name.equals(current, ignoreCase = true)) {
                    // Title updates once the host rebuilds tabs after a successful rename.
                    binding.textPageTitle.text = name
                }
                vm.renameList(name)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDeleteList() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_list_title)
            .setMessage(getString(R.string.delete_list_message, binding.textPageTitle.text))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_list) { _, _ -> vm.deleteList() }
            .show()
    }

    private fun wireNumberSteppers(dialogBinding: DialogMaterialEditBinding) {
        dialogBinding.buttonQtyMinus.setOnClickListener { adjust(dialogBinding.editQuantity, -1.0) }
        dialogBinding.buttonQtyPlus.setOnClickListener { adjust(dialogBinding.editQuantity, +1.0) }
        dialogBinding.buttonPriceMinus.setOnClickListener { adjust(dialogBinding.editPrice, -10.0) }
        dialogBinding.buttonPricePlus.setOnClickListener { adjust(dialogBinding.editPrice, +10.0) }
    }

    private fun adjust(field: TextInputEditText, delta: Double, min: Double = 0.0) {
        val current = field.readDouble()
        val next = (current + delta).coerceAtLeast(min)
        val text = formatNumber(next)
        field.setText(text)
        field.setSelection(text.length)
    }

    private fun TextInputEditText.readDouble(): Double {
        return text?.toString().orEmpty().replace(",", ".").toDoubleOrNull() ?: 0.0
    }

    private fun formatNumber(value: Double): String {
        return if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        filterRunnable?.let { filterHandler.removeCallbacks(it) }
        filterRunnable = null
        _binding = null
    }

    companion object {
        private const val ARG_SHEET = "sheetName"
        private const val MENU_RENAME = 1
        private const val MENU_DELETE = 2
        private val TR: Locale = Locale.forLanguageTag("tr-TR")

        fun newInstance(sheetName: String): MaterialsPageFragment {
            return MaterialsPageFragment().apply {
                arguments = Bundle().apply { putString(ARG_SHEET, sheetName) }
            }
        }
    }
}
