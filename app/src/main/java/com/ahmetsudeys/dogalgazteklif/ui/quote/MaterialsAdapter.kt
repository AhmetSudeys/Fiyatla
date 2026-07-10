package com.ahmetsudeys.dogalgazteklif.ui.quote

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.ahmetsudeys.dogalgazteklif.data.model.MaterialItem
import com.ahmetsudeys.dogalgazteklif.databinding.ItemMaterialTotalBinding
import com.ahmetsudeys.dogalgazteklif.databinding.ItemMaterialRowBinding
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

class MaterialsAdapter(
    private val excelStyle: Boolean = false
) : RecyclerView.Adapter<MaterialsAdapter.Vh>() {

    private val items = ArrayList<MaterialItem>()
    private val dfQty = DecimalFormat("0.##")
    private val moneyFormatter: NumberFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("tr-TR")).apply {
        currency = Currency.getInstance("TRY")
        maximumFractionDigits = 2
    }
    private var totalAmount: Double = 0.0

    var onItemClick: ((position: Int, item: MaterialItem) -> Unit)? = null

    fun setTotal(total: Double) {
        totalAmount = total
        notifyItemChanged(items.size) // footer
    }

    fun submitList(newItems: List<MaterialItem>) {
        val old = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                // Name is stable/unique for our lists.
                return old[oldItemPosition].name == newItems[newItemPosition].name
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return old[oldItemPosition] == newItems[newItemPosition]
            }
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
        notifyItemChanged(items.size) // footer
    }

    fun updateItem(position: Int, newItem: MaterialItem) {
        if (position !in 0 until items.size) return
        items[position] = newItem
        notifyItemChanged(position)
        notifyItemChanged(items.size) // footer
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_FOOTER) {
            Vh.Footer(ItemMaterialTotalBinding.inflate(inflater, parent, false))
        } else {
            Vh.Row(ItemMaterialRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        when (holder) {
            is Vh.Row -> {
                val item = items[position]
                holder.bind(item, dfQty, moneyFormatter, excelStyle, position)
                holder.itemView.setOnClickListener { onItemClick?.invoke(position, item) }
            }

            is Vh.Footer -> holder.bind(moneyFormatter.format(totalAmount))
        }
    }

    override fun getItemCount(): Int = items.size + 1

    override fun getItemViewType(position: Int): Int {
        return if (position == items.size) VIEW_TYPE_FOOTER else VIEW_TYPE_ROW
    }

    sealed class Vh(root: android.view.View) : RecyclerView.ViewHolder(root) {
        class Row(
            private val binding: ItemMaterialRowBinding
        ) : Vh(binding.root) {
            fun bind(item: MaterialItem, dfQty: DecimalFormat, money: NumberFormat, excelStyle: Boolean, position: Int) {
                if (excelStyle) {
                    val bg = if (position % 2 == 0) {
                        com.ahmetsudeys.dogalgazteklif.R.color.surface_white
                    } else {
                        com.ahmetsudeys.dogalgazteklif.R.color.bg_light
                    }
                    (binding.root as? com.google.android.material.card.MaterialCardView)?.setCardBackgroundColor(
                        androidx.core.content.ContextCompat.getColor(binding.root.context, bg)
                    )
                }
                binding.textName.text = item.name
                binding.textQuantity.text = dfQty.format(item.quantity)
                binding.textPrice.text = money.format(item.price)
                binding.textTotal.text = money.format(item.total)
            }
        }

        class Footer(
            private val binding: ItemMaterialTotalBinding
        ) : Vh(binding.root) {
            fun bind(totalFormatted: String) {
                binding.textTotalValue.text = totalFormatted
            }
        }
    }

    private companion object {
        const val VIEW_TYPE_ROW = 1
        const val VIEW_TYPE_FOOTER = 2
    }
}


