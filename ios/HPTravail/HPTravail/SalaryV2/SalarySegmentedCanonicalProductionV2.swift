import Foundation

struct SalarySegmentedCanonicalProductionResultV2 {
    let output: SalarySegmentedCanonicalOutputV2
}

/// Coordinateur pur B20 -> cash -> net -> sortie canonique.
/// Aucun store n'est relu et aucune formule métier n'est recréée ici.
enum SalarySegmentedCanonicalProductionV2 {
    static func calculate(
        worked: SalarySegmentedWorkedGrossProductionResultV2,
        fixed: SalaryConfirmedCashGrossComponentsV2,
        netContext: SalarySegmentedNetProjectionContextV2
    ) -> SalarySegmentedCanonicalProductionResultV2 {
        let cash = SalarySegmentedCashGrossAssemblerV2.assemble(
            worked: worked,
            fixed: fixed
        )
        let net = SalarySegmentedCashGrossNetProjectionV2.project(
            cash: cash,
            context: netContext
        )
        let output = SalarySegmentedCanonicalOutputAssemblerV2.assemble(
            worked: worked,
            cash: cash,
            net: net
        )
        return .init(output: output)
    }
}
