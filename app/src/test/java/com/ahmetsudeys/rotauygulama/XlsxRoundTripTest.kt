package com.ahmetsudeys.rotauygulama

import com.ahmetsudeys.rotauygulama.data.backup.XlsxReader
import com.ahmetsudeys.rotauygulama.data.backup.XlsxWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Guards the backup/restore core: whatever [XlsxWriter] writes into the machine sheet must come
 * back byte-for-byte through [XlsxReader]. Restore relies on this round-trip being lossless.
 */
class XlsxRoundTripTest {

    // Mirrors android.util.Base64 NO_WRAP: standard alphabet, padding kept, no line breaks.
    private fun b64(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    @Test
    fun machineSheetRoundTrips() {
        // Values with characters that would break naive XML handling if not for Base64.
        val jsonWithSpecials = """[{"name":"Ali & Veli <\"test\">","note":"a>b & c<d"}]"""
        val yedekRows = listOf(
            listOf(XlsxWriter.text("FIYATLA_BACKUP"), XlsxWriter.text("1"), XlsxWriter.text("2026-07-09T10:00:00")),
            listOf(XlsxWriter.text("rota_customers"), XlsxWriter.text("customers"), XlsxWriter.text(b64(jsonWithSpecials))),
            listOf(XlsxWriter.text("rota_prefs"), XlsxWriter.text("company_name"), XlsxWriter.text(b64("Öz Işık Doğalgaz"))),
        )
        // Also include a readable sheet to exercise multi-sheet + number cells.
        val readable = XlsxWriter.Sheet(
            "Özet",
            listOf(
                listOf(XlsxWriter.text("Müşteri sayısı"), XlsxWriter.num(3.0)),
                listOf(XlsxWriter.text("Toplam"), XlsxWriter.num(12500.5)),
            )
        )
        val machine = XlsxWriter.Sheet("Yedek", yedekRows)

        val bos = ByteArrayOutputStream()
        XlsxWriter.write(bos, listOf(readable, machine))

        val rows = XlsxReader.readSheet(ByteArrayInputStream(bos.toByteArray()), "Yedek")
        assertNotNull("Yedek sheet should be found", rows)
        requireNotNull(rows)

        assertEquals(3, rows.size)
        assertEquals("FIYATLA_BACKUP", rows[0][0])
        assertEquals("1", rows[0][1])

        // Base64 payloads must survive intact so the decoded JSON matches the original.
        assertEquals("rota_customers", rows[1][0])
        assertEquals("customers", rows[1][1])
        assertEquals(jsonWithSpecials, String(Base64.getDecoder().decode(rows[1][2]), Charsets.UTF_8))

        assertEquals("rota_prefs", rows[2][0])
        assertEquals("Öz Işık Doğalgaz", String(Base64.getDecoder().decode(rows[2][2]), Charsets.UTF_8))
    }

    @Test
    fun missingSheetReturnsNull() {
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(bos, listOf(XlsxWriter.Sheet("Özet", listOf(listOf(XlsxWriter.text("x"))))))
        assertNull(XlsxReader.readSheet(ByteArrayInputStream(bos.toByteArray()), "Yedek"))
    }
}
