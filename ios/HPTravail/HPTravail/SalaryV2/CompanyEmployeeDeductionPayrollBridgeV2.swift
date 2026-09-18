import Foundation

enum CompanyEmployeeDeductionPayrollBridgeV2 {
    struct Result: Equatable {
        let deductions: [DeductionV2]
        let confirmedEmployeeDeductionsComplete: Bool
        let warnings: [String]
        let traces: [String]
    }

    private static let directKinds: [CompanyEmployeeDeductionResolverV2.Kind] = [
        .mutualEmployee,
        .providentEmployee,
        .transportEmployee,
        .employeeProvidentNonDeductible
    ]

    static func resolve(
        snapshot: CompanyEmployeeDeductionResolverV2.Snapshot,
        period: CompanyEmployeeDeductionResolverV2.YearMonth
    ) -> Result {
        var deductions: [DeductionV2] = []
        var warnings: [String] = []
        var traces: [String] = []
        var complete = true

        for kind in directKinds {
            let value = snapshot[kind]
            if !value.reliable {
                complete = false
                warnings.append(contentsOf: value.warnings.isEmpty
                    ? ["\(kind.label) : valeur non fiable pour \(period) ; aucune retenue n'est inventée."]
                    : value.warnings)
                continue
            }

            guard let amount = value.amount else {
                complete = false
                warnings.append("\(kind.label) : aucun montant daté confirmé pour \(period) ; aucune retenue n'est inventée.")
                continue
            }

            guard amount.isFinite, amount >= 0 else {
                complete = false
                warnings.append("\(kind.label) : montant invalide pour \(period) ; aucune retenue n'est inventée.")
                continue
            }

            if amount > 0 {
                deductions.append(
                    DeductionV2(
                        id: "company_employee_\(kind.rawValue.lowercased())",
                        label: kind.label,
                        amount: amount,
                        recurring: true
                    )
                )
            }

            var trace = "\(kind.label) : \(amount) € confirmés pour \(period)"
            if let source = value.source?.trimmingCharacters(in: .whitespacesAndNewlines), !source.isEmpty {
                trace += " — source \(source)"
            }
            traces.append(trace)
        }

        return Result(
            deductions: deductions,
            confirmedEmployeeDeductionsComplete: complete,
            warnings: unique(warnings),
            traces: unique(traces)
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
