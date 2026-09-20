import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class EmployerGeneralReduction2026V2Tests: XCTestCase {
    private func input(
        gross: Double = 2_000,
        fnalTreatment: EmployerWorkforceContributionsV2.FnalTreatment? = .uncapped0Point5Percent,
        type: ContractTypeV2? = .fullTime,
        weeklyMinutes: Int? = 35 * 60,
        additionalMinutes: Double? = 0,
        fullMonth: Bool? = true,
        standardCase: Bool? = true
    ) -> EmployerGeneralReduction2026V2.Input {
        .init(
            year: 2026,
            reductionRemunerationMonthly: gross,
            fnalTreatment: fnalTreatment,
            contractType: type,
            contractualWeeklyMinutes: weeklyMinutes,
            additionalPaidMinutes: additionalMinutes,
            fullMonthPresent: fullMonth,
            standardCommonLawCaseConfirmed: standardCase
        )
    }

    func testUncappedFnalRegimeMatchesAndroidReference() {
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(input())

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.coefficient ?? -1, 0.3178, accuracy: 0.0000001)
        XCTAssertEqual(result.amount ?? -1, 635.60, accuracy: 0.001)
        XCTAssertEqual(result.referenceMinimumMonthly ?? -1, 1823.0333333333333, accuracy: 0.000001)
    }

    func testCappedFnalUsesLowerDeltaWithoutWorkforceInference() {
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(fnalTreatment: .capped0Point1Percent)
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.coefficient ?? -1, 0.3147, accuracy: 0.0000001)
        XCTAssertEqual(result.amount ?? -1, 629.40, accuracy: 0.001)
    }

    func testCappedFnalCoefficientIsLimitedToLegalMaximum() {
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(gross: 1_500, fnalTreatment: .capped0Point1Percent)
        )

        XCTAssertEqual(result.coefficient ?? -1, 0.3981, accuracy: 0.0000001)
        XCTAssertEqual(result.amount ?? -1, 597.15, accuracy: 0.001)
    }

    func testMissingFnalTreatmentFailsClosed() {
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(fnalTreatment: nil)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.amount)
        XCTAssertNil(result.coefficient)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("FNAL/logement") })
    }

    func testReductionBecomesZeroAtThreeTimesReferenceMinimum() {
        let monthlySmic = 12.02 * 35.0 * 52.0 / 12.0
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(gross: 3.0 * monthlySmic)
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.coefficient ?? -1, 0, accuracy: 0)
        XCTAssertEqual(result.amount ?? -1, 0, accuracy: 0)
    }

    func testPartTimeReferenceIsProratedAndComplementaryHoursAreAdded() {
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(
                gross: 1_600,
                type: .partTime,
                weeklyMinutes: 28 * 60,
                additionalMinutes: 120
            )
        )
        let expected = (12.02 * 35.0 * 52.0 / 12.0 * 0.8) + (12.02 * 2.0)

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.referenceMinimumMonthly ?? -1, expected, accuracy: 0.000001)
    }

    func testFractionalPaidMinutesArePreserved() {
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(additionalMinutes: 0.5)
        )
        let expected = (12.02 * 35.0 * 52.0 / 12.0) + (12.02 * 0.5 / 60.0)

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.referenceMinimumMonthly ?? -1, expected, accuracy: 0.000000001)
    }

    func testAdditionalPaidMinutesMustBeExplicitlyKnown() {
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(additionalMinutes: nil)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.amount)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("heures supplémentaires/complémentaires") })
    }

    func testInvalidAdditionalPaidMinutesAreRejected() {
        for minutes in [Double.infinity, -1.0, Double.nan] {
            let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
                input(additionalMinutes: minutes)
            )

            XCTAssertFalse(result.reliable)
            XCTAssertNil(result.amount)
        }
    }

    func testIncompleteMonthFailsClosed() {
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(fullMonth: false)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.coefficient)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("mois incomplet") })
    }

    func testUnknownCommonLawCaseNeverGetsDefaultCoefficient() {
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(standardCase: nil)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.amount)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("droit commun non confirmé") })
    }

    func testUnsupportedContractTypeRemainsBlocked() {
        let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            input(type: .forfaitDays)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.amount)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("règle dédiée") })
    }

    func testInvalidGrossNeverProducesReduction() {
        for gross in [-1.0, Double.nan, Double.infinity, -Double.infinity] {
            let result = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(input(gross: gross))

            XCTAssertFalse(result.reliable)
            XCTAssertNil(result.amount)
            XCTAssertNil(result.coefficient)
        }
    }
}
