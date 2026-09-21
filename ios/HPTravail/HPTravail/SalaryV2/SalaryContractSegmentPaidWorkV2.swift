import Foundation

struct SalaryContractSegmentPaidWorkV2: Equatable {
    let versionId: String
    let sourceId: String
    let startEpochDay: Int64
    let endEpochDay: Int64
    let contract: ContractV2
    let weeks: [SalaryPaidWeekV2]
    let paidMinutes: Int
    let completedSessionCount: Int
    let reliable: Bool
    let warnings: [String]
}

struct SalaryContractSegmentPaidWorkResultV2: Equatable {
    let segments: [SalaryContractSegmentPaidWorkV2]
    let totalPaidMinutes: Int
    let reliable: Bool
    let warnings: [String]
}

/// Répartit les faits de pointage sur les versions contractuelles datées qui couvrent le mois.
///
/// Aucune règle de métier ou de paie n'est créée ici. Les sessions et pauses sont uniquement
/// découpées aux bornes calendaires des contrats puis confiées à `SalaryPaidWorkAggregatorV2`,
/// qui reste l'unique moteur iOS de temps payé. Une donnée ambiguë rend le résultat non fiable.
enum SalaryContractSegmentPaidWorkAllocatorV2 {
    static let invalidCoverageWarning =
        "Temps payé segmenté : les segments contractuels ne couvrent pas une période continue ; calcul bloqué."
    static let unreliableSourceWarning =
        "Temps payé segmenté : historique de pointage non fiable ; calcul automatique bloqué."

    static func allocate(
        sessions: [SalarySessionFactV2],
        segments: [SalaryEmploymentContractCoverageSegmentV2],
        employerId rawEmployerId: String,
        period: YearMonthV2,
        sourceReliable: Bool,
        calendar inputCalendar: Calendar = .current
    ) -> SalaryContractSegmentPaidWorkResultV2 {
        let employerId = rawEmployerId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !employerId.isEmpty, !segments.isEmpty, continuous(segments) else {
            return SalaryContractSegmentPaidWorkResultV2(
                segments: [],
                totalPaidMinutes: 0,
                reliable: false,
                warnings: [invalidCoverageWarning]
            )
        }

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = inputCalendar.timeZone
        calendar.locale = inputCalendar.locale
        calendar.firstWeekday = 2
        calendar.minimumDaysInFirstWeek = 4

        let allocated: [SalaryContractSegmentPaidWorkV2] = segments.compactMap { segment in
            guard let start = startOfEpochDay(segment.startEpochDay, calendar: calendar),
                  segment.endEpochDay < Int64.max,
                  let endExclusive = startOfEpochDay(segment.endEpochDay + 1, calendar: calendar),
                  endExclusive > start else {
                return nil
            }

            let clipped = sessions.compactMap { session -> SalarySessionFactV2? in
                guard normalizedEmployerId(session.employerId) == employerId else { return nil }
                return clip(session: session, start: start, endExclusive: endExclusive)
            }
            let aggregate = SalaryPaidWorkAggregatorV2.aggregate(
                sessions: clipped,
                employerId: employerId,
                period: period,
                sourceReliable: sourceReliable,
                calendar: calendar
            )

            return SalaryContractSegmentPaidWorkV2(
                versionId: segment.snapshot.versionId,
                sourceId: segment.snapshot.sourceId,
                startEpochDay: segment.startEpochDay,
                endEpochDay: segment.endEpochDay,
                contract: segment.snapshot.contract,
                weeks: aggregate.weeks,
                paidMinutes: aggregate.totalPaidMinutes,
                completedSessionCount: aggregate.completedSessionCount,
                reliable: aggregate.reliable,
                warnings: aggregate.warnings
            )
        }

        guard allocated.count == segments.count else {
            return SalaryContractSegmentPaidWorkResultV2(
                segments: [],
                totalPaidMinutes: 0,
                reliable: false,
                warnings: [invalidCoverageWarning]
            )
        }

        var warnings = allocated.flatMap(\.warnings)
        if !sourceReliable { warnings.append(unreliableSourceWarning) }
        return SalaryContractSegmentPaidWorkResultV2(
            segments: allocated,
            totalPaidMinutes: allocated.reduce(0) { $0 + $1.paidMinutes },
            reliable: sourceReliable && allocated.allSatisfy(\.reliable),
            warnings: unique(warnings)
        )
    }

    private static func clip(
        session: SalarySessionFactV2,
        start: Date,
        endExclusive: Date
    ) -> SalarySessionFactV2? {
        guard session.entry < endExclusive else { return nil }
        if let exit = session.exit, exit <= start { return nil }

        let clippedStart = max(session.entry, start)
        if let exit = session.exit {
            let clippedEnd = min(exit, endExclusive)
            guard clippedEnd > clippedStart else { return nil }
            return SalarySessionFactV2(
                id: "\(session.id)#segment",
                entry: clippedStart,
                exit: clippedEnd,
                employerId: session.employerId,
                pauses: session.pauses.compactMap {
                    clip(pause: $0, start: clippedStart, endExclusive: clippedEnd)
                }
            )
        }

        return SalarySessionFactV2(
            id: "\(session.id)#segment",
            entry: clippedStart,
            exit: nil,
            employerId: session.employerId,
            pauses: session.pauses.compactMap {
                clip(pause: $0, start: clippedStart, endExclusive: endExclusive)
            }
        )
    }

    private static func clip(
        pause: PaidPauseFactV2,
        start: Date,
        endExclusive: Date
    ) -> PaidPauseFactV2? {
        guard pause.start < endExclusive else { return nil }
        if let end = pause.end {
            guard end > start else { return nil }
            let clippedStart = max(pause.start, start)
            let clippedEnd = min(end, endExclusive)
            guard clippedEnd > clippedStart else { return nil }
            return PaidPauseFactV2(start: clippedStart, end: clippedEnd, paid: pause.paid)
        }

        return PaidPauseFactV2(
            start: max(pause.start, start),
            end: nil,
            paid: pause.paid
        )
    }

    private static func startOfEpochDay(_ epochDay: Int64, calendar: Calendar) -> Date? {
        let utcDate = Date(timeIntervalSince1970: TimeInterval(epochDay) * 86_400.0)
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let components = utc.dateComponents([.year, .month, .day], from: utcDate)
        guard let year = components.year, let month = components.month, let day = components.day else {
            return nil
        }
        return calendar.date(from: DateComponents(
            calendar: calendar,
            timeZone: calendar.timeZone,
            year: year,
            month: month,
            day: day,
            hour: 0,
            minute: 0,
            second: 0
        ))
    }

    private static func continuous(_ segments: [SalaryEmploymentContractCoverageSegmentV2]) -> Bool {
        let sorted = segments.sorted { $0.startEpochDay < $1.startEpochDay }
        guard sorted.allSatisfy({ $0.endEpochDay >= $0.startEpochDay }) else { return false }
        guard sorted.count > 1 else { return true }
        for index in 1..<sorted.count {
            let previous = sorted[index - 1]
            let current = sorted[index]
            guard previous.endEpochDay < Int64.max,
                  current.startEpochDay == previous.endEpochDay + 1 else {
                return false
            }
        }
        return true
    }

    private static func normalizedEmployerId(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
