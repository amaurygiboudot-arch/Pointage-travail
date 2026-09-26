import Foundation

struct SalarySegmentedSalaryProductionResultV2 {
    let worked: SalarySegmentedWorkedGrossProductionResultV2
    let cash: SalarySegmentedCashGrossAssemblyResultV2
    let net: SalarySegmentedCashGrossNetProjectionResultV2

    var reliable: Bool {
        worked.reliable && cash.reliable && net.cashGrossReliable
    }

    var warnings: [String] {
        unique(worked.warnings + cash.warnings + net.warnings)
    }

    private func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}

/// Coordinateur pur B20 riche -> cash gross confirmé -> net canonique.
/// Aucun store, aucune formule UI, aucun fallback legacy.
enum SalarySegmentedSalaryProductionV2 {
    static func calculate(
        worked: SalarySegmentedWorkedGrossProductionResultV2,
        fixed: SalaryConfirmedCashGrossComponentsV2,
        context: SalarySegmentedNetProjectionContextV2
    ) -> SalarySegmentedSalaryProductionResultV2 {
        let cash = SalarySegmentedCashGrossAssemblerV2.assemble(worked: worked, fixed: fixed)
        let net = SalarySegmentedCashGrossNetProjectionV2.project(cash: cash, context: context)
        return .init(worked: worked, cash: cash, net: net)
    }
}
