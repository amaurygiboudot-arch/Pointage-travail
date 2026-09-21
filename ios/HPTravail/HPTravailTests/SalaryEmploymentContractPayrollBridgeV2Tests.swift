import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryEmploymentContractPayrollBridgeV2Tests: XCTestCase {
    func testSingleVersionCoveringWholeMonthIsReady() throws {
        let period = try XCTUnwrap(YearMonthV2(year: 2026, month: 1))
        let range = try XCTUnwrap(SalaryConventionCoverageResolverV2.monthEpochDayRange(period))
        let stored = reliable([
            snapshot(version: "v1", from: range.start - 20, to: nil, rate: 14.0)
        ])

        let result = SalaryEmploymentContractPayrollBridgeV2.resolve(
            companyId: "company-a",
            period: period,
            stored: stored
        )

        XCTAssertTrue(result.readyForSingleContractCalculation)
        XCTAssertEqual(result.contract?.grossHourlyRate, 14.0)
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testEquivalentVersionsInsideMonthCanShareMonthlyCalculation() throws {
        let period = try XCTUnwrap(YearMonthV2(year: 2026, month: 1))
        let range = try XCTUnwrap(SalaryConventionCoverageResolverV2.monthEpochDayRange(period))
        let change = range.start + 15
        let stored = reliable([
            snapshot(version: "v1", from: range.start - 20, to: change - 1, rate: 14.0),
            snapshot(version: "v2", from: change, to: nil, rate: 14.0)
        ])

        let result = SalaryEmploymentContractPayrollBridgeV2.resolve(
            companyId: "company-a",
            period: period,
            stored: stored
        )

        XCTAssertTrue(result.readyForSingleContractCalculation)
        XCTAssertFalse(result.resolution?.requiresMultipleContractVersions == true)
        XCTAssertEqual(result.contract?.grossHourlyRate, 14.0)
        XCTAssertTrue(result.warnings.contains(SalaryContractSegmentPayrollCompatibilityV2.equivalentVersionsWarning))
        XCTAssertFalse(result.warnings.contains(SalaryEmploymentContractPeriodResolverV2.multipleWarning))
    }

    func testTwoDifferentRatesInsideMonthBlockSingleContractCalculation() throws {
        let period = try XCTUnwrap(YearMonthV2(year: 2026, month: 1))
        let range = try XCTUnwrap(SalaryConventionCoverageResolverV2.monthEpochDayRange(period))
        let change = range.start + 15
        let stored = reliable([
            snapshot(version: "v1", from: range.start - 20, to: change - 1, rate: 13.5),
            snapshot(version: "v2", from: change, to: nil, rate: 14.0)
        ])

        let result = SalaryEmploymentContractPayrollBridgeV2.resolve(
            companyId: "company-a",
            period: period,
            stored: stored
        )

        XCTAssertFalse(result.readyForSingleContractCalculation)
        XCTAssertTrue(result.resolution?.requiresMultipleContractVersions == true)
        XCTAssertNil(result.contract)
        XCTAssertTrue(result.warnings.contains(SalaryEmploymentContractPeriodResolverV2.multipleWarning))
        XCTAssertTrue(result.warnings.contains(SalaryContractSegmentPayrollCompatibilityV2.changedPayrollInputsWarning))
    }

    func testUnreliableHistoryBlocksEvenValidSnapshot() throws {
        let period = try XCTUnwrap(YearMonthV2(year: 2026, month: 1))
        let range = try XCTUnwrap(SalaryConventionCoverageResolverV2.monthEpochDayRange(period))
        let stored = SalaryEmploymentContractHistoryReadResultV2(
            snapshots: [snapshot(version: "v1", from: range.start - 20, to: nil, rate: 14.0)],
            reliable: false,
            repairedFromBackup: false,
            warnings: [SalaryEmploymentContractHistoryStoreV2.storageWarning]
        )

        let result = SalaryEmploymentContractPayrollBridgeV2.resolve(
            companyId: "company-a",
            period: period,
            stored: stored
        )

        XCTAssertFalse(result.readyForSingleContractCalculation)
        XCTAssertNil(result.contract)
        XCTAssertTrue(result.warnings.contains(SalaryEmploymentContractPeriodResolverV2.unreliableWarning))
    }

    private func reliable(
        _ snapshots: [SalaryEmploymentContractSnapshotV2]
    ) -> SalaryEmploymentContractHistoryReadResultV2 {
        SalaryEmploymentContractHistoryReadResultV2(
            snapshots: snapshots,
            reliable: true,
            repairedFromBackup: false,
            warnings: []
        )
    }

    private func snapshot(
        version: String,
        from: Int64,
        to: Int64?,
        rate: Double
    ) -> SalaryEmploymentContractSnapshotV2 {
        SalaryEmploymentContractSnapshotV2(
            versionId: version,
            sourceId: "test",
            effectiveFromEpochDay: from,
            effectiveToEpochDay: to,
            contract: ContractV2(
                id: "contract-\(version)",
                employerId: "company-a",
                type: .fullTime,
                contractualWeeklyMinutes: 35 * 60,
                grossHourlyRate: rate,
                hireDateEpochDay: nil
            ),
            checkedAtMs: 1,
            note: nil
        )
    }
}
