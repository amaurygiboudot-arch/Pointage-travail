import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryConventionPayrollBridgeV2Tests: XCTestCase {
    private let company = SalaryCompanyV2(
        id: "company",
        name: "Entreprise",
        siret: "12345678901234",
        idcc: "0292"
    )
    private let period = YearMonthV2(year: 2026, month: 9)!

    func testSingleWholeMonthVersionProvidesRules() {
        let range = SalaryConventionCoverageResolverV2.monthEpochDayRange(period)!
        let rules = PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
        let result = SalaryConventionPayrollBridgeV2.resolve(
            companyId: company.id,
            period: period,
            companies: companies(),
            storedRules: stored([
                snapshot("v1", start: range.start, end: range.end, rules: rules)
            ])
        )

        XCTAssertTrue(result.readyForSingleRulesCalculation)
        XCTAssertEqual(result.rules, rules)
    }

    func testEquivalentMultipleVersionsArePromotedSafely() {
        let range = SalaryConventionCoverageResolverV2.monthEpochDayRange(period)!
        let split = range.start + 14
        let rules = PayrollRulesV2(
            weeklyRegularMinutes: 35 * 60,
            sundayMultiplier: 1.50
        )
        let result = SalaryConventionPayrollBridgeV2.resolve(
            companyId: company.id,
            period: period,
            companies: companies(),
            storedRules: stored([
                snapshot("v1", start: range.start, end: split, rules: rules),
                snapshot("v2", start: split + 1, end: range.end, rules: rules)
            ])
        )

        XCTAssertTrue(result.readyForSingleRulesCalculation)
        XCTAssertEqual(result.rules, rules)
        XCTAssertFalse(
            result.warnings.contains(
                SalaryConventionCoverageResolverV2.multipleVersionsWarning
            )
        )
        XCTAssertTrue(
            result.warnings.contains(
                SalaryConventionSegmentPayrollCompatibilityV2.equivalentVersionsWarning
            )
        )
    }

    func testRealRuleChangeRemainsBlocked() {
        let range = SalaryConventionCoverageResolverV2.monthEpochDayRange(period)!
        let split = range.start + 14
        let result = SalaryConventionPayrollBridgeV2.resolve(
            companyId: company.id,
            period: period,
            companies: companies(),
            storedRules: stored([
                snapshot(
                    "v1",
                    start: range.start,
                    end: split,
                    rules: PayrollRulesV2(
                        weeklyRegularMinutes: 35 * 60,
                        sundayMultiplier: 1.50
                    )
                ),
                snapshot(
                    "v2",
                    start: split + 1,
                    end: range.end,
                    rules: PayrollRulesV2(
                        weeklyRegularMinutes: 35 * 60,
                        sundayMultiplier: 2.00
                    )
                )
            ])
        )

        XCTAssertFalse(result.readyForSingleRulesCalculation)
        XCTAssertNil(result.rules)
        XCTAssertTrue(
            result.warnings.contains(
                SalaryConventionSegmentPayrollCompatibilityV2.changedPayrollRulesWarning
            )
        )
    }

    func testIncompleteCoverageRemainsBlocked() {
        let range = SalaryConventionCoverageResolverV2.monthEpochDayRange(period)!
        let result = SalaryConventionPayrollBridgeV2.resolve(
            companyId: company.id,
            period: period,
            companies: companies(),
            storedRules: stored([
                snapshot(
                    "v1",
                    start: range.start,
                    end: range.start + 10,
                    rules: PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
                )
            ])
        )

        XCTAssertFalse(result.readyForSingleRulesCalculation)
        XCTAssertNil(result.rules)
    }

    private func companies() -> SalaryCompanyReadResultV2 {
        SalaryCompanyReadResultV2(
            companies: [company],
            reliable: true,
            repairedFromBackup: false,
            warnings: []
        )
    }

    private func stored(
        _ snapshots: [SalaryConventionRuleSnapshotV2]
    ) -> SalaryConventionRuleReadResultV2 {
        SalaryConventionRuleReadResultV2(
            snapshots: snapshots,
            reliable: true,
            repairedFromBackup: false,
            warnings: []
        )
    }

    private func snapshot(
        _ versionId: String,
        start: Int64,
        end: Int64,
        rules: PayrollRulesV2
    ) -> SalaryConventionRuleSnapshotV2 {
        SalaryConventionRuleSnapshotV2(
            idcc: "0292",
            versionId: versionId,
            sourceId: "source-\(versionId)",
            effectiveFromEpochDay: start,
            effectiveToEpochDay: end,
            rules: rules,
            checkedAtMs: 1,
            note: nil
        )
    }
}
