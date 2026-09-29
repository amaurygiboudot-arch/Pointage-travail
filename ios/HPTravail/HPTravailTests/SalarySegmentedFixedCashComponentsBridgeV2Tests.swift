import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedFixedCashComponentsBridgeV2Tests: XCTestCase {
    private let period = YearMonthV2(year: 2026, month: 9)!

    func testSameConfirmedSeniorityForEveryDayKeepsFixedCashComponents() {
        let result = SalarySegmentedFixedCashComponentsBridgeV2.assemble(
            period: period,
            dailySeniority: Array(repeating: seniority(24), count: 30),
            companyPremiums: premiums(reliable: true),
            coverage: coverage(confirmed: true)
        )

        XCTAssertTrue(result.exhaustive)
        XCTAssertEqual(result.components.map(\.amount), [24, 100])
        XCTAssertFalse(result.sourceId.isEmpty)
    }

    func testChangeOnOneDayBlocksWholeFixedCashPackage() {
        var daily = Array(repeating: seniority(24), count: 30)
        daily[14] = seniority(30)
        let result = SalarySegmentedFixedCashComponentsBridgeV2.assemble(
            period: period,
            dailySeniority: daily,
            companyPremiums: premiums(reliable: true),
            coverage: coverage(confirmed: true)
        )

        XCTAssertFalse(result.exhaustive)
        XCTAssertTrue(result.components.isEmpty)
        XCTAssertTrue(result.warnings.contains(SalarySegmentedFixedCashComponentsBridgeV2.seniorityTransitionWarning))
    }

    func testMissingDayCannotProveAbsenceOfIntermediateTransition() {
        let result = SalarySegmentedFixedCashComponentsBridgeV2.assemble(
            period: period,
            dailySeniority: Array(repeating: seniority(24), count: 29),
            companyPremiums: premiums(reliable: true),
            coverage: coverage(confirmed: true)
        )

        XCTAssertFalse(result.exhaustive)
        XCTAssertTrue(result.components.isEmpty)
    }

    func testUnknownMonthlyCompanyPremiumCoverageStillBlocksCash() {
        let result = SalarySegmentedFixedCashComponentsBridgeV2.assemble(
            period: period,
            dailySeniority: Array(repeating: seniority(24), count: 30),
            companyPremiums: premiums(reliable: true),
            coverage: coverage(confirmed: false)
        )

        XCTAssertFalse(result.exhaustive)
        XCTAssertTrue(result.sourceId.isEmpty)
    }

    private func seniority(_ amount: Double) -> SalaryConventionSeniorityPremiumV2.Result {
        .init(
            applicable: true,
            reliable: true,
            selectedRule: nil,
            stepYears: 3,
            rate: 0.024,
            monthlyAmount: amount,
            warnings: []
        )
    }

    private func premiums(reliable: Bool) -> CompanyPremiumContractV2.Snapshot {
        .init(
            applied: [.init(id: "team", label: "Équipe", grossAmount: 100)],
            totalGross: 100,
            reliable: reliable,
            warnings: []
        )
    }

    private func coverage(confirmed: Bool) -> CompanyPremiumStoreV2.MonthCoverageSnapshot {
        .init(
            confirmed: confirmed,
            source: confirmed ? "bulletin" : nil,
            storageReliable: true,
            warnings: []
        )
    }
}
