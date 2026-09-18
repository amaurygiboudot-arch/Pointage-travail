import Foundation

/// Miroir Swift de la mensualisation Android d'un temps plein dont la durée contractuelle
/// peut dépasser la durée hebdomadaire régulière confirmée.
enum FullTimeStructuralOvertimeV2 {
    struct TierAmount: Equatable {
        let multiplier: Double
        let minutes: Double
        let gross: Double
    }

    struct Result: Equatable {
        let monthlyBaseGross: Double
        let monthlyRegularMinutes: Double
        let monthlyStructuralOvertimeMinutes: Double
        let structuralOvertimeGross: Double
        let variableOvertimeGross: Double
        let structuralTiers: [TierAmount]
        let variableTiers: [TierAmount]
        /// Compatibilité API : true signifie qu'au moins une tranche n'a pas pu être
        /// valorisée faute de taux confirmé. Aucun taux de secours n'est injecté.
        let provisionalRateUsed: Bool
        let unresolvedStructuralOvertimeMinutes: Double
        let unresolvedVariableOvertimeMinutes: Double
        let warnings: [String]
    }

    private struct Piece {
        let multiplier: Double
        let minutes: Double
        let gross: Double
    }

    private struct Rated {
        let minutes: Double
        let gross: Double
        let tiers: [Piece]
        let provisionalRateUsed: Bool
        let unresolvedMinutes: Double
        let warnings: [String]
    }

    static func calculate(
        contractualWeeklyMinutes: Int,
        regularWeeklyLimit: Int,
        paidWeeks: [Int],
        grossHourlyRate: Double,
        overtimeTiers: [OvertimeTierV2]
    ) -> Result {
        precondition(contractualWeeklyMinutes > 0)
        precondition(regularWeeklyLimit > 0)
        precondition(grossHourlyRate > 0 && grossHourlyRate.isFinite)

        let tiersStructurallyValid = OvertimeCoverageV2.isStructurallyValid(
            regularLimitMinutes: regularWeeklyLimit,
            tiers: overtimeTiers
        )
        let safeOvertimeTiers = OvertimeCoverageV2.calculationSafeTiers(
            regularLimitMinutes: regularWeeklyLimit,
            tiers: overtimeTiers
        )

        let factor = 52.0 / 12.0
        let regularContractMinutes = min(contractualWeeklyMinutes, regularWeeklyLimit)
        let structuralWeekly = ratedBetween(
            upper: contractualWeeklyMinutes,
            lower: regularWeeklyLimit,
            rate: grossHourlyRate,
            tiers: safeOvertimeTiers
        )
        let monthlyRegularMinutes = Double(regularContractMinutes) * factor
        let monthlyStructuralMinutes = structuralWeekly.minutes * factor
        let structuralGrossMonthly = structuralWeekly.gross * factor
        let monthlyBaseGross = monthlyRegularMinutes / 60.0 * grossHourlyRate + structuralGrossMonthly

        let variableParts = paidWeeks.map { paid in
            ratedBetween(
                upper: max(0, paid),
                lower: max(contractualWeeklyMinutes, regularWeeklyLimit),
                rate: grossHourlyRate,
                tiers: safeOvertimeTiers
            )
        }
        let variableGross = variableParts.reduce(0.0) { $0 + $1.gross }
        let allRated = [structuralWeekly] + variableParts
        var warnings = unique(allRated.flatMap(\.warnings))
        if !tiersStructurallyValid && !overtimeTiers.isEmpty {
            warnings.append("Paliers d'heures supplémentaires ambigus ou invalides : ils sont neutralisés et aucune valorisation n'est inventée pour les tranches concernées.")
        }

        return Result(
            monthlyBaseGross: monthlyBaseGross,
            monthlyRegularMinutes: monthlyRegularMinutes,
            monthlyStructuralOvertimeMinutes: monthlyStructuralMinutes,
            structuralOvertimeGross: structuralGrossMonthly,
            variableOvertimeGross: variableGross,
            structuralTiers: aggregate([structuralWeekly], monthly: true, factor: factor),
            variableTiers: aggregate(variableParts, monthly: false, factor: factor),
            provisionalRateUsed: allRated.contains { $0.provisionalRateUsed },
            unresolvedStructuralOvertimeMinutes: structuralWeekly.unresolvedMinutes * factor,
            unresolvedVariableOvertimeMinutes: variableParts.reduce(0.0) { $0 + $1.unresolvedMinutes },
            warnings: unique(warnings)
        )
    }

    /// Analyse la tranche (lower, upper] sans jamais laisser disparaître une minute.
    /// Une minute sans palier confirmé reste comptée mais n'alimente aucun montant.
    private static func ratedBetween(
        upper: Int,
        lower: Int,
        rate: Double,
        tiers: [OvertimeTierV2]
    ) -> Rated {
        if upper <= lower {
            return Rated(
                minutes: 0,
                gross: 0,
                tiers: [],
                provisionalRateUsed: false,
                unresolvedMinutes: 0,
                warnings: []
            )
        }

        let sorted = tiers.sorted { $0.fromMinutes < $1.fromMinutes }
        var cursor = lower
        var gross = 0.0
        var unresolvedMinutes = 0.0
        var pieces: [Piece] = []
        var warnings: [String] = []

        func piece(from: Int, to: Int, multiplier: Double) -> Piece? {
            guard to > from else { return nil }
            let minutes = Double(to - from)
            return Piece(
                multiplier: multiplier,
                minutes: minutes,
                gross: minutes / 60.0 * rate * multiplier
            )
        }

        func add(_ from: Int, _ to: Int, _ multiplier: Double) {
            guard let value = piece(from: from, to: to, multiplier: multiplier) else { return }
            pieces.append(value)
            gross += value.gross
        }

        func addUnresolved(_ from: Int, _ to: Int) {
            guard to > from else { return }
            unresolvedMinutes += Double(to - from)
            warnings.append("Palier d'heures supplémentaires non confirmé : aucune valorisation n'est injectée pour les minutes non couvertes ; le brut reste à confirmer.")
        }

        for tier in sorted {
            if cursor >= upper { continue }
            let tierStart = max(lower, tier.fromMinutes)
            let tierEnd = min(upper, tier.toMinutes ?? Int.max)
            if tierEnd <= cursor || tierEnd <= tierStart { continue }
            if tierStart > cursor {
                addUnresolved(cursor, min(tierStart, upper))
                cursor = min(tierStart, upper)
            }
            if cursor < upper && tierEnd > cursor {
                add(cursor, tierEnd, tier.multiplier)
                cursor = tierEnd
            }
        }
        if cursor < upper {
            addUnresolved(cursor, upper)
        }

        return Rated(
            minutes: Double(upper - lower),
            gross: gross,
            tiers: pieces,
            provisionalRateUsed: unresolvedMinutes > 0,
            unresolvedMinutes: unresolvedMinutes,
            warnings: unique(warnings)
        )
    }

    private static func aggregate(_ parts: [Rated], monthly: Bool, factor: Double) -> [TierAmount] {
        let scale = monthly ? factor : 1.0
        let grouped = Dictionary(grouping: parts.flatMap(\.tiers), by: \.multiplier)
        return grouped.map { multiplier, items in
            TierAmount(
                multiplier: multiplier,
                minutes: items.reduce(0.0) { $0 + $1.minutes } * scale,
                gross: items.reduce(0.0) { $0 + $1.gross } * scale
            )
        }.sorted { $0.multiplier < $1.multiplier }
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
