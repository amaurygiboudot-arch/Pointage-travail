import Foundation

/// Point d'entrée de production pour la base mensuelle d'un mois réellement segmenté.
///
/// Cette couche ne produit volontairement ni brut final, ni net. Elle active uniquement le
/// calculateur de base segmentée lorsque plusieurs versions contractuelles empêchent encore un
/// calcul mensuel unique et qu'une couverture conventionnelle existe. Les mois déjà réconciliés
/// vers un contrat unique restent sur le provider canonique habituel.
enum SalarySegmentedMonthlyBaseProductionV2 {
    static func resolve(
        contractSnapshot: SalaryEmploymentContractPayrollSnapshotV2?,
        conventionCoverage: SalaryConventionCoverageV2?,
        prorationSource: SalarySegmentedProrationSourceV2?
    ) -> SegmentedMonthlyBaseResultV2? {
        guard let contractSnapshot,
              let contracts = contractSnapshot.resolution,
              contracts.calculationSegments.count > 1,
              !contractSnapshot.readyForSingleContractCalculation,
              let conventionCoverage else {
            return nil
        }

        let source = prorationSource ?? SalarySegmentedProrationSourceV2(
            proration: nil,
            reliable: false,
            warnings: [SalarySegmentedProrationStoreV2.missingWarning]
        )

        return SalarySegmentedMonthlyBaseBridgeV2.calculate(
            contracts: contracts,
            rules: conventionCoverage,
            prorationSource: source
        )
    }
}
