import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class EmployerMobilityContributionV2Tests: XCTestCase {
    func testApplicableRuleResolvesOnlyInsideItsPeriod() {
        let rule = EmployerMobilityContributionV2.Record(
            id: "vm_1",
            status: .applicable,
            rate: 0.025,
            effectiveFrom: .init(year: 2026, month: 1),
            effectiveTo: .init(year: 2026, month: 6),
            source: "Urssaf - taux confirme"
        )

        let june = EmployerMobilityContributionV2.resolve(
            records: [rule],
            period: .init(year: 2026, month: 6)
        )
        let july = EmployerMobilityContributionV2.resolve(
            records: [rule],
            period: .init(year: 2026, month: 7)
        )

        XCTAssertTrue(june.reliable)
        XCTAssertEqual(june.rate ?? -1, 0.025, accuracy: 0.000_001)
        XCTAssertFalse(july.reliable)
        XCTAssertNil(july.rate)
    }

    func testConfirmedNotApplicableResolvesToZero() {
        let rule = EmployerMobilityContributionV2.Record(
            id: "vm_none",
            status: .notApplicable,
            rate: nil,
            effectiveFrom: .init(year: 2026, month: 1),
            effectiveTo: nil,
            source: "Effectif ou zone confirme non assujetti"
        )
        let result = EmployerMobilityContributionV2.resolve(
            records: [rule],
            period: .init(year: 2026, month: 9)
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.applicable, false)
        XCTAssertEqual(result.rate ?? -1, 0, accuracy: 0)
    }

    func testOverlappingRulesBlockAutomaticRate() {
        let first = EmployerMobilityContributionV2.Record(
            id: "a",
            status: .applicable,
            rate: 0.02,
            effectiveFrom: .init(year: 2026, month: 1),
            effectiveTo: .init(year: 2026, month: 12),
            source: "Urssaf A"
        )
        let second = EmployerMobilityContributionV2.Record(
            id: "b",
            status: .applicable,
            rate: 0.025,
            effectiveFrom: .init(year: 2026, month: 7),
            effectiveTo: nil,
            source: "Urssaf B"
        )
        let result = EmployerMobilityContributionV2.resolve(
            records: [first, second],
            period: .init(year: 2026, month: 9)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.rate)
    }

    func testCalculationUsesSocialGross() {
        let result = EmployerMobilityContributionV2.calculate(grossSocial: 2_700, rate: 0.02)

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.employerAmount ?? -1, 54, accuracy: 0.001)
    }

    func testInvalidGrossNeverProducesMobilityEmployerAmount() {
        for gross in [-1.0, .nan, .infinity, -.infinity] {
            let result = EmployerMobilityContributionV2.calculate(grossSocial: gross, rate: 0.02)

            XCTAssertFalse(result.complete)
            XCTAssertNil(result.employerAmount)
            XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("assiette") })
        }
    }
}
