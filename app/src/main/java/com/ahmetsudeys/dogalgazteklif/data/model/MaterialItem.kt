package com.ahmetsudeys.dogalgazteklif.data.model

data class MaterialItem(
    val name: String,
    val quantity: Double,
    val price: Double
) {
    val total: Double
        get() = quantity * price
}


