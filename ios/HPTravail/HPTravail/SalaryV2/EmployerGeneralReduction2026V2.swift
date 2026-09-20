import Foundation

/// Noyau iOS de calcul de la réduction générale dégressive unique (RGDU) 2026.
///
/// Ce composant ne s'applique qu'au cas mensuel standard explicitement confirmé.
/// Les cas particuliers (Mayotte, heures d'équivalence, caisses de congés,
/// intérim avec correctif, DFS, autre exonération/taux spécifique, salaire minimum
/// professionnel dérogatoire, mois incomplet, etc.) restent volontairement bloqués.
enum EmployerGeneralReduction2026V2 {
    private static let smicHourly2026 = 12.02
    private static let legalWeeklyMinutes = 35 * 60
    private static let tMin = 0.0200
    private static let tDeltaCapped0Point1Percent = 0.3781
    private static let tDeltaUncapped0Point5Percent = 0.3821
    private static let exponent = 1.75

    struct Input: Equatable {
        let year: Int
        /// Rémunération mensuelle entrant dans la formule RGDU.
        let reductionRemunerationMonthly: Double
        /// Régime FNAL/logement explicitement confirmé ; jamais déduit du seul effectif.
        let fnalTreatment: EmployerWorkforceContributionsV2.FnalTreatment?
        let contractType: ContractTypeV2?
        let contractualWeeklyMinutes: Int?
        /// Heures supplémentaires ou complémentaires rémunérées, sans leur majoration.
        /// Les fractions de minute sont conservées pour éviter tout arrondi amont.
        let additionalPaidMinutes: Double?
        /// true uniquement si la présence du salarié couvre le mois selon les règles RGDU.
        let fullMonthPresent: Bool?
        /// true uniquement lorsque le cas de droit commun couvert par ce noyau est confirmé.
        let standardCommonLawCaseConfirmed: Bool?
    }

    struct Result: Equatable {
        let amount: Double?
        let coefficient: Double?
        let referenceMinimumMonthly: Double?
        let thresholdMonthly: Double?
        let reliable: Bool
        let warnings: [String]
    }

    static func calculateMonthlyAdvance(_ input: Input) -> Result {
        guard input.year == 2026 else {
            return blocked("RGDU : barème non intégré pour \(input.year).")
        }
        guard input.reductionRemunerationMonthly.isFinite,
              input.reductionRemunerationMonthly >= 0 else {
            return blocked("RGDU 2026 : rémunération de référence invalide.")
        }
        guard let fnalTreatment = input.fnalTreatment else {
            return blocked("RGDU 2026 : régime FNAL/logement 0,10 % plafonné ou 0,50 % déplafonné à confirmer.")
        }
        guard let contractType = input.contractType else {
            return blocked("RGDU 2026 : type de contrat à confirmer.")
        }
        guard contractType == .fullTime || contractType == .partTime else {
            return blocked("RGDU 2026 : ce type de contrat nécessite une règle dédiée avant calcul automatique.")
        }
        guard let weeklyMinutes = input.contractualWeeklyMinutes,
              weeklyMinutes > 0 else {
            return blocked("RGDU 2026 : durée contractuelle hebdomadaire à confirmer.")
        }
        guard let additionalMinutes = input.additionalPaidMinutes,
              additionalMinutes.isFinite,
              additionalMinutes >= 0 else {
            return blocked("RGDU 2026 : heures supplémentaires/complémentaires rémunérées à confirmer, même si elles sont nulles.")
        }
        guard input.fullMonthPresent == true else {
            return blocked("RGDU 2026 : mois incomplet ou présence non confirmée ; proratisation légale à établir.")
        }
        guard input.standardCommonLawCaseConfirmed == true else {
            return blocked("RGDU 2026 : cas de droit commun non confirmé ; aucun coefficient standard n'est supposé.")
        }

        let baseRatio = min(Double(weeklyMinutes) / Double(legalWeeklyMinutes), 1.0)
        let contractualMinimum = smicHourly2026 * 35.0 * 52.0 / 12.0 * baseRatio
        let additionalMinimum = smicHourly2026 * (additionalMinutes / 60.0)
        let referenceMinimum = contractualMinimum + additionalMinimum
        let threshold = 3.0 * referenceMinimum
        let remuneration = input.reductionRemunerationMonthly

        if remuneration <= 0 {
            return Result(
                amount: 0,
                coefficient: maximumCoefficient(fnalTreatment),
                referenceMinimumMonthly: referenceMinimum,
                thresholdMonthly: threshold,
                reliable: true,
                warnings: []
            )
        }

        if threshold <= 0 || remuneration >= threshold {
            return Result(
                amount: 0,
                coefficient: 0,
                referenceMinimumMonthly: referenceMinimum,
                thresholdMonthly: threshold,
                reliable: true,
                warnings: []
            )
        }

        let tDelta: Double
        switch fnalTreatment {
        case .capped0Point1Percent:
            tDelta = tDeltaCapped0Point1Percent
        case .uncapped0Point5Percent:
            tDelta = tDeltaUncapped0Point5Percent
        }

        let formulaBase = 0.5 * (3.0 * referenceMinimum / remuneration - 1.0)
        let rawCoefficient = tMin + tDelta * pow(max(formulaBase, 0), exponent)
        let coefficient = min(max(round4(rawCoefficient), 0), maximumCoefficient(fnalTreatment))
        let amount = roundCents(remuneration * coefficient)

        return Result(
            amount: amount,
            coefficient: coefficient,
            referenceMinimumMonthly: referenceMinimum,
            thresholdMonthly: threshold,
            reliable: true,
            warnings: []
        )
    }

    private static func maximumCoefficient(
        _ fnalTreatment: EmployerWorkforceContributionsV2.FnalTreatment
    ) -> Double {
        switch fnalTreatment {
        case .capped0Point1Percent:
            return tMin + tDeltaCapped0Point1Percent
        case .uncapped0Point5Percent:
            return tMin + tDeltaUncapped0Point5Percent
        }
    }

    private static func round4(_ value: Double) -> Double {
        (value * 10_000).rounded() / 10_000
    }

    private static func roundCents(_ value: Double) -> Double {
        (value * 100).rounded() / 100
    }

    private static func blocked(_ warning: String) -> Result {
        Result(
            amount: nil,
            coefficient: nil,
            referenceMinimumMonthly: nil,
            thresholdMonthly: nil,
            reliable: false,
            warnings: [warning]
        )
    }
}
