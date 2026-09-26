import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class FrenchPublicHolidayCalendarV2Tests: XCTestCase {
    func testCommonFrance2026ComputesMobileDaysAndKeepsMayFirstSeparate() {
        let scope = FrenchPublicHolidayCalendarV2.scopeForAddress(
            "10 rue Exemple, 85000 La Roche-sur-Yon"
        )
        let holidays = FrenchPublicHolidayCalendarV2.genericHolidays(
            year: 2026,
            scope: scope
        )!

        XCTAssertTrue(scope.complete)
        XCTAssertEqual(scope.jurisdiction, .commonFrance)
        XCTAssertTrue(holidays.contains(date(2026, 4, 6)))
        XCTAssertTrue(holidays.contains(date(2026, 5, 14)))
        XCTAssertTrue(holidays.contains(date(2026, 5, 25)))
        XCTAssertFalse(holidays.contains(date(2026, 5, 1)))
        XCTAssertEqual(FrenchPublicHolidayCalendarV2.mayFirst(2026), date(2026, 5, 1))
        XCTAssertTrue(
            FrenchPublicHolidayCalendarV2.unresolvedPossibleHolidays(
                year: 2026,
                scope: scope
            )!.isEmpty
        )
    }

    func testAlsaceMoselleAddsDecember26ButKeepsGoodFridayUnresolved() {
        let scope = FrenchPublicHolidayCalendarV2.scopeForAddress(
            "1 rue Exemple, 67000 Strasbourg"
        )
        let holidays = FrenchPublicHolidayCalendarV2.genericHolidays(
            year: 2026,
            scope: scope
        )!
        let unresolved = FrenchPublicHolidayCalendarV2.unresolvedPossibleHolidays(
            year: 2026,
            scope: scope
        )!

        XCTAssertEqual(scope.jurisdiction, .alsaceMoselle)
        XCTAssertFalse(scope.complete)
        XCTAssertTrue(holidays.contains(date(2026, 12, 26)))
        XCTAssertTrue(unresolved.contains(date(2026, 4, 3)))
        XCTAssertTrue(scope.warning?.contains("Vendredi saint") == true)
    }

    func testOverseasTerritoriesAddTheirExistingAndroidCalendarDate() {
        let martinique = FrenchPublicHolidayCalendarV2.scopeForAddress("97200 Fort-de-France")
        let mayotte = FrenchPublicHolidayCalendarV2.scopeForAddress("97600 Mamoudzou")
        let reunion = FrenchPublicHolidayCalendarV2.scopeForAddress("97400 Saint-Denis")
        let saintMartin = FrenchPublicHolidayCalendarV2.scopeForAddress("97150 Saint-Martin")

        XCTAssertTrue(
            FrenchPublicHolidayCalendarV2.genericHolidays(year: 2026, scope: martinique)!
                .contains(date(2026, 5, 22))
        )
        XCTAssertTrue(
            FrenchPublicHolidayCalendarV2.genericHolidays(year: 2026, scope: mayotte)!
                .contains(date(2026, 4, 27))
        )
        XCTAssertTrue(
            FrenchPublicHolidayCalendarV2.genericHolidays(year: 2026, scope: reunion)!
                .contains(date(2026, 12, 20))
        )
        XCTAssertTrue(
            FrenchPublicHolidayCalendarV2.genericHolidays(year: 2026, scope: saintMartin)!
                .contains(date(2026, 5, 27))
        )
    }

    func testMissingAddressKeepsLocalDatesUnresolved() {
        let scope = FrenchPublicHolidayCalendarV2.scopeForAddress("")
        let unresolved = FrenchPublicHolidayCalendarV2.unresolvedPossibleHolidays(
            year: 2026,
            scope: scope
        )!

        XCTAssertFalse(scope.complete)
        XCTAssertEqual(scope.jurisdiction, .addressUnknown)
        XCTAssertTrue(unresolved.contains(date(2026, 4, 3)))
        XCTAssertTrue(unresolved.contains(date(2026, 12, 26)))
    }

    func testUnsupportedYearFailsClosed() {
        let scope = FrenchPublicHolidayCalendarV2.scopeForAddress("85000 La Roche-sur-Yon")
        XCTAssertNil(FrenchPublicHolidayCalendarV2.genericHolidays(year: 1800, scope: scope))
        XCTAssertNil(FrenchPublicHolidayCalendarV2.unresolvedPossibleHolidays(year: 2300, scope: scope))
        XCTAssertNil(FrenchPublicHolidayCalendarV2.mayFirst(2300))
    }

    private func date(_ year: Int, _ month: Int, _ day: Int) -> PayrollCivilDateV2 {
        PayrollCivilDateV2(year: year, month: month, day: day)!
    }
}
