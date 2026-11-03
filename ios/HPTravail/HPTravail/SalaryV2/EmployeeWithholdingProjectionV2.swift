import Foundation

/// Propriétaire unique du PAS salarié, partagé par la projection nette et le workspace mensuel.
/// Les entrées sont les montants déjà admis par le contrat canonique ; une absence reste inconnue.
enum EmployeeWithholdingProjectionV2 {
    struct Result {
        let incomeTax: Double?
        let netAfterIncomeTax: Double?
        let warnings: [String]
    }

    static func calculate(
        netBeforeIncomeTax: Double?,
        netTaxable: Double?,
        incomeTaxRate: CompanyIncomeTaxRateResolverV2.Snapshot?
    ) -> Result {
        var warnings = incomeTaxRate?.warnings ?? []
        guard let beforeTax = netBeforeIncomeTax, let taxable = netTaxable else {
            return .init(incomeTax: nil, netAfterIncomeTax: nil, warnings: warnings)
        }
        guard beforeTax >= 0, taxable >= 0,
              let beforeCents = PayrollMoneyV2.cents(beforeTax),
              let taxableCents = PayrollMoneyV2.cents(taxable) else {
            warnings.append("PAS incomplet : montants avant impôt ou fiscaux invalides ou non représentables au centime.")
            return .init(incomeTax: nil, netAfterIncomeTax: nil, warnings: warnings)
        }
        guard let snapshot = incomeTaxRate, snapshot.reliable,
              let rate = snapshot.rate, rate.isFinite, (0...1).contains(rate) else {
            warnings.append("PAS : taux personnel daté et confirmé indisponible ou invalide ; aucun net après impôt n'est affiché.")
            return .init(incomeTax: nil, netAfterIncomeTax: nil, warnings: warnings)
        }
        guard let calculatedTax = PayrollMoneyV2.tax(baseCents: taxableCents, rate: rate) else {
            warnings.append("PAS incomplet : calcul monétaire non représentable au centime ou dépassement arithmétique.")
            return .init(incomeTax: nil, netAfterIncomeTax: nil, warnings: warnings)
        }
        guard calculatedTax <= beforeCents else {
            warnings.append("PAS supérieur aux espèces disponibles : traitement du prélèvement par l'employeur à confirmer ; PAS et net après impôt incomplets.")
            return .init(incomeTax: nil, netAfterIncomeTax: nil, warnings: warnings)
        }
        guard let afterCents = PayrollMoneyV2.sum([beforeCents, -calculatedTax]),
              let incomeTax = PayrollMoneyV2.amount(calculatedTax),
              let afterTax = PayrollMoneyV2.amount(afterCents) else {
            warnings.append("PAS incomplet : calcul monétaire non représentable au centime ou dépassement arithmétique.")
            return .init(incomeTax: nil, netAfterIncomeTax: nil, warnings: warnings)
        }
        return .init(incomeTax: incomeTax, netAfterIncomeTax: afterTax, warnings: warnings)
    }
}
