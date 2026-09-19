import Foundation

/// État d'affichage SalaireV2 partagé par l'interface iOS.
///
/// Cette couche ne calcule aucun montant métier. Elle ne fait qu'exposer les
/// références déjà admises par le contrat canonique SalaryReferenceContractV2.
/// Une source amont absente ne produit donc jamais de zéro ou d'estimation locale.
struct SalaryWorkspaceSnapshotV2: Equatable {
    let period: YearMonthV2
    let sourceReady: Bool
    let socialGross: Double?
    let netBeforeIncomeTax: Double?
    let netTaxable: Double?
    let incomeTax: Double?
    let netAfterIncomeTax: Double?
    let warnings: [String]

    var hasReliableGross: Bool { socialGross != nil }
    var hasReliableNet: Bool { netBeforeIncomeTax != nil }
}

enum SalaryWorkspaceResolverV2 {
    static let upstreamUnavailableWarning =
        "Salaire V2 iOS : les sources canoniques amont ne sont pas encore raccordées pour ce mois ; aucun montant n'est inventé."

    static func resolve(
        period: YearMonthV2,
        reference: SalaryReferenceContractV2?,
        incomeTaxRate: CompanyIncomeTaxRateResolverV2.Snapshot? = nil
    ) -> SalaryWorkspaceSnapshotV2 {
        guard let reference else {
            return SalaryWorkspaceSnapshotV2(
                period: period,
                sourceReady: false,
                socialGross: nil,
                netBeforeIncomeTax: nil,
                netTaxable: nil,
                incomeTax: nil,
                netAfterIncomeTax: nil,
                warnings: [upstreamUnavailableWarning]
            )
        }

        let socialGross = SalaryReferenceContractV2.socialGross(reference)
        let netBeforeIncomeTax = SalaryReferenceContractV2.beforeIncomeTax(reference)
        let netTaxable = SalaryReferenceContractV2.taxable(reference)
        var warnings = reference.warnings
        var incomeTax: Double?
        var netAfterIncomeTax: Double?
        if let beforeTax = netBeforeIncomeTax,
           let taxable = netTaxable,
           taxable.isFinite,
           let rateSnapshot = incomeTaxRate,
           rateSnapshot.reliable,
           let rate = rateSnapshot.rate,
           rate.isFinite,
           (0...1).contains(rate) {
            let calculatedTax = ((taxable * rate) * 100).rounded() / 100
            if calculatedTax.isFinite {
                incomeTax = calculatedTax
                netAfterIncomeTax = max(0, beforeTax - calculatedTax)
            }
        }
        if netTaxable != nil && netAfterIncomeTax == nil {
            warnings.append("PAS : taux personnel daté et confirmé indisponible ou invalide ; net après impôt masqué.")
        }
        warnings.append(contentsOf: incomeTaxRate?.warnings ?? [])

        if socialGross == nil && !warnings.contains(where: { $0.hasPrefix("Brut social :") }) {
            warnings.append(
                "Brut social : référence non certifiable pour ce mois ; affichage bloqué."
            )
        }
        if netBeforeIncomeTax == nil && socialGross != nil && warnings.isEmpty {
            warnings.append(
                "Net avant impôt : résultat incomplet ; affichage de référence bloqué."
            )
        }

        return SalaryWorkspaceSnapshotV2(
            period: period,
            sourceReady: true,
            socialGross: socialGross,
            netBeforeIncomeTax: netBeforeIncomeTax,
            netTaxable: netTaxable,
            incomeTax: incomeTax,
            netAfterIncomeTax: netAfterIncomeTax,
            warnings: unique(warnings)
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
