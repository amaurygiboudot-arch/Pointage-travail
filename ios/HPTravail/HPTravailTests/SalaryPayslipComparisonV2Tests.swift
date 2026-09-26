import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryPayslipComparisonV2Tests: XCTestCase {
    private func snapshot(
        sourceReady: Bool = true,
        gross: Double? = 2_500.0,
        netBeforeTax: Double? = 2_000.0,
        taxable: Double? = 2_050.0,
        tax: Double? = 100.0,
        netAfterTax: Double? = 1_900.0
    ) -> SalaryWorkspaceSnapshotV2 {
        SalaryWorkspaceSnapshotV2(
            period: YearMonthV2(year: 2026, month: 9)!,
            sourceReady: sourceReady,
            socialGross: gross,
            netBeforeIncomeTax: netBeforeTax,
            netTaxable: taxable,
            incomeTax: tax,
            netAfterIncomeTax: netAfterTax,
            warnings: []
        )
    }

    func testExactValuesAreConforming() {
        let result = SalaryPayslipComparisonEngineV2.compare(
            snapshot: snapshot(),
            observed: SalaryPayslipObservedValuesV2(
                socialGross: 2_500.0,
                netBeforeIncomeTax: 2_000.0,
                netTaxable: 2_050.0,
                incomeTax: 100.0,
                netAfterIncomeTax: 1_900.0
            )
        )

        XCTAssertNotNil(result)
        XCTAssertTrue(result!.conforming)
        XCTAssertTrue(result!.discrepancies.isEmpty)
        XCTAssertEqual(result!.comparedFields.count, 5)
    }

    func testTwoCentDifferenceIsWithinTolerance() {
        let result = SalaryPayslipComparisonEngineV2.compare(
            snapshot: snapshot(),
            observed: SalaryPayslipObservedValuesV2(
                socialGross: 2_500.02,
                netBeforeIncomeTax: nil,
                netTaxable: nil,
                incomeTax: nil,
                netAfterIncomeTax: nil
            )
        )

        XCTAssertTrue(result!.conforming)
    }

    func testThreeCentDifferenceCreatesDiscrepancy() {
        let result = SalaryPayslipComparisonEngineV2.compare(
            snapshot: snapshot(),
            observed: SalaryPayslipObservedValuesV2(
                socialGross: 2_500.03,
                netBeforeIncomeTax: nil,
                netTaxable: nil,
                incomeTax: nil,
                netAfterIncomeTax: nil
            )
        )

        XCTAssertFalse(result!.conforming)
        XCTAssertEqual(result!.discrepancies.count, 1)
        XCTAssertEqual(result!.discrepancies.first?.field, .socialGross)
    }

    func testOnlyObservedAndCanonicalIntersectionIsCompared() {
        let result = SalaryPayslipComparisonEngineV2.compare(
            snapshot: snapshot(taxable: nil, tax: nil, netAfterTax: nil),
            observed: SalaryPayslipObservedValuesV2(
                socialGross: nil,
                netBeforeIncomeTax: 2_000.0,
                netTaxable: 999.0,
                incomeTax: 999.0,
                netAfterIncomeTax: 999.0
            )
        )

        XCTAssertEqual(result?.comparedFields, [.netBeforeIncomeTax])
        XCTAssertTrue(result?.conforming == true)
    }

    func testNoCanonicalSourceBlocksComparison() {
        let result = SalaryPayslipComparisonEngineV2.compare(
            snapshot: snapshot(sourceReady: false),
            observed: SalaryPayslipObservedValuesV2(
                socialGross: 2_500.0,
                netBeforeIncomeTax: nil,
                netTaxable: nil,
                incomeTax: nil,
                netAfterIncomeTax: nil
            )
        )

        XCTAssertNil(result)
    }

    func testNoComparableObservedValueReturnsNil() {
        let result = SalaryPayslipComparisonEngineV2.compare(
            snapshot: snapshot(),
            observed: SalaryPayslipObservedValuesV2(
                socialGross: nil,
                netBeforeIncomeTax: nil,
                netTaxable: nil,
                incomeTax: nil,
                netAfterIncomeTax: nil
            )
        )

        XCTAssertNil(result)
    }

    func testInvalidObservedAmountsAreIgnored() {
        let result = SalaryPayslipComparisonEngineV2.compare(
            snapshot: snapshot(),
            observed: SalaryPayslipObservedValuesV2(
                socialGross: -1,
                netBeforeIncomeTax: .nan,
                netTaxable: nil,
                incomeTax: nil,
                netAfterIncomeTax: nil
            )
        )

        XCTAssertNil(result)
    }
}
