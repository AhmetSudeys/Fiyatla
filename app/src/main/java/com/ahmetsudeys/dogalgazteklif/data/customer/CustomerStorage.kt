package com.ahmetsudeys.dogalgazteklif.data.customer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object CustomerStorage {
    private const val FILE = "rota_customers"
    private const val KEY_CUSTOMERS = "customers"

    data class AddressParts(
        val city: String? = null,
        val district: String? = null,
        val neighborhood: String? = null,
        val street: String? = null,
        val buildingNo: String? = null,
        val apartmentNo: String? = null
    ) {
        fun toJson(): JSONObject {
            val obj = JSONObject()
            obj.put("city", city.orEmpty())
            obj.put("district", district.orEmpty())
            obj.put("neighborhood", neighborhood.orEmpty())
            obj.put("street", street.orEmpty())
            obj.put("buildingNo", buildingNo.orEmpty())
            obj.put("apartmentNo", apartmentNo.orEmpty())
            return obj
        }

        fun preview(): String {
            val parts = buildList {
                city?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
                district?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
                neighborhood?.trim()?.takeIf { it.isNotBlank() }?.let { add("$it Mah.") }
                street?.trim()?.takeIf { it.isNotBlank() }?.let { add("$it Cad.") }
                buildingNo?.trim()?.takeIf { it.isNotBlank() }?.let { add("No: $it") }
                apartmentNo?.trim()?.takeIf { it.isNotBlank() }?.let { add("Daire: $it") }
            }
            return parts.joinToString(", ")
        }

        companion object {
            fun fromJson(obj: JSONObject?): AddressParts {
                if (obj == null) return AddressParts()
                return AddressParts(
                    city = obj.optString("city", "").takeIf { it.isNotBlank() },
                    district = obj.optString("district", "").takeIf { it.isNotBlank() },
                    neighborhood = obj.optString("neighborhood", "").takeIf { it.isNotBlank() },
                    street = obj.optString("street", "").takeIf { it.isNotBlank() },
                    buildingNo = obj.optString("buildingNo", "").takeIf { it.isNotBlank() },
                    apartmentNo = obj.optString("apartmentNo", "").takeIf { it.isNotBlank() }
                )
            }
        }
    }

    data class CustomerRecord(
        val name: String? = null,
        val phone: String? = null,
        val tcNo: String? = null,
        val buildingCode: String? = null,
        val installationNo: String? = null,
        val address: AddressParts = AddressParts(),
        val painted: Boolean = false,
        val createdAtMillis: Long,
        val updatedAtMillis: Long = createdAtMillis
    ) {
        fun toJson(): JSONObject {
            val obj = JSONObject()
            obj.put("name", name.orEmpty())
            obj.put("phone", phone.orEmpty())
            obj.put("tcNo", tcNo.orEmpty())
            obj.put("buildingCode", buildingCode.orEmpty())
            obj.put("installationNo", installationNo.orEmpty())
            obj.put("address", address.toJson())
            obj.put("painted", painted)
            obj.put("createdAtMillis", createdAtMillis)
            obj.put("updatedAtMillis", updatedAtMillis)
            return obj
        }

        companion object {
            fun fromJson(obj: JSONObject): CustomerRecord {
                // Backward compatibility: older versions used two flags (columnPainted / pipePainted).
                val painted = if (obj.has("painted")) {
                    obj.optBoolean("painted", false)
                } else {
                    obj.optBoolean("columnPainted", false) || obj.optBoolean("pipePainted", false)
                }
                return CustomerRecord(
                    name = obj.optString("name", "").takeIf { it.isNotBlank() },
                    phone = obj.optString("phone", "").takeIf { it.isNotBlank() },
                    tcNo = obj.optString("tcNo", "").takeIf { it.isNotBlank() },
                    buildingCode = obj.optString("buildingCode", "").takeIf { it.isNotBlank() },
                    installationNo = obj.optString("installationNo", "").takeIf { it.isNotBlank() },
                    address = AddressParts.fromJson(obj.optJSONObject("address")),
                    painted = painted,
                    createdAtMillis = obj.optLong("createdAtMillis", 0L),
                    updatedAtMillis = obj.optLong("updatedAtMillis", obj.optLong("createdAtMillis", 0L))
                )
            }
        }
    }

    fun getCustomers(context: Context): List<CustomerRecord> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CUSTOMERS, "[]").orEmpty()
        val arr = JSONArray(raw)
        val out = ArrayList<CustomerRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(CustomerRecord.fromJson(obj))
        }
        return out
    }

    fun upsertCustomer(context: Context, record: CustomerRecord) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CUSTOMERS, "[]").orEmpty()
        val arr = JSONArray(raw)

        var replaced = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optLong("createdAtMillis") == record.createdAtMillis) {
                arr.put(i, record.toJson())
                replaced = true
                break
            }
        }
        if (!replaced) arr.put(record.toJson())
        prefs.edit().putString(KEY_CUSTOMERS, arr.toString()).apply()
    }

    fun deleteCustomer(context: Context, createdAtMillis: Long) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CUSTOMERS, "[]").orEmpty()
        val arr = JSONArray(raw)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optLong("createdAtMillis") != createdAtMillis) out.put(obj)
        }
        prefs.edit().putString(KEY_CUSTOMERS, out.toString()).apply()
    }

    fun togglePaint(context: Context, createdAtMillis: Long): CustomerRecord? {
        return updateOne(context, createdAtMillis) { old ->
            old.copy(painted = !old.painted, updatedAtMillis = System.currentTimeMillis())
        }
    }

    fun setPaint(context: Context, createdAtMillis: Long, painted: Boolean): CustomerRecord? {
        return updateOne(context, createdAtMillis) { old ->
            old.copy(painted = painted, updatedAtMillis = System.currentTimeMillis())
        }
    }

    private fun updateOne(
        context: Context,
        createdAtMillis: Long,
        transform: (CustomerRecord) -> CustomerRecord
    ): CustomerRecord? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CUSTOMERS, "[]").orEmpty()
        val arr = JSONArray(raw)

        var updated: CustomerRecord? = null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optLong("createdAtMillis") == createdAtMillis) {
                val current = CustomerRecord.fromJson(obj)
                val next = transform(current)
                arr.put(i, next.toJson())
                updated = next
                break
            }
        }
        prefs.edit().putString(KEY_CUSTOMERS, arr.toString()).apply()
        return updated
    }
}


