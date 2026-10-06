package com.amaury.pointage

import java.text.Normalizer
import java.util.Locale

internal data class PayslipProfileDraftV2(val hourlyRate: Double?, val sourceLine: String?)

/** Only an explicit gross hourly-rate label can seed the uncommitted contract form. */
internal object PayslipProfileDraftParserV2 {
    fun parse(text: String): PayslipProfileDraftV2 {
        var invalidExplicitRate = false
        val matches = text.lineSequence().take(3000).mapNotNull { raw ->
            val normalized = Normalizer.normalize(raw.lowercase(Locale.FRANCE), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            val label = Regex("\\btaux\\s+horaire\\s+brut\\s*[:=]?\\s*(.*)$").find(normalized)
                ?: return@mapNotNull null
            // Reject rows containing multiple columns or cumulative figures; never guess a column.
            val valueText = label.groupValues[1].trim().replace(Regex("\\s*(€|eur|euros?)\\s*$"), "")
            if (!valueText.matches(Regex("[0-9]+(?:[,.][0-9]{1,4})?"))) {
                invalidExplicitRate = true
                return@mapNotNull null
            }
            val rate = PayslipImportConfirmationPolicyV2.parseAmount(valueText)?.takeIf { it > 0.0 }
                ?: run { invalidExplicitRate = true; return@mapNotNull null }
            rate to raw.trim().take(180)
        }.toList()
        val rates = matches.map { it.first }.distinct()
        return if (!invalidExplicitRate && rates.size == 1) PayslipProfileDraftV2(rates.single(), matches.first().second)
        else PayslipProfileDraftV2(null, null)
    }
}
