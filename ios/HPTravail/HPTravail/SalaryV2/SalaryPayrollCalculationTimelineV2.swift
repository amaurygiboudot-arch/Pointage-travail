import Foundation

/// Tranche minimale où contrat et règles conventionnelles sont constants.
struct SalaryPayrollCalculationSliceV2: Equatable {
    let startEpochDay: Int64
    let endEpochDay: Int64
    let contractSnapshot: SalaryEmploymentContractSnapshotV2
    let ruleSnapshot: SalaryConventionRuleSnapshotV2

    var contractVersionId: String { contractSnapshot.versionId }
    var ruleVersionId: String { ruleSnapshot.versionId }
}

struct SalaryPayrollCalculationTimelineResultV2: Equatable {
    let periodStartEpochDay: Int64
    let periodEndEpochDay: Int64
    let slices: [SalaryPayrollCalculationSliceV2]
    let reliable: Bool
    let warnings: [String]
}

/// Intersection déterministe des segments de contrat et de convention.
/// Aucune version unique n'est choisie pour le mois lorsque les historiques changent en cours de période.
enum SalaryPayrollCalculationTimelineV2 {
    static let unreliableWarning =
        "Calcul Salaire V2 : contrat ou règles datées non fiables ; aucune tranche monétaire n'est produite."
    static let periodMismatchWarning =
        "Calcul Salaire V2 : les couvertures contrat et règles ne portent pas sur la même période ; calcul segmenté bloqué."
    static let incompleteWarning =
        "Calcul Salaire V2 : l'intersection contrat/règles ne couvre pas chaque jour de la période ; calcul segmenté bloqué."

    static func align(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2
    ) -> SalaryPayrollCalculationTimelineResultV2 {
        let start = contracts.periodStartEpochDay
        let end = contracts.periodEndEpochDay

        guard contracts.sourceReliable, rules.sourceReliable else {
            return blocked(
                start: start,
                end: end,
                warning: unreliableWarning,
                upstream: contracts.warnings + rules.warnings
            )
        }
        guard rules.periodStartEpochDay == start,
              rules.periodEndEpochDay == end else {
            return blocked(
                start: start,
                end: end,
                warning: periodMismatchWarning,
                upstream: contracts.warnings + rules.warnings
            )
        }

        let contractSegments = contracts.calculationSegments.sorted { $0.startEpochDay < $1.startEpochDay }
        let ruleSegments = rules.fullyCovered
            ? rules.segments.sorted { $0.startEpochDay < $1.startEpochDay }
            : []
        guard !contractSegments.isEmpty, !ruleSegments.isEmpty else {
            return blocked(
                start: start,
                end: end,
                warning: incompleteWarning,
                upstream: contracts.warnings + rules.warnings
            )
        }

        var slices: [SalaryPayrollCalculationSliceV2] = []
        var contractIndex = 0
        var ruleIndex = 0
        while contractIndex < contractSegments.count && ruleIndex < ruleSegments.count {
            let contract = contractSegments[contractIndex]
            let rule = ruleSegments[ruleIndex]
            let sliceStart = max(contract.startEpochDay, rule.startEpochDay)
            let sliceEnd = min(contract.endEpochDay, rule.endEpochDay)
            if sliceStart <= sliceEnd {
                slices.append(
                    SalaryPayrollCalculationSliceV2(
                        startEpochDay: sliceStart,
                        endEpochDay: sliceEnd,
                        contractSnapshot: contract.snapshot,
                        ruleSnapshot: rule.snapshot
                    )
                )
            }

            if contract.endEpochDay < rule.endEpochDay {
                contractIndex += 1
            } else if rule.endEpochDay < contract.endEpochDay {
                ruleIndex += 1
            } else {
                contractIndex += 1
                ruleIndex += 1
            }
        }

        guard coversEveryDay(slices, start: start, end: end) else {
            return blocked(
                start: start,
                end: end,
                warning: incompleteWarning,
                upstream: contracts.warnings + rules.warnings
            )
        }

        return SalaryPayrollCalculationTimelineResultV2(
            periodStartEpochDay: start,
            periodEndEpochDay: end,
            slices: slices,
            reliable: true,
            warnings: unique(contracts.warnings + rules.warnings)
        )
    }

    private static func coversEveryDay(
        _ slices: [SalaryPayrollCalculationSliceV2],
        start: Int64,
        end: Int64
    ) -> Bool {
        guard !slices.isEmpty else { return false }
        var cursor = start
        for slice in slices.sorted(by: { $0.startEpochDay < $1.startEpochDay }) {
            guard slice.startEpochDay == cursor,
                  slice.endEpochDay >= slice.startEpochDay else {
                return false
            }
            if slice.endEpochDay == end { return true }
            guard slice.endEpochDay < end,
                  slice.endEpochDay < Int64.max else {
                return false
            }
            cursor = slice.endEpochDay + 1
        }
        return false
    }

    private static func blocked(
        start: Int64,
        end: Int64,
        warning: String,
        upstream: [String]
    ) -> SalaryPayrollCalculationTimelineResultV2 {
        SalaryPayrollCalculationTimelineResultV2(
            periodStartEpochDay: start,
            periodEndEpochDay: end,
            slices: [],
            reliable: false,
            warnings: unique(upstream + [warning])
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
