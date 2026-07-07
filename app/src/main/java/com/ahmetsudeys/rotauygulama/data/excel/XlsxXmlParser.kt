package com.ahmetsudeys.rotauygulama.data.excel

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

internal object XlsxXmlParser {

    fun parseSharedStrings(bytes: ByteArray): List<String> {
        val result = ArrayList<String>()
        parse(bytes) { parser ->
            var eventType = parser.eventType
            var inT = false
            var current = StringBuilder()
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "si") {
                            current = StringBuilder()
                        } else if (parser.name == "t") {
                            inT = true
                        }
                    }

                    XmlPullParser.TEXT -> {
                        if (inT) current.append(parser.text)
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name == "t") {
                            inT = false
                        } else if (parser.name == "si") {
                            result.add(current.toString())
                        }
                    }
                }
                eventType = parser.next()
            }
        }
        return result
    }

    fun parseWorkbookSheetRefs(workbookXml: ByteArray): List<SheetRef> {
        val refs = ArrayList<SheetRef>()
        parse(workbookXml) { parser ->
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                    val name = parser.getAttributeValue(null, "name").orEmpty()
                    val rId = parser.getAttributeValue(NS_REL, "id").orEmpty()
                    refs.add(SheetRef(name = name, relId = rId))
                }
                eventType = parser.next()
            }
        }
        return refs
    }

    fun parseWorkbookRelationships(relsXml: ByteArray): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        parse(relsXml) { parser ->
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                    val id = parser.getAttributeValue(null, "Id").orEmpty()
                    val target = parser.getAttributeValue(null, "Target").orEmpty()
                    if (id.isNotBlank() && target.isNotBlank()) {
                        map[id] = target
                    }
                }
                eventType = parser.next()
            }
        }
        return map
    }

    /**
     * Returns: rowIndex -> (columnLetters -> valueString)
     */
    fun parseSheetCells(sheetXml: ByteArray, sharedStrings: List<String>): Map<Int, Map<String, String>> {
        val rows = LinkedHashMap<Int, MutableMap<String, String>>()
        parse(sheetXml) { parser ->
            var eventType = parser.eventType
            var currentRow: Int? = null
            var currentCellRef: String? = null
            var currentCellType: String? = null
            var inV = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "row" -> {
                                currentRow = parser.getAttributeValue(null, "r")?.toIntOrNull()
                                currentRow?.let { rows.putIfAbsent(it, LinkedHashMap()) }
                            }

                            "c" -> {
                                currentCellRef = parser.getAttributeValue(null, "r")
                                currentCellType = parser.getAttributeValue(null, "t")
                            }

                            "v" -> inV = true
                        }
                    }

                    XmlPullParser.TEXT -> {
                        if (inV && currentRow != null && currentCellRef != null) {
                            val col = extractColumnLetters(currentCellRef!!)
                            val value = parseCellValue(currentCellType, parser.text, sharedStrings)
                            rows[currentRow!!]?.set(col, value)
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "v" -> inV = false
                            "c" -> {
                                currentCellRef = null
                                currentCellType = null
                            }

                            "row" -> currentRow = null
                        }
                    }
                }
                eventType = parser.next()
            }
        }
        return rows
    }

    private fun parseCellValue(type: String?, raw: String, sharedStrings: List<String>): String {
        return if (type == "s") {
            val idx = raw.toIntOrNull() ?: return ""
            sharedStrings.getOrNull(idx).orEmpty()
        } else {
            raw
        }
    }

    private fun extractColumnLetters(cellRef: String): String {
        val sb = StringBuilder()
        for (ch in cellRef) {
            if (ch.isLetter()) sb.append(ch) else break
        }
        return sb.toString()
    }

    private inline fun parse(bytes: ByteArray, block: (XmlPullParser) -> Unit) {
        ByteArrayInputStream(bytes).use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
            block(parser)
        }
    }

    internal data class SheetRef(val name: String, val relId: String)

    private const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
}


