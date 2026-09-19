import Foundation

/// Calcul annuel RGDU 2026 limité au cas standard homogène.
/// Les années incomplètes ou les changements de contrat, durée, régime logement
/// ou somme de taux éligibles restent volontairement bloqués.
enum EmployerGeneralReductionAnnual2026V2 {
    private static let monthsInYear = 12.0

    struct Input: Equatable {
        let year: Int
        let annualReductionRemuneration: Double
        let rateContext: EmployerGeneralReductionRateContext2026V2.Snapshot?
        let contractType: EmployerGeneralReduction2026V2.ContractKind?
        let contractualWeeklyMinutes: Int?
        let additionalPaidMinutesAnnual: Double?
        let fullCalendarYearPresent: Bool?
        let standardCommonLawCaseConfirmed: Bool?
        let homogeneousAnnualParametersConfirmed: Bool?
    }

    struct Result: Equatable {
        let amount: Double?
        let coefficient: Double?
        let referenceMinimumAnnual: Double?
        let thresholdAnnual: Double?
        let reliable: Bool
        let warnings: [String]
    }

    static func calculate(_ input: Input) -> Result {
        guard input.year == 2026 else {
            return blocked("RGDU annuelle : barème non intégré pour \(input.year).")
        }
        guard input.annualReductionRemuneration.isFinite,
              input.annualReductionRemuneration >= 0 else {
            return blocked("RGDU annuelle 2026 : rémunération annuelle de référence invalide.")
        }
        guard let additionalMinutes = input.additionalPaidMinutesAnnual,
              additionalMinutes.isFinite,
              additionalMinutes >= 0 else {
            return blocked("RGDU annuelle 2026 : heures supplémentaires/complémentaires rémunérées à confirmer, même si elles sont nulles.")
        }
        guard input.fullCalendarYearPresent == true else {
            return blocked("RGDU annuelle 2026 : présence sur l'année civile complète non confirmée ; régularisation automatique bloquée.")
        }
        guard input.standardCommonLawCaseConfirmed == true else {
            return blocked("RGDU annuelle 2026 : cas de droit commun non confirmé sur toute l'année.")
        }
        guard input.homogeneousAnnualParametersConfirmed == true else {
            return blocked("RGDU annuelle 2026 : stabilité du contrat, de la durée contractuelle, du régime logement et des taux éligibles à confirmer sur toute l'année.")
        }

        let monthlyEquivalent = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            .init(
                year: input.year,
                reductionRemunerationMonthly: input.annualReductionRemuneration / monthsInYear,
                rateContext: input.rateContext,
                contractType: input.contractType,
                contractualWeeklyMinutes: input.contractualWeeklyMinutes,
                additionalPaidMinutes: additionalMinutes / monthsInYear,
                fullMonthPresent: true,
                standardCommonLawCaseConfirmed: true
            )
        )
        guard monthlyEquivalent.reliable,
              let coefficient = monthlyEquivalent.coefficient,
              let monthlyMinimum = monthlyEquivalent.referenceMinimumMonthly,
              let monthlyThreshold = monthlyEquivalent.thresholdMonthly else {
            return blocked(
                monthlyEquivalent.warnings.isEmpty
                    ? ["RGDU annuelle 2026 : paramètres annuels insuffisants pour établir le coefficient."]
                    : monthlyEquivalent.warnings
            )
        }

        let annualMinimum = monthlyMinimum * monthsInYear
        let annualThreshold = monthlyThreshold * monthsInYear
        let amount = roundCents(input.annualReductionRemuneration * coefficient)

        return Result(
            amount: amount,
            coefficient: coefficient,
            referenceMinimumAnnual: annualMinimum,
            thresholdAnnual: annualThreshold,
            reliable: true,
            warnings: []
        )
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
            referenceMinimumAnnual: nil,
            thresholdAnnual: nil,
            reliable: false,
            warnings: Array(Set(warnings)).sorted()
        )
    }
}
