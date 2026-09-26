package com.amaury.pointage.v2.engine

/**
 * Chaîne canonique intermédiaire : faits V2 déjà attestés -> B21 -> B20.
 *
 * Cette couche n'ajoute aucune formule. Elle orchestre les propriétaires existants et retourne
 * le résultat B20 complet, avec la fiabilité et les avertissements B21 conservés par le bridge.
 */
object SegmentedWorkedGrossPipelineV2 {
    fun calculate(
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        source: SegmentedPayrollSessionSourceV2,
        premiums: List<SegmentedPayrollPremiumEvidenceV2>,
        base: SegmentedMonthlyBaseResultV2,
        nowMs: Long
    ): SegmentedWorkedGrossAssemblyResultV2 {
        val variables = SegmentedPayrollSessionEvidenceBuilderV2.calculateVariables(
            contracts = contracts,
            rules = rules,
            source = source,
            premiums = premiums,
            nowMs = nowMs
        )
        return SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts,
            base = base,
            variableSource = variables
        )
    }
}
