import Foundation

/// Régularise les avances mensuelles RGDU contre le droit annuel calculé.
/// Les douze mois doivent être connus explicitement, y compris les mois à 0 €.
enum EmployerGeneralReductionAnnualRegularizationV2 {
    struct MonthlyAdvance: Equatable {
        let month: Int
        let amount: Double
    }

    struct Result: Equatable {
        let annualEntitlement: Double?
        let advancesTotal: Double?
        /// Positif = réduction complémentaire ; négatif = reprise à régulariser.
        let adjustment: Double?
        let reliable: Bool
        let warnings: [String]
    }

    static func resolve(
        year: Int,
        annual: EmployerGeneralReductionAnnual2026V2.Result,
        monthlyAdvances: [MonthlyAdvance]
    ) -> Result {
        guard year == 2026 else {
            return blocked("Régularisation RGDU : barème non intégré pour \(year).")
        }

        let malformed = monthlyAdvances.contains {
            !(1...12).contains($0.month) || !$0.amount.isFinite || $0.amount < 0
        }
        guard !malformed else {
            return blocked("Régularisation RGDU 2026 : une avance mensuelle est invalide.")
        }

        let grouped = Dictionary(grouping: monthlyAdvances, by: \.month)
        guard !grouped.values.contains(where: { $0.count > 1 }) else {
            return blocked("Régularisation RGDU 2026 : plusieurs avances existent pour le même mois.")
        }

        let presentMonths = Set(monthlyAdvances.map(\.month))
        let expectedMonths = Set(1...12)
        guard presentMonths == expectedMonths else {
            let missing = expectedMonths.subtracting(presentMonths).sorted().map(String.init).joined(separator: ", ")
            return blocked("Régularisation RGDU 2026 : les 12 avances mensuelles doivent être connues explicitement ; mois manquants : \(missing).")
        }

        guard annual.reliable,
              let annualAmount = annual.amount,
              annualAmount.isFinite,
              annualAmount >= 0 else {
            return blocked(
                annual.warnings.isEmpty
                    ? ["Régularisation RGDU 2026 : droit annuel non calculable."]
                    : annual.warnings
            )
        }

        let advancesTotal = roundCents(monthlyAdvances.reduce(0) { $0 + $1.amount })
        let adjustment = roundCents(annualAmount - advancesTotal)

        return Result(
            annualEntitlement: annualAmount,
            advancesTotal: advancesTotal,
            adjustment: adjustment,
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
            annualEntitlement: nil,
            advancesTotal: nil,
            adjustment: nil,
            reliable: false,
            warnings: unique(warnings)
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
