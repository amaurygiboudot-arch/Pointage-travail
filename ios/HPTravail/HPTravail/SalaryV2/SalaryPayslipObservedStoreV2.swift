import Foundation

/// Confirmed monthly observations only. Reading never updates an employment profile.
enum SalaryPayslipObservedStoreV2 {
    private static let lock = NSRecursiveLock()
    struct Record: Codable, Equatable {
        let companyId: String
        let period: YearMonthV2
        let amounts: [String: Double]
        let excerpts: [String: String]
        let confirmedAt: Date
        var detailedDocument: SalaryObservedDocumentV2.Document? = nil
    }
    // Decode the original numeric tokens before Double can erase an unrepresentable cent.
    private struct StoredAmounts: Decodable {
        let amounts: [String: Decimal]
    }
    struct Result: Equatable {
        let record: Record?
        let reliable: Bool
        var lease = SalaryAccountSessionV2.storageLease
    }
    private static func key(_ companyId: String, _ period: YearMonthV2) -> String {
        "salary_payslip_observed_v2.\(companyId)|\(period)"
    }
    static func validScope(companyId: String, period: YearMonthV2) -> Bool {
        !companyId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && companyId.count <= 200 &&
        !companyId.contains("|") && (1900...9999).contains(period.year) && (1...12).contains(period.month)
    }
    static func valid(_ record: Record) -> Bool {
        let allowed = Set(SalaryPayslipFieldV2.allCases.map(\.rawValue))
        return validScope(companyId: record.companyId, period: record.period) &&
            (!record.amounts.isEmpty || record.detailedDocument != nil) && record.amounts.count <= allowed.count &&
            (record.detailedDocument.map { SalaryObservedDocumentV2.valid($0, period: record.period) } ?? true) &&
            record.amounts.allSatisfy { allowed.contains($0.key) && SalaryPayslipAmountV2.cents($0.value) != nil } &&
            record.excerpts.allSatisfy { record.amounts[$0.key] != nil && !$0.value.isEmpty && $0.value.count <= 300 } &&
            record.confirmedAt.timeIntervalSince1970.isFinite && record.confirmedAt.timeIntervalSince1970 > 0
    }
    static func read(companyId: String, period: YearMonthV2, defaults: UserDefaults = SalaryAccountStorageV2.currentDefaults) -> Result {
        let defaults = SalaryAccountStorageV2.scoped(defaults)
        return SalaryAccountStorageV2.readConsistently(defaults, unavailable: Result(record: nil, reliable: false)) {
            guard SalaryAccountStorageV2.available(defaults) else { return Result(record: nil, reliable: false) }
            guard validScope(companyId: companyId, period: period) else { return Result(record: nil, reliable: false) }
            guard let raw = defaults.object(forKey: key(companyId, period)) else { return Result(record: nil, reliable: true) }
            guard let data = raw as? Data, data.count <= 262_144,
                  let record = try? JSONDecoder().decode(Record.self, from: data), valid(record),
                  let original = try? JSONDecoder().decode(StoredAmounts.self, from: data),
                  original.amounts.count == record.amounts.count,
                  original.amounts.allSatisfy({ key, amount in
                      guard let value = SalaryPayslipAmountV2.importedAmount(amount) else { return false }
                      return record.amounts[key] == value
                  }),
                  record.companyId == companyId, record.period == period else { return Result(record: nil, reliable: false) }
            return Result(record: record, reliable: true)
        }
    }
    static func save(_ record: Record, confirmed: Bool, defaults: UserDefaults = SalaryAccountStorageV2.currentDefaults) -> Bool {
        let defaults = SalaryAccountStorageV2.scoped(defaults)
        lock.lock(); defer { lock.unlock() }
        let current = read(companyId: record.companyId, period: record.period, defaults: defaults)
        var updated = record
        // The aggregate editor cannot silently discard the confirmed transcription.
        if updated.detailedDocument == nil { updated.detailedDocument = current.record?.detailedDocument }
        guard confirmed, record.detailedDocument == nil || record.detailedDocument == current.record?.detailedDocument,
              current.reliable, valid(updated),
              let data = try? JSONEncoder().encode(updated), data.count <= 262_144 else { return false }
        defaults.set(data, forKey: key(updated.companyId, updated.period))
        return read(companyId: updated.companyId, period: updated.period, defaults: defaults).record == updated
    }
    static func saveDetail(_ document: SalaryObservedDocumentV2.Document?, companyId: String, period: YearMonthV2,
                           confirmed: Bool, expected: Result, defaults: UserDefaults = SalaryAccountStorageV2.currentDefaults) -> Bool {
        let defaults = SalaryAccountStorageV2.scoped(defaults)
        return SalaryCompanyStoreV2.withConfirmedCompany(companyId: companyId, defaults: defaults, unavailable: false) {
            lock.lock(); defer { lock.unlock() }
            let current = read(companyId: companyId, period: period, defaults: defaults)
            guard confirmed, expected.lease == SalaryAccountSessionV2.storageLease, current.reliable, current == expected, SalaryAccountStorageV2.available(defaults),
                  document.map({ SalaryObservedDocumentV2.valid($0, period: period) }) ?? true else { return false }
            var confirmedDocument = document
            confirmedDocument?.aggregateFingerprint = SalaryObservedDocumentV2.fingerprint(amounts: current.record?.amounts ?? [:], excerpts: current.record?.excerpts ?? [:])
            let updated = Record(companyId: companyId, period: period, amounts: current.record?.amounts ?? [:],
                                 excerpts: current.record?.excerpts ?? [:], confirmedAt: Date(), detailedDocument: confirmedDocument)
            if updated.amounts.isEmpty && document == nil {
                defaults.removeObject(forKey: key(companyId, period))
                return read(companyId: companyId, period: period, defaults: defaults).record == nil
            }
            guard valid(updated), let data = try? JSONEncoder().encode(updated), data.count <= 262_144 else { return false }
            defaults.set(data, forKey: key(companyId, period))
            return read(companyId: companyId, period: period, defaults: defaults).record == updated
        }
    }
    /// Remove aggregates only. Corrupt payloads remain intact for explicit recovery.
    static func remove(companyId: String, period: YearMonthV2, defaults: UserDefaults = SalaryAccountStorageV2.currentDefaults) -> Bool {
        let defaults = SalaryAccountStorageV2.scoped(defaults)
        lock.lock(); defer { lock.unlock() }
        guard validScope(companyId: companyId, period: period) else { return false }
        let current = read(companyId: companyId, period: period, defaults: defaults)
        guard current.reliable else { return false }
        if let detail = current.record?.detailedDocument {
            let retained = Record(companyId: companyId, period: period, amounts: [:], excerpts: [:], confirmedAt: Date(), detailedDocument: detail)
            guard let data = try? JSONEncoder().encode(retained) else { return false }
            defaults.set(data, forKey: key(companyId, period))
            return read(companyId: companyId, period: period, defaults: defaults).record == retained
        }
        defaults.removeObject(forKey: key(companyId, period))
        return defaults.object(forKey: key(companyId, period)) == nil
    }
}
