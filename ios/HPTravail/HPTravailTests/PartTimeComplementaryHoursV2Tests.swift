import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class PartTimeComplementaryHoursV2Tests: XCTestCase {
    private func partTimeContract(rate: Double = 10) -> ContractV2 {
        ContractV2(
            id: "part-time",
            employerId: "company-a",
            type: .partTime,
            contractualWeeklyMinutes: 20 * 60,
            grossHourlyRate: rate,
            hireDateEpochDay: nil
        )
    }

    func testNoComplementaryHoursKeepsGrossReliable() throws {
        let result = try PayrollEngineV2.calculate(
            contract: partTimeContract(),
            weeks: [PayrollWeekV2(paidMinutes: 20 * 60)],
            rules: PayrollRulesV2(weeklyRegularMinutes: 20 * 60)
        )

        XCTAssertEqual(result.complementaryMinutes, 0)
        XCTAssertTrue(result.grossReliable)
        XCTAssertEqual(result.regularGross, 20.0 * 52.0 / 12.0 * 10.0, accuracy: 0.001)
    }

    func testComplementaryFallbackIsCalculatedButMakesGrossUnreliable() throws {
        let result = try PayrollEngineV2.calculate(
            contract: partTimeContract(),
            weeks: [PayrollWeekV2(paidMinutes: 22 * 60)],
            rules: PayrollRulesV2(weeklyRegularMinutes: 20 * 60)
        )

        XCTAssertEqual(result.complementaryMinutes, 120)
        XCTAssertEqual(result.overtimeGross, 22.0, accuracy: 0.001)
        XCTAssertFalse(result.grossReliable)
        XCTAssertTrue(result.traces.contains { $0.contains("barème supplétif") })
    }

    func testSecondComplementaryTierUsesTwentyFivePercent() throws {
        let result = try PayrollEngineV2.calculate(
            contract: partTimeContract(),
            weeks: [PayrollWeekV2(paidMinutes: 24 * 60)],
            rules: PayrollRulesV2(weeklyRegularMinutes: 20 * 60)
        )

        XCTAssertEqual(result.complementaryMinutes, 240)
        XCTAssertEqual(result.overtimeGross, 47.0, accuracy: 0.001)
        XCTAssertFalse(result.grossReliable)
        XCTAssertTrue(result.traces.contains { $0.contains("dépasse 1/10") })
    }

    func testPayrollReliabilityCannotBeRehabilitatedByConfirmedBenefits() throws {
        let payroll = try PayrollEngineV2.calculate(
            contract: partTimeContract(),
            weeks: [PayrollWeekV2(paidMinutes: 22 * 60)],
            rules: PayrollRulesV2(weeklyRegularMinutes: 20 * 60)
        )
        let benefits = CompanyBenefitInKindContractV2.Snapshot(
            applied: [],
            totalGross: 0,
            reliable: true,
            warnings: []
        )

        let reference = SalaryReferenceContractV2.buildFromPayroll(
            payroll: payroll,
            benefits: benefits,
            netBeforeIncomeTax: 800,
            netTaxable: 850
        )

        XCTAssertFalse(reference.grossReliable)
        XCTAssertNil(SalaryReferenceContractV2.socialGross(reference))
        XCTAssertNil(SalaryReferenceContractV2.beforeIncomeTax(reference))
    }
}
