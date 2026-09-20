import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryPaidWorkAggregatorV2Tests: XCTestCase {
    private let employerA = "company-a"
    private let employerB = "company-b"

    private var utcCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "fr_FR")
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    private func date(
        _ year: Int,
        _ month: Int,
        _ day: Int,
        _ hour: Int,
        _ minute: Int = 0,
        calendar: Calendar? = nil
    ) -> Date {
        let calendar = calendar ?? utcCalendar
        return calendar.date(from: DateComponents(
            calendar: calendar,
            timeZone: calendar.timeZone,
            year: year,
            month: month,
            day: day,
            hour: hour,
            minute: minute,
            second: 0
        ))!
    }

    private func session(
        id: String = UUID().uuidString,
        entry: Date,
        exit: Date?,
        employerId: String? = "company-a",
        pauses: [PaidPauseFactV2] = []
    ) -> SalarySessionFactV2 {
        SalarySessionFactV2(
            id: id,
            entry: entry,
            exit: exit,
            employerId: employerId,
            pauses: pauses
        )
    }

    private var september2026: YearMonthV2 {
        YearMonthV2(year: 2026, month: 9)!
    }

    func testExplicitUnpaidPauseIsDeductedFromPaidMinutes() {
        let entry = date(2026, 9, 14, 8)
        let exit = date(2026, 9, 14, 16)
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: entry,
                exit: exit,
                pauses: [PaidPauseFactV2(
                    start: date(2026, 9, 14, 12),
                    end: date(2026, 9, 14, 12, 30),
                    paid: false
                )]
            )],
            employerId: employerA,
            period: september2026,
            calendar: utcCalendar
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.completedSessionCount, 1)
        XCTAssertEqual(result.totalPaidMinutes, 450)
        XCTAssertEqual(result.weeks.count, 1)
    }

    func testExplicitPaidPauseRemainsPaid() {
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: date(2026, 9, 14, 8),
                exit: date(2026, 9, 14, 16),
                pauses: [PaidPauseFactV2(
                    start: date(2026, 9, 14, 12),
                    end: date(2026, 9, 14, 13),
                    paid: true
                )]
            )],
            employerId: employerA,
            period: september2026,
            calendar: utcCalendar
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.totalPaidMinutes, 480)
    }

    func testUnknownPauseStatusFailsClosed() {
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: date(2026, 9, 14, 8),
                exit: date(2026, 9, 14, 16),
                pauses: [PaidPauseFactV2(
                    start: date(2026, 9, 14, 12),
                    end: date(2026, 9, 14, 12, 30),
                    paid: nil
                )]
            )],
            employerId: employerA,
            period: september2026,
            calendar: utcCalendar
        )

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.warnings.contains(SalaryPaidWorkAggregatorV2.unresolvedPauseWarning))
    }

    func testOnlyExplicitMatchingEmployerIsAggregated() {
        let matching = session(
            entry: date(2026, 9, 15, 8),
            exit: date(2026, 9, 15, 9),
            employerId: employerA
        )
        let otherEmployer = session(
            entry: date(2026, 9, 15, 9),
            exit: date(2026, 9, 15, 11),
            employerId: employerB
        )
        let unassigned = session(
            entry: date(2026, 9, 15, 11),
            exit: date(2026, 9, 15, 14),
            employerId: nil
        )

        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [matching, otherEmployer, unassigned],
            employerId: employerA,
            period: september2026,
            calendar: utcCalendar
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.completedSessionCount, 1)
        XCTAssertEqual(result.totalPaidMinutes, 60)
    }

    func testSessionCrossingMonthBoundaryIsClippedToRequestedMonth() {
        let october = YearMonthV2(year: 2026, month: 10)!
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: date(2026, 9, 30, 23),
                exit: date(2026, 10, 1, 1)
            )],
            employerId: employerA,
            period: october,
            calendar: utcCalendar
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.totalPaidMinutes, 60)
        XCTAssertEqual(result.completedSessionCount, 1)
    }

    func testSessionCrossingIsoWeekBoundaryProducesTwoBuckets() {
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: date(2026, 9, 20, 23, 30),
                exit: date(2026, 9, 21, 0, 30)
            )],
            employerId: employerA,
            period: september2026,
            calendar: utcCalendar
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.weeks.count, 2)
        XCTAssertEqual(result.weeks.map(\.paidMinutes), [30, 30])
        XCTAssertEqual(result.totalPaidMinutes, 60)
    }

    func testOpenSessionTouchingPeriodFailsClosedWithoutInventingExit() {
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: date(2026, 9, 20, 8),
                exit: nil
            )],
            employerId: employerA,
            period: september2026,
            calendar: utcCalendar
        )

        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.totalPaidMinutes, 0)
        XCTAssertTrue(result.warnings.contains(SalaryPaidWorkAggregatorV2.openSessionWarning))
    }

    func testOverlappingSessionsForSameEmployerFailClosed() {
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [
                session(
                    id: "a",
                    entry: date(2026, 9, 22, 8),
                    exit: date(2026, 9, 22, 10)
                ),
                session(
                    id: "b",
                    entry: date(2026, 9, 22, 9),
                    exit: date(2026, 9, 22, 11)
                )
            ],
            employerId: employerA,
            period: september2026,
            calendar: utcCalendar
        )

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.warnings.contains(SalaryPaidWorkAggregatorV2.overlapWarning))
    }

    func testUnreliableRuntimeSourceAlwaysFailsClosed() {
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: date(2026, 9, 23, 8),
                exit: date(2026, 9, 23, 9)
            )],
            employerId: employerA,
            period: september2026,
            sourceReliable: false,
            calendar: utcCalendar
        )

        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.totalPaidMinutes, 60)
        XCTAssertTrue(result.warnings.contains(SalaryPaidWorkAggregatorV2.sourceWarning))
    }

    func testSpringDstTransitionUsesElapsedTimeNotInventedWallClockHours() {
        var paris = Calendar(identifier: .gregorian)
        paris.locale = Locale(identifier: "fr_FR")
        paris.timeZone = TimeZone(identifier: "Europe/Paris")!

        let march = YearMonthV2(year: 2026, month: 3)!
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: date(2026, 3, 29, 1, 30, calendar: paris),
                exit: date(2026, 3, 29, 3, 30, calendar: paris)
            )],
            employerId: employerA,
            period: march,
            calendar: paris
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.totalPaidMinutes, 60)
    }

    func testMonthEndUsesIndependentNextMonthBoundaryAcrossMidnightDstShift() {
        var asuncion = Calendar(identifier: .gregorian)
        asuncion.locale = Locale(identifier: "es_PY")
        asuncion.timeZone = TimeZone(identifier: "America/Asuncion")!

        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: date(2023, 11, 1, 0, 30, calendar: asuncion),
                exit: date(2023, 11, 1, 1, 30, calendar: asuncion)
            )],
            employerId: employerA,
            period: YearMonthV2(year: 2023, month: 10)!,
            calendar: asuncion
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.completedSessionCount, 0)
        XCTAssertEqual(result.totalPaidMinutes, 0)
        XCTAssertEqual(result.weeks, [])
    }

    func testNonGregorianDeviceCalendarStillUsesGregorianPayrollPeriod() {
        var buddhist = Calendar(identifier: .buddhist)
        buddhist.locale = Locale(identifier: "th_TH")
        buddhist.timeZone = TimeZone(secondsFromGMT: 0)!

        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: date(2026, 9, 24, 8),
                exit: date(2026, 9, 24, 9)
            )],
            employerId: employerA,
            period: september2026,
            calendar: buddhist
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.completedSessionCount, 1)
        XCTAssertEqual(result.totalPaidMinutes, 60)
        XCTAssertEqual(result.weeks.first?.yearForWeekOfYear, 2026)
    }

    func testConflictingOverlappingPauseClassificationsFailClosed() {
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: date(2026, 9, 25, 8),
                exit: date(2026, 9, 25, 16),
                pauses: [
                    PaidPauseFactV2(
                        start: date(2026, 9, 25, 12),
                        end: date(2026, 9, 25, 13),
                        paid: false
                    ),
                    PaidPauseFactV2(
                        start: date(2026, 9, 25, 12, 30),
                        end: date(2026, 9, 25, 13, 30),
                        paid: true
                    )
                ]
            )],
            employerId: employerA,
            period: september2026,
            calendar: utcCalendar
        )

        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.completedSessionCount, 0)
        XCTAssertEqual(result.totalPaidMinutes, 0)
        XCTAssertTrue(result.warnings.contains(SalaryPaidWorkAggregatorV2.conflictingPauseWarning))
    }

    func testConflictingPausesBeforeRequestedMonthDoNotPoisonInMonthSlice() {
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: date(2026, 8, 31, 20),
                exit: date(2026, 9, 1, 8),
                pauses: [
                    PaidPauseFactV2(
                        start: date(2026, 8, 31, 21),
                        end: date(2026, 8, 31, 22),
                        paid: false
                    ),
                    PaidPauseFactV2(
                        start: date(2026, 8, 31, 21, 30),
                        end: date(2026, 8, 31, 22, 30),
                        paid: true
                    )
                ]
            )],
            employerId: employerA,
            period: september2026,
            calendar: utcCalendar
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.completedSessionCount, 1)
        XCTAssertEqual(result.totalPaidMinutes, 480)
        XCTAssertFalse(result.warnings.contains(SalaryPaidWorkAggregatorV2.conflictingPauseWarning))
    }

    func testMalformedPauseBeforeRequestedMonthDoesNotPoisonInMonthSlice() {
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: date(2026, 8, 31, 20),
                exit: date(2026, 9, 1, 8),
                pauses: [PaidPauseFactV2(
                    start: date(2026, 8, 31, 21, 30),
                    end: date(2026, 8, 31, 21),
                    paid: false
                )]
            )],
            employerId: employerA,
            period: september2026,
            calendar: utcCalendar
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.completedSessionCount, 1)
        XCTAssertEqual(result.totalPaidMinutes, 480)
        XCTAssertFalse(result.warnings.contains(SalaryPaidWorkAggregatorV2.invalidSessionWarning))
    }

    func testMalformedPauseInsideRequestedMonthStillFailsClosed() {
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: date(2026, 9, 1, 0),
                exit: date(2026, 9, 1, 8),
                pauses: [PaidPauseFactV2(
                    start: date(2026, 9, 1, 2),
                    end: date(2026, 9, 1, 1, 30),
                    paid: false
                )]
            )],
            employerId: employerA,
            period: september2026,
            calendar: utcCalendar
        )

        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.completedSessionCount, 0)
        XCTAssertEqual(result.totalPaidMinutes, 0)
        XCTAssertTrue(result.warnings.contains(SalaryPaidWorkAggregatorV2.invalidSessionWarning))
    }

    func testNonFiniteEntryFailsClosedBeforePeriodFiltering() {
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: Date(timeIntervalSince1970: .infinity),
                exit: date(2026, 9, 26, 9)
            )],
            employerId: employerA,
            period: september2026,
            calendar: utcCalendar
        )

        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.completedSessionCount, 0)
        XCTAssertEqual(result.totalPaidMinutes, 0)
        XCTAssertTrue(result.warnings.contains(SalaryPaidWorkAggregatorV2.invalidSessionWarning))
    }

    func testReversedSessionWithEndpointInsidePeriodFailsClosed() {
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: date(2026, 10, 2, 10),
                exit: date(2026, 9, 15, 10)
            )],
            employerId: employerA,
            period: september2026,
            calendar: utcCalendar
        )

        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.completedSessionCount, 0)
        XCTAssertEqual(result.totalPaidMinutes, 0)
        XCTAssertTrue(result.warnings.contains(SalaryPaidWorkAggregatorV2.invalidSessionWarning))
    }

    func testReversedSessionEntirelyOutsidePeriodDoesNotPoisonRequestedMonth() {
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [session(
                entry: date(2026, 10, 3, 10),
                exit: date(2026, 10, 2, 10)
            )],
            employerId: employerA,
            period: september2026,
            calendar: utcCalendar
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.completedSessionCount, 0)
        XCTAssertEqual(result.totalPaidMinutes, 0)
        XCTAssertEqual(result.warnings, [])
    }

    func testBlankEmployerIdFailsClosed() {
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: [],
            employerId: "   ",
            period: september2026,
            calendar: utcCalendar
        )

        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.weeks, [])
        XCTAssertEqual(result.warnings, [SalaryPaidWorkAggregatorV2.employerWarning])
    }
}
