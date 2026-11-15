package com.amaury.pointage

import java.text.Normalizer
import java.util.Locale

internal data class PayslipProfileDraftV2(val hourlyRate: Double?, val sourceLine: String?)

/** Only an explicit gross hourly-rate label can seed the uncommitted contract form. */
internal object PayslipProfileDraftParserV2 {
    fun parse(text: String): PayslipProfileDraftV2 {
        if (text.length > 30_000 || text.lineSequence().count() > 3000) return PayslipProfileDraftV2(null, null)
        var invalidExplicitRate = false
        val matches = text.lineSequence().mapNotNull { raw ->
            val normalized = Normalizer.normalize(raw.lowercase(Locale.FRANCE), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            if (!Regex("\\btaux\\s+(?:horaire\\s+brut|brut\\s+horaire)\\b").containsMatchIn(normalized)) return@mapNotNull null
            val label = Regex("^\\s*taux\\s+(?:horaire\\s+brut|brut\\s+horaire)\\s*[:=]?\\s*(.*)$").matchEntire(normalized)
                ?: run { invalidExplicitRate = true; return@mapNotNull null }
            // Reject rows containing multiple columns or cumulative figures; never guess a column.
            val valueText = label.groupValues[1].trim().replace(Regex("\\s*/\\s*h\\s*$"), "").replace(Regex("\\s*(€|eur|euros?)\\s*$"), "")
            if (!valueText.matches(Regex("[0-9]+(?:[,.][0-9]{1,4})?"))) {
                invalidExplicitRate = true
                return@mapNotNull null
            }
            val rate = PayslipImportConfirmationPolicyV2.parseAmount(valueText)?.takeIf { it > 0.0 }
                ?: run { invalidExplicitRate = true; return@mapNotNull null }
            rate to raw.trim().take(180)
        }.toList()
        return if (!invalidExplicitRate && matches.size == 1) PayslipProfileDraftV2(matches.single().first, matches.single().second)
        else PayslipProfileDraftV2(null, null)
    }
}
