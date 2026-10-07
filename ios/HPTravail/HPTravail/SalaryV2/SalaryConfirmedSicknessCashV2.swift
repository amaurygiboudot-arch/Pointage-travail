import Foundation

/// Explicit monthly observed IJSS transfers, separate from employer payroll and taxable/PAS calculations.
enum SalaryConfirmedSicknessCashV2 {
    struct Record: Codable, Equatable {
        let companyId: String
        let period: YearMonthV2
        let directEmployeeNetBeforeTax: Decimal?
        let subrogatedEmployerNetBeforeTax: Decimal?
        let source: String
        let confirmedAt: Date
    }
    struct Result: Equatable {
        let record: Record?
        let reliable: Bool
        let warnings: [String]
    }
    private static func key(_ companyId: String, _ period: YearMonthV2) -> String {
        "salary_confirmed_sickness_cash_v2.\(companyId)|\(period)"
    }
    static func valid(_ record: Record) -> Bool {
        !record.companyId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !record.companyId.contains("|") &&
        (1...9999).contains(record.period.year) && (1...12).contains(record.period.month) &&
        !record.source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && record.source.count <= 500 &&
        record.confirmedAt.timeIntervalSince1970.isFinite && record.confirmedAt.timeIntervalSince1970 > 0 &&
        (record.directEmployeeNetBeforeTax != nil || record.subrogatedEmployerNetBeforeTax != nil) &&
        [record.directEmployeeNetBeforeTax, record.subrogatedEmployerNetBeforeTax].compactMap { $0 }.allSatisfy { !$0.isNaN && $0 >= 0 }
    }
    static func read(companyId: String, period: YearMonthV2, defaults: UserDefaults = .standard) -> Result {
        guard let object = defaults.object(forKey: key(companyId, period)) else { return .init(record: nil, reliable: true, warnings: []) }
        guard let data = object as? Data, let record = try? JSONDecoder().decode(Record.self, from: data),
              valid(record), record.companyId == companyId, record.period == period else {
            return .init(record: nil, reliable: false, warnings: ["IJSS mensuelles : source locale à vérifier ; aucun montant confirmé utilisable."])
        }
        return .init(record: record, reliable: true, warnings: [])
    }
    static func save(_ record: Record, confirmed: Bool, defaults: UserDefaults = .standard) -> Bool {
        guard confirmed, valid(record), read(companyId: record.companyId, period: record.period, defaults: defaults).reliable,
              let data = try? JSONEncoder().encode(record) else { return false }
        defaults.set(data, forKey: key(record.companyId, record.period))
        return read(companyId: record.companyId, period: record.period, defaults: defaults).record == record
    }
    static func remove(companyId: String, period: YearMonthV2, defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: key(companyId, period))
    }
}
