import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryContractSegmentPayrollCompatibilityV2Tests: XCTestCase {
    func testEquivalentVersionsKeepSingleMonthlyCalculationAvailable() {
        let result = SalaryContractSegmentPayrollCompatibilityV2.resolve([
            segment(version: "v1", start: 1, end: 14, rate: 13.7),
            segment(version: "v2", start: 15, end: 30, rate: 13.7)
        ])

        XCTAssertTrue(result.compatibleForSingleMonthlyCalculation)
        XCTAssertNotNil(result.contract)
        XCTAssertTrue(result.changedFields.isEmpty)
        XCTAssertEqual(result.warnings, [SalaryContractSegmentPayrollCompatibilityV2.equivalentVersionsWarning])
    }

    func testRateChangeBlocksImplicitProration() {
        let result = SalaryContractSegmentPayrollCompatibilityV2.resolve([
            segment(version: "v1", start: 1, end: 14, rate: 13.7),
            segment(version: "v2", start: 15, end: 30, rate: 14.2)
        ])

        XCTAssertFalse(result.compatibleForSingleMonthlyCalculation)
        XCTAssertNil(result.contract)
        XCTAssertTrue(result.changedFields.contains("grossHourlyRate"))
        XCTAssertEqual(result.warnings, [SalaryContractSegmentPayrollCompatibilityV2.changedPayrollInputsWarning])
    }

    func testWeeklyDurationChangeBlocksImplicitProration() {
        let result = SalaryContractSegmentPayrollCompatibilityV2.resolve([
            segment(version: "v1", start: 1, end: 14, rate: 13.7, weeklyMinutes: 35 * 60),
            segment(version: "v2", start: 15, end: 30, rate: 13.7, weeklyMinutes: 39 * 60)
        ])

        XCTAssertFalse(result.compatibleForSingleMonthlyCalculation)
        XCTAssertTrue(result.changedFields.contains("contractualWeeklyMinutes"))
    }

    private func segment(
        version: String,
        start: Int64,
        end: Int64,
        rate: Double,
        weeklyMinutes: Int = 35 * 60
    ) -> SalaryEmploymentContractCoverageSegmentV2 {
        let contract = ContractV2(
            id: "contract-company",
            employerId: "company",
            type: .fullTime,
            contractualWeeklyMinutes: weeklyMinutes,
            grossHourlyRate: rate,
            hireDateEpochDay: 1
        )
        return SalaryEmploymentContractCoverageSegmentV2(
            startEpochDay: start,
            endEpochDay: end,
            snapshot: SalaryEmploymentContractSnapshotV2(
                versionId: version,
                sourceId: "source-\(version)",
                effectiveFromEpochDay: start,
                effectiveToEpochDay: end,
                contract: contract,
                checkedAtMs: 1,
                note: nil
            )
        )
    }
}
