import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryNetPresentationV2Tests: XCTestCase {
    private func payroll(grossReliable: Bool = true) -> PayrollResultV2 {
        PayrollResultV2(
            regularGross: 2_500,
            overtimeGross: 0,
            premiumsGross: 0,
            fixedPremiumsGross: 0,
            baskets: 0,
            grossEstimate: 2_500,
            deductions: 100,
            netBeforeUnknownContributions: 2_400,
            complementaryMinutes: 0,
            grossReliable: grossReliable,
            traces: []
        )
    }

    func testCurrentSwiftPayrollNeverPublishesKnownSubtotalAsFinalNet() {
        let presentation = SalaryNetPresentationV2.fromPayroll(payroll())

        XCTAssertEqual(presentation.state, .incomplete)
        XCTAssertEqual(presentation.primaryLabel, "Net incomplet")
        XCTAssertNil(presentation.primaryAmount)
        XCTAssertNil(presentation.secondaryLabel)
        XCTAssertNil(presentation.secondaryAmount)
    }

    func testUnreliableGrossAlwaysBlocksNetPresentation() {
        let presentation = SalaryNetPresentationV2.fromPayroll(payroll(grossReliable: false))

        XCTAssertEqual(presentation.state, .unreliableGross)
        XCTAssertEqual(presentation.primaryLabel, "Net indisponible")
        XCTAssertNil(presentation.primaryAmount)
        XCTAssertNil(presentation.secondaryAmount)
    }

    func testConfirmedNetCanBePublishedOnlyWhenCompletenessIsExplicit() {
        let presentation = SalaryNetPresentationV2.make(
            grossReliable: true,
            netComplete: true,
            netBeforeIncomeTax: 2_000,
            netAfterIncomeTax: 1_900
        )

        XCTAssertEqual(presentation.state, .available)
        XCTAssertEqual(presentation.primaryLabel, "Net avant impôt")
        XCTAssertEqual(presentation.primaryAmount, 2_000, accuracy: 0.001)
        XCTAssertEqual(presentation.secondaryLabel, "Net après impôt")
        XCTAssertEqual(presentation.secondaryAmount, 1_900, accuracy: 0.001)
    }

    func testIncompleteFlagWinsOverAccidentallyProvidedAmounts() {
        let presentation = SalaryNetPresentationV2.make(
            grossReliable: true,
            netComplete: false,
            netBeforeIncomeTax: 2_000,
            netAfterIncomeTax: 1_900
        )

        XCTAssertEqual(presentation.state, .incomplete)
        XCTAssertNil(presentation.primaryAmount)
        XCTAssertNil(presentation.secondaryAmount)
    }
}
