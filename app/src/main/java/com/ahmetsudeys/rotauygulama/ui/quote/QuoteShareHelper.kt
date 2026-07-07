package com.ahmetsudeys.rotauygulama.ui.quote

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.Prefs
import com.ahmetsudeys.rotauygulama.data.quote.QuoteDraftStore
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Date
import java.util.Locale

object QuoteShareHelper {

    /** Convenience: build + share on the caller thread (kept for compatibility). */
    fun shareCurrentDraftAsPdf(context: Context) {
        startShare(context, buildDraftPdf(context))
    }

    /** Heavy work (draw + write PDF). Safe to call off the main thread. Returns the shareable Uri. */
    fun buildDraftPdf(context: Context): Uri {
        val money: NumberFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("tr-TR")).apply {
            currency = Currency.getInstance("TRY")
            maximumFractionDigits = 2
        }

        val companyName = Prefs.getCompanyName(context).ifBlank { "-" }
        val companyLogoFile = Prefs.getCompanyLogoFile(context)
        val customer = QuoteDraftStore.customerName.trim().ifBlank { "-" }
        val ops = QuoteDraftStore.selectedOperations.map { it.trim() }.filter { it.isNotBlank() }

        val materialsTotal = QuoteDraftStore.currentMaterialsTotal()
        val boiler = QuoteDraftStore.boilerPrice
        val radiator = QuoteDraftStore.radiatorTotal
        val discount = QuoteDraftStore.discountAmount

        // NOTE: We still use the same grand total calculation the UI uses,
        // but we only print "Genel Toplam" in the PDF for a more premium look.
        val subtotal = materialsTotal +
            QuoteDraftStore.projectTotal +
            QuoteDraftStore.laborTotal +
            QuoteDraftStore.radiatorTotal +
            QuoteDraftStore.boilerPrice +
            QuoteDraftStore.profit
        val grandTotal = (subtotal - discount).coerceAtLeast(0.0)

        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4-ish at 72dpi
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas

        val padding = 40f
        val maxWidth = pageInfo.pageWidth - padding * 2

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        canvas.drawRect(0f, 0f, pageInfo.pageWidth.toFloat(), pageInfo.pageHeight.toFloat(), bgPaint)

        val brandBlue = 0xFF1976D2.toInt()
        val brandRed = 0xFFC62828.toInt()
        val outline = 0xFFE3E6EA.toInt()

        val headerHeight = 128f
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brandBlue }
        canvas.drawRect(0f, 0f, pageInfo.pageWidth.toFloat(), headerHeight, headerPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val headerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val headerSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF444444.toInt()
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val totalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = brandBlue
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x0A000000
            textSize = 56f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        fun wrap(text: String, paint: Paint, width: Float = maxWidth): List<String> {
            val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) return listOf("")
            val lines = ArrayList<String>()
            var current = ""
            for (w in words) {
                val candidate = if (current.isBlank()) w else "$current $w"
                if (paint.measureText(candidate) <= width) {
                    current = candidate
                } else {
                    if (current.isNotBlank()) lines.add(current)
                    current = w
                }
            }
            if (current.isNotBlank()) lines.add(current)
            return lines
        }

        // Header: company + document title + "Sayın <customer>" + logo
        val logoSize = 64f
        val logoRightPadding = 40f
        val headerTextMaxWidth = pageInfo.pageWidth - padding - (logoSize + logoRightPadding + 12f)

        var y = 44f
        // Company name can be long; wrap into max 2 lines inside the header.
        val companyLines = wrap(companyName, headerTitlePaint, headerTextMaxWidth).take(2)
        for (line in companyLines) {
            canvas.drawText(line, padding, y, headerTitlePaint)
            y += 26f
        }
        // Subtitle: document title, plus a warm "Sayın X" greeting right in the blue band.
        val headerSubBold = Paint(headerSubPaint).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        canvas.drawText(context.getString(R.string.quote_output_title), padding, headerHeight - 34f, headerSubPaint)
        if (customer != "-") {
            canvas.drawText("Sayın $customer", padding, headerHeight - 14f, headerSubBold)
        }

        // Logo (top-right)
        runCatching {
            val bmp = companyLogoFile?.absolutePath?.let { decodeSampledBitmap(it, 256, 256) }
            if (bmp != null) {
                val target = RectF(pageInfo.pageWidth - logoRightPadding - logoSize, 28f, pageInfo.pageWidth - logoRightPadding, 28f + logoSize)
                canvas.drawBitmap(bmp, null, target, null)
            }
        }

        // Body card
        val card = RectF(padding, headerHeight + 20f, pageInfo.pageWidth - padding, pageInfo.pageHeight - padding)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = outline
        }
        canvas.drawRoundRect(card, 16f, 16f, cardPaint)
        canvas.drawRoundRect(card, 16f, 16f, strokePaint)

        val contentLeft = padding + 22f
        val contentRight = pageInfo.pageWidth - padding - 22f
        var bodyY = card.top + 34f

        // Date + quote no
        val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale.forLanguageTag("tr-TR")).format(Date())
        canvas.drawText("Tarih: $dateStr", contentLeft, bodyY, labelPaint)
        val rawDocNo = QuoteDraftStore.editingCreatedAtMillis ?: System.currentTimeMillis()
        val shortDocNo = (rawDocNo % 1_000_000L).toString().padStart(6, '0')
        canvas.drawText("Teklif No: $shortDocNo", contentLeft + 250f, bodyY, labelPaint)
        bodyY += 30f

        // Warm greeting paragraph
        val greetPaint = Paint(valuePaint).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        if (customer != "-") {
            canvas.drawText("Sayın $customer,", contentLeft, bodyY, greetPaint)
            bodyY += 22f
        }
        val introMaxWidth = contentRight - contentLeft
        val intro = "İlginiz için teşekkür ederiz. Talebiniz doğrultusunda hazırladığımız fiyat teklifimiz aşağıda bilgilerinize sunulmuştur."
        for (line in wrap(intro, labelPaint, introMaxWidth)) {
            canvas.drawText(line, contentLeft, bodyY, labelPaint)
            bodyY += 16f
        }
        bodyY += 14f

        // İşlemler
        canvas.drawText("İşlemler", contentLeft, bodyY, labelPaint)
        bodyY += 17f
        val opsText = if (ops.isEmpty()) "-" else ops.joinToString(separator = ", ")
        val opsBoldPaint = Paint(valuePaint).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        for (line in wrap(opsText, opsBoldPaint, introMaxWidth)) {
            canvas.drawText(line, contentLeft, bodyY, opsBoldPaint)
            bodyY += 16f
        }
        bodyY += 14f

        // Scope list (text-only, no per-line prices)
        val scopeTitlePaint = Paint(labelPaint).apply {
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Kapsam", contentLeft, bodyY, scopeTitlePaint)
        bodyY += 18f
        val bulletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = brandRed
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val boilerLabel = run {
            val bm = listOf(QuoteDraftStore.boilerBrand, QuoteDraftStore.boilerModel)
                .map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ")
            if (bm.isNotBlank()) "${context.getString(R.string.boiler)}: $bm" else context.getString(R.string.boiler)
        }
        val scopeItems = buildList {
            add("Proje")
            add("İşçilik")
            add("Diğer Giderler")
            if (materialsTotal > 0.0) add("Malzemeler")
            if (QuoteDraftStore.boilerPresent || boiler > 0.0) add(boilerLabel)
            if (radiator > 0.0) add(context.getString(R.string.radiator))
        }
        for (item in scopeItems) {
            canvas.drawText("•", contentLeft + 2f, bodyY, bulletPaint)
            canvas.drawText(item, contentLeft + 16f, bodyY, valuePaint)
            bodyY += 18f
        }

        // Grand total banner (anchored near the bottom so the page reads as one cohesive letter)
        val bannerBottom = pageInfo.pageHeight - padding - 70f
        val bannerTop = bannerBottom - 74f
        val banner = RectF(contentLeft, bannerTop, contentRight, bannerBottom)
        val bannerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brandBlue }
        canvas.drawRoundRect(banner, 16f, 16f, bannerBg)
        val bannerLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt(); textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val bannerValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(context.getString(R.string.grand_total), banner.left + 20f, banner.centerY() - 2f, bannerLabelPaint)
        val totalStr = money.format(grandTotal)
        canvas.drawText(totalStr, banner.right - 20f - bannerValuePaint.measureText(totalStr), banner.centerY() + 22f, bannerValuePaint)

        // Closing + footer note
        val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF666666.toInt(); textSize = 10f }
        canvas.drawText("Sağlıklı günler dileriz.", contentLeft, bannerBottom + 24f, labelPaint)
        val noteY = pageInfo.pageHeight - padding - 16f
        canvas.drawText("Bu teklif bilgilendirme amaçlıdır. Değerler, keşif ve proje detayına göre değişebilir.", contentLeft, noteY, footPaint)

        pdf.finishPage(page)

        // Name the PDF after the customer, e.g. "teklif_Mehmet_Yilmaz.pdf".
        val safeName = QuoteDraftStore.customerName.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "")
            .replace(Regex("\\s+"), "_")
            .take(60)
        val fileBase = if (safeName.isBlank()) "teklif_${System.currentTimeMillis()}" else "teklif_$safeName"
        val outFile = File(context.cacheDir, "$fileBase.pdf")
        FileOutputStream(outFile).use { fos -> pdf.writeTo(fos) }
        pdf.close()

        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", outFile)
    }

    /** Opens the system share sheet for [uri]. Must be called on the main thread. */
    fun startShare(context: Context, uri: Uri) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.send_output)))
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var inSampleSize = 1
            var halfHeight = bounds.outHeight / 2
            var halfWidth = bounds.outWidth / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize.coerceAtLeast(1)
            }
            BitmapFactory.decodeFile(path, opts)
        }.getOrNull()
    }
}


