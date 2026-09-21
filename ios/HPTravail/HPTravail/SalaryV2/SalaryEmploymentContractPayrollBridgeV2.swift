import Foundation

struct SalaryEmploymentContractPayrollSnapshotV2: Equatable {
    let resolution: SalaryEmploymentContractPeriodResolutionV2?
    let warnings: [String]

    var contract: ContractV2? {
        resolution?.contract
    }

    var readyForSingleContractCalculation: Bool {
        resolution?.readyForSingleContractCalculation == true
    }
}

/// Source contractuelle autoritative de l'espace Salaire iOS.
///
/// Le bridge ne consulte jamais l'ancien store de « contrat courant » pour compléter un mois.
/// Il résout uniquement l'historique daté confirmé et reste bloqué si la période est incomplète
/// ou contient plusieurs versions nécessitant un calcul segmenté.
enum SalaryEmploymentContractPayrollBridgeV2 {
    static let invalidPeriodWarning =
        "Contrat de paie : période civile invalide ; aucun contrat n'est utilisé."

    static func resolve(
        companyId: String,
        period: YearMonthV2,
        stored: SalaryEmploymentContractHistoryReadResultV2 = SalaryEmploymentContractHistoryStoreV2.readConfirmed()
    ) -> SalaryEmploymentContractPayrollSnapshotV2 {
        guard let range = SalaryConventionCoverageResolverV2.monthEpochDayRange(period) else {
            return SalaryEmploymentContractPayrollSnapshotV2(
                resolution: nil,
                warnings: unique(stored.warnings + [invalidPeriodWarning])
            )
        }

        guard let resolution = SalaryEmploymentContractPeriodResolverV2.resolve(
            companyId: companyId,
            periodStartEpochDay: range.start,
            periodEndEpochDay: range.end,
            sourceReliable: stored.reliable,
            snapshots: stored.snapshots
        ) else {
            return SalaryEmploymentContractPayrollSnapshotV2(
                resolution: nil,
                warnings: unique(
                    stored.warnings + [SalaryEmploymentContractPeriodResolverV2.unreliableWarning]
                )
            )
        }

        return SalaryEmploymentContractPayrollSnapshotV2(
            resolution: resolution,
            warnings: unique(stored.warnings + resolution.warnings)
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
