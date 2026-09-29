import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryConventionSegmentPayrollCompatibilityV2Tests: XCTestCase {
    func testEmptySegmentsAreNotCompatible() {
        let result = SalaryConventionSegmentPayrollCompatibilityV2.resolve([])

        XCTAssertFalse(result.compatibleForSingleMonthlyCalculation)
        XCTAssertNil(result.rules)
    }

    func testSingleVersionIsCompatible() {
        let rules = PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
        let result = SalaryConventionSegmentPayrollCompatibilityV2.resolve([
            segment("v1", start: 0, end: 30, rules: rules)
        ])

        XCTAssertTrue(result.compatibleForSingleMonthlyCalculation)
        XCTAssertEqual(result.rules, rules)
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testMultipleVersionsWithIdenticalPayrollRulesRemainCompatible() {
        let rules = PayrollRulesV2(
            weeklyRegularMinutes: 35 * 60,
            overtimeTiers: [
                OvertimeTierV2(fromMinutes: 0, toMinutes: 8 * 60, multiplier: 1.25),
                OvertimeTierV2(fromMinutes: 8 * 60, toMinutes: nil, multiplier: 1.50)
            ],
            sundayMultiplier: 1.50
        )
        let result = SalaryConventionSegmentPayrollCompatibilityV2.resolve([
            segment("v1", start: 0, end: 14, rules: rules),
            segment("v2", start: 15, end: 30, rules: rules)
        ])

        XCTAssertTrue(result.compatibleForSingleMonthlyCalculation)
        XCTAssertEqual(result.rules, rules)
        XCTAssertEqual(
            result.warnings,
            [SalaryConventionSegmentPayrollCompatibilityV2.equivalentVersionsWarning]
        )
    }

    func testAnyPayrollRuleChangeBlocksSingleMonthlyCalculation() {
        let first = PayrollRulesV2(
            weeklyRegularMinutes: 35 * 60,
            sundayMultiplier: 1.50
        )
        let second = PayrollRulesV2(
            weeklyRegularMinutes: 35 * 60,
            sundayMultiplier: 2.00
        )
        let result = SalaryConventionSegmentPayrollCompatibilityV2.resolve([
            segment("v1", start: 0, end: 14, rules: first),
            segment("v2", start: 15, end: 30, rules: second)
        ])

        XCTAssertFalse(result.compatibleForSingleMonthlyCalculation)
        XCTAssertNil(result.rules)
        XCTAssertEqual(
            result.warnings,
            [SalaryConventionSegmentPayrollCompatibilityV2.changedPayrollRulesWarning]
        )
    }

    private func segment(
        _ versionId: String,
        start: Int64,
        end: Int64,
        rules: PayrollRulesV2
    ) -> SalaryConventionCoverageSegmentV2 {
        SalaryConventionCoverageSegmentV2(
            startEpochDay: start,
            endEpochDay: end,
            snapshot: SalaryConventionRuleSnapshotV2(
                idcc: "0292",
                versionId: versionId,
                sourceId: "source-\(versionId)",
                effectiveFromEpochDay: start,
                effectiveToEpochDay: end,
                rules: rules,
                checkedAtMs: 1,
                note: nil
            )
        )
    }
}
