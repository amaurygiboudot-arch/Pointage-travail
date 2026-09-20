import Foundation

/// Résolution pure de l'entreprise active dans l'onglet Salaire.
///
/// Règles de sécurité :
/// - 0 entreprise confirmée : aucune sélection ;
/// - 1 entreprise confirmée : sélection automatique non ambiguë ;
/// - plusieurs entreprises : aucune sélection implicite ; un identifiant courant n'est conservé
///   que s'il provient d'un choix utilisateur explicitement tracé et reste confirmé ;
/// - stockage non fiable ou identifiant inconnu : aucune sélection.
enum SalaryCompanySelectionV2 {
    static func reconcile(
        currentCompanyId: String?,
        selectionWasExplicit: Bool,
        companies stored: SalaryCompanyReadResultV2
    ) -> String? {
        guard stored.reliable else { return nil }

        if stored.companies.count == 1 {
            return normalized(stored.companies[0].id)
        }

        guard stored.companies.count > 1,
              selectionWasExplicit,
              let current = normalized(currentCompanyId),
              SalaryCompanyStoreV2.confirmedCompany(stored, companyId: current) != nil else {
            return nil
        }
        return current
    }

    static func explicitSelection(
        requestedCompanyId: String?,
        companies stored: SalaryCompanyReadResultV2
    ) -> String? {
        guard stored.reliable,
              let requested = normalized(requestedCompanyId),
              SalaryCompanyStoreV2.confirmedCompany(stored, companyId: requested) != nil else {
            return nil
        }
        return requested
    }

    private static func normalized(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }
}
