import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryPremiumTimePolicyV2Tests: XCTestCase {
    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(identifier: "Europe/Paris")!
        value.locale = Locale(identifier: "fr_FR")
        return value
    }

    func testNightOverlapUsesOnlyConfirmedPaidTime() {
        let session = fact(
            entry: localDate(2026, 1, 10, 21, 0),
            exit: localDate(2026, 1, 11, 7, 0),
            pauses: [
                PaidPauseFactV2(
                    start: localDate(2026, 1, 11, 0, 0),
                    end: localDate(2026, 1, 11, 1, 0),
                    paid: false
                )
            ]
        )
        let rule = NightPremiumRuleV2(
            startMinute: 22 * 60,
            endMinute: 6 * 60,
            multiplier: 1.25
        )!

        let result = NightPremiumPolicyV2.paidOverlap(
            session: session,
            rangeStart: session.entry,
            rangeEnd: session.exit!,
            rule: rule,
            calendar: calendar
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.paidDuration, 7 * 3_600, accuracy: 0.001)
    }

    func testPaidPauseStaysInsideNightPaidTime() {
        let session = fact(
            entry: localDate(2026, 1, 10, 21, 0),
            exit: localDate(2026, 1, 11, 7, 0),
            pauses: [
                PaidPauseFactV2(
                    start: localDate(2026, 1, 11, 0, 0),
                    end: localDate(2026, 1, 11, 1, 0),
                    paid: true
                )
            ]
        )
        let rule = NightPremiumRuleV2(
            startMinute: 22 * 60,
            endMinute: 6 * 60,
            multiplier: 1.25
        )!

        let result = NightPremiumPolicyV2.paidOverlap(
            session: session,
            rangeStart: session.entry,
            rangeEnd: session.exit!,
            rule: rule,
            calendar: calendar
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.paidDuration, 8 * 3_600, accuracy: 0.001)
    }

    func testUnknownPauseNeverBecomesZeroPremiumTime() {
        let session = fact(
            entry: localDate(2026, 1, 10, 22, 0),
            exit: localDate(2026, 1, 11, 6, 0),
            pauses: [
                PaidPauseFactV2(
                    start: localDate(2026, 1, 11, 0, 0),
                    end: localDate(2026, 1, 11, 1, 0),
                    paid: nil
                )
            ]
        )
        let rule = NightPremiumRuleV2(
            startMinute: 22 * 60,
            endMinute: 6 * 60,
            multiplier: 1.25
        )!

        let result = NightPremiumPolicyV2.paidOverlap(
            session: session,
            rangeStart: session.entry,
            rangeEnd: session.exit!,
            rule: rule,
            calendar: calendar
        )

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(
            result.warnings.contains(
                SalaryPaidOverlapPolicyV2.unresolvedPauseWarning
            )
        )
    }

    func testContradictoryOverlappingPauseClassificationsFailClosed() {
        let session = fact(
            entry: localDate(2026, 1, 10, 22, 0),
            exit: localDate(2026, 1, 11, 6, 0),
            pauses: [
                PaidPauseFactV2(
                    start: localDate(2026, 1, 11, 0, 0),
                    end: localDate(2026, 1, 11, 1, 0),
                    paid: false
                ),
                PaidPauseFactV2(
                    start: localDate(2026, 1, 11, 0, 30),
                    end: localDate(2026, 1, 11, 1, 30),
                    paid: true
                )
            ]
        )

        let result = SalaryPaidOverlapPolicyV2.paidOverlap(
            session: session,
            rangeStart: session.entry,
            rangeEnd: session.exit!
        )

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(
            result.warnings.contains(
                SalaryPaidOverlapPolicyV2.conflictingPauseWarning
            )
        )
    }

    func testPublicHolidayOverlapCountsOnlyHolidayCivilDate() {
        let session = fact(
            entry: localDate(2026, 5, 7, 23, 0),
            exit: localDate(2026, 5, 8, 2, 0),
            pauses: []
        )
        let holidays: Set<PayrollCivilDateV2> = [
            PayrollCivilDateV2(year: 2026, month: 5, day: 8)!
        ]

        let result = PublicHolidayPremiumPolicyV2.paidOverlap(
            session: session,
            rangeStart: session.entry,
            rangeEnd: session.exit!,
            holidayDates: holidays,
            calendar: calendar
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.paidDuration, 2 * 3_600, accuracy: 0.001)
    }

    func testInvalidNightRuleIsRejectedBeforeCalculation() {
        XCTAssertNil(
            NightPremiumRuleV2(
                startMinute: 22 * 60,
                endMinute: 22 * 60,
                multiplier: 1.25
            )
        )
        XCTAssertNil(
            NightPremiumRuleV2(
                startMinute: 22 * 60,
                endMinute: 6 * 60,
                multiplier: 0.9
            )
        )
    }

    private func fact(
        entry: Date,
        exit: Date,
        pauses: [PaidPauseFactV2]
    ) -> SalarySessionFactV2 {
        SalarySessionFactV2(
            id: UUID().uuidString,
            entry: entry,
            exit: exit,
            employerId: "company",
            pauses: pauses
        )
    }

    private func localDate(
        _ year: Int,
        _ month: Int,
        _ day: Int,
        _ hour: Int,
        _ minute: Int
    ) -> Date {
        var components = DateComponents()
        components.calendar = calendar
        components.timeZone = calendar.timeZone
        components.year = year
        components.month = month
        components.day = day
        components.hour = hour
        components.minute = minute
        return calendar.date(from: components)!
    }
}
