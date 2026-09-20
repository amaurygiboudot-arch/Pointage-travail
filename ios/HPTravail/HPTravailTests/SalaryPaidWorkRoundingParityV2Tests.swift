import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryPaidWorkRoundingParityV2Tests: XCTestCase {
    private func calendar() -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    private func startDate(_ calendar: Calendar) -> Date {
        calendar.date(from: DateComponents(
            calendar: calendar,
            timeZone: calendar.timeZone,
            year: 2026,
            month: 9,
            day: 24,
            hour: 8,
            minute: 0,
            second: 0
        ))!
    }

    func testSubMinuteSessionsRemainEmittedButAreTruncatedPerSliceLikeAndroid() {
        let calendar = calendar()
        let start = startDate(calendar)
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
        XCTAssertEqual(result.weeks.count, 1)
        XCTAssertEqual(result.weeks.first?.paidMinutes, 0)
        XCTAssertEqual(result.totalPaidMinutes, 0)
    }

    func testFullyUnpaidReliableSliceIsNotEmittedLikeAndroid() {
        let calendar = calendar()
        let start = startDate(calendar)
        let end = start.addingTimeInterval(30 * 60)

        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [SalarySessionFactV2(
                id: "fully-unpaid",
                entry: start,
                exit: end,
                employerId: "company-a",
                pauses: [PaidPauseFactV2(start: start, end: end, paid: false)]
            )],
            employerId: "company-a",
            period: YearMonthV2(year: 2026, month: 9)!,
            calendar: calendar
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.completedSessionCount, 1)
        XCTAssertEqual(result.weeks, [])
        XCTAssertEqual(result.totalPaidMinutes, 0)
    }
}
