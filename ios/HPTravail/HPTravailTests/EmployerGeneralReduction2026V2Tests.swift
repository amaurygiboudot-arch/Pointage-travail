import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class EmployerGeneralReduction2026V2Tests: XCTestCase {
    private func rateContext(
        regime: EmployerGeneralReductionRateContext2026V2.HousingContributionRegime = .l813_5_2,
        rateSum: Double = 0.4021
    ) -> EmployerGeneralReductionRateContext2026V2.Snapshot {
        EmployerGeneralReductionRateContext2026V2.resolve(
            records: [
                .init(
                    id: "rate",
                    housingContributionRegime: regime,
                    eligibleEmployerRateSum: rateSum,
                    effectiveFrom: YearMonthV2(year: 2026, month: 1)!,
                    effectiveTo: nil,
                    source: "DSN / paramétrage paie 2026"
                )
            ],
            period: YearMonthV2(year: 2026, month: 9)!
        )
    }

    private func input(
        gross: Double = 2_000,
        context: EmployerGeneralReductionRateContext2026V2.Snapshot? = nil,
        type: EmployerGeneralReduction2026V2.ContractKind? = .fullTime,
        weeklyMinutes: Int? = 35 * 60,
        additionalMinutes: Double? = 0,
        fullMonth: Bool? = true,
        standardCase: Bool? = true
    ) -> EmployerGeneralReduction2026V2.Input {
        .init(
            year: 2026,
            reductionRemunerationMonthly: gross,
            rateContext: context ?? rateContext(),
            contractType: type,
            contractualWeeklyMinutes: weeklyMinutes,
            additionalPaidMinutes: additionalMinutes,
            fullMonthPresent: fullMonth,
            standardCommonLawCaseConfirmed: standardCase
        )
    }

    func testL813SecondRegimeMatches2026ReferenceExample() {
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(input())

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.coefficient ?? -1, 0.3178, accuracy: 0.000_000_1)
        XCTAssertEqual(result.amount ?? -1, 635.60, accuracy: 0.001)
        XCTAssertEqual(result.referenceMinimumMonthly ?? -1, 1_823.0333333333333, accuracy: 0.000_001)
    }

    func testL813FirstRegimeUsesLowerCoefficientWithoutWorkforceInference() {
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(context: rateContext(regime: .l813_5_1, rateSum: 0.3981))
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.coefficient ?? -1, 0.3147, accuracy: 0.000_000_1)
        XCTAssertEqual(result.amount ?? -1, 629.40, accuracy: 0.001)
    }

    func testCoefficientIsCappedAtConfirmedEligibleRates() {
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(gross: 1_500, context: rateContext(rateSum: 0.35))
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.coefficient ?? -1, 0.35, accuracy: 0.000_000_1)
        XCTAssertEqual(result.amount ?? -1, 525, accuracy: 0.001)
    }

    func testMissingRateContextNeverFallsBackToWorkforce() {
        let explicit = EmployerGeneralReduction2026V2.Input(
            year: 2026,
            reductionRemunerationMonthly: 2_000,
            rateContext: nil,
            contractType: .fullTime,
            contractualWeeklyMinutes: 35 * 60,
            additionalPaidMinutes: 0,
            fullMonthPresent: true,
            standardCommonLawCaseConfirmed: true
        )
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(explicit)

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.amount)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("régime de contribution logement") })
    }

    func testPartTimeReferenceMinimumAndComplementaryHours() {
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(
                gross: 1_600,
                type: .partTime,
                weeklyMinutes: 28 * 60,
                additionalMinutes: 120
            )
        )
        let expected = 12.02 * 35 * 52 / 12 * 0.8 + 12.02 * 2

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.referenceMinimumMonthly ?? -1, expected, accuracy: 0.000_001)
    }

    func testFractionalPaidMinutesArePreserved() {
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(additionalMinutes: 0.5)
        )
        let expected = 12.02 * 35 * 52 / 12 + 12.02 * 0.5 / 60

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.referenceMinimumMonthly ?? -1, expected, accuracy: 0.000_000_001)
    }

    func testInvalidAdditionalMinutesFailClosed() {
        for value in [Double.nan, .infinity, -1] {
            let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
                input(additionalMinutes: value)
            )

            XCTAssertFalse(result.reliable)
            XCTAssertNil(result.amount)
        }
    }

    func testIncompleteMonthAndNonStandardCaseFailClosed() {
        let incomplete = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(input(fullMonth: false))
        let nonStandard = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(input(standardCase: nil))

        XCTAssertFalse(incomplete.reliable)
        XCTAssertNil(incomplete.coefficient)
        XCTAssertFalse(nonStandard.reliable)
        XCTAssertNil(nonStandard.amount)
    }

    func testUnsupportedContractTypeFailsClosed() {
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(input(type: .unsupported))

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.amount)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("règle dédiée") })
    }
}
