import Foundation

/// Méthode de proratisation admise uniquement lorsqu'elle est explicitement confirmée par une source.
enum ConfirmedProrationMethodV2: String, Equatable {
    case scheduledMinutes = "SCHEDULED_MINUTES"
}

struct ConfirmedProrationSegmentV2: Equatable {
    let versionId: String
    let startEpochDay: Int64
    let endEpochDay: Int64
    let scheduledMinutes: Int
}

struct ConfirmedSegmentedMonthlyProrationV2: Equatable {
    let sourceId: String
    let checkedAtMs: Int64
    let method: ConfirmedProrationMethodV2
    let segments: [ConfirmedProrationSegmentV2]

    init(
        sourceId: String,
        checkedAtMs: Int64,
        method: ConfirmedProrationMethodV2 = .scheduledMinutes,
        segments: [ConfirmedProrationSegmentV2]
    ) {
        self.sourceId = sourceId
        self.checkedAtMs = checkedAtMs
        self.method = method
        self.segments = segments
    }
}

struct SegmentedMonthlyBasePieceV2: Equatable {
    let versionId: String
    let startEpochDay: Int64
    let endEpochDay: Int64
    let scheduledMinutes: Int
    let factor: Double
    let fullMonthBaseGross: Double
    let proratedBaseGross: Double
    let fullMonthStructuralOvertimeMinutes: Double
    let proratedStructuralOvertimeMinutes: Double
    let fullMonthStructuralOvertimeGross: Double
    let proratedStructuralOvertimeGross: Double

    init(
        versionId: String,
        startEpochDay: Int64,
        endEpochDay: Int64,
        scheduledMinutes: Int,
        factor: Double,
        fullMonthBaseGross: Double,
        proratedBaseGross: Double,
        fullMonthStructuralOvertimeMinutes: Double = 0,
        proratedStructuralOvertimeMinutes: Double = 0,
        fullMonthStructuralOvertimeGross: Double = 0,
        proratedStructuralOvertimeGross: Double = 0
    ) {
        self.versionId = versionId
        self.startEpochDay = startEpochDay
        self.endEpochDay = endEpochDay
        self.scheduledMinutes = scheduledMinutes
        self.factor = factor
        self.fullMonthBaseGross = fullMonthBaseGross
        self.proratedBaseGross = proratedBaseGross
        self.fullMonthStructuralOvertimeMinutes = fullMonthStructuralOvertimeMinutes
        self.proratedStructuralOvertimeMinutes = proratedStructuralOvertimeMinutes
        self.fullMonthStructuralOvertimeGross = fullMonthStructuralOvertimeGross
        self.proratedStructuralOvertimeGross = proratedStructuralOvertimeGross
    }
}

struct SegmentedMonthlyBaseResultV2: Equatable {
    let pieces: [SegmentedMonthlyBasePieceV2]
    let baseGross: Double?
    let reliable: Bool
    let warnings: [String]
}

/// Miroir iOS du calcul Android de base mensuelle segmentée.
///
/// Aucun prorata par jours calendaires n'est créé. Les minutes planifiées, les bornes du segment et
/// leur source doivent être confirmées en amont. Les primes, paniers, absences et majorations
/// variables restent hors de cette couche afin de ne jamais être comptés deux fois.
enum ConfirmedSegmentedMonthlyProrationCalculatorV2 {
    static let missingProrationWarning =
        "Proratisation mensuelle : base planifiée confirmée absente ; aucun prorata calendaire n'est inventé."
    static let invalidProrationWarning =
        "Proratisation mensuelle : la base confirmée ne correspond pas exactement aux segments contractuels du mois ; calcul bloqué."
    static let unsupportedContractWarning =
        "Proratisation mensuelle : ce type de contrat ne peut pas être proratisé automatiquement avec des minutes planifiées confirmées."
    static let missingPayrollRuleWarning =
        "Proratisation mensuelle : une règle nécessaire à la base mensuelle du segment n'est pas confirmée ; calcul bloqué."

    static func calculate(
        segments: [SalaryEmploymentContractCoverageSegmentV2],
        rulesByVersionId: [String: PayrollRulesV2],
        proration: ConfirmedSegmentedMonthlyProrationV2?
    ) -> SegmentedMonthlyBaseResultV2 {
        guard let proration else { return blocked(missingProrationWarning) }
        guard validProration(proration, segments: segments),
              let totalScheduled = scheduledTotal(proration.segments),
              totalScheduled > 0 else {
            return blocked(invalidProrationWarning)
        }

        let scheduledBySegment = Dictionary(
            uniqueKeysWithValues: proration.segments.map {
                (key($0.versionId, start: $0.startEpochDay, end: $0.endEpochDay), $0.scheduledMinutes)
            }
        )

        var pieces: [SegmentedMonthlyBasePieceV2] = []
        for segment in segments.sorted(by: { $0.startEpochDay < $1.startEpochDay }) {
            let versionId = segment.snapshot.versionId.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let scheduled = scheduledBySegment[
                key(versionId, start: segment.startEpochDay, end: segment.endEpochDay)
            ] else {
                return blocked(invalidProrationWarning)
            }
            let rules = rulesByVersionId[versionId] ?? PayrollRulesV2()
            guard let base = fullMonthBaseInfo(contract: segment.snapshot.contract, rules: rules) else {
                switch segment.snapshot.contract.type {
                case .forfaitHours, .forfaitDays, .forfait, .other:
                    return blocked(unsupportedContractWarning)
                case .fullTime, .partTime:
                    return blocked(missingPayrollRuleWarning)
                }
            }
            let factor = Double(scheduled) / Double(totalScheduled)
            pieces.append(
                SegmentedMonthlyBasePieceV2(
                    versionId: versionId,
                    startEpochDay: segment.startEpochDay,
                    endEpochDay: segment.endEpochDay,
                    scheduledMinutes: scheduled,
                    factor: factor,
                    fullMonthBaseGross: base.gross,
                    proratedBaseGross: base.gross * factor,
                    fullMonthStructuralOvertimeMinutes: base.structuralMinutes,
                    proratedStructuralOvertimeMinutes: base.structuralMinutes * factor,
                    fullMonthStructuralOvertimeGross: base.structuralGross,
                    proratedStructuralOvertimeGross: base.structuralGross * factor
                )
            )
        }

        return SegmentedMonthlyBaseResultV2(
            pieces: pieces,
            baseGross: pieces.reduce(0.0) { $0 + $1.proratedBaseGross },
            reliable: true,
            warnings: []
        )
    }

    private struct FullMonthBaseInfo {
        let gross: Double
        let structuralMinutes: Double
        let structuralGross: Double
    }

    private static func fullMonthBaseInfo(
        contract: ContractV2,
        rules: PayrollRulesV2
    ) -> FullMonthBaseInfo? {
        guard let rate = contract.grossHourlyRate,
              rate.isFinite,
              rate > 0,
              let weekly = contract.contractualWeeklyMinutes,
              weekly > 0 else {
            return nil
        }

        switch contract.type {
        case .partTime:
            return .init(
                gross: Double(weekly) * (52.0 / 12.0) / 60.0 * rate,
                structuralMinutes: 0,
                structuralGross: 0
            )

        case .fullTime:
            // Le seuil régulier doit rester explicite : le remplacer par la durée du contrat
            // ferait disparaître silencieusement les heures structurelles d'un contrat > seuil.
            guard let regularLimit = rules.weeklyRegularMinutes, regularLimit > 0 else { return nil }
            let result = FullTimeStructuralOvertimeV2.calculate(
                contractualWeeklyMinutes: weekly,
                regularWeeklyLimit: regularLimit,
                paidWeeks: [],
                grossHourlyRate: rate,
                overtimeTiers: rules.overtimeTiers
            )
            guard !result.provisionalRateUsed,
                  result.unresolvedStructuralOvertimeMinutes <= 0 else {
                return nil
            }
            return .init(
                gross: result.monthlyBaseGross,
                structuralMinutes: result.monthlyStructuralOvertimeMinutes,
                structuralGross: result.structuralOvertimeGross
            )

        case .forfaitHours, .forfaitDays, .forfait, .other:
            return nil
        }
    }

    private static func validProration(
        _ proration: ConfirmedSegmentedMonthlyProrationV2,
        segments: [SalaryEmploymentContractCoverageSegmentV2]
    ) -> Bool {
        guard !segments.isEmpty,
              continuous(segments),
              !proration.sourceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              proration.checkedAtMs >= 0,
              proration.method == .scheduledMinutes else {
            return false
        }

        let expectedKeys = segments.map {
            key($0.snapshot.versionId, start: $0.startEpochDay, end: $0.endEpochDay)
        }
        guard expectedKeys.allSatisfy({ !$0.versionId.isEmpty }),
              Set(expectedKeys).count == expectedKeys.count else {
            return false
        }

        let providedKeys = proration.segments.map {
            key($0.versionId, start: $0.startEpochDay, end: $0.endEpochDay)
        }
        guard proration.segments.allSatisfy({ $0.endEpochDay >= $0.startEpochDay }),
              providedKeys.allSatisfy({ !$0.versionId.isEmpty }),
              Set(providedKeys).count == providedKeys.count,
              Set(providedKeys) == Set(expectedKeys),
              proration.segments.allSatisfy({ $0.scheduledMinutes >= 0 }),
              let total = scheduledTotal(proration.segments),
              total > 0 else {
            return false
        }
        return true
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

    private static func scheduledTotal(_ segments: [ConfirmedProrationSegmentV2]) -> Int64? {
        var total: Int64 = 0
        for segment in segments {
            guard segment.scheduledMinutes >= 0 else { return nil }
            let value = Int64(segment.scheduledMinutes)
            let addition = total.addingReportingOverflow(value)
            guard !addition.overflow else { return nil }
            total = addition.partialValue
        }
        return total
    }

    private static func key(
        _ versionId: String,
        start: Int64,
        end: Int64
    ) -> SegmentKey {
        SegmentKey(
            versionId: versionId.trimmingCharacters(in: .whitespacesAndNewlines),
            startEpochDay: start,
            endEpochDay: end
        )
    }

    private struct SegmentKey: Hashable {
        let versionId: String
        let startEpochDay: Int64
        let endEpochDay: Int64
    }

    private static func blocked(_ warning: String) -> SegmentedMonthlyBaseResultV2 {
        SegmentedMonthlyBaseResultV2(
            pieces: [],
            baseGross: nil,
            reliable: false,
            warnings: [warning]
        )
    }
}
