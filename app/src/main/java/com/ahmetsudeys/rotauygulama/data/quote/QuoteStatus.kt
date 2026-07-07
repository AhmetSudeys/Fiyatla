package com.ahmetsudeys.rotauygulama.data.quote

enum class QuoteStatus {
    PENDING,
    APPROVED,
    REJECTED;

    fun next(): QuoteStatus = when (this) {
        PENDING -> APPROVED
        APPROVED -> REJECTED
        REJECTED -> PENDING
    }

    companion object {
        fun fromString(raw: String?): QuoteStatus {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PENDING
        }
    }
}


