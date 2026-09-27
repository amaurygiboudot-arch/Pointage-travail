import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

/// Calendar identity regressions; the existing B21 suite remains unchanged.
final class SalarySegmentedWorkedVariableGrossCoverageV2Tests: XCTestCase {
    func testMissingFirstWeekBlocksTheWholeSlice() throws {
        assertWeekCoverageBlocked(try calculateSingleSliceWeeks([weekEvidence(3, paidMinutes: 40 * 60)]))
    }

    func testMissingLastWeekBlocksTheWholeSlice() throws {
        assertWeekCoverageBlocked(try calculateSingleSliceWeeks([weekEvidence(2, paidMinutes: 40 * 60)]))
    }

    func testOutOfPeriodWeekWithMatchingCountIsRejected() throws {
        assertWeekCoverageBlocked(try calculateSingleSliceWeeks([
            weekEvidence(2, paidMinutes: 40 * 60), weekEvidence(4, paidMinutes: 40 * 60)
        ]))
    }

    func testWrongWeekYearWithMatchingCountIsRejected() throws {
        assertWeekCoverageBlocked(try calculateSingleSliceWeeks([
            SalarySegmentedPayrollWeekEvidenceV2(yearForWeekOfYear: 1971, weekOfYear: 2,
                week: PayrollWeekV2(paidMinutes: 40 * 60), fullWeekContextReliable: true),
            SalarySegmentedPayrollWeekEvidenceV2(yearForWeekOfYear: 1971, weekOfYear: 3,
                week: PayrollWeekV2(paidMinutes: 40 * 60), fullWeekContextReliable: true)
        ]))
    }

    func testInvalidWeekNumbersAreRejected() throws {
        for invalid in [0, 54, Int.min, Int.max] {
            assertWeekCoverageBlocked(try calculateSingleSliceWeeks([
                weekEvidence(invalid, paidMinutes: 40 * 60), weekEvidence(3, paidMinutes: 40 * 60)
            ]))
        }
    }

    func testMissingZeroWeekDoesNotProveReliableZero() throws {
        assertWeekCoverageBlocked(try calculateSingleSliceWeeks([weekEvidence(2, paidMinutes: 0)]))
    }

    func testPartTimeMissingWeekDoesNotProveReliableZero() throws {
        assertWeekCoverageBlocked(try calculateSingleSliceWeeks(
            [weekEvidence(2, paidMinutes: 0)], contractType: .partTime, contractualWeeklyMinutes: 20 * 60
        ))
    }

    func testReorderedCompleteWeeksKeepTheSameVariable() throws {
        let result = try calculateSingleSliceWeeks([
            weekEvidence(3, paidMinutes: 40 * 60), weekEvidence(2, paidMinutes: 40 * 60)
        ])
        XCTAssertTrue(result.reliable)
        let piece = try XCTUnwrap(result.pieces.first)
        XCTAssertEqual(result.pieces.count, 1)
        XCTAssertEqual(piece.variableGross, 125, accuracy: 0.0001)
    }

    func testISOWeekYearTransitionKeepsBothCompleteWeeks() throws {
        let result = try calculateSingleSliceWeeks([
            SalarySegmentedPayrollWeekEvidenceV2(yearForWeekOfYear: 2020, weekOfYear: 53,
                week: PayrollWeekV2(paidMinutes: 40 * 60), fullWeekContextReliable: true),
            SalarySegmentedPayrollWeekEvidenceV2(yearForWeekOfYear: 2021, weekOfYear: 1,
                week: PayrollWeekV2(paidMinutes: 40 * 60), fullWeekContextReliable: true)
        ], periodStart: 18624, periodEnd: 18637)
        XCTAssertTrue(result.reliable)
        let piece = try XCTUnwrap(result.pieces.first)
        XCTAssertEqual(result.pieces.count, 1)
        XCTAssertEqual(piece.variableGross, 125, accuracy: 0.0001)
    }

    func testInvalidLaterSliceDiscardsPreviouslyCalculatedMoneyAndKeepsWarnings() throws {
        let contracts = try XCTUnwrap(contractResolution(start: 4, end: 17, snapshots: [
            contract("c1", from: 4, to: 10, rate: 10, type: .fullTime, weekly: 35 * 60),
            contract("c2", from: 11, to: nil, rate: 20, type: .fullTime, weekly: 35 * 60)
        ]))
        let rules = coverage(start: 4, end: 17, segments: [ruleSegment("r1", start: 4, end: 17)])
        let timeline = SalaryPayrollCalculationTimelineV2.align(contracts: contracts, rules: rules)
        XCTAssertEqual(timeline.slices.count, 2)
        let result = SalarySegmentedWorkedVariableGrossSourceV2.calculate(
            contracts: contracts, rules: rules,
            sliceEvidence: timeline.slices.enumerated().map { index, slice in
                SalarySegmentedPayrollSliceEvidenceV2(
                    startEpochDay: slice.startEpochDay, endEpochDay: slice.endEpochDay,
                    contractVersionId: slice.contractVersionId, ruleVersionId: slice.ruleVersionId,
                    weeks: [weekEvidence(index == 0 ? 2 : 4, paidMinutes: 40 * 60)],
                    evidence: .fullyConfirmed,
                    warnings: index == 1 ? ["semaine-hors-periode"] : []
                )
            }
        )
        assertWeekCoverageBlocked(result)
        XCTAssertTrue(result.warnings.contains("semaine-hors-periode"))
    }

    private func assertWeekCoverageBlocked(
        _ result: SalarySegmentedWorkedVariableGrossSourceResultV2,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertFalse(result.reliable, file: file, line: line)
        XCTAssertTrue(result.pieces.isEmpty, file: file, line: line)
        XCTAssertTrue(result.warnings.contains(SalarySegmentedWorkedVariableGrossSourceV2.weekCoverageWarning),
            file: file, line: line)
    }

    private func calculateSingleSliceWeeks(
        _ weeks: [SalarySegmentedPayrollWeekEvidenceV2],
        contractType: ContractTypeV2 = .fullTime,
        contractualWeeklyMinutes: Int = 35 * 60,
        periodStart: Int64 = 4,
        periodEnd: Int64 = 17
    ) throws -> SalarySegmentedWorkedVariableGrossSourceResultV2 {
        let contracts = try XCTUnwrap(
            contractResolution(
                start: periodStart,
                end: periodEnd,
                snapshots: [
                    contract("c1", from: periodStart, to: nil, rate: 10, type: contractType, weekly: contractualWeeklyMinutes)
                ]
            )
        )
        let rules = coverage(
            start: periodStart,
            end: periodEnd,
            segments: [ruleSegment("r1", start: periodStart, end: periodEnd, weeklyRegularMinutes: contractualWeeklyMinutes)]
        )
        let timeline = SalaryPayrollCalculationTimelineV2.align(contracts: contracts, rules: rules)
        XCTAssertEqual(timeline.slices.count, 1)
        let slice = try XCTUnwrap(timeline.slices.first)
        return SalarySegmentedWorkedVariableGrossSourceV2.calculate(
            contracts: contracts,
            rules: rules,
            sliceEvidence: [
                SalarySegmentedPayrollSliceEvidenceV2(
                    startEpochDay: slice.startEpochDay,
                    endEpochDay: slice.endEpochDay,
                    contractVersionId: slice.contractVersionId,
                    ruleVersionId: slice.ruleVersionId,
                    weeks: weeks,
                    evidence: .fullyConfirmed
                )
            ]
        )
    }

    private func weekEvidence(
        _ weekOfYear: Int,
        paidMinutes: Int
    ) -> SalarySegmentedPayrollWeekEvidenceV2 {
        SalarySegmentedPayrollWeekEvidenceV2(
            yearForWeekOfYear: 1970,
            weekOfYear: weekOfYear,
            week: PayrollWeekV2(paidMinutes: paidMinutes),
            fullWeekContextReliable: true
        )
    }

    private func contractResolution(
        start: Int64,
        end: Int64,
        snapshots: [SalaryEmploymentContractSnapshotV2]
    ) -> SalaryEmploymentContractPeriodResolutionV2? {
        SalaryEmploymentContractPeriodResolverV2.resolve(
            companyId: "company",
            periodStartEpochDay: start,
            periodEndEpochDay: end,
            sourceReliable: true,
            snapshots: snapshots
        )
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
            warnings: []
        )
    }

    private func contract(
        _ version: String,
        from: Int64,
        to: Int64?,
        rate: Double,
        type: ContractTypeV2,
        weekly: Int
    ) -> SalaryEmploymentContractSnapshotV2 {
        SalaryEmploymentContractSnapshotV2(
            versionId: version,
            sourceId: "contract-test",
            effectiveFromEpochDay: from,
            effectiveToEpochDay: to,
            contract: ContractV2(
                id: version,
                employerId: "company",
                type: type,
                contractualWeeklyMinutes: weekly,
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
        end: Int64,
        weeklyRegularMinutes: Int = 35 * 60
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
                rules: PayrollRulesV2(
                    weeklyRegularMinutes: weeklyRegularMinutes,
                    overtimeTiers: [
                        OvertimeTierV2(
                            fromMinutes: 35 * 60,
                            toMinutes: 43 * 60,
                            multiplier: 1.25
                        ),
                        OvertimeTierV2(
                            fromMinutes: 43 * 60,
                            toMinutes: nil,
                            multiplier: 1.50
                        )
                    ]
                ),
                checkedAtMs: 1,
                note: nil
            )
        )
    }
}
