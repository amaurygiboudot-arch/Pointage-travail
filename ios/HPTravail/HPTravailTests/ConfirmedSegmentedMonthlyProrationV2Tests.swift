import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class ConfirmedSegmentedMonthlyProrationV2Tests: XCTestCase {
    func testRateChangeUsesOnlyConfirmedScheduledMinutes() throws {
        let segments = [
            segment("v1", start: 0, end: 14, contract: fullTime(rate: 10)),
            segment("v2", start: 15, end: 30, contract: fullTime(rate: 20))
        ]
        let rules = [
            "v1": PayrollRulesV2(weeklyRegularMinutes: 35 * 60),
            "v2": PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
        ]
        let result = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments: segments,
            rulesByVersionId: rules,
            proration: confirmed((segments[0], 4_200), (segments[1], 4_200))
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.pieces.count, 2)
        XCTAssertEqual(result.pieces[0].factor, 0.5, accuracy: 0.000001)
        XCTAssertEqual(result.pieces[1].factor, 0.5, accuracy: 0.000001)
        XCTAssertEqual(try XCTUnwrap(result.baseGross), 2275.0, accuracy: 0.0001)
    }

    func testMissingProrationNeverInventsCalendarRatio() {
        let only = segment("v1", start: 0, end: 30, contract: fullTime(rate: 14))
        let result = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments: [only],
            rulesByVersionId: ["v1": PayrollRulesV2(weeklyRegularMinutes: 35 * 60)],
            proration: nil
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.baseGross)
        XCTAssertTrue(result.warnings.contains(ConfirmedSegmentedMonthlyProrationCalculatorV2.missingProrationWarning))
    }

    func testConfirmedBasisMustCoverExactlyEveryVersion() {
        let segments = [
            segment("v1", start: 0, end: 14, contract: fullTime(rate: 10)),
            segment("v2", start: 15, end: 30, contract: fullTime(rate: 20))
        ]
        let result = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments: segments,
            rulesByVersionId: [
                "v1": PayrollRulesV2(weeklyRegularMinutes: 35 * 60),
                "v2": PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
            ],
            proration: confirmed((segments[0], 8_400))
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.baseGross)
        XCTAssertTrue(result.warnings.contains(ConfirmedSegmentedMonthlyProrationCalculatorV2.invalidProrationWarning))
    }

    func testConfirmedBoundsMustStillMatchContractSegments() {
        let segments = [
            segment("v1", start: 0, end: 14, contract: fullTime(rate: 10)),
            segment("v2", start: 15, end: 30, contract: fullTime(rate: 20))
        ]
        let stale = ConfirmedSegmentedMonthlyProrationV2(
            sourceId: "planning-confirme",
            checkedAtMs: 1,
            segments: [
                ConfirmedProrationSegmentV2(versionId: "v1", startEpochDay: 0, endEpochDay: 13, scheduledMinutes: 4_200),
                ConfirmedProrationSegmentV2(versionId: "v2", startEpochDay: 14, endEpochDay: 30, scheduledMinutes: 4_200)
            ]
        )
        let result = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments: segments,
            rulesByVersionId: [
                "v1": PayrollRulesV2(weeklyRegularMinutes: 35 * 60),
                "v2": PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
            ],
            proration: stale
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.baseGross)
        XCTAssertTrue(result.warnings.contains(ConfirmedSegmentedMonthlyProrationCalculatorV2.invalidProrationWarning))
    }

    func testGapBetweenContractSegmentsBlocksProration() {
        let segments = [
            segment("v1", start: 0, end: 10, contract: fullTime(rate: 10)),
            segment("v2", start: 12, end: 30, contract: fullTime(rate: 20))
        ]
        let result = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments: segments,
            rulesByVersionId: [
                "v1": PayrollRulesV2(weeklyRegularMinutes: 35 * 60),
                "v2": PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
            ],
            proration: confirmed((segments[0], 4_200), (segments[1], 4_200))
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.baseGross)
        XCTAssertTrue(result.warnings.contains(ConfirmedSegmentedMonthlyProrationCalculatorV2.invalidProrationWarning))
    }

    func test39HourFullTimeRequiresConfirmedStructuralTiers() throws {
        let contract = fullTime(rate: 10, weeklyMinutes: 39 * 60)
        let only = segment("v1", start: 0, end: 30, contract: contract)

        let blocked = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments: [only],
            rulesByVersionId: ["v1": PayrollRulesV2(weeklyRegularMinutes: 35 * 60)],
            proration: confirmed((only, 8_400))
        )
        XCTAssertFalse(blocked.reliable)
        XCTAssertNil(blocked.baseGross)

        let calculated = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments: [only],
            rulesByVersionId: [
                "v1": PayrollRulesV2(
                    weeklyRegularMinutes: 35 * 60,
                    overtimeTiers: [OvertimeTierV2(fromMinutes: 35 * 60, toMinutes: 43 * 60, multiplier: 1.25)]
                )
            ],
            proration: confirmed((only, 8_400))
        )
        XCTAssertTrue(calculated.reliable)
        XCTAssertEqual(try XCTUnwrap(calculated.baseGross), 1733.333333, accuracy: 0.0001)
    }

    func testPartTimeUsesOnlyConfirmedWeeklyDurationAndRate() throws {
        let contract = ContractV2(
            id: "part",
            employerId: "company",
            type: .partTime,
            contractualWeeklyMinutes: 20 * 60,
            grossHourlyRate: 12,
            hireDateEpochDay: 0
        )
        let only = segment("v1", start: 0, end: 30, contract: contract)
        let result = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments: [only],
            rulesByVersionId: [:],
            proration: confirmed((only, 4_800))
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(try XCTUnwrap(result.baseGross), 1040.0, accuracy: 0.0001)
    }

    func testForfaitIsNeverProratedByScheduledMinutesWithoutSpecificRule() {
        let contract = ContractV2(
            id: "c1",
            employerId: "company",
            type: .forfaitDays,
            contractualWeeklyMinutes: nil,
            grossHourlyRate: nil,
            hireDateEpochDay: 0,
            forfaitAnnualDays: 218,
            monthlyGrossSalary: 3_000
        )
        let only = segment("v1", start: 0, end: 30, contract: contract)
        let result = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments: [only],
            rulesByVersionId: [:],
            proration: confirmed((only, 8_400))
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.baseGross)
        XCTAssertTrue(result.warnings.contains(ConfirmedSegmentedMonthlyProrationCalculatorV2.unsupportedContractWarning))
    }

    func testConfirmedZeroMinuteSegmentCanHaveZeroFactor() throws {
        let segments = [
            segment("v1", start: 0, end: 1, contract: fullTime(rate: 10)),
            segment("v2", start: 2, end: 30, contract: fullTime(rate: 20))
        ]
        let result = ConfirmedSegmentedMonthlyProrationCalculatorV2.calculate(
            segments: segments,
            rulesByVersionId: [
                "v1": PayrollRulesV2(weeklyRegularMinutes: 35 * 60),
                "v2": PayrollRulesV2(weeklyRegularMinutes: 35 * 60)
            ],
            proration: confirmed((segments[0], 0), (segments[1], 8_400))
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.pieces[0].factor, 0, accuracy: 0)
        XCTAssertEqual(try XCTUnwrap(result.baseGross), 3033.333333, accuracy: 0.0001)
    }

    private func confirmed(
        _ values: (SalaryEmploymentContractCoverageSegmentV2, Int)...
    ) -> ConfirmedSegmentedMonthlyProrationV2 {
        ConfirmedSegmentedMonthlyProrationV2(
            sourceId: "planning-confirme",
            checkedAtMs: 1,
            segments: values.map { segment, minutes in
                ConfirmedProrationSegmentV2(
                    versionId: segment.snapshot.versionId,
                    startEpochDay: segment.startEpochDay,
                    endEpochDay: segment.endEpochDay,
                    scheduledMinutes: minutes
                )
            }
        )
    }

    private func segment(
        _ versionId: String,
        start: Int64,
        end: Int64,
        contract: ContractV2
    ) -> SalaryEmploymentContractCoverageSegmentV2 {
        SalaryEmploymentContractCoverageSegmentV2(
            startEpochDay: start,
            endEpochDay: end,
            snapshot: SalaryEmploymentContractSnapshotV2(
                versionId: versionId,
                sourceId: "test",
                effectiveFromEpochDay: start,
                effectiveToEpochDay: end,
                contract: contract,
                checkedAtMs: 1,
                note: nil
            )
        )
    }

    private func fullTime(rate: Double, weeklyMinutes: Int = 35 * 60) -> ContractV2 {
        ContractV2(
            id: "contract-\(rate)-\(weeklyMinutes)",
            employerId: "company",
            type: .fullTime,
            contractualWeeklyMinutes: weeklyMinutes,
            grossHourlyRate: rate,
            hireDateEpochDay: 0
        )
    }
}
