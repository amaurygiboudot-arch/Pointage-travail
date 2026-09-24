import Foundation

struct SalarySegmentedPayrollBoundaryAssessmentV2: Equatable {
    let timelineReliable: Bool
    let safeForIndependentWeeklyVariableCalculation: Bool
    let transitionEpochDays: [Int64]
    let warnings: [String]
}

/// Vérifie si les changements datés de contrat/règles coupent une semaine de paie.
///
/// Les heures supplémentaires et complémentaires sont des mécanismes hebdomadaires. Tant qu'un
/// calculateur segmenté ne transporte pas le contexte cumulé d'une même semaine à travers un
/// changement de contrat/règle, HoraTrack ne doit jamais calculer chaque tranche indépendamment
/// lorsque la transition intervient en cours de semaine.
///
/// Cette couche ne calcule aucun euro. Elle autorise seulement un futur calcul indépendant par
/// tranches lorsque chaque transition interne commence un lundi civil (semaine ISO).
enum SalarySegmentedPayrollBoundaryV2 {
    static let timelineWarning =
        "Paie segmentée : contrat et règles datées ne produisent pas une timeline mensuelle fiable ; éléments variables bloqués."
    static let midweekTransitionWarning =
        "Paie segmentée : un changement de contrat ou de règle intervient en cours de semaine ; heures supplémentaires/complémentaires et majorations variables restent bloquées jusqu’à un calcul hebdomadaire continu."

    static func assess(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        rules: SalaryConventionCoverageV2
    ) -> SalarySegmentedPayrollBoundaryAssessmentV2 {
        let timeline = SalaryPayrollCalculationTimelineV2.align(
            contracts: contracts,
            rules: rules
        )
        guard timeline.reliable, !timeline.slices.isEmpty else {
            return SalarySegmentedPayrollBoundaryAssessmentV2(
                timelineReliable: false,
                safeForIndependentWeeklyVariableCalculation: false,
                transitionEpochDays: [],
                warnings: unique(timeline.warnings + [timelineWarning])
            )
        }

        let sorted = timeline.slices.sorted { $0.startEpochDay < $1.startEpochDay }
        let transitions = Array(sorted.dropFirst().map(\.startEpochDay))
        let midweek = transitions.filter { !isMondayEpochDay($0) }

        return SalarySegmentedPayrollBoundaryAssessmentV2(
            timelineReliable: true,
            safeForIndependentWeeklyVariableCalculation: midweek.isEmpty,
            transitionEpochDays: transitions,
            warnings: midweek.isEmpty ? [] : [midweekTransitionWarning]
        )
    }

    /// 1970-01-01 (epochDay 0) est un jeudi ; epochDay 4 est donc un lundi.
    private static func isMondayEpochDay(_ epochDay: Int64) -> Bool {
        let remainder = epochDay % 7
        let normalized = remainder >= 0 ? remainder : remainder + 7
        return normalized == 4
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
