package com.amaury.pointage.v2.engine

/**
 * Règle d'heures supplémentaires prête à être proposée au moteur de paie.
 * Le taux et la tranche doivent provenir du même extrait déjà validé.
 */
object CompanyAgreementOvertimeRuleV2 {
    data class Rule(
        val source: CompanyAgreementStructuredRuleV2.Rule,
        val percent: Double,
        val band: CompanyAgreementOvertimeBandV2.Band
    )

    fun from(source: CompanyAgreementStructuredRuleV2.Rule): Rule? {
        if (!source.calculationReady) return null
        val value = source.value ?: return null
        if (value.type != CompanyAgreementStructuredRuleV2.ValueType.PERCENT) return null
        val band = CompanyAgreementOvertimeBandV2.parse(source.source.excerpt) ?: return null
        return Rule(
            source = source,
            percent = value.amount,
            band = band
        )
    }
}
