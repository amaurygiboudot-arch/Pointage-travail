import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryPayrollCalculationTimelineV2Tests: XCTestCase {
    func testIndependentContractAndRuleChangesProduceMinimalExactSlices() throws {
        let contracts = try XCTUnwrap(
            SalaryEmploymentContractPeriodResolverV2.resolve(
                companyId: "company",
                periodStartEpochDay: 0,
                periodEndEpochDay: 30,
                sourceReliable: true,
                snapshots: [
                    contract("c1", from: 0, to: 14, rate: 12),
                    contract("c2", from: 15, to: nil, rate: 13)
                ]
            )
        )
        let rules = coverage(
            start: 0,
            end: 30,
            segments: [
                ruleSegment("r1", start: 0, end: 19),
                ruleSegment("r2", start: 20, end: 30)
            ]
        )

        let result = SalaryPayrollCalculationTimelineV2.align(contracts: contracts, rules: rules)

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.slices.count, 3)
        XCTAssertEqual(
            result.slices.map {
                SliceExpectation(
                    start: $0.startEpochDay,
                    end: $0.endEpochDay,
                    contractVersion: $0.contractVersionId,
                    ruleVersion: $0.ruleVersionId
                )
            },
            [
                SliceExpectation(start: 0, end: 14, contractVersion: "c1", ruleVersion: "r1"),
                SliceExpectation(start: 15, end: 19, contractVersion: "c2", ruleVersion: "r1"),
                SliceExpectation(start: 20, end: 30, contractVersion: "c2", ruleVersion: "r2")
            ]
        )
    }

    func testCoincidentContractAndRuleBoundaryDoesNotCreateArtificialSlice() throws {
        let contracts = try XCTUnwrap(
            SalaryEmploymentContractPeriodResolverV2.resolve(
                companyId: "company",
                periodStartEpochDay: 0,
                periodEndEpochDay: 30,
                sourceReliable: true,
                snapshots: [
                    contract("c1", from: 0, to: 14, rate: 12),
                    contract("c2", from: 15, to: nil, rate: 13)
                ]
            )
        )
        let rules = coverage(
            start: 0,
            end: 30,
            segments: [
                ruleSegment("r1", start: 0, end: 14),
                ruleSegment("r2", start: 15, end: 30)
            ]
        )

        let result = SalaryPayrollCalculationTimelineV2.align(contracts: contracts, rules: rules)

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.slices.count, 2)
        XCTAssertEqual(result.slices[0].contractVersionId, "c1")
        XCTAssertEqual(result.slices[0].ruleVersionId, "r1")
        XCTAssertEqual(result.slices[1].contractVersionId, "c2")
        XCTAssertEqual(result.slices[1].ruleVersionId, "r2")
    }

    func testDifferentCompaniesBlockAlignment() throws {
        let contracts = try XCTUnwrap(
            SalaryEmploymentContractPeriodResolverV2.resolve(
                companyId: "company",
                periodStartEpochDay: 0,
                periodEndEpochDay: 30,
                sourceReliable: true,
                snapshots: [contract("c1", from: 0, to: nil, rate: 12)]
            )
        )
        let rules = SalaryConventionCoverageV2(
            companyId: "other-company",
            idcc: "0292",
            periodStartEpochDay: 0,
            periodEndEpochDay: 30,
            segments: [ruleSegment("r1", start: 0, end: 30)],
            sourceReliable: true,
            fullyCovered: true,
            warnings: []
        )

        let result = SalaryPayrollCalculationTimelineV2.align(contracts: contracts, rules: rules)

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.slices.isEmpty)
        XCTAssertTrue(result.warnings.contains(SalaryPayrollCalculationTimelineV2.companyMismatchWarning))
    }

    func testDifferentCoveragePeriodsBlockAlignment() throws {
        let contracts = try XCTUnwrap(
            SalaryEmploymentContractPeriodResolverV2.resolve(
                companyId: "company",
                periodStartEpochDay: 0,
                periodEndEpochDay: 30,
                sourceReliable: true,
                snapshots: [contract("c1", from: 0, to: nil, rate: 12)]
            )
        )
        let rules = coverage(
            start: 1,
            end: 30,
            segments: [ruleSegment("r1", start: 1, end: 30)]
        )

        let result = SalaryPayrollCalculationTimelineV2.align(contracts: contracts, rules: rules)

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.slices.isEmpty)
        XCTAssertTrue(result.warnings.contains(SalaryPayrollCalculationTimelineV2.periodMismatchWarning))
    }

    func testIncompleteRuleCoverageBlocksAlignmentEvenWhenContractIsComplete() throws {
        let contracts = try XCTUnwrap(
            SalaryEmploymentContractPeriodResolverV2.resolve(
                companyId: "company",
                periodStartEpochDay: 0,
                periodEndEpochDay: 30,
                sourceReliable: true,
                snapshots: [contract("c1", from: 0, to: nil, rate: 12)]
            )
        )
        let rules = SalaryConventionCoverageV2(
            companyId: "company",
            idcc: "0292",
            periodStartEpochDay: 0,
            periodEndEpochDay: 30,
            segments: [
                ruleSegment("r1", start: 0, end: 10),
                ruleSegment("r2", start: 12, end: 30)
            ],
            sourceReliable: true,
            fullyCovered: false,
            warnings: [SalaryConventionCoverageResolverV2.coverageWarning]
        )

        let result = SalaryPayrollCalculationTimelineV2.align(contracts: contracts, rules: rules)

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.slices.isEmpty)
        XCTAssertTrue(result.warnings.contains(SalaryPayrollCalculationTimelineV2.incompleteWarning))
        XCTAssertTrue(result.warnings.contains(SalaryConventionCoverageResolverV2.coverageWarning))
    }

    func testUnreliableRuleSourceBlocksAllSlices() throws {
        let contracts = try XCTUnwrap(
            SalaryEmploymentContractPeriodResolverV2.resolve(
                companyId: "company",
                periodStartEpochDay: 0,
                periodEndEpochDay: 30,
                sourceReliable: true,
                snapshots: [contract("c1", from: 0, to: nil, rate: 12)]
            )
        )
        let rules = SalaryConventionCoverageV2(
            companyId: "company",
            idcc: "0292",
            periodStartEpochDay: 0,
            periodEndEpochDay: 30,
            segments: [],
            sourceReliable: false,
            fullyCovered: false,
            warnings: [SalaryConventionCoverageResolverV2.ruleStoreWarning]
        )

        let result = SalaryPayrollCalculationTimelineV2.align(contracts: contracts, rules: rules)

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.slices.isEmpty)
        XCTAssertTrue(result.warnings.contains(SalaryPayrollCalculationTimelineV2.unreliableWarning))
    }

    private struct SliceExpectation: Equatable {
        let start: Int64
        let end: Int64
        let contractVersion: String
        let ruleVersion: String
    }

    private func coverage(
        start: Int64,
        end: Int64,
        segments: [SalaryConventionCoverageSegmentV2]
    ) -> SalaryConventionCoverageV2 {
        SalaryConventionCoverageV2(
            companyId: "company",
            idcc: "0292",
            periodStartEpochDay: start,
            periodEndEpochDay: end,
            segments: segments,
            sourceReliable: true,
            fullyCovered: true,
            warnings: segments.count > 1 ? [SalaryConventionCoverageResolverV2.multipleVersionsWarning] : []
        )
    }

    private func contract(
        _ version: String,
        from: Int64,
        to: Int64?,
        rate: Double
    ) -> SalaryEmploymentContractSnapshotV2 {
        SalaryEmploymentContractSnapshotV2(
            versionId: version,
            sourceId: "contract-test",
            effectiveFromEpochDay: from,
            effectiveToEpochDay: to,
            contract: ContractV2(
                id: version,
                employerId: "company",
                type: .fullTime,
                contractualWeeklyMinutes: 35 * 60,
                grossHourlyRate: rate,
                hireDateEpochDay: 0
            ),
            checkedAtMs: 1,
            note: nil
        )
    }

    private func ruleSegment(
        _ version: String,
        start: Int64,
        end: Int64
    ) -> SalaryConventionCoverageSegmentV2 {
        SalaryConventionCoverageSegmentV2(
            startEpochDay: start,
            endEpochDay: end,
            snapshot: SalaryConventionRuleSnapshotV2(
                idcc: "0292",
                versionId: version,
                sourceId: "rule-test",
                effectiveFromEpochDay: start,
                effectiveToEpochDay: end,
                rules: PayrollRulesV2(weeklyRegularMinutes: 35 * 60),
                checkedAtMs: 1,
                note: nil
            )
        )
    }
}
