import Foundation

/// Adaptateur journal RuntimeV2 + registre explicite de couverture -> source B21 iOS.
enum SalarySegmentedPayrollSessionSourceBridgeV2 {
    static func source(
        work: SalaryWorkSessionSourceV2,
        coverage: WorkHistoryCoverageResultV2,
        employerId: String,
        startEpochDay: Int64,
        endEpochDay: Int64,
        timeZoneId: String
    ) -> SalarySegmentedPayrollSessionSourceV2 {
        .init(
            employerId: employerId.trimmingCharacters(in: .whitespacesAndNewlines),
            work: work,
            sourceId: coverage.sourceId,
            exhaustive: coverage.fullyCovered,
            coveredStartEpochDay: startEpochDay,
            coveredEndEpochDay: endEpochDay,
            checkedAt: coverage.checkedAt ?? Date(timeIntervalSince1970: 0),
            timeZoneId: timeZoneId.trimmingCharacters(in: .whitespacesAndNewlines),
            warnings: Array(Set(coverage.warnings)).sorted()
        )
    }
}
