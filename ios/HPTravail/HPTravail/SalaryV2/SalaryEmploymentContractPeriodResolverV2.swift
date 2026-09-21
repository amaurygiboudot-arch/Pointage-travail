import Foundation

struct SalaryEmploymentContractPeriodResolutionV2: Equatable {
    let companyId: String
    let periodStartEpochDay: Int64
    let periodEndEpochDay: Int64
    let sourceReliable: Bool
    let coverage: SalaryEmploymentContractCoverageV2?
    let contract: ContractV2?
    let warnings: [String]

    var readyForSingleContractCalculation: Bool {
        sourceReliable && contract != nil
    }

    var requiresMultipleContractVersions: Bool {
        coverage?.requiresMultipleContractVersions == true
    }
}

/// Résolution fail-closed du contrat applicable à une période de paie complète.
///
/// Aucune version n'est choisie arbitrairement : stockage non fiable, trou de couverture ou
/// changement de contrat dans la période bloquent le contrat unique jusqu'à une résolution sûre.
enum SalaryEmploymentContractPeriodResolverV2 {
    static let unreliableWarning =
        "Contrat de paie : historique contractuel non fiable ; aucun contrat n'est utilisé pour cette période."
    static let incompleteWarning =
        "Contrat de paie : la période n'est pas entièrement couverte par un contrat daté confirmé ; aucun fallback n'est utilisé."
    static let multipleWarning =
        "Contrat de paie : plusieurs versions contractuelles couvrent cette période ; le calcul unique est bloqué jusqu'au calcul segmenté."

    static func resolve(
        companyId: String,
        periodStartEpochDay: Int64,
        periodEndEpochDay: Int64,
        sourceReliable: Bool,
        snapshots: [SalaryEmploymentContractSnapshotV2]
    ) -> SalaryEmploymentContractPeriodResolutionV2? {
        let normalizedCompanyId = companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedCompanyId.isEmpty,
              periodEndEpochDay >= periodStartEpochDay else {
            return nil
        }

        guard sourceReliable else {
            return SalaryEmploymentContractPeriodResolutionV2(
                companyId: normalizedCompanyId,
                periodStartEpochDay: periodStartEpochDay,
                periodEndEpochDay: periodEndEpochDay,
                sourceReliable: false,
                coverage: nil,
                contract: nil,
                warnings: [unreliableWarning]
            )
        }

        guard let history = SalaryEmploymentContractHistoryV2(snapshots),
              let coverage = history.coverage(
                companyId: normalizedCompanyId,
                periodStartEpochDay: periodStartEpochDay,
                periodEndEpochDay: periodEndEpochDay
              ) else {
            return SalaryEmploymentContractPeriodResolutionV2(
                companyId: normalizedCompanyId,
                periodStartEpochDay: periodStartEpochDay,
                periodEndEpochDay: periodEndEpochDay,
                sourceReliable: false,
                coverage: nil,
                contract: nil,
                warnings: [unreliableWarning]
            )
        }

        guard coverage.fullyCovered else {
            return SalaryEmploymentContractPeriodResolutionV2(
                companyId: normalizedCompanyId,
                periodStartEpochDay: periodStartEpochDay,
                periodEndEpochDay: periodEndEpochDay,
                sourceReliable: true,
                coverage: coverage,
                contract: nil,
                warnings: [incompleteWarning]
            )
        }

        guard !coverage.requiresMultipleContractVersions else {
            return SalaryEmploymentContractPeriodResolutionV2(
                companyId: normalizedCompanyId,
                periodStartEpochDay: periodStartEpochDay,
                periodEndEpochDay: periodEndEpochDay,
                sourceReliable: true,
                coverage: coverage,
                contract: nil,
                warnings: [multipleWarning]
            )
        }

        let contract = coverage.singleSnapshotForWholePeriod?.contract
        return SalaryEmploymentContractPeriodResolutionV2(
            companyId: normalizedCompanyId,
            periodStartEpochDay: periodStartEpochDay,
            periodEndEpochDay: periodEndEpochDay,
            sourceReliable: true,
            coverage: coverage,
            contract: contract,
            warnings: contract == nil ? [incompleteWarning] : []
        )
    }
}
