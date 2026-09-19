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
        let base = max(0, gross)
        guard let rate = confirmedRate,
              rate.isFinite,
              rate >= 0,
              rate <= 1 else {
            return Result(
                rate: nil,
                baseGross: base,
                employerAmount: nil,
                complete: false,
                warnings: [
                    "AT/MP employeur : taux de l'établissement non renseigné ; coût employeur incomplet."
                ]
            )
        }

        return Result(
            rate: rate,
            baseGross: base,
            employerAmount: base * rate,
            complete: true,
            warnings: []
        )
    }
}
