import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryPayrollWeekEvidenceBuilderV2Tests: XCTestCase {
    private let employerId = "company"

    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(identifier: "Europe/Paris")!
        value.locale = Locale(identifier: "fr_FR")
        value.firstWeekday = 2
        value.minimumDaysInFirstWeek = 4
        return value
    }

    func testNoPremiumRuleCanProveZeroBreakdownWithoutInventingNightWindow() {
        let result = build(
            sessions: [
                fact(
                    entry: localDate(2026, 9, 7, 8, 0),
                    exit: localDate(2026, 9, 7, 16, 0)
                )
            ],
            period: YearMonthV2(year: 2026, month: 9)!,
            rules: PayrollRulesV2()
        )

        XCTAssertTrue(result.evidence.paidTimeReliable)
        XCTAssertTrue(result.evidence.premiumTimeBreakdownReliable)
        XCTAssertTrue(result.evidence.payrollRulesReliable)
        XCTAssertTrue(result.evidence.grossInputsReliable)
        XCTAssertEqual(result.weeks.reduce(0) { $0 + $1.paidMinutes }, 480)
        XCTAssertEqual(result.weeks.reduce(0) { $0 + $1.nightMinutes }, 0)
    }

    func testSaturdayMinutesAreDerivedFromPaidPointageFacts() {
        let result = build(
            sessions: [
                fact(
                    entry: localDate(2026, 9, 5, 8, 0),
                    exit: localDate(2026, 9, 5, 16, 0)
                )
            ],
            period: YearMonthV2(year: 2026, month: 9)!,
            rules: PayrollRulesV2(saturdayMultiplier: 1.25)
        )

        XCTAssertTrue(result.evidence.premiumTimeBreakdownReliable)
        XCTAssertEqual(result.weeks.reduce(0) { $0 + $1.saturdayMinutes }, 480)
        XCTAssertEqual(result.weeks.reduce(0) { $0 + $1.sundayMinutes }, 0)
    }

    func testNightMultiplierWithoutOfficialWindowNeverTreatsZeroAsProof() {
        let result = build(
            sessions: [
                fact(
                    entry: localDate(2026, 9, 7, 22, 0),
                    exit: localDate(2026, 9, 8, 6, 0)
                )
            ],
            period: YearMonthV2(year: 2026, month: 9)!,
            rules: PayrollRulesV2(nightMultiplier: 1.25),
            nightRule: nil
        )

        XCTAssertTrue(result.evidence.paidTimeReliable)
        XCTAssertFalse(result.evidence.premiumTimeBreakdownReliable)
        XCTAssertFalse(result.evidence.grossInputsReliable)
        XCTAssertTrue(result.warnings.contains(SalaryPayrollWeekEvidenceBuilderV2.missingNightRuleWarning))
    }

    func testConfirmedNightWindowProducesNightMinutes() {
        let result = build(
            sessions: [
                fact(
                    entry: localDate(2026, 9, 7, 22, 0),
                    exit: localDate(2026, 9, 8, 6, 0)
                )
            ],
            period: YearMonthV2(year: 2026, month: 9)!,
            rules: PayrollRulesV2(nightMultiplier: 1.25),
            nightRule: NightPremiumRuleV2(
                startMinute: 22 * 60,
                endMinute: 6 * 60,
                multiplier: 1.25
            )
        )

        XCTAssertTrue(result.evidence.premiumTimeBreakdownReliable)
        XCTAssertEqual(result.weeks.reduce(0) { $0 + $1.nightMinutes }, 480)
    }

    func testCommonFrancePublicHolidayCanBeVentilated() {
        let scope = FrenchPublicHolidayCalendarV2.scopeForAddress(
            "10 rue Exemple, 85000 La Roche-sur-Yon"
        )
        let result = build(
            sessions: [
                fact(
                    entry: localDate(2026, 5, 8, 8, 0),
                    exit: localDate(2026, 5, 8, 16, 0)
                )
            ],
            period: YearMonthV2(year: 2026, month: 5)!,
            rules: PayrollRulesV2(publicHolidayMultiplier: 2.0),
            publicHolidayScope: scope
        )

        XCTAssertTrue(scope.complete)
        XCTAssertTrue(result.evidence.premiumTimeBreakdownReliable)
        XCTAssertEqual(result.weeks.reduce(0) { $0 + $1.publicHolidayMinutes }, 480)
    }

    func testIncompleteTerritorialScopeBlocksGenericHolidayProof() {
        let scope = FrenchPublicHolidayCalendarV2.scopeForAddress(
            "1 rue Exemple, 67000 Strasbourg"
        )
        let result = build(
            sessions: [
                fact(
                    entry: localDate(2026, 9, 7, 8, 0),
                    exit: localDate(2026, 9, 7, 16, 0)
                )
            ],
            period: YearMonthV2(year: 2026, month: 9)!,
            rules: PayrollRulesV2(publicHolidayMultiplier: 2.0),
            publicHolidayScope: scope
        )

        XCTAssertFalse(scope.complete)
        XCTAssertFalse(result.evidence.premiumTimeBreakdownReliable)
        XCTAssertTrue(
            result.warnings.contains(
                SalaryPayrollWeekEvidenceBuilderV2.incompleteHolidayScopeWarning
            )
        )
    }

    func testWorkedMayFirstBlocksReliableGrossEvenWithoutGenericHolidayMultiplier() {
        let scope = FrenchPublicHolidayCalendarV2.scopeForAddress(
            "85000 La Roche-sur-Yon"
        )
        let result = build(
            sessions: [
                fact(
                    entry: localDate(2026, 5, 1, 8, 0),
                    exit: localDate(2026, 5, 1, 16, 0)
                )
            ],
            period: YearMonthV2(year: 2026, month: 5)!,
            rules: PayrollRulesV2(),
            publicHolidayScope: scope
        )

        XCTAssertFalse(result.evidence.premiumTimeBreakdownReliable)
        XCTAssertTrue(
            result.warnings.contains(
                SalaryPayrollWeekEvidenceBuilderV2.mayFirstWorkedWarning
            )
        )
    }

    func testPayrollRulesReliabilityRemainsIndependentFromTimeFacts() {
        let result = build(
            sessions: [
                fact(
                    entry: localDate(2026, 9, 7, 8, 0),
                    exit: localDate(2026, 9, 7, 16, 0)
                )
            ],
            period: YearMonthV2(year: 2026, month: 9)!,
            rules: PayrollRulesV2(),
            payrollRulesReliable: false
        )

        XCTAssertTrue(result.evidence.paidTimeReliable)
        XCTAssertTrue(result.evidence.premiumTimeBreakdownReliable)
        XCTAssertFalse(result.evidence.payrollRulesReliable)
        XCTAssertFalse(result.evidence.grossInputsReliable)
    }

    private func build(
        sessions: [SalarySessionFactV2],
        period: YearMonthV2,
        rules: PayrollRulesV2,
        payrollRulesReliable: Bool = true,
        nightRule: NightPremiumRuleV2? = nil,
        publicHolidayScope: FrenchPublicHolidayCalendarV2.Scope? = nil
    ) -> SalaryPayrollWeekEvidenceResultV2 {
        SalaryPayrollWeekEvidenceBuilderV2.build(
            sessions: sessions,
            employerId: employerId,
            period: period,
            rules: rules,
            payrollRulesReliable: payrollRulesReliable,
            nightRule: nightRule,
            publicHolidayScope: publicHolidayScope,
            sourceReliable: true,
            calendar: calendar
        )
    }

    private func fact(
        entry: Date,
        exit: Date,
        pauses: [PaidPauseFactV2] = []
    ) -> SalarySessionFactV2 {
        SalarySessionFactV2(
            id: UUID().uuidString,
            entry: entry,
            exit: exit,
            employerId: employerId,
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
