import Foundation
#if SWIFT_PACKAGE
import RuntimeV2Contract
#endif

struct SalarySegmentedWorkedGrossProductionResultV2 {
    let source: SalarySegmentedPayrollSessionSourceV2
    let variables: SalarySegmentedWorkedVariableGrossSourceResultV2
    let worked: SalarySegmentedWorkedGrossAssemblyResultV2
    let reliable: Bool
    let warnings: [String]
}

/// Point d'entrée interne couverture RuntimeV2 -> B21 -> B20.
/// Les adaptateurs de persistance diffèrent selon la plateforme, pas la règle métier.
enum SalarySegmentedWorkedGrossProductionV2 {
    static let boundaryWarning =
        "Brut segmenté : bornes de période non représentables pour une couverture hebdomadaire complète."

#if !SWIFT_PACKAGE
    static func calculateFromStores(
        defaults: UserDefaults = .standard,
        sessions: [WorkSession],
        storageReliable: Bool,
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        base: SegmentedMonthlyBaseResultV2,
        premiums: [SalarySegmentedPayrollPremiumEvidenceV2],
        requestedTimeZoneId: String,
        now: Date = Date()
    ) -> SalarySegmentedWorkedGrossProductionResultV2 {
        guard let bounds = requiredCoverageBounds(
            start: contracts.periodStartEpochDay,
            end: contracts.periodEndEpochDay
        ) else {
            return blocked(
                contracts: contracts,
                base: base,
                requestedTimeZoneId: requestedTimeZoneId,
                now: now,
                warning: boundaryWarning
            )
        }
        let work = SalaryWorkSessionBridgeV2.source(
            from: sessions,
            storageReliable: storageReliable
        )
        let coverage = WorkHistoryCoverageStoreV2.coverage(
            employerId: contracts.companyId,
            startEpochDay: bounds.start,
            endEpochDay: bounds.end,
            timeZoneId: requestedTimeZoneId,
            defaults: defaults,
            now: now
        )
        let source = SalarySegmentedPayrollSessionSourceBridgeV2.source(
            work: work,
            coverage: coverage,
            employerId: contracts.companyId,
            startEpochDay: bounds.start,
            endEpochDay: bounds.end,
            timeZoneId: requestedTimeZoneId
        )
        return calculateFromSource(
            contracts: contracts,
            rules: rules,
            base: base,
            source: source,
            premiums: premiums,
            now: now
        )
    }
#endif

    static func calculateFromSource(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        base: SegmentedMonthlyBaseResultV2,
        source: SalarySegmentedPayrollSessionSourceV2,
        premiums: [SalarySegmentedPayrollPremiumEvidenceV2],
        now: Date
    ) -> SalarySegmentedWorkedGrossProductionResultV2 {
        let variables = SalarySegmentedPayrollSessionEvidenceBuilderV2.calculateVariables(
            contracts: contracts,
            rules: rules,
            source: source,
            premiums: premiums,
            now: now
        )
        let worked = SalarySegmentedWorkedGrossAssemblerV2.assembleFromSource(
            contracts: contracts,
            base: base,
            source: variables
        )
        let warnings = unique(source.warnings + variables.warnings + worked.warnings)
        let normalizedWorked = SalarySegmentedWorkedGrossAssemblyResultV2(
            baseGross: worked.baseGross,
            variableGross: worked.variableGross,
            workedGross: worked.workedGross,
            reliable: worked.reliable,
            warnings: warnings
        )
        return .init(
            source: source,
            variables: variables,
            worked: normalizedWorked,
            reliable: normalizedWorked.reliable,
            warnings: warnings
        )
    }

    static func requiredCoverageBounds(
        start: Int64,
        end: Int64
    ) -> (start: Int64, end: Int64)? {
        guard end >= start else { return nil }
        let shiftedStart = start.addingReportingOverflow(3)
        let shiftedEnd = end.addingReportingOverflow(3)
        guard !shiftedStart.overflow, !shiftedEnd.overflow else { return nil }
        let startOffset = floorMod(shiftedStart.partialValue, 7)
        let endOffset = floorMod(shiftedEnd.partialValue, 7)
        let first = start.subtractingReportingOverflow(startOffset)
        let monday = end.subtractingReportingOverflow(endOffset)
        guard !first.overflow, !monday.overflow else { return nil }
        let sunday = monday.partialValue.addingReportingOverflow(6)
        guard !sunday.overflow else { return nil }
        return (first.partialValue, sunday.partialValue)
    }

    private static func blocked(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        base: SegmentedMonthlyBaseResultV2,
        requestedTimeZoneId: String,
        now: Date,
        warning: String
    ) -> SalarySegmentedWorkedGrossProductionResultV2 {
        let warnings = unique(contracts.warnings + base.warnings + [warning])
        let source = SalarySegmentedPayrollSessionSourceV2(
            employerId: contracts.companyId,
            work: .init(sessions: [], reliable: false),
            sourceId: "",
            exhaustive: false,
            coveredStartEpochDay: 0,
            coveredEndEpochDay: -1,
            checkedAt: now,
            timeZoneId: requestedTimeZoneId,
            warnings: warnings
        )
        let variables = SalarySegmentedWorkedVariableGrossSourceResultV2(
            pieces: [],
            reliable: false,
            warnings: warnings
        )
        let worked = SalarySegmentedWorkedGrossAssemblyResultV2(
            baseGross: nil,
            variableGross: nil,
            workedGross: nil,
            reliable: false,
            warnings: warnings
        )
        return .init(
            source: source,
            variables: variables,
            worked: worked,
            reliable: false,
            warnings: warnings
        )
    }

    private static func floorMod(_ value: Int64, _ modulus: Int64) -> Int64 {
        let remainder = value % modulus
        return remainder >= 0 ? remainder : remainder + modulus
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
