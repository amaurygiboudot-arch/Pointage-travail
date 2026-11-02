import Foundation

/// Confirmed monthly observations only. Reading never updates an employment profile.
enum SalaryPayslipObservedStoreV2 {
    struct Record: Codable, Equatable {
        let companyId: String
        let period: YearMonthV2
        let amounts: [String: Double]
        let excerpts: [String: String]
        let confirmedAt: Date
    }
    struct Result {
        let record: Record?
        let reliable: Bool
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
            !record.amounts.isEmpty && record.amounts.count <= allowed.count &&
            record.amounts.allSatisfy { allowed.contains($0.key) && $0.value.isFinite && $0.value >= 0 } &&
            record.excerpts.allSatisfy { record.amounts[$0.key] != nil && !$0.value.isEmpty && $0.value.count <= 300 } &&
            record.confirmedAt.timeIntervalSince1970.isFinite && record.confirmedAt.timeIntervalSince1970 > 0
    }
    static func read(companyId: String, period: YearMonthV2, defaults: UserDefaults = .standard) -> Result {
        guard validScope(companyId: companyId, period: period) else { return Result(record: nil, reliable: false) }
        guard let raw = defaults.object(forKey: key(companyId, period)) else { return Result(record: nil, reliable: true) }
        guard let data = raw as? Data, data.count <= 16_384,
              let record = try? JSONDecoder().decode(Record.self, from: data), valid(record),
              record.companyId == companyId, record.period == period else { return Result(record: nil, reliable: false) }
        return Result(record: record, reliable: true)
    }
    static func save(_ record: Record, confirmed: Bool, defaults: UserDefaults = .standard) -> Bool {
        guard confirmed, valid(record), read(companyId: record.companyId, period: record.period, defaults: defaults).reliable,
              let data = try? JSONEncoder().encode(record), data.count <= 16_384 else { return false }
        defaults.set(data, forKey: key(record.companyId, record.period))
        return read(companyId: record.companyId, period: record.period, defaults: defaults).record == record
    }
    /// Explicit removal also permits recovery from a corrupt scoped record.
    static func remove(companyId: String, period: YearMonthV2, defaults: UserDefaults = .standard) -> Bool {
        guard validScope(companyId: companyId, period: period) else { return false }
        defaults.removeObject(forKey: key(companyId, period))
        return defaults.object(forKey: key(companyId, period)) == nil
    }
}
