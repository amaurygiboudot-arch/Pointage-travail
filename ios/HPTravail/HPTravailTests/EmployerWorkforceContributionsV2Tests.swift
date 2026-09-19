import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class EmployerWorkforceContributionsV2Tests: XCTestCase {
    func testUnder11UsesCappedFnalAndTrainingRate() {
        let result = EmployerWorkforceContributionsV2.calculate(
            grossSocial: 5_000,
            applicableMonthlyCeiling: 4_005,
            year: 2026,
            band: .under11
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.fnalAmount ?? -1, 4.005, accuracy: 0.001)
        XCTAssertEqual(result.trainingAmount ?? -1, 27.50, accuracy: 0.001)
    }

    func testFrom11To49UsesCappedFnalAndOnePercentTraining() {
        let result = EmployerWorkforceContributionsV2.calculate(
            grossSocial: 5_000,
            applicableMonthlyCeiling: 4_005,
            year: 2026,
            band: .from11To49
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.fnalAmount ?? -1, 4.005, accuracy: 0.001)
        XCTAssertEqual(result.trainingAmount ?? -1, 50, accuracy: 0.001)
    }

    func testAtLeast50UsesUncappedFnalAndOnePercentTraining() {
        let result = EmployerWorkforceContributionsV2.calculate(
            grossSocial: 5_000,
            applicableMonthlyCeiling: 4_005,
            year: 2026,
            band: .atLeast50
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.fnalAmount ?? -1, 25, accuracy: 0.001)
        XCTAssertEqual(result.trainingAmount ?? -1, 50, accuracy: 0.001)
    }

    func testMissingBandNeverInventsEmployerContribution() {
        let result = EmployerWorkforceContributionsV2.calculate(
            grossSocial: 2_500,
            applicableMonthlyCeiling: 4_005,
            year: 2026,
            band: nil
        )

        XCTAssertFalse(result.complete)
        XCTAssertNil(result.totalEmployerAmount)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("effectif") })
    }

    func testInvalidGrossNeverProducesWorkforceEmployerAmounts() {
        for gross in [-1.0, .nan, .infinity, -.infinity] {
            let result = EmployerWorkforceContributionsV2.calculate(
                grossSocial: gross,
                applicableMonthlyCeiling: 4_005,
                year: 2026,
                band: .under11
            )

            XCTAssertFalse(result.complete)
            XCTAssertNil(result.fnalAmount)
            XCTAssertNil(result.trainingAmount)
            XCTAssertNil(result.totalEmployerAmount)
            XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("assiette") })
        }
    }

    func testOverlappingWorkforceBandsBlockResolution() {
        let first = EmployerWorkforceContributionsV2.Record(
            id: "a",
            band: .under11,
            effectiveFrom: .init(year: 2026, month: 1),
            effectiveTo: nil,
            source: "DSN"
        )
        let second = EmployerWorkforceContributionsV2.Record(
            id: "b",
            band: .from11To49,
            effectiveFrom: .init(year: 2026, month: 6),
            effectiveTo: nil,
            source: "DSN"
        )
        let result = EmployerWorkforceContributionsV2.resolve(
            records: [first, second],
            period: .init(year: 2026, month: 9)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.band)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("chevauchent") })
    }
}
