import Foundation

struct SalarySegmentedNetProjectionContextV2 {
    let benefits: CompanyBenefitInKindContractV2.Snapshot
    let year: Int
    let ceiling: SocialSecurityCeilingV2.Snapshot
    let alsaceMoselleLocalRegime: Bool?
    let professionalStatus: String?
    let protectionCategory: ProtectionCategoryV2.Result
    let companyDeductions: CompanyEmployeeDeductionResolverV2.Snapshot
    let period: CompanyEmployeeDeductionResolverV2.YearMonth
    let incomeTaxRate: CompanyIncomeTaxRateResolverV2.Snapshot?
}

struct SalarySegmentedCashGrossNetProjectionResultV2 {
    let cash: SalarySegmentedCashGrossAssemblyResultV2
    let projection: EmployeeNetProjectionV2.Result?
    let cashGrossReliable: Bool
    let netBeforeIncomeTaxComplete: Bool
    let warnings: [String]
}

/// Pont unique cashGross segmenté -> moteur net canonique iOS.
/// Tous les éléments sociaux restent fournis explicitement par leurs résolveurs propriétaires.
enum SalarySegmentedCashGrossNetProjectionV2 {
    static let cashWarning =
        "Projection nette segmentée : brut en espèces absent ou non fiable."

    static func project(
        cash: SalarySegmentedCashGrossAssemblyResultV2,
        context: SalarySegmentedNetProjectionContextV2
    ) -> SalarySegmentedCashGrossNetProjectionResultV2 {
        guard cash.reliable,
              let cashGross = cash.cashGross,
              cashGross.isFinite,
              cashGross >= 0 else {
            return .init(
                cash: cash,
                projection: nil,
                cashGrossReliable: false,
                netBeforeIncomeTaxComplete: false,
                warnings: unique(cash.warnings + [cashWarning])
            )
        }

        let projection = EmployeeNetProjectionV2.calculate(
            .init(
                cashGross: cashGross,
                upstreamGrossReliable: true,
                benefits: context.benefits,
                year: context.year,
                ceiling: context.ceiling,
                alsaceMoselleLocalRegime: context.alsaceMoselleLocalRegime,
                professionalStatus: context.professionalStatus,
                protectionCategory: context.protectionCategory,
                companyDeductions: context.companyDeductions,
                period: context.period,
                incomeTaxRate: context.incomeTaxRate
            )
        )

        return .init(
            cash: cash,
            projection: projection,
            cashGrossReliable: projection.grossReliable,
            netBeforeIncomeTaxComplete: projection.netBeforeIncomeTaxComplete,
            warnings: unique(cash.warnings + projection.warnings)
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
