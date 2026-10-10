import Foundation

/// Read-only qualification layer. This does not invent rates and does not
/// replace the canonical dated contract/convention calculation in Salary V2.
enum SalaryDatedWorkTimeQualificationV2 {
    static let contextWarning = "Calcul personnalisé : compte, dates ou sujets de règles non qualifiés."
    static let multipleRulesWarning = "Plusieurs règles pendant la session : le temps doit être segmenté avant certification."

    struct Assessment {
        let time: PaidTimeAssessmentV2
        let selectedRuleEvidence: [SalaryWorkRuleTopicV2: SalaryDatedWorkRuleV2]
        var selectedSourceIds: [SalaryWorkRuleTopicV2: String] {
            selectedRuleEvidence.mapValues { $0.sourceId }
        }
        let reliable: Bool
        let warnings: [String]
    }

    static func assess(
        session: SalarySessionFactV2,
        owner: SalaryWorkRuleOwnerV2,
        calendar inputCalendar: Calendar,
        requiredTopics: Set<SalaryWorkRuleTopicV2>,
        rules: [SalaryDatedWorkRuleV2],
        sourceReliable: Bool,
        now: Date = Date()
    ) -> Assessment {
        let basic = PaidTimePolicyV2.assess(
            sessionStart: session.entry,
            sessionEnd: session.exit,
            pauses: session.pauses,
            until: now
        )
        guard owner.isValid, !requiredTopics.isEmpty,
              session.employerId == owner.employerId,
              let end = session.exit, end > session.entry,
              let beginDay = epochDay(session.entry, calendar: inputCalendar),
              let endDay = lastDayExclusive(end, calendar: inputCalendar) else {
            return .init(time: basic, selectedRuleEvidence: [:], reliable: false,
                         warnings: [contextWarning])
        }
        var warnings: [String] = []
        var selected: [SalaryWorkRuleTopicV2: SalaryDatedWorkRuleV2] = [:]
        var rulesReliable = true
        for topic in requiredTopics {
            let result = SalaryDatedWorkRuleApplicabilityV2.resolvePeriod(
                owner: owner, topic: topic,
                fromEpochDay: beginDay, toExclusiveEpochDay: endDay,
                records: rules, sourceReliable: sourceReliable
            )
            warnings.append(contentsOf: result.warnings)
            guard result.reliable, !result.requiresSegmentedCalculation,
                  result.segments.count == 1 else {
                rulesReliable = false
                if result.requiresSegmentedCalculation {
                    warnings.append(multipleRulesWarning)
                }
                continue
            }
            selected[topic] = result.segments[0].record
        }
        let uniqueWarnings = Array(NSOrderedSet(array: warnings)) as? [String] ?? warnings
        return .init(
            time: basic,
            selectedRuleEvidence: rulesReliable ? selected : [:],
            reliable: basic.reliable && rulesReliable && selected.count == requiredTopics.count,
            warnings: uniqueWarnings
        )
    }

    private static func epochDay(_ date: Date, calendar input: Calendar) -> Int64? {
        var local = Calendar(identifier: .gregorian)
        local.timeZone = input.timeZone
        let components = local.dateComponents([.year, .month, .day], from: date)
        guard let year = components.year, let month = components.month, let day = components.day else {
            return nil
        }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        guard let midnight = utc.date(from: DateComponents(
            year: year, month: month, day: day, hour: 0, minute: 0, second: 0
        )) else { return nil }
        return Int64(floor(midnight.timeIntervalSince1970 / 86_400))
    }

    private static func lastDayExclusive(
        _ end: Date, calendar input: Calendar
    ) -> Int64? {
        var local = Calendar(identifier: .gregorian)
        local.timeZone = input.timeZone
        let lastMoment: Date
        if local.startOfDay(for: end) == end {
            guard let previousDay = local.date(byAdding: .day, value: -1, to: end) else {
                return nil
            }
            lastMoment = previousDay
        } else {
            lastMoment = end
        }
        guard let day = epochDay(lastMoment, calendar: input) else { return nil }
        return day + 1
    }
}
