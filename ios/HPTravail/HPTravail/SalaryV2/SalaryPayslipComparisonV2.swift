import Foundation

enum SalaryPayslipFieldV2: String, CaseIterable, Hashable {
    case socialGross
    case netBeforeIncomeTax
    case netTaxable
    case incomeTax
    case netAfterIncomeTax

    var label: String {
        switch self {
        case .socialGross: return "Brut social"
        case .netBeforeIncomeTax: return "Net avant impôt"
        case .netTaxable: return "Net imposable"
        case .incomeTax: return "Prélèvement à la source"
        case .netAfterIncomeTax: return "Net après impôt"
        }
    }
}

struct SalaryPayslipObservedValuesV2: Equatable {
    let socialGross: Double?
    let netBeforeIncomeTax: Double?
    let netTaxable: Double?
    let incomeTax: Double?
    let netAfterIncomeTax: Double?

    var values: [SalaryPayslipFieldV2: Double] {
        var result: [SalaryPayslipFieldV2: Double] = [:]
        append(.socialGross, socialGross, to: &result)
        append(.netBeforeIncomeTax, netBeforeIncomeTax, to: &result)
        append(.netTaxable, netTaxable, to: &result)
        append(.incomeTax, incomeTax, to: &result)
        append(.netAfterIncomeTax, netAfterIncomeTax, to: &result)
        return result
    }

    private func append(
        _ field: SalaryPayslipFieldV2,
        _ value: Double?,
        to result: inout [SalaryPayslipFieldV2: Double]
    ) {
        guard let value, value.isFinite, value >= 0 else { return }
        result[field] = value
    }
}

struct SalaryPayslipDiscrepancyV2: Equatable, Identifiable {
    let field: SalaryPayslipFieldV2
    let expected: Double
    let observed: Double

    var id: String {
        "\(field.rawValue):\(expected):\(observed)"
    }

    var difference: Double {
        observed - expected
    }

    var explanation: String {
        observed < expected
            ? "Écart négatif à vérifier"
            : "Écart positif à vérifier"
    }
}

struct SalaryPayslipComparisonResultV2: Equatable {
    let comparedFields: [SalaryPayslipFieldV2]
    let discrepancies: [SalaryPayslipDiscrepancyV2]
    let conforming: Bool
}

enum SalaryPayslipComparisonEngineV2 {
    static let defaultTolerance = 0.02

    static func expectedValues(
        from snapshot: SalaryWorkspaceSnapshotV2
    ) -> [SalaryPayslipFieldV2: Double]? {
        guard snapshot.sourceReady else { return nil }

        var result: [SalaryPayslipFieldV2: Double] = [:]
        append(.socialGross, snapshot.socialGross, to: &result)
        append(.netBeforeIncomeTax, snapshot.netBeforeIncomeTax, to: &result)
        append(.netTaxable, snapshot.netTaxable, to: &result)
        append(.incomeTax, snapshot.incomeTax, to: &result)
        append(.netAfterIncomeTax, snapshot.netAfterIncomeTax, to: &result)
        return result.isEmpty ? nil : result
    }

    static func compare(
        snapshot: SalaryWorkspaceSnapshotV2,
        observed: SalaryPayslipObservedValuesV2,
        tolerance: Double = defaultTolerance
    ) -> SalaryPayslipComparisonResultV2? {
        guard tolerance.isFinite, tolerance >= 0,
              let expected = expectedValues(from: snapshot) else {
            return nil
        }

        let observedValues = observed.values
        let fields = SalaryPayslipFieldV2.allCases.filter {
            expected[$0] != nil && observedValues[$0] != nil
        }
        guard !fields.isEmpty else { return nil }

        let toleranceCents = Int64((tolerance * 100).rounded())
        let discrepancies = fields.compactMap { field -> SalaryPayslipDiscrepancyV2? in
            guard let expectedValue = expected[field],
                  let observedValue = observedValues[field] else {
                return nil
            }
            let expectedCents = Int64((expectedValue * 100).rounded())
            let observedCents = Int64((observedValue * 100).rounded())
            guard abs(expectedCents - observedCents) > toleranceCents else {
                return nil
            }
            return SalaryPayslipDiscrepancyV2(
                field: field,
                expected: expectedValue,
                observed: observedValue
            )
        }

        return SalaryPayslipComparisonResultV2(
            comparedFields: fields,
            discrepancies: discrepancies,
            conforming: discrepancies.isEmpty
        )
    }

    private static func append(
        _ field: SalaryPayslipFieldV2,
        _ value: Double?,
        to result: inout [SalaryPayslipFieldV2: Double]
    ) {
        guard let value, value.isFinite, value >= 0 else { return }
        result[field] = value
    }
}
