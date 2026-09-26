package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.SegmentedProrationSourceV2

/**
 * Sortie riche de la chaîne segmentée.
 *
 * L'assemblage B20 reste le total canonique, mais les preuves et pièces intermédiaires sont
 * conservées pour les écrans/PDF/historique. Aucun champ absent n'est reconstruit à partir du total.
 */
data class SegmentedWorkedGrossProductionResultV2(
    val evidence: SegmentedPayrollSessionEvidenceResultV2,
    val variables: SegmentedWorkedVariableGrossSourceResultV2,
    val base: SegmentedMonthlyBaseResultV2,
    val assembly: SegmentedWorkedGrossAssemblyResultV2
) {
    val reliable: Boolean
        get() = evidence.reliable && variables.reliable && base.reliable && assembly.reliable
    val workedGross: Double?
        get() = assembly.workedGross
    val warnings: List<String>
        get() = (evidence.warnings + variables.warnings + base.warnings + assembly.warnings).distinct()
}

/**
 * Chaîne métier pure B21 -> B20.
 *
 * Aucune lecture UI ni fallback legacy ici. Les sources déjà confirmées sont assemblées dans
 * l'ordre canonique : base mensualisée segmentée, variables prouvées depuis les sessions V2,
 * puis brut de travail B20.
 */
object SegmentedWorkedGrossProductionV2 {
    fun calculateDetailed(
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        prorationSource: SegmentedProrationSourceV2,
        source: SegmentedPayrollSessionSourceV2,
        premiums: List<SegmentedPayrollPremiumEvidenceV2>,
        nowMs: Long
    ): SegmentedWorkedGrossProductionResultV2 {
        val base = SegmentedMonthlyBaseBridgeV2.calculate(
            contracts = contracts,
            rules = rules,
            prorationSource = prorationSource
        )
        val evidence = SegmentedPayrollSessionEvidenceBuilderV2.build(
            contracts = contracts,
            rules = rules,
            source = source,
            premiums = premiums,
            nowMs = nowMs
        )
        val variables = if (evidence.reliable) {
            val calculated = SegmentedWorkedVariableGrossSourceV2.calculate(
                contracts = contracts,
                rules = rules,
                sliceEvidence = evidence.slices
            )
            calculated.copy(
                warnings = (evidence.warnings + calculated.warnings).distinct()
            )
        } else {
            SegmentedWorkedVariableGrossSourceResultV2(
                pieces = emptyList(),
                reliable = false,
                warnings = evidence.warnings
            )
        }
        val assembly = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts,
            base = base,
            variableSource = variables
        )
        return SegmentedWorkedGrossProductionResultV2(
            evidence = evidence,
            variables = variables,
            base = base,
            assembly = assembly
        )
    }

    fun calculate(
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        prorationSource: SegmentedProrationSourceV2,
        source: SegmentedPayrollSessionSourceV2,
        premiums: List<SegmentedPayrollPremiumEvidenceV2>,
        nowMs: Long
    ): SegmentedWorkedGrossAssemblyResultV2 =
        calculateDetailed(
            contracts = contracts,
            rules = rules,
            prorationSource = prorationSource,
            source = source,
            premiums = premiums,
            nowMs = nowMs
        ).assembly
}
