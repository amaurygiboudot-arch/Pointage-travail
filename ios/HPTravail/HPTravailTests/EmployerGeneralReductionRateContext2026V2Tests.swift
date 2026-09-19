import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class EmployerGeneralReductionRateContext2026V2Tests: XCTestCase {
    private let month = YearMonthV2(year: 2026, month: 9)!

    private func record(
        regime: EmployerGeneralReductionRateContext2026V2.HousingContributionRegime = .l813_5_2,
        rateSum: Double = 0.4021,
        from: YearMonthV2 = YearMonthV2(year: 2026, month: 1)!,
        to: YearMonthV2? = nil,
        source: String = "DSN / paramétrage paie 2026",
        id: String = "rgdu-rate"
    ) -> EmployerGeneralReductionRateContext2026V2.Record {
        .init(
            id: id,
            housingContributionRegime: regime,
            eligibleEmployerRateSum: rateSum,
            effectiveFrom: from,
            effectiveTo: to,
            source: source
        )
    }

    func testL813FirstRateResolvesLowerStandardCoefficient() {
        let result = EmployerGeneralReductionRateContext2026V2.resolve(
            records: [record(regime: .l813_5_1, rateSum: 0.3981)],
            period: month
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.tDelta ?? -1, 0.3781, accuracy: 0.000_000_1)
        XCTAssertEqual(result.maximumCoefficient ?? -1, 0.3981, accuracy: 0.000_000_1)
        XCTAssertTrue(EmployerGeneralReductionRateContext2026V2.isUsable(result))
    }

    func testL813SecondRateResolvesHigherStandardCoefficient() {
        let result = EmployerGeneralReductionRateContext2026V2.resolve(
            records: [record()],
            period: month
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.tDelta ?? -1, 0.3821, accuracy: 0.000_000_1)
        XCTAssertEqual(result.maximumCoefficient ?? -1, 0.4021, accuracy: 0.000_000_1)
    }

    func testLowerActualRatesReduceTDelta() {
        let result = EmployerGeneralReductionRateContext2026V2.resolve(
            records: [record(rateSum: 0.35)],
            period: month
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.tDelta ?? -1, 0.33, accuracy: 0.000_000_1)
        XCTAssertEqual(result.maximumCoefficient ?? -1, 0.35, accuracy: 0.000_000_1)
    }

    func testOverlappingRulesFailClosed() {
        let result = EmployerGeneralReductionRateContext2026V2.resolve(
            records: [
                record(id: "a"),
                record(from: YearMonthV2(year: 2026, month: 6)!, id: "b")
            ],
            period: month
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.tDelta)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("chevauchent") })
    }

    func testMissingRuleNeverFallsBackToWorkforce() {
        let result = EmployerGeneralReductionRateContext2026V2.resolve(
            records: [record(from: YearMonthV2(year: 2026, month: 10)!)],
            period: month
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.maximumCoefficient)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("régime de contribution logement") })
    }

    func testInvalidRateSumsAreRejected() {
        for rate in [Double.nan, .infinity, -1, 0.0199, 1.1] {
            let result = EmployerGeneralReductionRateContext2026V2.resolve(
                records: [record(rateSum: rate)],
                period: month
            )

            XCTAssertFalse(result.reliable)
            XCTAssertNil(result.tDelta)
        }
    }

    func testWrongYearNeverReuses2026Coefficient() {
        let result = EmployerGeneralReductionRateContext2026V2.resolve(
            records: [record()],
            period: YearMonthV2(year: 2027, month: 1)!
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.maximumCoefficient)
        XCTAssertTrue(result.warnings.contains { $0.contains("2027") })
    }
}
