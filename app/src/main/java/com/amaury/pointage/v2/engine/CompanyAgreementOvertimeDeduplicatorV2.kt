package com.amaury.pointage.v2.engine

/**
 * Retire uniquement les doublons strictement identiques avant la détection des conflits.
 * Deux règles différentes ou provenant de sources différentes ne sont jamais arbitrées ici.
 */
object CompanyAgreementOvertimeDeduplicatorV2 {
    fun deduplicate(rules: List<CompanyAgreementOvertimeRuleV2.Rule>): List<CompanyAgreementOvertimeRuleV2.Rule> {
        return rules.distinctBy { rule ->
            val candidate = rule.source.source
            listOf(
                candidate.agreementId,
                candidate.category.name,
                candidate.excerpt.trim(),
                candidate.effectiveFrom.orEmpty(),
                candidate.effectiveTo.orEmpty(),
                candidate.scope.orEmpty().trim(),
                rule.percent.toString(),
                rule.band.fromHourInclusive.toString(),
                rule.band.toHourInclusive?.toString().orEmpty()
            ).joinToString("\u001F")
        }
    }
}
