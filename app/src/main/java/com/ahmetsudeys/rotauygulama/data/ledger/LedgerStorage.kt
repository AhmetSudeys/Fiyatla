package com.ahmetsudeys.rotauygulama.data.ledger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the "Alacak Defteri" (receivables ledger) data.
 *
 * Mirrors the existing SharedPreferences + JSON pattern used by [com.ahmetsudeys.rotauygulama.data.customer.CustomerStorage]
 * and [com.ahmetsudeys.rotauygulama.data.quote.QuoteStorage]. Each ledger account is keyed by the
 * customer's [com.ahmetsudeys.rotauygulama.data.customer.CustomerStorage.CustomerRecord.createdAtMillis].
 */
object LedgerStorage {
    private const val FILE = "rota_ledger"
    private const val KEY_ACCOUNTS = "accounts"

    /** How a payment was collected. */
    enum class PaymentMethod {
        CASH,      // Nakit
        CARD,      // Kredi Kartı
        TRANSFER;  // Havale / EFT

        companion object {
            fun fromString(raw: String?): PaymentMethod =
                entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: CASH
        }
    }

    /** A single collection entry against a customer's receivable. */
    data class Payment(
        val id: Long,                 // unique; defaults to creation time
        val amount: Double,
        val method: PaymentMethod,
        val dateMillis: Long,
        val note: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("amount", amount)
            put("method", method.name)
            put("dateMillis", dateMillis)
            put("note", note.orEmpty())
        }

        companion object {
            fun fromJson(obj: JSONObject): Payment = Payment(
                id = obj.optLong("id", 0L),
                amount = obj.optDouble("amount", 0.0),
                method = PaymentMethod.fromString(obj.optString("method", PaymentMethod.CASH.name)),
                dateMillis = obj.optLong("dateMillis", 0L),
                note = obj.optString("note", "").takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * A customer's receivable account.
     *
     * [agreedAmount] is the manually-set agreed total. When null the UI falls back to a value
     * suggested from the customer's approved quotes (hybrid mode).
     */
    data class LedgerAccount(
        val customerId: Long,
        val agreedAmount: Double? = null,
        val agreedDateMillis: Long? = null,
        val payments: List<Payment> = emptyList()
    ) {
        val collected: Double get() = payments.sumOf { it.amount }

        fun toJson(): JSONObject = JSONObject().apply {
            put("customerId", customerId)
            if (agreedAmount != null) put("agreedAmount", agreedAmount)
            if (agreedDateMillis != null) put("agreedDateMillis", agreedDateMillis)
            val arr = JSONArray()
            payments.forEach { arr.put(it.toJson()) }
            put("payments", arr)
        }

        companion object {
            fun fromJson(obj: JSONObject): LedgerAccount {
                val paymentsArr = obj.optJSONArray("payments") ?: JSONArray()
                val payments = ArrayList<Payment>(paymentsArr.length())
                for (i in 0 until paymentsArr.length()) {
                    val p = paymentsArr.optJSONObject(i) ?: continue
                    payments.add(Payment.fromJson(p))
                }
                return LedgerAccount(
                    customerId = obj.optLong("customerId", 0L),
                    agreedAmount = if (obj.has("agreedAmount")) obj.optDouble("agreedAmount") else null,
                    agreedDateMillis = if (obj.has("agreedDateMillis")) obj.optLong("agreedDateMillis") else null,
                    payments = payments
                )
            }
        }
    }

    fun getAccounts(context: Context): List<LedgerAccount> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ACCOUNTS, "[]").orEmpty()
        val arr = JSONArray(raw)
        val out = ArrayList<LedgerAccount>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(LedgerAccount.fromJson(obj))
        }
        return out
    }

    fun getAccount(context: Context, customerId: Long): LedgerAccount? =
        getAccounts(context).firstOrNull { it.customerId == customerId }

    fun upsertAccount(context: Context, account: LedgerAccount) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY_ACCOUNTS, "[]").orEmpty())
        var replaced = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optLong("customerId") == account.customerId) {
                arr.put(i, account.toJson())
                replaced = true
                break
            }
        }
        if (!replaced) arr.put(account.toJson())
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    /** Deletes a customer's whole ledger account (used when a customer is removed). */
    fun deleteAccount(context: Context, customerId: Long) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY_ACCOUNTS, "[]").orEmpty())
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optLong("customerId") != customerId) out.put(obj)
        }
        prefs.edit().putString(KEY_ACCOUNTS, out.toString()).apply()
    }

    /** Sets the agreed amount / date, creating the account if needed. */
    fun setAgreement(context: Context, customerId: Long, agreedAmount: Double?, agreedDateMillis: Long?) {
        val current = getAccount(context, customerId) ?: LedgerAccount(customerId = customerId)
        upsertAccount(context, current.copy(agreedAmount = agreedAmount, agreedDateMillis = agreedDateMillis))
    }

    /** Appends a payment, creating the account if needed. */
    fun addPayment(context: Context, customerId: Long, payment: Payment) {
        val current = getAccount(context, customerId) ?: LedgerAccount(customerId = customerId)
        upsertAccount(context, current.copy(payments = current.payments + payment))
    }

    fun deletePayment(context: Context, customerId: Long, paymentId: Long) {
        val current = getAccount(context, customerId) ?: return
        upsertAccount(context, current.copy(payments = current.payments.filterNot { it.id == paymentId }))
    }
}
