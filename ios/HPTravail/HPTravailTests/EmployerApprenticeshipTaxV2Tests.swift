import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class EmployerApprenticeshipTaxV2Tests: XCTestCase {
    func testGeneralRatesCalculatePrincipalAndBalanceSeparately() {
        let result = EmployerApprenticeshipTaxV2.calculate(
            grossSocial: 2_500,
            principalRate: 0.0059,
            balanceRate: 0.0009
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.principalAmount ?? -1, 14.75, accuracy: 0.001)
        XCTAssertEqual(result.balanceAccrualAmount ?? -1, 2.25, accuracy: 0.001)
        XCTAssertEqual(result.totalEmployerAmount ?? -1, 17, accuracy: 0.001)
    }

    func testZeroBalanceCanRepresentConfirmedLocalRule() {
        let result = EmployerApprenticeshipTaxV2.calculate(
            grossSocial: 2_500,
            principalRate: 0.0044,
            balanceRate: 0
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.principalAmount ?? -1, 11, accuracy: 0.001)
        XCTAssertEqual(result.balanceAccrualAmount ?? -1, 0, accuracy: 0.001)
    }

    func testConfirmedExemptionCanUseZeroRates() {
        let result = EmployerApprenticeshipTaxV2.calculate(
            grossSocial: 2_500,
            principalRate: 0,
            balanceRate: 0
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.totalEmployerAmount ?? -1, 0, accuracy: 0.001)
    }

    func testInvalidGrossNeverProducesApprenticeshipAmounts() {
        for gross in [-1.0, .nan, .infinity, -.infinity] {
            let result = EmployerApprenticeshipTaxV2.calculate(
                grossSocial: gross,
                principalRate: 0.0059,
                balanceRate: 0.0009
            )

            XCTAssertFalse(result.complete)
            XCTAssertNil(result.principalAmount)
            XCTAssertNil(result.balanceAccrualAmount)
            XCTAssertNil(result.totalEmployerAmount)
            XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("assiette") })
        }
    }

    func testOverlappingRecordsBlockAutomaticCalculation() {
        let first = EmployerApprenticeshipTaxV2.Record(
            id: "a",
            principalRate: 0.0059,
            balanceRate: 0.0009,
            effectiveFrom: .init(year: 2026, month: 1),
            effectiveTo: nil,
            source: "Urssaf"
        )
        let second = EmployerApprenticeshipTaxV2.Record(
            id: "b",
            principalRate: 0.0044,
            balanceRate: 0,
            effectiveFrom: .init(year: 2026, month: 7),
            effectiveTo: nil,
            source: "Régime local confirmé"
        )
        let result = EmployerApprenticeshipTaxV2.resolve(
            records: [first, second],
            period: .init(year: 2026, month: 9)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.principalRate)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("chevauchent") })
    }
}
