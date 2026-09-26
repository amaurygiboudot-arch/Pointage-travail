import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedWorkedGrossProductionBridgeV2Tests: XCTestCase {
    func testCoverageExpandsPeriodToWholeIsoWeeks() {
        let start = epochDay(2026, 9, 1)
        let end = epochDay(2026, 9, 30)

        let bounds = SalarySegmentedWorkedGrossProductionBridgeV2.coverageBounds(
            start: start,
            end: end
        )

        XCTAssertEqual(bounds?.start, epochDay(2026, 8, 31))
        XCTAssertEqual(bounds?.end, epochDay(2026, 10, 4))
    }

    func testInvertedPeriodIsRejected() {
        XCTAssertNil(
            SalarySegmentedWorkedGrossProductionBridgeV2.coverageBounds(
                start: 10,
                end: 9
            )
        )
    }

    func testDetailedBlockedResultKeepsWarningsAndPublishesNoAmounts() {
        let result = SalarySegmentedWorkedGrossProductionBridgeV2.blockedDetailed(
            ["preuve manquante", "preuve manquante", "règle absente"]
        )

        XCTAssertFalse(result.reliable)
        XCTAssertFalse(result.evidence.reliable)
        XCTAssertFalse(result.variables.reliable)
        XCTAssertFalse(result.base.reliable)
        XCTAssertFalse(result.assembly.reliable)
        XCTAssertNil(result.base.baseGross)
        XCTAssertNil(result.assembly.variableGross)
        XCTAssertNil(result.workedGross)
        XCTAssertTrue(result.evidence.slices.isEmpty)
        XCTAssertTrue(result.variables.pieces.isEmpty)
        XCTAssertEqual(result.warnings, ["preuve manquante", "règle absente"])
    }

    private func epochDay(_ year: Int, _ month: Int, _ day: Int) -> Int64 {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = calendar.date(
            from: DateComponents(year: year, month: month, day: day)
        )!
        return Int64(floor(date.timeIntervalSince1970 / 86_400))
    }
}
