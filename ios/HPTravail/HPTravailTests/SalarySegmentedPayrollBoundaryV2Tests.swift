import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedPayrollBoundaryV2Tests: XCTestCase {
    func testMondayContractTransitionAllowsIndependentWeeklyVariableCalculation() {
        let contracts = contractResolution(
            [
                contractSegment("c1", start: 0, end: 10),
                contractSegment("c2", start: 11, end: 30)
            ]
        )
        let rules = ruleCoverage([
            ruleSegment("r1", start: 0, end: 30)
        ])

        let result = SalarySegmentedPayrollBoundaryV2.assess(
            contracts: contracts,
            rules: rules
        )

        XCTAssertTrue(result.timelineReliable)
        XCTAssertTrue(result.safeForIndependentWeeklyVariableCalculation)
        XCTAssertEqual(result.transitionEpochDays, [11])
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testMidweekContractTransitionBlocksIndependentWeeklyVariableCalculation() {
        let contracts = contractResolution(
            [
                contractSegment("c1", start: 0, end: 8),
                contractSegment("c2", start: 9, end: 30)
            ]
        )
        let rules = ruleCoverage([
            ruleSegment("r1", start: 0, end: 30)
        ])

        let result = SalarySegmentedPayrollBoundaryV2.assess(
            contracts: contracts,
            rules: rules
        )

        XCTAssertTrue(result.timelineReliable)
        XCTAssertFalse(result.safeForIndependentWeeklyVariableCalculation)
        XCTAssertEqual(result.transitionEpochDays, [9])
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedPayrollBoundaryV2.midweekTransitionWarning
            )
        )
    }

    func testMidweekRuleTransitionAlsoBlocks() {
        let contracts = contractResolution([
            contractSegment("c1", start: 0, end: 30)
        ])
        let rules = ruleCoverage([
            ruleSegment("r1", start: 0, end: 8),
            ruleSegment("r2", start: 9, end: 30)
        ])

        let result = SalarySegmentedPayrollBoundaryV2.assess(
            contracts: contracts,
            rules: rules
        )

        XCTAssertTrue(result.timelineReliable)
        XCTAssertFalse(result.safeForIndependentWeeklyVariableCalculation)
        XCTAssertEqual(result.transitionEpochDays, [9])
    }

    func testIncompleteRuleTimelineFailsClosed() {
        let contracts = contractResolution([
            contractSegment("c1", start: 0, end: 30)
        ])
        let rules = SalaryConventionCoverageV2(
            companyId: "company",
            idcc: "0001",
            periodStartEpochDay: 0,
            periodEndEpochDay: 30,
            segments: [
                ruleSegment("r1", start: 0, end: 20)
            ],
            sourceReliable: true,
            fullyCovered: false,
            warnings: ["couverture incomplète"]
        )

        let result = SalarySegmentedPayrollBoundaryV2.assess(
            contracts: contracts,
            rules: rules
        )

        XCTAssertFalse(result.timelineReliable)
        XCTAssertFalse(result.safeForIndependentWeeklyVariableCalculation)
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedPayrollBoundaryV2.timelineWarning
            )
        )
    }

    private func contractResolution(
        _ segments: [SalaryEmploymentContractCoverageSegmentV2]
    ) -> SalaryEmploymentContractPeriodResolutionV2 {
        SalaryEmploymentContractPeriodResolutionV2(
            companyId: "company",
            periodStartEpochDay: 0,
            periodEndEpochDay: 30,
            sourceReliable: true,
            coverage: SalaryEmploymentContractCoverageV2(
                companyId: "company",
                periodStartEpochDay: 0,
                periodEndEpochDay: 30,
                segments: segments,
                fullyCovered: true
            ),
            contract: segments.count == 1 ? segments[0].snapshot.contract : nil,
            warnings: []
        )
    }

    private func contractSegment(
        _ versionId: String,
        start: Int64,
        end: Int64
    ) -> SalaryEmploymentContractCoverageSegmentV2 {
        SalaryEmploymentContractCoverageSegmentV2(
            startEpochDay: start,
            endEpochDay: end,
            snapshot: SalaryEmploymentContractSnapshotV2(
                versionId: versionId,
                sourceId: "contract-source",
                effectiveFromEpochDay: start,
                effectiveToEpochDay: end,
                contract: ContractV2(
                    id: versionId,
                    employerId: "company",
                    type: .fullTime,
                    contractualWeeklyMinutes: 35 * 60,
                    grossHourlyRate: 14,
                    hireDateEpochDay: 0
                ),
                checkedAtMs: 1,
                note: nil
            )
        )
    }

    private func ruleCoverage(
        _ segments: [SalaryConventionCoverageSegmentV2]
    ) -> SalaryConventionCoverageV2 {
        SalaryConventionCoverageV2(
            companyId: "company",
            idcc: "0001",
            periodStartEpochDay: 0,
            periodEndEpochDay: 30,
            segments: segments,
            sourceReliable: true,
            fullyCovered: true,
            warnings: []
        )
    }

    private func ruleSegment(
        _ versionId: String,
        start: Int64,
        end: Int64
    ) -> SalaryConventionCoverageSegmentV2 {
        SalaryConventionCoverageSegmentV2(
            startEpochDay: start,
            endEpochDay: end,
            snapshot: SalaryConventionRuleSnapshotV2(
                idcc: "0001",
                versionId: versionId,
                sourceId: "rule-source",
                effectiveFromEpochDay: start,
                effectiveToEpochDay: end,
                rules: PayrollRulesV2(weeklyRegularMinutes: 35 * 60),
                checkedAtMs: 1,
                note: nil
            )
        )
    }
}
