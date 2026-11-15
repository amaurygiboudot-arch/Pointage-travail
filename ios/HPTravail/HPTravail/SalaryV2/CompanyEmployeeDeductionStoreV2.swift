import Foundation

enum CompanyEmployeeDeductionStoreV2 {
    private static let lock = NSRecursiveLock()
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
        defaults: UserDefaults = SalaryAccountStorageV2.currentDefaults,
        companyId: String
    ) -> ReadResult {
        let defaults = SalaryAccountStorageV2.scoped(defaults)
        let id = companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        return SalaryAccountStorageV2.readConsistently(defaults, unavailable: ReadResult(records: [], reliable: false, warnings: [SalaryAccountStorageV2.warning])) {
            withStore(defaults: defaults, companyId: id, unavailable: companyUnavailableResult()) {
                guard let object = defaults.object(forKey: storageKey(id)) else {
                    return ReadResult(records: [], reliable: true, warnings: [])
                }
                guard let raw = object as? String else {
                    return ReadResult(records: [], reliable: false, warnings: [storageWarning])
                }
                return decode(raw)
            }
        }
    }

    static func save(
        defaults: UserDefaults = SalaryAccountStorageV2.currentDefaults,
        companyId: String,
        record: CompanyEmployeeDeductionResolverV2.Record
    ) -> Bool {
        let defaults = SalaryAccountStorageV2.scoped(defaults)
        let id = companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        return withStore(defaults: defaults, companyId: id, unavailable: false) {
            guard CompanyEmployeeDeductionResolverV2.valid(record) else { return false }

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
    }

    /// Interactive entry affects one month only. Existing wider periods and conflicts are
    /// preserved for explicit historical correction; an old draft cannot overwrite newer data.
    static func confirmMonth(
        defaults: UserDefaults = SalaryAccountStorageV2.currentDefaults,
        companyId: String,
        period: CompanyEmployeeDeductionResolverV2.YearMonth,
        kind: CompanyEmployeeDeductionResolverV2.Kind,
        amount: Double,
        source: String,
        confirmed: Bool,
        expected: ReadResult
    ) -> Bool {
        let defaults = SalaryAccountStorageV2.scoped(defaults)
        let id = companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        let source = source.trimmingCharacters(in: .whitespacesAndNewlines)
        return withStore(defaults: defaults, companyId: id, unavailable: false) {
            guard confirmed, amount >= 0, PayrollMoneyV2.cents(amount) != nil,
                  !source.isEmpty, source.count <= 500, expected.reliable else { return false }
            let stored = read(defaults: defaults, companyId: id)
            guard stored.reliable, stored == expected else { return false }
            let applicable = stored.records.filter {
                $0.kind == kind && $0.effectiveFrom.map { $0 <= period } == true &&
                    ($0.effectiveTo == nil || $0.effectiveTo! >= period)
            }
            guard applicable.count <= 1,
                  applicable.allSatisfy({ $0.effectiveFrom == period && $0.effectiveTo == period }) else {
                return false
            }
            let record = CompanyEmployeeDeductionResolverV2.Record(
                id: applicable.first?.id ?? UUID().uuidString,
                kind: kind, amount: amount, effectiveFrom: period, effectiveTo: period, source: source
            )
            var records = stored.records.filter { $0.id != record.id }
            records.append(record)
            guard SalaryAccountStorageV2.available(defaults),
                  write(defaults: defaults, companyId: id, records: records) else { return false }
            let saved = read(defaults: defaults, companyId: id)
            return saved.reliable && saved.records == records
        }
    }

    static func remove(
        defaults: UserDefaults = SalaryAccountStorageV2.currentDefaults,
        companyId: String,
        recordId: String
    ) -> Bool {
        let defaults = SalaryAccountStorageV2.scoped(defaults)
        let id = companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        let target = recordId.trimmingCharacters(in: .whitespacesAndNewlines)
        return withStore(defaults: defaults, companyId: id, unavailable: false) {
            guard !target.isEmpty else { return false }
            let stored = read(defaults: defaults, companyId: id)
            guard stored.reliable else { return false }
            return write(
                defaults: defaults,
                companyId: id,
                records: stored.records.filter { $0.id != target }
            )
        }
    }

    static func resolve(
        defaults: UserDefaults = SalaryAccountStorageV2.currentDefaults,
        companyId: String,
        period: CompanyEmployeeDeductionResolverV2.YearMonth
    ) -> CompanyEmployeeDeductionResolverV2.Snapshot {
        let defaults = SalaryAccountStorageV2.scoped(defaults)
        return resolve(read(defaults: defaults, companyId: companyId), period: period)
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
        let defaults = SalaryAccountStorageV2.scoped(defaults)
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

    /// The employer lock always precedes the deduction lock, including nested reads.
    private static func withStore<T>(defaults: UserDefaults, companyId: String, unavailable: T, _ operation: () -> T) -> T {
        SalaryCompanyStoreV2.withConfirmedCompany(companyId: companyId, defaults: defaults, unavailable: unavailable) {
            lock.lock(); defer { lock.unlock() }
            return operation()
        }
    }

    private static func companyUnavailableResult() -> ReadResult {
        ReadResult(records: [], reliable: false, warnings: [companyWarning])
    }

    private static func storageKey(_ companyId: String) -> String {
        "employee_deductions_v2.\(companyId)"
    }
}
