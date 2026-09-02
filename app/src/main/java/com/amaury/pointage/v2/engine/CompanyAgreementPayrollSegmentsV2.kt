package com.amaury.pointage.v2.engine

import android.content.Context
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

    fun load(
        context: Context,
        companyId: String,
        period: PayrollPeriodV2.Period
    ): List<Segment> {
        val rulePeriods = CompanyAgreementRuleStoreV2.list(context, companyId)
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
                applicableRules = ApplicableCompanyAgreementRulesV2.list(
                    context,
                    companyId,
                    segment.start
                )
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
