import Foundation

/// Noyau iOS de calcul de la réduction générale dégressive unique (RGDU) 2026.
///
/// Limité au cas mensuel standard explicitement confirmé. Les cas particuliers
/// restent fail-closed. Le coefficient maximal provient exclusivement du contexte
/// légal daté/sourcé, jamais du seul effectif de l'entreprise.
enum EmployerGeneralReduction2026V2 {
    private static let smicHourly2026 = 12.02
    private static let legalWeeklyMinutes = 35 * 60
    private static let tMin = 0.0200
    private static let exponent = 1.75

    enum ContractKind: Equatable {
        case fullTime
        case partTime
        case unsupported
    }

    struct Input: Equatable {
        let year: Int
        let reductionRemunerationMonthly: Double
        let rateContext: EmployerGeneralReductionRateContext2026V2.Snapshot?
        let contractType: ContractKind?
        let contractualWeeklyMinutes: Int?
        let additionalPaidMinutes: Double?
        let fullMonthPresent: Bool?
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

        guard EmployerGeneralReductionRateContext2026V2.isUsable(input.rateContext),
              let rateContext = input.rateContext,
              let tDelta = rateContext.tDelta,
              let maximumCoefficient = rateContext.maximumCoefficient else {
            let warnings = input.rateContext?.warnings ?? []
            return blocked(
                warnings.isEmpty
                    ? ["RGDU 2026 : régime de contribution logement et somme des taux éligibles à confirmer."]
                    : warnings
            )
        }
        guard maximumCoefficient >= tMin,
              abs((tMin + tDelta) - maximumCoefficient) < 0.000_000_1 else {
            return blocked("RGDU 2026 : contexte de coefficient incohérent ; calcul automatique bloqué.")
        }

        guard let contractType = input.contractType else {
            return blocked("RGDU 2026 : type de contrat à confirmer.")
        }
        guard contractType == .fullTime || contractType == .partTime else {
            return blocked("RGDU 2026 : ce type de contrat nécessite une règle dédiée avant calcul automatique.")
        }
        guard let weekly = input.contractualWeeklyMinutes, weekly > 0 else {
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

        let baseRatio = min(Double(weekly) / Double(legalWeeklyMinutes), 1)
        let contractualMinimum = smicHourly2026 * 35 * 52 / 12 * baseRatio
        let additionalMinimum = smicHourly2026 * (additionalMinutes / 60)
        let referenceMinimum = contractualMinimum + additionalMinimum
        let threshold = 3 * referenceMinimum
        let remuneration = input.reductionRemunerationMonthly

        if remuneration <= 0 {
            return Result(
                amount: 0,
                coefficient: maximumCoefficient,
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

        let formulaBase = 0.5 * (3 * referenceMinimum / remuneration - 1)
        let rawCoefficient = tMin + tDelta * pow(max(formulaBase, 0), exponent)
        let coefficient = min(max(round4(rawCoefficient), 0), maximumCoefficient)
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

    private static func round4(_ value: Double) -> Double {
        (value * 10_000).rounded() / 10_000
    }

    private static func roundCents(_ value: Double) -> Double {
        (value * 100).rounded() / 100
    }

    private static func blocked(_ warning: String) -> Result {
        blocked([warning])
    }

    private static func blocked(_ warnings: [String]) -> Result {
        Result(
            amount: nil,
            coefficient: nil,
            referenceMinimumMonthly: nil,
            thresholdMonthly: nil,
            reliable: false,
            warnings: Array(Set(warnings)).sorted()
        )
    }
}
