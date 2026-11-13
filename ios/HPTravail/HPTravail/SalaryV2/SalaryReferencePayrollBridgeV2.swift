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
        additionalWarnings: [String] = [],
        expectedCompanyId: String? = nil,
        expectedPeriod: YearMonthV2? = nil
    ) -> SalaryReferenceContractV2 {
        let payrollCashCents = PayrollMoneyV2.cents(payroll.grossEstimate)
        let projectionCashCents = PayrollMoneyV2.cents(projection.cashGross)
        let cashGrossMatches = payroll.grossEstimate >= 0 && payrollCashCents != nil &&
            payrollCashCents == projectionCashCents
        let projectedBenefitsCents = PayrollMoneyV2.cents(projection.contributionGross).flatMap { social in
            projectionCashCents.flatMap { PayrollMoneyV2.sum([social, -$0]) }
        }
        let benefitsCents = PayrollMoneyV2.cents(benefits.totalGross)
        let benefitsMatch = benefits.totalGross >= 0 && projectedBenefitsCents != nil &&
            projectedBenefitsCents == benefitsCents
        let monetaryBenefits = CompanyBenefitInKindContractV2.Snapshot(
            applied: benefits.applied,
            totalGross: benefitsCents.flatMap(PayrollMoneyV2.amount) ?? benefits.totalGross,
            reliable: benefits.reliable,
            warnings: benefits.warnings
        )

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
            cashGross: projection.cashGross,
            benefits: monetaryBenefits,
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
            ] + base.warnings + projection.warnings
            blockedWarnings = uniqueReferenceWarnings(blockedWarnings)
            return SalaryReferenceContractV2(
                gross: base.gross,
                grossReliable: false,
                netBeforeIncomeTax: base.netBeforeIncomeTax,
                netTaxable: base.netTaxable,
                complete: false,
                warnings: blockedWarnings,
                benefitsInKindDeduction: base.benefitsInKindDeduction,
                employerWarnings: ["Cotisations patronales : brut canonique non confirmé ; projection indisponible."]
            )
        }

        let employer = projection.employerContributions.flatMap { value -> EmployerContributionProjectionV2.Result? in
            guard let period = expectedPeriod,
                  value.matches(companyId: expectedCompanyId, year: period.year, month: period.month, gross: base.gross) else { return nil }
            return value
        }

        // Les diagnostics de projection incluent aussi des réserves informatives de catalogue
        // et des blocages limités au fiscal/PAS. Ils restent visibles sans invalider un net
        // avant impôt dont la complétude structurelle a déjà été démontrée ci-dessus.
        return SalaryReferenceContractV2(
            gross: base.gross,
            grossReliable: base.grossReliable,
            netBeforeIncomeTax: base.netBeforeIncomeTax,
            netTaxable: base.netTaxable,
            complete: base.complete,
            warnings: uniqueReferenceWarnings(base.warnings + projection.warnings),
            benefitsInKindDeduction: base.benefitsInKindDeduction,
            employerContributions: employer,
            employerWarnings: employer?.warnings ?? ["Cotisations patronales : contexte entreprise/période/brut non concordant ou absent ; projection indisponible."],
            payslipDetails: expectedPeriod.flatMap { period in
                SalaryCalculatedPayslipDetailsV2.monthly(payroll: payroll, projection: projection, companyId: expectedCompanyId, period: period)
            },
            settlementBaseReliable: base.complete && employer != nil && projection.settlementBaseReliable
        )
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
