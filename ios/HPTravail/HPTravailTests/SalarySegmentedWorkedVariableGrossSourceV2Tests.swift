import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedWorkedVariableGrossSourceV2Tests: XCTestCase {
    func testFullTimeMondaySegmentsProduceOnlyVariableOvertime() throws {
        let contracts = try XCTUnwrap(
            contractResolution(
                start: 4,
                end: 17,
                snapshots: [
                    contract("c1", from: 4, to: 10, rate: 10, type: .fullTime, weekly: 35 * 60),
                    contract("c2", from: 11, to: nil, rate: 20, type: .fullTime, weekly: 35 * 60)
                ]
            )
        )
        let rules = coverage(
            start: 4,
            end: 17,
            segments: [ruleSegment("r1", start: 4, end: 17)]
        )
        let timeline = SalaryPayrollCalculationTimelineV2.align(
            contracts: contracts,
            rules: rules
        )

        let result = SalarySegmentedWorkedVariableGrossSourceV2.calculate(
            contracts: contracts,
            rules: rules,
            sliceEvidence: timeline.slices.enumerated().map { index, slice in
                evidence(
                    slice: slice,
                    weekYear: 1970,
                    weekOfYear: index + 2,
                    paidMinutes: 40 * 60
                )
            }
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.pieces.count, 2)
        XCTAssertEqual(result.pieces[0].variableGross, 62.5, accuracy: 0.0001)
        XCTAssertEqual(result.pieces[1].variableGross, 125.0, accuracy: 0.0001)
    }

    func testMaterialMidweekTransitionFailsClosed() throws {
        let contracts = try XCTUnwrap(
            contractResolution(
                start: 4,
                end: 17,
                snapshots: [
                    contract("c1", from: 4, to: 8, rate: 10, type: .fullTime, weekly: 35 * 60),
                    contract("c2", from: 9, to: nil, rate: 20, type: .fullTime, weekly: 35 * 60)
                ]
            )
        )
        let rules = coverage(
            start: 4,
            end: 17,
            segments: [ruleSegment("r1", start: 4, end: 17)]
        )
        let timeline = SalaryPayrollCalculationTimelineV2.align(
            contracts: contracts,
            rules: rules
        )

        let result = SalarySegmentedWorkedVariableGrossSourceV2.calculate(
            contracts: contracts,
            rules: rules,
            sliceEvidence: timeline.slices.enumerated().map { index, slice in
                evidence(
                    slice: slice,
                    weekYear: 1970,
                    weekOfYear: index + 2,
                    paidMinutes: 35 * 60
                )
            }
        )

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedWorkedVariableGrossSourceV2.weekContextWarning
            )
        )
    }

    func testSameWeekCannotBelongToTwoSlices() throws {
        let contracts = try XCTUnwrap(
            contractResolution(
                start: 4,
                end: 17,
                snapshots: [
                    contract("c1", from: 4, to: 10, rate: 10, type: .fullTime, weekly: 35 * 60),
                    contract("c2", from: 11, to: nil, rate: 20, type: .fullTime, weekly: 35 * 60)
                ]
            )
        )
        let rules = coverage(
            start: 4,
            end: 17,
            segments: [ruleSegment("r1", start: 4, end: 17)]
        )
        let timeline = SalaryPayrollCalculationTimelineV2.align(
            contracts: contracts,
            rules: rules
        )

        let result = SalarySegmentedWorkedVariableGrossSourceV2.calculate(
            contracts: contracts,
            rules: rules,
            sliceEvidence: timeline.slices.map {
                evidence(
                    slice: $0,
                    weekYear: 1970,
                    weekOfYear: 2,
                    paidMinutes: 35 * 60
                )
            }
        )

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedWorkedVariableGrossSourceV2.weekContextWarning
            )
        )
    }

    func testExplicitZeroVariableIsReliable() throws {
        let contracts = try XCTUnwrap(
            contractResolution(
                start: 4,
                end: 10,
                snapshots: [
                    contract("c1", from: 4, to: nil, rate: 10, type: .fullTime, weekly: 35 * 60)
                ]
            )
        )
        let rules = coverage(
            start: 4,
            end: 10,
            segments: [ruleSegment("r1", start: 4, end: 10)]
        )
        let slice = SalaryPayrollCalculationTimelineV2.align(
            contracts: contracts,
            rules: rules
        ).slices[0]

        let result = SalarySegmentedWorkedVariableGrossSourceV2.calculate(
            contracts: contracts,
            rules: rules,
            sliceEvidence: [
                evidence(
                    slice: slice,
                    weekYear: 1970,
                    weekOfYear: 2,
                    paidMinutes: 35 * 60
                )
            ]
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.pieces[0].variableGross, 0, accuracy: 0)
    }

    func testPartTimeComplementaryHoursRemainBlocked() throws {
        let contracts = try XCTUnwrap(
            contractResolution(
                start: 4,
                end: 10,
                snapshots: [
                    contract("c1", from: 4, to: nil, rate: 12, type: .partTime, weekly: 20 * 60)
                ]
            )
        )
        let rules = coverage(
            start: 4,
            end: 10,
            segments: [
                ruleSegment(
                    "r1",
                    start: 4,
                    end: 10,
                    weeklyRegularMinutes: 20 * 60
                )
            ]
        )
        let slice = SalaryPayrollCalculationTimelineV2.align(
            contracts: contracts,
            rules: rules
        ).slices[0]

        let result = SalarySegmentedWorkedVariableGrossSourceV2.calculate(
            contracts: contracts,
            rules: rules,
            sliceEvidence: [
                evidence(
                    slice: slice,
                    weekYear: 1970,
                    weekOfYear: 2,
                    paidMinutes: 22 * 60
                )
            ]
        )

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedWorkedVariableGrossSourceV2.partTimeComplementaryWarning
            )
        )
    }

    func testUnreliableFullWeekContextNeverBecomesZero() throws {
        let contracts = try XCTUnwrap(
            contractResolution(
                start: 4,
                end: 10,
                snapshots: [
                    contract("c1", from: 4, to: nil, rate: 10, type: .fullTime, weekly: 35 * 60)
                ]
            )
        )
        let rules = coverage(
            start: 4,
            end: 10,
            segments: [ruleSegment("r1", start: 4, end: 10)]
        )
        let slice = SalaryPayrollCalculationTimelineV2.align(
            contracts: contracts,
            rules: rules
        ).slices[0]

        let result = SalarySegmentedWorkedVariableGrossSourceV2.calculate(
            contracts: contracts,
            rules: rules,
            sliceEvidence: [
                SalarySegmentedPayrollSliceEvidenceV2(
                    startEpochDay: slice.startEpochDay,
                    endEpochDay: slice.endEpochDay,
                    contractVersionId: slice.contractVersionId,
                    ruleVersionId: slice.ruleVersionId,
                    weeks: [
                        SalarySegmentedPayrollWeekEvidenceV2(
                            yearForWeekOfYear: 1970,
                            weekOfYear: 2,
                            week: PayrollWeekV2(paidMinutes: 35 * 60),
                            fullWeekContextReliable: false
                        )
                    ],
                    evidence: .fullyConfirmed
                )
            ]
        )

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedWorkedVariableGrossSourceV2.evidenceWarning
            )
        )
    }

    func testIdenticalWeekWithinOneSliceFailsClosed() throws {
        let week = weekEvidence(2, paidMinutes: 40 * 60)
        assertDuplicateWeekBlocked(try calculateSingleSliceWeeks([week, week]))
    }

    func testConflictingWeekWithinOneSliceFailsClosed() throws {
        assertDuplicateWeekBlocked(
            try calculateSingleSliceWeeks([
                weekEvidence(2, paidMinutes: 40 * 60),
                weekEvidence(2, paidMinutes: 36 * 60)
            ])
        )
    }

    func testNonAdjacentDuplicateWeekWithinOneSliceFailsClosed() throws {
        let week = weekEvidence(2, paidMinutes: 40 * 60)
        assertDuplicateWeekBlocked(
            try calculateSingleSliceWeeks([week, weekEvidence(3, paidMinutes: 36 * 60), week])
        )
    }

    func testDuplicateZeroVariableWeekDoesNotBecomeReliableZero() throws {
        let week = weekEvidence(2, paidMinutes: 35 * 60)
        assertDuplicateWeekBlocked(try calculateSingleSliceWeeks([week, week]))
    }

    func testDistinctWeeksWithinOneSliceRemainReliable() throws {
        let result = try calculateSingleSliceWeeks([
            weekEvidence(2, paidMinutes: 40 * 60),
            weekEvidence(3, paidMinutes: 40 * 60)
        ])

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.pieces.count, 1)
        let piece = try XCTUnwrap(result.pieces.first)
        XCTAssertEqual(piece.variableGross, 125.0, accuracy: 0.0001)
        XCTAssertFalse(result.warnings.contains(SalarySegmentedWorkedVariableGrossSourceV2.duplicateWeekWarning))
    }

    func testNegativePaidMinutesFailClosedBeforeFullTimeCalculation() throws {
        assertInvalidPaidTimeBlocked(
            try calculateSingleSliceWeeks([weekEvidence(2, paidMinutes: -1), weekEvidence(3, paidMinutes: 0)])
        )
    }

    func testMinimumPaidMinutesFailClosedBeforeFullTimeCalculation() throws {
        assertInvalidPaidTimeBlocked(
            try calculateSingleSliceWeeks([weekEvidence(2, paidMinutes: Int.min), weekEvidence(3, paidMinutes: 0)])
        )
    }

    func testNegativePaidMinutesAfterValidWeekDoNotPublishPartialGross() throws {
        assertInvalidPaidTimeBlocked(
            try calculateSingleSliceWeeks([weekEvidence(2, paidMinutes: 40 * 60), weekEvidence(3, paidMinutes: -1)])
        )
    }

    func testNegativePaidMinutesInLaterSliceDiscardEarlierVariable() throws {
        let contracts = try XCTUnwrap(
            contractResolution(
                start: 4,
                end: 17,
                snapshots: [
                    contract("c1", from: 4, to: 10, rate: 10, type: .fullTime, weekly: 35 * 60),
                    contract("c2", from: 11, to: nil, rate: 20, type: .fullTime, weekly: 35 * 60)
                ]
            )
        )
        let rules = coverage(start: 4, end: 17, segments: [ruleSegment("r1", start: 4, end: 17)])
        let timeline = SalaryPayrollCalculationTimelineV2.align(contracts: contracts, rules: rules)
        XCTAssertEqual(timeline.slices.count, 2)
        let result = SalarySegmentedWorkedVariableGrossSourceV2.calculate(
            contracts: contracts,
            rules: rules,
            sliceEvidence: timeline.slices.enumerated().map { index, slice in
                SalarySegmentedPayrollSliceEvidenceV2(
                    startEpochDay: slice.startEpochDay,
                    endEpochDay: slice.endEpochDay,
                    contractVersionId: slice.contractVersionId,
                    ruleVersionId: slice.ruleVersionId,
                    weeks: [weekEvidence(index + 2, paidMinutes: index == 0 ? 40 * 60 : -1)],
                    evidence: .fullyConfirmed,
                    warnings: index == 1 ? ["preuve-import"] : []
                )
            }
        )
        assertInvalidPaidTimeBlocked(result)
        XCTAssertTrue(result.warnings.contains("preuve-import"))
    }

    func testPartTimeNegativePaidMinutesFailClosed() throws {
        assertInvalidPaidTimeBlocked(
            try calculateSingleSliceWeeks(
                [weekEvidence(2, paidMinutes: -1), weekEvidence(3, paidMinutes: 0)],
                contractType: .partTime,
                contractualWeeklyMinutes: 20 * 60
            )
        )
    }

    func testExplicitZeroPaidTimeRemainsReliable() throws {
        let result = try calculateSingleSliceWeeks([weekEvidence(2, paidMinutes: 0), weekEvidence(3, paidMinutes: 0)])
        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.pieces.count, 1)
        let piece = try XCTUnwrap(result.pieces.first)
        XCTAssertEqual(piece.variableGross, 0, accuracy: 0)
        XCTAssertFalse(result.warnings.contains(SalarySegmentedWorkedVariableGrossSourceV2.invalidPaidTimeWarning))
    }

    func testPartTimeExplicitZeroPaidTimeRemainsReliable() throws {
        let result = try calculateSingleSliceWeeks(
            [weekEvidence(2, paidMinutes: 0), weekEvidence(3, paidMinutes: 0)],
            contractType: .partTime,
            contractualWeeklyMinutes: 20 * 60
        )
        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.pieces.count, 1)
        let piece = try XCTUnwrap(result.pieces.first)
        XCTAssertEqual(piece.variableGross, 0, accuracy: 0)
        XCTAssertFalse(result.warnings.contains(SalarySegmentedWorkedVariableGrossSourceV2.invalidPaidTimeWarning))
    }

    func testEmptyFullTimeWeeksNeverBecomeReliableZero() throws {
        assertMissingWeeksBlocked(try calculateSingleSliceWeeks([]))
    }

    func testEmptyPartTimeWeeksNeverBecomeReliableZero() throws {
        assertMissingWeeksBlocked(
            try calculateSingleSliceWeeks(
                [],
                contractType: .partTime,
                contractualWeeklyMinutes: 20 * 60
            )
        )
    }

    func testEmptyEarlierSliceBlocksOtherwiseValidPeriod() throws {
        assertMissingWeeksBlocked(try calculateWithEmptySlice(emptyIndex: 0))
    }

    func testEmptyLaterSliceDiscardsEarlierVariableAndPreservesWarnings() throws {
        assertMissingWeeksBlocked(try calculateWithEmptySlice(emptyIndex: 1))
    }

    private func calculateWithEmptySlice(
        emptyIndex: Int
    ) throws -> SalarySegmentedWorkedVariableGrossSourceResultV2 {
        let contracts = try XCTUnwrap(
            contractResolution(
                start: 4,
                end: 17,
                snapshots: [
                    contract("c1", from: 4, to: 10, rate: 10, type: .fullTime, weekly: 35 * 60),
                    contract("c2", from: 11, to: nil, rate: 20, type: .fullTime, weekly: 35 * 60)
                ]
            )
        )
        let rules = coverage(start: 4, end: 17, segments: [ruleSegment("r1", start: 4, end: 17)])
        let timeline = SalaryPayrollCalculationTimelineV2.align(contracts: contracts, rules: rules)
        XCTAssertEqual(timeline.slices.count, 2)
        let result = SalarySegmentedWorkedVariableGrossSourceV2.calculate(
            contracts: contracts,
            rules: rules,
            sliceEvidence: timeline.slices.enumerated().map { index, slice in
                SalarySegmentedPayrollSliceEvidenceV2(
                    startEpochDay: slice.startEpochDay,
                    endEpochDay: slice.endEpochDay,
                    contractVersionId: slice.contractVersionId,
                    ruleVersionId: slice.ruleVersionId,
                    weeks: index == emptyIndex ? [] : [weekEvidence(index + 2, paidMinutes: 40 * 60)],
                    evidence: .fullyConfirmed,
                    warnings: index == emptyIndex ? ["preuve-vide"] : []
                )
            }
        )
        XCTAssertTrue(result.warnings.contains("preuve-vide"))
        return result
    }

    private func assertMissingWeeksBlocked(
        _ result: SalarySegmentedWorkedVariableGrossSourceResultV2,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertFalse(result.reliable, file: file, line: line)
        XCTAssertTrue(result.pieces.isEmpty, file: file, line: line)
        XCTAssertTrue(
            result.warnings.contains(SalarySegmentedWorkedVariableGrossSourceV2.missingWeeksWarning),
            file: file,
            line: line
        )
    }

    private func assertInvalidPaidTimeBlocked(
        _ result: SalarySegmentedWorkedVariableGrossSourceResultV2,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertFalse(result.reliable, file: file, line: line)
        XCTAssertTrue(result.pieces.isEmpty, file: file, line: line)
        XCTAssertTrue(
            result.warnings.contains(SalarySegmentedWorkedVariableGrossSourceV2.invalidPaidTimeWarning),
            file: file,
            line: line
        )
    }

    private func assertDuplicateWeekBlocked(
        _ result: SalarySegmentedWorkedVariableGrossSourceResultV2,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertFalse(result.reliable, file: file, line: line)
        XCTAssertTrue(result.pieces.isEmpty, file: file, line: line)
        XCTAssertTrue(
            result.warnings.contains(SalarySegmentedWorkedVariableGrossSourceV2.duplicateWeekWarning),
            file: file,
            line: line
        )
    }

    private func calculateSingleSliceWeeks(
        _ weeks: [SalarySegmentedPayrollWeekEvidenceV2],
        contractType: ContractTypeV2 = .fullTime,
        contractualWeeklyMinutes: Int = 35 * 60
    ) throws -> SalarySegmentedWorkedVariableGrossSourceResultV2 {
        let contracts = try XCTUnwrap(
            contractResolution(
                start: 4,
                end: 17,
                snapshots: [
                    contract("c1", from: 4, to: nil, rate: 10, type: contractType, weekly: contractualWeeklyMinutes)
                ]
            )
        )
        let rules = coverage(
            start: 4,
            end: 17,
            segments: [ruleSegment("r1", start: 4, end: 17, weeklyRegularMinutes: contractualWeeklyMinutes)]
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

    private func evidence(
        slice: SalaryPayrollCalculationSliceV2,
        weekYear: Int,
        weekOfYear: Int,
        paidMinutes: Int
    ) -> SalarySegmentedPayrollSliceEvidenceV2 {
        SalarySegmentedPayrollSliceEvidenceV2(
            startEpochDay: slice.startEpochDay,
            endEpochDay: slice.endEpochDay,
            contractVersionId: slice.contractVersionId,
            ruleVersionId: slice.ruleVersionId,
            weeks: [
                SalarySegmentedPayrollWeekEvidenceV2(
                    yearForWeekOfYear: weekYear,
                    weekOfYear: weekOfYear,
                    week: PayrollWeekV2(paidMinutes: paidMinutes),
                    fullWeekContextReliable: true
                )
            ],
            evidence: .fullyConfirmed
        )
    }
}
