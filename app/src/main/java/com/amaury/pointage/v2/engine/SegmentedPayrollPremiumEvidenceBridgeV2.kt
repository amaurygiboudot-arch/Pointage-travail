package com.amaury.pointage.v2.engine

data class SegmentedPayrollPremiumEvidenceBridgeResultV2(
    val evidence: List<SegmentedPayrollPremiumEvidenceV2>,
    val reliable: Boolean,
    val warnings: List<String>
)

object SegmentedPayrollPremiumEvidenceBridgeV2 {
    const val NIGHT_SOURCE_WARNING =
        "Preuves B21 : historique daté des plages de nuit non fiable."
    const val NIGHT_COVERAGE_WARNING =
        "Preuves B21 : aucune plage de nuit confirmée unique ne couvre toute la tranche."
    const val NIGHT_MISMATCH_WARNING =
        "Preuves B21 : multiplicateur de nuit incohérent entre le snapshot de paie et la plage horaire confirmée."
    const val FUTURE_RULE_WARNING =
        "Preuves B21 : un snapshot de règle a été confirmé dans le futur ; contexte premium bloqué."

    fun build(
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        nightSnapshots: List<ConventionNightRuleSnapshotV2>,
        nightSourceReliable: Boolean,
        nightWarnings: List<String>,
        holidayScope: FrenchPublicHolidayCalendarV2.Scope?,
        nowMs: Long
    ): SegmentedPayrollPremiumEvidenceBridgeResultV2 {
        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)
        val warnings = (timeline.warnings + nightWarnings).toMutableList()
        fun blocked(message: String) = SegmentedPayrollPremiumEvidenceBridgeResultV2(
            evidence = emptyList(),
            reliable = false,
            warnings = (warnings + message).distinct()
        )

        if (!timeline.reliable || timeline.slices.isEmpty()) {
            return blocked(SegmentedPayrollSessionEvidenceBuilderV2.RULE_WARNING)
        }
        if (nowMs <= 0L) return blocked(FUTURE_RULE_WARNING)

        val scope = holidayScope ?: return blocked(
            SegmentedPayrollSessionEvidenceBuilderV2.HOLIDAY_WARNING
        )
        if (!scope.complete) {
            scope.warning?.let(warnings::add)
            return blocked(SegmentedPayrollSessionEvidenceBuilderV2.HOLIDAY_WARNING)
        }

        val needsNight = timeline.slices.any { it.ruleSnapshot.rules.nightMultiplier != null }
        val history = if (needsNight) {
            if (!nightSourceReliable) return blocked(NIGHT_SOURCE_WARNING)
            runCatching { ConventionNightRuleHistoryV2(nightSnapshots) }.getOrNull()
                ?: return blocked(NIGHT_SOURCE_WARNING)
        } else null

        val output = mutableListOf<SegmentedPayrollPremiumEvidenceV2>()
        for (slice in timeline.slices) {
            val ruleSnapshot = slice.ruleSnapshot
            if (ruleSnapshot.sourceId.isBlank() || ruleSnapshot.checkedAtMs > nowMs) {
                return blocked(FUTURE_RULE_WARNING)
            }

            val multiplier = ruleSnapshot.rules.nightMultiplier
            val nightSnapshot = if (multiplier == null) {
                null
            } else {
                val start = history?.applicable(rules.idcc, slice.startEpochDay)
                    ?: return blocked(NIGHT_COVERAGE_WARNING)
                val end = history.applicable(rules.idcc, slice.endEpochDay)
                    ?: return blocked(NIGHT_COVERAGE_WARNING)
                if (start.versionId != end.versionId ||
                    start.sourceId != end.sourceId ||
                    !start.appliesTo(slice.startEpochDay) ||
                    !start.appliesTo(slice.endEpochDay) ||
                    start.checkedAtMs > nowMs ||
                    start.sourceId.isBlank()
                ) {
                    return blocked(NIGHT_COVERAGE_WARNING)
                }
                if (kotlin.math.abs(start.rule.multiplier - multiplier) > 0.000_001) {
                    return blocked(NIGHT_MISMATCH_WARNING)
                }
                start
            }

            val sourceId = buildString {
                append("premium-context-v1|rule=")
                append(ruleSnapshot.sourceId.trim())
                append("|night=")
                append(nightSnapshot?.sourceId?.trim() ?: "none")
                append("|holiday=")
                append(scope.jurisdiction.name)
                append(':')
                append(scope.postalCode ?: "-")
            }

            output += SegmentedPayrollPremiumEvidenceV2(
                slice = slice,
                sourceId = sourceId,
                reliable = true,
                nightRule = nightSnapshot?.rule,
                holidayScope = scope
            )
        }

        return SegmentedPayrollPremiumEvidenceBridgeResultV2(
            evidence = output,
            reliable = true,
            warnings = warnings.distinct()
        )
    }
}
