import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class EmployeeWithholdingProjectionV2Tests: XCTestCase {
    func testDecimalHalfCentUsesSameOwnerForAllConsumers() {
        let result = EmployeeWithholdingProjectionV2.calculate(
            netBeforeIncomeTax: 26.15, netTaxable: 29, incomeTaxRate: rate(0.005)
        )
        XCTAssertEqual(result.incomeTax ?? -1, 0.15, accuracy: 0.000000001)
        XCTAssertEqual(result.netAfterIncomeTax ?? -1, 26, accuracy: 0.000000001)
    }

    func testTaxEqualToAvailableCashIsKnown() {
        let result = EmployeeWithholdingProjectionV2.calculate(
            netBeforeIncomeTax: 0.15, netTaxable: 29, incomeTaxRate: rate(0.005)
        )
        XCTAssertEqual(result.incomeTax ?? -1, 0.15, accuracy: 0.000000001)
        XCTAssertEqual(result.netAfterIncomeTax, 0)
    }

    func testTaxExceedingCashIsNotClampedOrPublishedAsCollected() {
        for (cash, taxable, taxRate) in [(0.14, 29.0, 0.005), (0, 771.27, 0.032), (2313.62, 2411.45, 1)] {
            let result = EmployeeWithholdingProjectionV2.calculate(
                netBeforeIncomeTax: cash, netTaxable: taxable, incomeTaxRate: rate(taxRate)
            )
            XCTAssertNil(result.incomeTax)
            XCTAssertNil(result.netAfterIncomeTax)
            XCTAssertTrue(result.warnings.contains { $0.contains("employeur") })
            XCTAssertFalse(result.warnings.contains { $0.contains("taux personnel") })
        }
    }

    func testConfirmedZeroRateAllowsKnownZeroCashResult() {
        let result = EmployeeWithholdingProjectionV2.calculate(
            netBeforeIncomeTax: 0, netTaxable: 771.27, incomeTaxRate: rate(0)
        )
        XCTAssertEqual(result.incomeTax, 0)
        XCTAssertEqual(result.netAfterIncomeTax, 0)
    }

    func testMissingCanonicalAmountNeverBecomesZero() {
        for (before, taxable) in [(nil, 29.0), (26.15, nil), (nil, nil)] as [(Double?, Double?)] {
            let result = EmployeeWithholdingProjectionV2.calculate(
                netBeforeIncomeTax: before, netTaxable: taxable, incomeTaxRate: rate(0.005)
            )
            XCTAssertNil(result.incomeTax)
            XCTAssertNil(result.netAfterIncomeTax)
        }
    }

    func testInvalidAmountsCannotProduceWithholding() {
        for invalid in [Double.nan, .infinity, -.infinity, -1, Double.greatestFiniteMagnitude] {
            let invalidCash = EmployeeWithholdingProjectionV2.calculate(
                netBeforeIncomeTax: invalid, netTaxable: 29, incomeTaxRate: rate(0.005)
            )
            let invalidTaxable = EmployeeWithholdingProjectionV2.calculate(
                netBeforeIncomeTax: 26.15, netTaxable: invalid, incomeTaxRate: rate(0.005)
            )
            for result in [invalidCash, invalidTaxable] {
                XCTAssertNil(result.incomeTax)
                XCTAssertNil(result.netAfterIncomeTax)
            }
        }
    }

    func testCurrentDatedRateOutsideCoverageRemainsIncomplete() {
        let april = YearMonthV2(year: 2026, month: 4)!
        let may = YearMonthV2(year: 2026, month: 5)!
        let resolved = CompanyIncomeTaxRateResolverV2.resolve(
            records: [.init(id: "april-only", ratePercent: 3.2, effectiveFrom: april,
                            effectiveTo: april, source: "Donnée synthétique confirmée")],
            period: may
        )
        let result = EmployeeWithholdingProjectionV2.calculate(
            netBeforeIncomeTax: 2313.62, netTaxable: 2411.45, incomeTaxRate: resolved
        )
        XCTAssertNil(result.incomeTax)
        XCTAssertNil(result.netAfterIncomeTax)
        XCTAssertTrue(result.warnings.contains { $0.contains("2026-05") })
    }

    private func rate(_ value: Double) -> CompanyIncomeTaxRateResolverV2.Snapshot {
        .init(rate: value, ratePercent: value * 100, source: "Donnée synthétique confirmée",
              hasDatedRecords: true, reliable: true, warnings: [])
    }
}
