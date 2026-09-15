import Foundation

extension SalaryReferenceContractV2 {
    /// Propage explicitement la fiabilité du brut salarial amont vers le contrat de référence social.
    /// Un brut calculé avec un barème provisoire ne peut jamais redevenir une référence fiable
    /// uniquement parce que les avantages en nature sont, eux, confirmés.
    static func buildFromPayroll(
        payroll: PayrollResultV2,
        benefits: CompanyBenefitInKindContractV2.Snapshot,
        netBeforeIncomeTax: Double,
        netTaxable: Double?,
        additionalWarnings: [String] = []
    ) -> SalaryReferenceContractV2 {
        let base = build(
            cashGross: payroll.grossEstimate,
            benefits: benefits,
            netBeforeIncomeTax: netBeforeIncomeTax,
            netTaxable: netTaxable,
            additionalWarnings: additionalWarnings
        )
        guard !payroll.grossReliable else { return base }

        var warnings = [
            "Brut salarial : règle provisoire ou donnée amont non suffisamment confirmée ; la référence sociale reste à confirmer."
        ] + base.warnings
        var seen = Set<String>()
        warnings = warnings.filter { seen.insert($0).inserted }

        return SalaryReferenceContractV2(
            gross: base.gross,
            grossReliable: false,
            netBeforeIncomeTax: base.netBeforeIncomeTax,
            netTaxable: base.netTaxable,
            complete: false,
            warnings: warnings,
            benefitsInKindDeduction: base.benefitsInKindDeduction
        )
    }
}
