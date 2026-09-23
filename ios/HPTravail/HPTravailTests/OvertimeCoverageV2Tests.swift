import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class OvertimeCoverageV2Tests: XCTestCase {
    private let limit = 35 * 60

    private func genericContract(rate: Double = 10) -> ContractV2 {
        ContractV2(
            id: "generic-hourly",
            employerId: "company-a",
            type: .other,
            contractualWeeklyMinutes: limit,
            grossHourlyRate: rate,
            hireDateEpochDay: nil
        )
    }

    func testNoOvertimeNeedsNoTier() {
        XCTAssertTrue(
            OvertimeCoverageV2.isFullyCovered(
                regularLimitMinutes: limit,
                paidMinutes: limit,
                tiers: []
            )
        )
    }

    func testNegativePaidMinutesAreNeverConsideredCovered() {
        XCTAssertFalse(
            OvertimeCoverageV2.isFullyCovered(
                regularLimitMinutes: limit,
                paidMinutes: -1,
                tiers: []
            )
        )
    }

    func testOvertimeWithoutTierIsNotCovered() {
        XCTAssertFalse(
            OvertimeCoverageV2.isFullyCovered(
                regularLimitMinutes: limit,
                paidMinutes: 40 * 60,
                tiers: []
            )
        )
    }

    func testContinuousConfirmedTierCoversAllOvertime() {
        XCTAssertTrue(
            OvertimeCoverageV2.isFullyCovered(
                regularLimitMinutes: limit,
                paidMinutes: 40 * 60,
                tiers: [OvertimeTierV2(fromMinutes: limit, toMinutes: nil, multiplier: 1.25)]
            )
        )
    }

    func testGapBetweenTiersLeavesGrossUnresolved() {
        XCTAssertFalse(
            OvertimeCoverageV2.isFullyCovered(
                regularLimitMinutes: limit,
                paidMinutes: 45 * 60,
                tiers: [
                    OvertimeTierV2(fromMinutes: limit, toMinutes: 40 * 60, multiplier: 1.25),
                    OvertimeTierV2(fromMinutes: 41 * 60, toMinutes: nil, multiplier: 1.50)
                ]
            )
        )
    }

    func testOverlappingTiersAreAmbiguousAndNotReliable() {
        XCTAssertFalse(
            OvertimeCoverageV2.isFullyCovered(
                regularLimitMinutes: limit,
                paidMinutes: 45 * 60,
                tiers: [
                    OvertimeTierV2(fromMinutes: limit, toMinutes: 43 * 60, multiplier: 1.25),
                    OvertimeTierV2(fromMinutes: 42 * 60, toMinutes: nil, multiplier: 1.50)
                ]
            )
        )
    }

    func testOpenEndedTierCannotHideLaterOverlap() {
        XCTAssertFalse(
            OvertimeCoverageV2.isFullyCovered(
                regularLimitMinutes: limit,
                paidMinutes: 45 * 60,
                tiers: [
                    OvertimeTierV2(fromMinutes: limit, toMinutes: nil, multiplier: 1.25),
                    OvertimeTierV2(fromMinutes: 43 * 60, toMinutes: nil, multiplier: 1.50)
                ]
            )
        )
    }

    func testAdjacentTiersCoverWithoutGapOrOverlap() {
        XCTAssertTrue(
            OvertimeCoverageV2.isFullyCovered(
                regularLimitMinutes: limit,
                paidMinutes: 45 * 60,
                tiers: [
                    OvertimeTierV2(fromMinutes: limit, toMinutes: 43 * 60, multiplier: 1.25),
                    OvertimeTierV2(fromMinutes: 43 * 60, toMinutes: nil, multiplier: 1.50)
                ]
            )
        )
    }

    func testEveryWeekMustBeCovered() {
        XCTAssertFalse(
            OvertimeCoverageV2.areWeeksFullyCovered(
                regularLimitMinutes: limit,
                paidWeeks: [34 * 60, 40 * 60, 45 * 60],
                tiers: [OvertimeTierV2(fromMinutes: limit, toMinutes: 43 * 60, multiplier: 1.25)]
            )
        )
    }

    func testPayrollWithoutOvertimeKeepsGrossReliableWithoutTier() throws {
        let result = try PayrollEngineV2.calculate(
            contract: genericContract(),
            weeks: [PayrollWeekV2(paidMinutes: limit)],
            rules: PayrollRulesV2(weeklyRegularMinutes: limit)
        ,
            evidence: .fullyConfirmed
        )

        XCTAssertTrue(result.grossReliable)
        XCTAssertEqual(result.overtimeGross, 0, accuracy: 0.001)
    }

    func testPayrollWithUncoveredOvertimeBecomesUnreliableWithoutInventingRate() throws {
        let result = try PayrollEngineV2.calculate(
            contract: genericContract(),
            weeks: [PayrollWeekV2(paidMinutes: 40 * 60)],
            rules: PayrollRulesV2(weeklyRegularMinutes: limit)
        ,
            evidence: .fullyConfirmed
        )

        XCTAssertFalse(result.grossReliable)
        XCTAssertEqual(result.overtimeGross, 0, accuracy: 0.001)
        XCTAssertTrue(result.traces.contains { $0.contains("brut reste à confirmer") })
    }

    func testPayrollWithContinuousConfirmedTierRemainsReliable() throws {
        let result = try PayrollEngineV2.calculate(
            contract: genericContract(),
            weeks: [PayrollWeekV2(paidMinutes: 40 * 60)],
            rules: PayrollRulesV2(
                weeklyRegularMinutes: limit,
                overtimeTiers: [OvertimeTierV2(fromMinutes: limit, toMinutes: nil, multiplier: 1.25)]
            )
        ,
            evidence: .fullyConfirmed
        )

        XCTAssertTrue(result.grossReliable)
        XCTAssertEqual(result.overtimeGross, 62.5, accuracy: 0.001)
    }

    func testPayrollWithGapKeepsKnownAmountButMarksGrossUnreliable() throws {
        let result = try PayrollEngineV2.calculate(
            contract: genericContract(),
            weeks: [PayrollWeekV2(paidMinutes: 45 * 60)],
            rules: PayrollRulesV2(
                weeklyRegularMinutes: limit,
                overtimeTiers: [OvertimeTierV2(fromMinutes: limit, toMinutes: 40 * 60, multiplier: 1.25)]
            )
        ,
            evidence: .fullyConfirmed
        )

        XCTAssertFalse(result.grossReliable)
        XCTAssertEqual(result.overtimeGross, 62.5, accuracy: 0.001)
    }
}
