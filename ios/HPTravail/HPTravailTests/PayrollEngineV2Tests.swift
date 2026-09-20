import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class PayrollEngineV2Tests: XCTestCase {
    private func hourlyContract(rate: Double = 10) -> ContractV2 {
        ContractV2(
            id: "contract-a",
            employerId: "company-a",
            type: .other,
            contractualWeeklyMinutes: 35 * 60,
            grossHourlyRate: rate,
            hireDateEpochDay: nil
        )
    }

    func testNoOvertimeRuleNeverInventsOvertimePremium() throws {
        let result = try PayrollEngineV2.calculate(
            contract: hourlyContract(),
            weeks: [PayrollWeekV2(paidMinutes: 40 * 60)],
            rules: PayrollRulesV2()
        )

        XCTAssertEqual(result.regularGross, 350, accuracy: 0.001)
        XCTAssertEqual(result.overtimeGross, 0, accuracy: 0.001)
        XCTAssertEqual(result.grossEstimate, 350, accuracy: 0.001)
        XCTAssertTrue(result.traces.contains { $0.contains("règle non fournie") })
    }

    func testConfirmedOvertimeTierIsApplied() throws {
        let result = try PayrollEngineV2.calculate(
            contract: hourlyContract(),
            weeks: [PayrollWeekV2(paidMinutes: 40 * 60)],
            rules: PayrollRulesV2(
                overtimeTiers: [
                    OvertimeTierV2(fromMinutes: 35 * 60, toMinutes: nil, multiplier: 1.25)
                ]
            )
        )

        XCTAssertEqual(result.regularGross, 350, accuracy: 0.001)
        XCTAssertEqual(result.overtimeGross, 62.5, accuracy: 0.001)
        XCTAssertEqual(result.grossEstimate, 412.5, accuracy: 0.001)
    }

    func testNightAndSundayPremiumsUseOnlyProvidedMultipliers() throws {
        let result = try PayrollEngineV2.calculate(
            contract: hourlyContract(),
            weeks: [
                PayrollWeekV2(
                    paidMinutes: 35 * 60,
                    nightMinutes: 60,
                    sundayMinutes: 120
                )
            ],
            rules: PayrollRulesV2(
                nightMultiplier: 1.25,
                sundayMultiplier: 2.0
            )
        )

        XCTAssertEqual(result.premiumsGross, 22.5, accuracy: 0.001)
        XCTAssertEqual(result.grossEstimate, 372.5, accuracy: 0.001)
    }

    func testOverlappingCategoriesRemainAllowedWhenEachFitsInsidePaidTime() throws {
        let result = try PayrollEngineV2.calculate(
            contract: hourlyContract(),
            weeks: [
                PayrollWeekV2(
                    paidMinutes: 60,
                    nightMinutes: 60,
                    sundayMinutes: 60
                )
            ],
            rules: PayrollRulesV2(
                nightMultiplier: 1.25,
                sundayMultiplier: 2.0
            )
        )

        XCTAssertEqual(result.premiumsGross, 12.5, accuracy: 0.001)
    }

    func testNegativePaidMinutesAreRejectedInsteadOfClampedToZero() {
        XCTAssertThrowsError(
            try PayrollEngineV2.calculate(
                contract: hourlyContract(),
                weeks: [PayrollWeekV2(paidMinutes: -1)],
                rules: PayrollRulesV2()
            )
        ) { error in
            XCTAssertEqual(error as? PayrollEngineErrorV2, .invalidPaidMinutes)
        }
    }

    func testNegativePremiumCategoryMinutesAreRejected() {
        XCTAssertThrowsError(
            try PayrollEngineV2.calculate(
                contract: hourlyContract(),
                weeks: [PayrollWeekV2(paidMinutes: 60, nightMinutes: -1)],
                rules: PayrollRulesV2(nightMultiplier: 1.25)
            )
        ) { error in
            XCTAssertEqual(error as? PayrollEngineErrorV2, .invalidPaidMinutes)
        }
    }

    func testPremiumCategoryCannotExceedPaidMinutes() {
        XCTAssertThrowsError(
            try PayrollEngineV2.calculate(
                contract: hourlyContract(),
                weeks: [PayrollWeekV2(paidMinutes: 60, publicHolidayMinutes: 61)],
                rules: PayrollRulesV2(publicHolidayMultiplier: 1.5)
            )
        ) { error in
            XCTAssertEqual(error as? PayrollEngineErrorV2, .invalidPaidMinutes)
        }
    }

    func testBasketsStayOutsideGrossEstimate() throws {
        let result = try PayrollEngineV2.calculate(
            contract: hourlyContract(),
            weeks: [PayrollWeekV2(paidMinutes: 35 * 60)],
            rules: PayrollRulesV2(),
            baskets: [BasketV2(id: "meal", label: "Panier", amount: 6)]
        )

        XCTAssertEqual(result.grossEstimate, 350, accuracy: 0.001)
        XCTAssertEqual(result.baskets, 6, accuracy: 0.001)
        XCTAssertTrue(result.traces.contains { $0.contains("Paniers suivis séparément") })
    }

    func testKnownDeductionsOnlyAffectLegacyInternalNetField() throws {
        let result = try PayrollEngineV2.calculate(
            contract: hourlyContract(),
            weeks: [PayrollWeekV2(paidMinutes: 35 * 60)],
            rules: PayrollRulesV2(),
            deductions: [DeductionV2(id: "known", label: "Retenue connue", amount: 50, recurring: true)]
        )

        XCTAssertEqual(result.grossEstimate, 350, accuracy: 0.001)
        XCTAssertEqual(result.deductions, 50, accuracy: 0.001)
        XCTAssertEqual(result.netBeforeUnknownContributions, 300, accuracy: 0.001)
    }

    func testForfaitHoursUsesAgreedMonthlyGrossWithoutHourlyReconstruction() throws {
        let contract = ContractV2(
            id: "forfait-hours",
            employerId: "company-a",
            type: .forfaitHours,
            contractualWeeklyMinutes: nil,
            grossHourlyRate: nil,
            hireDateEpochDay: nil,
            forfaitHoursPeriod: .year,
            forfaitHours: 1_607,
            monthlyGrossSalary: 2_500
        )

        let result = try PayrollEngineV2.calculate(
            contract: contract,
            weeks: [PayrollWeekV2(paidMinutes: 60 * 60)],
            rules: PayrollRulesV2(),
            premiums: [PremiumV2(id: "fixed", label: "Prime", amount: 100, periodicity: .monthly)],
            baskets: [BasketV2(id: "meal", label: "Panier", amount: 20)],
            deductions: [DeductionV2(id: "known", label: "Retenue", amount: 50, recurring: true)]
        )

        XCTAssertEqual(result.regularGross, 2_500, accuracy: 0.001)
        XCTAssertEqual(result.fixedPremiumsGross, 100, accuracy: 0.001)
        XCTAssertEqual(result.grossEstimate, 2_600, accuracy: 0.001)
        XCTAssertEqual(result.baskets, 20, accuracy: 0.001)
        XCTAssertEqual(result.netBeforeUnknownContributions, 2_550, accuracy: 0.001)
    }

    func testForfaitDaysUsesAgreedMonthlyGross() throws {
        let contract = ContractV2(
            id: "forfait-days",
            employerId: "company-a",
            type: .forfaitDays,
            contractualWeeklyMinutes: nil,
            grossHourlyRate: nil,
            hireDateEpochDay: nil,
            forfaitAnnualDays: 218,
            monthlyGrossSalary: 3_000
        )

        let result = try PayrollEngineV2.calculate(
            contract: contract,
            weeks: [],
            rules: PayrollRulesV2()
        )

        XCTAssertEqual(result.regularGross, 3_000, accuracy: 0.001)
        XCTAssertEqual(result.overtimeGross, 0, accuracy: 0.001)
        XCTAssertEqual(result.grossEstimate, 3_000, accuracy: 0.001)
    }

    func testInvalidOvertimeMultiplierIsNeutralizedAndMarksGrossUnreliable() throws {
        let result = try PayrollEngineV2.calculate(
            contract: hourlyContract(),
            weeks: [PayrollWeekV2(paidMinutes: 40 * 60)],
            rules: PayrollRulesV2(
                overtimeTiers: [
                    OvertimeTierV2(fromMinutes: 35 * 60, toMinutes: nil, multiplier: 0.5)
                ]
            )
        )

        XCTAssertEqual(result.regularGross, 350, accuracy: 0.001)
        XCTAssertEqual(result.overtimeGross, 0, accuracy: 0.001)
        XCTAssertEqual(result.grossEstimate, 350, accuracy: 0.001)
        XCTAssertFalse(result.grossReliable)
        XCTAssertTrue(result.traces.contains { $0.contains("ambigus ou invalides") })
        XCTAssertTrue(result.traces.contains { $0.contains("brut reste à confirmer") })
    }
}
