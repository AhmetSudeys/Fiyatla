package com.ahmetsudeys.rotauygulama.data.excel

import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

internal object XlsxZipReader {

    fun readEntries(assetManager: AssetManager, assetFileName: String): Map<String, ByteArray> {
        assetManager.open(assetFileName).use { input ->
            ZipInputStream(input).use { zip ->
                val entries = LinkedHashMap<String, ByteArray>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        entries[entry.name] = zip.readAllBytesCompat()
                    }
                    zip.closeEntry()
                }
                return entries
            }
        }
    }

    private fun ZipInputStream.readAllBytesCompat(): ByteArray {
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream()
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}


