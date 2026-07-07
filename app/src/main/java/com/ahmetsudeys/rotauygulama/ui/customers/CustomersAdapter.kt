package com.ahmetsudeys.rotauygulama.ui.customers

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.res.ColorStateList
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.customer.CustomerStorage
import com.ahmetsudeys.rotauygulama.databinding.ItemCustomerRowBinding
import com.ahmetsudeys.rotauygulama.ui.util.setOnSingleClickListener

class CustomersAdapter(
    private val onEdit: (CustomerStorage.CustomerRecord) -> Unit,
    private val onDelete: (CustomerStorage.CustomerRecord) -> Unit,
    private val onSetPaint: (CustomerStorage.CustomerRecord, Boolean) -> Unit,
    private val onDirections: (CustomerStorage.CustomerRecord, String) -> Unit
) : ListAdapter<CustomerStorage.CustomerRecord, CustomersAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCustomerRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, onEdit, onDelete, onSetPaint, onDirections)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ItemCustomerRowBinding,
        private val onEdit: (CustomerStorage.CustomerRecord) -> Unit,
        private val onDelete: (CustomerStorage.CustomerRecord) -> Unit,
        private val onSetPaint: (CustomerStorage.CustomerRecord, Boolean) -> Unit,
        private val onDirections: (CustomerStorage.CustomerRecord, String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CustomerStorage.CustomerRecord) {
            val ctx = binding.root.context
            val name = item.name.orEmpty().trim().ifBlank { "-" }
            binding.textName.text = name

            val address = item.address.preview().trim().ifBlank { "-" }
            binding.textAddress.text = address

            val phone = item.phone.orEmpty().trim().ifBlank { "-" }
            binding.textPhone.text = phone

            val state = ctx.getString(if (item.painted) R.string.painted else R.string.not_painted)
            binding.textPaintLabel.text = "${ctx.getString(R.string.column_pipe_paint)}: $state"

            val accentBg = if (item.painted) R.color.brand_blue_soft else R.color.brand_red_soft
            val accentFg = if (item.painted) R.color.brand_blue else R.color.brand_red
            binding.viewAccent.setBackgroundColor(ContextCompat.getColor(ctx, accentBg))
            binding.layoutPaint.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, accentBg))
            binding.textPaintLabel.setTextColor(ContextCompat.getColor(ctx, accentFg))
            binding.imagePaintCaret.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, accentFg))

            // Click targets:
            binding.cardRoot.setOnSingleClickListener { onEdit(item) }
            binding.imageDelete.setOnSingleClickListener { onDelete(item) }
            binding.layoutPaint.setOnClickListener { anchor -> showPaintMenu(anchor, item) }
            binding.buttonDirections.setOnSingleClickListener { onDirections(item, address) }
        }

        private fun showPaintMenu(anchor: View, item: CustomerStorage.CustomerRecord) {
            val ctx = anchor.context
            val popup = PopupMenu(ctx, anchor)
            // itemId 1 = Boyalı (painted), itemId 0 = Boyasız (not painted)
            popup.menu.add(0, 1, 0, prefix(item.painted) + ctx.getString(R.string.painted))
            popup.menu.add(0, 0, 1, prefix(!item.painted) + ctx.getString(R.string.not_painted))
            popup.setOnMenuItemClickListener { menuItem ->
                val painted = menuItem.itemId == 1
                if (painted != item.painted) onSetPaint(item, painted)
                true
            }
            popup.show()
        }

        private fun prefix(selected: Boolean): String = if (selected) "✓  " else "     "
    }

    private object Diff : DiffUtil.ItemCallback<CustomerStorage.CustomerRecord>() {
        override fun areItemsTheSame(
            oldItem: CustomerStorage.CustomerRecord,
            newItem: CustomerStorage.CustomerRecord
        ): Boolean = oldItem.createdAtMillis == newItem.createdAtMillis

        override fun areContentsTheSame(
            oldItem: CustomerStorage.CustomerRecord,
            newItem: CustomerStorage.CustomerRecord
        ): Boolean = oldItem == newItem
    }
}


