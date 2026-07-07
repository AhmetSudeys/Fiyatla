package com.ahmetsudeys.rotauygulama.ui.quotes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.quote.QuoteStatus
import com.ahmetsudeys.rotauygulama.data.quote.QuoteStorage
import com.ahmetsudeys.rotauygulama.databinding.ItemQuoteRowBinding
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

class QuotesAdapter(
    private val onRowClick: (QuoteStorage.QuoteRecord) -> Unit,
    private val onStatusChange: (QuoteStorage.QuoteRecord, QuoteStatus) -> Unit,
    private val onNoteClick: (QuoteStorage.QuoteRecord) -> Unit,
    private val onDeleteClick: (QuoteStorage.QuoteRecord) -> Unit,
    private val showDelete: Boolean
) : ListAdapter<QuoteStorage.QuoteRecord, QuotesAdapter.VH>(Diff) {

    private val money: NumberFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("tr-TR")).apply {
        currency = Currency.getInstance("TRY")
        maximumFractionDigits = 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemQuoteRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, onRowClick, onStatusChange, onNoteClick, onDeleteClick, showDelete, money)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ItemQuoteRowBinding,
        private val onRowClick: (QuoteStorage.QuoteRecord) -> Unit,
        private val onStatusChange: (QuoteStorage.QuoteRecord, QuoteStatus) -> Unit,
        private val onNoteClick: (QuoteStorage.QuoteRecord) -> Unit,
        private val onDeleteClick: (QuoteStorage.QuoteRecord) -> Unit,
        private val showDelete: Boolean,
        private val money: NumberFormat
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: QuoteStorage.QuoteRecord) {
            binding.textCustomer.text = item.customerName.ifBlank { "-" }
            binding.textOperations.text = item.operations.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "-"
            binding.textTotal.text = money.format(item.total)

            QuoteStatusUi.bindPill(
                container = binding.layoutStatus,
                icon = binding.imageStatus,
                label = binding.textStatusLabel,
                caret = binding.imageCaret,
                status = item.status
            )

            binding.viewStatusBar.setBackgroundResource(
                when (item.status) {
                    QuoteStatus.PENDING -> R.color.warning_amber
                    QuoteStatus.APPROVED -> R.color.success_green
                    QuoteStatus.REJECTED -> R.color.error_red
                }
            )

            val note = item.note.orEmpty().trim()
            binding.textNote.isVisible = note.isNotBlank()
            binding.textNote.text = if (note.isNotBlank()) "Not: $note" else ""

            binding.imageDelete.isVisible = showDelete

            binding.root.setOnClickListener { onRowClick(item) }
            binding.imageEye.setOnClickListener { onRowClick(item) }
            binding.layoutStatus.setOnClickListener { anchor ->
                QuoteStatusUi.showPicker(anchor.context, anchor, item.status) { picked ->
                    onStatusChange(item, picked)
                }
            }
            binding.textNote.setOnClickListener { onNoteClick(item) }
            binding.imageDelete.setOnClickListener { onDeleteClick(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<QuoteStorage.QuoteRecord>() {
        override fun areItemsTheSame(oldItem: QuoteStorage.QuoteRecord, newItem: QuoteStorage.QuoteRecord): Boolean {
            return oldItem.createdAtMillis == newItem.createdAtMillis
        }

        override fun areContentsTheSame(oldItem: QuoteStorage.QuoteRecord, newItem: QuoteStorage.QuoteRecord): Boolean {
            return oldItem == newItem
        }
    }
}


