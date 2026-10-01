import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedMonthlyBaseBridgeV2Tests: XCTestCase {
    func testIndependentRuleVersionIdsAreAlignedByDates() throws {
        let contracts = contractResolution()
        let commonRules = PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
        let rules = ruleCoverage([
            ruleSegment("rule-a", start: 0, end: 9, rules: commonRules),
            ruleSegment("rule-b", start: 10, end: 14, rules: commonRules),
            ruleSegment("rule-c", start: 15, end: 30, rules: commonRules)
        ])

        let result = SalarySegmentedMonthlyBaseBridgeV2.calculate(
            contracts: contracts,
            rules: rules,
            prorationSource: confirmedProration()
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.pieces.map(\.versionId), ["contract-a", "contract-b"])
        XCTAssertEqual(try XCTUnwrap(result.baseGross), 2_275.0, accuracy: 0.0001)
    }

    func testRuleChangeInsideSameContractSegmentBlocksCalculation() {
        let contracts = contractResolution()
        let rules = ruleCoverage([
            ruleSegment(
                "rule-a",
                start: 0,
                end: 9,
                rules: PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
            ),
            ruleSegment(
                "rule-b",
                start: 10,
                end: 14,
                rules: PayrollRulesV2(
                    weeklyRegularMinutes: 35 * 60,
                    saturdayMultiplier: 1.25
                )
            ),
            ruleSegment(
                "rule-c",
                start: 15,
                end: 30,
                rules: PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
            )
        ])

        let result = SalarySegmentedMonthlyBaseBridgeV2.calculate(
            contracts: contracts,
            rules: rules,
            prorationSource: confirmedProration()
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.baseGross)
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedMonthlyBaseBridgeV2.ruleChangesWithinContractWarning
            )
        )
    }

    func testMissingConfirmedProrationStaysBlocked() {
        let result = SalarySegmentedMonthlyBaseBridgeV2.calculate(
            contracts: contractResolution(),
            rules: ruleCoverage([
                ruleSegment(
                    "rule-only",
                    start: 0,
                    end: 30,
                    rules: PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
                )
            ]),
            prorationSource: SalarySegmentedProrationSourceV2(
                proration: nil,
                reliable: false,
                warnings: [SalarySegmentedProrationStoreV2.missingWarning]
            )
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.baseGross)
        XCTAssertEqual(result.warnings, [SalarySegmentedProrationStoreV2.missingWarning])
    }

    func testIncompleteRuleTimelineBlocksCalculation() {
        let result = SalarySegmentedMonthlyBaseBridgeV2.calculate(
            contracts: contractResolution(),
            rules: SalaryConventionCoverageV2(
                companyId: "company",
                idcc: "0001",
                periodStartEpochDay: 0,
                periodEndEpochDay: 30,
                segments: [
                    ruleSegment(
                        "rule-a",
                        start: 0,
                        end: 20,
                        rules: PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
                    )
                ],
                sourceReliable: true,
                fullyCovered: false,
                warnings: ["couverture incomplète"]
            ),
            prorationSource: confirmedProration()
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.baseGross)
        XCTAssertTrue(result.warnings.contains(SalarySegmentedMonthlyBaseBridgeV2.timelineWarning))
    }

    func testProductionResolverUsesSegmentedBaseWhenSingleCalculationIsBlocked() throws {
        let snapshot = SalaryEmploymentContractPayrollSnapshotV2(
            resolution: contractResolution(),
            warnings: []
        )

        let result = SalarySegmentedMonthlyBaseProductionV2.resolve(
            contractSnapshot: snapshot,
            conventionCoverage: ruleCoverage([
                ruleSegment(
                    "rule-a",
                    start: 0,
                    end: 14,
                    rules: PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
                ),
                ruleSegment(
                    "rule-b",
                    start: 15,
                    end: 30,
                    rules: PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
                )
            ]),
            prorationSource: confirmedProration()
        )

        XCTAssertTrue(result?.reliable == true)
        XCTAssertEqual(try XCTUnwrap(result?.baseGross), 2_275.0, accuracy: 0.0001)
    }

    func testProductionResolverKeepsMissingProrationFailClosed() {
        let snapshot = SalaryEmploymentContractPayrollSnapshotV2(
            resolution: contractResolution(),
            warnings: []
        )

        let result = SalarySegmentedMonthlyBaseProductionV2.resolve(
            contractSnapshot: snapshot,
            conventionCoverage: ruleCoverage([
                ruleSegment(
                    "rule-a",
                    start: 0,
                    end: 14,
                    rules: PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
                ),
                ruleSegment(
                    "rule-b",
                    start: 15,
                    end: 30,
                    rules: PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
                )
            ]),
            prorationSource: nil
        )

        XCTAssertFalse(result?.reliable ?? true)
        XCTAssertNil(result?.baseGross)
        XCTAssertEqual(result?.warnings, [SalarySegmentedProrationStoreV2.missingWarning])
    }

    func testProductionResolverDoesNotCompeteWithSingleContractPath() {
        let base = contractResolution()
        let promoted = SalaryEmploymentContractPeriodResolutionV2(
            companyId: base.companyId,
            periodStartEpochDay: base.periodStartEpochDay,
            periodEndEpochDay: base.periodEndEpochDay,
            sourceReliable: base.sourceReliable,
            coverage: base.coverage,
            contract: base.calculationSegments.first?.snapshot.contract,
            warnings: []
        )
        let snapshot = SalaryEmploymentContractPayrollSnapshotV2(
            resolution: promoted,
            warnings: []
        )

        let result = SalarySegmentedMonthlyBaseProductionV2.resolve(
            contractSnapshot: snapshot,
            conventionCoverage: ruleCoverage([
                ruleSegment(
                    "rule-a",
                    start: 0,
                    end: 30,
                    rules: PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
                )
            ]),
            prorationSource: confirmedProration()
        )

        XCTAssertNil(result)
    }

    private func contractResolution() -> SalaryEmploymentContractPeriodResolutionV2 {
        let segments = [
            contractSegment("contract-a", start: 0, end: 14, rate: 10),
            contractSegment("contract-b", start: 15, end: 30, rate: 20)
        ]
        return SalaryEmploymentContractPeriodResolutionV2(
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
            contract: nil,
            warnings: []
        )
    }

    private func contractSegment(
        _ versionId: String,
        start: Int64,
        end: Int64,
        rate: Double
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
                    grossHourlyRate: rate,
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
        end: Int64,
        rules: PayrollRulesV2
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
                rules: rules,
                checkedAtMs: 1,
                note: nil
            )
        )
    }

    private func confirmedProration() -> SalarySegmentedProrationSourceV2 {
        SalarySegmentedProrationSourceV2(
            proration: ConfirmedSegmentedMonthlyProrationV2(
                sourceId: "planning-confirme",
                checkedAtMs: 1,
                segments: [
                    ConfirmedProrationSegmentV2(
                        versionId: "contract-a",
                        startEpochDay: 0,
                        endEpochDay: 14,
                        scheduledMinutes: 4_200
                    ),
                    ConfirmedProrationSegmentV2(
                        versionId: "contract-b",
                        startEpochDay: 15,
                        endEpochDay: 30,
                        scheduledMinutes: 4_200
                    )
                ]
            ),
            reliable: true,
            warnings: []
        )
    }
}
