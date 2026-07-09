package com.ahmetsudeys.rotauygulama.data.backup

import java.io.OutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A tiny, dependency-free writer for the small subset of the .xlsx (OpenXML Spreadsheet) format we
 * need for backups. Cells are written as inline strings or numbers (no shared strings table), which
 * keeps both the writer and our own [XlsxReader] simple and robust.
 *
 * We deliberately hand-roll this instead of pulling in Apache POI: the app already ships a
 * dependency-free xlsx *reader* for built-in price lists, and a real .xlsx keeps the backup file
 * openable in Excel / Google Sheets so the user can eyeball their data.
 */
object XlsxWriter {

    sealed interface Cell {
        data class Text(val value: String) : Cell
        data class Num(val value: Double) : Cell
    }

    data class Sheet(val name: String, val rows: List<List<Cell>>)

    fun text(value: String?): Cell = Cell.Text(value.orEmpty())
    fun num(value: Double): Cell = Cell.Num(value)

    fun write(out: OutputStream, sheets: List<Sheet>) {
        require(sheets.isNotEmpty()) { "At least one sheet is required" }
        ZipOutputStream(out).use { zip ->
            zip.putUtf8("[Content_Types].xml", contentTypes(sheets.size))
            zip.putUtf8("_rels/.rels", rootRels())
            zip.putUtf8("xl/workbook.xml", workbook(sheets))
            zip.putUtf8("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            sheets.forEachIndexed { index, sheet ->
                zip.putUtf8("xl/worksheets/sheet${index + 1}.xml", sheetXml(sheet))
            }
        }
    }

    private fun ZipOutputStream.putUtf8(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        for (i in 1..sheetCount) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun rootRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    private fun workbook(sheets: List<Sheet>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        sheets.forEachIndexed { index, sheet ->
            val id = index + 1
            append("""<sheet name="${escape(sanitizeSheetName(sheet.name))}" sheetId="$id" r:id="rId$id"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..sheetCount) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("</Relationships>")
    }

    private fun sheetXml(sheet: Sheet): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        sheet.rows.forEachIndexed { rowIndex, row ->
            val rowNo = rowIndex + 1
            append("""<row r="$rowNo">""")
            row.forEachIndexed { colIndex, cell ->
                val ref = "${colName(colIndex)}$rowNo"
                when (cell) {
                    is Cell.Text -> append(
                        """<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${escape(cell.value)}</t></is></c>"""
                    )
                    is Cell.Num -> append("""<c r="$ref"><v>${formatNumber(cell.value)}</v></c>""")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    /** 0 -> "A", 25 -> "Z", 26 -> "AA". */
    private fun colName(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, ('A' + (i % 26)))
            i = i / 26 - 1
        }
        return sb.toString()
    }

    /** Excel forbids these characters in sheet names and caps the length at 31. */
    private fun sanitizeSheetName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/*?\\[\\]:]"), " ").trim()
        return cleaned.take(31).ifBlank { "Sheet" }
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                // Strip control chars that are illegal in XML 1.0 (tab/newline/CR are allowed).
                else -> if (ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') append(' ') else append(ch)
            }
        }
    }
}
