package com.ahmetsudeys.dogalgazteklif.data.excel

import android.content.Context
import com.ahmetsudeys.dogalgazteklif.data.materials.MaterialListStore
import com.ahmetsudeys.dogalgazteklif.data.model.MaterialItem
import com.ahmetsudeys.dogalgazteklif.data.price.PriceOverridesStore
import java.util.Locale

class ExcelPriceListRepository(
    private val appContext: Context
) {
    fun getMaterials(operationName: String): List<MaterialItem> {
        // User-managed lists (custom lists + edited Excel lists) win over the built-in workbook.
        MaterialListStore.getStoredList(appContext, operationName)?.let { stored ->
            return if (stored.custom) stored.items else stored.items.adoptBuiltInNames(operationName)
        }

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

    /**
     * Ayrıştırıcı çalışma kitabının ayrıntı sütununu ada katmaya BAŞLAMADAN önce kalıcılaştırılmış
     * (kullanıcı tarafından düzenlenmiş) bir Excel listesini güncel adlara taşır; böylece orada da
     * beş ayırt edilemez "PATENT DİRSEK" yerine "PATENT DİRSEK 1 1/4"" görünür.
     *
     * Bilerek dar tutuldu: yalnız satır sayıları hâlâ örtüşüyorsa çalışır ve yalnız gömülü adın
     * *uzattığı* bir adı değiştirir — yani tam olarak bu değişikliğin eklediği son eki. Miktar ve
     * fiyat (kullanıcının kendi düzenlemeleri) hiç ellenmez; kullanıcının kendi yazdığı bir ad ön
     * ek testini geçemeyeceği için olduğu gibi kalır. Adlar bir kez eşleşince işlem etkisizdir.
     */
    private fun List<MaterialItem>.adoptBuiltInNames(listName: String): List<MaterialItem> {
        val workbook = getOrLoadWorkbook()
        val sheetName = workbook.findSheetName(listName) ?: return this
        val builtIn = workbook.materialsBySheet[sheetName] ?: return this
        if (builtIn.size != size) return this
        return mapIndexed { index, item ->
            val fresh = builtIn[index].name
            if (fresh != item.name && fresh.startsWith("${item.name} ")) item.copy(name = fresh) else item
        }
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

        val detailCols = detailColumns(headerCells, nameCol, qtyCol, priceCol, totalCol)

        val rows = cellsByRow
            .filterKeys { it > headerRowIndex }
            .toSortedMap()

        val items = ArrayList<MaterialItem>()
        for ((_, rowCells) in rows) {
            val baseName = rowCells[nameCol].orEmpty().trim()
            if (baseName.isBlank()) break
            if (shouldSkipSummaryRow(baseName)) break

            val name = appendDetails(baseName, detailCols.map { rowCells[it].orEmpty().trim() })
            val quantity = rowCells[qtyCol].toDoubleOrZero()
            val price = rowCells[priceCol].toDoubleOrZero()
            items.add(MaterialItem(name = name, quantity = quantity, price = price))
        }
        return items.withUniqueNames()
    }

    /**
     * Ad ile miktar sütunlarının ARASINDA kalan, bilinen sayısal sütunlardan olmayan sütunlar.
     * Satıra özel, adın tek başına taşımadığı bir ayrıntı tutarlar.
     *
     * `FiyatListesi.xlsx`'te böyle bir sütunu olan tek sayfa "Kolon": başlığı "AÇIKLAMA" ve boru
     * çapı orada duruyor (`1"`, `1 1/4"`, `2"` …). Yok sayılınca sayfada beş satır birden
     * "PATENT DİRSEK" adıyla, farklı fiyatlarla görünüyordu — kullanıcı ayırt edemiyordu, teklif
     * akışı da satır düzenlemelerini malzeme ADINA göre sakladığı için birini değiştirmek
     * hepsini değiştiriyordu. Diğer bütün sayfalarda ad ve miktar sütunları yan yana, dolayısıyla
     * burası boş liste döndürür ve o sayfaların adları hiç değişmez.
     *
     * Yalnız miktardan ÖNCEKİ sütunlara bakılır: adın solundaki bir sütun açıklamadan çok satır
     * numarası olma ihtimali taşır.
     */
    private fun detailColumns(
        headerCells: Map<String, String>,
        nameCol: String,
        qtyCol: String,
        priceCol: String,
        totalCol: String?
    ): List<String> {
        val reserved = setOfNotNull(nameCol, qtyCol, priceCol, totalCol)
        val from = columnIndex(nameCol)
        val until = columnIndex(qtyCol)
        if (until - from <= 1) return emptyList()
        return headerCells.keys
            .filter { it !in reserved && columnIndex(it) in (from + 1) until until }
            .sortedBy { columnIndex(it) }
    }

    /**
     * Ayrıntıları ada ekler; boşları ve adın zaten söylediklerini atlar — "1 1/4\" SİYAH DİRSEK 90"
     * çapını kendi taşıyor, AÇIKLAMA hücresini eklemek onu tekrar ederdi.
     */
    private fun appendDetails(baseName: String, details: List<String>): String {
        if (details.isEmpty()) return baseName
        var name = baseName
        for (detail in details) {
            if (detail.isBlank()) continue
            if (name.squeeze().contains(detail.squeeze())) continue
            name = "$name $detail"
        }
        return name
    }

    /**
     * Bir liste içinde adların benzersiz olmasını garanti eder; uygulamanın geri kalanı buna
     * dayanıyor (teklifteki miktar/fiyat düzenlemeleri malzeme adına göre saklanıyor, malzeme
     * listesi de satırları adla eşliyor).
     *
     * [appendDetails] sonrasında pakete gömülü çalışma kitabında geriye yalnız "Kolon" sayfasının
     * sonundaki beş boş "PATENT TE" satırı kalıyor — ne çap, ne miktar, ne fiyat; sayfada onları
     * ayıracak hiçbir şey yok. "PATENT TE (2)" … "PATENT TE (5)" olurlar; ilk geçen ad olduğu gibi
     * kalır, böylece mevcut veriler onunla eşleşmeye devam eder.
     */
    private fun List<MaterialItem>.withUniqueNames(): List<MaterialItem> {
        val used = HashSet<String>(size)
        return map { item ->
            var candidate = item.name
            var n = 1
            while (!used.add(candidate.normalizeTrLower())) {
                n++
                candidate = "${item.name} ($n)"
            }
            if (candidate == item.name) item else item.copy(name = candidate)
        }
    }

    /** Sütun harflerini 1 tabanlı indekse çevirir; böylece "Z", "AA"dan önce sıralanır. */
    private fun columnIndex(letters: String): Int {
        var index = 0
        for (ch in letters) {
            val upper = ch.uppercaseChar()
            if (upper !in 'A'..'Z') continue
            index = index * 26 + (upper - 'A' + 1)
        }
        return index
    }

    /** "Ad bunu zaten söylüyor mu?" karşılaştırmaları için normalize + boşlukları sadeleştirilmiş. */
    private fun String.squeeze(): String = normalizeTrLower().replace(WHITESPACE, " ")

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

        val WHITESPACE = Regex("\\s+")

        val NAME_KEYWORDS = listOf("malzeme", "aciklama", "urun")
        val QTY_KEYWORDS = listOf("miktar", "adet")
        val PRICE_KEYWORDS = listOf("fiyat", "birim")
        val TOTAL_KEYWORDS = listOf("tutar", "maliyet")
    }
}


