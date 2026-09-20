import Foundation

struct ActiveSalaryCompanySelectionSnapshotV2: Equatable {
    let companies: [SalaryCompanyV2]
    let activeCompany: SalaryCompanyV2?
    let reliable: Bool
    let requiresExplicitSelection: Bool
    let warnings: [String]
}

/// Résout l'entreprise explicitement rattachée aux nouveaux pointages iOS.
///
/// - aucune entreprise confirmée : le pointage reste possible sans employeur ;
/// - une seule entreprise confirmée : elle est déterministe et peut être utilisée sans choix ambigu ;
/// - plusieurs entreprises : un choix persistant explicite est obligatoire ;
/// - stockage des entreprises non fiable : aucun employeur n'est déduit.
enum ActiveSalaryCompanySelectionV2 {
    static let storageKey = "salary_active_company_v2.id"

    static func resolve(defaults: UserDefaults = .standard) -> ActiveSalaryCompanySelectionSnapshotV2 {
        let stored = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)
        guard stored.reliable else {
            return ActiveSalaryCompanySelectionSnapshotV2(
                companies: [],
                activeCompany: nil,
                reliable: false,
                requiresExplicitSelection: false,
                warnings: stored.warnings.isEmpty
                    ? [SalaryCompanyStoreV2.storageWarning]
                    : stored.warnings
            )
        }

        let companies = stored.companies
        let selectedId = defaults.string(forKey: storageKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty

        if let selectedId,
           let selected = companies.first(where: { $0.id == selectedId }) {
            return ActiveSalaryCompanySelectionSnapshotV2(
                companies: companies,
                activeCompany: selected,
                reliable: true,
                requiresExplicitSelection: false,
                warnings: stored.warnings
            )
        }

        if companies.count == 1 {
            return ActiveSalaryCompanySelectionSnapshotV2(
                companies: companies,
                activeCompany: companies[0],
                reliable: true,
                requiresExplicitSelection: false,
                warnings: stored.warnings
            )
        }

        if companies.count > 1 {
            let staleWarning = selectedId == nil
                ? "Pointage : plusieurs entreprises sont confirmées ; choisissez l'entreprise active avant l'entrée."
                : "Pointage : l'entreprise active enregistrée n'existe plus ; choisissez une entreprise confirmée avant l'entrée."
            return ActiveSalaryCompanySelectionSnapshotV2(
                companies: companies,
                activeCompany: nil,
                reliable: true,
                requiresExplicitSelection: true,
                warnings: (stored.warnings + [staleWarning]).uniqued
            )
        }

        return ActiveSalaryCompanySelectionSnapshotV2(
            companies: [],
            activeCompany: nil,
            reliable: true,
            requiresExplicitSelection: false,
            warnings: stored.warnings
        )
    }

    @discardableResult
    static func select(companyId: String, defaults: UserDefaults = .standard) -> Bool {
        let id = companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return false }
        let stored = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)
        guard SalaryCompanyStoreV2.confirmedCompany(stored, companyId: id) != nil else {
            return false
        }
        defaults.set(id, forKey: storageKey)
        return defaults.string(forKey: storageKey) == id
            && resolve(defaults: defaults).activeCompany?.id == id
    }

    static func clear(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: storageKey)
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

private extension Array where Element == String {
    var uniqued: [String] {
        var seen = Set<String>()
        return filter { seen.insert($0).inserted }
    }
}
