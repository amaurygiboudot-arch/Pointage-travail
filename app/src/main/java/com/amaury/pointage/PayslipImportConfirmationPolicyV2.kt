package com.amaury.pointage

import com.amaury.pointage.v2.engine.PayslipEngineV2

/** A blank optional field means unknown; malformed text must never mean unknown. */
internal object PayslipImportConfirmationPolicyV2 {
    fun parseAmount(raw: String): Double? = PayslipEngineV2.parseAmount(raw.trim()
        .replace(" ", "")
        .replace("\u00a0", "")
        .replace("\u202f", "")
        .replace(',', '.'))

    fun isOptionalAmountValid(raw: String): Boolean = raw.isBlank() || parseAmount(raw) != null
}
