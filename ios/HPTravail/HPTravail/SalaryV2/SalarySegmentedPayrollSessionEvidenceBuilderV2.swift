import Foundation

/// Le stockage fiable et l'exhaustivité sont deux preuves différentes.
/// `work` provient de SalaryWorkSessionBridgeV2, pas d'un tableau de minutes reconstruit par l'écran.
/// L'appelant doit fournir un intervalle réellement confirmé ; le builder ne le fabrique pas.
struct SalarySegmentedPayrollSessionSourceV2 {
    let employerId: String
    let work: SalaryWorkSessionSourceV2
    let sourceId: String
    let exhaustive: Bool
    let coveredStartEpochDay: Int64
    let coveredEndEpochDay: Int64
    let checkedAt: Date
    let timeZoneId: String
    var warnings: [String] = []
}

struct SalarySegmentedPayrollPremiumEvidenceV2 {
    let slice: SalaryPayrollCalculationSliceV2
    let sourceId: String
    let reliable: Bool
    let nightRule: NightPremiumRuleV2?
    let holidayScope: FrenchPublicHolidayCalendarV2.Scope?
}

struct SalarySegmentedPayrollSessionEvidenceResultV2 {
    let slices: [SalarySegmentedPayrollSliceEvidenceV2]
    let reliable: Bool
    let warnings: [String]
    let sourceId: String
    let contributingSessionIds: [String]
}

/// Faits V2 -> preuves B21, sans reproduire de formule de temps payé ou de salaire.
/// Les politiques de chevauchement existantes restent propriétaires des pauses et majorations.
/// Le constructeur mensuel n'est pas utilisé : il tronquerait le contexte des semaines de bord.
/// Une semaine contenant du temps payé hors tranche reste bloquée tant que son allocation
/// monétaire n'est pas prouvée. Les gardes B19 et le chemin non segmenté restent inchangés.
enum SalarySegmentedPayrollSessionEvidenceBuilderV2 {
    static let sourceWarning = "Preuves B21 : lecture ou couverture exhaustive des pointages non confirmée."
    static let scopeWarning = "Preuves B21 : employeur, provenance ou plage de lecture incohérents."
    static let ruleWarning = "Preuves B21 : règles temporelles de la tranche absentes ou non confirmées."
    static let sessionWarning = "Preuves B21 : identifiant de pointage absent ou dupliqué."
    static let edgeWarning = "Preuves B21 : temps payé hors de la tranche dans une semaine de bord ; allocation monétaire non prouvée."
    static let holidayWarning = "Preuves B21 : jour férié nécessitant une règle dédiée ou un périmètre confirmé."
    static let calendarWarning = "Preuves B21 : dates, fuseau ou durée hors du domaine vérifiable."

    static func build(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        source: SalarySegmentedPayrollSessionSourceV2,
        premiums: [SalarySegmentedPayrollPremiumEvidenceV2],
        now: Date
    ) -> SalarySegmentedPayrollSessionEvidenceResultV2 {
        var warnings = source.warnings
        func blocked(_ message: String) -> SalarySegmentedPayrollSessionEvidenceResultV2 {
            .init(slices: [], reliable: false, warnings: unique(warnings + [message]),
                  sourceId: source.sourceId, contributingSessionIds: [])
        }
        guard source.work.reliable, source.exhaustive else { return blocked(sourceWarning) }
        let employer = normalized(source.employerId)
        guard !employer.isEmpty, employer == normalized(contracts.companyId),
              !normalized(source.sourceId).isEmpty,
              source.checkedAt.timeIntervalSince1970.isFinite, now.timeIntervalSince1970.isFinite,
              source.checkedAt <= now,
              source.coveredEndEpochDay >= source.coveredStartEpochDay else { return blocked(scopeWarning) }
        let timeline = SalaryPayrollCalculationTimelineV2.align(contracts: contracts, rules: rules)
        warnings += timeline.warnings
        guard timeline.reliable, !timeline.slices.isEmpty,
              timeline.slices.allSatisfy({ normalized($0.contractSnapshot.contract.employerId) == employer })
        else { return blocked(scopeWarning) }
        guard premiums.count == timeline.slices.count,
              premiums.allSatisfy({ $0.reliable && !normalized($0.sourceId).isEmpty }),
              timeline.slices.allSatisfy({ slice in premiums.filter { $0.slice == slice }.count == 1 })
        else { return blocked(ruleWarning) }
        guard let timeZone = TimeZone(identifier: source.timeZoneId) else { return blocked(calendarWarning) }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.firstWeekday = 2
        calendar.minimumDaysInFirstWeek = 4
        let targetFacts = source.work.sessions.filter { normalized($0.employerId) == employer }
        guard targetFacts.allSatisfy({ !normalized($0.id).isEmpty }),
              Set(targetFacts.map { normalized($0.id) }).count == targetFacts.count else { return blocked(sessionWarning) }
        var output: [SalarySegmentedPayrollSliceEvidenceV2] = []
        var usedIds: [String] = []
        for slice in timeline.slices {
            guard let sliceStart = localStart(slice.startEpochDay, calendar: calendar),
                  slice.endEpochDay < Int64.max,
                  let sliceEnd = localStart(slice.endEpochDay + 1, calendar: calendar),
                  sliceEnd > sliceStart,
                  let context = premiums.first(where: { $0.slice == slice }) else { return blocked(calendarWarning) }
            let payrollRules = slice.ruleSnapshot.rules
            guard [payrollRules.nightMultiplier, payrollRules.saturdayMultiplier,
                   payrollRules.sundayMultiplier, payrollRules.publicHolidayMultiplier]
                    .compactMap({ $0 }).allSatisfy({ $0.isFinite && $0 >= 1 }) else { return blocked(ruleWarning) }
            if let multiplier = payrollRules.nightMultiplier {
                guard let nightRule = context.nightRule,
                      abs(multiplier - nightRule.multiplier) <= 0.000_001 else { return blocked(ruleWarning) }
            }
            guard let holidayScope = context.holidayScope, holidayScope.complete else { return blocked(holidayWarning) }
            let firstMonday = slice.startEpochDay - ((slice.startEpochDay % 7 + 10) % 7)
            var monday = firstMonday
            var weeks: [SalarySegmentedPayrollWeekEvidenceV2] = []
            var sliceWarnings: [String] = []
            while monday <= slice.endEpochDay {
                let nextMonday = monday + 7 // Bornes déjà contrôlées par localStart, loin des limites Int64.
                guard monday >= source.coveredStartEpochDay, nextMonday - 1 <= source.coveredEndEpochDay,
                      let from = localStart(monday, calendar: calendar),
                      let to = localStart(nextMonday, calendar: calendar), to > from,
                      to <= source.checkedAt else { return blocked(sourceWarning) }
                let relevant = source.work.sessions.filter { touches($0, from: from, to: to) }
                guard !relevant.contains(where: { normalized($0.employerId).isEmpty }) else {
                    return blocked(SalaryPaidWorkAggregatorV2.unassignedEmployerWarning)
                }
                let selected = relevant.filter { normalized($0.employerId) == employer }
                let ordered = selected.sorted { $0.entry < $1.entry }
                for index in ordered.indices {
                    let session = ordered[index]
                    guard session.entry.timeIntervalSince1970.isFinite,
                          let exit = session.exit, exit.timeIntervalSince1970.isFinite, exit > session.entry, exit <= source.checkedAt else {
                        return blocked(SalaryPaidWorkAggregatorV2.invalidSessionWarning)
                    }
                    if index > 0, let previousExit = ordered[index - 1].exit,
                       session.entry < previousExit { return blocked(SalaryPaidWorkAggregatorV2.overlapWarning) }
                }
                let components = calendar.dateComponents([.yearForWeekOfYear, .weekOfYear], from: from)
                guard let weekYear = components.yearForWeekOfYear, let weekNumber = components.weekOfYear,
                      let firstDate = civilDate(monday), let lastDate = civilDate(nextMonday - 1),
                      let saturdayDate = civilDate(monday + 5), let sundayDate = civilDate(monday + 6)
                else { return blocked(calendarWarning) }
                var holidayDates = Set<PayrollCivilDateV2>()
                var dedicatedDates = Set<PayrollCivilDateV2>()
                for year in firstDate.year...lastDate.year {
                    guard let holidays = FrenchPublicHolidayCalendarV2.genericHolidays(year: year, scope: holidayScope),
                          let mayFirst = FrenchPublicHolidayCalendarV2.mayFirst(year) else { return blocked(calendarWarning) }
                    holidayDates.formUnion(holidays)
                    dedicatedDates.insert(mayFirst)
                }
                var totals = [Int](repeating: 0, count: 5)
                for session in selected {
                    let full = SalaryPaidOverlapPolicyV2.paidOverlap(session: session, rangeStart: from, rangeEnd: to)
                    let inside = SalaryPaidOverlapPolicyV2.paidOverlap(session: session,
                        rangeStart: max(from, sliceStart), rangeEnd: min(to, sliceEnd))
                    warnings += full.warnings + inside.warnings
                    guard full.reliable, inside.reliable else { return blocked(sourceWarning) }
                    // Tolérance submilliseconde seulement pour les Double de Date ; pas de minute effacée.
                    guard abs(full.paidDuration - inside.paidDuration) < 0.000_001 else { return blocked(edgeWarning) }
                    let dedicated = PublicHolidayPremiumPolicyV2.paidOverlap(session: session,
                        rangeStart: from, rangeEnd: to, holidayDates: dedicatedDates, calendar: calendar)
                    guard dedicated.reliable, dedicated.paidDuration == 0 else { return blocked(holidayWarning) }
                    var night = SalaryPaidOverlapResultV2(paidDuration: 0, reliable: true, warnings: [])
                    if payrollRules.nightMultiplier != nil, let nightRule = context.nightRule {
                        night = NightPremiumPolicyV2.paidOverlap(session: session,
                            rangeStart: from, rangeEnd: to, rule: nightRule, calendar: calendar)
                    }
                    let saturday = PublicHolidayPremiumPolicyV2.paidOverlap(session: session,
                        rangeStart: from, rangeEnd: to, holidayDates: [saturdayDate], calendar: calendar)
                    let sunday = PublicHolidayPremiumPolicyV2.paidOverlap(session: session,
                        rangeStart: from, rangeEnd: to, holidayDates: [sundayDate], calendar: calendar)
                    let holiday = PublicHolidayPremiumPolicyV2.paidOverlap(session: session,
                        rangeStart: from, rangeEnd: to, holidayDates: holidayDates, calendar: calendar)
                    let values = [full, night, saturday, sunday, holiday]
                    sliceWarnings += values.flatMap(\.warnings)
                    warnings += values.flatMap(\.warnings)
                    for index in values.indices {
                        let value = values[index]
                        guard value.reliable, let minutes = wholeMinutes(value.paidDuration) else { return blocked(ruleWarning) }
                        let added = totals[index].addingReportingOverflow(minutes)
                        guard !added.overflow, added.partialValue <= Int(Int32.max) else { return blocked(calendarWarning) }
                        totals[index] = added.partialValue
                    }
                    if !usedIds.contains(session.id) { usedIds.append(session.id) }
                }
                weeks.append(.init(yearForWeekOfYear: weekYear, weekOfYear: weekNumber,
                    week: PayrollWeekV2(paidMinutes: totals[0], nightMinutes: totals[1],
                        saturdayMinutes: totals[2], sundayMinutes: totals[3], publicHolidayMinutes: totals[4]),
                    fullWeekContextReliable: true))
                monday = nextMonday
            }
            output.append(.init(startEpochDay: slice.startEpochDay, endEpochDay: slice.endEpochDay,
                contractVersionId: slice.contractVersionId, ruleVersionId: slice.ruleVersionId,
                weeks: weeks, evidence: .fullyConfirmed, warnings: unique(sliceWarnings)))
        }
        return .init(slices: output, reliable: true, warnings: unique(warnings),
                     sourceId: source.sourceId, contributingSessionIds: usedIds)
    }

    static func calculateVariables(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        source: SalarySegmentedPayrollSessionSourceV2,
        premiums: [SalarySegmentedPayrollPremiumEvidenceV2],
        now: Date
    ) -> SalarySegmentedWorkedVariableGrossSourceResultV2 {
        let proof = build(contracts: contracts, rules: rules, source: source, premiums: premiums, now: now)
        guard proof.reliable else { return .init(pieces: [], reliable: false, warnings: proof.warnings) }
        let result = SalarySegmentedWorkedVariableGrossSourceV2.calculate(
            contracts: contracts, rules: rules, sliceEvidence: proof.slices)
        return .init(pieces: result.pieces, reliable: result.reliable, warnings: unique(proof.warnings + result.warnings))
    }

    private static func touches(_ session: SalarySessionFactV2, from: Date, to: Date) -> Bool {
        guard session.entry.timeIntervalSince1970.isFinite else { return true }
        guard let exit = session.exit else { return session.entry < to }
        guard exit.timeIntervalSince1970.isFinite else { return true }
        if exit == session.entry { return session.entry >= from && session.entry < to }
        return min(session.entry, exit) < to && max(session.entry, exit) > from
    }

    private static func civilDate(_ epochDay: Int64) -> PayrollCivilDateV2? {
        // Même domaine que le calendrier férié canonique réutilisé, pas une restriction par métier.
        guard (-25567...84370).contains(epochDay) else { return nil } // 1900-01-01 ... 2200-12-31
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let c = utc.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: Double(epochDay) * 86400))
        guard let y = c.year, let m = c.month, let d = c.day else { return nil }
        return PayrollCivilDateV2(year: y, month: m, day: d)
    }

    private static func localStart(_ epochDay: Int64, calendar: Calendar) -> Date? {
        guard let day = civilDate(epochDay),
              let value = calendar.date(from: DateComponents(year: day.year, month: day.month, day: day.day,
                                                               hour: 0, minute: 0, second: 0)) else { return nil }
        let actual = calendar.dateComponents([.year, .month, .day], from: value)
        guard actual.year == day.year, actual.month == day.month, actual.day == day.day else { return nil }
        return calendar.startOfDay(for: value)
    }

    private static func wholeMinutes(_ seconds: TimeInterval) -> Int? {
        guard seconds.isFinite, seconds >= 0 else { return nil }
        let value = floor(seconds / 60)
        guard value <= Double(Int32.max) else { return nil }
        return Int(value)
    }

    private static func normalized(_ raw: String?) -> String {
        raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }
    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
