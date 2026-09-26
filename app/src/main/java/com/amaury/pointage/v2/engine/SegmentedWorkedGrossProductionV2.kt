package com.amaury.pointage.v2.engine

/**
 * Chaîne métier pure B21 -> B20.
 *
 * Aucune lecture UI ni fallback legacy ici. Les sources déjà confirmées sont assemblées dans
 * l'ordre canonique : base mensualisée segmentée, variables prouvées depuis les sessions V2,
 * puis brut de travail B20.
 */
object SegmentedWorkedGrossProductionV2 {
    fun calculate(
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        proration: ConfirmedSegmentedMonthlyProrationV2?,
        source: SegmentedPayrollSessionSourceV2,
        premiums: List<SegmentedPayrollPremiumEvidenceV2>,
        nowMs: Long
    ): SegmentedWorkedGrossAssemblyResultV2 {
        val base = SegmentedMonthlyBaseBridgeV2.calculate(
            contracts = contracts,
            rules = rules,
            proration = proration
        )
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
