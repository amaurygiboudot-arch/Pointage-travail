import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class EmployerGeneralReductionAnnual2026V2Tests: XCTestCase {
    private func annualInput(
        remuneration: Double = 24_000,
        fnalTreatment: EmployerWorkforceContributionsV2.FnalTreatment? = .uncapped0Point5Percent,
        type: ContractTypeV2? = .fullTime,
        weeklyMinutes: Int? = 35 * 60,
        additionalMinutes: Double? = 0,
        fullYear: Bool? = true,
        standardCase: Bool? = true,
        homogeneousParameters: Bool? = true
    ) -> EmployerGeneralReductionAnnual2026V2.Input {
        .init(
            year: 2026,
            annualReductionRemuneration: remuneration,
            fnalTreatment: fnalTreatment,
            contractType: type,
            contractualWeeklyMinutes: weeklyMinutes,
            additionalPaidMinutesAnnual: additionalMinutes,
            fullCalendarYearPresent: fullYear,
            standardCommonLawCaseConfirmed: standardCase,
            homogeneousAnnualParametersConfirmed: homogeneousParameters
        )
    }

    private func confirmedContext(
        fnalTreatment: EmployerWorkforceContributionsV2.FnalTreatment = .uncapped0Point5Percent
    ) -> EmployerGeneralReductionAnnualContextV2.Snapshot {
        EmployerGeneralReductionAnnualContextV2.resolve(
            records: [
                .init(
                    id: "ctx-2026",
                    year: 2026,
                    fullCalendarYearPresent: true,
                    standardCommonLawCaseConfirmed: true,
                    homogeneousAnnualParametersConfirmed: true,
                    source: "DSN + bulletins",
                    confirmedFnalTreatment: fnalTreatment,
                    confirmedContractType: .fullTime,
                    confirmedContractualWeeklyMinutes: 35 * 60
                )
            ],
            year: 2026
        )
    }

    private func months(
        fnalTreatment: EmployerWorkforceContributionsV2.FnalTreatment = .uncapped0Point5Percent,
        overrideAdvanceForMonth: Int? = nil,
        overrideFnalForMonth: Int? = nil
    ) -> [EmployerGeneralReductionAnnualInputV2.Month] {
        (1...12).map { month in
            let period = YearMonthV2(year: 2026, month: month)!
            let treatment: EmployerWorkforceContributionsV2.FnalTreatment =
                month == overrideFnalForMonth ? .capped0Point1Percent : fnalTreatment
            let calculated = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
                .init(
                    year: 2026,
                    reductionRemunerationMonthly: 2_000,
                    fnalTreatment: treatment,
                    contractType: .fullTime,
                    contractualWeeklyMinutes: 35 * 60,
                    additionalPaidMinutes: 0,
                    fullMonthPresent: true,
                    standardCommonLawCaseConfirmed: true
                )
            )
            let amount = month == overrideAdvanceForMonth
                ? (calculated.amount ?? 0) + 1
                : calculated.amount
            return .init(
                period: period,
                reductionRemunerationMonthly: 2_000,
                additionalPaidMinutes: 0,
                automaticRgduAdvanceAmount: amount,
                fnalTreatment: treatment,
                contractType: .fullTime,
                contractualWeeklyMinutes: 35 * 60,
                fullMonthPresent: true,
                standardCommonLawCaseConfirmed: true,
                paidHoursComplete: true,
                source: "Bulletin \(month)",
                reliable: true
            )
        }
    }

    func testAnnualStandardCaseMatchesAndroidReference() {
        let result = EmployerGeneralReductionAnnual2026V2.calculate(annualInput())

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.coefficient ?? -1, 0.3178, accuracy: 0.0000001)
        XCTAssertEqual(result.amount ?? -1, 7_627.20, accuracy: 0.001)
        XCTAssertEqual(result.referenceMinimumAnnual ?? -1, 21_876.40, accuracy: 0.000001)
        XCTAssertEqual(result.thresholdAnnual ?? -1, 65_629.20, accuracy: 0.000001)
    }

    func testCappedFnalUsesLowerAnnualDelta() {
        let result = EmployerGeneralReductionAnnual2026V2.calculate(
            annualInput(fnalTreatment: .capped0Point1Percent)
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.coefficient ?? -1, 0.3147, accuracy: 0.0000001)
    }

    func testMissingAnnualFnalTreatmentFailsClosed() {
        let result = EmployerGeneralReductionAnnual2026V2.calculate(annualInput(fnalTreatment: nil))

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.amount)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("FNAL/logement") })
    }

    func testAnnualParameterInstabilityFailsClosed() {
        let result = EmployerGeneralReductionAnnual2026V2.calculate(
            annualInput(homogeneousParameters: false)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.amount)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("stabilité") })
    }

    func testRegularizationCanProducePositiveAndNegativeAdjustments() {
        let positiveAnnual = EmployerGeneralReductionAnnual2026V2.Result(
            amount: 1_500,
            coefficient: 0.1,
            referenceMinimumAnnual: 20_000,
            thresholdAnnual: 60_000,
            reliable: true,
            warnings: []
        )
        let advances = (1...12).map {
            EmployerGeneralReductionAnnualRegularizationV2.MonthlyAdvance(month: $0, amount: 100)
        }
        let positive = EmployerGeneralReductionAnnualRegularizationV2.resolve(
            year: 2026,
            annual: positiveAnnual,
            monthlyAdvances: advances
        )
        XCTAssertTrue(positive.reliable)
        XCTAssertEqual(positive.advancesTotal ?? -1, 1_200, accuracy: 0.001)
        XCTAssertEqual(positive.adjustment ?? -1, 300, accuracy: 0.001)

        let negativeAnnual = EmployerGeneralReductionAnnual2026V2.Result(
            amount: 900,
            coefficient: 0.1,
            referenceMinimumAnnual: 20_000,
            thresholdAnnual: 60_000,
            reliable: true,
            warnings: []
        )
        let negative = EmployerGeneralReductionAnnualRegularizationV2.resolve(
            year: 2026,
            annual: negativeAnnual,
            monthlyAdvances: advances
        )
        XCTAssertTrue(negative.reliable)
        XCTAssertEqual(negative.adjustment ?? 1, -300, accuracy: 0.001)
    }

    func testMissingMonthlyAdvanceIsNeverTreatedAsZero() {
        let annual = EmployerGeneralReductionAnnual2026V2.calculate(annualInput())
        let advances = (1...11).map {
            EmployerGeneralReductionAnnualRegularizationV2.MonthlyAdvance(month: $0, amount: 100)
        }
        let result = EmployerGeneralReductionAnnualRegularizationV2.resolve(
            year: 2026,
            annual: annual,
            monthlyAdvances: advances
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.adjustment)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("mois manquants") })
    }

    func testAnnualContextRequiresExplicitFnalWhenParametersAreDeclaredStable() {
        let context = EmployerGeneralReductionAnnualContextV2.resolve(
            records: [
                .init(
                    id: "legacy",
                    year: 2026,
                    fullCalendarYearPresent: true,
                    standardCommonLawCaseConfirmed: true,
                    homogeneousAnnualParametersConfirmed: true,
                    source: "Ancien contexte",
                    confirmedFnalTreatment: nil,
                    confirmedContractType: .fullTime,
                    confirmedContractualWeeklyMinutes: 35 * 60
                )
            ],
            year: 2026
        )

        XCTAssertTrue(context.reliable)
        XCTAssertNil(context.confirmedFnalTreatment)
        XCTAssertTrue(context.warnings.contains { $0.localizedCaseInsensitiveContains("FNAL/logement") })
    }

    func testTwelveVerifiedMonthsBuildAnnualInput() {
        let result = EmployerGeneralReductionAnnualInputV2.resolve(
            year: 2026,
            months: months(),
            annualContext: confirmedContext()
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.monthlyAdvances.count, 12)
        XCTAssertEqual(result.annualInput?.annualReductionRemuneration ?? -1, 24_000, accuracy: 0.001)
        XCTAssertEqual(result.annualInput?.fnalTreatment, .uncapped0Point5Percent)
    }

    func testRecordedAdvanceMustStillMatchMonthlyFacts() {
        let result = EmployerGeneralReductionAnnualInputV2.resolve(
            year: 2026,
            months: months(overrideAdvanceForMonth: 6),
            annualContext: confirmedContext()
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.annualInput)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("ne correspond plus") })
    }

    func testFnalTreatmentVariationDuringYearBlocksStandardAnnualCase() {
        let result = EmployerGeneralReductionAnnualInputV2.resolve(
            year: 2026,
            months: months(overrideFnalForMonth: 7),
            annualContext: confirmedContext()
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.annualInput)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("varie") })
    }
}
