import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryPayrollCoveragePeriodV2Tests: XCTestCase {
    func testSeptember2026IncludesWholeBoundaryWeeks() throws {
        let month = try XCTUnwrap(YearMonthV2(year: 2026, month: 9))
        let period = try XCTUnwrap(SalaryPayrollCoveragePeriodV2.forMonth(month))
        XCTAssertEqual(period.startEpochDay, epochDay(2026, 8, 31))
        XCTAssertEqual(period.endEpochDay, epochDay(2026, 10, 4))
    }

    func testMondayToSundayMonthNeedsNoExtraBoundaryDay() throws {
        let month = try XCTUnwrap(YearMonthV2(year: 2021, month: 2))
        let period = try XCTUnwrap(SalaryPayrollCoveragePeriodV2.forMonth(month))
        XCTAssertEqual(period.startEpochDay, epochDay(2021, 2, 1))
        XCTAssertEqual(period.endEpochDay, epochDay(2021, 2, 28))
    }

    func testPeriodClosesOnlyAfterFinalSunday() throws {
        let month = try XCTUnwrap(YearMonthV2(year: 2026, month: 9))
        let period = try XCTUnwrap(SalaryPayrollCoveragePeriodV2.forMonth(month))
        let zone = "Europe/Paris"
        XCTAssertFalse(period.isClosed(at: localDate(2026, 10, 4, 23, 59, zone), timeZoneId: zone))
        XCTAssertTrue(period.isClosed(at: localDate(2026, 10, 5, 0, 0, zone), timeZoneId: zone))
    }

    private func epochDay(_ y: Int, _ m: Int, _ d: Int) -> Int64 {
        var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(secondsFromGMT: 0)!
        return Int64(c.date(from: DateComponents(year: y, month: m, day: d))!.timeIntervalSince1970 / 86400)
    }

    private func localDate(_ y: Int, _ m: Int, _ d: Int, _ h: Int, _ min: Int, _ zone: String) -> Date {
        var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: zone)!
        return c.date(from: DateComponents(year: y, month: m, day: d, hour: h, minute: min))!
    }
}
