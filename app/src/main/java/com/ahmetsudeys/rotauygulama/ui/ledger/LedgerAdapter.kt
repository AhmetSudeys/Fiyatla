package com.ahmetsudeys.rotauygulama.ui.ledger

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.ledger.LedgerCalculator
import com.ahmetsudeys.rotauygulama.databinding.ItemLedgerRowBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Date
import java.util.Locale

class LedgerAdapter(
    private val onRowClick: (LedgerCalculator.LedgerRow) -> Unit
) : ListAdapter<LedgerCalculator.LedgerRow, LedgerAdapter.VH>(Diff) {

    private val money: NumberFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("tr-TR")).apply {
        currency = Currency.getInstance("TRY")
        maximumFractionDigits = 0
    }
    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale("tr", "TR"))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemLedgerRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, onRowClick, money, dateFmt)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(
        private val binding: ItemLedgerRowBinding,
        private val onRowClick: (LedgerCalculator.LedgerRow) -> Unit,
        private val money: NumberFormat,
        private val dateFmt: SimpleDateFormat
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: LedgerCalculator.LedgerRow) {
            val ctx = binding.root.context
            binding.textName.text = item.customer.name?.takeIf { it.isNotBlank() } ?: "-"
            binding.textAgreed.text = money.format(item.agreedAmount)
            binding.textCollected.text = money.format(item.collected)
            binding.textRemaining.text = money.format(item.remaining)

            // Status badge + accent bar
            when {
                item.isOverdue -> {
                    binding.badgeStatus.isVisible = true
                    binding.badgeStatus.text = ctx.getString(R.string.ledger_overdue)
                    binding.badgeStatus.setTextColor(ContextCompat.getColor(ctx, R.color.error_red))
                    binding.badgeStatus.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.error_red_soft)
                    binding.viewAccent.setBackgroundResource(R.color.error_red)
                }
                item.isFullyPaid -> {
                    binding.badgeStatus.isVisible = true
                    binding.badgeStatus.text = ctx.getString(R.string.ledger_paid)
                    binding.badgeStatus.setTextColor(ContextCompat.getColor(ctx, R.color.success_green))
                    binding.badgeStatus.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.success_green_soft)
                    binding.viewAccent.setBackgroundResource(R.color.success_green)
                }
                else -> {
                    binding.badgeStatus.isVisible = false
                    binding.viewAccent.setBackgroundResource(R.color.brand_blue)
                }
            }

            val dateMillis = item.agreedDateMillis
            binding.textDate.text = if (dateMillis != null) {
                ctx.getString(R.string.ledger_agreed_date, dateFmt.format(Date(dateMillis)))
            } else {
                ctx.getString(R.string.ledger_no_date)
            }

            binding.root.setOnClickListener { onRowClick(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<LedgerCalculator.LedgerRow>() {
        override fun areItemsTheSame(
            oldItem: LedgerCalculator.LedgerRow,
            newItem: LedgerCalculator.LedgerRow
        ): Boolean = oldItem.customerId == newItem.customerId

        override fun areContentsTheSame(
            oldItem: LedgerCalculator.LedgerRow,
            newItem: LedgerCalculator.LedgerRow
        ): Boolean = oldItem == newItem
    }
}
