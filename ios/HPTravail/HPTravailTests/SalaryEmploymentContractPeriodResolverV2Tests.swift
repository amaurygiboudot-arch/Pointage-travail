import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryEmploymentContractPeriodResolverV2Tests: XCTestCase {
    func testSingleVersionCoveringWholePeriodIsUsable() throws {
        let result = try XCTUnwrap(
            SalaryEmploymentContractPeriodResolverV2.resolve(
                companyId: "company-a",
                periodStartEpochDay: 100,
                periodEndEpochDay: 129,
                sourceReliable: true,
                snapshots: [snapshot(version: "v1", from: 50, to: nil, rate: 13.5)]
            )
        )

        XCTAssertTrue(result.readyForSingleContractCalculation)
        XCTAssertFalse(result.requiresMultipleContractVersions)
        XCTAssertEqual(result.contract?.grossHourlyRate, 13.5)
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testCoverageGapBlocksWithoutFallback() throws {
        let result = try XCTUnwrap(
            SalaryEmploymentContractPeriodResolverV2.resolve(
                companyId: "company-a",
                periodStartEpochDay: 100,
                periodEndEpochDay: 129,
                sourceReliable: true,
                snapshots: [snapshot(version: "v1", from: 100, to: 110, rate: 13.5)]
            )
        )

        XCTAssertFalse(result.readyForSingleContractCalculation)
        XCTAssertNil(result.contract)
        XCTAssertEqual(result.warnings, [SalaryEmploymentContractPeriodResolverV2.incompleteWarning])
    }

    func testTwoVersionsInPeriodBlockSingleContractCalculation() throws {
        let result = try XCTUnwrap(
            SalaryEmploymentContractPeriodResolverV2.resolve(
                companyId: "company-a",
                periodStartEpochDay: 100,
                periodEndEpochDay: 129,
                sourceReliable: true,
                snapshots: [
                    snapshot(version: "v1", from: 50, to: 114, rate: 13.5),
                    snapshot(version: "v2", from: 115, to: nil, rate: 14.0)
                ]
            )
        )

        XCTAssertFalse(result.readyForSingleContractCalculation)
        XCTAssertTrue(result.requiresMultipleContractVersions)
        XCTAssertNil(result.contract)
        XCTAssertEqual(result.warnings, [SalaryEmploymentContractPeriodResolverV2.multipleWarning])
    }

    func testUnreliableSourceBlocksEvenValidSnapshot() throws {
        let result = try XCTUnwrap(
            SalaryEmploymentContractPeriodResolverV2.resolve(
                companyId: "company-a",
                periodStartEpochDay: 100,
                periodEndEpochDay: 129,
                sourceReliable: false,
                snapshots: [snapshot(version: "v1", from: 50, to: nil, rate: 13.5)]
            )
        )

        XCTAssertFalse(result.sourceReliable)
        XCTAssertFalse(result.readyForSingleContractCalculation)
        XCTAssertNil(result.contract)
        XCTAssertEqual(result.warnings, [SalaryEmploymentContractPeriodResolverV2.unreliableWarning])
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
