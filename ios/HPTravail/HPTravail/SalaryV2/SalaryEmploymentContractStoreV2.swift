import Foundation

struct SalaryEmploymentContractReadResultV2: Equatable {
    let contracts: [ContractV2]
    let reliable: Bool
    let repairedFromBackup: Bool
    let warnings: [String]
}

/// Stockage canonique iOS du contrat salarié consommé par `PayrollEngineV2`.
///
/// Le store ne déduit jamais un type de contrat, une durée ou une rémunération à partir
/// du métier de l'utilisateur. Il accepte tous les types déjà supportés par le moteur V2
/// et refuse les combinaisons de champs que ce moteur considérerait comme incohérentes.
/// Une entreprise ne possède qu'un contrat courant confirmé dans ce store ; l'historique
/// contractuel devra être modélisé séparément avant d'autoriser plusieurs contrats simultanés.
enum SalaryEmploymentContractStoreV2 {
    static let storageWarning =
        "Contrat Salaire V2 : stockage local incohérent ; aucun contrat ni absence de contrat ne peut être déduit de ce stockage."
    static let repairedWarning =
        "Contrat Salaire V2 : stockage principal restauré depuis la dernière copie locale valide."

    private static let primaryKey = "salary_employment_contracts_v2.contracts"
    private static let backupKey = "salary_employment_contracts_v2.contracts_last_known_good"
    private static let lock = NSLock()

    static func readConfirmed(defaults: UserDefaults = .standard) -> SalaryEmploymentContractReadResultV2 {
        let primaryObject = defaults.object(forKey: primaryKey)
        let backupObject = defaults.object(forKey: backupKey)

        if primaryObject == nil {
            guard let backupObject else {
                return SalaryEmploymentContractReadResultV2(
                    contracts: [],
                    reliable: true,
                    repairedFromBackup: false,
                    warnings: []
                )
            }
            guard let backupRaw = backupObject as? String else { return unreliableResult() }
            let backup = decodeContracts(backupRaw)
            guard backup.reliable else { return unreliableResult(contracts: backup.contracts) }
            guard writeVerified(backupRaw, forKey: primaryKey, defaults: defaults) else {
                return SalaryEmploymentContractReadResultV2(
                    contracts: backup.contracts,
                    reliable: false,
                    repairedFromBackup: false,
                    warnings: [storageWarning, "La copie valide a été trouvée mais sa restauration a échoué."]
                )
            }
            return SalaryEmploymentContractReadResultV2(
                contracts: backup.contracts,
                reliable: true,
                repairedFromBackup: true,
                warnings: [repairedWarning]
            )
        }

        let primary = (primaryObject as? String).map(decodeContracts)
            ?? unreliableResult()
        if primary.reliable, let raw = primaryObject as? String {
            if defaults.string(forKey: backupKey) != raw {
                _ = writeVerified(raw, forKey: backupKey, defaults: defaults)
            }
            return primary
        }

        guard let backupRaw = backupObject as? String else { return primary }
        let backup = decodeContracts(backupRaw)
        guard backup.reliable else { return primary }
        guard writeVerified(backupRaw, forKey: primaryKey, defaults: defaults) else {
            return SalaryEmploymentContractReadResultV2(
                contracts: backup.contracts,
                reliable: false,
                repairedFromBackup: false,
                warnings: [storageWarning, "La copie valide a été trouvée mais sa restauration a échoué."]
            )
        }
        return SalaryEmploymentContractReadResultV2(
            contracts: backup.contracts,
            reliable: true,
            repairedFromBackup: true,
            warnings: [repairedWarning]
        )
    }

    static func confirmedContract(
        _ stored: SalaryEmploymentContractReadResultV2,
        companyId: String,
        defaults: UserDefaults = .standard
    ) -> ContractV2? {
        let employerId = companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard stored.reliable, !employerId.isEmpty else { return nil }
        let companies = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)
        guard SalaryCompanyStoreV2.confirmedCompany(companies, companyId: employerId) != nil else {
            return nil
        }
        return stored.contracts.first { $0.employerId == employerId }
    }

    @discardableResult
    static func save(_ contract: ContractV2, defaults: UserDefaults = .standard) -> Bool {
        guard validate(contract) else { return false }
        let companies = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)
        guard SalaryCompanyStoreV2.confirmedCompany(companies, companyId: contract.employerId) != nil else {
            return false
        }

        lock.lock()
        defer { lock.unlock() }

        let stored = readConfirmed(defaults: defaults)
        guard stored.reliable else { return false }
        guard !stored.contracts.contains(where: {
            $0.id == contract.id && $0.employerId != contract.employerId
        }) else { return false }

        var contracts = stored.contracts
        if let index = contracts.firstIndex(where: { $0.employerId == contract.employerId }) {
            contracts[index] = contract
        } else {
            contracts.append(contract)
        }
        guard persist(contracts, defaults: defaults) else { return false }
        return confirmedContract(readConfirmed(defaults: defaults), companyId: contract.employerId, defaults: defaults) == contract
    }

    @discardableResult
    static func remove(companyId: String, defaults: UserDefaults = .standard) -> Bool {
        let employerId = companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !employerId.isEmpty else { return false }

        lock.lock()
        defer { lock.unlock() }

        let stored = readConfirmed(defaults: defaults)
        guard stored.reliable else { return false }
        let remaining = stored.contracts.filter { $0.employerId != employerId }
        guard remaining.count != stored.contracts.count else { return true }
        guard persist(remaining, defaults: defaults) else { return false }
        let reloaded = readConfirmed(defaults: defaults)
        return reloaded.reliable && !reloaded.contracts.contains { $0.employerId == employerId }
    }

    static func decodeContracts(_ raw: String) -> SalaryEmploymentContractReadResultV2 {
        guard let data = raw.data(using: .utf8) else { return unreliableResult() }
        do {
            guard let array = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
                return unreliableResult()
            }
            var contracts: [ContractV2] = []
            var malformed = false
            for object in array {
                guard let contract = decodeContract(object), validate(contract) else {
                    malformed = true
                    continue
                }
                contracts.append(contract)
            }
            if Set(contracts.map(\.id)).count != contracts.count { malformed = true }
            if Set(contracts.map(\.employerId)).count != contracts.count { malformed = true }
            return SalaryEmploymentContractReadResultV2(
                contracts: contracts,
                reliable: !malformed,
                repairedFromBackup: false,
                warnings: malformed ? [storageWarning] : []
            )
        } catch {
            return unreliableResult()
        }
    }

    static func validate(_ contract: ContractV2) -> Bool {
        let id = contract.id.trimmingCharacters(in: .whitespacesAndNewlines)
        let employerId = contract.employerId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty, !employerId.isEmpty else { return false }
        if let cutoff = contract.payrollCutoffDay, !(1...31).contains(cutoff) { return false }

        switch contract.type {
        case .forfaitHours:
            guard contract.contractualWeeklyMinutes == nil,
                  contract.grossHourlyRate == nil,
                  let monthly = contract.monthlyGrossSalary,
                  monthly.isFinite,
                  monthly > 0,
                  contract.forfaitHoursPeriod != nil,
                  let hours = contract.forfaitHours,
                  hours.isFinite,
                  hours > 0,
                  contract.forfaitAnnualDays == nil else {
                return false
            }
            return true

        case .forfaitDays:
            guard contract.contractualWeeklyMinutes == nil,
                  contract.grossHourlyRate == nil,
                  let monthly = contract.monthlyGrossSalary,
                  monthly.isFinite,
                  monthly > 0,
                  contract.forfaitHoursPeriod == nil,
                  contract.forfaitHours == nil,
                  let days = contract.forfaitAnnualDays,
                  days.isFinite,
                  days > 0,
                  days <= 218 else {
                return false
            }
            return true

        case .fullTime, .partTime, .forfait, .other:
            guard let weekly = contract.contractualWeeklyMinutes,
                  weekly > 0,
                  let rate = contract.grossHourlyRate,
                  rate.isFinite,
                  rate > 0,
                  contract.forfaitHoursPeriod == nil,
                  contract.forfaitHours == nil,
                  contract.forfaitAnnualDays == nil,
                  contract.monthlyGrossSalary == nil else {
                return false
            }
            return true
        }
    }

    private static func persist(_ contracts: [ContractV2], defaults: UserDefaults) -> Bool {
        guard contracts.allSatisfy(validate),
              Set(contracts.map(\.id)).count == contracts.count,
              Set(contracts.map(\.employerId)).count == contracts.count,
              let raw = encodeContracts(contracts) else {
            return false
        }
        let verification = decodeContracts(raw)
        guard verification.reliable, verification.contracts == contracts else { return false }
        return writeVerified(raw, forKey: primaryKey, defaults: defaults)
            && writeVerified(raw, forKey: backupKey, defaults: defaults)
    }

    private static func encodeContracts(_ contracts: [ContractV2]) -> String? {
        let array = contracts.map { contract -> [String: Any] in
            [
                "id": contract.id,
                "employerId": contract.employerId,
                "type": contract.type.rawValue,
                "contractualWeeklyMinutes": contract.contractualWeeklyMinutes.map { NSNumber(value: $0) } ?? NSNull(),
                "grossHourlyRate": contract.grossHourlyRate.map { NSNumber(value: $0) } ?? NSNull(),
                "hireDateEpochDay": contract.hireDateEpochDay.map { NSNumber(value: $0) } ?? NSNull(),
                "payrollCutoffDay": contract.payrollCutoffDay.map { NSNumber(value: $0) } ?? NSNull(),
                "forfaitHoursPeriod": contract.forfaitHoursPeriod?.rawValue ?? NSNull(),
                "forfaitHours": contract.forfaitHours.map { NSNumber(value: $0) } ?? NSNull(),
                "forfaitAnnualDays": contract.forfaitAnnualDays.map { NSNumber(value: $0) } ?? NSNull(),
                "monthlyGrossSalary": contract.monthlyGrossSalary.map { NSNumber(value: $0) } ?? NSNull()
            ]
        }
        guard JSONSerialization.isValidJSONObject(array),
              let data = try? JSONSerialization.data(withJSONObject: array),
              let raw = String(data: data, encoding: .utf8) else {
            return nil
        }
        return raw
    }

    private static func decodeContract(_ object: [String: Any]) -> ContractV2? {
        guard let idRaw = object["id"] as? String,
              let employerRaw = object["employerId"] as? String,
              let typeRaw = object["type"] as? String,
              let type = ContractTypeV2(rawValue: typeRaw),
              let weekly = optionalInt(object["contractualWeeklyMinutes"]),
              let rate = optionalDouble(object["grossHourlyRate"]),
              let hire = optionalInt64(object["hireDateEpochDay"]),
              let cutoff = optionalInt(object["payrollCutoffDay"]),
              let period = optionalForfaitPeriod(object["forfaitHoursPeriod"]),
              let forfaitHours = optionalDouble(object["forfaitHours"]),
              let forfaitDays = optionalDouble(object["forfaitAnnualDays"]),
              let monthlyGross = optionalDouble(object["monthlyGrossSalary"]) else {
            return nil
        }

        let id = idRaw.trimmingCharacters(in: .whitespacesAndNewlines)
        let employerId = employerRaw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty, !employerId.isEmpty else { return nil }

        return ContractV2(
            id: id,
            employerId: employerId,
            type: type,
            contractualWeeklyMinutes: weekly,
            grossHourlyRate: rate,
            hireDateEpochDay: hire,
            payrollCutoffDay: cutoff,
            forfaitHoursPeriod: period,
            forfaitHours: forfaitHours,
            forfaitAnnualDays: forfaitDays,
            monthlyGrossSalary: monthlyGross
        )
    }

    private static func optionalInt(_ raw: Any?) -> Int?? {
        guard let raw else { return .some(nil) }
        if raw is NSNull { return .some(nil) }
        guard let number = raw as? NSNumber else { return nil }
        return .some(number.intValue)
    }

    private static func optionalInt64(_ raw: Any?) -> Int64?? {
        guard let raw else { return .some(nil) }
        if raw is NSNull { return .some(nil) }
        guard let number = raw as? NSNumber else { return nil }
        return .some(number.int64Value)
    }

    private static func optionalDouble(_ raw: Any?) -> Double?? {
        guard let raw else { return .some(nil) }
        if raw is NSNull { return .some(nil) }
        guard let number = raw as? NSNumber else { return nil }
        return .some(number.doubleValue)
    }

    private static func optionalForfaitPeriod(_ raw: Any?) -> ForfaitHoursPeriodV2?? {
        guard let raw else { return .some(nil) }
        if raw is NSNull { return .some(nil) }
        guard let value = raw as? String,
              let period = ForfaitHoursPeriodV2(rawValue: value) else {
            return nil
        }
        return .some(period)
    }

    private static func writeVerified(_ value: String, forKey key: String, defaults: UserDefaults) -> Bool {
        defaults.set(value, forKey: key)
        return defaults.string(forKey: key) == value
    }

    private static func unreliableResult(contracts: [ContractV2] = []) -> SalaryEmploymentContractReadResultV2 {
        SalaryEmploymentContractReadResultV2(
            contracts: contracts,
            reliable: false,
            repairedFromBackup: false,
            warnings: [storageWarning]
        )
    }
}
