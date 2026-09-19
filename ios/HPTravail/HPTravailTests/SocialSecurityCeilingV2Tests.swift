import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SocialSecurityCeilingV2Tests: XCTestCase {
    private let january2026 = YearMonthV2(year: 2026, month: 1)!
    private let oldEntry = PayrollCivilDateV2(year: 2020, month: 1, day: 1)!

    func testFullTimeFullMonthUses2026Pmss() {
        let result = SocialSecurityCeilingV2.calculate(
            .init(
                period: january2026,
                contractType: .fullTime,
                contractualWeeklyMinutes: 35 * 60,
                entryDate: oldEntry
            )
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.fullMonthly, 4_005, accuracy: 0.001)
        XCTAssertEqual(result.applicableMonthly, 4_005, accuracy: 0.001)
        XCTAssertEqual(result.presenceRatio, 1, accuracy: 0.000001)
        XCTAssertEqual(result.workTimeRatio, 1, accuracy: 0.000001)
    }

    func testPartTimeHalfScheduleProratesOnlyWithConfirmedComplementaryMinutes() {
        let incomplete = SocialSecurityCeilingV2.calculate(
            .init(
                period: january2026,
                contractType: .partTime,
                contractualWeeklyMinutes: 17 * 60 + 30,
                complementaryMinutes: nil,
                entryDate: oldEntry
            )
        )
        XCTAssertFalse(incomplete.complete)
        XCTAssertEqual(incomplete.applicableMonthly, 2_002.5, accuracy: 0.01)
        XCTAssertTrue(incomplete.warnings.contains { $0.contains("heures complémentaires") })

        let confirmedZero = SocialSecurityCeilingV2.calculate(
            .init(
                period: january2026,
                contractType: .partTime,
                contractualWeeklyMinutes: 17 * 60 + 30,
                complementaryMinutes: 0,
                entryDate: oldEntry
            )
        )
        XCTAssertTrue(confirmedZero.complete)
        XCTAssertEqual(confirmedZero.workTimeRatio, 0.5, accuracy: 0.000001)
        XCTAssertEqual(confirmedZero.applicableMonthly, 2_002.5, accuracy: 0.01)
    }

    func testMidMonthEntryUsesCalendarPresenceRatio() {
        let march = YearMonthV2(year: 2026, month: 3)!
        let entry = PayrollCivilDateV2(year: 2026, month: 3, day: 16)!
        let result = SocialSecurityCeilingV2.calculate(
            .init(
                period: march,
                contractType: .fullTime,
                contractualWeeklyMinutes: 35 * 60,
                entryDate: entry
            )
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.presenceRatio, 16.0 / 31.0, accuracy: 0.000001)
        XCTAssertEqual(result.applicableMonthly, 4_005.0 * 16.0 / 31.0, accuracy: 0.01)
    }

    func testUnpaidAbsenceReducesCeilingOnlyWhenCountIsConfirmed() {
        let april = YearMonthV2(year: 2026, month: 4)!
        let confirmed = SocialSecurityCeilingV2.calculate(
            .init(
                period: april,
                contractType: .fullTime,
                contractualWeeklyMinutes: 35 * 60,
                entryDate: oldEntry,
                unpaidAbsenceDays: 1
            )
        )
        XCTAssertTrue(confirmed.complete)
        XCTAssertEqual(confirmed.presenceRatio, 29.0 / 30.0, accuracy: 0.000001)

        let unknown = SocialSecurityCeilingV2.calculate(
            .init(
                period: april,
                contractType: .fullTime,
                contractualWeeklyMinutes: 35 * 60,
                entryDate: oldEntry,
                unpaidAbsenceDays: nil
            )
        )
        XCTAssertFalse(unknown.complete)
        XCTAssertEqual(unknown.presenceRatio, 1, accuracy: 0.000001)
        XCTAssertTrue(unknown.warnings.contains { $0.contains("non fiabilisés") })
    }

    func testMissingEntryNeverInventsMidMonthReduction() {
        let result = SocialSecurityCeilingV2.calculate(
            .init(
                period: january2026,
                contractType: .fullTime,
                contractualWeeklyMinutes: 35 * 60,
                entryDate: nil
            )
        )

        XCTAssertFalse(result.complete)
        XCTAssertEqual(result.applicableMonthly, 4_005, accuracy: 0.001)
        XCTAssertTrue(result.warnings.contains { $0.contains("date d'entrée absente") })
    }

    func testUnknownYearFailsClosed() {
        let result = SocialSecurityCeilingV2.calculate(
            .init(
                period: YearMonthV2(year: 2027, month: 1)!,
                contractType: .fullTime,
                contractualWeeklyMinutes: 35 * 60,
                entryDate: oldEntry
            )
        )

        XCTAssertFalse(result.complete)
        XCTAssertEqual(result.applicableMonthly, 0, accuracy: 0.001)
        XCTAssertTrue(result.warnings.contains { $0.contains("barème non intégré") })
    }
}
