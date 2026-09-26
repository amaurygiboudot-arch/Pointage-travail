import Foundation

struct SalarySegmentedWorkedVariableGrossPieceV2: Equatable {
    let companyId: String
    let versionId: String
    let startEpochDay: Int64
    let endEpochDay: Int64
    let variableGross: Double
    let reliable: Bool
    let warnings: [String]

    init(
        companyId: String,
        versionId: String,
        startEpochDay: Int64,
        endEpochDay: Int64,
        variableGross: Double,
        reliable: Bool,
        warnings: [String] = []
    ) {
        self.companyId = companyId
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
    static let contractWarning =
        "Brut segmenté : la couverture contractuelle sélectionnée est absente, non fiable ou ne correspond pas aux segments monétaires."
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
    static let breakdownWarning =
        "Brut segment? : la ventilation des variables ne correspond pas aux pi?ces mon?taires prouv?es ; raccord aval bloqu?."

    static func assemble(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        base: SegmentedMonthlyBaseResultV2,
        variableSource: SalarySegmentedWorkedVariableGrossSourceResultV2
    ) -> SalarySegmentedWorkedGrossAssemblyResultV2 {
        guard variableSource.reliable else {
            return blocked(
                contracts.warnings
                    + base.warnings
                    + variableSource.warnings
                    + [variableReliabilityWarning]
            )
        }
        if !variableSource.breakdowns.isEmpty {
            let piecePairs = variableSource.pieces.map {
                (key($0.versionId, start: $0.startEpochDay, end: $0.endEpochDay), $0)
            }
            let pieceKeys = piecePairs.map { $0.0 }
            let breakdownKeys = variableSource.breakdowns.map {
                key($0.versionId, start: $0.startEpochDay, end: $0.endEpochDay)
            }
            let validKeys = Set(pieceKeys).count == pieceKeys.count
                && Set(breakdownKeys).count == breakdownKeys.count
                && Set(pieceKeys) == Set(breakdownKeys)
            let validAmounts = variableSource.breakdowns.allSatisfy { item in
                let amounts = [
                    item.overtimeGross,
                    item.complementaryGross,
                    item.premiumGross,
                    item.variableGross
                ]
                let itemKey = key(item.versionId, start: item.startEpochDay, end: item.endEpochDay)
                guard let piece = piecePairs.first(where: { $0.0 == itemKey })?.1 else { return false }
                return item.companyId.trimmingCharacters(in: .whitespacesAndNewlines)
                        == contracts.companyId.trimmingCharacters(in: .whitespacesAndNewlines)
                    && amounts.allSatisfy { $0.isFinite && $0 >= 0 }
                    && abs(item.variableGross - piece.variableGross) <= currencyTolerance
            }
            guard validKeys, validAmounts else {
                return blocked(
                    contracts.warnings + base.warnings + variableSource.warnings + [breakdownWarning]
                )
            }
        }
        let assembled = assemble(
            contracts: contracts,
            base: base,
            variables: variableSource.pieces
        )
        return SalarySegmentedWorkedGrossAssemblyResultV2(
            baseGross: assembled.baseGross,
            variableGross: assembled.variableGross,
            workedGross: assembled.workedGross,
            reliable: assembled.reliable,
            warnings: unique(assembled.warnings + variableSource.warnings)
        )
    }

    /// Pont canonique B21 -> B20 : les avertissements globaux de B21 ne sont jamais perdus.
    static func assemble(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        base: SegmentedMonthlyBaseResultV2,
        variables: SalarySegmentedWorkedVariableGrossSourceResultV2
    ) -> SalarySegmentedWorkedGrossAssemblyResultV2 {
        let upstreamWarnings = unique(
            contracts.warnings + base.warnings + variables.warnings
        )
        guard variables.reliable else {
            return blocked(upstreamWarnings + [variableReliabilityWarning])
        }
        let result = assemble(
            contracts: contracts,
            base: base,
            variables: variables.pieces
        )
        return SalarySegmentedWorkedGrossAssemblyResultV2(
            baseGross: result.baseGross,
            variableGross: result.variableGross,
            workedGross: result.workedGross,
            reliable: result.reliable,
            warnings: unique(variables.warnings + result.warnings)
        )
    }

    static func assemble(
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        base: SegmentedMonthlyBaseResultV2,
        variables: [SalarySegmentedWorkedVariableGrossPieceV2]
    ) -> SalarySegmentedWorkedGrossAssemblyResultV2 {
        let companyId = contracts.companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        let contractSegments = contracts.calculationSegments
        guard contracts.sourceReliable,
              !companyId.isEmpty,
              !contractSegments.isEmpty,
              contractSegments.allSatisfy({
                  $0.snapshot.contract.employerId
                      .trimmingCharacters(in: .whitespacesAndNewlines) == companyId
              }) else {
            return blocked(
                contracts.warnings
                    + base.warnings
                    + [contractWarning]
            )
        }
        let upstreamWarnings = contracts.warnings + base.warnings

        guard base.reliable,
              let baseAmount = base.baseGross,
              baseAmount.isFinite,
              baseAmount >= 0,
              !base.pieces.isEmpty else {
            return blocked(upstreamWarnings + [baseWarning])
        }

        let baseKeys = base.pieces.map {
            key($0.versionId, start: $0.startEpochDay, end: $0.endEpochDay)
        }
        let contractKeys = contractSegments.map {
            key(
                $0.snapshot.versionId,
                start: $0.startEpochDay,
                end: $0.endEpochDay
            )
        }
        guard baseKeys.allSatisfy({ !$0.versionId.isEmpty }),
              Set(baseKeys).count == baseKeys.count,
              base.pieces.allSatisfy({ $0.endEpochDay >= $0.startEpochDay }) else {
            return blocked(upstreamWarnings + [baseWarning])
        }
        guard contractKeys.allSatisfy({ !$0.versionId.isEmpty }),
              Set(contractKeys).count == contractKeys.count,
              Set(contractKeys) == Set(baseKeys) else {
            return blocked(upstreamWarnings + [contractWarning])
        }

        var recomputedBase = 0.0
        var factorTotal = 0.0
        var scheduledTotal: Int64 = 0
        for piece in base.pieces {
            guard piece.scheduledMinutes >= 0,
                  piece.factor.isFinite,
                  piece.factor >= 0,
                  piece.factor <= 1.0 + factorTolerance,
                  piece.fullMonthBaseGross.isFinite,
                  piece.fullMonthBaseGross >= 0,
                  piece.proratedBaseGross.isFinite,
                  piece.proratedBaseGross >= 0 else {
                return blocked(upstreamWarnings + [amountWarning])
            }

            let expectedPiece = piece.fullMonthBaseGross * piece.factor
            guard expectedPiece.isFinite,
                  abs(expectedPiece - piece.proratedBaseGross) <= currencyTolerance else {
                return blocked(upstreamWarnings + [baseWarning])
            }

            recomputedBase += piece.proratedBaseGross
            factorTotal += piece.factor
            let scheduledAddition = scheduledTotal.addingReportingOverflow(
                Int64(piece.scheduledMinutes)
            )
            guard !scheduledAddition.overflow else {
                return blocked(upstreamWarnings + [overflowWarning])
            }
            scheduledTotal = scheduledAddition.partialValue

            guard recomputedBase.isFinite, factorTotal.isFinite else {
                return blocked(upstreamWarnings + [overflowWarning])
            }
        }
        guard scheduledTotal > 0,
              abs(factorTotal - 1.0) <= factorTolerance else {
            return blocked(upstreamWarnings + [baseWarning])
        }
        for piece in base.pieces {
            let expectedFactor = Double(piece.scheduledMinutes) / Double(scheduledTotal)
            guard expectedFactor.isFinite,
                  abs(expectedFactor - piece.factor) <= factorTolerance else {
                return blocked(upstreamWarnings + [baseWarning])
            }
        }
        guard abs(recomputedBase - baseAmount) <= currencyTolerance else {
            return blocked(upstreamWarnings + [baseWarning])
        }

        let variableKeys = variables.map {
            key($0.versionId, start: $0.startEpochDay, end: $0.endEpochDay)
        }
        let variableWarnings = variables.flatMap(\.warnings)
        guard variableKeys.allSatisfy({ !$0.versionId.isEmpty }),
              Set(variableKeys).count == variableKeys.count,
              variables.allSatisfy({
                  $0.companyId.trimmingCharacters(in: .whitespacesAndNewlines) == companyId
                      && $0.endEpochDay >= $0.startEpochDay
              }),
              Set(variableKeys) == Set(baseKeys) else {
            return blocked(upstreamWarnings + variableWarnings + [coverageWarning])
        }

        guard variables.allSatisfy(\.reliable) else {
            return blocked(
                upstreamWarnings
                    + variableWarnings
                    + [variableReliabilityWarning]
            )
        }

        var variableTotal = 0.0
        for piece in variables {
            guard piece.variableGross.isFinite,
                  piece.variableGross >= 0 else {
                return blocked(
                    upstreamWarnings
                        + variableWarnings
                        + [amountWarning]
                )
            }
            variableTotal += piece.variableGross
            guard variableTotal.isFinite else {
                return blocked(
                    upstreamWarnings
                        + variableWarnings
                        + [overflowWarning]
                )
            }
        }

        let workedGross = baseAmount + variableTotal
        guard workedGross.isFinite else {
            return blocked(
                upstreamWarnings
                    + variableWarnings
                    + [overflowWarning]
            )
        }

        return SalarySegmentedWorkedGrossAssemblyResultV2(
            baseGross: baseAmount,
            variableGross: variableTotal,
            workedGross: workedGross,
            reliable: true,
            warnings: unique(upstreamWarnings + variableWarnings)
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
    private static let factorTolerance = 0.000_000_001
}
