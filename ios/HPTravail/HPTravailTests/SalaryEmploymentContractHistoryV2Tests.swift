import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryEmploymentContractHistoryV2Tests: XCTestCase {
    func testSingleVersionCoversWholePeriod() throws {
        let history = try XCTUnwrap(SalaryEmploymentContractHistoryV2([
            snapshot(version: "v1", from: 100, to: 199, rate: 13.5)
        ]))

        let coverage = try XCTUnwrap(history.coverage(
            companyId: "company-a",
            periodStartEpochDay: 120,
            periodEndEpochDay: 150
        ))

        XCTAssertTrue(coverage.fullyCovered)
        XCTAssertEqual(coverage.segments.count, 1)
        XCTAssertEqual(coverage.singleSnapshotForWholePeriod?.contract.grossHourlyRate, 13.5)
        XCTAssertFalse(coverage.requiresMultipleContractVersions)
    }

    func testTwoContiguousVersionsRemainDistinct() throws {
        let history = try XCTUnwrap(SalaryEmploymentContractHistoryV2([
            snapshot(version: "v1", from: 100, to: 129, rate: 13.5),
            snapshot(version: "v2", from: 130, to: 199, rate: 14.0)
        ]))

        let coverage = try XCTUnwrap(history.coverage(
            companyId: "company-a",
            periodStartEpochDay: 120,
            periodEndEpochDay: 150
        ))

        XCTAssertTrue(coverage.fullyCovered)
        XCTAssertEqual(coverage.segments.map { $0.snapshot.versionId }, ["v1", "v2"])
        XCTAssertNil(coverage.singleSnapshotForWholePeriod)
        XCTAssertTrue(coverage.requiresMultipleContractVersions)
    }

    func testGapNeverFallsBackToAnotherVersion() throws {
        let history = try XCTUnwrap(SalaryEmploymentContractHistoryV2([
            snapshot(version: "v1", from: 100, to: 129, rate: 13.5),
            snapshot(version: "v2", from: 131, to: 199, rate: 14.0)
        ]))

        let coverage = try XCTUnwrap(history.coverage(
            companyId: "company-a",
            periodStartEpochDay: 120,
            periodEndEpochDay: 150
        ))

        XCTAssertFalse(coverage.fullyCovered)
        XCTAssertNil(coverage.singleSnapshotForWholePeriod)
    }

    func testFutureVersionIsNotHistoricalFallback() throws {
        let history = try XCTUnwrap(SalaryEmploymentContractHistoryV2([
            snapshot(version: "future", from: 200, to: nil, rate: 15.0)
        ]))

        XCTAssertNil(history.applicable(companyId: "company-a", epochDay: 150))
        let coverage = try XCTUnwrap(history.coverage(
            companyId: "company-a",
            periodStartEpochDay: 120,
            periodEndEpochDay: 150
        ))
        XCTAssertFalse(coverage.fullyCovered)
        XCTAssertTrue(coverage.segments.isEmpty)
    }

    func testOverlappingVersionsAreRejected() {
        XCTAssertNil(SalaryEmploymentContractHistoryV2([
            snapshot(version: "v1", from: 100, to: 140, rate: 13.5),
            snapshot(version: "v2", from: 130, to: 199, rate: 14.0)
        ]))
    }

    func testDuplicateVersionForSameCompanyIsRejected() {
        XCTAssertNil(SalaryEmploymentContractHistoryV2([
            snapshot(version: "v1", from: 100, to: 129, rate: 13.5),
            snapshot(version: "v1", from: 130, to: 199, rate: 14.0)
        ]))
    }

    func testSameVersionIdCanExistForDifferentCompanies() throws {
        let history = try XCTUnwrap(SalaryEmploymentContractHistoryV2([
            snapshot(version: "v1", from: 100, to: nil, rate: 13.5, companyId: "company-a"),
            snapshot(version: "v1", from: 100, to: nil, rate: 18.0, companyId: "company-b")
        ]))

        XCTAssertEqual(history.applicable(companyId: "company-a", epochDay: 120)?.contract.grossHourlyRate, 13.5)
        XCTAssertEqual(history.applicable(companyId: "company-b", epochDay: 120)?.contract.grossHourlyRate, 18.0)
    }

    func testAmbiguousLegacyForfaitCannotBecomeHistoricalTruth() {
        let contract = ContractV2(
            id: "legacy",
            employerId: "company-a",
            type: .forfait,
            contractualWeeklyMinutes: 35 * 60,
            grossHourlyRate: 15,
            hireDateEpochDay: nil
        )

        XCTAssertNil(SalaryEmploymentContractHistoryV2([dated(contract)]))
    }

    func testIncompleteHourlyContractIsRejectedBeforeHistory() {
        let contract = ContractV2(
            id: "incomplete",
            employerId: "company-a",
            type: .fullTime,
            contractualWeeklyMinutes: 35 * 60,
            grossHourlyRate: nil,
            hireDateEpochDay: nil
        )

        XCTAssertNil(SalaryEmploymentContractHistoryV2([dated(contract)]))
    }

    func testInvalidPayrollCutoffDayIsRejectedBeforeHistory() {
        let contract = ContractV2(
            id: "bad-cutoff",
            employerId: "company-a",
            type: .fullTime,
            contractualWeeklyMinutes: 35 * 60,
            grossHourlyRate: 15,
            hireDateEpochDay: nil,
            payrollCutoffDay: 32
        )

        XCTAssertNil(SalaryEmploymentContractHistoryV2([dated(contract)]))
    }

    func testValidForfaitHoursAndDaysRemainSupported() {
        let hours = ContractV2(
            id: "hours",
            employerId: "company-a",
            type: .forfaitHours,
            contractualWeeklyMinutes: nil,
            grossHourlyRate: nil,
            hireDateEpochDay: nil,
            forfaitHoursPeriod: .year,
            forfaitHours: 1607,
            forfaitAnnualDays: nil,
            monthlyGrossSalary: 3200
        )
        let days = ContractV2(
            id: "days",
            employerId: "company-b",
            type: .forfaitDays,
            contractualWeeklyMinutes: nil,
            grossHourlyRate: nil,
            hireDateEpochDay: nil,
            forfaitHoursPeriod: nil,
            forfaitHours: nil,
            forfaitAnnualDays: 218,
            monthlyGrossSalary: 4200
        )

        XCTAssertNotNil(SalaryEmploymentContractHistoryV2([dated(hours, version: "hours")]))
        XCTAssertNotNil(SalaryEmploymentContractHistoryV2([dated(days, version: "days")]))
    }

    private func dated(
        _ contract: ContractV2,
        version: String = "v1"
    ) -> SalaryEmploymentContractSnapshotV2 {
        SalaryEmploymentContractSnapshotV2(
            versionId: version,
            sourceId: "user-confirmed",
            effectiveFromEpochDay: 100,
            effectiveToEpochDay: nil,
            contract: contract,
            checkedAtMs: 1,
            note: nil
        )
    }

    private func snapshot(
        version: String,
        from: Int64,
        to: Int64?,
        rate: Double,
        companyId: String = "company-a"
    ) -> SalaryEmploymentContractSnapshotV2 {
        SalaryEmploymentContractSnapshotV2(
            versionId: version,
            sourceId: "user-confirmed",
            effectiveFromEpochDay: from,
            effectiveToEpochDay: to,
            contract: ContractV2(
                id: "contract-\(companyId)-\(version)",
                employerId: companyId,
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
