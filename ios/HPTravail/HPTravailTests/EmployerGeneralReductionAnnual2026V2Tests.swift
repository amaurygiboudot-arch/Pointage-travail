import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class EmployerGeneralReductionAnnual2026V2Tests: XCTestCase {
    private func rateContext(
        regime: EmployerGeneralReductionRateContext2026V2.HousingContributionRegime = .l813_5_2,
        rateSum: Double = 0.4021
    ) -> EmployerGeneralReductionRateContext2026V2.Snapshot {
        EmployerGeneralReductionRateContext2026V2.resolve(
            records: [
                .init(
                    id: "annual-rate",
                    housingContributionRegime: regime,
                    eligibleEmployerRateSum: rateSum,
                    effectiveFrom: YearMonthV2(year: 2026, month: 1)!,
                    effectiveTo: nil,
                    source: "Paramétrage paie annuel 2026"
                )
            ],
            period: YearMonthV2(year: 2026, month: 1)!
        )
    }

    private func input(
        remuneration: Double = 24_000,
        context: EmployerGeneralReductionRateContext2026V2.Snapshot? = nil,
        type: EmployerGeneralReduction2026V2.ContractKind? = .fullTime,
        weeklyMinutes: Int? = 35 * 60,
        additionalMinutes: Double? = 0,
        fullYear: Bool? = true,
        standardCase: Bool? = true,
        homogeneous: Bool? = true
    ) -> EmployerGeneralReductionAnnual2026V2.Input {
        .init(
            year: 2026,
            annualReductionRemuneration: remuneration,
            rateContext: context ?? rateContext(),
            contractType: type,
            contractualWeeklyMinutes: weeklyMinutes,
            additionalPaidMinutesAnnual: additionalMinutes,
            fullCalendarYearPresent: fullYear,
            standardCommonLawCaseConfirmed: standardCase,
            homogeneousAnnualParametersConfirmed: homogeneous
        )
    }

    func testAnnualStandardCaseUses2026CoefficientWithoutMonthlyAmountRounding() {
        let result = EmployerGeneralReductionAnnual2026V2.calculate(input())

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.coefficient ?? -1, 0.3178, accuracy: 0.000_000_1)
        XCTAssertEqual(result.amount ?? -1, 7_627.20, accuracy: 0.001)
        XCTAssertEqual(result.referenceMinimumAnnual ?? -1, 21_876.40, accuracy: 0.000_001)
        XCTAssertEqual(result.thresholdAnnual ?? -1, 65_629.20, accuracy: 0.000_001)
    }

    func testAnnualCoefficientFollowsConfirmedHousingRegime() {
        let result = EmployerGeneralReductionAnnual2026V2.calculate(
            input(context: rateContext(regime: .l813_5_1, rateSum: 0.3981))
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.coefficient ?? -1, 0.3147, accuracy: 0.000_000_1)
    }

    func testLowerEligibleRateSumCapsAnnualCoefficient() {
        let result = EmployerGeneralReductionAnnual2026V2.calculate(
            input(remuneration: 18_000, context: rateContext(rateSum: 0.35))
        )

        XCTAssertTrue(result.reliable)
        XCTAssertLessThanOrEqual(result.coefficient ?? 1, 0.35)
    }

    func testMissingAnnualRateContextFailsClosed() {
        let explicit = EmployerGeneralReductionAnnual2026V2.Input(
            year: 2026,
            annualReductionRemuneration: 24_000,
            rateContext: nil,
            contractType: .fullTime,
            contractualWeeklyMinutes: 35 * 60,
            additionalPaidMinutesAnnual: 0,
            fullCalendarYearPresent: true,
            standardCommonLawCaseConfirmed: true,
            homogeneousAnnualParametersConfirmed: true
        )
        let result = EmployerGeneralReductionAnnual2026V2.calculate(explicit)

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.amount)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("régime de contribution logement") })
    }

    func testPartTimeAnnualReferenceMinimumIsProrated() {
        let result = EmployerGeneralReductionAnnual2026V2.calculate(
            input(remuneration: 14_000, type: .partTime, weeklyMinutes: 17 * 60 + 30)
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.referenceMinimumAnnual ?? -1, 10_938.20, accuracy: 0.000_001)
    }

    func testFractionalAdditionalMinuteIsPreservedAnnually() {
        let base = EmployerGeneralReductionAnnual2026V2.calculate(input(additionalMinutes: 0))
        let half = EmployerGeneralReductionAnnual2026V2.calculate(input(additionalMinutes: 0.5))

        XCTAssertTrue(base.reliable)
        XCTAssertTrue(half.reliable)
        XCTAssertEqual(
            (half.referenceMinimumAnnual ?? 0) - (base.referenceMinimumAnnual ?? 0),
            12.02 * 0.5 / 60,
            accuracy: 0.000_001
        )
    }

    func testIncompleteYearAndChangingParametersFailClosed() {
        let incomplete = EmployerGeneralReductionAnnual2026V2.calculate(input(fullYear: false))
        let changing = EmployerGeneralReductionAnnual2026V2.calculate(input(homogeneous: false))

        XCTAssertFalse(incomplete.reliable)
        XCTAssertNil(incomplete.amount)
        XCTAssertFalse(changing.reliable)
        XCTAssertNil(changing.amount)
    }

    func testUnsupportedContractTypeFailsClosed() {
        let result = EmployerGeneralReductionAnnual2026V2.calculate(input(type: .unsupported))

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.coefficient)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("type de contrat") })
    }
}
