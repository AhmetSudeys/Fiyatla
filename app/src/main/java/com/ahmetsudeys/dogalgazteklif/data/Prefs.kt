package com.ahmetsudeys.dogalgazteklif.data

import android.content.Context
import java.io.File

object Prefs {
    private const val FILE_NAME = "rota_prefs"
    private const val KEY_COMPANY_NAME = "company_name"
    private const val KEY_COMPANY_LOGO_PATH = "company_logo_path"
    private const val KEY_LABOR_RATE = "labor_rate"
    private const val KEY_RADIATOR_RATE = "radiator_rate"
    private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"

    const val DEFAULT_LABOR_RATE = "400"
    const val DEFAULT_RADIATOR_RATE = "2600"

    /** True once the user has read + accepted the "data is stored only on this device" notice. */
    fun isDisclaimerAccepted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false)
    }

    fun setDisclaimerAccepted(context: Context) {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, true).apply()
    }

    /** Last-used labor unit rate (₺/m). Remembered across quotes. */
    fun getLaborRate(context: Context): String {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LABOR_RATE, DEFAULT_LABOR_RATE)?.trim().orEmpty().ifBlank { DEFAULT_LABOR_RATE }
    }

    fun setLaborRate(context: Context, rate: String) {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LABOR_RATE, rate.trim()).apply()
    }

    /** Last-used radiator unit rate (₺/m). Remembered across quotes. */
    fun getRadiatorRate(context: Context): String {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_RADIATOR_RATE, DEFAULT_RADIATOR_RATE)?.trim().orEmpty().ifBlank { DEFAULT_RADIATOR_RATE }
    }

    fun setRadiatorRate(context: Context, rate: String) {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RADIATOR_RATE, rate.trim()).apply()
    }

    fun getCompanyName(context: Context): String {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_COMPANY_NAME, "")?.trim().orEmpty()
    }

    fun setCompanyName(context: Context, name: String) {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_COMPANY_NAME, name.trim()).apply()
    }

    fun getCompanyLogoPath(context: Context): String {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_COMPANY_LOGO_PATH, "")?.trim().orEmpty()
    }

    fun setCompanyLogoPath(context: Context, absolutePath: String) {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_COMPANY_LOGO_PATH, absolutePath.trim()).apply()
    }

    fun getCompanyLogoFile(context: Context): File? {
        val path = getCompanyLogoPath(context)
        if (path.isBlank()) return null
        val file = File(path)
        return file.takeIf { it.exists() && it.isFile && it.length() > 0 }
    }

    fun hasCompanyName(context: Context): Boolean {
        return getCompanyName(context).isNotBlank()
    }

    fun hasCompanyBranding(context: Context): Boolean {
        return getCompanyName(context).isNotBlank() && getCompanyLogoFile(context) != null
    }
}


