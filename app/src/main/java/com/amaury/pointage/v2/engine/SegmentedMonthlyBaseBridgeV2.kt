package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.SegmentedProrationSourceV2

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
        val proration = prorationSource.proration
        if (!prorationSource.reliable || proration == null) {
            return blocked(
                prorationSource.warnings.ifEmpty {
                    listOf(ConfirmedSegmentedMonthlyProrationCalculatorV2.MISSING_PRORATION_WARNING)
                }
            )
        }

        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)
        if (!timeline.reliable) {
            return blocked((timeline.warnings + TIMELINE_WARNING).distinct())
        }
        val contractSegments = contracts.calculationSegments
        if (contractSegments.isEmpty()) {
            return blocked((contracts.warnings + TIMELINE_WARNING).distinct())
        }

        val rulesByContractVersion = linkedMapOf<String, PayrollRulesV2>()
        for (segment in contractSegments) {
            val versionId = segment.snapshot.versionId.trim()
            if (versionId.isBlank()) return blocked(listOf(TIMELINE_WARNING))
            val slices = timeline.slices.filter {
                it.contractVersionId.trim() == versionId &&
                    it.endEpochDay >= segment.startEpochDay &&
                    it.startEpochDay <= segment.endEpochDay
            }.sortedBy { it.startEpochDay }

            if (!coversExactly(slices, segment.startEpochDay, segment.endEpochDay)) {
                return blocked((contracts.warnings + rules.warnings + TIMELINE_WARNING).distinct())
            }
            val firstRules = slices.firstOrNull()?.ruleSnapshot?.rules
                ?: return blocked(listOf(TIMELINE_WARNING))
            if (slices.any { it.ruleSnapshot.rules != firstRules }) {
                return blocked(
                    (contracts.warnings + rules.warnings + RULE_CHANGES_WITHIN_CONTRACT_WARNING)
                        .distinct()
                )
            }
            val previous = rulesByContractVersion.putIfAbsent(versionId, firstRules)
            if (previous != null && previous != firstRules) {
                return blocked(listOf(RULE_CHANGES_WITHIN_CONTRACT_WARNING))
            }
        }

        return ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments = contractSegments,
            rulesByVersionId = rulesByContractVersion,
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
