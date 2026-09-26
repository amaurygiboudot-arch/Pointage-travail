package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.SegmentedProrationSourceV2

/**
 * Pont fail-closed entre les timelines contractuelle/conventionnelle et la base mensuelle segmentée.
 *
 * Les identifiants de versions de contrat et de règles sont indépendants. Une règle qui change
 * à l'intérieur d'un même segment contractuel bloque la base au lieu d'aplatir le mois.
 */
object SegmentedMonthlyBaseBridgeV2 {
    const val RULE_CHANGES_WITHIN_CONTRACT_WARNING =
        "Proratisation mensuelle : les règles de paie changent à l'intérieur d'un même segment contractuel ; la base mensuelle reste bloquée."
    const val TIMELINE_WARNING =
        "Proratisation mensuelle : contrat et règles conventionnelles ne peuvent pas être alignés de façon fiable pour tout le mois."

    fun calculate(
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        prorationSource: SegmentedProrationSourceV2
    ): SegmentedMonthlyBaseResultV2 {
        if (!prorationSource.reliable) {
            return blocked(
                prorationSource.warnings.ifEmpty {
                    listOf(ConfirmedSegmentedMonthlyProrationCalculatorV2.MISSING_PRORATION_WARNING)
                }
            )
        }
        val result = calculate(contracts, rules, prorationSource.proration)
        return result.copy(
            warnings = (prorationSource.warnings + result.warnings).distinct()
        )
    }

    fun calculate(
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        proration: ConfirmedSegmentedMonthlyProrationV2?
    ): SegmentedMonthlyBaseResultV2 {
        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)
        if (!timeline.reliable) {
            return blocked((timeline.warnings + TIMELINE_WARNING).distinct())
        }

        val contractSegments = contracts.calculationSegments
        if (contractSegments.isEmpty()) {
            return blocked((contracts.warnings + TIMELINE_WARNING).distinct())
        }

        val rulesByContractVersionId = linkedMapOf<String, PayrollRulesV2>()
        for (contractSegment in contractSegments) {
            val versionId = contractSegment.snapshot.versionId.trim()
            if (versionId.isEmpty()) return blocked(listOf(TIMELINE_WARNING))

            val slices = timeline.slices
                .filter {
                    it.contractVersionId.trim() == versionId &&
                        it.endEpochDay >= contractSegment.startEpochDay &&
                        it.startEpochDay <= contractSegment.endEpochDay
                }
                .sortedBy { it.startEpochDay }

            val firstRules = slices.firstOrNull()?.ruleSnapshot?.rules
            if (!coversExactly(
                    slices,
                    contractSegment.startEpochDay,
                    contractSegment.endEpochDay
                ) ||
                firstRules == null ||
                slices.any { it.ruleSnapshot.rules != firstRules }
            ) {
                return blocked(
                    (contracts.warnings + rules.warnings +
                        RULE_CHANGES_WITHIN_CONTRACT_WARNING).distinct()
                )
            }

            val existing = rulesByContractVersionId[versionId]
            if (existing != null && existing != firstRules) {
                return blocked(listOf(RULE_CHANGES_WITHIN_CONTRACT_WARNING))
            }
            rulesByContractVersionId[versionId] = firstRules
        }

        return ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments = contractSegments,
            rulesByVersionId = rulesByContractVersionId,
            proration = proration
        )
    }

    private fun coversExactly(
        slices: List<PayrollCalculationSliceV2>,
        start: Long,
        end: Long
    ): Boolean {
        if (slices.isEmpty()) return false
        var cursor = start
        for (slice in slices) {
            if (slice.startEpochDay != cursor ||
                slice.endEpochDay < slice.startEpochDay ||
                slice.endEpochDay > end
            ) return false
            if (slice.endEpochDay == end) return true
            if (slice.endEpochDay == Long.MAX_VALUE) return false
            cursor = slice.endEpochDay + 1L
        }
        return false
    }

    private fun blocked(warnings: List<String>) = SegmentedMonthlyBaseResultV2(
        pieces = emptyList(),
        baseGross = null,
        reliable = false,
        warnings = warnings.distinct()
    )
}
