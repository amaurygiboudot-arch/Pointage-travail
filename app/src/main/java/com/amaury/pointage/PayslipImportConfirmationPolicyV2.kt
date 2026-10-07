package com.amaury.pointage

/** A blank optional field means unknown; malformed text must never mean unknown. */
internal object PayslipImportConfirmationPolicyV2 {
    fun parseAmount(raw: String): Double? = raw.trim()
        .replace(" ", "")
        .replace("\u00a0", "")
        .replace("\u202f", "")
        .replace(',', '.')
        .toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 }

    fun isOptionalAmountValid(raw: String): Boolean = raw.isBlank() || parseAmount(raw) != null
}
