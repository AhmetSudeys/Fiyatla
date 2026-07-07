package com.ahmetsudeys.rotauygulama.ui.quotes

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.quote.QuoteStatus

/**
 * Shared styling + interaction for the editable quote-status chip.
 *
 * The chip is a small pill (soft-colored background, status icon, label, dropdown caret) that
 * makes it obvious the status can be changed by tapping. Tapping opens a picker with all statuses.
 */
object QuoteStatusUi {

    fun labelRes(status: QuoteStatus): Int = when (status) {
        QuoteStatus.PENDING -> R.string.status_pending
        QuoteStatus.APPROVED -> R.string.status_approved
        QuoteStatus.REJECTED -> R.string.status_rejected
    }

    fun iconRes(status: QuoteStatus): Int = when (status) {
        QuoteStatus.PENDING -> R.drawable.ic_status_pending
        QuoteStatus.APPROVED -> R.drawable.ic_status_approved
        QuoteStatus.REJECTED -> R.drawable.ic_status_rejected
    }

    private fun strongColorRes(status: QuoteStatus): Int = when (status) {
        QuoteStatus.PENDING -> R.color.warning_amber
        QuoteStatus.APPROVED -> R.color.success_green
        QuoteStatus.REJECTED -> R.color.error_red
    }

    private fun softColorRes(status: QuoteStatus): Int = when (status) {
        QuoteStatus.PENDING -> R.color.warning_amber_soft
        QuoteStatus.APPROVED -> R.color.success_green_soft
        QuoteStatus.REJECTED -> R.color.error_red_soft
    }

    /** Applies the current status look to a pill made of [container] + [icon] + [label] + [caret]. */
    fun bindPill(
        container: View,
        icon: ImageView,
        label: TextView,
        caret: ImageView,
        status: QuoteStatus
    ) {
        val ctx = container.context
        val strong = ContextCompat.getColor(ctx, strongColorRes(status))
        val soft = ContextCompat.getColor(ctx, softColorRes(status))

        container.backgroundTintList = ColorStateList.valueOf(soft)
        icon.setImageResource(iconRes(status))
        icon.imageTintList = ColorStateList.valueOf(strong)
        label.setText(labelRes(status))
        label.setTextColor(strong)
        caret.imageTintList = ColorStateList.valueOf(strong)
    }

    /** Shows a dropdown letting the user pick any status. Invokes [onPick] only on a real change. */
    fun showPicker(
        context: Context,
        anchor: View,
        current: QuoteStatus,
        onPick: (QuoteStatus) -> Unit
    ) {
        val popup = PopupMenu(context, anchor)
        val order = listOf(QuoteStatus.APPROVED, QuoteStatus.PENDING, QuoteStatus.REJECTED)
        order.forEachIndexed { index, status ->
            val prefix = if (status == current) "✓  " else "     "
            popup.menu.add(0, index, index, prefix + context.getString(labelRes(status)))
        }
        popup.setOnMenuItemClickListener { item ->
            val picked = order.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            if (picked != current) onPick(picked)
            true
        }
        popup.show()
    }
}
