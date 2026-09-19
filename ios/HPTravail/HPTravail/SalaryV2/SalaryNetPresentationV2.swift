import Foundation

/// Contrat de présentation commun au parcours Salaire V2.
///
/// Cette couche ne calcule aucune cotisation. Elle empêche seulement une interface de présenter
/// un sous-total ou un brut incertain comme un net salarié final.
enum SalaryNetPresentationStateV2: Equatable {
    case available
    case incomplete
    case unreliableGross
}

struct SalaryNetPresentationV2: Equatable {
    let state: SalaryNetPresentationStateV2
    let primaryLabel: String
    let primaryAmount: Double?
    let secondaryLabel: String?
    let secondaryAmount: Double?
    let detail: String

    static func make(
        grossReliable: Bool,
        netComplete: Bool,
        netBeforeIncomeTax: Double?,
        netAfterIncomeTax: Double?
    ) -> SalaryNetPresentationV2 {
        guard grossReliable else {
            return SalaryNetPresentationV2(
                state: .unreliableGross,
                primaryLabel: "Net indisponible",
                primaryAmount: nil,
                secondaryLabel: nil,
                secondaryAmount: nil,
                detail: "Brut à confirmer : aucun net salarié n'est affiché."
            )
        }

        guard netComplete, let beforeTax = netBeforeIncomeTax else {
            return SalaryNetPresentationV2(
                state: .incomplete,
                primaryLabel: "Net incomplet",
                primaryAmount: nil,
                secondaryLabel: nil,
                secondaryAmount: nil,
                detail: "Cotisations ou paramètres de paie à confirmer : aucun net salarié final n'est affiché."
            )
        }

        return SalaryNetPresentationV2(
            state: .available,
            primaryLabel: "Net avant impôt",
            primaryAmount: beforeTax,
            secondaryLabel: netAfterIncomeTax == nil ? nil : "Net après impôt",
            secondaryAmount: netAfterIncomeTax,
            detail: "Montants affichés uniquement à partir des données de paie confirmées."
        )
    }

    /// Le moteur brut seul ne démontre pas la couverture exhaustive des cotisations salariales.
    /// Son champ `netBeforeUnknownContributions` reste donc un sous-total interne et n'est jamais
    /// publié comme net final par cette couche.
    static func fromPayroll(_ payroll: PayrollResultV2) -> SalaryNetPresentationV2 {
        make(
            grossReliable: payroll.grossReliable,
            netComplete: false,
            netBeforeIncomeTax: nil,
            netAfterIncomeTax: nil
        )
    }

    /// Seule la projection canonique peut publier le net avant impôt. Le net après impôt reste
    /// volontairement absent tant que le prélèvement à la source daté n'est pas raccordé sur iOS.
    static func fromProjection(_ projection: EmployeeNetProjectionV2.Result) -> SalaryNetPresentationV2 {
        make(
            grossReliable: projection.grossReliable,
            netComplete: projection.netBeforeIncomeTaxComplete,
            netBeforeIncomeTax: projection.netBeforeIncomeTax,
            netAfterIncomeTax: nil
        )
    }
}
