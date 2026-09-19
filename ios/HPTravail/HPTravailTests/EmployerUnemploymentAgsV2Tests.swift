import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class EmployerUnemploymentAgsV2Tests: XCTestCase {
    func testConfirmedRatesResolveForSelectedPeriod() {
        let record = EmployerUnemploymentAgsV2.Record(
            id: "rule",
            unemploymentRate: 0.04,
            agsRate: 0.0025,
            effectiveFrom: .init(year: 2026, month: 1),
            effectiveTo: nil,
            source: "Notification ou DSN"
        )
        let result = EmployerUnemploymentAgsV2.resolve(
            records: [record],
            period: .init(year: 2026, month: 9)
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.unemploymentRate ?? -1, 0.04, accuracy: 0.000_001)
        XCTAssertEqual(result.agsRate ?? -1, 0.0025, accuracy: 0.000_001)
    }

    func testOverlappingRecordsBlockAutomaticCalculation() {
        let first = EmployerUnemploymentAgsV2.Record(
            id: "a",
            unemploymentRate: 0.04,
            agsRate: 0.0025,
            effectiveFrom: .init(year: 2026, month: 1),
            effectiveTo: nil,
            source: "A"
        )
        let second = EmployerUnemploymentAgsV2.Record(
            id: "b",
            unemploymentRate: 0.035,
            agsRate: 0.0025,
            effectiveFrom: .init(year: 2026, month: 3),
            effectiveTo: nil,
            source: "B"
        )
        let result = EmployerUnemploymentAgsV2.resolve(
            records: [first, second],
            period: .init(year: 2026, month: 9)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.unemploymentRate)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("chevauchent") })
    }

    func testConfirmedRatesCalculateEmployerOnlyAmounts() {
        let result = EmployerUnemploymentAgsV2.calculate(
            grossSocial: 2_500,
            fourTimesApplicableCeiling: 16_020,
            unemploymentRate: 0.04,
            agsRate: 0.0025
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.baseAmount ?? -1, 2_500, accuracy: 0.001)
        XCTAssertEqual(result.unemploymentAmount ?? -1, 100, accuracy: 0.001)
        XCTAssertEqual(result.agsAmount ?? -1, 6.25, accuracy: 0.001)
        XCTAssertEqual(result.totalEmployerAmount ?? -1, 106.25, accuracy: 0.001)
    }

    func testBaseIsCappedAtFourApplicableCeilings() {
        let result = EmployerUnemploymentAgsV2.calculate(
            grossSocial: 20_000,
            fourTimesApplicableCeiling: 16_020,
            unemploymentRate: 0.04,
            agsRate: 0.0025
        )

        XCTAssertEqual(result.baseAmount ?? -1, 16_020, accuracy: 0.001)
        XCTAssertEqual(result.unemploymentAmount ?? -1, 16_020 * 0.04, accuracy: 0.001)
        XCTAssertEqual(result.agsAmount ?? -1, 16_020 * 0.0025, accuracy: 0.001)
    }

    func testInvalidGrossNeverInventsEmployerAmounts() {
        for gross in [-50, .infinity, .nan] {
            let result = EmployerUnemploymentAgsV2.calculate(
                grossSocial: gross,
                fourTimesApplicableCeiling: 16_020,
                unemploymentRate: 0.04,
                agsRate: 0.0025
            )

            XCTAssertFalse(result.complete)
            XCTAssertNil(result.baseAmount)
            XCTAssertNil(result.unemploymentAmount)
            XCTAssertNil(result.agsAmount)
            XCTAssertNil(result.totalEmployerAmount)
            XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("assiette brute invalide") })
        }
    }
}
