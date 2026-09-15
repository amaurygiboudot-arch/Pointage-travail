import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class OvertimeCoverageV2Tests: XCTestCase {
    private let limit = 35 * 60

    func testNoOvertimeNeedsNoTier() {
        XCTAssertTrue(
            OvertimeCoverageV2.isFullyCovered(
                regularLimitMinutes: limit,
                paidMinutes: limit,
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
}
