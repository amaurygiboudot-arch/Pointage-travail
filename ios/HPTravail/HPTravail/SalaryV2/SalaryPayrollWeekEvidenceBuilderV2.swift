import Foundation

struct SalaryPayrollWeekEvidenceResultV2: Equatable {
    let weeks: [PayrollWeekV2]
    let evidence: PayrollInputEvidenceV2
    let warnings: [String]
}

/// Transforme les faits de pointage en semaines consommables par PayrollEngineV2.
///
/// Le temps payé et la ventilation des majorations ont des preuves séparées. Ainsi un total
/// hebdomadaire fiable peut rester visible tout en interdisant la publication d'un brut lorsque,
/// par exemple, une plage de nuit ou un calendrier territorial n'est pas suffisamment prouvé.
enum SalaryPayrollWeekEvidenceBuilderV2 {
    static let missingNightRuleWarning =
        "Nuit : un multiplicateur existe mais aucune plage horaire officielle structurée n'est disponible ; ventilation bloquée."
    static let nightRuleMismatchWarning =
        "Nuit : la plage structurée et le multiplicateur de paie ne proviennent pas d'une règle cohérente ; ventilation bloquée."
    static let missingHolidayScopeWarning =
        "Jours fériés : périmètre territorial absent ; ventilation fériée non certifiable."
    static let incompleteHolidayScopeWarning =
        "Jours fériés : calendrier territorial non exhaustif ; la majoration générique reste bloquée."
    static let unresolvedHolidayWorkedWarning =
        "Jour férié territorial potentiel travaillé : le périmètre local n'est pas suffisamment confirmé pour certifier ces minutes."
    static let mayFirstWorkedWarning =
        "1er mai travaillé : le régime légal dédié n'est pas encore injecté dans PayrollEngineV2 ; brut fiable bloqué."
    static let calendarWarning =
        "Ventilation du temps majorable : calendrier local impossible à construire de façon fiable."

    static func build(
        sessions: [SalarySessionFactV2],
        employerId rawEmployerId: String,
        period: YearMonthV2,
        rules: PayrollRulesV2,
        payrollRulesReliable: Bool,
        nightRule: NightPremiumRuleV2? = nil,
        publicHolidayScope: FrenchPublicHolidayCalendarV2.Scope? = nil,
        sourceReliable: Bool = true,
        calendar inputCalendar: Calendar = .current
    ) -> SalaryPayrollWeekEvidenceResultV2 {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = inputCalendar.timeZone
        calendar.locale = inputCalendar.locale
        calendar.firstWeekday = 2
        calendar.minimumDaysInFirstWeek = 4

        let employerId = rawEmployerId.trimmingCharacters(in: .whitespacesAndNewlines)
        let paidWork = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: sessions,
            employerId: employerId,
            period: period,
            sourceReliable: sourceReliable,
            calendar: calendar
        )

        var premiumReliable = paidWork.reliable
        var warnings = paidWork.warnings

        let nightRequired = rules.nightMultiplier != nil
        if nightRequired {
            guard let nightRule else {
                premiumReliable = false
                warnings.append(missingNightRuleWarning)
                return result(
                    from: paidWork,
                    premiumByWeek: [:],
                    paidTimeReliable: paidWork.reliable,
                    premiumReliable: premiumReliable,
                    payrollRulesReliable: payrollRulesReliable,
                    warnings: warnings
                )
            }
            if let multiplier = rules.nightMultiplier,
               abs(multiplier - nightRule.multiplier) > 0.000_001 {
                premiumReliable = false
                warnings.append(nightRuleMismatchWarning)
            }
        }

        let genericHolidays: Set<PayrollCivilDateV2>
        let unresolvedHolidays: Set<PayrollCivilDateV2>
        if let scope = publicHolidayScope {
            genericHolidays = FrenchPublicHolidayCalendarV2.genericHolidays(
                year: period.year,
                scope: scope
            ) ?? []
            unresolvedHolidays = FrenchPublicHolidayCalendarV2.unresolvedPossibleHolidays(
                year: period.year,
                scope: scope
            ) ?? []
            if rules.publicHolidayMultiplier != nil && !scope.complete {
                premiumReliable = false
                warnings.append(incompleteHolidayScopeWarning)
            }
        } else {
            genericHolidays = []
            unresolvedHolidays = []
            if rules.publicHolidayMultiplier != nil {
                premiumReliable = false
                warnings.append(missingHolidayScopeWarning)
            }
        }

        guard let month = monthInterval(period: period, calendar: calendar) else {
            premiumReliable = false
            warnings.append(calendarWarning)
            return result(
                from: paidWork,
                premiumByWeek: [:],
                paidTimeReliable: paidWork.reliable,
                premiumReliable: premiumReliable,
                payrollRulesReliable: payrollRulesReliable,
                warnings: warnings
            )
        }

        let relevantSessions = sessions.filter {
            normalizedEmployerId($0.employerId) == employerId
                && potentiallyTouches($0, start: month.start, end: month.end)
        }

        var premiumByWeek: [WeekKey: PremiumMinutes] = [:]
        for paidWeek in paidWork.weeks {
            let key = WeekKey(
                year: paidWeek.yearForWeekOfYear,
                week: paidWeek.weekOfYear
            )
            guard let rawWeek = weekInterval(key, calendar: calendar) else {
                premiumReliable = false
                warnings.append(calendarWarning)
                continue
            }
            let rangeStart = max(rawWeek.start, month.start)
            let rangeEnd = min(rawWeek.end, month.end)
            guard rangeEnd > rangeStart else { continue }

            var values = PremiumMinutes()

            for session in relevantSessions where potentiallyTouches(
                session,
                start: rangeStart,
                end: rangeEnd
            ) {
                if let nightRule {
                    let night = NightPremiumPolicyV2.paidOverlap(
                        session: session,
                        rangeStart: rangeStart,
                        rangeEnd: rangeEnd,
                        rule: nightRule,
                        calendar: calendar
                    )
                    values.night += floorMinutes(night.paidDuration)
                    premiumReliable = premiumReliable && night.reliable
                    warnings.append(contentsOf: night.warnings)
                }

                let saturday = weekdayPaidOverlap(
                    session: session,
                    rangeStart: rangeStart,
                    rangeEnd: rangeEnd,
                    weekday: 7,
                    calendar: calendar
                )
                values.saturday += floorMinutes(saturday.paidDuration)
                premiumReliable = premiumReliable && saturday.reliable
                warnings.append(contentsOf: saturday.warnings)

                let sunday = weekdayPaidOverlap(
                    session: session,
                    rangeStart: rangeStart,
                    rangeEnd: rangeEnd,
                    weekday: 1,
                    calendar: calendar
                )
                values.sunday += floorMinutes(sunday.paidDuration)
                premiumReliable = premiumReliable && sunday.reliable
                warnings.append(contentsOf: sunday.warnings)

                if !genericHolidays.isEmpty {
                    let holiday = PublicHolidayPremiumPolicyV2.paidOverlap(
                        session: session,
                        rangeStart: rangeStart,
                        rangeEnd: rangeEnd,
                        holidayDates: genericHolidays,
                        calendar: calendar
                    )
                    values.publicHoliday += floorMinutes(holiday.paidDuration)
                    premiumReliable = premiumReliable && holiday.reliable
                    warnings.append(contentsOf: holiday.warnings)
                }
            }
            premiumByWeek[key] = values
        }

        if let mayFirst = FrenchPublicHolidayCalendarV2.mayFirst(period.year) {
            let result = paidOverlap(
                sessions: relevantSessions,
                rangeStart: month.start,
                rangeEnd: month.end,
                dates: [mayFirst],
                calendar: calendar
            )
            if result.paidDuration > 0 {
                premiumReliable = false
                warnings.append(mayFirstWorkedWarning)
            }
            if !result.reliable {
                premiumReliable = false
                warnings.append(contentsOf: result.warnings)
            }
        } else {
            premiumReliable = false
            warnings.append(calendarWarning)
        }

        if !unresolvedHolidays.isEmpty {
            let unresolved = paidOverlap(
                sessions: relevantSessions,
                rangeStart: month.start,
                rangeEnd: month.end,
                dates: unresolvedHolidays,
                calendar: calendar
            )
            if unresolved.paidDuration > 0 {
                premiumReliable = false
                warnings.append(unresolvedHolidayWorkedWarning)
            }
            if !unresolved.reliable {
                premiumReliable = false
                warnings.append(contentsOf: unresolved.warnings)
            }
        }

        return result(
            from: paidWork,
            premiumByWeek: premiumByWeek,
            paidTimeReliable: paidWork.reliable,
            premiumReliable: premiumReliable,
            payrollRulesReliable: payrollRulesReliable,
            warnings: warnings
        )
    }

    private static func result(
        from paidWork: SalaryPaidWorkAggregationV2,
        premiumByWeek: [WeekKey: PremiumMinutes],
        paidTimeReliable: Bool,
        premiumReliable: Bool,
        payrollRulesReliable: Bool,
        warnings: [String]
    ) -> SalaryPayrollWeekEvidenceResultV2 {
        let weeks = paidWork.weeks.map { paidWeek in
            let values = premiumByWeek[
                WeekKey(
                    year: paidWeek.yearForWeekOfYear,
                    week: paidWeek.weekOfYear
                )
            ] ?? PremiumMinutes()
            return PayrollWeekV2(
                paidMinutes: paidWeek.paidMinutes,
                nightMinutes: values.night,
                saturdayMinutes: values.saturday,
                sundayMinutes: values.sunday,
                publicHolidayMinutes: values.publicHoliday
            )
        }
        let evidence = PayrollInputEvidenceV2(
            paidTimeReliable: paidTimeReliable,
            premiumTimeBreakdownReliable: premiumReliable,
            payrollRulesReliable: payrollRulesReliable
        )
        return SalaryPayrollWeekEvidenceResultV2(
            weeks: weeks,
            evidence: evidence,
            warnings: unique(warnings + evidence.warnings)
        )
    }

    private static func weekdayPaidOverlap(
        session: SalarySessionFactV2,
        rangeStart: Date,
        rangeEnd: Date,
        weekday: Int,
        calendar: Calendar
    ) -> SalaryPaidOverlapResultV2 {
        guard rangeEnd > rangeStart else {
            return SalaryPaidOverlapResultV2(
                paidDuration: 0,
                reliable: true,
                warnings: []
            )
        }

        var day = calendar.startOfDay(for: rangeStart)
        var total: TimeInterval = 0
        var reliable = true
        var warnings: [String] = []

        while day < rangeEnd {
            guard let next = calendar.date(byAdding: .day, value: 1, to: day),
                  next > day else {
                return SalaryPaidOverlapResultV2(
                    paidDuration: total,
                    reliable: false,
                    warnings: unique(warnings + [calendarWarning])
                )
            }
            if calendar.component(.weekday, from: day) == weekday {
                let result = SalaryPaidOverlapPolicyV2.paidOverlap(
                    session: session,
                    rangeStart: max(rangeStart, day),
                    rangeEnd: min(rangeEnd, next)
                )
                total += result.paidDuration
                reliable = reliable && result.reliable
                warnings.append(contentsOf: result.warnings)
            }
            day = next
        }

        return SalaryPaidOverlapResultV2(
            paidDuration: max(0, total),
            reliable: reliable,
            warnings: unique(warnings)
        )
    }

    private static func paidOverlap(
        sessions: [SalarySessionFactV2],
        rangeStart: Date,
        rangeEnd: Date,
        dates: Set<PayrollCivilDateV2>,
        calendar: Calendar
    ) -> SalaryPaidOverlapResultV2 {
        var total: TimeInterval = 0
        var reliable = true
        var warnings: [String] = []
        for session in sessions {
            let result = PublicHolidayPremiumPolicyV2.paidOverlap(
                session: session,
                rangeStart: rangeStart,
                rangeEnd: rangeEnd,
                holidayDates: dates,
                calendar: calendar
            )
            total += result.paidDuration
            reliable = reliable && result.reliable
            warnings.append(contentsOf: result.warnings)
        }
        return SalaryPaidOverlapResultV2(
            paidDuration: max(0, total),
            reliable: reliable,
            warnings: unique(warnings)
        )
    }

    private static func weekInterval(
        _ key: WeekKey,
        calendar: Calendar
    ) -> DateInterval? {
        var components = DateComponents()
        components.calendar = calendar
        components.timeZone = calendar.timeZone
        components.yearForWeekOfYear = key.year
        components.weekOfYear = key.week
        components.weekday = 2
        components.hour = 0
        components.minute = 0
        components.second = 0

        guard let start = calendar.date(from: components),
              let end = calendar.date(byAdding: .day, value: 7, to: start),
              end > start else {
            return nil
        }
        return DateInterval(start: start, end: end)
    }

    private static func monthInterval(
        period: YearMonthV2,
        calendar: Calendar
    ) -> DateInterval? {
        let nextYear: Int
        let nextMonth: Int
        if period.month == 12 {
            guard period.year < Int.max else { return nil }
            nextYear = period.year + 1
            nextMonth = 1
        } else {
            nextYear = period.year
            nextMonth = period.month + 1
        }

        let startComponents = DateComponents(
            calendar: calendar,
            timeZone: calendar.timeZone,
            year: period.year,
            month: period.month,
            day: 1,
            hour: 0,
            minute: 0,
            second: 0
        )
        let endComponents = DateComponents(
            calendar: calendar,
            timeZone: calendar.timeZone,
            year: nextYear,
            month: nextMonth,
            day: 1,
            hour: 0,
            minute: 0,
            second: 0
        )
        guard let start = calendar.date(from: startComponents),
              let end = calendar.date(from: endComponents),
              end > start else {
            return nil
        }
        return DateInterval(start: start, end: end)
    }

    private static func potentiallyTouches(
        _ session: SalarySessionFactV2,
        start: Date,
        end: Date
    ) -> Bool {
        guard session.entry < end else { return false }
        guard let exit = session.exit else { return session.entry >= start || session.entry < end }
        return exit > start
    }

    private static func normalizedEmployerId(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func floorMinutes(_ duration: TimeInterval) -> Int {
        guard duration.isFinite, duration > 0 else { return 0 }
        let value = floor(duration / 60.0)
        guard value <= Double(Int.max) else { return Int.max }
        return Int(value)
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }

    private struct PremiumMinutes {
        var night = 0
        var saturday = 0
        var sunday = 0
        var publicHoliday = 0
    }

    private struct WeekKey: Hashable {
        let year: Int
        let week: Int
    }
}
