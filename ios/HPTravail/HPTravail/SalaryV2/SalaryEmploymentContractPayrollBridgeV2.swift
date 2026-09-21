import Foundation

struct SalaryEmploymentContractPayrollSnapshotV2: Equatable {
    let resolution: SalaryEmploymentContractPeriodResolutionV2?
    let warnings: [String]
    let compatibility: SalaryContractSegmentPayrollCompatibilityResultV2?

    init(
        resolution: SalaryEmploymentContractPeriodResolutionV2?,
        warnings: [String],
        compatibility: SalaryContractSegmentPayrollCompatibilityResultV2? = nil
    ) {
        self.resolution = resolution
        self.warnings = warnings
        self.compatibility = compatibility
    }

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
/// Il résout uniquement l'historique daté confirmé. Plusieurs versions peuvent alimenter un même
/// calcul mensuel seulement si leurs paramètres de paie sont strictement identiques ; dès qu'un
/// paramètre change, le montant reste bloqué jusqu'à disposer d'une proratisation confirmée.
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

        guard let rawResolution = SalaryEmploymentContractPeriodResolverV2.resolve(
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

        let compatibility = rawResolution.calculationSegments.isEmpty
            ? nil
            : SalaryContractSegmentPayrollCompatibilityV2.resolve(rawResolution.calculationSegments)
        let canPromoteEquivalentVersions =
            rawResolution.contract == nil &&
            rawResolution.requiresMultipleContractVersions &&
            compatibility?.compatibleForSingleMonthlyCalculation == true &&
            compatibility?.contract != nil

        let resolution: SalaryEmploymentContractPeriodResolutionV2
        if canPromoteEquivalentVersions, let contract = compatibility?.contract {
            resolution = SalaryEmploymentContractPeriodResolutionV2(
                companyId: rawResolution.companyId,
                periodStartEpochDay: rawResolution.periodStartEpochDay,
                periodEndEpochDay: rawResolution.periodEndEpochDay,
                sourceReliable: rawResolution.sourceReliable,
                coverage: rawResolution.coverage,
                contract: contract,
                warnings: unique(
                    rawResolution.warnings.filter {
                        $0 != SalaryEmploymentContractPeriodResolverV2.multipleWarning
                    } + (compatibility?.warnings ?? [])
                )
            )
        } else {
            resolution = rawResolution
        }

        var baseWarnings = stored.warnings + resolution.warnings
        if !canPromoteEquivalentVersions {
            baseWarnings += compatibility?.warnings ?? []
        }
        return SalaryEmploymentContractPayrollSnapshotV2(
            resolution: resolution,
            warnings: unique(baseWarnings),
            compatibility: compatibility
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
