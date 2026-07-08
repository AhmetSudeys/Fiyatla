package com.ahmetsudeys.rotauygulama.data.excel

import android.content.Context
import com.ahmetsudeys.rotauygulama.data.materials.MaterialListStore
import com.ahmetsudeys.rotauygulama.data.model.MaterialItem
import com.ahmetsudeys.rotauygulama.data.price.PriceOverridesStore
import java.util.Locale

class ExcelPriceListRepository(
    private val appContext: Context
) {
    fun getMaterials(operationName: String): List<MaterialItem> {
        // User-managed lists (custom lists + edited Excel lists) win over the built-in workbook.
        MaterialListStore.getStoredList(appContext, operationName)?.let { return it.items }

        val workbook = getOrLoadWorkbook()
        val sheetName = workbook.findSheetName(operationName) ?: return emptyList()
        val base = workbook.materialsBySheet[sheetName].orEmpty()
        return applyOverrides(sheetName, base)
    }

    /** Built-in Excel sheets first, then user-created custom lists. Deleted built-ins are hidden. */
    fun getAllSheetNames(): List<String> {
        val builtIns = getOrLoadWorkbook().sheetNames
            .filterNot { MaterialListStore.isBuiltInHidden(appContext, it) }
        return builtIns + MaterialListStore.getCustomListNames(appContext)
    }

    /** True if [listName] matches a built-in Excel sheet (as opposed to a user-created custom list). */
    fun isBuiltInList(listName: String): Boolean {
        val wanted = listName.normalizeForMatch()
        return getOrLoadWorkbook().sheetNames.any { it.normalizeForMatch() == wanted }
    }

    /** Only the built-in Excel sheet names (used to reject custom-list name clashes). */
    fun getBuiltInSheetNames(): List<String> {
        return getOrLoadWorkbook().sheetNames
    }

    fun isCustomList(listName: String): Boolean {
        return MaterialListStore.isCustom(appContext, listName)
    }

    fun createCustomList(displayName: String): Boolean {
        return MaterialListStore.createCustomList(appContext, displayName, getBuiltInSheetNames())
    }

    /**
     * Renames any list. Custom lists are renamed in place. A built-in Excel list is snapshotted
     * into a new custom list under [newName] and the original built-in is tombstoned, so from then
     * on it behaves like a fully editable custom list. Returns false on a name clash.
     */
    fun renameList(oldName: String, newName: String): Boolean {
        if (isBuiltInList(oldName)) {
            // No-op rename (same normalized name) keeps the built-in as-is.
            if (oldName.normalizeForMatch() == newName.normalizeForMatch()) return true
            val items = getMaterials(oldName)
            val created = MaterialListStore.createCustomListWithItems(
                appContext, newName, items, getBuiltInSheetNames()
            )
            if (!created) return false
            MaterialListStore.deleteList(appContext, oldName)
            MaterialListStore.hideBuiltIn(appContext, oldName)
            return true
        }
        return MaterialListStore.renameCustomList(appContext, oldName, newName, getBuiltInSheetNames())
    }

    fun deleteList(listName: String) {
        // Remove any custom/materialized entry, and tombstone the name if it is a built-in sheet so
        // it does not reappear on the next tab rebuild.
        MaterialListStore.deleteList(appContext, listName)
        if (isBuiltInList(listName)) {
            MaterialListStore.hideBuiltIn(appContext, listName)
        }
    }

    /** Persists the full item list, materializing a built-in Excel list on first structural edit. */
    fun saveMaterials(listName: String, items: List<MaterialItem>) {
        MaterialListStore.saveItems(appContext, listName, listName, items)
    }

    private fun applyOverrides(sheetName: String, base: List<MaterialItem>): List<MaterialItem> {
        if (base.isEmpty()) return emptyList()
        return base.map { item ->
            val o = PriceOverridesStore.getOverride(appContext, sheetName, item.name) ?: return@map item
            item.copy(quantity = o.quantity, price = o.price)
        }
    }

    private fun getOrLoadWorkbook(): WorkbookCache {
        // Parsed once per process and shared across all repository instances (each screen creates
        // its own repo). Avoids re-reading/parsing the .xlsx on every screen — a big jank source.
        cachedWorkbook?.let { return it }
        synchronized(workbookLock) {
            cachedWorkbook?.let { return it }

            val entries = XlsxZipReader.readEntries(appContext.assets, ASSET_FILE_NAME)
            val sharedStrings = XlsxXmlParser.parseSharedStrings(entries.getValue("xl/sharedStrings.xml"))

            val sheetRefs = XlsxXmlParser.parseWorkbookSheetRefs(entries.getValue("xl/workbook.xml"))
            val rels = XlsxXmlParser.parseWorkbookRelationships(entries.getValue("xl/_rels/workbook.xml.rels"))

            val sheetNameToPath = sheetRefs.associate { ref ->
                val target = rels[ref.relId].orEmpty() // e.g. worksheets/sheet1.xml
                ref.name to "xl/${target.trimStart('/')}"
            }

            val materialsBySheet = LinkedHashMap<String, List<MaterialItem>>()
            for ((sheetName, entryPath) in sheetNameToPath) {
                val sheetXml = entries[entryPath] ?: continue
                val cellsByRow = XlsxXmlParser.parseSheetCells(sheetXml, sharedStrings)
                materialsBySheet[sheetName] = extractMaterials(cellsByRow)
            }

            return WorkbookCache(
                sheetNames = materialsBySheet.keys.toList(),
                materialsBySheet = materialsBySheet
            ).also { cachedWorkbook = it }
        }
    }

    private fun extractMaterials(cellsByRow: Map<Int, Map<String, String>>): List<MaterialItem> {
        // Find header row: contains quantity + price (keywords vary per sheet; e.g. "adet", "birim fiyat")
        val headerRowIndex = cellsByRow.entries.firstOrNull { (_, cells) ->
            val values = cells.values.map { it.normalizeTrLower() }
            values.any { it.containsAny(QTY_KEYWORDS) } && values.any { it.containsAny(PRICE_KEYWORDS) }
        }?.key ?: return emptyList()

        val headerCells = cellsByRow[headerRowIndex].orEmpty()
        val qtyCol = headerCells.firstKeyContainsAny(QTY_KEYWORDS) ?: return emptyList()
        val priceCol = headerCells.firstKeyContainsAny(PRICE_KEYWORDS) ?: return emptyList()
        val totalCol = headerCells.firstKeyContainsAny(TOTAL_KEYWORDS)
        val nameCol = headerCells.firstKeyContainsAny(NAME_KEYWORDS)
            ?: guessNameColumn(
                cellsByRow = cellsByRow,
                headerRowIndex = headerRowIndex,
                excludedCols = setOfNotNull(qtyCol, priceCol, totalCol)
            )

        val rows = cellsByRow
            .filterKeys { it > headerRowIndex }
            .toSortedMap()

        val items = ArrayList<MaterialItem>()
        for ((_, rowCells) in rows) {
            val name = rowCells[nameCol].orEmpty().trim()
            if (name.isBlank()) break
            if (shouldSkipSummaryRow(name)) break

            val quantity = rowCells[qtyCol].toDoubleOrZero()
            val price = rowCells[priceCol].toDoubleOrZero()
            items.add(MaterialItem(name = name, quantity = quantity, price = price))
        }
        return items
    }

    private fun shouldSkipSummaryRow(name: String): Boolean {
        val n = name.normalizeTrLower()
        // Excel'de bazı sayfalarda altta "TOPLAM" / "KDV'Lİ TOPLAM" gibi özet satırları var.
        return n.contains("toplam") || n.contains("kdv")
    }

    private fun guessNameColumn(
        cellsByRow: Map<Int, Map<String, String>>,
        headerRowIndex: Int,
        excludedCols: Set<String>
    ): String {
        val headerCols = cellsByRow[headerRowIndex].orEmpty().keys
        val candidates = headerCols.filterNot { excludedCols.contains(it) }
        if (candidates.isEmpty()) return headerCols.firstOrNull() ?: "A"

        // Pick the column with the most "texty" values below header (e.g. SHUT OFF sheet has no 'malzeme' header)
        val rows = cellsByRow
            .filterKeys { it > headerRowIndex }
            .toSortedMap()

        var bestCol = candidates.first()
        var bestScore = -1
        for (col in candidates) {
            var score = 0
            for ((_, rowCells) in rows) {
                val v = rowCells[col].orEmpty().trim()
                if (v.isBlank()) continue
                if (v.replace(",", ".").toDoubleOrNull() == null) score++
            }
            if (score > bestScore) {
                bestScore = score
                bestCol = col
            }
        }
        return bestCol
    }

    private fun Map<String, String>.firstKeyContainsAny(keywords: List<String>): String? {
        return entries.firstOrNull { (_, value) ->
            val normalizedValue = value.normalizeTrLower()
            normalizedValue.containsAny(keywords)
        }?.key
    }

    private fun WorkbookCache.findSheetName(operationName: String): String? {
        val wanted = operationName.normalizeForMatch()
        return sheetNames.firstOrNull { it.normalizeForMatch() == wanted }
    }

    private fun String.normalizeForMatch(): String {
        return normalizeTrLower()
            .replace(" ", "")
            .replace("+", "")
    }

    private fun String.normalizeTrLower(): String {
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

    private fun String.containsAny(keywords: List<String>): Boolean {
        val normalized = this.normalizeTrLower()
        return keywords.any { normalized.contains(it) }
    }

    private fun String?.toDoubleOrZero(): Double {
        val raw = this?.trim().orEmpty()
        if (raw.isBlank()) return 0.0
        return raw.replace(",", ".").toDoubleOrNull() ?: 0.0
    }

    private data class WorkbookCache(
        val sheetNames: List<String>,
        val materialsBySheet: Map<String, List<MaterialItem>>
    )

    private companion object {
        const val ASSET_FILE_NAME = "FiyatListesi.xlsx"

        // Process-wide workbook cache: the .xlsx is parsed only once, then reused everywhere.
        private val workbookLock = Any()
        @Volatile
        private var cachedWorkbook: WorkbookCache? = null

        val NAME_KEYWORDS = listOf("malzeme", "aciklama", "urun")
        val QTY_KEYWORDS = listOf("miktar", "adet")
        val PRICE_KEYWORDS = listOf("fiyat", "birim")
        val TOTAL_KEYWORDS = listOf("tutar", "maliyet")
    }
}


