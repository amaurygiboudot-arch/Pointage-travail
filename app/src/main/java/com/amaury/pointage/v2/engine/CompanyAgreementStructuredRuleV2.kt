package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.CompanyAgreementRuleExtractorV2
import com.amaury.pointage.v2.CompanyAgreementRuleStoreV2

/**
 * Valeur structurée issue d'une règle d'accord déjà vérifiée et applicable.
 * L'absence d'une valeur certaine laisse la règle informative et interdit son application au calcul.
 */
object CompanyAgreementStructuredRuleV2 {
    enum class ValueType { PERCENT, EURO_AMOUNT, HOURS }

    data class Value(
        val type: ValueType,
        val amount: Double
    )

    data class Rule(
        val source: CompanyAgreementRuleStoreV2.StoredCandidate,
        val value: Value?
    ) {
        val calculationReady: Boolean get() = value != null
    }

    fun structure(source: CompanyAgreementRuleStoreV2.StoredCandidate): Rule {
        val value = when (source.category) {
            CompanyAgreementRuleExtractorV2.Category.OVERTIME,
            CompanyAgreementRuleExtractorV2.Category.NIGHT,
            CompanyAgreementRuleExtractorV2.Category.SUNDAY -> percent(source.excerpt)
            CompanyAgreementRuleExtractorV2.Category.BONUS,
            CompanyAgreementRuleExtractorV2.Category.SALARY -> euro(source.excerpt)
            CompanyAgreementRuleExtractorV2.Category.WORKING_TIME -> hours(source.excerpt)
            CompanyAgreementRuleExtractorV2.Category.PAID_LEAVE,
            CompanyAgreementRuleExtractorV2.Category.RTT -> null
        }
        return Rule(source, value)
    }

    private fun percent(text: String): Value? {
        val matches = Regex("""(\d{1,3}(?:[.,]\d+)?)\s*%""").findAll(text).toList()
        if (matches.size != 1) return null
        val amount = matches.single().groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return amount.takeIf { it in 0.0..500.0 }?.let { Value(ValueType.PERCENT, it) }
    }

    private fun euro(text: String): Value? {
        val matches = Regex("""(\d+(?:[\s\u00A0]\d{3})*(?:[.,]\d{1,2})?)\s*(?:€|euros?)""", RegexOption.IGNORE_CASE)
            .findAll(text).toList()
        if (matches.size != 1) return null
        val amount = matches.single().groupValues[1].replace(" ", "").replace("\u00A0", "").replace(',', '.').toDoubleOrNull() ?: return null
        return amount.takeIf { it >= 0.0 }?.let { Value(ValueType.EURO_AMOUNT, it) }
    }

    private fun hours(text: String): Value? {
        val matches = Regex("""(\d{1,4}(?:[.,]\d+)?)\s*(?:h|heures?)\b""", RegexOption.IGNORE_CASE)
            .findAll(text).toList()
        if (matches.size != 1) return null
        val amount = matches.single().groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return amount.takeIf { it in 0.0..5000.0 }?.let { Value(ValueType.HOURS, it) }
    }
}
