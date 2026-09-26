import Foundation

/// Sortie riche de la chaîne segmentée.
///
/// B20 reste le total canonique, mais les preuves et pièces intermédiaires sont conservées
/// pour l'affichage/PDF/historique. Aucun détail absent n'est reconstruit à partir du total.
struct SalarySegmentedWorkedGrossProductionResultV2 {
    let evidence: SalarySegmentedPayrollSessionEvidenceResultV2
    let variables: SalarySegmentedWorkedVariableGrossSourceResultV2
    let base: SegmentedMonthlyBaseResultV2
    let assembly: SalarySegmentedWorkedGrossAssemblyResultV2

    var reliable: Bool {
        evidence.reliable && variables.reliable && base.reliable && assembly.reliable
    }

    var workedGross: Double? { assembly.workedGross }

    var warnings: [String] {
        unique(evidence.warnings + variables.warnings + base.warnings + assembly.warnings)
    }

    private func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}

/// Chaîne métier pure B21 -> B20 pour les mois segmentés.
///
/// Les sources sont déjà confirmées en amont. Aucun écran, fallback legacy ou projection nette
/// n'est utilisé ici : base segmentée -> variables prouvées -> brut de travail.
enum SalarySegmentedWorkedGrossProductionV2 {
    static func calculateDetailed(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        prorationSource: SalarySegmentedProrationSourceV2,
        source: SalarySegmentedPayrollSessionSourceV2,
        premiums: [SalarySegmentedPayrollPremiumEvidenceV2],
        now: Date
    ) -> SalarySegmentedWorkedGrossProductionResultV2 {
        let base = SalarySegmentedMonthlyBaseBridgeV2.calculate(
            contracts: contracts,
            rules: rules,
            prorationSource: prorationSource
        )
        let evidence = SalarySegmentedPayrollSessionEvidenceBuilderV2.build(
            contracts: contracts,
            rules: rules,
            source: source,
            premiums: premiums,
            now: now
        )
        let variables: SalarySegmentedWorkedVariableGrossSourceResultV2
        if evidence.reliable {
            let calculated = SalarySegmentedWorkedVariableGrossSourceV2.calculate(
                contracts: contracts,
                rules: rules,
                sliceEvidence: evidence.slices
            )
            variables = SalarySegmentedWorkedVariableGrossSourceResultV2(
                pieces: calculated.pieces,
                reliable: calculated.reliable,
                warnings: unique(evidence.warnings + calculated.warnings)
            )
        } else {
            variables = SalarySegmentedWorkedVariableGrossSourceResultV2(
                pieces: [],
                reliable: false,
                warnings: evidence.warnings
            )
        }
        let assembly = SalarySegmentedWorkedGrossAssemblerV2.assemble(
            contracts: contracts,
            base: base,
            variableSource: variables
        )
        return SalarySegmentedWorkedGrossProductionResultV2(
            evidence: evidence,
            variables: variables,
            base: base,
            assembly: assembly
        )
    }

    static func calculate(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        prorationSource: SalarySegmentedProrationSourceV2,
        source: SalarySegmentedPayrollSessionSourceV2,
        premiums: [SalarySegmentedPayrollPremiumEvidenceV2],
        now: Date
    ) -> SalarySegmentedWorkedGrossAssemblyResultV2 {
        calculateDetailed(
            contracts: contracts,
            rules: rules,
            prorationSource: prorationSource,
            source: source,
            premiums: premiums,
            now: now
        ).assembly
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
