import Foundation

enum CompanyEmployeeDeductionStoreV2 {
    static let storageWarning =
        "Retenues salarié / fiscales : stockage local incohérent ; calcul bloqué et aucune ancienne valeur n'est réutilisée."
    static let companyWarning =
        "Retenues salarié / fiscales : entreprise absente ou stockage des entreprises non fiable."

    struct ReadResult: Equatable {
        let records: [CompanyEmployeeDeductionResolverV2.Record]
        let reliable: Bool
        let warnings: [String]
    }

    static func read(
        defaults: UserDefaults = .standard,
        companyId: String
    ) -> ReadResult {
        let id = companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard confirmedCompany(defaults: defaults, companyId: id) else {
            return companyUnavailableResult()
        }

        guard let object = defaults.object(forKey: storageKey(id)) else {
            return ReadResult(records: [], reliable: true, warnings: [])
        }
        guard let raw = object as? String else {
            return ReadResult(records: [], reliable: false, warnings: [storageWarning])
        }
        return decode(raw)
    }

    static func save(
        defaults: UserDefaults = .standard,
        companyId: String,
        record: CompanyEmployeeDeductionResolverV2.Record
    ) -> Bool {
        let id = companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard confirmedCompany(defaults: defaults, companyId: id),
              CompanyEmployeeDeductionResolverV2.valid(record) else {
            return false
        }

        let stored = read(defaults: defaults, companyId: id)
        guard stored.reliable else { return false }

        var items = stored.records
        if let index = items.firstIndex(where: { $0.id == record.id }) {
            items[index] = record
        } else {
            items.append(record)
        }
        return write(defaults: defaults, companyId: id, records: items)
    }

    static func remove(
        defaults: UserDefaults = .standard,
        companyId: String,
        recordId: String
    ) -> Bool {
        let id = companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        let target = recordId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !target.isEmpty, confirmedCompany(defaults: defaults, companyId: id) else {
            return false
        }

        let stored = read(defaults: defaults, companyId: id)
        guard stored.reliable else { return false }
        return write(
            defaults: defaults,
            companyId: id,
            records: stored.records.filter { $0.id != target }
        )
    }

    static func resolve(
        defaults: UserDefaults = .standard,
        companyId: String,
        period: CompanyEmployeeDeductionResolverV2.YearMonth
    ) -> CompanyEmployeeDeductionResolverV2.Snapshot {
        resolve(read(defaults: defaults, companyId: companyId), period: period)
    }

    static func resolve(
        _ stored: ReadResult,
        period: CompanyEmployeeDeductionResolverV2.YearMonth
    ) -> CompanyEmployeeDeductionResolverV2.Snapshot {
        if stored.reliable {
            return CompanyEmployeeDeductionResolverV2.resolve(records: stored.records, period: period)
        }

        let warnings = stored.warnings.isEmpty ? [storageWarning] : stored.warnings
        let values = Dictionary(
            uniqueKeysWithValues: CompanyEmployeeDeductionResolverV2.Kind.allCases.map { kind in
                (
                    kind,
                    CompanyEmployeeDeductionResolverV2.Value(
                        amount: nil,
                        source: nil,
                        hasDatedRecords: true,
                        reliable: false,
                        warnings: warnings
                    )
                )
            }
        )
        return CompanyEmployeeDeductionResolverV2.Snapshot(values: values, warnings: warnings)
    }

    static func decode(_ raw: String) -> ReadResult {
        guard let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return ReadResult(records: [], reliable: false, warnings: [storageWarning])
        }

        var records: [CompanyEmployeeDeductionResolverV2.Record] = []
        var malformed = false
        for object in array {
            if let record = decodeRecord(object) {
                records.append(record)
            } else {
                malformed = true
            }
        }

        if Set(records.map(\.id)).count != records.count {
            malformed = true
        }

        return ReadResult(
            records: records,
            reliable: !malformed,
            warnings: malformed ? [storageWarning] : []
        )
    }

    private static func write(
        defaults: UserDefaults,
        companyId: String,
        records: [CompanyEmployeeDeductionResolverV2.Record]
    ) -> Bool {
        guard records.allSatisfy(CompanyEmployeeDeductionResolverV2.valid),
              Set(records.map(\.id)).count == records.count else {
            return false
        }

        let array: [[String: Any]] = records.map { record in
            var value: [String: Any] = [
                "id": record.id,
                "kind": record.kind.rawValue,
                "amount": record.amount,
                "effectiveFrom": record.effectiveFrom!.description,
                "source": record.source!.trimmingCharacters(in: .whitespacesAndNewlines)
            ]
            value["effectiveTo"] = record.effectiveTo?.description ?? NSNull()
            return value
        }

        guard JSONSerialization.isValidJSONObject(array),
              let data = try? JSONSerialization.data(withJSONObject: array),
              let raw = String(data: data, encoding: .utf8),
              decode(raw).reliable else {
            return false
        }

        defaults.set(raw, forKey: storageKey(companyId))
        return defaults.string(forKey: storageKey(companyId)) == raw
    }

    private static func decodeRecord(
        _ object: [String: Any]
    ) -> CompanyEmployeeDeductionResolverV2.Record? {
        guard let id = (object["id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !id.isEmpty,
              let kindRaw = object["kind"] as? String,
              let kind = CompanyEmployeeDeductionResolverV2.Kind(rawValue: kindRaw),
              let amount = (object["amount"] as? NSNumber)?.doubleValue,
              let fromRaw = object["effectiveFrom"] as? String,
              let effectiveFrom = CompanyEmployeeDeductionResolverV2.YearMonth(iso8601: fromRaw),
              let source = (object["source"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !source.isEmpty else {
            return nil
        }

        let effectiveTo: CompanyEmployeeDeductionResolverV2.YearMonth?
        if object["effectiveTo"] == nil || object["effectiveTo"] is NSNull {
            effectiveTo = nil
        } else if let toRaw = object["effectiveTo"] as? String,
                  let parsed = CompanyEmployeeDeductionResolverV2.YearMonth(iso8601: toRaw) {
            effectiveTo = parsed
        } else {
            return nil
        }

        let record = CompanyEmployeeDeductionResolverV2.Record(
            id: id,
            kind: kind,
            amount: amount,
            effectiveFrom: effectiveFrom,
            effectiveTo: effectiveTo,
            source: source
        )
        return CompanyEmployeeDeductionResolverV2.valid(record) ? record : nil
    }

    private static func confirmedCompany(defaults: UserDefaults, companyId: String) -> Bool {
        guard !companyId.isEmpty else { return false }
        let stored = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)
        return SalaryCompanyStoreV2.confirmedCompany(stored, companyId: companyId) != nil
    }

    private static func companyUnavailableResult() -> ReadResult {
        ReadResult(records: [], reliable: false, warnings: [companyWarning])
    }

    private static func storageKey(_ companyId: String) -> String {
        "employee_deductions_v2.\(companyId)"
    }
}
