import Foundation

/// Chaîne métier pure B21 -> B20 pour les mois segmentés.
///
/// Les sources sont déjà confirmées en amont. Aucun écran, fallback legacy ou projection nette
/// n'est utilisé ici : base segmentée -> variables prouvées -> brut de travail.
enum SalarySegmentedWorkedGrossProductionV2 {
    static func calculate(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        prorationSource: SalarySegmentedProrationSourceV2,
        source: SalarySegmentedPayrollSessionSourceV2,
        premiums: [SalarySegmentedPayrollPremiumEvidenceV2],
        now: Date
    ) -> SalarySegmentedWorkedGrossAssemblyResultV2 {
        let base = SalarySegmentedMonthlyBaseBridgeV2.calculate(
            contracts: contracts,
            rules: rules,
            prorationSource: prorationSource
        )
        let variables = SalarySegmentedPayrollSessionEvidenceBuilderV2.calculateVariables(
            contracts: contracts,
            rules: rules,
            source: source,
            premiums: premiums,
            now: now
        )
        return SalarySegmentedWorkedGrossAssemblerV2.assemble(
            contracts: contracts,
            base: base,
            variableSource: variables
        )
    }
}
