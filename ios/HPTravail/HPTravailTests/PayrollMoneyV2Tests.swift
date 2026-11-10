import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class PayrollMoneyV2Tests: XCTestCase {
    func testHalfCentRoundsAwayFromZeroUsingDecimalInputs() {
        XCTAssertEqual(PayrollMoneyV2.cents(1.005), 101)
        XCTAssertEqual(PayrollMoneyV2.cents(-1.005), -101)
        XCTAssertEqual(PayrollMoneyV2.cents(2.675), 268)
        XCTAssertEqual(PayrollMoneyV2.cents(3000.005), 300001)
    }

    func testContributionMultipliesDecimalOperandsBeforeRounding() {
        XCTAssertEqual(PayrollMoneyV2.contribution(base: 100, rate: 0.01005), 101)
        XCTAssertEqual(PayrollMoneyV2.contribution(base: 100.5, rate: 0.01), 101)
        XCTAssertEqual(PayrollMoneyV2.contribution(base: 1, rate: 0.005), 1)
    }

    func testPasRoundsExactHalfCentAccordingToIndividualRule() {
        XCTAssertEqual(PayrollMoneyV2.tax(baseCents: 82, rate: 0.25), 21)
        XCTAssertEqual(PayrollMoneyV2.tax(baseCents: 120565, rate: 0.1), 12057)
        XCTAssertEqual(PayrollMoneyV2.tax(baseCents: 241145, rate: 0.032), 7717)
    }

    func testSignedAssemblyDoesNotClampFiscalIntermediate() {
        XCTAssertEqual(PayrollMoneyV2.sum([0, -26957, 100000, 2884, 800, 400]), 77127)
        XCTAssertEqual(PayrollMoneyV2.amount(-26957), -269.57)
        XCTAssertEqual(PayrollMoneyV2.sum([]), 0)
    }

    func testNonFiniteAmountsAndRatesAreRejected() {
        for value in [Double.nan, .infinity, -.infinity] {
            XCTAssertNil(PayrollMoneyV2.cents(value))
            XCTAssertNil(PayrollMoneyV2.contribution(base: value, rate: 0.1))
            XCTAssertNil(PayrollMoneyV2.contribution(base: 100, rate: value))
            XCTAssertNil(PayrollMoneyV2.tax(baseCents: 100, rate: value))
        }
    }

    func testInvalidTaxRateAndContributionInputsAreRejected() {
        XCTAssertNil(PayrollMoneyV2.tax(baseCents: -1, rate: 0.1))
        XCTAssertNil(PayrollMoneyV2.tax(baseCents: 100, rate: -0.1))
        XCTAssertNil(PayrollMoneyV2.tax(baseCents: 100, rate: 1.01))
        XCTAssertNil(PayrollMoneyV2.contribution(base: -100, rate: 0.1))
        XCTAssertNil(PayrollMoneyV2.contribution(base: 100, rate: -0.1))
    }

    func testTechnicalBoundsAndLossyDoubleRoundTripFailClosed() {
        XCTAssertNil(PayrollMoneyV2.cents(Double.greatestFiniteMagnitude))
        XCTAssertNil(PayrollMoneyV2.sum([PayrollMoneyV2.maximumCents, 1]))
        XCTAssertNil(PayrollMoneyV2.sum([Int64.max, -Int64.max]))
        XCTAssertNil(PayrollMoneyV2.amount(Int64.min))
        XCTAssertNil(PayrollMoneyV2.amount(PayrollMoneyV2.maximumCents))
        XCTAssertEqual(PayrollMoneyV2.amount(PayrollMoneyV2.maximumCents - 1), 90071992547409.9)
        XCTAssertNil(PayrollMoneyV2.contribution(base: Double.greatestFiniteMagnitude, rate: 100))
    }

    func testSubnormalAmountsAndTaxRatesRoundToKnownZero() {
        XCTAssertEqual(PayrollMoneyV2.cents(Double.leastNonzeroMagnitude), 0)
        XCTAssertEqual(PayrollMoneyV2.cents(-Double.leastNonzeroMagnitude), 0)
        XCTAssertEqual(PayrollMoneyV2.cents(0.004999999999999999), 0)
        XCTAssertEqual(PayrollMoneyV2.cents(0.005), 1)
        XCTAssertEqual(PayrollMoneyV2.tax(baseCents: 241145, rate: Double.leastNonzeroMagnitude), 0)
        XCTAssertEqual(PayrollMoneyV2.tax(baseCents: PayrollMoneyV2.maximumCents - 1, rate: 1e-300), 0)
        XCTAssertEqual(PayrollMoneyV2.tax(baseCents: 100, rate: 0.005), 1)
    }

    func testZeroAndFullTaxRatesRemainExact() {
        XCTAssertEqual(PayrollMoneyV2.tax(baseCents: 77127, rate: 0), 0)
        XCTAssertEqual(PayrollMoneyV2.tax(baseCents: 77127, rate: 1), 77127)
        XCTAssertEqual(PayrollMoneyV2.cents(0), 0)
    }
}
