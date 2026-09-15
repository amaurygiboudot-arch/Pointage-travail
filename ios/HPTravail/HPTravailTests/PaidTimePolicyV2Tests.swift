import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class PaidTimePolicyV2Tests: XCTestCase {
    private let minute: TimeInterval = 60

    private func at(_ hour: Int, _ minute: Int = 0) -> Date {
        Date(timeIntervalSince1970: TimeInterval(hour * 3_600 + minute * 60))
    }

    func testExplicitUnpaidPauseIsDeductedInFullRegardlessOfStartHour() {
        let result = PaidTimePolicyV2.assess(
            sessionStart: at(6),
            sessionEnd: at(14),
            pauses: [PaidPauseFactV2(start: at(10), end: at(10, 30), paid: false)],
            until: at(14)
        )
        XCTAssertEqual(result.unpaidPauseDuration, 30 * minute, accuracy: 0.001)
        XCTAssertEqual(result.paidDuration, 7 * 3_600 + 30 * minute, accuracy: 0.001)
        XCTAssertTrue(result.reliable)
    }

    func testExplicitPaidPauseRemainsPaidRegardlessOfStartHour() {
        let result = PaidTimePolicyV2.assess(
            sessionStart: at(22),
            sessionEnd: at(30),
            pauses: [PaidPauseFactV2(start: at(26), end: at(27), paid: true)],
            until: at(30)
        )
        XCTAssertEqual(result.paidPauseDuration, 3_600, accuracy: 0.001)
        XCTAssertEqual(result.paidDuration, 8 * 3_600, accuracy: 0.001)
        XCTAssertTrue(result.reliable)
    }

    func testUnknownPauseStatusDoesNotInventClassification() {
        let result = PaidTimePolicyV2.assess(
            sessionStart: at(8),
            sessionEnd: at(16),
            pauses: [PaidPauseFactV2(start: at(12), end: at(13), paid: nil)],
            until: at(16)
        )
        XCTAssertEqual(result.unpaidPauseDuration, 0, accuracy: 0.001)
        XCTAssertEqual(result.paidPauseDuration, 0, accuracy: 0.001)
        XCTAssertEqual(result.unresolvedPauseCount, 1)
        XCTAssertFalse(result.reliable)
    }

    func testOverlappingUnpaidPausesAreDeductedOnlyOnce() {
        let result = PaidTimePolicyV2.assess(
            sessionStart: at(8),
            sessionEnd: at(16),
            pauses: [
                PaidPauseFactV2(start: at(12), end: at(12, 30), paid: false),
                PaidPauseFactV2(start: at(12, 15), end: at(12, 45), paid: false)
            ],
            until: at(16)
        )
        XCTAssertEqual(result.unpaidPauseDuration, 45 * minute, accuracy: 0.001)
        XCTAssertEqual(result.paidDuration, 8 * 3_600 - 45 * minute, accuracy: 0.001)
        XCTAssertTrue(result.reliable)
    }
}
