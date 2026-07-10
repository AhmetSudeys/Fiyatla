package com.ahmetsudeys.dogalgazteklif.data.backup

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Reads back the small .xlsx files produced by [XlsxWriter]. Only what we need for restore:
 * locate a sheet by name and return its rows as strings.
 *
 * Handles both inline strings (what we write) and the shared-strings table (what Excel/Sheets may
 * produce if the user opens and re-saves the backup), so a round-trip through a spreadsheet app
 * doesn't break restore.
 */
object XlsxReader {

    /** One parsed worksheet: its (unescaped) name and its rows. */
    data class SheetData(val name: String, val rows: List<List<String>>)

    /**
     * Reads every worksheet in the workbook. Used by restore so it can locate the backup's machine
     * sheet even if a spreadsheet app renamed/reordered sheets — we just look for the marker row.
     */
    fun readAllSheets(input: InputStream): List<SheetData>? {
        val entries = readZip(input)
        val workbook = entries["xl/workbook.xml"]?.toString(Charsets.UTF_8) ?: return null
        val rels = entries["xl/_rels/workbook.xml.rels"]?.toString(Charsets.UTF_8) ?: return null
        val sharedStrings = entries["xl/sharedStrings.xml"]?.toString(Charsets.UTF_8)?.let { parseSharedStrings(it) }
            ?: emptyList()

        val result = ArrayList<SheetData>()
        for ((name, rId) in findAllSheets(workbook)) {
            val target = findRelTarget(rels, rId) ?: continue
            val sheetXml = entries[normalizeSheetPath(target)]?.toString(Charsets.UTF_8) ?: continue
            result.add(SheetData(name, parseRows(sheetXml, sharedStrings)))
        }
        return result
    }

    private fun findAllSheets(workbookXml: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (m in Regex("""<sheet\b[^>]*?/?>""").findAll(workbookXml)) {
            val tag = m.value
            val name = Regex("""name="([^"]*)"""").find(tag)?.groupValues?.get(1)?.let { unescape(it) } ?: continue
            val rId = Regex("""r:id="([^"]*)"""").find(tag)?.groupValues?.get(1) ?: continue
            out.add(name to rId)
        }
        return out
    }

    /** Returns the rows of [sheetName] (each row a list of cell strings), or null if not found. */
    fun readSheet(input: InputStream, sheetName: String): List<List<String>>? {
        val entries = readZip(input)

        val workbook = entries["xl/workbook.xml"]?.toString(Charsets.UTF_8) ?: return null
        val rels = entries["xl/_rels/workbook.xml.rels"]?.toString(Charsets.UTF_8) ?: return null

        val rId = findSheetRid(workbook, sheetName) ?: return null
        val target = findRelTarget(rels, rId) ?: return null
        val sheetPath = normalizeSheetPath(target)
        val sheetXml = entries[sheetPath]?.toString(Charsets.UTF_8) ?: return null

        val sharedStrings = entries["xl/sharedStrings.xml"]?.toString(Charsets.UTF_8)?.let { parseSharedStrings(it) }
            ?: emptyList()

        return parseRows(sheetXml, sharedStrings)
    }

    private fun readZip(input: InputStream): Map<String, ByteArray> {
        // Buffer to a temp file and read via ZipFile (central directory) instead of ZipInputStream
        // (sequential local headers). ZipFile is far more tolerant of the zip variants that Excel /
        // Google Sheets produce when a user opens and re-saves the backup, so restore keeps working.
        val tmp = File.createTempFile("rota_restore", ".xlsx")
        try {
            FileOutputStream(tmp).use { fos -> input.copyTo(fos) }
            val out = LinkedHashMap<String, ByteArray>()
            ZipFile(tmp).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        zf.getInputStream(entry).use { stream ->
                            out[entry.name] = stream.readBytes()
                        }
                    }
                }
            }
            return out
        } finally {
            tmp.delete()
        }
    }

    private fun findSheetRid(workbookXml: String, sheetName: String): String? {
        val matcher = Regex("""<sheet\b[^>]*?/?>""")
        for (m in matcher.findAll(workbookXml)) {
            val tag = m.value
            val name = Regex("""name="([^"]*)"""").find(tag)?.groupValues?.get(1)?.let { unescape(it) }
            if (name != null && name == sheetName) {
                return Regex("""r:id="([^"]*)"""").find(tag)?.groupValues?.get(1)
            }
        }
        return null
    }

    private fun findRelTarget(relsXml: String, rId: String): String? {
        for (m in Regex("""<Relationship\b[^>]*?/?>""").findAll(relsXml)) {
            val tag = m.value
            val id = Regex("""Id="([^"]*)"""").find(tag)?.groupValues?.get(1)
            if (id == rId) {
                return Regex("""Target="([^"]*)"""").find(tag)?.groupValues?.get(1)
            }
        }
        return null
    }

    private fun normalizeSheetPath(target: String): String {
        val t = target.removePrefix("/")
        return if (t.startsWith("xl/")) t else "xl/$t"
    }

    private fun parseSharedStrings(xml: String): List<String> {
        val out = ArrayList<String>()
        for (m in Regex("""<si\b[^>]*>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val inner = m.groupValues[1]
            // A shared string may hold several <t> runs; concatenate them.
            val sb = StringBuilder()
            for (t in Regex("""<t\b[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL).findAll(inner)) {
                sb.append(unescape(t.groupValues[1]))
            }
            out.add(sb.toString())
        }
        return out
    }

    private fun parseRows(sheetXml: String, sharedStrings: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        for (rowMatch in Regex("""<row\b[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL).findAll(sheetXml)) {
            val rowInner = rowMatch.groupValues[1]
            // Collect cells by their column index so gaps are preserved as blanks.
            val cellsByCol = sortedMapOf<Int, String>()
            for (cellMatch in Regex("""<c\b([^>]*)>(.*?)</c>""", RegexOption.DOT_MATCHES_ALL).findAll(rowInner)) {
                val attrs = cellMatch.groupValues[1]
                val body = cellMatch.groupValues[2]
                val ref = Regex("""r="([A-Z]+)\d+"""").find(attrs)?.groupValues?.get(1)
                val col = if (ref != null) colIndex(ref) else cellsByCol.size
                val type = Regex("""t="([^"]*)"""").find(attrs)?.groupValues?.get(1)
                cellsByCol[col] = cellValue(type, body, sharedStrings)
            }
            if (cellsByCol.isEmpty()) {
                rows.add(emptyList())
                continue
            }
            val maxCol = cellsByCol.lastKey()
            val row = ArrayList<String>(maxCol + 1)
            for (c in 0..maxCol) row.add(cellsByCol[c] ?: "")
            rows.add(row)
        }
        return rows
    }

    private fun cellValue(type: String?, body: String, sharedStrings: List<String>): String {
        return when (type) {
            "inlineStr" -> {
                val sb = StringBuilder()
                for (t in Regex("""<t\b[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL).findAll(body)) {
                    sb.append(unescape(t.groupValues[1]))
                }
                sb.toString()
            }
            "s" -> {
                val idx = Regex("""<v\b[^>]*>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
                    .find(body)?.groupValues?.get(1)?.trim()?.toIntOrNull()
                if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else ""
            }
            "str" -> {
                val v = Regex("""<v\b[^>]*>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                unescape(v.orEmpty())
            }
            else -> {
                // Numeric or untyped: return the raw <v> text.
                val v = Regex("""<v\b[^>]*>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                unescape(v.orEmpty())
            }
        }
    }

    /** "A" -> 0, "Z" -> 25, "AA" -> 26. */
    private fun colIndex(ref: String): Int {
        var result = 0
        for (ch in ref) {
            result = result * 26 + (ch - 'A' + 1)
        }
        return result - 1
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
