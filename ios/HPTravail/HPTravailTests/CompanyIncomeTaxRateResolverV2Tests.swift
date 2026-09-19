import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class CompanyIncomeTaxRateResolverV2Tests: XCTestCase {
    func testRejectsNonPositiveStartYear() {
        let period = YearMonthV2(year: 2026, month: 4)!
        let record = CompanyIncomeTaxRateResolverV2.Record(
            id: "invalid-year",
            ratePercent: 3.2,
            effectiveFrom: YearMonthV2(year: 0, month: 1),
            effectiveTo: nil,
            source: "Bulletin confirmé"
        )

        let result = CompanyIncomeTaxRateResolverV2.resolve(records: [record], period: period)

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.rate)
        XCTAssertTrue(result.warnings.contains { $0.contains("invalide") })
    }
}
