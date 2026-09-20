import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryPaidWorkRoundingParityV2Tests: XCTestCase {
    func testSubMinuteSessionsAreTruncatedPerSliceLikeAndroid() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let start = calendar.date(from: DateComponents(
            calendar: calendar,
            timeZone: calendar.timeZone,
            year: 2026,
            month: 9,
            day: 24,
            hour: 8,
            minute: 0,
            second: 0
        ))!
        let secondStart = start.addingTimeInterval(120)

        let sessions = [
            SalarySessionFactV2(
                id: "first",
                entry: start,
                exit: start.addingTimeInterval(59),
                employerId: "company-a",
                pauses: []
            ),
            SalarySessionFactV2(
                id: "second",
                entry: secondStart,
                exit: secondStart.addingTimeInterval(59),
                employerId: "company-a",
                pauses: []
            )
        ]

        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: sessions,
            employerId: "company-a",
            period: YearMonthV2(year: 2026, month: 9)!,
            calendar: calendar
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.completedSessionCount, 2)
        XCTAssertEqual(result.totalPaidMinutes, 0)
    }
}
