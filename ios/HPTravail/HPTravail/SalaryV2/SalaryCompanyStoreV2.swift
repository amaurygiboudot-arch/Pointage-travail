import Foundation

struct SalaryCompanyV2: Equatable, Identifiable {
    let id: String
    let name: String
    let siret: String
    let address: String
    let conventionName: String
    let idcc: String

    init(
        id: String,
        name: String,
        siret: String,
        address: String = "",
        conventionName: String = "",
        idcc: String = ""
    ) {
        self.id = id
        self.name = name
        self.siret = siret
        self.address = address
        self.conventionName = conventionName
        self.idcc = idcc
    }
}

struct SalaryCompanyReadResultV2: Equatable {
    let companies: [SalaryCompanyV2]
    let reliable: Bool
    let repairedFromBackup: Bool
    let warnings: [String]
}

/// Store iOS des entreprises SalaireV2.
///
/// Il reprend les invariants Android utiles au runtime : identifiant stable,
/// lecture fail-closed, copie locale de secours et vérification après écriture.
/// Il n'existe pas de migration V1 iOS à rejouer : une absence réelle de store
/// sur iOS vaut donc liste vide fiable, tandis qu'un store présent mais illisible
/// reste explicitement non fiable.
enum SalaryCompanyStoreV2 {
    static let storageWarning =
        "Entreprises Salaire V2 : stockage local incohérent ; aucune entreprise ni absence d'entreprise ne peut être déduite de ce stockage."
    static let repairedWarning =
        "Entreprises Salaire V2 : stockage principal restauré depuis la dernière copie locale valide."

    private static let primaryKey = "salary_companies_v2.companies"
    private static let backupKey = "salary_companies_v2.companies_last_known_good"
    private static let corruptBackupKey = "salary_companies_v2.companies_corrupt_backup"
    private static let lock = NSLock()

    static func readConfirmed(defaults: UserDefaults = .standard) -> SalaryCompanyReadResultV2 {
        let primaryObject = defaults.object(forKey: primaryKey)
        let backupObject = defaults.object(forKey: backupKey)

        if primaryObject == nil {
            if let backupObject {
                guard let backupRaw = backupObject as? String else {
                    return unreliableBackupResult()
                }
                let backup = decodeCompanies(backupRaw)
                guard backup.reliable else { return unreliableBackupResult(companies: backup.companies) }
                guard writeVerified(backupRaw, forKey: primaryKey, defaults: defaults) else {
                    return SalaryCompanyReadResultV2(
                        companies: backup.companies,
                        reliable: false,
                        repairedFromBackup: false,
                        warnings: [storageWarning, "La copie valide a été trouvée mais sa restauration a échoué."]
                    )
                }
                defaults.removeObject(forKey: corruptBackupKey)
                return SalaryCompanyReadResultV2(
                    companies: backup.companies,
                    reliable: true,
                    repairedFromBackup: true,
                    warnings: [repairedWarning]
                )
            }
            return SalaryCompanyReadResultV2(
                companies: [],
                reliable: true,
                repairedFromBackup: false,
                warnings: []
            )
        }

        let primaryRaw = primaryObject as? String
        let primary = primaryRaw.map(decodeCompanies)
            ?? SalaryCompanyReadResultV2(
                companies: [],
                reliable: false,
                repairedFromBackup: false,
                warnings: [storageWarning]
            )

        if primary.reliable, let primaryRaw {
            if defaults.string(forKey: backupKey) != primaryRaw {
                _ = writeVerified(primaryRaw, forKey: backupKey, defaults: defaults)
            }
            defaults.removeObject(forKey: corruptBackupKey)
            return primary
        }

        guard let backupRaw = backupObject as? String else { return primary }
        let backup = decodeCompanies(backupRaw)
        guard backup.reliable else { return primary }
        guard writeVerified(backupRaw, forKey: primaryKey, defaults: defaults) else {
            return SalaryCompanyReadResultV2(
                companies: backup.companies,
                reliable: false,
                repairedFromBackup: false,
                warnings: [storageWarning, "La copie valide a été trouvée mais sa restauration a échoué."]
            )
        }
        defaults.removeObject(forKey: corruptBackupKey)
        return SalaryCompanyReadResultV2(
            companies: backup.companies,
            reliable: true,
            repairedFromBackup: true,
            warnings: [repairedWarning]
        )
    }

    static func confirmedCompany(
        _ stored: SalaryCompanyReadResultV2,
        companyId: String
    ) -> SalaryCompanyV2? {
        let id = companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard stored.reliable, !id.isEmpty else { return nil }
        return stored.companies.first { $0.id == id }
    }

    static func createOrUpdate(
        _ company: SalaryCompanyV2,
        defaults: UserDefaults = .standard
    ) -> Bool {
        mutate(company, allowInsert: true, defaults: defaults)
    }

    static func upsertExisting(
        _ company: SalaryCompanyV2,
        defaults: UserDefaults = .standard
    ) -> Bool {
        mutate(company, allowInsert: false, defaults: defaults)
    }

    static func remove(companyId: String, defaults: UserDefaults = .standard) -> Bool {
        let id = companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return false }
        lock.lock()
        defer { lock.unlock() }

        let stored = readConfirmed(defaults: defaults)
        guard stored.reliable else { return false }
        guard stored.companies.contains(where: { $0.id == id }) else { return true }
        guard save(stored.companies.filter { $0.id != id }, defaults: defaults) else { return false }
        let reloaded = readConfirmed(defaults: defaults)
        return reloaded.reliable && !reloaded.companies.contains(where: { $0.id == id })
    }

    static func decodeCompanies(_ raw: String) -> SalaryCompanyReadResultV2 {
        guard let data = raw.data(using: .utf8) else { return unreliableResult() }
        do {
            guard let array = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
                return unreliableResult()
            }
            var companies: [SalaryCompanyV2] = []
            var malformed = false
            for object in array {
                guard let company = decodeCompany(object) else {
                    malformed = true
                    continue
                }
                companies.append(company)
            }
            if Set(companies.map(\.id)).count != companies.count { malformed = true }
            return SalaryCompanyReadResultV2(
                companies: companies,
                reliable: !malformed,
                repairedFromBackup: false,
                warnings: malformed ? [storageWarning] : []
            )
        } catch {
            return unreliableResult()
        }
    }

    private static func mutate(
        _ company: SalaryCompanyV2,
        allowInsert: Bool,
        defaults: UserDefaults
    ) -> Bool {
        lock.lock()
        defer { lock.unlock() }

        let stored = readConfirmed(defaults: defaults)
        guard let companies = companiesAfterMutation(stored, company: company, allowInsert: allowInsert) else {
            return false
        }
        guard save(companies, defaults: defaults) else { return false }
        return confirmedCompany(readConfirmed(defaults: defaults), companyId: company.id) != nil
    }

    private static func companiesAfterMutation(
        _ stored: SalaryCompanyReadResultV2,
        company: SalaryCompanyV2,
        allowInsert: Bool
    ) -> [SalaryCompanyV2]? {
        guard stored.reliable else { return nil }
        let id = company.id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return nil }

        let normalizedSiret = company.siret.filter(\.isNumber)
        if !company.siret.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           normalizedSiret.count != 14 {
            return nil
        }
        if normalizedSiret.count == 14,
           stored.companies.contains(where: {
               $0.id != company.id && $0.siret.filter(\.isNumber) == normalizedSiret
           }) {
            return nil
        }

        var all = stored.companies
        if let index = all.firstIndex(where: { $0.id == company.id }) {
            all[index] = company
        } else if allowInsert {
            all.append(company)
        } else {
            return nil
        }
        return all
    }

    private static func save(_ companies: [SalaryCompanyV2], defaults: UserDefaults) -> Bool {
        guard companies.allSatisfy({ !$0.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }),
              Set(companies.map(\.id)).count == companies.count,
              let raw = encodeCompanies(companies) else {
            return false
        }
        let verification = decodeCompanies(raw)
        guard verification.reliable, verification.companies.count == companies.count else { return false }
        guard writeVerified(raw, forKey: primaryKey, defaults: defaults),
              writeVerified(raw, forKey: backupKey, defaults: defaults) else {
            return false
        }
        defaults.removeObject(forKey: corruptBackupKey)
        return true
    }

    private static func encodeCompanies(_ companies: [SalaryCompanyV2]) -> String? {
        let array: [[String: Any]] = companies.map {
            [
                "id": $0.id,
                "name": $0.name,
                "siret": $0.siret,
                "address": $0.address,
                "conventionName": $0.conventionName,
                "idcc": $0.idcc
            ]
        }
        guard JSONSerialization.isValidJSONObject(array),
              let data = try? JSONSerialization.data(withJSONObject: array),
              let raw = String(data: data, encoding: .utf8) else { return nil }
        return raw
    }

    private static func decodeCompany(_ object: [String: Any]) -> SalaryCompanyV2? {
        guard let idRaw = object["id"] as? String else { return nil }
        let id = idRaw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return nil }

        func optional(_ key: String) -> String? {
            guard let value = object[key] else { return "" }
            if value is NSNull { return "" }
            return value as? String
        }

        guard let name = optional("name"),
              let siret = optional("siret"),
              let address = optional("address"),
              let conventionName = optional("conventionName"),
              let idcc = optional("idcc") else { return nil }

        return SalaryCompanyV2(
            id: id,
            name: name,
            siret: siret,
            address: address,
            conventionName: conventionName,
            idcc: idcc
        )
    }

    private static func writeVerified(_ value: String, forKey key: String, defaults: UserDefaults) -> Bool {
        defaults.set(value, forKey: key)
        return defaults.string(forKey: key) == value
    }

    private static func unreliableResult(companies: [SalaryCompanyV2] = []) -> SalaryCompanyReadResultV2 {
        SalaryCompanyReadResultV2(
            companies: companies,
            reliable: false,
            repairedFromBackup: false,
            warnings: [storageWarning]
        )
    }

    private static func unreliableBackupResult(companies: [SalaryCompanyV2] = []) -> SalaryCompanyReadResultV2 {
        SalaryCompanyReadResultV2(
            companies: companies,
            reliable: false,
            repairedFromBackup: false,
            warnings: [
                storageWarning,
                "La copie locale de secours existe mais elle est illisible ; aucune absence d'entreprise ne peut être confirmée."
            ]
        )
    }
}
