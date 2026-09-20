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
    static let overlapWarning =
        "Temps de travail : des pointages de la même entreprise se chevauchent ; le total payé reste à confirmer."

    static func aggregate(
        sessions: [SalarySessionFactV2],
        employerId rawEmployerId: String,
        period: YearMonthV2,
        sourceReliable: Bool = true,
        calendar inputCalendar: Calendar = .current
    ) -> SalaryPaidWorkAggregationV2 {
        var calendar = inputCalendar
        calendar.firstWeekday = 2 // lundi
        calendar.minimumDaysInFirstWeek = 4 // définition ISO-8601

        let employerId = rawEmployerId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !employerId.isEmpty else {
            return SalaryPaidWorkAggregationV2(
                weeks: [],
                completedSessionCount: 0,
                reliable: false,
                warnings: [employerWarning]
            )
        }

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
        let monthEnd = calendar.date(byAdding: .month, value: 1, to: monthStart),
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
        var completedSessionCount = 0
        var paidMinutesByWeek: [WeekKey: Int] = [:]
        var coveredIntervals: [(start: Date, end: Date)] = []

        for session in sessions where normalizedEmployerId(session.employerId) == employerId {
            guard potentiallyTouchesPeriod(session, monthStart: monthStart, monthEnd: monthEnd) else {
                continue
            }

            guard let exit = session.exit else {
                reliable = false
                warnings.append(openSessionWarning)
                continue
            }
            guard exit > session.entry,
                  session.entry.timeIntervalSince1970.isFinite,
                  exit.timeIntervalSince1970.isFinite,
                  pausesAreStructurallyUsable(session.pauses, sessionStart: session.entry, sessionEnd: exit) else {
                reliable = false
                warnings.append(invalidSessionWarning)
                continue
            }

            let clippedStart = max(session.entry, monthStart)
            let clippedEnd = min(exit, monthEnd)
            guard clippedEnd > clippedStart else { continue }

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
                let assessment = PaidTimePolicyV2.assess(
                    sessionStart: cursor,
                    sessionEnd: sliceEnd,
                    pauses: session.pauses,
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
                    // Android PaidWorkAllocationV2 tronque chaque tranche à la minute avant
                    // l'agrégation hebdomadaire. Garder la même règle évite tout écart inter-plateforme.
                    paidMinutesByWeek[key, default: 0] += Int(floor(paidSeconds / 60.0))
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

    private static func pausesAreStructurallyUsable(
        _ pauses: [PaidPauseFactV2],
        sessionStart: Date,
        sessionEnd: Date
    ) -> Bool {
        pauses.allSatisfy { pause in
            guard pause.start >= sessionStart,
                  let end = pause.end,
                  end > pause.start,
                  end <= sessionEnd else {
                return false
            }
            return true
        }
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
