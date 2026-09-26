import Foundation

/// Classification conventionnelle locale, strictement séparée par entreprise confirmée.
///
/// Une absence de fiche est une classification vide, jamais une valeur par défaut.
enum SalaryConventionClassificationStoreV2 {
    static let storageWarning =
        "Classification conventionnelle : stockage local incohérent ; aucune classification n'est supposée."

    private static let prefix = "salary_convention_classification_v2."

    static func load(
        companyId rawCompanyId: String,
        defaults: UserDefaults = .standard
    ) -> ConventionClassificationV2 {
        let companyId = rawCompanyId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !companyId.isEmpty,
              SalaryCompanyStoreV2.confirmedCompany(
                SalaryCompanyStoreV2.readConfirmed(defaults: defaults),
                companyId: companyId
              ) != nil else {
            return ConventionClassificationV2()
        }

        guard let data = defaults.data(forKey: key(companyId)) else {
            return ConventionClassificationV2()
        }
        guard let decoded = try? JSONDecoder().decode(ConventionClassificationV2.self, from: data),
              valid(decoded) else {
            return ConventionClassificationV2()
        }
        return decoded
    }

    @discardableResult
    static func save(
        companyId rawCompanyId: String,
        value: ConventionClassificationV2,
        defaults: UserDefaults = .standard
    ) -> Bool {
        let companyId = rawCompanyId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !companyId.isEmpty,
              SalaryCompanyStoreV2.confirmedCompany(
                SalaryCompanyStoreV2.readConfirmed(defaults: defaults),
                companyId: companyId
              ) != nil,
              valid(value),
              let data = try? JSONEncoder().encode(value) else {
            return false
        }

        defaults.set(data, forKey: key(companyId))
        guard let reloaded = defaults.data(forKey: key(companyId)),
              reloaded == data,
              let decoded = try? JSONDecoder().decode(ConventionClassificationV2.self, from: reloaded) else {
            return false
        }
        return decoded == value
    }

    private static func valid(_ value: ConventionClassificationV2) -> Bool {
        if let coefficient = value.coefficient, coefficient <= 0 { return false }
        return true
    }

    private static func key(_ companyId: String) -> String {
        prefix + companyId
    }
}
