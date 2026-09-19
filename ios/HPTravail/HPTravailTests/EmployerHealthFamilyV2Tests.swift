import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class EmployerHealthFamilyV2Tests: XCTestCase {
    func testConfirmedRatesCalculateEmployerAmounts() {
        let result = EmployerHealthFamilyV2.calculate(
            grossSocial: 2_500,
            healthRate: 0.13,
            familyRate: 0.0525
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.healthAmount ?? -1, 325, accuracy: 0.001)
        XCTAssertEqual(result.familyAmount ?? -1, 131.25, accuracy: 0.001)
        XCTAssertEqual(result.totalEmployerAmount ?? -1, 456.25, accuracy: 0.001)
    }

    func testOverlappingRulesBlockAutomaticSelection() {
        let first = EmployerHealthFamilyV2.Record(
            id: "a",
            healthRate: 0.13,
            familyRate: 0.0525,
            effectiveFrom: .init(year: 2026, month: 1),
            effectiveTo: nil,
            source: "DSN"
        )
        let second = EmployerHealthFamilyV2.Record(
            id: "b",
            healthRate: 0.07,
            familyRate: 0.0345,
            effectiveFrom: .init(year: 2026, month: 6),
            effectiveTo: nil,
            source: "Exonération confirmée"
        )
        let result = EmployerHealthFamilyV2.resolve(
            records: [first, second],
            period: .init(year: 2026, month: 9)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.healthRate)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("chevauchent") })
    }

    func testMissingRatesNeverInventEmployerAmount() {
        let result = EmployerHealthFamilyV2.calculate(
            grossSocial: 2_500,
            healthRate: nil,
            familyRate: nil
        )

        XCTAssertFalse(result.complete)
        XCTAssertNil(result.totalEmployerAmount)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("manquants") })
    }

    func testInvalidGrossNeverInventsEmployerAmount() {
        for gross in [-50, .infinity, .nan] {
            let result = EmployerHealthFamilyV2.calculate(
                grossSocial: gross,
                healthRate: 0.13,
                familyRate: 0.0525
            )

            XCTAssertFalse(result.complete)
            XCTAssertNil(result.healthAmount)
            XCTAssertNil(result.familyAmount)
            XCTAssertNil(result.totalEmployerAmount)
            XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("assiette brute invalide") })
        }
    }

    func testMalformedPeriodNeverSelectsRule() {
        let record = EmployerHealthFamilyV2.Record(
            id: "a",
            healthRate: 0.13,
            familyRate: 0.0525,
            effectiveFrom: .init(year: 2026, month: 13),
            effectiveTo: nil,
            source: "DSN"
        )
        let result = EmployerHealthFamilyV2.resolve(
            records: [record],
            period: .init(year: 2026, month: 9)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.healthRate)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("incomplète") })
    }
}
