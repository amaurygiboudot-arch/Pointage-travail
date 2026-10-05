import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryConventionSeniorityPremiumV2Tests: XCTestCase {
    private let classification = ConventionClassificationV2()

    private func date(_ year: Int, _ month: Int, _ day: Int) -> PayrollCivilDateV2 {
        PayrollCivilDateV2(year: year, month: month, day: day)!
    }

    private func rule(
        steps: [SalaryConventionSeniorityPremiumV2.Step] = [
            .init(years: 3, rate: 0.024, fixedMonthlyAmount: nil)
        ]
    ) -> SalaryConventionSeniorityPremiumV2.Rule {
        .init(
            idcc: "0292",
            ruleId: "plasturgie-seniority",
            effectiveFrom: date(2025, 1, 1),
            effectiveTo: nil,
            classification: classification,
            basis: .actualMonthlyBase,
            steps: steps,
            includeConfirmedMonthlySupplement: false,
            source: "KALI-test",
            extensionStatus: .extended,
            extensionEffectiveFrom: date(2025, 1, 1)
        )
    }

    func testMissingConfirmedSeniorityDateBlocksAmount() {
        let result = SalaryConventionSeniorityPremiumV2.calculate(
            rules: [rule()],
            idcc: "292",
            classification: classification,
            referenceDate: date(2026, 9, 30),
            confirmedSeniorityDate: nil,
            actualMonthlyBaseGross: 2_000,
            conventionalMinimumMonthlyGross: nil
        )

        XCTAssertTrue(result.applicable)
        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.monthlyAmount)
    }

    func testConfirmedStepUsesExactRateOnReliableBase() {
        let result = SalaryConventionSeniorityPremiumV2.calculate(
            rules: [rule()],
            idcc: "0292",
            classification: classification,
            referenceDate: date(2026, 9, 30),
            confirmedSeniorityDate: date(2020, 6, 15),
            actualMonthlyBaseGross: 2_000,
            conventionalMinimumMonthlyGross: nil
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.stepYears, 3)
        XCTAssertEqual(result.rate ?? -1, 0.024, accuracy: 0.000001)
        XCTAssertEqual(result.monthlyAmount ?? -1, 48, accuracy: 0.001)
    }

    func testStepChangeInsideMonthBlocksAutomaticAmount() {
        let result = SalaryConventionSeniorityPremiumV2.calculate(
            rules: [rule(steps: [
                .init(years: 3, rate: 0.024, fixedMonthlyAmount: nil),
                .init(years: 6, rate: 0.048, fixedMonthlyAmount: nil)
            ])],
            idcc: "0292",
            classification: classification,
            referenceDate: date(2026, 9, 30),
            confirmedSeniorityDate: date(2020, 9, 15),
            actualMonthlyBaseGross: 2_000,
            conventionalMinimumMonthlyGross: nil
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.monthlyAmount)
        XCTAssertTrue(result.warnings.contains { $0.contains("palier change pendant le mois") })
    }
}