import Foundation

struct SalaryConfirmedCashGrossComponentV2: Equatable {
    let id: String
    let amount: Double
    let reliable: Bool
    var warnings: [String] = []
}

struct SalaryConfirmedCashGrossComponentsV2: Equatable {
    let components: [SalaryConfirmedCashGrossComponentV2]
    let exhaustive: Bool
    let sourceId: String
    var warnings: [String] = []
}

struct SalarySegmentedCashGrossAssemblyResultV2: Equatable {
    let workedGross: Double?
    let additionalCashGross: Double?
    let cashGross: Double?
    let reliable: Bool
    let warnings: [String]
}

/// B20 -> brut en espèces final.
/// Une liste vide de composantes fixes vaut zéro uniquement si son exhaustivité est confirmée.
/// Les avantages en nature restent hors cashGross et appartiennent à la projection sociale.
enum SalarySegmentedCashGrossAssemblerV2 {
    static let workedWarning =
        "Brut en espèces segmenté : brut de travail B20 absent ou non fiable."
    static let coverageWarning =
        "Brut en espèces segmenté : liste des composantes fixes non confirmée exhaustive."
    static let componentWarning =
        "Brut en espèces segmenté : composante fixe absente, dupliquée, non fiable ou invalide."
    static let amountWarning =
        "Brut en espèces segmenté : montant non fini, négatif ou total non représentable."

    static func assemble(
        worked: SalarySegmentedWorkedGrossProductionResultV2,
        fixed: SalaryConfirmedCashGrossComponentsV2
    ) -> SalarySegmentedCashGrossAssemblyResultV2 {
        let upstream = unique(worked.warnings + fixed.warnings)
        guard worked.reliable, worked.assembly.reliable,
              let workedGross = worked.assembly.workedGross,
              workedGross.isFinite, workedGross >= 0 else {
            return blocked(upstream + [workedWarning])
        }
        guard fixed.exhaustive, !normalized(fixed.sourceId).isEmpty else {
            return blocked(upstream + [coverageWarning])
        }

        let ids = fixed.components.map { normalized($0.id) }
        guard ids.allSatisfy({ !$0.isEmpty }),
              Set(ids).count == ids.count,
              fixed.components.allSatisfy({
                  $0.reliable && $0.amount.isFinite && $0.amount >= 0
              }) else {
            return blocked(upstream + fixed.components.flatMap(\.warnings) + [componentWarning])
        }

        var extras = 0.0
        for component in fixed.components {
            extras += component.amount
            guard extras.isFinite else {
                return blocked(upstream + fixed.components.flatMap(\.warnings) + [amountWarning])
            }
        }
        let cash = workedGross + extras
        guard cash.isFinite, cash >= 0 else {
            return blocked(upstream + fixed.components.flatMap(\.warnings) + [amountWarning])
        }

        return .init(
            workedGross: workedGross,
            additionalCashGross: extras,
            cashGross: cash,
            reliable: true,
            warnings: unique(upstream + fixed.components.flatMap(\.warnings))
        )
    }

    private static func blocked(_ warnings: [String]) -> SalarySegmentedCashGrossAssemblyResultV2 {
        .init(
            workedGross: nil,
            additionalCashGross: nil,
            cashGross: nil,
            reliable: false,
            warnings: unique(warnings)
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
