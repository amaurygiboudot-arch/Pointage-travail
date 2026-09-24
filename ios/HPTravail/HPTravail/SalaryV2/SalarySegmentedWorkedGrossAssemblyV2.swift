import Foundation

struct SalarySegmentedWorkedVariableGrossPieceV2: Equatable {
    let versionId: String
    let startEpochDay: Int64
    let endEpochDay: Int64
    let variableGross: Double
    let reliable: Bool
    let warnings: [String]

    init(
        versionId: String,
        startEpochDay: Int64,
        endEpochDay: Int64,
        variableGross: Double,
        reliable: Bool,
        warnings: [String] = []
    ) {
        self.versionId = versionId
        self.startEpochDay = startEpochDay
        self.endEpochDay = endEpochDay
        self.variableGross = variableGross
        self.reliable = reliable
        self.warnings = warnings
    }
}

struct SalarySegmentedWorkedGrossAssemblyResultV2: Equatable {
    let baseGross: Double?
    let variableGross: Double?
    let workedGross: Double?
    let reliable: Bool
    let warnings: [String]
}

/// Assemble uniquement une base mensualisée segmentée déjà confirmée avec les variables de
/// travail dont les bornes/version et la fiabilité sont elles aussi prouvées.
///
/// Ce résultat est un intermédiaire de brut de travail. Il ne contient volontairement ni primes
/// fixes mensuelles non rattachées au temps, ni avantages en nature, paniers, retenues, cotisations,
/// PAS ou net. Il ne doit donc jamais être présenté seul comme brut social final.
///
/// Invariant fail-closed : une pièce variable absente n'est jamais assimilée à zéro. Un zéro n'est
/// accepté que s'il est porté par une pièce explicite, fiable et correspondant exactement au segment.
enum SalarySegmentedWorkedGrossAssemblerV2 {
    static let baseWarning =
        "Brut segmenté : base mensuelle segmentée absente, incohérente ou non fiable ; assemblage bloqué."
    static let coverageWarning =
        "Brut segmenté : les variables prouvées ne correspondent pas exactement aux segments de base ; aucun zéro implicite n'est ajouté."
    static let variableReliabilityWarning =
        "Brut segmenté : au moins une variable de travail du segment reste non fiable ; brut de travail bloqué."
    static let amountWarning =
        "Brut segmenté : montant non fini ou négatif détecté ; assemblage bloqué."
    static let overflowWarning =
        "Brut segmenté : total monétaire non représentable de façon fiable ; assemblage bloqué."

    static func assemble(
        base: SegmentedMonthlyBaseResultV2,
        variables: [SalarySegmentedWorkedVariableGrossPieceV2]
    ) -> SalarySegmentedWorkedGrossAssemblyResultV2 {
        guard base.reliable,
              let baseAmount = base.baseGross,
              baseAmount.isFinite,
              baseAmount >= 0,
              !base.pieces.isEmpty else {
            return blocked(base.warnings + [baseWarning])
        }

        let baseKeys = base.pieces.map {
            key($0.versionId, start: $0.startEpochDay, end: $0.endEpochDay)
        }
        guard baseKeys.allSatisfy({ !$0.versionId.isEmpty }),
              Set(baseKeys).count == baseKeys.count else {
            return blocked(base.warnings + [baseWarning])
        }

        var recomputedBase = 0.0
        for piece in base.pieces {
            guard piece.proratedBaseGross.isFinite,
                  piece.proratedBaseGross >= 0 else {
                return blocked(base.warnings + [amountWarning])
            }
            recomputedBase += piece.proratedBaseGross
            guard recomputedBase.isFinite else {
                return blocked(base.warnings + [overflowWarning])
            }
        }
        guard abs(recomputedBase - baseAmount) <= currencyTolerance else {
            return blocked(base.warnings + [baseWarning])
        }

        let variableKeys = variables.map {
            key($0.versionId, start: $0.startEpochDay, end: $0.endEpochDay)
        }
        let variableWarnings = variables.flatMap(\.warnings)
        guard variableKeys.allSatisfy({ !$0.versionId.isEmpty }),
              Set(variableKeys).count == variableKeys.count,
              Set(variableKeys) == Set(baseKeys) else {
            return blocked(base.warnings + variableWarnings + [coverageWarning])
        }

        guard variables.allSatisfy(\.reliable) else {
            return blocked(
                base.warnings
                    + variableWarnings
                    + [variableReliabilityWarning]
            )
        }

        var variableTotal = 0.0
        for piece in variables {
            guard piece.variableGross.isFinite,
                  piece.variableGross >= 0 else {
                return blocked(
                    base.warnings
                        + variableWarnings
                        + [amountWarning]
                )
            }
            variableTotal += piece.variableGross
            guard variableTotal.isFinite else {
                return blocked(
                    base.warnings
                        + variableWarnings
                        + [overflowWarning]
                )
            }
        }

        let workedGross = baseAmount + variableTotal
        guard workedGross.isFinite else {
            return blocked(
                base.warnings
                    + variableWarnings
                    + [overflowWarning]
            )
        }

        return SalarySegmentedWorkedGrossAssemblyResultV2(
            baseGross: baseAmount,
            variableGross: variableTotal,
            workedGross: workedGross,
            reliable: true,
            warnings: unique(base.warnings + variableWarnings)
        )
    }

    private static func key(
        _ versionId: String,
        start: Int64,
        end: Int64
    ) -> SegmentKey {
        SegmentKey(
            versionId: versionId.trimmingCharacters(in: .whitespacesAndNewlines),
            startEpochDay: start,
            endEpochDay: end
        )
    }

    private static func blocked(
        _ warnings: [String]
    ) -> SalarySegmentedWorkedGrossAssemblyResultV2 {
        SalarySegmentedWorkedGrossAssemblyResultV2(
            baseGross: nil,
            variableGross: nil,
            workedGross: nil,
            reliable: false,
            warnings: unique(warnings)
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }

    private struct SegmentKey: Hashable {
        let versionId: String
        let startEpochDay: Int64
        let endEpochDay: Int64
    }

    private static let currencyTolerance = 0.005
}
