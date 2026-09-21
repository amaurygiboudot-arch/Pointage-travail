import Foundation

/// Fait neutre transmis par le runtime de pointage vers Salaire V2.
///
/// Cette structure ne connaît ni métier, ni convention collective, ni type de contrat.
/// Elle transporte uniquement des faits déjà enregistrés par le moteur de pointage.
struct SalarySessionFactV2: Equatable {
    let id: String
    let entry: Date
    let exit: Date?
    let employerId: String?
    let pauses: [PaidPauseFactV2]
}

struct SalaryPaidWeekV2: Equatable {
    let yearForWeekOfYear: Int
    let weekOfYear: Int
    let paidMinutes: Int
}

struct SalaryPaidWorkAggregationV2: Equatable {
    let weeks: [SalaryPaidWeekV2]
    let completedSessionCount: Int
    let reliable: Bool
    let warnings: [String]

    var totalPaidMinutes: Int {
        weeks.reduce(0) { $0 + $1.paidMinutes }
    }
}

/// Répartit les faits de pointage d'une entreprise sur le mois demandé puis par semaine ISO.
///
/// Aucune règle de paie n'est inventée ici. Une donnée ambiguë rend l'agrégat non fiable :
/// l'appelant peut alors afficher les avertissements mais ne doit pas publier un montant de paie
/// comme s'il était confirmé.
enum SalaryPaidWorkAggregatorV2 {
    static let sourceWarning =
        "Temps de travail : historique de pointage non fiable ; calcul automatique bloqué."
    static let employerWarning =
        "Temps de travail : entreprise invalide ou absente ; calcul automatique bloqué."
    static let openSessionWarning =
        "Temps de travail : un pointage de la période est encore ouvert ; le total payé reste à confirmer."
    static let invalidSessionWarning =
        "Temps de travail : un pointage de la période est incohérent ; le total payé reste à confirmer."
    static let unresolvedPauseWarning =
        "Temps de travail : une pause de la période n'a pas un statut payé/non payé fiable ; le total payé reste à confirmer."
    static let conflictingPauseWarning =
        "Temps de travail : des pauses qui se chevauchent ont des statuts payé/non payé contradictoires ; le total payé reste à confirmer."
    static let overlapWarning =
        "Temps de travail : des pointages de la même entreprise se chevauchent ; le total payé reste à confirmer."
    static let unassignedEmployerWarning =
        "Temps de travail : un pointage de la période n'est rattaché à aucune entreprise ; le total payé reste à confirmer."

    static func aggregate(
        sessions: [SalarySessionFactV2],
        employerId rawEmployerId: String,
        period: YearMonthV2,
        sourceReliable: Bool = true,
        calendar inputCalendar: Calendar = .current
    ) -> SalaryPaidWorkAggregationV2 {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = inputCalendar.timeZone
        calendar.locale = inputCalendar.locale
        calendar.firstWeekday = 2
        calendar.minimumDaysInFirstWeek = 4

        let employerId = rawEmployerId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !employerId.isEmpty else {
            return SalaryPaidWorkAggregationV2(
                weeks: [],
                completedSessionCount: 0,
                reliable: false,
                warnings: [employerWarning]
            )
        }

        let nextYear: Int
        let nextMonth: Int
        if period.month == 12 {
            guard period.year < Int.max else {
                return SalaryPaidWorkAggregationV2(
                    weeks: [],
                    completedSessionCount: 0,
                    reliable: false,
                    warnings: [invalidSessionWarning]
                )
            }
            nextYear = period.year + 1
            nextMonth = 1
        } else {
            nextYear = period.year
            nextMonth = period.month + 1
        }

        // Construire les deux bornes indépendamment évite de conserver une heure normalisée
        // lorsque le premier jour du mois subit un saut DST à minuit.
        guard let monthStart = calendar.date(from: DateComponents(
            calendar: calendar,
            timeZone: calendar.timeZone,
            year: period.year,
            month: period.month,
            day: 1,
            hour: 0,
            minute: 0,
            second: 0
        )),
        let monthEnd = calendar.date(from: DateComponents(
            calendar: calendar,
            timeZone: calendar.timeZone,
            year: nextYear,
            month: nextMonth,
            day: 1,
            hour: 0,
            minute: 0,
            second: 0
        )),
        monthEnd > monthStart else {
            return SalaryPaidWorkAggregationV2(
                weeks: [],
                completedSessionCount: 0,
                reliable: false,
                warnings: [invalidSessionWarning]
            )
        }

        var reliable = sourceReliable
        var warnings: [String] = sourceReliable ? [] : [sourceWarning]
        let unassignedEmployerTouchesPeriod = sessions.contains { session in
            normalizedEmployerId(session.employerId) == nil &&
                potentiallyTouchesPeriod(session, monthStart: monthStart, monthEnd: monthEnd)
        }
        if unassignedEmployerTouchesPeriod {
            reliable = false
            warnings.append(unassignedEmployerWarning)
        }
        var completedSessionCount = 0
        var paidMinutesByWeek: [WeekKey: Int] = [:]
        var coveredIntervals: [(start: Date, end: Date)] = []

        for session in sessions where normalizedEmployerId(session.employerId) == employerId {
            guard session.entry.timeIntervalSince1970.isFinite,
                  session.exit?.timeIntervalSince1970.isFinite ?? true else {
                reliable = false
                warnings.append(invalidSessionWarning)
                continue
            }

            if let exit = session.exit, exit <= session.entry {
                if unorderedEndpointsTouchPeriod(
                    entry: session.entry,
                    exit: exit,
                    monthStart: monthStart,
                    monthEnd: monthEnd
                ) {
                    reliable = false
                    warnings.append(invalidSessionWarning)
                }
                continue
            }

            guard potentiallyTouchesPeriod(session, monthStart: monthStart, monthEnd: monthEnd) else {
                continue
            }

            guard let exit = session.exit else {
                reliable = false
                warnings.append(openSessionWarning)
                continue
            }

            let clippedStart = max(session.entry, monthStart)
            let clippedEnd = min(exit, monthEnd)
            guard clippedEnd > clippedStart else { continue }

            // Les défauts structurels de pause ne contaminent que la période demandée.
            // Une pause malformée entièrement hors de ce mois n'annule pas du travail valide du mois.
            guard pausesAreStructurallyUsable(
                session.pauses,
                sessionStart: session.entry,
                sessionEnd: exit,
                rangeStart: clippedStart,
                rangeEnd: clippedEnd
            ) else {
                reliable = false
                warnings.append(invalidSessionWarning)
                continue
            }

            if hasConflictingPauseClassifications(
                session.pauses,
                rangeStart: clippedStart,
                rangeEnd: clippedEnd
            ) {
                reliable = false
                warnings.append(conflictingPauseWarning)
                continue
            }

            completedSessionCount += 1
            coveredIntervals.append((clippedStart, clippedEnd))

            var cursor = clippedStart
            while cursor < clippedEnd {
                guard let weekInterval = calendar.dateInterval(of: .weekOfYear, for: cursor),
                      weekInterval.end > cursor else {
                    reliable = false
                    warnings.append(invalidSessionWarning)
                    break
                }

                let sliceEnd = min(clippedEnd, weekInterval.end)
                let slicePauses = session.pauses.filter {
                    pausePotentiallyTouchesRange($0, rangeStart: cursor, rangeEnd: sliceEnd)
                }
                let assessment = PaidTimePolicyV2.assess(
                    sessionStart: cursor,
                    sessionEnd: sliceEnd,
                    pauses: slicePauses,
                    until: sliceEnd
                )
                if !assessment.reliable {
                    reliable = false
                    warnings.append(unresolvedPauseWarning)
                }

                let components = calendar.dateComponents(
                    [.yearForWeekOfYear, .weekOfYear],
                    from: cursor
                )
                guard let weekYear = components.yearForWeekOfYear,
                      let weekOfYear = components.weekOfYear else {
                    reliable = false
                    warnings.append(invalidSessionWarning)
                    break
                }

                let key = WeekKey(year: weekYear, week: weekOfYear)
                let paidSeconds = max(0, assessment.paidDuration)
                if paidSeconds.isFinite {
                    let roundedMinutes = Int(floor(paidSeconds / 60.0))
                    if paidSeconds > 0 || !assessment.reliable {
                        paidMinutesByWeek[key, default: 0] += roundedMinutes
                    }
                } else {
                    reliable = false
                    warnings.append(invalidSessionWarning)
                }
                cursor = sliceEnd
            }
        }

        let sortedIntervals = coveredIntervals.sorted {
            $0.start == $1.start ? $0.end < $1.end : $0.start < $1.start
        }
        if zip(sortedIntervals, sortedIntervals.dropFirst()).contains(where: { previous, next in
            next.start < previous.end
        }) {
            reliable = false
            warnings.append(overlapWarning)
        }

        let weeks = paidMinutesByWeek.keys.sorted().map { key in
            SalaryPaidWeekV2(
                yearForWeekOfYear: key.year,
                weekOfYear: key.week,
                paidMinutes: paidMinutesByWeek[key, default: 0]
            )
        }

        return SalaryPaidWorkAggregationV2(
            weeks: weeks,
            completedSessionCount: completedSessionCount,
            reliable: reliable,
            warnings: unique(warnings)
        )
    }

    private static func normalizedEmployerId(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private static func potentiallyTouchesPeriod(
        _ session: SalarySessionFactV2,
        monthStart: Date,
        monthEnd: Date
    ) -> Bool {
        guard session.entry < monthEnd else { return false }
        if session.entry >= monthStart { return true }
        guard let exit = session.exit else { return true }
        return exit > monthStart
    }

    private static func unorderedEndpointsTouchPeriod(
        entry: Date,
        exit: Date,
        monthStart: Date,
        monthEnd: Date
    ) -> Bool {
        func isInside(_ value: Date) -> Bool {
            value >= monthStart && value < monthEnd
        }
        if isInside(entry) || isInside(exit) { return true }

        let lower = min(entry, exit)
        let upper = max(entry, exit)
        return lower < monthEnd && upper > monthStart
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

        guard let end = pause.end else {
            // Sans fin connue, une pause commencée avant la fin de la tranche peut encore la toucher.
            return pause.start < rangeEnd
        }
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

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }

    private struct WeekKey: Hashable, Comparable {
        let year: Int
        let week: Int

        static func < (lhs: WeekKey, rhs: WeekKey) -> Bool {
            lhs.year == rhs.year ? lhs.week < rhs.week : lhs.year < rhs.year
        }
    }
}
