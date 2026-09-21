import CoreFoundation
import Foundation

struct SalaryEmploymentContractHistoryReadResultV2: Equatable {
    let snapshots: [SalaryEmploymentContractSnapshotV2]
    let reliable: Bool
    let repairedFromBackup: Bool
    let warnings: [String]
}

/// Stockage persistant fail-closed de l'historique des contrats salariés confirmés.
///
/// Un stockage absent représente explicitement un historique vide. Un stockage présent mais
/// illisible ou incohérent reste non fiable et n'est jamais remplacé par un historique vide.
/// Le contrat courant n'est jamais utilisé comme fallback pour une période historique.
enum SalaryEmploymentContractHistoryStoreV2 {
    static let storageWarning =
        "Contrats Salaire V2 : historique local incohérent ; aucun contrat historique ni absence de contrat ne peut être déduit de ce stockage."
    static let repairedWarning =
        "Contrats Salaire V2 : historique local restauré depuis la dernière copie valide."

    private static let primaryKey = "salary_employment_contract_history_v2.confirmed_snapshots"
    private static let backupKey = "salary_employment_contract_history_v2.confirmed_snapshots_last_known_good"
    private static let lock = NSLock()
    private static let maxExactJSONInteger = 9_007_199_254_740_991.0

    static func readConfirmed(defaults: UserDefaults = .standard) -> SalaryEmploymentContractHistoryReadResultV2 {
        let primaryObject = defaults.object(forKey: primaryKey)
        let backupObject = defaults.object(forKey: backupKey)

        if primaryObject == nil {
            guard let backupObject else {
                return SalaryEmploymentContractHistoryReadResultV2(
                    snapshots: [],
                    reliable: true,
                    repairedFromBackup: false,
                    warnings: []
                )
            }
            guard let backupRaw = backupObject as? String else { return unreliableResult() }
            let backup = decodeConfirmed(backupRaw)
            guard backup.reliable else { return unreliableResult(snapshots: backup.snapshots) }
            guard writeVerified(backupRaw, forKey: primaryKey, defaults: defaults) else {
                return SalaryEmploymentContractHistoryReadResultV2(
                    snapshots: backup.snapshots,
                    reliable: false,
                    repairedFromBackup: false,
                    warnings: [storageWarning, "La copie valide a été trouvée mais sa restauration a échoué."]
                )
            }
            return SalaryEmploymentContractHistoryReadResultV2(
                snapshots: backup.snapshots,
                reliable: true,
                repairedFromBackup: true,
                warnings: [repairedWarning]
            )
        }

        let primary = (primaryObject as? String).map(decodeConfirmed) ?? unreliableResult()
        if primary.reliable, let raw = primaryObject as? String {
            if defaults.string(forKey: backupKey) != raw {
                _ = writeVerified(raw, forKey: backupKey, defaults: defaults)
            }
            return primary
        }

        guard let backupRaw = backupObject as? String else { return primary }
        let backup = decodeConfirmed(backupRaw)
        guard backup.reliable else { return primary }
        guard writeVerified(backupRaw, forKey: primaryKey, defaults: defaults) else {
            return SalaryEmploymentContractHistoryReadResultV2(
                snapshots: backup.snapshots,
                reliable: false,
                repairedFromBackup: false,
                warnings: [storageWarning, "La copie valide a été trouvée mais sa restauration a échoué."]
            )
        }
        return SalaryEmploymentContractHistoryReadResultV2(
            snapshots: backup.snapshots,
            reliable: true,
            repairedFromBackup: true,
            warnings: [repairedWarning]
        )
    }

    static func history(
        from stored: SalaryEmploymentContractHistoryReadResultV2
    ) -> SalaryEmploymentContractHistoryV2? {
        guard stored.reliable else { return nil }
        return SalaryEmploymentContractHistoryV2(stored.snapshots)
    }

    @discardableResult
    static func saveConfirmed(
        _ snapshot: SalaryEmploymentContractSnapshotV2,
        defaults: UserDefaults = .standard
    ) -> Bool {
        let candidate = normalizedSnapshot(snapshot)
        guard SalaryEmploymentContractHistoryV2([candidate]) != nil else { return false }

        lock.lock()
        defer { lock.unlock() }

        let stored = readConfirmed(defaults: defaults)
        guard stored.reliable else { return false }

        var current = stored.snapshots.filter {
            !(normalizeCompanyId($0.contract.employerId) == candidate.contract.employerId
              && $0.versionId.trimmingCharacters(in: .whitespacesAndNewlines) == candidate.versionId)
        }
        current.append(candidate)
        guard SalaryEmploymentContractHistoryV2(current) != nil,
              let raw = encodeConfirmed(current) else {
            return false
        }
        guard writeVerified(raw, forKey: primaryKey, defaults: defaults),
              writeVerified(raw, forKey: backupKey, defaults: defaults) else {
            return false
        }

        let reloaded = readConfirmed(defaults: defaults)
        return reloaded.reliable && reloaded.snapshots.contains(candidate)
    }

    static func decodeConfirmed(_ raw: String) -> SalaryEmploymentContractHistoryReadResultV2 {
        guard let data = raw.data(using: .utf8) else { return unreliableResult() }
        do {
            guard let array = try JSONSerialization.jsonObject(with: data) as? [Any] else {
                return unreliableResult()
            }

            var snapshots: [SalaryEmploymentContractSnapshotV2] = []
            var malformed = false
            for item in array {
                guard let object = item as? [String: Any],
                      let snapshot = decodeSnapshot(object) else {
                    malformed = true
                    continue
                }
                snapshots.append(normalizedSnapshot(snapshot))
            }
            if SalaryEmploymentContractHistoryV2(snapshots) == nil { malformed = true }

            return SalaryEmploymentContractHistoryReadResultV2(
                snapshots: snapshots,
                reliable: !malformed,
                repairedFromBackup: false,
                warnings: malformed ? [storageWarning] : []
            )
        } catch {
            return unreliableResult()
        }
    }

    static func encodeConfirmed(_ snapshots: [SalaryEmploymentContractSnapshotV2]) -> String? {
        let sorted = snapshots.map(normalizedSnapshot).sorted {
            let leftCompany = normalizeCompanyId($0.contract.employerId)
            let rightCompany = normalizeCompanyId($1.contract.employerId)
            if leftCompany != rightCompany { return leftCompany < rightCompany }
            if $0.effectiveFromEpochDay != $1.effectiveFromEpochDay {
                return $0.effectiveFromEpochDay < $1.effectiveFromEpochDay
            }
            return $0.versionId < $1.versionId
        }
        let array: [[String: Any]] = sorted.map { snapshot in
            [
                "versionId": snapshot.versionId,
                "sourceId": snapshot.sourceId,
                "effectiveFromEpochDay": snapshot.effectiveFromEpochDay,
                "effectiveToEpochDay": jsonValue(snapshot.effectiveToEpochDay),
                "checkedAtMs": snapshot.checkedAtMs,
                "note": jsonValue(snapshot.note),
                "contract": encodeContract(snapshot.contract)
            ]
        }
        guard JSONSerialization.isValidJSONObject(array),
              let data = try? JSONSerialization.data(withJSONObject: array),
              let raw = String(data: data, encoding: .utf8) else {
            return nil
        }
        return raw
    }

    private static func decodeSnapshot(_ object: [String: Any]) -> SalaryEmploymentContractSnapshotV2? {
        do {
            guard let contractObject = object["contract"] as? [String: Any] else { return nil }
            return SalaryEmploymentContractSnapshotV2(
                versionId: try requiredString(object["versionId"]),
                sourceId: try requiredString(object["sourceId"]),
                effectiveFromEpochDay: try strictInt64(object["effectiveFromEpochDay"]),
                effectiveToEpochDay: try optionalInt64(object["effectiveToEpochDay"]),
                contract: try decodeContract(contractObject),
                checkedAtMs: try strictInt64(object["checkedAtMs"]),
                note: try optionalString(object["note"])
            )
        } catch {
            return nil
        }
    }

    private static func encodeContract(_ contract: ContractV2) -> [String: Any] {
        [
            "id": contract.id,
            "employerId": contract.employerId,
            "type": contract.type.rawValue,
            "contractualWeeklyMinutes": jsonValue(contract.contractualWeeklyMinutes),
            "grossHourlyRate": jsonValue(contract.grossHourlyRate),
            "hireDateEpochDay": jsonValue(contract.hireDateEpochDay),
            "payrollCutoffDay": jsonValue(contract.payrollCutoffDay),
            "forfaitHoursPeriod": jsonValue(contract.forfaitHoursPeriod?.rawValue),
            "forfaitHours": jsonValue(contract.forfaitHours),
            "forfaitAnnualDays": jsonValue(contract.forfaitAnnualDays),
            "monthlyGrossSalary": jsonValue(contract.monthlyGrossSalary)
        ]
    }

    private static func decodeContract(_ object: [String: Any]) throws -> ContractV2 {
        let typeRaw = try requiredString(object["type"])
        guard let type = ContractTypeV2(rawValue: typeRaw) else { throw ParseError.invalid }
        let periodRaw = try optionalString(object["forfaitHoursPeriod"])
        let period: ForfaitHoursPeriodV2?
        if let periodRaw {
            guard let parsed = ForfaitHoursPeriodV2(rawValue: periodRaw) else { throw ParseError.invalid }
            period = parsed
        } else {
            period = nil
        }

        return ContractV2(
            id: try requiredString(object["id"]),
            employerId: try requiredString(object["employerId"]),
            type: type,
            contractualWeeklyMinutes: try optionalInt(object["contractualWeeklyMinutes"]),
            grossHourlyRate: try optionalDouble(object["grossHourlyRate"]),
            hireDateEpochDay: try optionalInt64(object["hireDateEpochDay"]),
            payrollCutoffDay: try optionalInt(object["payrollCutoffDay"]),
            forfaitHoursPeriod: period,
            forfaitHours: try optionalDouble(object["forfaitHours"]),
            forfaitAnnualDays: try optionalDouble(object["forfaitAnnualDays"]),
            monthlyGrossSalary: try optionalDouble(object["monthlyGrossSalary"])
        )
    }

    private static func normalizedSnapshot(
        _ snapshot: SalaryEmploymentContractSnapshotV2
    ) -> SalaryEmploymentContractSnapshotV2 {
        let contract = ContractV2(
            id: snapshot.contract.id.trimmingCharacters(in: .whitespacesAndNewlines),
            employerId: normalizeCompanyId(snapshot.contract.employerId),
            type: snapshot.contract.type,
            contractualWeeklyMinutes: snapshot.contract.contractualWeeklyMinutes,
            grossHourlyRate: snapshot.contract.grossHourlyRate,
            hireDateEpochDay: snapshot.contract.hireDateEpochDay,
            payrollCutoffDay: snapshot.contract.payrollCutoffDay,
            forfaitHoursPeriod: snapshot.contract.forfaitHoursPeriod,
            forfaitHours: snapshot.contract.forfaitHours,
            forfaitAnnualDays: snapshot.contract.forfaitAnnualDays,
            monthlyGrossSalary: snapshot.contract.monthlyGrossSalary
        )
        return SalaryEmploymentContractSnapshotV2(
            versionId: snapshot.versionId.trimmingCharacters(in: .whitespacesAndNewlines),
            sourceId: snapshot.sourceId.trimmingCharacters(in: .whitespacesAndNewlines),
            effectiveFromEpochDay: snapshot.effectiveFromEpochDay,
            effectiveToEpochDay: snapshot.effectiveToEpochDay,
            contract: contract,
            checkedAtMs: snapshot.checkedAtMs,
            note: snapshot.note?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        )
    }

    private static func normalizeCompanyId(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func requiredString(_ raw: Any?) throws -> String {
        guard let value = raw as? String else { throw ParseError.invalid }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw ParseError.invalid }
        return trimmed
    }

    private static func optionalString(_ raw: Any?) throws -> String? {
        if raw == nil || raw is NSNull { return nil }
        guard let value = raw as? String else { throw ParseError.invalid }
        return value.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
    }

    private static func optionalInt(_ raw: Any?) throws -> Int? {
        if raw == nil || raw is NSNull { return nil }
        let value = try strictInt64(raw)
        guard let exact = Int(exactly: value) else { throw ParseError.invalid }
        return exact
    }

    private static func optionalInt64(_ raw: Any?) throws -> Int64? {
        if raw == nil || raw is NSNull { return nil }
        return try strictInt64(raw)
    }

    private static func optionalDouble(_ raw: Any?) throws -> Double? {
        if raw == nil || raw is NSNull { return nil }
        guard let number = raw as? NSNumber,
              !isBooleanNumber(number) else {
            throw ParseError.invalid
        }
        let value = number.doubleValue
        guard value.isFinite else { throw ParseError.invalid }
        return value
    }

    private static func strictInt64(_ raw: Any?) throws -> Int64 {
        guard let number = raw as? NSNumber,
              !isBooleanNumber(number) else {
            throw ParseError.invalid
        }
        let value = number.doubleValue
        guard value.isFinite,
              value.rounded() == value,
              abs(value) <= maxExactJSONInteger else {
            throw ParseError.invalid
        }
        return Int64(value)
    }

    private static func isBooleanNumber(_ number: NSNumber) -> Bool {
        CFGetTypeID(number) == CFBooleanGetTypeID()
    }

    private static func jsonValue<T>(_ value: T?) -> Any {
        guard let value else { return NSNull() }
        return value
    }

    private static func writeVerified(_ value: String, forKey key: String, defaults: UserDefaults) -> Bool {
        defaults.set(value, forKey: key)
        return defaults.string(forKey: key) == value
    }

    private static func unreliableResult(
        snapshots: [SalaryEmploymentContractSnapshotV2] = []
    ) -> SalaryEmploymentContractHistoryReadResultV2 {
        SalaryEmploymentContractHistoryReadResultV2(
            snapshots: snapshots,
            reliable: false,
            repairedFromBackup: false,
            warnings: [storageWarning]
        )
    }

    private enum ParseError: Error {
        case invalid
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
