package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.ApplicableCompanyAgreementRulesV2
import com.amaury.pointage.v2.CompanyAgreementRuleStoreV2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Associe chaque sous-période de paie aux règles d'entreprise réellement applicables. */
object CompanyAgreementPayrollSegmentsV2 {
    data class Segment(
        val start: LocalDate,
        val endInclusive: LocalDate,
        val applicableRules: List<CompanyAgreementRuleStoreV2.StoredCandidate>
    )

    private val iso = DateTimeFormatter.ISO_LOCAL_DATE
    private val french = DateTimeFormatter.ofPattern("dd/MM/uuuu")

    internal fun load(
        storedRules: CompanyAgreementRuleStoreV2.ReadResult,
        period: PayrollPeriodV2.Period
    ): List<Segment> {
        if (!storedRules.reliable) return emptyList()

        // La validation d'applicabilité signale aussi toute règle vérifiée incomplète ou mal datée.
        // Elle est faite sur ce même snapshot afin qu'aucune relecture du stockage ne puisse changer
        // l'état juridique au milieu d'un calcul de période.
        if (!ApplicableCompanyAgreementRulesV2.resolve(storedRules, period.start).reliable) return emptyList()

        val rulePeriods = storedRules.records
            .filter { it.verified && !it.scope.isNullOrBlank() }
            .mapNotNull { rule ->
                val from = parseDate(rule.effectiveFrom) ?: return@mapNotNull null
                val to = rule.effectiveTo?.takeIf { it.isNotBlank() }?.let(::parseDate)
                    ?: if (rule.effectiveTo.isNullOrBlank()) null else return@mapNotNull null
                from to to
            }

        return PayrollPeriodRuleSegmentsV2.split(period, rulePeriods).map { segment ->
            Segment(
                start = segment.start,
                endInclusive = segment.endInclusive,
                applicableRules = ApplicableCompanyAgreementRulesV2.resolve(
                    storedRules,
                    segment.start
                ).rules
            )
        }
    }

    private fun parseDate(value: String?): LocalDate? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            LocalDate.parse(raw, iso)
        } catch (_: DateTimeParseException) {
            try {
                LocalDate.parse(raw, french)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
