import Foundation

/// Références datées vers les sources canoniques. Aucune règle d'un autre salarié
/// ou employeur ne peut être sélectionnée à partir du seul métier.
struct SalaryWorkRuleOwnerV2: Codable, Equatable, Hashable {
    let accountId: String
    let employeeId: String
    let employerId: String
    let contractVersionId: String

    var isValid: Bool {
        [accountId, employeeId, employerId, contractVersionId].allSatisfy {
            !$0.isEmpty && $0 == $0.trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }
}

enum SalaryWorkRuleTopicV2: String, Codable, CaseIterable {
    case timeAccounting = "TIME_ACCOUNTING"
    case pauseCompensation = "PAUSE_COMPENSATION"
    case nightWork = "NIGHT_WORK"
    case weekendWork = "WEEKEND_WORK"
    case publicHoliday = "PUBLIC_HOLIDAY"
    case overtime = "OVERTIME"
    case onCall = "ON_CALL"
    case businessTravel = "BUSINESS_TRAVEL"
    case mealAllowance = "MEAL_ALLOWANCE"
    case absence = "ABSENCE"
}

enum SalaryWorkRuleConfirmationV2: String, Codable {
    case confirmed = "CONFIRMED"
    case toConfirm = "TO_CONFIRM"
}

/// endEpochDay is EXCLUSIVE, nil = confirmed open-ended version.
/// sourceId/ruleReference identify the source in existing canonical legal/contract stores.
struct SalaryDatedWorkRuleV2: Codable, Equatable {
    let id: String
    let owner: SalaryWorkRuleOwnerV2
    let topic: SalaryWorkRuleTopicV2
    let effectiveFromEpochDay: Int64
    let effectiveToEpochDay: Int64?
    let sourceId: String
    let ruleReference: String
    let checkedAtMs: Int64
    let confirmation: SalaryWorkRuleConfirmationV2
    let explicitlyNotApplicable: Bool

    private enum CodingKeys: String, CodingKey {
        case id, topic, sourceId, ruleReference, checkedAtMs, confirmation
        case effectiveFromEpochDay = "fromDay"
        case effectiveToEpochDay = "toDayExclusive"
        case explicitlyNotApplicable = "notApplicable"
        // Owner is in the enclosing envelope; it is deliberately not duplicated per row.
    }

    init(id: String, owner: SalaryWorkRuleOwnerV2, topic: SalaryWorkRuleTopicV2,
         effectiveFromEpochDay: Int64, effectiveToEpochDay: Int64?,
         sourceId: String, ruleReference: String, checkedAtMs: Int64,
         confirmation: SalaryWorkRuleConfirmationV2,
         explicitlyNotApplicable: Bool = false) {
        self.id = id
        self.owner = owner
        self.topic = topic
        self.effectiveFromEpochDay = effectiveFromEpochDay
        self.effectiveToEpochDay = effectiveToEpochDay
        self.sourceId = sourceId
        self.ruleReference = ruleReference
        self.checkedAtMs = checkedAtMs
        self.confirmation = confirmation
        self.explicitlyNotApplicable = explicitlyNotApplicable
    }

    // Envelope store constructs each record after decoding the owner separately.
    init(from decoder: Decoder) throws {
        let box = try decoder.container(keyedBy: CodingKeys.self)
        id = try box.decode(String.self, forKey: .id)
        topic = try box.decode(SalaryWorkRuleTopicV2.self, forKey: .topic)
        effectiveFromEpochDay = try box.decode(Int64.self, forKey: .effectiveFromEpochDay)
        effectiveToEpochDay = try box.decodeIfPresent(Int64.self, forKey: .effectiveToEpochDay)
        sourceId = try box.decode(String.self, forKey: .sourceId)
        ruleReference = try box.decode(String.self, forKey: .ruleReference)
        checkedAtMs = try box.decode(Int64.self, forKey: .checkedAtMs)
        confirmation = try box.decode(SalaryWorkRuleConfirmationV2.self, forKey: .confirmation)
        explicitlyNotApplicable = try box.decode(Bool.self, forKey: .explicitlyNotApplicable)
        // The source JSON has owner only at envelope level, so decode with a temporary owner.
        // The actual owner is supplied and checked by the persistent store.
        owner = SalaryWorkRuleOwnerV2(accountId: "", employeeId: "", employerId: "", contractVersionId: "")
    }

    func withOwner(_ owner: SalaryWorkRuleOwnerV2) -> Self {
        Self(id: id, owner: owner, topic: topic,
             effectiveFromEpochDay: effectiveFromEpochDay,
             effectiveToEpochDay: effectiveToEpochDay,
             sourceId: sourceId, ruleReference: ruleReference, checkedAtMs: checkedAtMs,
             confirmation: confirmation, explicitlyNotApplicable: explicitlyNotApplicable)
    }
}

enum SalaryWorkRuleResolutionStateV2 {
    case confirmed, missing, pending, conflict, invalid
}

struct SalaryWorkRuleDayResolutionV2 {
    let state: SalaryWorkRuleResolutionStateV2
    let record: SalaryDatedWorkRuleV2?
    let warnings: [String]
    var reliable: Bool { state == .confirmed && record != nil }
}

struct SalaryWorkRulePeriodSegmentV2 {
    let startEpochDay: Int64
    let endExclusiveEpochDay: Int64
    let record: SalaryDatedWorkRuleV2
}

struct SalaryWorkRulePeriodResolutionV2 {
    let segments: [SalaryWorkRulePeriodSegmentV2]
    let reliable: Bool
    let requiresSegmentedCalculation: Bool
    let warnings: [String]
}

enum SalaryDatedWorkRuleApplicabilityV2 {
    static let missingWarning = "Règle individuelle datée absente : qualification à confirmer."
    static let pendingWarning = "Règle individuelle en attente de confirmation."
    static let conflictWarning = "Plusieurs règles applicables : conflit non arbitré."
    static let invalidWarning = "Historique des règles incohérent : calcul certifié bloqué."
    static let segmentedWarning = "Changement de règle pendant la période : calculer les segments séparément."

    static func validRecord(_ record: SalaryDatedWorkRuleV2) -> Bool {
        guard record.owner.isValid,
              !record.id.isEmpty,
              record.id == record.id.trimmingCharacters(in: .whitespacesAndNewlines) else {
            return false
        }
        if let end = record.effectiveToEpochDay, end <= record.effectiveFromEpochDay {
            return false
        }
        if record.confirmation == .confirmed &&
            (record.sourceId.isEmpty || record.ruleReference.isEmpty ||
             record.checkedAtMs <= 0 ||
             record.sourceId != record.sourceId.trimmingCharacters(in: .whitespacesAndNewlines) ||
             record.ruleReference != record.ruleReference.trimmingCharacters(in: .whitespacesAndNewlines)) {
            return false
        }
        return true
    }

    static func validTimeline(_ owner: SalaryWorkRuleOwnerV2,
                              records: [SalaryDatedWorkRuleV2]) -> Bool {
        guard owner.isValid,
              records.allSatisfy({ validRecord($0) && $0.owner == owner }),
              Set(records.map(\.id)).count == records.count else { return false }
        for topic in SalaryWorkRuleTopicV2.allCases {
            let sorted = records.filter { $0.topic == topic && $0.confirmation == .confirmed }
                .sorted { $0.effectiveFromEpochDay < $1.effectiveFromEpochDay }
            for (a, b) in zip(sorted, sorted.dropFirst()) {
                if b.effectiveFromEpochDay < (a.effectiveToEpochDay ?? Int64.max) {
                    return false
                }
            }
        }
        return true
    }

    static func resolveDay(
        owner: SalaryWorkRuleOwnerV2, topic: SalaryWorkRuleTopicV2,
        epochDay: Int64, records: [SalaryDatedWorkRuleV2], sourceReliable: Bool
    ) -> SalaryWorkRuleDayResolutionV2 {
        guard owner.isValid, sourceReliable, records.allSatisfy(validRecord) else {
            return .init(state: .invalid, record: nil, warnings: [invalidWarning])
        }
        let applicable = records.filter {
            $0.owner == owner && $0.topic == topic &&
            $0.effectiveFromEpochDay <= epochDay &&
            ($0.effectiveToEpochDay == nil || epochDay < $0.effectiveToEpochDay!)
        }
        if applicable.isEmpty {
            return .init(state: .missing, record: nil, warnings: [missingWarning])
        }
        if applicable.contains(where: { $0.confirmation != .confirmed }) {
            return .init(state: .pending, record: nil, warnings: [pendingWarning])
        }
        if applicable.count != 1 {
            return .init(state: .conflict, record: nil, warnings: [conflictWarning])
        }
        return .init(state: .confirmed, record: applicable[0], warnings: [])
    }

    static func resolvePeriod(
        owner: SalaryWorkRuleOwnerV2, topic: SalaryWorkRuleTopicV2,
        fromEpochDay: Int64, toExclusiveEpochDay: Int64,
        records: [SalaryDatedWorkRuleV2], sourceReliable: Bool
    ) -> SalaryWorkRulePeriodResolutionV2 {
        guard toExclusiveEpochDay > fromEpochDay,
              toExclusiveEpochDay - fromEpochDay <= 366 else {
            return .init(segments: [], reliable: false,
                         requiresSegmentedCalculation: false, warnings: [invalidWarning])
        }
        var segments: [SalaryWorkRulePeriodSegmentV2] = []
        var warnings: [String] = []
        var complete = true
        for day in fromEpochDay..<toExclusiveEpochDay {
            let resolution = resolveDay(owner: owner, topic: topic, epochDay: day,
                                        records: records, sourceReliable: sourceReliable)
            guard resolution.reliable, let record = resolution.record else {
                complete = false
                warnings.append(contentsOf: resolution.warnings)
                continue
            }
            if let last = segments.last, last.record.id == record.id,
               last.endExclusiveEpochDay == day {
                segments[segments.count - 1] = .init(
                    startEpochDay: last.startEpochDay, endExclusiveEpochDay: day + 1,
                    record: last.record)
            } else {
                segments.append(.init(startEpochDay: day,
                                      endExclusiveEpochDay: day + 1, record: record))
            }
        }
        let changed = Set(segments.map { $0.record.id }).count > 1
        if changed { warnings.append(segmentedWarning) }
        return .init(segments: segments, reliable: complete,
                     requiresSegmentedCalculation: changed,
                     warnings: Array(NSOrderedSet(array: warnings)) as? [String] ?? warnings)
    }
}
