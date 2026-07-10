package com.ahmetsudeys.dogalgazteklif.data.price

import android.content.Context
import org.json.JSONObject
import java.util.Locale

object PriceOverridesStore {
    private const val FILE = "rota_price_overrides"
    private const val KEY = "overrides_json"

    data class Override(val quantity: Double, val price: Double)

    fun getOverride(context: Context, sheetName: String, materialName: String): Override? {
        val obj = readJson(context)
        val sheetKey = sheetName.keyNorm()
        val matKey = materialName.keyNorm()
        val sheetObj = obj.optJSONObject(sheetKey) ?: return null
        val m = sheetObj.optJSONObject(matKey) ?: return null
        return Override(
            quantity = m.optDouble("q", Double.NaN),
            price = m.optDouble("p", Double.NaN)
        ).takeIf { !it.quantity.isNaN() && !it.price.isNaN() }
    }

    fun setOverride(context: Context, sheetName: String, materialName: String, quantity: Double, price: Double) {
        val obj = readJson(context)
        val sheetKey = sheetName.keyNorm()
        val matKey = materialName.keyNorm()

        val sheetObj = obj.optJSONObject(sheetKey) ?: JSONObject().also { obj.put(sheetKey, it) }
        val m = JSONObject().apply {
            put("q", quantity)
            put("p", price)
        }
        sheetObj.put(matKey, m)
        writeJson(context, obj)
    }

    private fun readJson(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "{}").orEmpty()
        return try {
            JSONObject(raw)
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    private fun writeJson(context: Context, obj: JSONObject) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, obj.toString()).apply()
    }

    private fun String.keyNorm(): String {
        return this
            .lowercase(Locale.forLanguageTag("tr-TR"))
            .replace('ı', 'i')
            .replace('İ', 'i')
            .replace('ş', 's')
            .replace('Ş', 's')
            .replace('ğ', 'g')
            .replace('Ğ', 'g')
            .replace('ç', 'c')
            .replace('Ç', 'c')
            .replace('ö', 'o')
            .replace('Ö', 'o')
            .replace('ü', 'u')
            .replace('Ü', 'u')
            .trim()
    }
}


