import Foundation

/// Chaîne canonique intermédiaire : faits RuntimeV2 attestés -> B21 -> B20.
///
/// Aucune formule n'est ajoutée ici. Les moteurs B21/B20 existants restent propriétaires
/// des calculs et de leurs blocages ; cette couche ne fait que transporter le résultat complet.
enum SalarySegmentedWorkedGrossPipelineV2 {
    static func calculate(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        source: SalarySegmentedPayrollSessionSourceV2,
        premiums: [SalarySegmentedPayrollPremiumEvidenceV2],
        base: SegmentedMonthlyBaseResultV2,
        now: Date
    ) -> SalarySegmentedWorkedGrossAssemblyResultV2 {
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
