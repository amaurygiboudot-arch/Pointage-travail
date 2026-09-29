import Foundation

struct SalarySegmentedPayrollPremiumEvidenceBridgeResultV2 {
    let evidence: [SalarySegmentedPayrollPremiumEvidenceV2]
    let reliable: Bool
    let warnings: [String]
}

enum SalarySegmentedPayrollPremiumEvidenceBridgeV2 {
    static let nightSourceWarning =
        "Preuves B21 : historique daté des plages de nuit non fiable."
    static let nightCoverageWarning =
        "Preuves B21 : aucune plage de nuit confirmée unique ne couvre toute la tranche."
    static let nightMismatchWarning =
        "Preuves B21 : multiplicateur de nuit incohérent entre le snapshot de paie et la plage horaire confirmée."
    static let futureRuleWarning =
        "Preuves B21 : un snapshot de règle a été confirmé dans le futur ; contexte premium bloqué."

    static func build(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2,
        nightSnapshots: [SalaryConventionNightRuleSnapshotV2],
        nightSourceReliable: Bool,
        nightWarnings: [String],
        holidayScope: FrenchPublicHolidayCalendarV2.Scope?,
        now: Date
    ) -> SalarySegmentedPayrollPremiumEvidenceBridgeResultV2 {
        let timeline = SalaryPayrollCalculationTimelineV2.align(
            contracts: contracts,
            rules: rules
        )
        var warnings = unique(timeline.warnings + nightWarnings)
        func blocked(_ message: String) -> SalarySegmentedPayrollPremiumEvidenceBridgeResultV2 {
            .init(evidence: [], reliable: false, warnings: unique(warnings + [message]))
        }

        guard timeline.reliable, !timeline.slices.isEmpty else {
            return blocked(SalarySegmentedPayrollSessionEvidenceBuilderV2.ruleWarning)
        }
        let nowSeconds = now.timeIntervalSince1970
        guard nowSeconds.isFinite, nowSeconds > 0 else { return blocked(futureRuleWarning) }

        guard let scope = holidayScope else {
            return blocked(SalarySegmentedPayrollSessionEvidenceBuilderV2.holidayWarning)
        }
        guard scope.complete else {
            if let warning = scope.warning { warnings.append(warning) }
            return blocked(SalarySegmentedPayrollSessionEvidenceBuilderV2.holidayWarning)
        }

        let needsNight = timeline.slices.contains {
            $0.ruleSnapshot.rules.nightMultiplier != nil
        }
        let history: SalaryConventionNightRuleHistoryV2?
        if needsNight {
            guard nightSourceReliable,
                  let value = SalaryConventionNightRuleHistoryV2(nightSnapshots) else {
                return blocked(nightSourceWarning)
            }
            history = value
        } else {
            history = nil
        }

        let nowMs = nowSeconds * 1_000
        var output: [SalarySegmentedPayrollPremiumEvidenceV2] = []
        for slice in timeline.slices {
            let ruleSnapshot = slice.ruleSnapshot
            guard !ruleSnapshot.sourceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  Double(ruleSnapshot.checkedAtMs) <= nowMs else {
                return blocked(futureRuleWarning)
            }

            let nightSnapshot: SalaryConventionNightRuleSnapshotV2?
            if let multiplier = ruleSnapshot.rules.nightMultiplier {
                guard let start = history?.applicable(
                    idcc: rules.idcc,
                    epochDay: slice.startEpochDay
                ),
                let end = history?.applicable(
                    idcc: rules.idcc,
                    epochDay: slice.endEpochDay
                ),
                start.versionId == end.versionId,
                start.sourceId == end.sourceId,
                start.applies(to: slice.startEpochDay),
                start.applies(to: slice.endEpochDay),
                Double(start.checkedAtMs) <= nowMs,
                !start.sourceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                    return blocked(nightCoverageWarning)
                }
                guard abs(start.rule.multiplier - multiplier) <= 0.000_001 else {
                    return blocked(nightMismatchWarning)
                }
                nightSnapshot = start
            } else {
                nightSnapshot = nil
            }

            let nightSourceId = nightSnapshot?.sourceId
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? "none"
            let ruleSourceId = ruleSnapshot.sourceId
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let sourceId =
                "premium-context-v1|rule=\(ruleSourceId)" +
                "|night=\(nightSourceId)" +
                "|holiday=\(holidayKey(scope))"

            output.append(
                SalarySegmentedPayrollPremiumEvidenceV2(
                    slice: slice,
                    sourceId: sourceId,
                    reliable: true,
                    nightRule: nightSnapshot?.rule,
                    holidayScope: scope
                )
            )
        }

        return .init(
            evidence: output,
            reliable: true,
            warnings: unique(warnings)
        )
    }

    private static func holidayKey(
        _ scope: FrenchPublicHolidayCalendarV2.Scope
    ) -> String {
        let jurisdiction: String
        switch scope.jurisdiction {
        case .commonFrance: jurisdiction = "COMMON_FRANCE"
        case .alsaceMoselle: jurisdiction = "ALSACE_MOSELLE"
        case .guadeloupe: jurisdiction = "GUADELOUPE"
        case .martinique: jurisdiction = "MARTINIQUE"
        case .guyane: jurisdiction = "GUYANE"
        case .mayotte: jurisdiction = "MAYOTTE"
        case .reunion: jurisdiction = "REUNION"
        case .saintBarthelemy: jurisdiction = "SAINT_BARTHELEMY"
        case .saintMartin: jurisdiction = "SAINT_MARTIN"
        case .specialTerritoryUnknown: jurisdiction = "SPECIAL_TERRITORY_UNKNOWN"
        case .addressUnknown: jurisdiction = "ADDRESS_UNKNOWN"
        }
        return jurisdiction + ":" + (scope.postalCode ?? "-")
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
