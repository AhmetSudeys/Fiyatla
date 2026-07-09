package com.ahmetsudeys.rotauygulama.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object CompanyBrandingStore {
    private const val COMPANY_LOGO_FILE_NAME = "company_logo.png"

    /**
     * Copies the selected logo image into app-internal storage and persists its absolute path in [Prefs].
     * Returns the stored [File] on success, or null on failure.
     */
    fun saveCompanyLogoFromUri(context: Context, source: Uri): File? {
        val outFile = File(context.filesDir, COMPANY_LOGO_FILE_NAME)
        return runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            } ?: return null

            Prefs.setCompanyLogoPath(context, outFile.absolutePath)
            outFile
        }.getOrNull()?.takeIf { it.exists() && it.length() > 0 }
    }

    /**
     * Restores the company logo from raw bytes (used by backup restore) into app-internal storage
     * and persists its path in [Prefs]. Returns the stored [File] on success, or null on failure.
     */
    fun saveCompanyLogoFromBytes(context: Context, bytes: ByteArray): File? {
        if (bytes.isEmpty()) return null
        val outFile = File(context.filesDir, COMPANY_LOGO_FILE_NAME)
        return runCatching {
            FileOutputStream(outFile).use { output ->
                output.write(bytes)
                output.flush()
            }
            Prefs.setCompanyLogoPath(context, outFile.absolutePath)
            outFile
        }.getOrNull()?.takeIf { it.exists() && it.length() > 0 }
    }
}


