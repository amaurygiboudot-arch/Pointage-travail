import Foundation

/// Persistance iOS des primes contractuelles/personnelles.
/// Mêmes invariants que le store Android : entreprise confirmée et couverture mensuelle explicite.
enum CompanyPremiumStoreV2 {
    struct MonthCoverageSnapshot: Equatable {
        let confirmed: Bool
        let source: String?
        let storageReliable: Bool
        let warnings: [String]
    }

    private static let lock = NSLock()

    static func read(
        companyId: String,
        defaults: UserDefaults = .standard
    ) -> CompanyPremiumContractV2.ReadResult {
        guard confirmedCompany(companyId: companyId, defaults: defaults) != nil else {
            return CompanyPremiumContractV2.companyUnavailableReadResult()
        }
        return readRecordsConfirmed(companyId: companyId, defaults: defaults)
    }

    static func resolve(
        companyId: String,
        period: YearMonthV2,
        defaults: UserDefaults = .standard
    ) -> CompanyPremiumContractV2.Snapshot {
        guard confirmedCompany(companyId: companyId, defaults: defaults) != nil else {
            return CompanyPremiumContractV2.resolve(
                records: CompanyPremiumContractV2.companyUnavailableReadResult(),
                confirmations: CompanyPremiumContractV2.companyUnavailableConfirmationResult(),
                period: period
            )
        }
        return CompanyPremiumContractV2.resolve(
            records: readRecordsConfirmed(companyId: companyId, defaults: defaults),
            confirmations: readConfirmationsConfirmed(companyId: companyId, defaults: defaults),
            period: period
        )
    }

    static func monthCoverage(
        companyId: String,
        period: YearMonthV2,
        defaults: UserDefaults = .standard
    ) -> MonthCoverageSnapshot {
        guard confirmedCompany(companyId: companyId, defaults: defaults) != nil else {
            return .init(
                confirmed: false,
                source: nil,
                storageReliable: false,
                warnings: CompanyPremiumContractV2.companyUnavailableConfirmationResult().warnings
            )
        }
        let stored = readConfirmationsConfirmed(companyId: companyId, defaults: defaults)
        guard stored.reliable else {
            return .init(confirmed: false, source: nil, storageReliable: false, warnings: stored.warnings)
        }
        let matching = stored.confirmations.filter { $0.period == period }
        switch matching.count {
        case 0:
            return .init(
                confirmed: false,
                source: nil,
                storageReliable: true,
                warnings: [
                    "Primes contractuelles/personnelles \(period) : exhaustivité du mois à confirmer ; aucun zéro implicite n'est retenu."
                ]
            )
        case 1:
            return .init(confirmed: true, source: matching[0].source, storageReliable: true, warnings: [])
        default:
            return .init(
                confirmed: false,
                source: nil,
                storageReliable: false,
                warnings: [CompanyPremiumContractV2.coverageStorageWarning]
            )
        }
    }

    static func save(
        companyId: String,
        record: CompanyPremiumContractV2.Record,
        defaults: UserDefaults = .standard
    ) -> Bool {
        guard validRecord(record) else { return false }
        lock.lock()
        defer { lock.unlock() }

        guard confirmedCompany(companyId: companyId, defaults: defaults) != nil else { return false }
        let stored = readRecordsConfirmed(companyId: companyId, defaults: defaults)
        let confirmations = readConfirmationsConfirmed(companyId: companyId, defaults: defaults)
        guard stored.reliable, confirmations.reliable else { return false }

        var records = stored.records
        if let index = records.firstIndex(where: { $0.id == record.id }) {
            records[index] = record
        } else {
            records.append(record)
        }
        return writeRecordsAndInvalidateConfirmations(
            companyId: companyId,
            records: records,
            defaults: defaults
        )
    }

    static func remove(
        companyId: String,
        recordId: String,
        defaults: UserDefaults = .standard
    ) -> Bool {
        guard !recordId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        lock.lock()
        defer { lock.unlock() }

        guard confirmedCompany(companyId: companyId, defaults: defaults) != nil else { return false }
        let stored = readRecordsConfirmed(companyId: companyId, defaults: defaults)
        let confirmations = readConfirmationsConfirmed(companyId: companyId, defaults: defaults)
        guard stored.reliable, confirmations.reliable else { return false }
        return writeRecordsAndInvalidateConfirmations(
            companyId: companyId,
            records: stored.records.filter { $0.id != recordId },
            defaults: defaults
        )
    }

    static func confirmMonth(
        companyId: String,
        period: YearMonthV2,
        source: String,
        defaults: UserDefaults = .standard
    ) -> Bool {
        let source = source.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !source.isEmpty else { return false }
        lock.lock()
        defer { lock.unlock() }

        guard confirmedCompany(companyId: companyId, defaults: defaults) != nil else { return false }
        let stored = readRecordsConfirmed(companyId: companyId, defaults: defaults)
        let confirmations = readConfirmationsConfirmed(companyId: companyId, defaults: defaults)
        guard stored.reliable, confirmations.reliable else { return false }
        let base = CompanyPremiumContractV2.resolve(records: stored.records, period: period)
        guard base.reliable else { return false }

        var updated = confirmations.confirmations.filter { $0.period != period }
        updated.append(.init(period: period, source: source))
        return writeConfirmations(companyId: companyId, confirmations: updated, defaults: defaults)
    }
    static func clearMonthConfirmation(
        companyId: String,
        period: YearMonthV2,
        defaults: UserDefaults = .standard
    ) -> Bool {
        lock.lock()
        defer { lock.unlock() }

        guard confirmedCompany(companyId: companyId, defaults: defaults) != nil else { return false }
        let stored = readConfirmationsConfirmed(companyId: companyId, defaults: defaults)
        guard stored.reliable else { return false }
        return writeConfirmations(
            companyId: companyId,
            confirmations: stored.confirmations.filter { $0.period != period },
            defaults: defaults
        )
    }

    private static func confirmedCompany(
        companyId: String,
        defaults: UserDefaults
    ) -> SalaryCompanyV2? {
        SalaryCompanyStoreV2.confirmedCompany(
            SalaryCompanyStoreV2.readConfirmed(defaults: defaults),
            companyId: companyId
        )
    }

    private static func readRecordsConfirmed(
        companyId: String,
        defaults: UserDefaults
    ) -> CompanyPremiumContractV2.ReadResult {
        let key = recordsKey(companyId)
        guard defaults.object(forKey: key) != nil else {
            return CompanyPremiumContractV2.decodeRecords("[]")
        }
        guard let raw = defaults.string(forKey: key) else {
            return .init(records: [], reliable: false, warnings: [CompanyPremiumContractV2.storageWarning])
        }
        return CompanyPremiumContractV2.decodeRecords(raw)
    }

    private static func readConfirmationsConfirmed(
        companyId: String,
        defaults: UserDefaults
    ) -> CompanyPremiumContractV2.ConfirmationReadResult {
        let key = confirmationsKey(companyId)
        guard defaults.object(forKey: key) != nil else {
            return CompanyPremiumContractV2.decodeConfirmations("[]")
        }
        guard let raw = defaults.string(forKey: key) else {
            return .init(
                confirmations: [],
                reliable: false,
                warnings: [CompanyPremiumContractV2.coverageStorageWarning]
            )
        }
        return CompanyPremiumContractV2.decodeConfirmations(raw)
    }

    private static func writeRecordsAndInvalidateConfirmations(
        companyId: String,
        records: [CompanyPremiumContractV2.Record],
        defaults: UserDefaults
    ) -> Bool {
        guard let raw = encodeRecords(records) else { return false }
        let verification = CompanyPremiumContractV2.decodeRecords(raw)
        guard verification.reliable, verification.records.count == records.count else { return false }

        defaults.set(raw, forKey: recordsKey(companyId))
        defaults.set("[]", forKey: confirmationsKey(companyId))
        return defaults.string(forKey: recordsKey(companyId)) == raw
            && defaults.string(forKey: confirmationsKey(companyId)) == "[]"
    }

    private static func writeConfirmations(
        companyId: String,
        confirmations: [CompanyPremiumContractV2.MonthConfirmation],
        defaults: UserDefaults
    ) -> Bool {
        let sorted = confirmations.sorted { $0.period < $1.period }
        let array: [[String: Any]] = sorted.map {
            ["period": $0.period.description, "source": $0.source]
        }
        guard JSONSerialization.isValidJSONObject(array),
              let data = try? JSONSerialization.data(withJSONObject: array),
              let raw = String(data: data, encoding: .utf8) else { return false }
        let verification = CompanyPremiumContractV2.decodeConfirmations(raw)
        guard verification.reliable, verification.confirmations.count == confirmations.count else { return false }
        defaults.set(raw, forKey: confirmationsKey(companyId))
        return defaults.string(forKey: confirmationsKey(companyId)) == raw
    }

    private static func encodeRecords(_ records: [CompanyPremiumContractV2.Record]) -> String? {
        let array: [[String: Any]] = records.map { record in
            [
                "id": record.id,
                "label": record.label,
                "grossAmount": record.grossAmount,
                "kind": record.kind.rawValue,
                "effectiveFrom": record.effectiveFrom?.description ?? NSNull(),
                "effectiveTo": record.effectiveTo?.description ?? NSNull(),
                "paymentMonth": record.paymentMonth?.description ?? NSNull()
            ]
        }
        guard JSONSerialization.isValidJSONObject(array),
              let data = try? JSONSerialization.data(withJSONObject: array),
              let raw = String(data: data, encoding: .utf8) else { return nil }
        return raw
    }

    private static func validRecord(_ record: CompanyPremiumContractV2.Record) -> Bool {
        guard !record.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !record.label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              record.grossAmount.isFinite,
              record.grossAmount > 0 else { return false }
        switch record.kind {
        case .monthly:
            guard let start = record.effectiveFrom,
                  record.paymentMonth == nil else { return false }
            return record.effectiveTo == nil || record.effectiveTo! >= start
        case .oneOff:
            return record.paymentMonth != nil
                && record.effectiveFrom == nil
                && record.effectiveTo == nil
        }
    }
    private static func recordsKey(_ companyId: String) -> String {
        "salary_company_\(safeCompanyId(companyId)).company_premiums_v2"
    }

    private static func confirmationsKey(_ companyId: String) -> String {
        "salary_company_\(safeCompanyId(companyId)).company_premiums_month_coverage_v2"
    }

    private static func safeCompanyId(_ companyId: String) -> String {
        String(companyId.map { character in
            if character.isLetter || character.isNumber || character == "_" || character == "-" {
                return character
            }
            return "_"
        })
    }
}
