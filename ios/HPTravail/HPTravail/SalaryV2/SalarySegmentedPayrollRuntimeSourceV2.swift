import Foundation

struct SalarySegmentedPayrollCoverageRequirementV2: Equatable {
    let startEpochDay: Int64
    let endEpochDay: Int64
    let reliable: Bool
    let warnings: [String]
}

/// Raccord canonique WorkStoreV2/bridge -> couverture attestée -> preuves B21.
enum SalarySegmentedPayrollRuntimeSourceV2 {
    static let requirementWarning =
        "Preuves B21 : impossible de déterminer la plage hebdomadaire complète à certifier."

    static func requirement(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2
    ) -> SalarySegmentedPayrollCoverageRequirementV2 {
        let timeline = SalaryPayrollCalculationTimelineV2.align(
            contracts: contracts,
            rules: rules
        )
        guard timeline.reliable, !timeline.slices.isEmpty,
              let first = timeline.slices.map(\.startEpochDay).min(),
              let last = timeline.slices.map(\.endEpochDay).max() else {
            return .init(
                startEpochDay: timeline.periodStartEpochDay,
                endEpochDay: timeline.periodEndEpochDay,
                reliable: false,
                warnings: unique(timeline.warnings + [requirementWarning])
            )
        }

        let firstMonday = previousOrSameMonday(first)
        let lastMonday = previousOrSameMonday(last)
        guard lastMonday <= Int64.max - 6 else {
            return .init(
                startEpochDay: timeline.periodStartEpochDay,
                endEpochDay: timeline.periodEndEpochDay,
                reliable: false,
                warnings: unique(timeline.warnings + [requirementWarning])
            )
        }
        let lastSunday = lastMonday + 6
        guard lastSunday >= firstMonday else {
            return .init(
                startEpochDay: timeline.periodStartEpochDay,
                endEpochDay: timeline.periodEndEpochDay,
                reliable: false,
                warnings: unique(timeline.warnings + [requirementWarning])
            )
        }

        return .init(
            startEpochDay: firstMonday,
            endEpochDay: lastSunday,
            reliable: true,
            warnings: unique(timeline.warnings)
        )
    }

    static func source(
        work: SalaryWorkSessionSourceV2,
        defaults: UserDefaults = .standard,
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        timeZoneId: String,
        now: Date = Date()
    ) -> SalarySegmentedPayrollSessionSourceV2 {
        let required = requirement(contracts: contracts, rules: rules)
        guard required.reliable else {
            return .init(
                employerId: contracts.companyId.trimmingCharacters(in: .whitespacesAndNewlines),
                work: work,
                sourceId: "coverage-requirement-unavailable",
                exhaustive: false,
                coveredStartEpochDay: required.startEpochDay,
                coveredEndEpochDay: required.endEpochDay,
                checkedAt: now,
                timeZoneId: timeZoneId,
                warnings: unique(required.warnings)
            )
        }

        let source = SalarySegmentedPayrollCoverageStoreV2.source(
            work: work,
            defaults: defaults,
            employerId: contracts.companyId,
            requiredStartEpochDay: required.startEpochDay,
            requiredEndEpochDay: required.endEpochDay,
            timeZoneId: timeZoneId,
            now: now
        )
        return .init(
            employerId: source.employerId,
            work: source.work,
            sourceId: source.sourceId,
            exhaustive: source.exhaustive,
            coveredStartEpochDay: source.coveredStartEpochDay,
            coveredEndEpochDay: source.coveredEndEpochDay,
            checkedAt: source.checkedAt,
            timeZoneId: source.timeZoneId,
            warnings: unique(source.warnings + required.warnings)
        )
    }

    static func calculateVariables(
        work: SalaryWorkSessionSourceV2,
        defaults: UserDefaults = .standard,
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        premiums: [SalarySegmentedPayrollPremiumEvidenceV2],
        timeZoneId: String,
        now: Date = Date()
    ) -> SalarySegmentedWorkedVariableGrossSourceResultV2 {
        SalarySegmentedPayrollSessionEvidenceBuilderV2.calculateVariables(
            contracts: contracts,
            rules: rules,
            source: source(
                work: work,
                defaults: defaults,
                contracts: contracts,
                rules: rules,
                timeZoneId: timeZoneId,
                now: now
            ),
            premiums: premiums,
            now: now
        )
    }

    static func previousOrSameMonday(_ epochDay: Int64) -> Int64 {
        epochDay - ((epochDay % 7 + 10) % 7)
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
