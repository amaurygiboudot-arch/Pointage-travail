import Foundation

extension SalaryReferenceContractV2 {
    /// Assemble la référence sociale uniquement depuis les deux moteurs canoniques :
    /// PayrollEngineV2 pour le brut, puis EmployeeNetProjectionV2 pour le net salarié.
    ///
    /// Le bridge vérifie que la projection nette porte exactement sur le brut calculé et sur
    /// les mêmes avantages en nature. Une incohérence de chaîne reste fail-closed : les montants
    /// connus sont conservés pour audit, mais ils ne redeviennent jamais une référence fiable.
    static func buildFromPayroll(
        payroll: PayrollResultV2,
        benefits: CompanyBenefitInKindContractV2.Snapshot,
        projection: EmployeeNetProjectionV2.Result,
        additionalWarnings: [String] = []
    ) -> SalaryReferenceContractV2 {
        let tolerance = 0.005
        let cashGrossMatches = payroll.grossEstimate.isFinite
            && projection.cashGross.isFinite
            && abs(payroll.grossEstimate - projection.cashGross) <= tolerance
        let projectedBenefits = projection.contributionGross - projection.cashGross
        let benefitsMatch = projectedBenefits.isFinite
            && benefits.totalGross.isFinite
            && abs(projectedBenefits - benefits.totalGross) <= tolerance

        var warnings = additionalWarnings
        if !cashGrossMatches {
            warnings.append(
                "Chaîne Salaire V2 : le brut transmis au moteur net ne correspond pas au brut canonique PayrollEngineV2 ; référence bloquée."
            )
        }
        if !benefitsMatch {
            warnings.append(
                "Chaîne Salaire V2 : les avantages en nature du moteur net ne correspondent pas au snapshot canonique du mois ; référence bloquée."
            )
        }
        if !projection.netBeforeIncomeTaxComplete || projection.netBeforeIncomeTax == nil {
            warnings.append(
                "Net avant impôt : projection canonique incomplète ; les sous-totaux connus restent visibles uniquement pour audit."
            )
        }

        let base = build(
            cashGross: payroll.grossEstimate,
            benefits: benefits,
            netBeforeIncomeTax: projection.netBeforeIncomeTax ?? projection.knownNetBeforeIncomeTax,
            netTaxable: projection.netTaxableComplete ? projection.netTaxable : nil,
            additionalWarnings: uniqueReferenceWarnings(warnings)
        )

        let grossChainReliable = payroll.grossReliable
            && projection.grossReliable
            && cashGrossMatches
            && benefitsMatch

        guard grossChainReliable else {
            var blockedWarnings = [
                "Brut salarial : la chaîne canonique PayrollEngineV2 → EmployeeNetProjectionV2 n'est pas suffisamment confirmée ; la référence sociale reste à confirmer."
            ] + base.warnings
            blockedWarnings = uniqueReferenceWarnings(blockedWarnings)
            return SalaryReferenceContractV2(
                gross: base.gross,
                grossReliable: false,
                netBeforeIncomeTax: base.netBeforeIncomeTax,
                netTaxable: base.netTaxable,
                complete: false,
                warnings: blockedWarnings,
                benefitsInKindDeduction: base.benefitsInKindDeduction
            )
        }

        return base
    }

    /// Compatibilité transitoire pour les anciens tests/appelants qui disposent déjà de valeurs nettes.
    /// Le chemin de production iOS ne doit pas utiliser cette surcharge : il doit passer par
    /// EmployeeNetProjectionV2 afin qu'une seule chaîne canonique porte la fiabilité.
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
        warnings = uniqueReferenceWarnings(warnings)

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

    private static func uniqueReferenceWarnings(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
