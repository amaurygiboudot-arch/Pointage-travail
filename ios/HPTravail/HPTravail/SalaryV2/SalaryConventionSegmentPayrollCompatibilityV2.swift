import Foundation

struct SalaryConventionSegmentPayrollCompatibilityResultV2: Equatable {
    let rules: PayrollRulesV2?
    let compatibleForSingleMonthlyCalculation: Bool
    let warnings: [String]
}

/// Autorise un calcul mensuel unique uniquement lorsque toutes les versions conventionnelles
/// datées du mois portent exactement les mêmes paramètres de paie.
///
/// Les identifiants de version, sources et dates peuvent changer sans effet monétaire.
/// Toute différence dans PayrollRulesV2 reste bloquante : aucun rapprochement partiel n'est fait.
enum SalaryConventionSegmentPayrollCompatibilityV2 {
    static let equivalentVersionsWarning =
        "Convention collective : plusieurs versions datées couvrent le mois mais leurs paramètres de paie sont identiques ; le calcul mensuel unique peut être conservé."

    static let changedPayrollRulesWarning =
        "Convention collective : une règle de paie change en cours de mois ; HoraTrack conserve les versions datées séparées et bloque le calcul mensuel unique tant qu'une allocation temporelle adaptée n'est pas appliquée."

    static func resolve(
        _ segments: [SalaryConventionCoverageSegmentV2]
    ) -> SalaryConventionSegmentPayrollCompatibilityResultV2 {
        guard let first = segments.first?.snapshot.rules else {
            return SalaryConventionSegmentPayrollCompatibilityResultV2(
                rules: nil,
                compatibleForSingleMonthlyCalculation: false,
                warnings: []
            )
        }

        guard segments.dropFirst().allSatisfy({ $0.snapshot.rules == first }) else {
            return SalaryConventionSegmentPayrollCompatibilityResultV2(
                rules: nil,
                compatibleForSingleMonthlyCalculation: false,
                warnings: [changedPayrollRulesWarning]
            )
        }

        return SalaryConventionSegmentPayrollCompatibilityResultV2(
            rules: first,
            compatibleForSingleMonthlyCalculation: true,
            warnings: segments.count > 1 ? [equivalentVersionsWarning] : []
        )
    }
}
