import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryWeeklyThresholdMonthBoundaryGuardV2Tests: XCTestCase {
    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(identifier: "Europe/Paris")!
        value.locale = Locale(identifier: "fr_FR")
        value.firstWeekday = 2
        value.minimumDaysInFirstWeek = 4
        return value
    }

    func testOutsideMonthTimeThatPushesBoundaryWeekOverThresholdBlocks() throws {
        let period = try XCTUnwrap(YearMonthV2(year: 2026, month: 9))
        let sessions = [
            session("aug31", 2026, 8, 31, 8, 16),
            session("sep1", 2026, 9, 1, 8, 16),
            session("sep2", 2026, 9, 2, 8, 16),
            session("sep3", 2026, 9, 3, 8, 16),
            session("sep4", 2026, 9, 4, 8, 16)
        ]

        let result = SalaryWeeklyThresholdMonthBoundaryGuardV2.assess(
            sessions: sessions,
            employerId: "company",
            period: period,
            weeklyThresholdMinutes: 35 * 60,
            sourceReliable: true,
            calendar: calendar,
            now: date(2026, 10, 6, 0)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.affectedWeeks.count, 1)
        XCTAssertEqual(result.affectedWeeks[0].fullWeekPaidMinutes, 40 * 60)
        XCTAssertEqual(result.affectedWeeks[0].inMonthPaidMinutes, 32 * 60)
        XCTAssertTrue(
            result.warnings.contains(
                SalaryWeeklyThresholdMonthBoundaryGuardV2.contextWarning
            )
        )
    }

    func testBoundaryWeekBelowThresholdStaysReliable() throws {
        let period = try XCTUnwrap(YearMonthV2(year: 2026, month: 9))
        let sessions = [
            session("aug31", 2026, 8, 31, 8, 12),
            session("sep1", 2026, 9, 1, 8, 16),
            session("sep2", 2026, 9, 2, 8, 16),
            session("sep3", 2026, 9, 3, 8, 16)
        ]

        let result = SalaryWeeklyThresholdMonthBoundaryGuardV2.assess(
            sessions: sessions,
            employerId: "company",
            period: period,
            weeklyThresholdMinutes: 35 * 60,
            sourceReliable: true,
            calendar: calendar,
            now: date(2026, 10, 6, 0)
        )

        XCTAssertTrue(result.reliable)
        XCTAssertTrue(result.affectedWeeks.isEmpty)
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testThresholdCrossedEntirelyInsideMonthDoesNotCreateArtificialBlock() throws {
        let period = try XCTUnwrap(YearMonthV2(year: 2026, month: 9))
        let sessions = (1...5).map {
            session("sep\($0)", 2026, 9, $0, 8, 16)
        }

        let result = SalaryWeeklyThresholdMonthBoundaryGuardV2.assess(
            sessions: sessions,
            employerId: "company",
            period: period,
            weeklyThresholdMinutes: 35 * 60,
            sourceReliable: true,
            calendar: calendar,
            now: date(2026, 10, 6, 0)
        )

        XCTAssertTrue(result.reliable)
        XCTAssertTrue(result.affectedWeeks.isEmpty)
    }

    func testUnfinishedLastBoundaryWeekFailsClosed() throws {
        let period = try XCTUnwrap(YearMonthV2(year: 2026, month: 9))

        let result = SalaryWeeklyThresholdMonthBoundaryGuardV2.assess(
            sessions: [],
            employerId: "company",
            period: period,
            weeklyThresholdMinutes: 35 * 60,
            sourceReliable: true,
            calendar: calendar,
            now: date(2026, 10, 1, 12)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(
            result.warnings.contains(
                SalaryWeeklyThresholdMonthBoundaryGuardV2.futureContextWarning
            )
        )
    }

    func testUnassignedSessionOnBoundaryWeekFailsClosed() throws {
        let period = try XCTUnwrap(YearMonthV2(year: 2026, month: 9))
        let unassigned = SalarySessionFactV2(
            id: "unknown",
            entry: date(2026, 8, 31, 8),
            exit: date(2026, 8, 31, 16),
            employerId: nil,
            pauses: []
        )

        let result = SalaryWeeklyThresholdMonthBoundaryGuardV2.assess(
            sessions: [unassigned],
            employerId: "company",
            period: period,
            weeklyThresholdMinutes: 35 * 60,
            sourceReliable: true,
            calendar: calendar,
            now: date(2026, 10, 6, 0)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(
            result.warnings.contains(
                SalaryWeeklyThresholdMonthBoundaryGuardV2.sourceWarning
            )
        )
    }

    private func session(
        _ id: String,
        _ year: Int,
        _ month: Int,
        _ day: Int,
        _ startHour: Int,
        _ endHour: Int
    ) -> SalarySessionFactV2 {
        SalarySessionFactV2(
            id: id,
            entry: date(year, month, day, startHour),
            exit: date(year, month, day, endHour),
            employerId: "company",
            pauses: []
        )
    }

    private func date(
        _ year: Int,
        _ month: Int,
        _ day: Int,
        _ hour: Int
    ) -> Date {
        calendar.date(
            from: DateComponents(
                calendar: calendar,
                timeZone: calendar.timeZone,
                year: year,
                month: month,
                day: day,
                hour: hour,
                minute: 0,
                second: 0
            )
        )!
    }
}
