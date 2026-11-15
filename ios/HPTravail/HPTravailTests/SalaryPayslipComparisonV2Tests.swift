import Foundation
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
        var value = SalaryWorkspaceSnapshotV2(
            period: YearMonthV2(year: 2026, month: 9)!,
            sourceReady: sourceReady,
            socialGross: gross,
            netBeforeIncomeTax: netBeforeTax,
            netTaxable: taxable,
            incomeTax: tax,
            netAfterIncomeTax: netAfterTax,
            warnings: []
        )
        value.settlementBaseReliable = true
        value.monthlySettlement = settlement()
        return value
    }

    private func settlement(transport: Decimal? = 0, garnishment: Decimal? = 0,
                            addition: Decimal? = 0, deduction: Decimal? = 0, complete: Bool = true,
                            company: String = "synthetic-company", month: Int = 9,
                            source: String = "Synthetic monthly statement", reliable: Bool = true,
                            observedPaid: Decimal? = 1) -> SalaryMonthlyPayrollSettlementV2.Result {
        .init(record: .init(version: 1, companyId: company, period: YearMonthV2(year: 2026, month: month)!,
            transportReimbursement: transport, garnishment: garnishment, otherAddition: addition,
            otherDeduction: deduction, netSocialObserved: 2, netPaidObserved: observedPaid, source: source,
            confirmedAt: 1, adjustmentsComplete: complete), reliable: reliable, warnings: [])
    }

    private func compare(snapshot: SalaryWorkspaceSnapshotV2, observed: SalaryPayslipObservedValuesV2,
                         company: String? = "synthetic-company", tolerance: Double = 0.02) -> SalaryPayslipComparisonResultV2? {
        SalaryPayslipComparisonEngineV2.compare(snapshot: snapshot, observed: observed, companyId: company, tolerance: tolerance)
    }

    func testExactValuesAreConforming() {
        let result = compare(
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
        let result = compare(
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
        let result = compare(
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

    func testMissingTaxReferenceDoesNotPresumeBulletinCashNet() {
        let result = compare(
            snapshot: snapshot(taxable: nil, tax: nil, netAfterTax: nil),
            observed: SalaryPayslipObservedValuesV2(
                socialGross: nil,
                netBeforeIncomeTax: 2_000.0,
                netTaxable: 999.0,
                incomeTax: 999.0,
                netAfterIncomeTax: 999.0
            )
        )

        XCTAssertNil(result)
    }

    func testNoCanonicalSourceBlocksComparison() {
        let result = compare(
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
        let result = compare(
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
        let result = compare(
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
    private func observed(_ gross: Double?, net: Double? = nil) -> SalaryPayslipObservedValuesV2 {
        .init(socialGross: gross, netBeforeIncomeTax: net, netTaxable: nil,
              incomeTax: nil, netAfterIncomeTax: nil)
    }

    func testInvalidProvidedObservationCannotHideBehindAValidField() {
        for invalid in [-1.0, -.leastNonzeroMagnitude, Double.nan, .infinity, 1e17, 1e308] {
            XCTAssertNil(compare(
                snapshot: snapshot(gross: nil), observed: observed(invalid, net: 2000)), "\(invalid)")
        }
    }

    func testInvalidCanonicalFieldCannotBeSilentlyOmitted() {
        for invalid in [-1.0, Double.nan, .infinity, 1e17, 1e308] {
            XCTAssertNil(SalaryPayslipComparisonEngineV2.expectedValues(from: snapshot(gross: invalid)))
            XCTAssertNil(compare(
                snapshot: snapshot(gross: invalid), observed: observed(nil, net: 2000)))
        }
    }

    func testInvalidToleranceRefusesComparisonWithoutTrapping() {
        for invalid in [-1.0, Double.nan, .infinity, 1e17, 1e308] {
            XCTAssertNil(compare(
                snapshot: snapshot(), observed: observed(2500), tolerance: invalid))
        }
    }

    func testDecimalHalfCentUsesCanonicalPayrollRounding() {
        XCTAssertEqual(SalaryPayslipAmountV2.cents(1.005), 101)
        XCTAssertEqual(SalaryPayslipAmountV2.cents(2.675), 268)
        XCTAssertEqual(SalaryPayslipAmountV2.cents(0.005), 1)
        XCTAssertTrue(compare(
            snapshot: snapshot(gross: 1.005), observed: observed(1.01), tolerance: 0)?.conforming == true)
        XCTAssertFalse(compare(
            snapshot: snapshot(gross: 1.005), observed: observed(1.00), tolerance: 0)?.conforming == true)
    }

    func testRepresentableBoundaryAndLargeDifferenceAreSafe() {
        let boundary = 90_071_992_547_409.90
        XCTAssertEqual(SalaryPayslipAmountV2.cents(boundary), 9_007_199_254_740_990)
        XCTAssertNil(SalaryPayslipAmountV2.cents(boundary.nextUp))
        XCTAssertFalse(compare(
            snapshot: snapshot(gross: boundary), observed: observed(0), tolerance: 0)?.conforming == true)
        XCTAssertTrue(compare(
            snapshot: snapshot(gross: 0), observed: observed(0), tolerance: 0)?.conforming == true)
    }

    func testManualInputPreservesSourceCentsAndEmptyIsUnknown() {
        XCTAssertTrue(SalaryPayslipAmountV2.parse(" ").valid)
        XCTAssertNil(SalaryPayslipAmountV2.parse(" ").value)
        for text in ["0", "2500,02", "1.005", "2.675", "90071992547409.90"] {
            XCTAssertTrue(SalaryPayslipAmountV2.parse(text).valid, text)
        }
        for text in ["90071992547409.91", "90071992547409.92", "1e17", "1e308", "nan", "inf", "-0.001", "12oops", "1e-200", "1e-400", "-1e-400", "1e-2147483647"] {
            XCTAssertFalse(SalaryPayslipAmountV2.parse(text).valid, text)
        }
    }

    func testCommonMonetaryTextFixtures() {
        // Shared R14 fixture table: COMMON_MONETARY_TEXT_FIXTURES.csv.
        let cases: [(id: String, text: String, state: String, cents: Int64?)] = [
            ("zero", "0", "accept", 0),
            ("zero_negative_sign", "-0", "accept", 0),
            ("normal_cent", "2500.02", "accept", 250002),
            ("normal_comma", "2500,02", "accept", 250002),
            ("decimal_half_cent", "1.005", "accept", 101),
            ("decimal_half_cent_2", "2.675", "accept", 268),
            ("positive_sub_cent", "0.001", "accept", 0),
            ("negative_sub_cent", "-0.001", "reject", nil),
            ("negative", "-1", "reject", nil),
            ("boundary_representable", "90071992547409.90", "accept", 9007199254740990),
            ("boundary_unrepresentable_cent", "90071992547409.91", "reject", nil),
            ("boundary_over_policy", "90071992547409.92", "reject", nil),
            ("huge_finite", "100000000000000000", "reject", nil),
            ("huge_exponent", "1e308", "reject", nil),
            ("nonfinite_nan", "NaN", "reject", nil),
            ("nonfinite_inf", "inf", "reject", nil),
            ("empty", "", "missing", nil),
        ]
        for item in cases {
            let parsed = SalaryPayslipAmountV2.parse(item.text)
            switch item.state {
            case "missing":
                XCTAssertTrue(parsed.valid, item.id)
                XCTAssertNil(parsed.value, item.id)
            case "reject":
                XCTAssertFalse(parsed.valid, item.id)
                XCTAssertNil(parsed.value, item.id)
            default:
                XCTAssertTrue(parsed.valid, item.id)
                XCTAssertEqual(parsed.value.flatMap(SalaryPayslipAmountV2.cents), item.cents, item.id)
            }
        }
    }

    func testCommonComparisonFixtures() {
        // Shared R14 fixture table: COMMON_COMPARISON_FIXTURES.csv.
        let cases: [(id: String, expected: Double, observed: Double, tolerance: Double, result: String)] = [
            ("equal", 2500.00, 2500.00, 0.02, "conforming"),
            ("plus_two", 2500.00, 2500.02, 0.02, "conforming"),
            ("minus_two", 2500.00, 2499.98, 0.02, "conforming"),
            ("plus_three", 2500.00, 2500.03, 0.02, "discrepancy"),
            ("minus_three", 2500.00, 2499.97, 0.02, "discrepancy"),
            ("half_cent_zero_tolerance", 1.00, 1.005, 0, "discrepancy"),
            ("invalid_observed", 2500, 1e17, 0.02, "invalid"),
            ("invalid_expected", 1e17, 2500, 0.02, "invalid"),
            ("invalid_tolerance", 2500, 2500, 1e17, "invalid"),
            ("negative_subcent_tolerance", 2500, 2500, -0.001, "invalid"),
        ]
        for item in cases {
            let result = compare(
                snapshot: snapshot(gross: item.expected), observed: observed(item.observed), tolerance: item.tolerance)
            if item.result == "invalid" {
                XCTAssertNil(result, item.id)
            } else {
                XCTAssertNotNil(result, item.id)
                XCTAssertEqual(result?.conforming, item.result == "conforming", item.id)
                XCTAssertEqual(result?.comparedFields, [.socialGross], item.id)
            }
        }
    }

    func testCompleteSettlementReconcilesBeforeAndAfterTaxWithoutChangingGrossTaxableOrPas() {
        var reference = snapshot()
        reference.monthlySettlement = settlement(transport: 50, garnishment: 20, addition: 15, deduction: 5)
        let observed = SalaryPayslipObservedValuesV2(socialGross: 2500, netBeforeIncomeTax: 2040,
            netTaxable: 2050, incomeTax: 100, netAfterIncomeTax: 1940)
        let result = compare(snapshot: reference, observed: observed)
        XCTAssertTrue(result?.conforming == true)
        XCTAssertEqual(result?.comparedFields.count, 5)
        reference.monthlySettlement = settlement(transport: 50, garnishment: 20, addition: 15, deduction: 5, observedPaid: 999)
        XCTAssertEqual(compare(snapshot: reference, observed: observed), result)
        XCTAssertFalse(compare(snapshot: reference, observed: .init(socialGross: nil, netBeforeIncomeTax: 2000,
            netTaxable: nil, incomeTax: nil, netAfterIncomeTax: 1900))?.conforming == true)
    }

    func testUnknownInvalidIncompleteAndWrongScopeCannotDeclareNetConformity() {
        let cases: [SalaryMonthlyPayrollSettlementV2.Result?] = [nil,settlement(complete: false),
            settlement(addition: nil),settlement(company: "other"),settlement(month: 10),settlement(source: ""),
            settlement(reliable: false),settlement(garnishment: -1),settlement(garnishment: .nan),
            settlement(garnishment: 3000),settlement(transport: Decimal(string: "100000000000000000")!),
            settlement(transport: 60_000_000_000_000, addition: 60_000_000_000_000)]
        let observed = SalaryPayslipObservedValuesV2(socialGross: nil, netBeforeIncomeTax: 2000,
            netTaxable: nil, incomeTax: nil, netAfterIncomeTax: 1900)
        for source in cases {
            var reference = snapshot()
            reference.monthlySettlement = source
            XCTAssertNil(compare(snapshot: reference, observed: observed))
            let values = SalaryPayslipComparisonEngineV2.expectedValues(from: reference, companyId: "synthetic-company")
            XCTAssertNil(values?[.netBeforeIncomeTax])
            XCTAssertNil(values?[.netAfterIncomeTax])
            XCTAssertEqual(values?[.socialGross],2500)
            XCTAssertEqual(values?[.netTaxable],2050)
            XCTAssertEqual(values?[.incomeTax],100)
        }
        XCTAssertNil(compare(snapshot: snapshot(), observed: observed, company: nil))
        XCTAssertNil(compare(snapshot: snapshot(), observed: observed, company: "other"))
        var clamped = snapshot()
        clamped.settlementBaseReliable = false
        XCTAssertNil(compare(snapshot: clamped, observed: observed))
        XCTAssertNil(compare(snapshot: snapshot(netAfterTax: 1899), observed: observed))
    }

}
