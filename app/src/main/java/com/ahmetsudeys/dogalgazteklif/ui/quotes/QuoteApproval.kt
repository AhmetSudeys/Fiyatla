package com.ahmetsudeys.dogalgazteklif.ui.quotes

import android.os.Handler
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.ahmetsudeys.dogalgazteklif.R
import com.ahmetsudeys.dogalgazteklif.data.customer.CustomerStorage
import com.ahmetsudeys.dogalgazteklif.data.ledger.LedgerStorage
import com.ahmetsudeys.dogalgazteklif.data.quote.QuoteStatus
import com.ahmetsudeys.dogalgazteklif.data.quote.QuoteStorage
import com.ahmetsudeys.dogalgazteklif.ui.customers.CustomerFormSheet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.ExecutorService

/**
 * Centralizes quote-status changes and the "when a quote is approved, add its customer to
 * Müşterilerim" flow. Shared by Home, Tekliflerim and the quote detail screen.
 */
object QuoteApproval {

    /**
     * Persists [newStatus] for the quote, refreshes the UI, and—if the quote just became
     * APPROVED—offers to add the customer to Müşterilerim.
     */
    fun changeStatus(
        fragment: Fragment,
        io: ExecutorService,
        main: Handler,
        record: QuoteStorage.QuoteRecord,
        newStatus: QuoteStatus,
        onRefresh: () -> Unit
    ) {
        val appCtx = fragment.requireContext().applicationContext
        val wasApproved = record.status == QuoteStatus.APPROVED
        io.execute {
            QuoteStorage.updateStatus(appCtx, record.createdAtMillis, newStatus)
            main.post {
                if (!fragment.isAdded) return@post
                onRefresh()
                if (newStatus == QuoteStatus.APPROVED && !wasApproved) {
                    promoteToCustomer(fragment, io, main, record)
                }
            }
        }
    }

    /**
     * Offers to add this quote's customer to Müşterilerim (also usable as a manual action).
     * [onCustomerSaved] fires (on the main thread) only when the customer is actually persisted —
     * used by the detail screen to auto-approve the quote once its customer is added.
     */
    fun promoteToCustomer(
        fragment: Fragment,
        io: ExecutorService,
        main: Handler,
        record: QuoteStorage.QuoteRecord,
        onCustomerSaved: (() -> Unit)? = null
    ) {
        val ctx = fragment.requireContext()
        val candidate = record.buildCustomerCandidate()
        val name = candidate.name?.takeIf { it.isNotBlank() } ?: record.customerName.ifBlank { "-" }

        val essentialsComplete = !candidate.name.isNullOrBlank() &&
            !candidate.phone.isNullOrBlank() &&
            candidate.address.preview().isNotBlank()

        // Whatever the total of this (approved) quote is becomes the customer's receivable.
        val quoteTotal = record.total

        if (essentialsComplete) {
            // Info already looks complete: offer a quick review or a direct add.
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.approve_add_customer_title)
                .setMessage(ctx.getString(R.string.approve_add_customer_review_message, name))
                .setNeutralButton(R.string.approve_later, null)
                .setNegativeButton(R.string.approve_review) { _, _ ->
                    CustomerFormSheet.show(fragment, candidate) { saved -> saveCustomer(fragment, io, main, saved, quoteTotal, onCustomerSaved) }
                }
                .setPositiveButton(R.string.approve_direct_add) { _, _ ->
                    saveCustomer(fragment, io, main, candidate, quoteTotal, onCustomerSaved)
                }
                .show()
        } else {
            // Missing details: prompt to complete them before adding.
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.approve_add_customer_title)
                .setMessage(ctx.getString(R.string.approve_add_customer_fill_message, name))
                .setNegativeButton(R.string.approve_later, null)
                .setPositiveButton(R.string.approve_review) { _, _ ->
                    CustomerFormSheet.show(fragment, candidate) { saved -> saveCustomer(fragment, io, main, saved, quoteTotal, onCustomerSaved) }
                }
                .show()
        }
    }

    private fun saveCustomer(
        fragment: Fragment,
        io: ExecutorService,
        main: Handler,
        candidate: CustomerStorage.CustomerRecord,
        quoteTotal: Double,
        onCustomerSaved: (() -> Unit)? = null
    ) {
        val appCtx = fragment.requireContext().applicationContext
        io.execute {
            val existing = CustomerStorage.getCustomers(appCtx).firstOrNull { sameCustomer(it, candidate) }
            val now = System.currentTimeMillis()
            // New customers get "now" so they sort to the top of Müşterilerim.
            val createdAt = existing?.createdAtMillis ?: now
            CustomerStorage.upsertCustomer(
                appCtx,
                candidate.copy(createdAtMillis = createdAt, updatedAtMillis = now)
            )
            // Persist the receivable in the ledger so the amount is deterministic and never
            // depends on re-matching the customer to a quote later. Agreement date = approval time.
            LedgerStorage.seedAgreementIfEmpty(appCtx, createdAt, quoteTotal, now)
            main.post {
                if (fragment.isAdded) {
                    Toast.makeText(fragment.requireContext(), R.string.customer_added, Toast.LENGTH_SHORT).show()
                }
                onCustomerSaved?.invoke()
            }
        }
    }

    /** Same person if names match and, when both have phones, phones match too. */
    private fun sameCustomer(a: CustomerStorage.CustomerRecord, b: CustomerStorage.CustomerRecord): Boolean {
        val an = a.name.orEmpty().trim().lowercase()
        val bn = b.name.orEmpty().trim().lowercase()
        if (an.isBlank() || an != bn) return false
        val ap = a.phone.orEmpty().trim()
        val bp = b.phone.orEmpty().trim()
        return ap.isBlank() || bp.isBlank() || ap == bp
    }
}
