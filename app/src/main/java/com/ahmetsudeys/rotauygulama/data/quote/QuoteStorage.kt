package com.ahmetsudeys.rotauygulama.data.quote

import android.content.Context
import com.ahmetsudeys.rotauygulama.data.customer.CustomerStorage
import org.json.JSONArray
import org.json.JSONObject

object QuoteStorage {
    private const val FILE = "rota_quotes"
    private const val KEY_QUOTES = "quotes"

    fun upsertQuote(context: Context, quote: QuoteRecord) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_QUOTES, "[]").orEmpty()
        val arr = JSONArray(raw)

        var replaced = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optLong("createdAtMillis") == quote.createdAtMillis) {
                arr.put(i, quote.toJson())
                replaced = true
                break
            }
        }
        if (!replaced) arr.put(quote.toJson())
        prefs.edit().putString(KEY_QUOTES, arr.toString()).apply()
    }

    fun addQuote(context: Context, quote: QuoteRecord) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_QUOTES, "[]").orEmpty()
        val arr = JSONArray(current)
        arr.put(quote.toJson())
        prefs.edit().putString(KEY_QUOTES, arr.toString()).apply()
    }

    fun deleteQuote(context: Context, createdAtMillis: Long) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_QUOTES, "[]").orEmpty()
        val arr = JSONArray(raw)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optLong("createdAtMillis") != createdAtMillis) out.put(obj)
        }
        prefs.edit().putString(KEY_QUOTES, out.toString()).apply()
    }

    fun getQuotes(context: Context): List<QuoteRecord> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_QUOTES, "[]").orEmpty()
        val arr = JSONArray(raw)
        val out = ArrayList<QuoteRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(QuoteRecord.fromJson(obj))
        }
        return out
    }

    fun updateStatus(context: Context, createdAtMillis: Long, status: QuoteStatus) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_QUOTES, "[]").orEmpty()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optLong("createdAtMillis") == createdAtMillis) {
                obj.put("status", status.name)
                break
            }
        }
        prefs.edit().putString(KEY_QUOTES, arr.toString()).apply()
    }

    data class QuoteRecord(
        val customerName: String,
        val note: String? = null,
        val customerPhone: String? = null,
        val customerTcNo: String? = null,
        val customerBuildingCode: String? = null,
        val customerInstallationNo: String? = null,
        val customerAddress: CustomerStorage.AddressParts = CustomerStorage.AddressParts(),
        val customerPainted: Boolean = false,
        val operations: List<String>,
        val materialOverridesByOperation: Map<String, Map<String, MaterialOverride>> = emptyMap(),
        val pipeMeters: Double = 0.0,
        val radiatorMeters: Double = 0.0,
        val materialsTotal: Double = 0.0,
        val projectTotal: Double = 0.0,
        val laborTotal: Double = 0.0,
        val radiatorTotal: Double = 0.0,
        val boilerPresent: Boolean = false,
        val boilerBrand: String? = null,
        val boilerModel: String? = null,
        val boilerPrice: Double = 0.0,
        val profit: Double = 0.0,
        val total: Double,
        val discount: Double,
        val createdAtMillis: Long,
        val status: QuoteStatus = QuoteStatus.PENDING
    ) {
        data class MaterialOverride(val quantity: Double? = null, val price: Double? = null) {
            fun toJson(): JSONObject {
                val obj = JSONObject()
                if (quantity != null) obj.put("quantity", quantity)
                if (price != null) obj.put("price", price)
                return obj
            }

            companion object {
                fun fromJson(obj: JSONObject?): MaterialOverride {
                    if (obj == null) return MaterialOverride()
                    val q = obj.optDouble("quantity", Double.NaN).takeIf { !it.isNaN() }
                    val p = obj.optDouble("price", Double.NaN).takeIf { !it.isNaN() }
                    return MaterialOverride(quantity = q, price = p)
                }
            }
        }

        /** Builds a customer record from the customer info captured on this quote. */
        fun buildCustomerCandidate(): CustomerStorage.CustomerRecord {
            return CustomerStorage.CustomerRecord(
                name = customerName.trim().takeIf { it.isNotBlank() },
                phone = customerPhone?.trim()?.takeIf { it.isNotBlank() },
                tcNo = customerTcNo?.trim()?.takeIf { it.isNotBlank() },
                buildingCode = customerBuildingCode?.trim()?.takeIf { it.isNotBlank() },
                installationNo = customerInstallationNo?.trim()?.takeIf { it.isNotBlank() },
                address = customerAddress,
                painted = customerPainted,
                createdAtMillis = createdAtMillis
            )
        }

        fun toJson(): JSONObject {
            val obj = JSONObject()
            obj.put("customerName", customerName)
            obj.put("note", note.orEmpty())
            obj.put("customerPhone", customerPhone.orEmpty())
            obj.put("customerTcNo", customerTcNo.orEmpty())
            obj.put("customerBuildingCode", customerBuildingCode.orEmpty())
            obj.put("customerInstallationNo", customerInstallationNo.orEmpty())
            obj.put("customerAddress", customerAddress.toJson())
            obj.put("customerPainted", customerPainted)
            obj.put("operations", JSONArray(operations))
            obj.put("pipeMeters", pipeMeters)
            obj.put("radiatorMeters", radiatorMeters)

            // operation -> key -> {quantity, price}
            val overridesObj = JSONObject()
            for ((operation, byKey) in materialOverridesByOperation) {
                val byKeyObj = JSONObject()
                for ((key, override) in byKey) {
                    byKeyObj.put(key, override.toJson())
                }
                overridesObj.put(operation, byKeyObj)
            }
            obj.put("materialOverridesByOperation", overridesObj)

            obj.put("materialsTotal", materialsTotal)
            obj.put("projectTotal", projectTotal)
            obj.put("laborTotal", laborTotal)
            obj.put("radiatorTotal", radiatorTotal)
            obj.put("boilerPresent", boilerPresent)
            obj.put("boilerBrand", boilerBrand.orEmpty())
            obj.put("boilerModel", boilerModel.orEmpty())
            obj.put("boilerPrice", boilerPrice)
            obj.put("profit", profit)
            obj.put("total", total)
            obj.put("discount", discount)
            obj.put("createdAtMillis", createdAtMillis)
            obj.put("status", status.name)
            return obj
        }

        companion object {
            fun fromJson(obj: JSONObject): QuoteRecord {
                val opsArr = obj.optJSONArray("operations") ?: JSONArray()
                val ops = buildList {
                    for (i in 0 until opsArr.length()) {
                        val s = opsArr.optString(i).orEmpty().trim()
                        if (s.isNotBlank()) add(s)
                    }
                }

                val statusRaw = obj.optString("status", QuoteStatus.PENDING.name)
                val status = QuoteStatus.fromString(statusRaw)

                val overridesOut = linkedMapOf<String, Map<String, MaterialOverride>>()
                val overridesRoot = obj.optJSONObject("materialOverridesByOperation")
                if (overridesRoot != null) {
                    val opKeys = overridesRoot.keys()
                    while (opKeys.hasNext()) {
                        val operation = opKeys.next().orEmpty().trim()
                        if (operation.isBlank()) continue
                        val byKeyObj = overridesRoot.optJSONObject(operation) ?: continue
                        val byKeyOut = linkedMapOf<String, MaterialOverride>()
                        val keyIter = byKeyObj.keys()
                        while (keyIter.hasNext()) {
                            val key = keyIter.next().orEmpty().trim()
                            if (key.isBlank()) continue
                            byKeyOut[key] = MaterialOverride.fromJson(byKeyObj.optJSONObject(key))
                        }
                        overridesOut[operation] = byKeyOut
                    }
                }

                return QuoteRecord(
                    customerName = obj.optString("customerName", ""),
                    note = obj.optString("note", "").takeIf { it.isNotBlank() },
                    customerPhone = obj.optString("customerPhone", "").takeIf { it.isNotBlank() },
                    customerTcNo = obj.optString("customerTcNo", "").takeIf { it.isNotBlank() },
                    customerBuildingCode = obj.optString("customerBuildingCode", "").takeIf { it.isNotBlank() },
                    customerInstallationNo = obj.optString("customerInstallationNo", "").takeIf { it.isNotBlank() },
                    customerAddress = CustomerStorage.AddressParts.fromJson(obj.optJSONObject("customerAddress")),
                    customerPainted = obj.optBoolean("customerPainted", false),
                    operations = ops,
                    materialOverridesByOperation = overridesOut,
                    pipeMeters = obj.optDouble("pipeMeters", 0.0),
                    radiatorMeters = obj.optDouble("radiatorMeters", 0.0),
                    materialsTotal = obj.optDouble("materialsTotal", 0.0),
                    projectTotal = obj.optDouble("projectTotal", 0.0),
                    laborTotal = obj.optDouble("laborTotal", 0.0),
                    radiatorTotal = obj.optDouble("radiatorTotal", 0.0),
                    boilerPresent = obj.optBoolean("boilerPresent", obj.optDouble("boilerPrice", 0.0) > 0.0),
                    boilerBrand = obj.optString("boilerBrand", "").takeIf { it.isNotBlank() },
                    boilerModel = obj.optString("boilerModel", "").takeIf { it.isNotBlank() },
                    boilerPrice = obj.optDouble("boilerPrice", 0.0),
                    profit = obj.optDouble("profit", 0.0),
                    total = obj.optDouble("total", 0.0),
                    discount = obj.optDouble("discount", 0.0),
                    createdAtMillis = obj.optLong("createdAtMillis", 0L),
                    status = status
                )
            }
        }
    }
}


