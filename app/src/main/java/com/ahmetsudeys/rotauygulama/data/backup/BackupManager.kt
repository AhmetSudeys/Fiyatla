package com.ahmetsudeys.rotauygulama.data.backup

import android.content.Context
import android.content.Intent
import android.content.pm.LabeledIntent
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.CompanyBrandingStore
import com.ahmetsudeys.rotauygulama.data.Prefs
import com.ahmetsudeys.rotauygulama.data.customer.CustomerStorage
import com.ahmetsudeys.rotauygulama.data.ledger.LedgerStorage
import com.ahmetsudeys.rotauygulama.data.quote.QuoteStatus
import com.ahmetsudeys.rotauygulama.data.quote.QuoteStorage
import com.ahmetsudeys.rotauygulama.ui.onboarding.BackupDownloadActivity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Creates and restores a full backup of the app's local data as a single .xlsx file.
 *
 * The file has human-readable sheets (Özet / Müşteriler / Teklifler / Alacak Defteri) so the user
 * can open it in Excel or Google Sheets and see their data, plus a hidden machine sheet ("Yedek")
 * that carries every SharedPreferences store verbatim (Base64-encoded) so restore is lossless.
 *
 * All data lives in SharedPreferences JSON stores + the company logo image; there is no SQLite DB.
 */
object BackupManager {

    /** SharedPreferences files that together hold all user data. */
    private val DATA_STORES = listOf(
        "rota_prefs",             // company name, logo path, unit rates
        "rota_customers",         // Müşterilerim
        "rota_ledger",            // Alacak Defteri (receivables)
        "rota_quotes",            // Tekliflerim
        "rota_material_lists",    // custom + materialized material lists
        "rota_price_overrides"    // per-material price/qty overrides
    )

    private const val MACHINE_SHEET = "Yedek"
    private const val BACKUP_MARKER = "FIYATLA_BACKUP"
    private const val BACKUP_VERSION = "1"
    private const val LOGO_ROW_KEY = "__LOGO__"
    private const val LOGO_FILE_NAME = "company_logo.png"

    /**
     * Excel caps a single cell at 32,767 characters; a value longer than that makes the whole file
     * open as "corrupt / needs repair". So long backup values are split across consecutive columns
     * (col C, D, E, ...) and stitched back together on restore.
     */
    private const val CELL_CHUNK = 30_000

    private val trLocale: Locale = Locale.forLanguageTag("tr-TR")

    // region Export ----------------------------------------------------------------------------

    /** Default file name (with date) suggested when saving/downloading a backup. */
    fun defaultBackupFileName(): String {
        val dateForName = SimpleDateFormat("yyyy-MM-dd", trLocale).format(Date())
        return "DogalgazUsta_Yedek_$dateForName.xlsx"
    }

    /**
     * Writes the backup .xlsx content straight into [output] (e.g. a user-picked document on the
     * phone's storage). Heavy work; call off the main thread. The caller owns/closes [output].
     */
    fun writeBackup(context: Context, output: java.io.OutputStream) {
        val sheets = buildList {
            add(summarySheet(context))
            add(customersSheet(context))
            add(quotesSheet(context))
            add(ledgerSheet(context))
            add(machineSheet(context))
        }
        XlsxWriter.write(output, sheets)
    }

    /**
     * Builds the backup .xlsx in the cache dir and returns a shareable content Uri.
     * Heavy work; call off the main thread.
     */
    fun buildBackup(context: Context): Uri {
        val outFile = File(context.cacheDir, defaultBackupFileName())
        FileOutputStream(outFile).use { fos -> writeBackup(context, fos) }
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", outFile)
    }

    /**
     * Opens the system share sheet so the user can send the backup to Drive / WhatsApp / Gmail / etc.
     * A "Telefona İndir" target is injected right into that same sheet so the user can also save the
     * .xlsx straight onto the phone (Downloads / Documents) without leaving the chooser.
     */
    fun startShare(context: Context, uri: Uri, chooserTitle: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Doğalgaz Usta - Veri Yedeği")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, chooserTitle)

        // Custom "save to this phone" entry, shown alongside WhatsApp/Gmail/etc.
        val saveToPhone = LabeledIntent(
            Intent(context, BackupDownloadActivity::class.java),
            context.packageName,
            R.string.backup_download,
            R.drawable.ic_download
        )
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf<Intent>(saveToPhone))

        context.startActivity(chooser)
    }

    private fun summarySheet(context: Context): XlsxWriter.Sheet {
        val customers = CustomerStorage.getCustomers(context)
        val quotes = QuoteStorage.getQuotes(context)
        val accounts = LedgerStorage.getAccounts(context)

        val totalAgreed = accounts.sumOf { it.agreedAmount ?: 0.0 }
        val totalCollected = accounts.sumOf { it.collected }
        val totalRemaining = (totalAgreed - totalCollected).coerceAtLeast(0.0)

        val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", trLocale).format(Date())

        val rows = listOf(
            listOf(XlsxWriter.text("Doğalgaz Usta - Veri Yedeği")),
            listOf(XlsxWriter.text("Firma"), XlsxWriter.text(Prefs.getCompanyName(context))),
            listOf(XlsxWriter.text("Oluşturma tarihi"), XlsxWriter.text(dateStr)),
            emptyList(),
            listOf(XlsxWriter.text("Müşteri sayısı"), XlsxWriter.num(customers.size.toDouble())),
            listOf(XlsxWriter.text("Teklif sayısı"), XlsxWriter.num(quotes.size.toDouble())),
            listOf(XlsxWriter.text("Toplam alacak (₺)"), XlsxWriter.num(totalAgreed)),
            listOf(XlsxWriter.text("Toplam tahsil edilen (₺)"), XlsxWriter.num(totalCollected)),
            listOf(XlsxWriter.text("Toplam kalan borç (₺)"), XlsxWriter.num(totalRemaining)),
            emptyList(),
            listOf(XlsxWriter.text("Bu dosya, verilerinizin yedeğidir. Geri yüklemek için uygulamadaki")),
            listOf(XlsxWriter.text("\"Yedekten Geri Yükle\" düğmesini kullanın. \"Yedek\" sayfasını silmeyin.")),
        )
        return XlsxWriter.Sheet("Özet", rows)
    }

    private fun customersSheet(context: Context): XlsxWriter.Sheet {
        val header = listOf(
            "Ad Soyad", "Telefon", "TC Kimlik", "Bina Kodu", "Tesisat No", "Adres", "Boyalı"
        ).map { XlsxWriter.text(it) }

        val rows = ArrayList<List<XlsxWriter.Cell>>()
        rows.add(header)
        for (c in CustomerStorage.getCustomers(context).sortedByDescending { it.createdAtMillis }) {
            rows.add(
                listOf(
                    XlsxWriter.text(c.name),
                    XlsxWriter.text(c.phone),
                    XlsxWriter.text(c.tcNo),
                    XlsxWriter.text(c.buildingCode),
                    XlsxWriter.text(c.installationNo),
                    XlsxWriter.text(c.address.preview()),
                    XlsxWriter.text(if (c.painted) "Evet" else "Hayır")
                )
            )
        }
        return XlsxWriter.Sheet("Müşteriler", rows)
    }

    private fun quotesSheet(context: Context): XlsxWriter.Sheet {
        val header = listOf(
            "Müşteri", "Tarih", "Durum", "Genel Toplam (₺)", "İndirim (₺)", "İşlemler"
        ).map { XlsxWriter.text(it) }
        val dateFmt = SimpleDateFormat("dd.MM.yyyy", trLocale)

        val rows = ArrayList<List<XlsxWriter.Cell>>()
        rows.add(header)
        for (q in QuoteStorage.getQuotes(context).sortedByDescending { it.createdAtMillis }) {
            rows.add(
                listOf(
                    XlsxWriter.text(q.customerName),
                    XlsxWriter.text(dateFmt.format(Date(q.createdAtMillis))),
                    XlsxWriter.text(statusLabel(q.status)),
                    XlsxWriter.num(q.total),
                    XlsxWriter.num(q.discount),
                    XlsxWriter.text(q.operations.joinToString(", "))
                )
            )
        }
        return XlsxWriter.Sheet("Teklifler", rows)
    }

    private fun ledgerSheet(context: Context): XlsxWriter.Sheet {
        val header = listOf(
            "Müşteri", "Telefon", "Anlaşılan (₺)", "Tahsil Edilen (₺)", "Kalan (₺)", "Anlaşılan Tarih", "Ödeme Tarihi"
        ).map { XlsxWriter.text(it) }
        val dateFmt = SimpleDateFormat("dd.MM.yyyy", trLocale)

        val customersById = CustomerStorage.getCustomers(context).associateBy { it.createdAtMillis }

        val rows = ArrayList<List<XlsxWriter.Cell>>()
        rows.add(header)
        for (a in LedgerStorage.getAccounts(context)) {
            val name = customersById[a.customerId]?.name
                ?: a.customerName
                ?: "Silinmiş müşteri"
            val phone = customersById[a.customerId]?.phone ?: a.customerPhone.orEmpty()
            val agreed = a.agreedAmount ?: 0.0
            val remaining = (agreed - a.collected).coerceAtLeast(0.0)
            rows.add(
                listOf(
                    XlsxWriter.text(name),
                    XlsxWriter.text(phone),
                    XlsxWriter.num(agreed),
                    XlsxWriter.num(a.collected),
                    XlsxWriter.num(remaining),
                    XlsxWriter.text(a.agreedDateMillis?.let { dateFmt.format(Date(it)) }.orEmpty()),
                    XlsxWriter.text(a.dueDateMillis?.let { dateFmt.format(Date(it)) }.orEmpty())
                )
            )
        }
        return XlsxWriter.Sheet("Alacak Defteri", rows)
    }

    /** The lossless, machine-readable sheet used by [restore]. */
    private fun machineSheet(context: Context): XlsxWriter.Sheet {
        val rows = ArrayList<List<XlsxWriter.Cell>>()
        // Marker row lets restore verify this is really one of our backups.
        rows.add(
            listOf(
                XlsxWriter.text(BACKUP_MARKER),
                XlsxWriter.text(BACKUP_VERSION),
                XlsxWriter.text(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            )
        )

        for (store in DATA_STORES) {
            val prefs = context.getSharedPreferences(store, Context.MODE_PRIVATE)
            for ((key, value) in prefs.all) {
                if (value is String) {
                    rows.add(machineRow(store, key, encode(value.toByteArray(Charsets.UTF_8))))
                }
            }
        }

        // Company logo (binary) as its own Base64 row.
        Prefs.getCompanyLogoFile(context)?.let { file ->
            runCatching { file.readBytes() }.getOrNull()?.let { bytes ->
                rows.add(machineRow(LOGO_ROW_KEY, LOGO_FILE_NAME, encode(bytes)))
            }
        }

        return XlsxWriter.Sheet(MACHINE_SHEET, rows)
    }

    /** Builds one machine row: [store/marker, key, chunk1, chunk2, ...] respecting Excel's cell cap. */
    private fun machineRow(col0: String, col1: String, value: String): List<XlsxWriter.Cell> {
        val cells = ArrayList<XlsxWriter.Cell>()
        cells.add(XlsxWriter.text(col0))
        cells.add(XlsxWriter.text(col1))
        // chunked() keeps order, so concatenating the chunks on restore reproduces the value exactly.
        val chunks = if (value.isEmpty()) listOf("") else value.chunked(CELL_CHUNK)
        for (chunk in chunks) cells.add(XlsxWriter.text(chunk))
        return cells
    }

    private fun statusLabel(status: QuoteStatus): String = when (status) {
        QuoteStatus.PENDING -> "Beklemede"
        QuoteStatus.APPROVED -> "Onaylandı"
        QuoteStatus.REJECTED -> "Reddedildi"
    }

    // endregion

    // region Restore ---------------------------------------------------------------------------

    sealed interface RestoreResult {
        object Success : RestoreResult
        object InvalidFile : RestoreResult
        object Error : RestoreResult
    }

    /**
     * Restores data from a backup .xlsx at [uri], replacing all current data.
     * Heavy work; call off the main thread.
     */
    fun restore(context: Context, uri: Uri): RestoreResult {
        val sheets = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                XlsxReader.readAllSheets(input)
            }
        }.getOrNull()
        if (sheets.isNullOrEmpty()) return RestoreResult.InvalidFile

        fun List<List<String>>.hasMarker() =
            any { it.getOrNull(0)?.trim() == BACKUP_MARKER }

        // Prefer the "Yedek" sheet, but fall back to any sheet that carries our marker row so a
        // backup still restores even if a spreadsheet app renamed or reordered the sheets.
        val rows = sheets.firstOrNull { it.name == MACHINE_SHEET && it.rows.hasMarker() }?.rows
            ?: sheets.firstOrNull { it.rows.hasMarker() }?.rows
            ?: return RestoreResult.InvalidFile

        return runCatching {
            val byStore = LinkedHashMap<String, MutableList<Pair<String, String>>>()
            var logoBytes: ByteArray? = null

            // Everything after the marker row is restorable data (leading blank/extra rows tolerated).
            val markerIndex = rows.indexOfFirst { it.getOrNull(0)?.trim() == BACKUP_MARKER }
            for (i in (markerIndex + 1) until rows.size) {
                val row = rows[i]
                val col0 = row.getOrNull(0).orEmpty().trim()
                val col1 = row.getOrNull(1).orEmpty()
                if (col0.isBlank()) continue
                // Value may be split across columns C, D, E, ... — stitch them back in order.
                val encoded = buildString {
                    for (c in 2 until row.size) append(row.getOrNull(c).orEmpty())
                }

                if (col0 == LOGO_ROW_KEY) {
                    logoBytes = runCatching { decode(encoded) }.getOrNull()
                    continue
                }
                if (col0 in DATA_STORES) {
                    val value = String(decode(encoded), Charsets.UTF_8)
                    byStore.getOrPut(col0) { mutableListOf() }.add(col1 to value)
                }
            }

            // Replace semantics: wipe every managed store, then write the backed-up keys.
            for (store in DATA_STORES) {
                context.getSharedPreferences(store, Context.MODE_PRIVATE).edit().clear().apply()
            }
            for ((store, entries) in byStore) {
                val editor = context.getSharedPreferences(store, Context.MODE_PRIVATE).edit()
                for ((key, value) in entries) editor.putString(key, value)
                editor.apply()
            }

            // Restore the logo image (this also fixes the now-stale company_logo_path).
            if (logoBytes != null && logoBytes.isNotEmpty()) {
                CompanyBrandingStore.saveCompanyLogoFromBytes(context, logoBytes)
            }

            // The user has clearly onboarded before; don't re-show the first-run notice.
            Prefs.setDisclaimerAccepted(context)

            RestoreResult.Success
        }.getOrElse { RestoreResult.Error }
    }

    // endregion

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text.trim(), Base64.NO_WRAP)
}
