package com.ahmetsudeys.dogalgazteklif.data.quote

import com.ahmetsudeys.dogalgazteklif.data.model.MaterialItem

/**
 * Minimal in-memory store for the current quote draft.
 * Keeps UI simple; persistence happens on "Teklifi Tamamla".
 */
object QuoteDraftStore {
    /**
     * If non-null, the wizard is in "edit" mode and should update an existing quote instead of creating a new one.
     */
    var editingCreatedAtMillis: Long? = null
    var editingStatus: QuoteStatus? = null

    var customerName: String = ""
    var customerNote: String = ""

    // Optional customer details collected during quote creation.
    // Used to auto-create a customer when the quote is approved.
    var customerPhone: String = ""
    var customerTcNo: String = ""
    var customerBuildingCode: String = ""
    var customerInstallationNo: String = ""
    var customerCity: String = ""
    var customerDistrict: String = ""
    var customerNeighborhood: String = ""
    var customerStreet: String = ""
    var customerBuildingNo: String = ""
    var customerApartmentNo: String = ""
    var customerPainted: Boolean = false

    var selectedOperations: List<String> = emptyList()

    // operation -> editable materials
    val materialsByOperation: MutableMap<String, MutableList<MaterialItem>> = linkedMapOf()

    /**
     * Performance: keep fast totals so we don't repeatedly sum large material lists on the UI thread.
     * - `materialsTotalByOperation` is updated from materials pages.
     * - `materialsTotalCached` is a fallback (e.g. loaded from saved QuoteRecord in edit mode).
     */
    val materialsTotalByOperation: MutableMap<String, Double> = linkedMapOf()
    var materialsTotalCached: Double = 0.0

    fun currentMaterialsTotal(): Double {
        return when {
            materialsTotalByOperation.isNotEmpty() -> materialsTotalByOperation.values.sum()
            materialsByOperation.isNotEmpty() -> materialsByOperation.values.sumOf { list -> list.sumOf { it.total } }
            else -> materialsTotalCached
        }
    }

    /**
     * Quote-only overrides (do not persist to app-wide materials list).
     * We keep these separate so the base list can always be reloaded fresh from the repository
     * (including permanent edits from the Materials screen), while preserving any in-quote edits.
     */
    data class MaterialOverride(val quantity: Double?, val price: Double?)
    val materialOverridesByOperation: MutableMap<String, MutableMap<String, MaterialOverride>> = linkedMapOf()

    // Extras
    var pipeMeters: Double = 0.0
    var radiatorMeters: Double = 0.0
    var boilerPresent: Boolean = false
    var boilerBrand: String = ""
    var boilerModel: String = ""
    var boilerPrice: Double = 0.0
    var projectTotal: Double = 0.0
    var laborTotal: Double = 0.0
    var radiatorTotal: Double = 0.0
    var profit: Double = 0.0

    // Discount
    var discountAmount: Double = 0.0

    fun reset() {
        editingCreatedAtMillis = null
        editingStatus = null
        customerName = ""
        customerNote = ""
        customerPhone = ""
        customerTcNo = ""
        customerBuildingCode = ""
        customerInstallationNo = ""
        customerCity = ""
        customerDistrict = ""
        customerNeighborhood = ""
        customerStreet = ""
        customerBuildingNo = ""
        customerApartmentNo = ""
        customerPainted = false
        selectedOperations = emptyList()
        materialsByOperation.clear()
        materialOverridesByOperation.clear()
        materialsTotalByOperation.clear()
        materialsTotalCached = 0.0
        pipeMeters = 0.0
        radiatorMeters = 0.0
        boilerPresent = false
        boilerBrand = ""
        boilerModel = ""
        boilerPrice = 0.0
        projectTotal = 0.0
        laborTotal = 0.0
        radiatorTotal = 0.0
        profit = 0.0
        discountAmount = 0.0
    }

    fun loadFromRecord(record: QuoteStorage.QuoteRecord) {
        reset()
        editingCreatedAtMillis = record.createdAtMillis
        editingStatus = record.status

        customerName = record.customerName
        customerNote = record.note.orEmpty()
        customerPhone = record.customerPhone.orEmpty()
        customerTcNo = record.customerTcNo.orEmpty()
        customerBuildingCode = record.customerBuildingCode.orEmpty()
        customerInstallationNo = record.customerInstallationNo.orEmpty()
        customerCity = record.customerAddress.city.orEmpty()
        customerDistrict = record.customerAddress.district.orEmpty()
        customerNeighborhood = record.customerAddress.neighborhood.orEmpty()
        customerStreet = record.customerAddress.street.orEmpty()
        customerBuildingNo = record.customerAddress.buildingNo.orEmpty()
        customerApartmentNo = record.customerAddress.apartmentNo.orEmpty()
        customerPainted = record.customerPainted
        selectedOperations = record.operations

        pipeMeters = record.pipeMeters
        radiatorMeters = record.radiatorMeters
        boilerPresent = record.boilerPresent
        boilerBrand = record.boilerBrand.orEmpty()
        boilerModel = record.boilerModel.orEmpty()
        boilerPrice = record.boilerPrice
        projectTotal = record.projectTotal
        laborTotal = record.laborTotal
        radiatorTotal = record.radiatorTotal
        profit = record.profit
        discountAmount = record.discount
        materialsTotalCached = record.materialsTotal

        // Restore quote-only overrides so materials pages can rebuild the list from repository + overrides.
        materialOverridesByOperation.clear()
        for ((operation, byKey) in record.materialOverridesByOperation) {
            val out = linkedMapOf<String, MaterialOverride>()
            for ((key, o) in byKey) {
                out[key] = MaterialOverride(quantity = o.quantity, price = o.price)
            }
            materialOverridesByOperation[operation] = out
        }
    }
}


