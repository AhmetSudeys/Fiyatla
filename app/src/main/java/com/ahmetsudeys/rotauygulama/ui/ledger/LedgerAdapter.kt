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

            val phone = item.phone
            binding.textPhone.isVisible = phone != null
            binding.textPhone.text = phone.orEmpty()

            // Status badge + accent bar
            val accentColor: Int = when {
                item.isOverdue -> {
                    binding.badgeStatus.isVisible = true
                    binding.badgeStatus.text = ctx.getString(R.string.ledger_overdue)
                    binding.badgeStatus.setTextColor(ContextCompat.getColor(ctx, R.color.accent_red))
                    binding.badgeStatus.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.error_red_soft)
                    R.color.accent_red
                }
                item.isFullyPaid -> {
                    binding.badgeStatus.isVisible = true
                    binding.badgeStatus.text = ctx.getString(R.string.ledger_paid)
                    binding.badgeStatus.setTextColor(ContextCompat.getColor(ctx, R.color.accent_green))
                    binding.badgeStatus.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.success_green_soft)
                    R.color.accent_green
                }
                else -> {
                    binding.badgeStatus.isVisible = false
                    R.color.accent_blue
                }
            }
            binding.viewAccent.backgroundTintList = ContextCompat.getColorStateList(ctx, accentColor)

            // Collected progress bar
            val f = item.collectedFraction
            (binding.barFill.layoutParams as android.widget.LinearLayout.LayoutParams).weight = f
            (binding.barEmpty.layoutParams as android.widget.LinearLayout.LayoutParams).weight = 1f - f
            binding.barFill.requestLayout()
            binding.barEmpty.requestLayout()

            val dateMillis = item.agreedDateMillis
            binding.textDate.text = if (dateMillis != null) {
                ctx.getString(R.string.ledger_agreed_date, dateFmt.format(Date(dateMillis)))
            } else {
                ctx.getString(R.string.ledger_no_date)
            }

            // Payment date passed and the customer still owes -> red warning.
            binding.textOverdueNotice.isVisible = item.isOverdue

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
