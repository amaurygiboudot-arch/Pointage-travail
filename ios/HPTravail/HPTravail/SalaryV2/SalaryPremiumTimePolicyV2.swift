import Foundation

struct SalaryPaidOverlapResultV2: Equatable {
    let paidDuration: TimeInterval
    let reliable: Bool
    let warnings: [String]
}

/// Calcule uniquement le temps payé d'une session dans une tranche donnée.
///
/// Cette couche ne connaît aucun métier ni taux de paie. Une pause non payée est déduite,
/// une pause explicitement payée reste du temps payé et une pause inconnue/contradictoire
/// bloque la fiabilité au lieu de devenir silencieusement zéro.
enum SalaryPaidOverlapPolicyV2 {
    static let invalidSessionWarning =
        "Temps majorable : pointage incohérent ; chevauchement payé non certifiable."
    static let unresolvedPauseWarning =
        "Temps majorable : pause sans statut payé/non payé fiable ; chevauchement non certifiable."
    static let conflictingPauseWarning =
        "Temps majorable : pauses superposées avec statuts contradictoires ; chevauchement non certifiable."

    static func paidOverlap(
        session: SalarySessionFactV2,
        rangeStart: Date,
        rangeEnd: Date
    ) -> SalaryPaidOverlapResultV2 {
        guard rangeEnd > rangeStart,
              session.entry.timeIntervalSince1970.isFinite,
              let sessionEnd = session.exit,
              sessionEnd.timeIntervalSince1970.isFinite,
              sessionEnd > session.entry else {
            return blocked([invalidSessionWarning])
        }

        let start = max(session.entry, rangeStart)
        let end = min(sessionEnd, rangeEnd)
        guard end > start else {
            return SalaryPaidOverlapResultV2(
                paidDuration: 0,
                reliable: true,
                warnings: []
            )
        }

        guard pausesAreStructurallyUsable(
            session.pauses,
            sessionStart: session.entry,
            sessionEnd: sessionEnd,
            rangeStart: start,
            rangeEnd: end
        ) else {
            return blocked([invalidSessionWarning])
        }

        guard !hasConflictingPauseClassifications(
            session.pauses,
            rangeStart: start,
            rangeEnd: end
        ) else {
            return blocked([conflictingPauseWarning])
        }

        let pauses = session.pauses.filter {
            pausePotentiallyTouchesRange($0, rangeStart: start, rangeEnd: end)
        }
        let assessment = PaidTimePolicyV2.assess(
            sessionStart: start,
            sessionEnd: end,
            pauses: pauses,
            until: end
        )
        guard assessment.paidDuration.isFinite else {
            return blocked([invalidSessionWarning])
        }
        guard assessment.reliable else {
            return SalaryPaidOverlapResultV2(
                paidDuration: max(0, assessment.paidDuration),
                reliable: false,
                warnings: [unresolvedPauseWarning]
            )
        }

        return SalaryPaidOverlapResultV2(
            paidDuration: max(0, assessment.paidDuration),
            reliable: true,
            warnings: []
        )
    }

    private static func pausesAreStructurallyUsable(
        _ pauses: [PaidPauseFactV2],
        sessionStart: Date,
        sessionEnd: Date,
        rangeStart: Date,
        rangeEnd: Date
    ) -> Bool {
        pauses.allSatisfy { pause in
            guard pausePotentiallyTouchesRange(
                pause,
                rangeStart: rangeStart,
                rangeEnd: rangeEnd
            ) else {
                return true
            }

            guard pause.start.timeIntervalSince1970.isFinite,
                  pause.start >= sessionStart,
                  let end = pause.end,
                  end.timeIntervalSince1970.isFinite,
                  end > pause.start,
                  end <= sessionEnd else {
                return false
            }
            return true
        }
    }

    private static func pausePotentiallyTouchesRange(
        _ pause: PaidPauseFactV2,
        rangeStart: Date,
        rangeEnd: Date
    ) -> Bool {
        guard pause.start.timeIntervalSince1970.isFinite else { return true }
        guard let end = pause.end else { return pause.start < rangeEnd }
        guard end.timeIntervalSince1970.isFinite else { return true }

        let lower = min(pause.start, end)
        let upper = max(pause.start, end)
        if lower == upper {
            return lower >= rangeStart && lower < rangeEnd
        }
        return lower < rangeEnd && upper > rangeStart
    }

    private static func hasConflictingPauseClassifications(
        _ pauses: [PaidPauseFactV2],
        rangeStart: Date,
        rangeEnd: Date
    ) -> Bool {
        let classified = pauses.compactMap { pause -> (start: Date, end: Date, paid: Bool)? in
            guard let rawEnd = pause.end, let paid = pause.paid else { return nil }
            let start = max(pause.start, rangeStart)
            let end = min(rawEnd, rangeEnd)
            guard end > start else { return nil }
            return (start, end, paid)
        }.sorted {
            $0.start == $1.start ? $0.end < $1.end : $0.start < $1.start
        }

        for index in classified.indices {
            let current = classified[index]
            var nextIndex = classified.index(after: index)
            while nextIndex < classified.endIndex {
                let candidate = classified[nextIndex]
                if candidate.start >= current.end { break }
                if candidate.end > current.start && candidate.paid != current.paid {
                    return true
                }
                nextIndex = classified.index(after: nextIndex)
            }
        }
        return false
    }

    private static func blocked(_ warnings: [String]) -> SalaryPaidOverlapResultV2 {
        SalaryPaidOverlapResultV2(
            paidDuration: 0,
            reliable: false,
            warnings: warnings
        )
    }
}

struct NightPremiumRuleV2: Equatable {
    let startMinute: Int
    let endMinute: Int
    let multiplier: Double

    init?(
        startMinute: Int,
        endMinute: Int,
        multiplier: Double
    ) {
        guard (0..<(24 * 60)).contains(startMinute),
              (0..<(24 * 60)).contains(endMinute),
              startMinute != endMinute,
              multiplier.isFinite,
              multiplier >= 1.0 else {
            return nil
        }
        self.startMinute = startMinute
        self.endMinute = endMinute
        self.multiplier = multiplier
    }

    var percentage: Double { (multiplier - 1.0) * 100.0 }
}

/// Calcule les secondes PAYÉES qui tombent dans une plage de nuit explicitement fournie.
/// La plage de poste ou l'horaire habituel n'est jamais utilisé comme substitut.
enum NightPremiumPolicyV2 {
    static let calendarWarning =
        "Nuit : une borne horaire locale n'a pas pu être construite sans ambiguïté ; minutes de nuit non certifiables."

    static func paidOverlap(
        session: SalarySessionFactV2,
        rangeStart: Date,
        rangeEnd: Date,
        rule: NightPremiumRuleV2,
        calendar inputCalendar: Calendar = .current
    ) -> SalaryPaidOverlapResultV2 {
        guard rangeEnd > rangeStart else {
            return SalaryPaidOverlapResultV2(paidDuration: 0, reliable: true, warnings: [])
        }

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = inputCalendar.timeZone
        calendar.locale = inputCalendar.locale

        let startOfRangeDay = calendar.startOfDay(for: rangeStart)
        guard let firstDay = calendar.date(byAdding: .day, value: -1, to: startOfRangeDay),
              let endOfRangeDay = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: rangeEnd)) else {
            return SalaryPaidOverlapResultV2(
                paidDuration: 0,
                reliable: false,
                warnings: [calendarWarning]
            )
        }

        var total: TimeInterval = 0
        var reliable = true
        var warnings: [String] = []
        var day = firstDay

        while day <= endOfRangeDay {
            guard let start = exactLocalTime(
                minuteOfDay: rule.startMinute,
                on: day,
                calendar: calendar
            ) else {
                reliable = false
                warnings.append(calendarWarning)
                day = calendar.date(byAdding: .day, value: 1, to: day) ?? endOfRangeDay.addingTimeInterval(1)
                continue
            }

            let endBase: Date
            if rule.endMinute <= rule.startMinute {
                guard let nextDay = calendar.date(byAdding: .day, value: 1, to: day) else {
                    reliable = false
                    warnings.append(calendarWarning)
                    break
                }
                endBase = nextDay
            } else {
                endBase = day
            }

            guard let end = exactLocalTime(
                minuteOfDay: rule.endMinute,
                on: endBase,
                calendar: calendar
            ) else {
                reliable = false
                warnings.append(calendarWarning)
                day = calendar.date(byAdding: .day, value: 1, to: day) ?? endOfRangeDay.addingTimeInterval(1)
                continue
            }

            let from = max(rangeStart, start)
            let to = min(rangeEnd, end)
            if to > from {
                let result = SalaryPaidOverlapPolicyV2.paidOverlap(
                    session: session,
                    rangeStart: from,
                    rangeEnd: to
                )
                total += result.paidDuration
                reliable = reliable && result.reliable
                warnings.append(contentsOf: result.warnings)
            }

            guard let nextDay = calendar.date(byAdding: .day, value: 1, to: day) else {
                reliable = false
                warnings.append(calendarWarning)
                break
            }
            day = nextDay
        }

        return SalaryPaidOverlapResultV2(
            paidDuration: max(0, total),
            reliable: reliable,
            warnings: unique(warnings)
        )
    }

    private static func exactLocalTime(
        minuteOfDay: Int,
        on day: Date,
        calendar: Calendar
    ) -> Date? {
        let hour = minuteOfDay / 60
        let minute = minuteOfDay % 60
        let dayComponents = calendar.dateComponents([.year, .month, .day], from: day)
        guard let year = dayComponents.year,
              let month = dayComponents.month,
              let dayValue = dayComponents.day else {
            return nil
        }

        var components = DateComponents()
        components.calendar = calendar
        components.timeZone = calendar.timeZone
        components.year = year
        components.month = month
        components.day = dayValue
        components.hour = hour
        components.minute = minute
        components.second = 0

        guard let value = calendar.date(from: components) else { return nil }
        let verified = calendar.dateComponents(
            [.year, .month, .day, .hour, .minute],
            from: value
        )
        guard verified.year == year,
              verified.month == month,
              verified.day == dayValue,
              verified.hour == hour,
              verified.minute == minute else {
            return nil
        }
        return value
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}

/// Calcule le temps payé tombant sur des dates civiles déjà déterminées.
/// La sélection des dates appartient au calendrier territorial ; cette couche ne choisit aucun taux.
enum PublicHolidayPremiumPolicyV2 {
    static let calendarWarning =
        "Jour férié : impossible de construire une borne calendaire locale fiable ; minutes fériées non certifiables."

    static func paidOverlap(
        session: SalarySessionFactV2,
        rangeStart: Date,
        rangeEnd: Date,
        holidayDates: Set<PayrollCivilDateV2>,
        calendar inputCalendar: Calendar = .current
    ) -> SalaryPaidOverlapResultV2 {
        guard rangeEnd > rangeStart, !holidayDates.isEmpty else {
            return SalaryPaidOverlapResultV2(paidDuration: 0, reliable: true, warnings: [])
        }

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = inputCalendar.timeZone
        calendar.locale = inputCalendar.locale

        var total: TimeInterval = 0
        var reliable = true
        var warnings: [String] = []

        for holiday in holidayDates.sorted() {
            var components = DateComponents()
            components.calendar = calendar
            components.timeZone = calendar.timeZone
            components.year = holiday.year
            components.month = holiday.month
            components.day = holiday.day
            components.hour = 0
            components.minute = 0
            components.second = 0

            guard let dayStart = calendar.date(from: components),
                  let dayEnd = calendar.date(byAdding: .day, value: 1, to: dayStart),
                  dayEnd > dayStart else {
                reliable = false
                warnings.append(calendarWarning)
                continue
            }

            let from = max(rangeStart, dayStart)
            let to = min(rangeEnd, dayEnd)
            guard to > from else { continue }

            let result = SalaryPaidOverlapPolicyV2.paidOverlap(
                session: session,
                rangeStart: from,
                rangeEnd: to
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

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
