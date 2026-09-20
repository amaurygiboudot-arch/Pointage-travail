import Foundation

struct SalaryConventionCoverageSegmentV2: Equatable {
    let startEpochDay: Int64
    let endEpochDay: Int64
    let snapshot: SalaryConventionRuleSnapshotV2
}

/// Couverture conventionnelle factuelle d'une entreprise pour un mois de paie.
///
/// `sourceReliable` décrit uniquement la fiabilité des stores amont. `fullyCovered`
/// indique si chaque jour civil du mois possède une version confirmée. Une couverture
/// complète avec plusieurs versions reste factuellement exploitable, mais elle n'autorise
/// pas à appliquer une règle unique à tout le mois.
struct SalaryConventionCoverageV2: Equatable {
    let companyId: String
    let idcc: String?
    let periodStartEpochDay: Int64?
    let periodEndEpochDay: Int64?
    let segments: [SalaryConventionCoverageSegmentV2]
    let sourceReliable: Bool
    let fullyCovered: Bool
    let warnings: [String]

    var singleSnapshotForWholePeriod: SalaryConventionRuleSnapshotV2? {
        guard sourceReliable,
              fullyCovered,
              segments.count == 1,
              let start = periodStartEpochDay,
              let end = periodEndEpochDay,
              segments[0].startEpochDay == start,
              segments[0].endEpochDay == end else {
            return nil
        }
        return segments[0].snapshot
    }

    var requiresMultipleRuleVersions: Bool {
        sourceReliable && fullyCovered && segments.count > 1
    }
}

/// Résout la convention de l'entreprise sélectionnée sur toute la période demandée.
///
/// Aucun fallback vers une règle plus récente n'est autorisé. Les dates d'effet sont
/// inclusives, comme dans `SalaryConventionRuleSnapshotV2`. Une modification de version
/// en cours de mois est conservée sous forme de segments distincts au lieu d'être aplatie.
enum SalaryConventionCoverageResolverV2 {
    static let companyStoreWarning =
        "Convention collective : stockage des entreprises non fiable ; la convention applicable ne peut pas être déterminée."
    static let companyWarning =
        "Convention collective : l'entreprise sélectionnée n'est plus confirmée ; la convention applicable ne peut pas être déterminée."
    static let idccWarning =
        "Convention collective : IDCC absent pour l'entreprise sélectionnée ; aucune règle conventionnelle n'est appliquée automatiquement."
    static let ruleStoreWarning =
        "Convention collective : historique des règles non fiable ; aucune règle conventionnelle n'est appliquée automatiquement."
    static let periodWarning =
        "Convention collective : période civile invalide ; le calcul automatique reste bloqué."
    static let coverageWarning =
        "Convention collective : aucune version confirmée ne couvre entièrement le mois ; aucun fallback vers une autre version n'est appliqué."
    static let multipleVersionsWarning =
        "Convention collective : plusieurs versions confirmées couvrent ce mois ; une allocation temporelle plus fine est nécessaire avant tout calcul automatique avec une règle unique."

    static func resolve(
        companyId rawCompanyId: String,
        period: YearMonthV2,
        companies: SalaryCompanyReadResultV2,
        rules storedRules: SalaryConventionRuleReadResultV2
    ) -> SalaryConventionCoverageV2 {
        let companyId = rawCompanyId.trimmingCharacters(in: .whitespacesAndNewlines)

        guard companies.reliable else {
            return blocked(companyId: companyId, warning: companyStoreWarning)
        }
        guard !companyId.isEmpty,
              let company = SalaryCompanyStoreV2.confirmedCompany(companies, companyId: companyId) else {
            return blocked(companyId: companyId, warning: companyWarning)
        }

        let idcc = SalaryConventionRuleStoreV2.normalizeIdcc(company.idcc)
        guard !idcc.isEmpty else {
            return blocked(companyId: companyId, idcc: nil, warning: idccWarning)
        }
        guard storedRules.reliable,
              let history = SalaryConventionRuleStoreV2.history(from: storedRules) else {
            return blocked(companyId: companyId, idcc: idcc, warning: ruleStoreWarning)
        }
        guard let range = monthEpochDayRange(period) else {
            return blocked(companyId: companyId, idcc: idcc, warning: periodWarning)
        }

        let segments = history.allVersions(idcc: idcc)
            .compactMap { snapshot -> SalaryConventionCoverageSegmentV2? in
                let start = max(snapshot.effectiveFromEpochDay, range.start)
                let end = min(snapshot.effectiveToEpochDay ?? range.end, range.end)
                guard start <= end else { return nil }
                return SalaryConventionCoverageSegmentV2(
                    startEpochDay: start,
                    endEpochDay: end,
                    snapshot: snapshot
                )
            }
            .sorted {
                if $0.startEpochDay == $1.startEpochDay {
                    return $0.endEpochDay < $1.endEpochDay
                }
                return $0.startEpochDay < $1.startEpochDay
            }

        let fullyCovered = coversEveryDay(segments, range: range)
        var warnings: [String] = []
        if !fullyCovered {
            warnings.append(coverageWarning)
        } else if segments.count > 1 {
            warnings.append(multipleVersionsWarning)
        }

        return SalaryConventionCoverageV2(
            companyId: companyId,
            idcc: idcc,
            periodStartEpochDay: range.start,
            periodEndEpochDay: range.end,
            segments: segments,
            sourceReliable: true,
            fullyCovered: fullyCovered,
            warnings: warnings
        )
    }

    private static func coversEveryDay(
        _ segments: [SalaryConventionCoverageSegmentV2],
        range: (start: Int64, end: Int64)
    ) -> Bool {
        guard !segments.isEmpty else { return false }
        var cursor = range.start
        for segment in segments {
            guard segment.startEpochDay == cursor else { return false }
            if segment.endEpochDay == range.end { return true }
            guard segment.endEpochDay < range.end,
                  segment.endEpochDay < Int64.max else {
                return false
            }
            cursor = segment.endEpochDay + 1
        }
        return false
    }

    /// Conversion du mois civil vers les epoch-days UTC, indépendante du calendrier
    /// et du fuseau choisis sur l'appareil.
    static func monthEpochDayRange(_ period: YearMonthV2) -> (start: Int64, end: Int64)? {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        calendar.locale = Locale(identifier: "en_US_POSIX")

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

        guard let startDate = exactUTCDate(year: period.year, month: period.month, calendar: calendar),
              let endExclusiveDate = exactUTCDate(year: nextYear, month: nextMonth, calendar: calendar),
              endExclusiveDate > startDate else {
            return nil
        }

        let secondsPerDay = 86_400.0
        let startSeconds = startDate.timeIntervalSince1970
        let endSeconds = endExclusiveDate.timeIntervalSince1970
        guard startSeconds.isFinite, endSeconds.isFinite else { return nil }

        let startValue = startSeconds / secondsPerDay
        let endExclusiveValue = endSeconds / secondsPerDay
        guard startValue.rounded() == startValue,
              endExclusiveValue.rounded() == endExclusiveValue,
              startValue >= Double(Int64.min),
              startValue <= Double(Int64.max),
              endExclusiveValue >= Double(Int64.min) + 1,
              endExclusiveValue <= Double(Int64.max) else {
            return nil
        }

        let start = Int64(startValue)
        let endExclusive = Int64(endExclusiveValue)
        guard endExclusive > start else { return nil }
        return (start, endExclusive - 1)
    }

    private static func exactUTCDate(year: Int, month: Int, calendar: Calendar) -> Date? {
        var components = DateComponents()
        components.calendar = calendar
        components.timeZone = calendar.timeZone
        components.year = year
        components.month = month
        components.day = 1
        components.hour = 0
        components.minute = 0
        components.second = 0
        guard let date = calendar.date(from: components) else { return nil }
        let verified = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        guard verified.year == year,
              verified.month == month,
              verified.day == 1,
              verified.hour == 0,
              verified.minute == 0,
              verified.second == 0 else {
            return nil
        }
        return date
    }

    private static func blocked(
        companyId: String,
        idcc: String? = nil,
        warning: String
    ) -> SalaryConventionCoverageV2 {
        SalaryConventionCoverageV2(
            companyId: companyId,
            idcc: idcc,
            periodStartEpochDay: nil,
            periodEndEpochDay: nil,
            segments: [],
            sourceReliable: false,
            fullyCovered: false,
            warnings: [warning]
        )
    }
}
