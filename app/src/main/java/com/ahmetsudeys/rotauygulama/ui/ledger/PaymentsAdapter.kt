package com.ahmetsudeys.rotauygulama.ui.ledger

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.ledger.LedgerStorage
import com.ahmetsudeys.rotauygulama.databinding.ItemPaymentRowBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Date
import java.util.Locale

class PaymentsAdapter(
    private val onDelete: (LedgerStorage.Payment) -> Unit
) : ListAdapter<LedgerStorage.Payment, PaymentsAdapter.VH>(Diff) {

    private val money: NumberFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("tr-TR")).apply {
        currency = Currency.getInstance("TRY")
        maximumFractionDigits = 0
    }
    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale("tr", "TR"))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPaymentRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, onDelete, money, dateFmt)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(
        private val binding: ItemPaymentRowBinding,
        private val onDelete: (LedgerStorage.Payment) -> Unit,
        private val money: NumberFormat,
        private val dateFmt: SimpleDateFormat
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: LedgerStorage.Payment) {
            val ctx = binding.root.context
            binding.textAmount.text = money.format(item.amount)
            binding.textMethod.text = ctx.getString(methodLabel(item.method))
            binding.textDate.text = dateFmt.format(Date(item.dateMillis))

            val note = item.note.orEmpty().trim()
            binding.textNote.isVisible = note.isNotBlank()
            binding.textNote.text = if (note.isNotBlank()) "Not: $note" else ""

            binding.imageDelete.setOnClickListener { onDelete(item) }
        }

        private fun methodLabel(method: LedgerStorage.PaymentMethod): Int = when (method) {
            LedgerStorage.PaymentMethod.CASH -> R.string.payment_method_cash
            LedgerStorage.PaymentMethod.CARD -> R.string.payment_method_card
            LedgerStorage.PaymentMethod.TRANSFER -> R.string.payment_method_transfer
        }
    }

    private object Diff : DiffUtil.ItemCallback<LedgerStorage.Payment>() {
        override fun areItemsTheSame(oldItem: LedgerStorage.Payment, newItem: LedgerStorage.Payment): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: LedgerStorage.Payment, newItem: LedgerStorage.Payment): Boolean =
            oldItem == newItem
    }
}
