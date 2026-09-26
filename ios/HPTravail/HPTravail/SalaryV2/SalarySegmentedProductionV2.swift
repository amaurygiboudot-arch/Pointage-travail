import Foundation

struct SalarySegmentedProductionResultV2 {
    let worked: SalarySegmentedWorkedGrossProductionResultV2
    let fixed: SalaryConfirmedCashGrossComponentsV2
    let cash: SalarySegmentedCashGrossAssemblyResultV2
    let net: SalarySegmentedCashGrossNetProjectionResultV2

    var cashGrossReliable: Bool {
        worked.reliable && fixed.exhaustive && cash.reliable && net.cashGrossReliable
    }

    var netComplete: Bool {
        cashGrossReliable && net.netBeforeIncomeTaxComplete
    }

    var warnings: [String] {
        unique(worked.warnings + fixed.warnings + cash.warnings + net.warnings)
    }

    private func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}

/// Chaîne pure après B20 : composantes fixes confirmées -> cash gross -> net canonique.
/// Aucun store, écran, PDF ou fallback legacy n'est lu ici.
enum SalarySegmentedProductionV2 {
    static func calculate(
        worked: SalarySegmentedWorkedGrossProductionResultV2,
        fixedFacts: [SalaryConfirmedCashGrossFixedFactV2],
        fixedExhaustive: Bool,
        fixedSourceId: String,
        fixedWarnings: [String] = [],
        netContext: SalarySegmentedNetProjectionContextV2
    ) -> SalarySegmentedProductionResultV2 {
        let fixed = SalaryConfirmedCashGrossComponentsResolverV2.resolve(
            facts: fixedFacts,
            exhaustive: fixedExhaustive,
            sourceId: fixedSourceId,
            warnings: fixedWarnings
        )
        let cash = SalarySegmentedCashGrossAssemblerV2.assemble(
            worked: worked,
            fixed: fixed
        )
        let net = SalarySegmentedCashGrossNetProjectionV2.project(
            cash: cash,
            context: netContext
        )
        return .init(
            worked: worked,
            fixed: fixed,
            cash: cash,
            net: net
        )
    }
}
