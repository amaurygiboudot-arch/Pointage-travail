import Foundation
import XCTest
@testable import RuntimeV2Contract

final class ManualSessionPolicyV2Tests: XCTestCase {
    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(identifier: "Europe/Paris")!
        return value
    }

    private func date(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(
            year: year,
            month: month,
            day: day,
            hour: hour,
            minute: minute
        ))!
    }

    func testOvernightRangeEndsOnNextCalendarDay() throws {
        let range = try XCTUnwrap(ManualSessionPolicyV2.normalizedRange(
            day: date(2026, 9, 17, 0),
            startTime: date(2026, 9, 17, 21),
            endTime: date(2026, 9, 17, 6),
            calendar: calendar
        ))

        XCTAssertEqual(range.entry, date(2026, 9, 17, 21))
        XCTAssertEqual(range.exit, date(2026, 9, 18, 6))
    }

    func testEqualTimesAreRejected() {
        let value = date(2026, 9, 17, 8)
        XCTAssertNil(ManualSessionPolicyV2.normalizedRange(
            day: value,
            startTime: value,
            endTime: value,
            calendar: calendar
        ))
    }

    func testNoCompanyRemainsExplicitAndDuplicateIsRejected() throws {
        let entry = date(2026, 9, 17, 8)
        let exit = date(2026, 9, 17, 16)
        let first = try XCTUnwrap(ManualSessionPolicyV2.appending(
            to: [],
            entry: entry,
            exit: exit,
            employerId: nil,
            placeLabel: " Atelier "
        ))

        XCTAssertNil(try XCTUnwrap(first.first).employerId)
        XCTAssertEqual(try XCTUnwrap(first.first).placeLabel, "Atelier")
        XCTAssertNil(ManualSessionPolicyV2.appending(
            to: first,
            entry: entry,
            exit: exit,
            employerId: nil,
            placeLabel: "Autre libellé"
        ))
    }
}
