package com.ahmetsudeys.rotauygulama.data.ledger

import com.ahmetsudeys.rotauygulama.data.customer.CustomerStorage
import com.ahmetsudeys.rotauygulama.data.quote.QuoteStatus
import com.ahmetsudeys.rotauygulama.data.quote.QuoteStorage
import java.util.Calendar

/**
 * Combines customers, their ledger accounts and approved quotes into the derived numbers the
 * receivables screens display (hybrid agreed amount, collected, remaining, overdue, analytics).
 *
 * Pure/stateless so it can run on a background thread with data already loaded from storage.
 */
object LedgerCalculator {

    /** One customer's receivable state, ready to render. */
    data class LedgerRow(
        val customer: CustomerStorage.CustomerRecord,
        val agreedAmount: Double,       // effective (persisted if set, else suggested from quotes)
        val suggestedAmount: Double,    // from approved quotes
        val isManualAmount: Boolean,    // true when an amount is persisted for this customer
        val collected: Double,
        val agreedDateMillis: Long?,    // anlaşılan tarih
        val dueDateMillis: Long?,       // anlaşılan alacak tarihi (vade)
        val account: LedgerStorage.LedgerAccount?
    ) {
        val phone: String? get() = customer.phone?.trim()?.takeIf { it.isNotBlank() }
        val remaining: Double get() = (agreedAmount - collected).coerceAtLeast(0.0)
        val isFullyPaid: Boolean get() = agreedAmount > 0.0 && remaining <= 0.009
        val isOverdue: Boolean
            get() = remaining > 0.009 &&
                dueDateMillis != null &&
                dueDateMillis < System.currentTimeMillis()

        /** 0f..1f share of the agreed amount that has been collected. */
        val collectedFraction: Float
            get() = if (agreedAmount <= 0.0) 0f else (collected / agreedAmount).coerceIn(0.0, 1.0).toFloat()

        val customerId: Long get() = customer.createdAtMillis
    }

    /** Sum of the customer's APPROVED quote totals. Matches on name (+phone when both have one). */
    fun suggestedAmountFor(
        customer: CustomerStorage.CustomerRecord,
        quotes: List<QuoteStorage.QuoteRecord>
    ): Double {
        return quotes
            .filter { it.status == QuoteStatus.APPROVED && matches(customer, it) }
            .sumOf { it.total }
    }

    private fun matches(customer: CustomerStorage.CustomerRecord, quote: QuoteStorage.QuoteRecord): Boolean {
        val cn = customer.name.orEmpty().trim().lowercase()
        val qn = quote.customerName.trim().lowercase()
        if (cn.isBlank() || cn != qn) return false
        val cp = customer.phone.orEmpty().trim()
        val qp = quote.customerPhone.orEmpty().trim()
        return cp.isBlank() || qp.isBlank() || cp == qp
    }

    fun buildRow(
        customer: CustomerStorage.CustomerRecord,
        account: LedgerStorage.LedgerAccount?,
        quotes: List<QuoteStorage.QuoteRecord>
    ): LedgerRow {
        val suggested = suggestedAmountFor(customer, quotes)
        val persisted = account?.agreedAmount
        return LedgerRow(
            customer = customer,
            agreedAmount = persisted ?: suggested,
            suggestedAmount = suggested,
            isManualAmount = persisted != null,
            collected = account?.collected ?: 0.0,
            agreedDateMillis = account?.agreedDateMillis,
            dueDateMillis = account?.dueDateMillis,
            account = account
        )
    }

    fun buildRows(
        customers: List<CustomerStorage.CustomerRecord>,
        accounts: List<LedgerStorage.LedgerAccount>,
        quotes: List<QuoteStorage.QuoteRecord>
    ): List<LedgerRow> {
        val accByCustomer = accounts.associateBy { it.customerId }
        return customers.map { buildRow(it, accByCustomer[it.createdAtMillis], quotes) }
    }

    // --- Analytics ---------------------------------------------------------

    data class Summary(
        val totalAgreed: Double,
        val totalCollected: Double,
        val totalRemaining: Double
    )

    fun summarize(rows: List<LedgerRow>): Summary = Summary(
        totalAgreed = rows.sumOf { it.agreedAmount },
        totalCollected = rows.sumOf { it.collected },
        totalRemaining = rows.sumOf { it.remaining }
    )

    data class MethodBreakdown(val cash: Double, val card: Double, val transfer: Double) {
        val total: Double get() = cash + card + transfer
    }

    fun methodBreakdown(rows: List<LedgerRow>): MethodBreakdown {
        var cash = 0.0; var card = 0.0; var transfer = 0.0
        rows.forEach { row ->
            row.account?.payments?.forEach { p ->
                when (p.method) {
                    LedgerStorage.PaymentMethod.CASH -> cash += p.amount
                    LedgerStorage.PaymentMethod.CARD -> card += p.amount
                    LedgerStorage.PaymentMethod.TRANSFER -> transfer += p.amount
                }
            }
        }
        return MethodBreakdown(cash, card, transfer)
    }

    /** Collected amount per calendar month, newest first. [monthKey] is "yyyy-MM" for sorting. */
    data class MonthlyRevenue(val year: Int, val month: Int, val total: Double) {
        val monthKey: String get() = "%04d-%02d".format(year, month)
    }

    fun monthlyRevenue(rows: List<LedgerRow>): List<MonthlyRevenue> {
        val byMonth = HashMap<String, Double>()
        val cal = Calendar.getInstance()
        rows.forEach { row ->
            row.account?.payments?.forEach { p ->
                cal.timeInMillis = p.dateMillis
                val key = "%04d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
                byMonth[key] = (byMonth[key] ?: 0.0) + p.amount
            }
        }
        return byMonth.entries
            .map { (key, total) ->
                val (y, m) = key.split("-")
                MonthlyRevenue(y.toInt(), m.toInt(), total)
            }
            .sortedByDescending { it.monthKey }
    }

    /** Customers with remaining debt, largest first. */
    fun topDebtors(rows: List<LedgerRow>, limit: Int = 5): List<LedgerRow> =
        rows.filter { it.remaining > 0.009 }
            .sortedByDescending { it.remaining }
            .take(limit)
}
