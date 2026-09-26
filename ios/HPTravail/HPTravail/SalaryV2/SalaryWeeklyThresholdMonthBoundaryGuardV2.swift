import Foundation

struct SalaryWeeklyThresholdMonthBoundaryResultV2: Equatable {
    struct BoundaryWeek: Equatable {
        let yearForWeekOfYear: Int
        let weekOfYear: Int
        let fullWeekPaidMinutes: Int
        let inMonthPaidMinutes: Int
    }

    let reliable: Bool
    let affectedWeeks: [BoundaryWeek]
    let warnings: [String]
}

/// Garde-fou iOS des seuils hebdomadaires lorsque le mois coupe une semaine ISO.
///
/// Les moteurs mensuels tronquent naturellement les sessions aux bornes du mois, mais les heures
/// supplémentaires/complémentaires restent des mécanismes hebdomadaires. Si du temps payé hors du
/// mois contribue à franchir le seuil d'une semaine de bord, le brut du mois reste à confirmer.
///
/// Cette couche ne recalcule aucun euro et ne suppose aucune règle métier.
enum SalaryWeeklyThresholdMonthBoundaryGuardV2 {
    static let contextWarning =
        "Seuil hebdomadaire : une semaine chevauche deux mois et dépasse le seuil avec du temps payé hors du mois ; la répartition des heures supplémentaires/complémentaires du mois reste à confirmer."
    static let futureContextWarning =
        "Seuil hebdomadaire : la semaine de fin de mois n'est pas encore terminée ; le contexte hebdomadaire complet n'est pas disponible."
    static let sourceWarning =
        "Seuil hebdomadaire : historique de pointage non fiable sur une semaine de bord ; qualification hebdomadaire bloquée."

    static func assess(
        sessions: [SalarySessionFactV2],
        employerId rawEmployerId: String,
        period: YearMonthV2,
        weeklyThresholdMinutes: Int,
        sourceReliable: Bool,
        calendar inputCalendar: Calendar = .current,
        now: Date = Date()
    ) -> SalaryWeeklyThresholdMonthBoundaryResultV2 {
        let employerId = rawEmployerId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard sourceReliable,
              !employerId.isEmpty,
              weeklyThresholdMinutes > 0,
              let month = monthInterval(period: period, calendar: inputCalendar) else {
            return blocked([sourceWarning])
        }

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = inputCalendar.timeZone
        calendar.locale = inputCalendar.locale
        calendar.firstWeekday = 2
        calendar.minimumDaysInFirstWeek = 4

        guard let firstWeek = calendar.dateInterval(of: .weekOfYear, for: month.start),
              let lastInstant = calendar.date(byAdding: .second, value: -1, to: month.end),
              let lastWeek = calendar.dateInterval(of: .weekOfYear, for: lastInstant) else {
            return blocked([sourceWarning])
        }

        let intervals = uniqueIntervals([firstWeek, lastWeek])
        var reliable = true
        var affected: [SalaryWeeklyThresholdMonthBoundaryResultV2.BoundaryWeek] = []
        var warnings: [String] = []
        let thresholdSeconds = Double(weeklyThresholdMinutes) * 60.0

        for week in intervals {
            if week.start >= month.start && week.end <= month.end { continue }

            if week.end > now {
                reliable = false
                warnings.append(futureContextWarning)
                continue
            }

            if sessions.contains(where: {
                normalizedEmployerId($0.employerId) == nil
                    && potentiallyTouches($0, start: week.start, end: week.end)
            }) {
                reliable = false
                warnings.append(sourceWarning)
                continue
            }

            let relevant = sessions.filter {
                normalizedEmployerId($0.employerId) == employerId
                    && potentiallyTouches($0, start: week.start, end: week.end)
            }

            if hasInvalidOrOpenSession(relevant) || hasOverlappingSessions(relevant, range: week) {
                reliable = false
                warnings.append(sourceWarning)
                continue
            }

            var fullWeekPaid: TimeInterval = 0
            var inMonthPaid: TimeInterval = 0
            let inMonthStart = max(week.start, month.start)
            let inMonthEnd = min(week.end, month.end)

            for session in relevant {
                let full = SalaryPaidOverlapPolicyV2.paidOverlap(
                    session: session,
                    rangeStart: week.start,
                    rangeEnd: week.end
                )
                guard full.reliable else {
                    reliable = false
                    warnings.append(sourceWarning)
                    continue
                }
                fullWeekPaid += full.paidDuration

                if inMonthEnd > inMonthStart {
                    let monthPart = SalaryPaidOverlapPolicyV2.paidOverlap(
                        session: session,
                        rangeStart: inMonthStart,
                        rangeEnd: inMonthEnd
                    )
                    guard monthPart.reliable else {
                        reliable = false
                        warnings.append(sourceWarning)
                        continue
                    }
                    inMonthPaid += monthPart.paidDuration
                }
            }

            let outsidePaid = max(0, fullWeekPaid - inMonthPaid)
            if fullWeekPaid > thresholdSeconds && outsidePaid > 0 {
                reliable = false
                let components = calendar.dateComponents(
                    [.yearForWeekOfYear, .weekOfYear],
                    from: week.start
                )
                if let year = components.yearForWeekOfYear,
                   let weekOfYear = components.weekOfYear {
                    affected.append(
                        .init(
                            yearForWeekOfYear: year,
                            weekOfYear: weekOfYear,
                            fullWeekPaidMinutes: Int(floor(fullWeekPaid / 60.0)),
                            inMonthPaidMinutes: Int(floor(inMonthPaid / 60.0))
                        )
                    )
                }
                warnings.append(contextWarning)
            }
        }

        return SalaryWeeklyThresholdMonthBoundaryResultV2(
            reliable: reliable,
            affectedWeeks: affected,
            warnings: unique(warnings)
        )
    }

    private static func monthInterval(
        period: YearMonthV2,
        calendar inputCalendar: Calendar
    ) -> DateInterval? {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = inputCalendar.timeZone
        calendar.locale = inputCalendar.locale

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

        let start = calendar.date(
            from: DateComponents(
                calendar: calendar,
                timeZone: calendar.timeZone,
                year: period.year,
                month: period.month,
                day: 1,
                hour: 0,
                minute: 0,
                second: 0
            )
        )
        let end = calendar.date(
            from: DateComponents(
                calendar: calendar,
                timeZone: calendar.timeZone,
                year: nextYear,
                month: nextMonth,
                day: 1,
                hour: 0,
                minute: 0,
                second: 0
            )
        )
        guard let start, let end, end > start else { return nil }
        return DateInterval(start: start, end: end)
    }

    private static func uniqueIntervals(_ values: [DateInterval]) -> [DateInterval] {
        var seen = Set<String>()
        return values.filter {
            let key = "\($0.start.timeIntervalSince1970)|\($0.end.timeIntervalSince1970)"
            return seen.insert(key).inserted
        }
    }

    private static func hasInvalidOrOpenSession(_ sessions: [SalarySessionFactV2]) -> Bool {
        sessions.contains {
            !$0.entry.timeIntervalSince1970.isFinite
                || $0.exit == nil
                || !($0.exit?.timeIntervalSince1970.isFinite ?? false)
                || ($0.exit ?? $0.entry) <= $0.entry
        }
    }

    private static func hasOverlappingSessions(
        _ sessions: [SalarySessionFactV2],
        range: DateInterval
    ) -> Bool {
        let intervals = sessions.compactMap { session -> DateInterval? in
            guard let exit = session.exit else { return nil }
            let start = max(session.entry, range.start)
            let end = min(exit, range.end)
            guard end > start else { return nil }
            return DateInterval(start: start, end: end)
        }
        .sorted { $0.start == $1.start ? $0.end < $1.end : $0.start < $1.start }

        return zip(intervals, intervals.dropFirst()).contains {
            $1.start < $0.end
        }
    }

    private static func potentiallyTouches(
        _ session: SalarySessionFactV2,
        start: Date,
        end: Date
    ) -> Bool {
        guard session.entry < end else { return false }
        guard let exit = session.exit else { return true }
        return exit > start
    }

    private static func normalizedEmployerId(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private static func blocked(_ warnings: [String]) -> SalaryWeeklyThresholdMonthBoundaryResultV2 {
        SalaryWeeklyThresholdMonthBoundaryResultV2(
            reliable: false,
            affectedWeeks: [],
            warnings: unique(warnings)
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
