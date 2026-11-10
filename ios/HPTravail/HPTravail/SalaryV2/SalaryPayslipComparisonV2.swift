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

/// Uses the same decimal HALF_UP and representability boundary as canonical payroll.
/// This is a technical bound, not a legal limit on a payslip amount.
enum SalaryPayslipAmountV2 {
    static func cents(_ value: Double) -> Int64? {
        guard value >= 0 else { return nil }
        return PayrollMoneyV2.cents(value)
    }

    static func valid(_ value: Double?) -> Bool {
        value.map { cents($0) != nil } ?? true
    }

    /// Import must preserve the exact proposed cents when crossing Decimal -> Double.
    static func importedAmount(_ value: Decimal) -> Double? {
        guard !value.isNaN, value >= 0 else { return nil }
        // Parse the canonical decimal spelling: NSDecimalNumber.doubleValue can introduce
        // a different binary neighbour at half-cent values such as 1.005.
        guard let amount = Double(NSDecimalNumber(decimal: value).stringValue),
              let cents = cents(amount) else { return nil }
        var original = value
        var rounded = Decimal()
        NSDecimalRound(&rounded, &original, 2, .plain)
        guard rounded == Decimal(cents) / Decimal(100) else { return nil }
        return amount
    }

    static func parse(_ raw: String) -> (valid: Bool, value: Double?) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return (true, nil) }
        let normalized = trimmed.replacingOccurrences(of: ",", with: ".")
        guard normalized.count <= 256, let value = Double(normalized), cents(value) != nil,
              let decimal = Decimal(string: normalized, locale: Locale(identifier: "en_US_POSIX")),
              importedAmount(decimal) == value else { return (false, nil) }
        return (true, value)
    }
}

struct SalaryPayslipObservedValuesV2: Equatable {
    let socialGross: Double?
    let netBeforeIncomeTax: Double?
    let netTaxable: Double?
    let incomeTax: Double?
    let netAfterIncomeTax: Double?

    var valid: Bool {
        [socialGross, netBeforeIncomeTax, netTaxable, incomeTax, netAfterIncomeTax]
            .allSatisfy(SalaryPayslipAmountV2.valid)
    }

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
        guard let value, SalaryPayslipAmountV2.cents(value) != nil else { return }
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
        guard snapshot.sourceReady,
              [snapshot.socialGross, snapshot.netBeforeIncomeTax, snapshot.netTaxable,
               snapshot.incomeTax, snapshot.netAfterIncomeTax].allSatisfy(SalaryPayslipAmountV2.valid)
        else { return nil }

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
        guard let toleranceCents = SalaryPayslipAmountV2.cents(tolerance), observed.valid,
              let expected = expectedValues(from: snapshot) else {
            return nil
        }

        let observedValues = observed.values
        let fields = SalaryPayslipFieldV2.allCases.filter {
            expected[$0] != nil && observedValues[$0] != nil
        }
        guard !fields.isEmpty else { return nil }

        var discrepancies: [SalaryPayslipDiscrepancyV2] = []
        for field in fields {
            guard let expectedValue = expected[field], let observedValue = observedValues[field],
                  let expectedCents = SalaryPayslipAmountV2.cents(expectedValue),
                  let observedCents = SalaryPayslipAmountV2.cents(observedValue) else { return nil }
            // Nonnegative cents are bounded by PayrollMoneyV2.maximumCents, so this
            // ordered subtraction cannot overflow and needs no potentially trapping abs.
            let difference = max(expectedCents, observedCents) - min(expectedCents, observedCents)
            if difference > toleranceCents {
                discrepancies.append(SalaryPayslipDiscrepancyV2(
                    field: field, expected: expectedValue, observed: observedValue
                ))
            }
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
        guard let value, SalaryPayslipAmountV2.cents(value) != nil else { return }
        result[field] = value
    }
}
