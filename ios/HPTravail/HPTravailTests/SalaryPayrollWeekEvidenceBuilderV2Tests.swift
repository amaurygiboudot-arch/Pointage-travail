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

    // HT-015: the payroll minute boundary belongs after the weekly/category sum.
    func testSaturdayPremiumAndGrossAreInvariantToSessionFragmentation() throws {
        try assertFragmentedPremium(
            entry: localDate(2026, 9, 5, 8, 0),
            period: YearMonthV2(year: 2026, month: 9)!,
            rules: PayrollRulesV2(weeklyRegularMinutes: 2_100, saturdayMultiplier: 1.25),
            category: \.saturdayMinutes
        )
    }

    func testSundayPremiumAndGrossAreInvariantToSessionFragmentation() throws {
        try assertFragmentedPremium(
            entry: localDate(2026, 9, 6, 8, 0),
            period: YearMonthV2(year: 2026, month: 9)!,
            rules: PayrollRulesV2(weeklyRegularMinutes: 2_100, sundayMultiplier: 1.25),
            category: \.sundayMinutes
        )
    }

    func testNightPremiumAndGrossAreInvariantToSessionFragmentation() throws {
        try assertFragmentedPremium(
            entry: localDate(2026, 9, 7, 22, 0),
            period: YearMonthV2(year: 2026, month: 9)!,
            rules: PayrollRulesV2(weeklyRegularMinutes: 2_100, nightMultiplier: 1.25),
            category: \.nightMinutes,
            nightRule: NightPremiumRuleV2(startMinute: 22 * 60, endMinute: 6 * 60, multiplier: 1.25)
        )
    }

    func testHolidayPremiumAndGrossAreInvariantToSessionFragmentation() throws {
        try assertFragmentedPremium(
            entry: localDate(2026, 5, 8, 8, 0),
            period: YearMonthV2(year: 2026, month: 5)!,
            rules: PayrollRulesV2(weeklyRegularMinutes: 2_100, publicHolidayMultiplier: 1.25),
            category: \.publicHolidayMinutes,
            publicHolidayScope: FrenchPublicHolidayCalendarV2.scopeForAddress("85000 La Roche-sur-Yon")
        )
    }

    func testTwoSubMinuteSaturdaySessionsRecoverTheirCombinedMinute() {
        let start = localDate(2026, 9, 5, 8, 0)
        let result = build(
            sessions: [
                fact(entry: start, exit: start.addingTimeInterval(40)),
                fact(entry: start.addingTimeInterval(120), exit: start.addingTimeInterval(160))
            ],
            period: YearMonthV2(year: 2026, month: 9)!,
            rules: PayrollRulesV2(saturdayMultiplier: 1.25)
        )
        XCTAssertTrue(result.evidence.grossInputsReliable)
        XCTAssertEqual(result.weeks, [PayrollWeekV2(paidMinutes: 1, saturdayMinutes: 1)])
    }

    func testFractionalSecondsSurviveUntilTheSameWeeklyMinuteBoundary() {
        let start = localDate(2026, 9, 5, 8, 0)
        let whole = fact(entry: start, exit: start.addingTimeInterval(60))
        let split = fragments(whole, offsets: [0.125, 20.375, 40.625])
        let period = YearMonthV2(year: 2026, month: 9)!
        let rules = PayrollRulesV2(saturdayMultiplier: 1.25)
        let result = build(sessions: split, period: period, rules: rules)
        XCTAssertEqual(result, build(sessions: [whole], period: period, rules: rules))
        XCTAssertEqual(result.weeks, [PayrollWeekV2(paidMinutes: 1, saturdayMinutes: 1)])
    }

    func testPremiumRemaindersAreNotTransferredBetweenWeeks() {
        let first = localDate(2026, 9, 5, 8, 0)
        let second = localDate(2026, 9, 12, 8, 0)
        let result = build(
            sessions: [
                fact(entry: first, exit: first.addingTimeInterval(40)),
                fact(entry: second, exit: second.addingTimeInterval(40))
            ],
            period: YearMonthV2(year: 2026, month: 9)!,
            rules: PayrollRulesV2(saturdayMultiplier: 1.25)
        )
        XCTAssertTrue(result.evidence.grossInputsReliable)
        XCTAssertEqual(result.weeks, [PayrollWeekV2(paidMinutes: 0), PayrollWeekV2(paidMinutes: 0)])
    }

    func testSessionCrossingIsoWeekBoundaryKeepsEachWeeksOwnRemainder() {
        let boundary = localDate(2026, 9, 7, 0, 0)
        let whole = fact(entry: boundary.addingTimeInterval(-40), exit: boundary.addingTimeInterval(40))
        let period = YearMonthV2(year: 2026, month: 9)!
        let rules = PayrollRulesV2(nightMultiplier: 1.25)
        let night = NightPremiumRuleV2(startMinute: 22 * 60, endMinute: 6 * 60, multiplier: 1.25)
        let single = build(sessions: [whole], period: period, rules: rules, nightRule: night)
        let split = build(sessions: fragments(whole, offsets: [20, 40, 60]), period: period, rules: rules, nightRule: night)
        XCTAssertEqual(single, split)
        XCTAssertTrue(split.evidence.grossInputsReliable)
        XCTAssertEqual(split.weeks, [PayrollWeekV2(paidMinutes: 0), PayrollWeekV2(paidMinutes: 0)])
    }

    func testJanuaryUsesTheIsoWeekYearWhenAccumulatingNightRemainders() {
        // January 1 and 2 belong to ISO 2020-W53; January 4 starts 2021-W01.
        let starts = [localDate(2021, 1, 1, 22, 0), localDate(2021, 1, 2, 22, 0), localDate(2021, 1, 4, 22, 0)]
        let result = build(
            sessions: starts.map { fact(entry: $0, exit: $0.addingTimeInterval(40)) },
            period: YearMonthV2(year: 2021, month: 1)!,
            rules: PayrollRulesV2(nightMultiplier: 1.25),
            nightRule: NightPremiumRuleV2(startMinute: 22 * 60, endMinute: 6 * 60, multiplier: 1.25)
        )
        XCTAssertTrue(result.evidence.grossInputsReliable)
        XCTAssertEqual(result.weeks, [PayrollWeekV2(paidMinutes: 1, nightMinutes: 1), PayrollWeekV2(paidMinutes: 0)])
    }

    func testMonthClippingPrecedesNightDurationAccumulationAtBothEdges() {
        let start = localDate(2026, 9, 1, 0, 0)
        let end = localDate(2026, 10, 1, 0, 0)
        let sessions = [
            fact(entry: start.addingTimeInterval(-40), exit: start.addingTimeInterval(40)),
            fact(entry: start.addingTimeInterval(120), exit: start.addingTimeInterval(160)),
            fact(entry: end.addingTimeInterval(-40), exit: end.addingTimeInterval(40)),
            fact(entry: end.addingTimeInterval(-160), exit: end.addingTimeInterval(-120))
        ]
        let period = YearMonthV2(year: 2026, month: 9)!
        let rules = PayrollRulesV2(nightMultiplier: 1.25)
        let night = NightPremiumRuleV2(startMinute: 22 * 60, endMinute: 6 * 60, multiplier: 1.25)
        let single = build(sessions: sessions, period: period, rules: rules, nightRule: night)
        let split = build(sessions: sessions.flatMap { fragments($0, offsets: [20]) }, period: period, rules: rules, nightRule: night)
        XCTAssertEqual(single, split)
        XCTAssertTrue(split.evidence.grossInputsReliable)
        XCTAssertEqual(split.weeks, [PayrollWeekV2(paidMinutes: 1, nightMinutes: 1), PayrollWeekV2(paidMinutes: 1, nightMinutes: 1)])
    }

    func testSaturdayAndSundayRemaindersRemainIndependentWithinAWeek() {
        let saturday = localDate(2026, 9, 5, 8, 0)
        let sunday = localDate(2026, 9, 6, 8, 0)
        let result = build(
            sessions: [
                fact(entry: saturday, exit: saturday.addingTimeInterval(40)),
                fact(entry: sunday, exit: sunday.addingTimeInterval(40))
            ],
            period: YearMonthV2(year: 2026, month: 9)!,
            rules: PayrollRulesV2(saturdayMultiplier: 1.25, sundayMultiplier: 1.5)
        )
        XCTAssertTrue(result.evidence.grossInputsReliable)
        XCTAssertEqual(result.weeks, [PayrollWeekV2(paidMinutes: 1)])
    }

    func testFragmentedPaidTravelAndPausesPreservePremiumWhilePersonalTravelIsExcluded() {
        let start = localDate(2026, 9, 5, 8, 0)
        func date(_ seconds: TimeInterval) -> Date { start.addingTimeInterval(seconds) }
        let whole = SalarySessionFactV2(
            id: "work-and-travel", entry: start, exit: date(180), employerId: employerId,
            pauses: [
                PaidPauseFactV2(start: date(50), end: date(70), paid: false),
                PaidPauseFactV2(start: date(90), end: date(100), paid: true)
            ],
            segments: [
                PaidWorkSegmentFactV2(start: start, end: date(20), kind: .personalTravel, confirmed: true),
                PaidWorkSegmentFactV2(start: date(20), end: date(70), kind: .work, confirmed: true),
                PaidWorkSegmentFactV2(start: date(70), end: date(110), kind: .paidTravel, confirmed: true),
                PaidWorkSegmentFactV2(start: date(110), end: date(160), kind: .work, confirmed: true),
                PaidWorkSegmentFactV2(start: date(160), end: date(180), kind: .personalTravel, confirmed: true)
            ]
        )
        let period = YearMonthV2(year: 2026, month: 9)!
        let rules = PayrollRulesV2(saturdayMultiplier: 1.25)
        let single = build(sessions: [whole], period: period, rules: rules)
        let split = build(sessions: fragments(whole, offsets: [90]), period: period, rules: rules)
        XCTAssertTrue(single.evidence.grossInputsReliable)
        XCTAssertTrue(split.evidence.grossInputsReliable)
        XCTAssertEqual(single, split)
        // 180 seconds - 40 personal travel - 20 unpaid pause = 120 seconds.
        XCTAssertEqual(split.weeks, [PayrollWeekV2(paidMinutes: 2, saturdayMinutes: 2)])
    }

    func testAnotherEmployersRemainderCannotCompleteThisEmployersMinute() {
        let start = localDate(2026, 9, 5, 8, 0)
        let other = SalarySessionFactV2(id: "other", entry: start.addingTimeInterval(120),
            exit: start.addingTimeInterval(160), employerId: "other-company", pauses: [])
        let result = build(
            sessions: [fact(entry: start, exit: start.addingTimeInterval(40)), other],
            period: YearMonthV2(year: 2026, month: 9)!,
            rules: PayrollRulesV2(saturdayMultiplier: 1.25)
        )
        XCTAssertTrue(result.evidence.grossInputsReliable)
        XCTAssertEqual(result.weeks, [PayrollWeekV2(paidMinutes: 0)])
    }

    private func assertFragmentedPremium(
        entry: Date,
        period: YearMonthV2,
        rules: PayrollRulesV2,
        category: KeyPath<PayrollWeekV2, Int>,
        nightRule: NightPremiumRuleV2? = nil,
        publicHolidayScope: FrenchPublicHolidayCalendarV2.Scope? = nil,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws {
        let whole = fact(entry: entry, exit: entry.addingTimeInterval(7_280))
        let pieces = fragments(whole, offsets: [3_640])
        let single = build(sessions: [whole], period: period, rules: rules,
            nightRule: nightRule, publicHolidayScope: publicHolidayScope)
        let split = build(sessions: pieces, period: period, rules: rules,
            nightRule: nightRule, publicHolidayScope: publicHolidayScope)
        XCTAssertEqual(single, split, file: file, line: line)
        XCTAssertEqual(split, build(sessions: Array(pieces.reversed()), period: period, rules: rules,
            nightRule: nightRule, publicHolidayScope: publicHolidayScope), file: file, line: line)
        XCTAssertTrue(split.evidence.grossInputsReliable, file: file, line: line)
        XCTAssertEqual(split.weeks.count, 1, file: file, line: line)
        XCTAssertEqual(split.weeks.first?.paidMinutes, 121, file: file, line: line)
        XCTAssertEqual(split.weeks.first?[keyPath: category], 121, file: file, line: line)

        let contract = ContractV2(id: "fictitious-hourly", employerId: employerId, type: .other,
            contractualWeeklyMinutes: 2_100, grossHourlyRate: 60, hireDateEpochDay: nil)
        let singleGross = try PayrollEngineV2.calculate(contract: contract, weeks: single.weeks,
            rules: rules, evidence: single.evidence)
        let splitGross = try PayrollEngineV2.calculate(contract: contract, weeks: split.weeks,
            rules: rules, evidence: split.evidence)
        XCTAssertEqual(singleGross, splitGross, file: file, line: line)
        XCTAssertEqual(splitGross.premiumsGross, 30.25, accuracy: 0.000_001, file: file, line: line)
        XCTAssertEqual(splitGross.grossEstimate, 151.25, accuracy: 0.000_001, file: file, line: line)
    }

    /// Split only the recorded facts, clipping pauses and qualifications to each fragment.
    private func fragments(_ source: SalarySessionFactV2, offsets: [TimeInterval]) -> [SalarySessionFactV2] {
        let bounds = [source.entry] + offsets.map { source.entry.addingTimeInterval($0) } + [source.exit!]
        return zip(bounds, bounds.dropFirst()).enumerated().map { index, endpoints in
            let (start, end) = endpoints
            let pauses = source.pauses.compactMap { pause -> PaidPauseFactV2? in
                let clippedStart = max(start, pause.start)
                let clippedEnd = min(end, pause.end!)
                return clippedEnd > clippedStart
                    ? PaidPauseFactV2(start: clippedStart, end: clippedEnd, paid: pause.paid) : nil
            }
            let segments = source.segments.compactMap { segment -> PaidWorkSegmentFactV2? in
                let clippedStart = max(start, segment.start)
                let clippedEnd = min(end, segment.end!)
                return clippedEnd > clippedStart
                    ? PaidWorkSegmentFactV2(start: clippedStart, end: clippedEnd, kind: segment.kind, confirmed: segment.confirmed) : nil
            }
            return SalarySessionFactV2(id: source.id + "-\(index)", entry: start, exit: end,
                employerId: source.employerId, pauses: pauses, segments: segments)
        }
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
