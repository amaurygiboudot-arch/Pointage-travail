import Foundation

struct SalaryConfirmedCashGrossFixedFactV2: Equatable {
    let id: String
    let amount: Double?
    let applicable: Bool
    let reliable: Bool
    var warnings: [String] = []
}

/// Transforme uniquement des faits fixes déjà résolus en paquet consommable par le cash gross.
/// Aucune lecture de store ni formule métier ici.
enum SalaryConfirmedCashGrossComponentsResolverV2 {
    static let coverageWarning =
        "Brut en espèces segmenté : exhaustivité des composantes fixes non confirmée."
    static let factWarning =
        "Brut en espèces segmenté : composante fixe applicable absente, dupliquée ou non fiable."

    static func resolve(
        facts: [SalaryConfirmedCashGrossFixedFactV2],
        exhaustive: Bool,
        sourceId: String,
        warnings: [String] = []
    ) -> SalaryConfirmedCashGrossComponentsV2 {
        let normalizedIds = facts.map { normalized($0.id) }
        let uniqueIds = normalizedIds.allSatisfy { !$0.isEmpty } &&
            Set(normalizedIds).count == normalizedIds.count
        let factsReliable = facts.allSatisfy { fact in
            guard fact.reliable else { return false }
            guard fact.applicable else { return true }
            guard let amount = fact.amount else { return false }
            return amount.isFinite && amount >= 0
        }
        let components = facts.compactMap { fact -> SalaryConfirmedCashGrossComponentV2? in
            guard fact.applicable, fact.reliable, let amount = fact.amount,
                  amount.isFinite, amount >= 0 else { return nil }
            return .init(
                id: normalized(fact.id),
                amount: amount,
                reliable: true,
                warnings: fact.warnings
            )
        }
        let resolvedExhaustive = exhaustive && !normalized(sourceId).isEmpty && uniqueIds && factsReliable
        var allWarnings = warnings + facts.flatMap(\.warnings)
        if !exhaustive || normalized(sourceId).isEmpty { allWarnings.append(coverageWarning) }
        if !uniqueIds || !factsReliable { allWarnings.append(factWarning) }

        return .init(
            components: components,
            exhaustive: resolvedExhaustive,
            sourceId: normalized(sourceId),
            warnings: unique(allWarnings)
        )
    }

    private static func normalized(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
