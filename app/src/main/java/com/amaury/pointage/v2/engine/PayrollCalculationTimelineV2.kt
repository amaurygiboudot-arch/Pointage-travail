package com.amaury.pointage.v2.engine

/**
 * Tranche minimale de calcul où contrat ET règles conventionnelles sont constants.
 * Les bornes sont inclusives et proviennent uniquement d'historiques datés confirmés.
 */
data class PayrollCalculationSliceV2(
    val startEpochDay: Long,
    val endEpochDay: Long,
    val contractSnapshot: EmploymentContractSnapshotV2,
    val ruleSnapshot: ConventionRuleSnapshotV2
) {
    val contractVersionId: String get() = contractSnapshot.versionId
    val ruleVersionId: String get() = ruleSnapshot.versionId
}

data class PayrollCalculationTimelineResultV2(
    val periodStartEpochDay: Long,
    val periodEndEpochDay: Long,
    val slices: List<PayrollCalculationSliceV2>,
    val reliable: Boolean,
    val warnings: List<String>
)

/**
 * Intersection déterministe des segments de contrat et des segments de règles.
 *
 * Exemple : contrat v1 jusqu'au 14, contrat v2 dès le 15, règle r1 jusqu'au 19 puis r2 :
 * le résultat contient [v1/r1], [v2/r1], [v2/r2]. Aucun côté n'est aplati sur le mois.
 */
object PayrollCalculationTimelineV2 {
    const val UNRELIABLE_WARNING =
        "Calcul Salaire V2 : contrat ou règles datées non fiables ; aucune tranche monétaire n'est produite."
    const val PERIOD_MISMATCH_WARNING =
        "Calcul Salaire V2 : les couvertures contrat et règles ne portent pas sur la même période ; calcul segmenté bloqué."
    const val INCOMPLETE_WARNING =
        "Calcul Salaire V2 : l'intersection contrat/règles ne couvre pas chaque jour de la période ; calcul segmenté bloqué."

    fun align(
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2
    ): PayrollCalculationTimelineResultV2 {
        val start = contracts.periodStartEpochDay
        val end = contracts.periodEndEpochDay
        if (!contracts.sourceReliable || !rules.sourceReliable) {
            return blocked(start, end, UNRELIABLE_WARNING, contracts.warnings + rules.warnings)
        }
        if (rules.periodStartEpochDay != start || rules.periodEndEpochDay != end) {
            return blocked(start, end, PERIOD_MISMATCH_WARNING, contracts.warnings + rules.warnings)
        }

        val contractSegments = contracts.calculationSegments.sortedBy { it.startEpochDay }
        val ruleSegments = rules.calculationSegments.sortedBy { it.startEpochDay }
        if (contractSegments.isEmpty() || ruleSegments.isEmpty()) {
            return blocked(start, end, INCOMPLETE_WARNING, contracts.warnings + rules.warnings)
        }

        val slices = mutableListOf<PayrollCalculationSliceV2>()
        var contractIndex = 0
        var ruleIndex = 0
        while (contractIndex < contractSegments.size && ruleIndex < ruleSegments.size) {
            val contract = contractSegments[contractIndex]
            val rule = ruleSegments[ruleIndex]
            val sliceStart = maxOf(contract.startEpochDay, rule.startEpochDay)
            val sliceEnd = minOf(contract.endEpochDay, rule.endEpochDay)
            if (sliceStart <= sliceEnd) {
                slices += PayrollCalculationSliceV2(
                    startEpochDay = sliceStart,
                    endEpochDay = sliceEnd,
                    contractSnapshot = contract.snapshot,
                    ruleSnapshot = rule.snapshot
                )
            }

            when {
                contract.endEpochDay < rule.endEpochDay -> contractIndex += 1
                rule.endEpochDay < contract.endEpochDay -> ruleIndex += 1
                else -> {
                    contractIndex += 1
                    ruleIndex += 1
                }
            }
        }

        if (!coversEveryDay(slices, start, end)) {
            return blocked(start, end, INCOMPLETE_WARNING, contracts.warnings + rules.warnings)
        }

        return PayrollCalculationTimelineResultV2(
            periodStartEpochDay = start,
            periodEndEpochDay = end,
            slices = slices,
            reliable = true,
            warnings = (contracts.warnings + rules.warnings).distinct()
        )
    }

    private fun coversEveryDay(
        slices: List<PayrollCalculationSliceV2>,
        start: Long,
        end: Long
    ): Boolean {
        if (slices.isEmpty()) return false
        var cursor = start
        for (slice in slices.sortedBy { it.startEpochDay }) {
            if (slice.startEpochDay != cursor || slice.endEpochDay < slice.startEpochDay) return false
            if (slice.endEpochDay == end) return true
            if (slice.endEpochDay >= end || slice.endEpochDay == Long.MAX_VALUE) return false
            cursor = slice.endEpochDay + 1L
        }
        return false
    }

    private fun blocked(
        start: Long,
        end: Long,
        warning: String,
        upstream: List<String>
    ) = PayrollCalculationTimelineResultV2(
        periodStartEpochDay = start,
        periodEndEpochDay = end,
        slices = emptyList(),
        reliable = false,
        warnings = (upstream + warning).distinct()
    )
}
