package com.amaury.pointage.v2.engine

/**
 * Refuse l'application automatique lorsque deux règles d'accord couvrent
 * au moins une même heure hebdomadaire. Aucun arbitrage n'est deviné.
 */
object CompanyAgreementOvertimeConflictV2 {
    data class Result(
        val safeRules: List<CompanyAgreementOvertimeRuleV2.Rule>,
        val conflictingRules: List<CompanyAgreementOvertimeRuleV2.Rule>
    ) {
        val hasConflicts: Boolean get() = conflictingRules.isNotEmpty()
    }

    fun check(rules: List<CompanyAgreementOvertimeRuleV2.Rule>): Result {
        if (rules.size < 2) return Result(rules, emptyList())

        val conflictingIndexes = mutableSetOf<Int>()
        rules.indices.forEach { leftIndex ->
            ((leftIndex + 1) until rules.size).forEach { rightIndex ->
                if (overlap(rules[leftIndex].band, rules[rightIndex].band)) {
                    conflictingIndexes += leftIndex
                    conflictingIndexes += rightIndex
                }
            }
        }

        return Result(
            safeRules = rules.filterIndexed { index, _ -> index !in conflictingIndexes },
            conflictingRules = rules.filterIndexed { index, _ -> index in conflictingIndexes }
        )
    }

    private fun overlap(
        left: CompanyAgreementOvertimeBandV2.Band,
        right: CompanyAgreementOvertimeBandV2.Band
    ): Boolean {
        val leftEnd = left.toHourInclusive ?: Int.MAX_VALUE
        val rightEnd = right.toHourInclusive ?: Int.MAX_VALUE
        return left.fromHourInclusive <= rightEnd && right.fromHourInclusive <= leftEnd
    }
}
