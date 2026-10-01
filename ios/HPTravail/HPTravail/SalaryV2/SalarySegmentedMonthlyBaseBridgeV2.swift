import Foundation

/// Pont fail-closed entre les timelines contractuelle/conventionnelle et le calculateur de
/// proratisation mensuelle. Les identifiants de version de contrat et de règle sont indépendants :
/// ils ne sont jamais supposés identiques.
enum SalarySegmentedMonthlyBaseBridgeV2 {
    static let ruleChangesWithinContractWarning =
        "Proratisation mensuelle : les règles de paie changent à l'intérieur d'un même segment contractuel ; la base mensuelle reste bloquée."
    static let timelineWarning =
        "Proratisation mensuelle : contrat et règles conventionnelles ne peuvent pas être alignés de façon fiable pour tout le mois."

    static func calculate(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        prorationSource: SalarySegmentedProrationSourceV2
    ) -> SegmentedMonthlyBaseResultV2 {
        guard prorationSource.reliable,
              let proration = prorationSource.proration else {
            return blocked(
                prorationSource.warnings.isEmpty
                    ? [ConfirmedSegmentedMonthlyProrationCalculatorV2.missingProrationWarning]
                    : prorationSource.warnings
            )
        }

        let timeline = SalaryPayrollCalculationTimelineV2.align(
            contracts: contracts,
            rules: rules
        )
        guard timeline.reliable else {
            return blocked(unique(timeline.warnings + [timelineWarning]))
        }

        let contractSegments = contracts.calculationSegments
        guard !contractSegments.isEmpty else {
            return blocked(unique(contracts.warnings + [timelineWarning]))
        }

        var rulesByContractVersionId: [String: PayrollRulesV2] = [:]
        for contractSegment in contractSegments {
            let versionId = contractSegment.snapshot.versionId
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard !versionId.isEmpty else {
                return blocked([timelineWarning])
            }

            let slices = timeline.slices
                .filter {
                    $0.contractVersionId.trimmingCharacters(in: .whitespacesAndNewlines) == versionId
                        && $0.endEpochDay >= contractSegment.startEpochDay
                        && $0.startEpochDay <= contractSegment.endEpochDay
                }
                .sorted { $0.startEpochDay < $1.startEpochDay }

            guard coversExactly(
                slices,
                start: contractSegment.startEpochDay,
                end: contractSegment.endEpochDay
            ),
            let firstRules = slices.first?.ruleSnapshot.rules,
            slices.allSatisfy({ $0.ruleSnapshot.rules == firstRules }) else {
                return blocked(
                    unique(
                        contracts.warnings
                            + rules.warnings
                            + [ruleChangesWithinContractWarning]
                    )
                )
            }

            if let existing = rulesByContractVersionId[versionId],
               existing != firstRules {
                return blocked([ruleChangesWithinContractWarning])
            }
            rulesByContractVersionId[versionId] = firstRules
        }

        return ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments: contractSegments,
            rulesByVersionId: rulesByContractVersionId,
            proration: proration
        )
    }

    private static func coversExactly(
        _ slices: [SalaryPayrollCalculationSliceV2],
        start: Int64,
        end: Int64
    ) -> Bool {
        guard !slices.isEmpty else { return false }
        var cursor = start
        for slice in slices {
            guard slice.startEpochDay == cursor,
                  slice.endEpochDay >= slice.startEpochDay,
                  slice.endEpochDay <= end else {
                return false
            }
            if slice.endEpochDay == end { return true }
            guard slice.endEpochDay < Int64.max else { return false }
            cursor = slice.endEpochDay + 1
        }
        return false
    }

    private static func blocked(_ warnings: [String]) -> SegmentedMonthlyBaseResultV2 {
        SegmentedMonthlyBaseResultV2(
            pieces: [],
            baseGross: nil,
            reliable: false,
            warnings: unique(warnings)
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
