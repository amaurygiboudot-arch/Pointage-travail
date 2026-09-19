import Foundation

/// Cotisation patronale accidents du travail / maladies professionnelles.
///
/// Le taux dépend de l'établissement. Il doit donc provenir d'une source employeur
/// confirmée : HoraTrack ne sélectionne jamais un taux collectif par défaut. Cette
/// cotisation reste exclusivement patronale et ne diminue jamais le net salarié.
enum EmployerAtMpContributionV2 {
    struct Result: Equatable {
        let rate: Double?
        let baseGross: Double
        let employerAmount: Double?
        let complete: Bool
        let warnings: [String]
    }

    static func calculate(gross: Double, confirmedRate: Double?) -> Result {
        let validGross = gross.isFinite && gross >= 0 ? gross : nil
        let base = validGross ?? 0
        let rate = confirmedRate.flatMap { value in
            value.isFinite && value >= 0 && value <= 1 ? value : nil
        }
        guard let validGross, let rate else {
            var warnings: [String] = []
            if validGross == nil {
                warnings.append(
                    "AT/MP employeur : assiette brute invalide ; aucun montant patronal n'est calculé."
                )
            }
            if rate == nil {
                warnings.append(
                    "AT/MP employeur : taux de l'établissement non renseigné ; coût employeur incomplet."
                )
            }
            return Result(
                rate: rate,
                baseGross: base,
                employerAmount: nil,
                complete: false,
                warnings: warnings
            )
        }

        return Result(
            rate: rate,
            baseGross: validGross,
            employerAmount: validGross * rate,
            complete: true,
            warnings: []
        )
    }
}
