import CoreFoundation
import Foundation

/// Entrées explicitement confirmées pour la prime d'ancienneté.
/// La date d'ancienneté conventionnelle reste distincte de la date d'embauche.
/// Un complément absent n'est jamais transformé en zéro sans confirmation explicite.
enum SalaryConventionSeniorityInputStoreV2 {
    struct Record: Equatable {
        let companyId: String
        let seniorityDate: PayrollCivilDateV2?
        let seniorityDateConfirmed: Bool
        let monthlySupplement: Double?
        let monthlySupplementConfirmed: Bool
        let source: String
        let checkedAtMs: Int64
    }

    struct ReadResult {
        let record: Record?
        let reliable: Bool
        let warnings: [String]
    }

    static let storageWarning =
        "Prime d'ancienneté : stockage local des entrées confirmées incohérent ; aucune date ni complément n'est utilisé."

    private static let key = "salary_convention_seniority_input_v2.records"
    private static let lock = NSLock()

    static func read(companyId: String, defaults: UserDefaults = .standard) -> ReadResult {
        let id = companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else {
            return .init(record: nil, reliable: false, warnings: [storageWarning])
        }
        let all = readAll(defaults: defaults)
        guard all.reliable else {
            return .init(record: nil, reliable: false, warnings: all.warnings)
        }
        return .init(record: all.records.first { $0.companyId == id }, reliable: true, warnings: [])
    }

    @discardableResult
    static func confirmSeniorityDate(
        companyId: String,
        date: PayrollCivilDateV2,
        source: String,
        checkedAtMs: Int64,
        defaults: UserDefaults = .standard
    ) -> Bool {
        mutate(companyId: companyId, source: source, checkedAtMs: checkedAtMs, defaults: defaults) {
            current, id, src in
            Record(
                companyId: id,
                seniorityDate: date,
                seniorityDateConfirmed: true,
                monthlySupplement: current?.monthlySupplement,
                monthlySupplementConfirmed: current?.monthlySupplementConfirmed ?? false,
                source: src,
                checkedAtMs: checkedAtMs
            )
        }
    }

    /// amount = 0 confirme explicitement qu'aucun complément mensuel n'existe.
    @discardableResult
    static func confirmMonthlySupplement(
        companyId: String,
        amount: Double,
        source: String,
        checkedAtMs: Int64,
        defaults: UserDefaults = .standard
    ) -> Bool {
        guard amount.isFinite, amount >= 0 else { return false }
        return mutate(companyId: companyId, source: source, checkedAtMs: checkedAtMs, defaults: defaults) {
            current, id, src in
            Record(
                companyId: id,
                seniorityDate: current?.seniorityDate,
                seniorityDateConfirmed: current?.seniorityDateConfirmed ?? false,
                monthlySupplement: amount,
                monthlySupplementConfirmed: true,
                source: src,
                checkedAtMs: checkedAtMs
            )
        }
    }

    static func decode(_ raw: String) -> (records: [Record], reliable: Bool, warnings: [String]) {
        guard let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return ([], false, [storageWarning])
        }
        var records: [Record] = []
        var malformed = false
        for object in array {
            guard let record = decodeRecord(object), valid(record) else {
                malformed = true
                continue
            }
            records.append(record)
        }
        if Set(records.map(\.companyId)).count != records.count { malformed = true }
        return (records, !malformed, malformed ? [storageWarning] : [])
    }

    private static func mutate(
        companyId: String,
        source: String,
        checkedAtMs: Int64,
        defaults: UserDefaults,
        transform: (Record?, String, String) -> Record
    ) -> Bool {
        let id = companyId.trimmingCharacters(in: .whitespacesAndNewlines)
        let src = source.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty, !src.isEmpty, checkedAtMs >= 0 else { return false }

        lock.lock()
        defer { lock.unlock() }
        let stored = readAll(defaults: defaults)
        guard stored.reliable else { return false }

        let current = stored.records.first { $0.companyId == id }
        let candidate = transform(current, id, src)
        guard valid(candidate) else { return false }

        var records = stored.records.filter { $0.companyId != id }
        records.append(candidate)
        guard let raw = encode(records) else { return false }
        defaults.set(raw, forKey: key)
        guard defaults.string(forKey: key) == raw else { return false }
        return read(companyId: id, defaults: defaults).record == candidate
    }

    private static func readAll(defaults: UserDefaults)
        -> (records: [Record], reliable: Bool, warnings: [String]) {
        guard let object = defaults.object(forKey: key) else { return ([], true, []) }
        guard let raw = object as? String else { return ([], false, [storageWarning]) }
        return decode(raw)
    }

    private static func valid(_ record: Record) -> Bool {
        guard !record.companyId.isEmpty, !record.source.isEmpty, record.checkedAtMs >= 0 else { return false }
        if record.seniorityDateConfirmed != (record.seniorityDate != nil) { return false }
        if record.monthlySupplementConfirmed {
            guard let value = record.monthlySupplement, value.isFinite, value >= 0 else { return false }
        } else if record.monthlySupplement != nil {
            return false
        }
        return true
    }

    private static func encode(_ records: [Record]) -> String? {
        guard records.allSatisfy(valid), Set(records.map(\.companyId)).count == records.count else { return nil }
        let array: [[String: Any]] = records.sorted { $0.companyId < $1.companyId }.map { record in
            [
                "companyId": record.companyId,
                "seniorityDate": json(record.seniorityDate.map(dateString)),
                "seniorityDateConfirmed": record.seniorityDateConfirmed,
                "monthlySupplement": json(record.monthlySupplement),
                "monthlySupplementConfirmed": record.monthlySupplementConfirmed,
                "source": record.source,
                "checkedAtMs": record.checkedAtMs
            ]
        }
        guard JSONSerialization.isValidJSONObject(array),
              let data = try? JSONSerialization.data(withJSONObject: array),
              let raw = String(data: data, encoding: .utf8) else { return nil }
        return raw
    }

    private static func decodeRecord(_ object: [String: Any]) -> Record? {
        guard let companyId = string(object["companyId"]),
              let source = string(object["source"]),
              let checkedAtMs = integer(object["checkedAtMs"]),
              let dateConfirmed = boolean(object["seniorityDateConfirmed"]),
              let supplementConfirmed = boolean(object["monthlySupplementConfirmed"]),
              let date = optionalDate(object["seniorityDate"]),
              let supplement = optionalDouble(object["monthlySupplement"]) else { return nil }
        return .init(
            companyId: companyId,
            seniorityDate: date,
            seniorityDateConfirmed: dateConfirmed,
            monthlySupplement: supplement,
            monthlySupplementConfirmed: supplementConfirmed,
            source: source,
            checkedAtMs: checkedAtMs
        )
    }

    private static func optionalDate(_ raw: Any?) -> PayrollCivilDateV2?? {
        if raw == nil || raw is NSNull { return .some(nil) }
        guard let value = raw as? String else { return nil }
        let p = value.split(separator: "-", omittingEmptySubsequences: false)
        guard p.count == 3, let y = Int(p[0]), let m = Int(p[1]), let d = Int(p[2]),
              let date = PayrollCivilDateV2(year: y, month: m, day: d) else { return nil }
        return .some(date)
    }

    private static func optionalDouble(_ raw: Any?) -> Double?? {
        if raw == nil || raw is NSNull { return .some(nil) }
        guard let n = raw as? NSNumber, !isBool(n), n.doubleValue.isFinite else { return nil }
        return .some(n.doubleValue)
    }

    private static func integer(_ raw: Any?) -> Int64? {
        guard let n = raw as? NSNumber, !isBool(n) else { return nil }
        let v = n.doubleValue
        guard v.isFinite, v.rounded() == v, v >= 0, v <= Double(Int64.max) else { return nil }
        return n.int64Value
    }

    private static func boolean(_ raw: Any?) -> Bool? {
        guard let n = raw as? NSNumber, isBool(n) else { return nil }
        return n.boolValue
    }

    private static func string(_ raw: Any?) -> String? {
        guard let s = raw as? String else { return nil }
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }

    private static func dateString(_ value: PayrollCivilDateV2) -> String {
        String(format: "%04d-%02d-%02d", value.year, value.month, value.day)
    }
    private static func json(_ value: Any?) -> Any { value ?? NSNull() }
    private static func isBool(_ value: NSNumber) -> Bool {
        CFGetTypeID(value) == CFBooleanGetTypeID()
    }
}
