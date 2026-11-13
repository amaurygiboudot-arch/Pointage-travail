import Foundation
import CryptoKit
import CoreFoundation

/// Facts confirmed for one employer and month. These never become contract or tax rules.
enum SalaryMonthlyPayrollSettlementV2 {
    private static let lock = NSRecursiveLock()
    enum Field: String, CaseIterable, Codable, Identifiable {
        case transportReimbursement, garnishment, otherAddition, otherDeduction, netSocialObserved, netPaidObserved
        var id: String { rawValue }
        var label: String {
            switch self {
            case .transportReimbursement: return "Transport remboursé hors brut"
            case .garnishment: return "Saisie-arrêt sur le versement"
            case .otherAddition: return "Autres versements hors brut (dont paniers/frais non déjà inclus)"
            case .otherDeduction: return "Autres retenues sur le versement, non déjà incluses"
            case .netSocialObserved: return "Net social constaté sur la source"
            case .netPaidObserved: return "Net effectivement payé constaté sur la source"
            }
        }
    }
    struct Record: Equatable {
        var version = 1
        let companyId: String
        let period: YearMonthV2
        let transportReimbursement: Decimal?
        let garnishment: Decimal?
        let otherAddition: Decimal?
        let otherDeduction: Decimal?
        let netSocialObserved: Decimal?
        let netPaidObserved: Decimal?
        let source: String
        let confirmedAt: Int64 // Unix milliseconds; same unit on both mobile platforms.
        let adjustmentsComplete: Bool

        subscript(_ field: Field) -> Decimal? {
            switch field {
            case .transportReimbursement: return transportReimbursement
            case .garnishment: return garnishment
            case .otherAddition: return otherAddition
            case .otherDeduction: return otherDeduction
            case .netSocialObserved: return netSocialObserved
            case .netPaidObserved: return netPaidObserved
            }
        }
        var adjustments: [Decimal?] { [transportReimbursement, garnishment, otherAddition, otherDeduction] }
    }
    struct Result: Equatable {
        let record: Record?
        let reliable: Bool
        let warnings: [String]
        var revision: String? = nil
        static let missing = Result(record: nil, reliable: true, warnings: [])
        static let unavailable = Result(record: nil, reliable: false, warnings: ["Règlement mensuel : source indisponible ou illisible ; aucun montant confirmé utilisable."])
    }
    struct Projection: Equatable {
        let record: Record?
        let projectedPaid: Double?
        let observedMinusProjected: Double?
        let warnings: [String]
    }
    static let prefix = "salary_monthly_payroll_settlement_v2."
    static let completenessLabel = "Ces quatre ajustements sont exhaustifs pour ce mois, hors brut, cotisations et PAS, et ne sont pas déjà inclus dans le net calculé. Les paniers et frais restant à verser sont compris. Chaque zéro est confirmé."

    static func validScope(companyId: String, period: YearMonthV2) -> Bool {
        !companyId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        companyId.utf16.count <= 200 && !companyId.contains("|") &&
        !companyId.unicodeScalars.contains { $0.value <= 31 || (127...159).contains($0.value) } &&
        (1...9999).contains(period.year) && (1...12).contains(period.month)
    }
    /// Strict cents, with no rounding of entered or stored facts.
    static func cents(_ value: Decimal) -> Int64? {
        guard !value.isNaN, value >= 0 else { return nil }
        var original = value
        var rounded = Decimal()
        NSDecimalRound(&rounded, &original, 2, .plain)
        guard rounded == value, value <= Decimal(PayrollMoneyV2.maximumCents) / 100 else { return nil }
        let scaled = value * 100
        let result = NSDecimalNumber(decimal: scaled).int64Value
        guard Decimal(result) == scaled, PayrollMoneyV2.amount(result) != nil else { return nil }
        return result
    }
    static func parse(_ text: String) -> (valid: Bool, value: Decimal?) {
        let token = text.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: ".")
        guard !token.isEmpty else { return (true, nil) }
        guard token.count <= 32, token.range(of: #"^[0-9]+(?:\.[0-9]{1,2})?$"#, options: .regularExpression) != nil,
              let value = Decimal(string: token, locale: Locale(identifier: "en_US_POSIX")), cents(value) != nil else { return (false, nil) }
        return (true, value)
    }
    static func valid(_ record: Record) -> Bool {
        let values = Field.allCases.compactMap { record[$0] }
        return record.version == 1 && validScope(companyId: record.companyId, period: record.period) &&
            !record.source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            record.source.utf16.count <= 500 && record.confirmedAt > 0 &&
            !values.isEmpty && values.allSatisfy { cents($0) != nil } &&
            (!record.adjustmentsComplete || record.adjustments.allSatisfy { $0 != nil })
    }
    private static func key(_ companyId: String, _ period: YearMonthV2) -> String { "\(prefix)\(companyId)|\(period)" }

    static func read(companyId: String, period: YearMonthV2, defaults: UserDefaults = SalaryAccountStorageV2.currentDefaults) -> Result {
        let defaults = SalaryAccountStorageV2.scoped(defaults)
        return SalaryCompanyStoreV2.withConfirmedCompany(companyId: companyId, defaults: defaults, unavailable: Result.unavailable) {
            lock.lock(); defer { lock.unlock() }
            return SalaryAccountStorageV2.readConsistently(defaults, unavailable: Result.unavailable) {
                guard validScope(companyId: companyId, period: period) else { return .unavailable }
                guard let raw = defaults.object(forKey: key(companyId, period)) else { return .missing }
                guard let fingerprint = revision(raw) else { return .unavailable }
                guard let data = raw as? Data, let record = decode(data),
                      record.companyId == companyId, record.period == period else {
                    return .init(record: nil, reliable: false, warnings: Result.unavailable.warnings, revision: fingerprint)
                }
                return .init(record: record, reliable: true, warnings: [], revision: fingerprint)
            }
        }
    }

    private static func revision(_ object: Any) -> String? {
        // Binary plist dictionary order is not canonical across UserDefaults reads.
        // Frame typed values instead: dictionary keys sorted by their exact UTF-8 bytes,
        // array order retained, and scalar types never conflated (e.g. true, 1, "1").
        let maximumBytes = 1_048_576
        let maximumNodes = 4096
        var data = Data()
        var nodes = 0
        func token(_ kind: String, _ bytes: Data) -> Bool {
            let prefix = Data("\(kind):\(bytes.count):".utf8)
            guard bytes.count <= maximumBytes, data.count <= maximumBytes - bytes.count,
                  prefix.count <= maximumBytes - data.count - bytes.count else { return false }
            data.append(prefix); data.append(bytes)
            return true
        }
        func text(_ kind: String, _ value: String) -> Bool {
            guard value.utf8.count <= maximumBytes else { return false }
            return token(kind, Data(value.utf8))
        }
        func visit(_ value: Any, depth: Int) -> Bool {
            guard depth <= 16, nodes < maximumNodes else { return false }
            nodes += 1
            if let bytes = value as? Data { return token("data", bytes) }
            if let string = value as? String { return text("string", string) }
            if let date = value as? Date {
                let interval = date.timeIntervalSinceReferenceDate
                return interval.isFinite && text("date", String(interval.bitPattern, radix: 16))
            }
            if let number = value as? NSNumber {
                if CFGetTypeID(number) == CFBooleanGetTypeID() {
                    return text("bool", number.boolValue ? "true" : "false")
                }
                if let decimal = number as? NSDecimalNumber {
                    guard !decimal.decimalValue.isNaN else { return false }
                    return text("decimal", decimal.stringValue)
                }
                switch String(cString: number.objCType) {
                case "c", "s", "i", "l", "q", "C", "S", "I", "L", "Q":
                    return text("integer", number.stringValue)
                case "f", "d":
                    let real = number.doubleValue
                    return real.isFinite && text("real", String(real.bitPattern, radix: 16))
                default:
                    return false
                }
            }
            if let dictionary = value as? NSDictionary {
                guard dictionary.count <= maximumNodes else { return false }
                var entries: [(key: String, value: Any)] = []
                var keyBytes = 0
                for rawKey in dictionary.allKeys {
                    guard let key = rawKey as? String, let item = dictionary.object(forKey: rawKey),
                          key.utf8.count <= maximumBytes - keyBytes else { return false }
                    keyBytes += key.utf8.count
                    entries.append((key, item))
                }
                entries.sort { $0.key.utf8.lexicographicallyPrecedes($1.key.utf8) }
                guard text("dictionary-count", String(entries.count)) else { return false }
                for entry in entries {
                    guard text("key", entry.key), visit(entry.value, depth: depth + 1) else { return false }
                }
                return true
            }
            if let array = value as? NSArray {
                guard array.count <= maximumNodes, text("array-count", String(array.count)) else { return false }
                for item in array {
                    guard visit(item, depth: depth + 1) else { return false }
                }
                return true
            }
            return false
        }
        guard visit(object, depth: 0) else { return nil }
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
    static func save(_ record: Record, confirmed: Bool, expected: Result? = nil, defaults: UserDefaults = SalaryAccountStorageV2.currentDefaults) -> Bool {
        let defaults = SalaryAccountStorageV2.scoped(defaults)
        return SalaryCompanyStoreV2.withConfirmedCompany(companyId: record.companyId, defaults: defaults, unavailable: false) {
            lock.lock(); defer { lock.unlock() }
            let current = read(companyId: record.companyId, period: record.period, defaults: defaults)
            guard confirmed, valid(record), current.reliable, expected == nil || expected == current,
                  let data = encode(record), SalaryAccountStorageV2.available(defaults) else { return false }
            defaults.set(data, forKey: key(record.companyId, record.period))
            return read(companyId: record.companyId, period: record.period, defaults: defaults).record == record
        }
    }

    static func remove(companyId: String, period: YearMonthV2, expected: Result? = nil, defaults: UserDefaults = SalaryAccountStorageV2.currentDefaults) -> Bool {
        let defaults = SalaryAccountStorageV2.scoped(defaults)
        return SalaryCompanyStoreV2.withConfirmedCompany(companyId: companyId, defaults: defaults, unavailable: false) {
            lock.lock(); defer { lock.unlock() }
            let current = read(companyId: companyId, period: period, defaults: defaults)
            guard validScope(companyId: companyId, period: period), SalaryAccountStorageV2.available(defaults),
                  current.reliable || current.revision != nil,
                  expected == nil || expected == current else { return false }
            defaults.removeObject(forKey: key(companyId, period))
            return SalaryAccountStorageV2.available(defaults) && defaults.object(forKey: key(companyId, period)) == nil
        }
    }

    static func project(_ result: Result?, companyId: String?, period: YearMonthV2,
                        netAfterIncomeTax: Double?, baseReliable: Bool) -> Projection {
        guard let result else { return .init(record: nil, projectedPaid: nil, observedMinusProjected: nil, warnings: []) }
        guard result.reliable else { return .init(record: nil, projectedPaid: nil, observedMinusProjected: nil, warnings: result.warnings) }
        guard let record = result.record else { return .init(record: nil, projectedPaid: nil, observedMinusProjected: nil, warnings: []) }
        guard valid(record), record.companyId == companyId, record.period == period else {
            return .init(record: nil, projectedPaid: nil, observedMinusProjected: nil, warnings: ["Règlement mensuel : entreprise ou mois non concordant."])
        }
        let unknown = Projection(record: record, projectedPaid: nil, observedMinusProjected: nil,
                                 warnings: ["Versement calculé à confirmer : net après PAS non écrêté et ajustements exhaustifs requis. Les montants constatés restent distincts du calcul."])
        guard baseReliable, record.adjustmentsComplete, let base = netAfterIncomeTax, base >= 0,
              let baseCents = PayrollMoneyV2.cents(base),
              let transport = record.transportReimbursement.flatMap(cents),
              let garnishment = record.garnishment.flatMap(cents),
              let addition = record.otherAddition.flatMap(cents),
              let deduction = record.otherDeduction.flatMap(cents),
              let paidCents = PayrollMoneyV2.sum([baseCents, transport, addition, -garnishment, -deduction]),
              paidCents >= 0, let paid = PayrollMoneyV2.amount(paidCents) else { return unknown }
        let difference = record.netPaidObserved.flatMap(cents).flatMap { PayrollMoneyV2.sum([$0, -paidCents]) }.flatMap(PayrollMoneyV2.amount)
        return .init(record: record, projectedPaid: paid, observedMinusProjected: difference, warnings: [])
    }

    // A small flat JSON boundary preserves original tokens, rejects duplicates and future fields,
    // and prevents Decimal's finite precision from silently removing a fractional cent.
    private struct Token {
        let raw: String
        var string: String? { try? JSONDecoder().decode(String.self, from: Data(raw.utf8)) }
    }
    private static func tokens(_ data: Data) -> [String: Token]? {
        guard data.count <= 16_384, let text = String(data: data, encoding: .utf8) else { return nil }
        let chars = Array(text)
        var index = 0
        func space() { while index < chars.count && [" ", "\r", "\n", "\t"].contains(chars[index]) { index += 1 } }
        func stringToken() -> String? {
            guard index < chars.count, chars[index] == "\"" else { return nil }
            let start = index
            index += 1
            while index < chars.count {
                if chars[index] == "\\" { index += 2; continue }
                if chars[index] == "\"" { index += 1; return String(chars[start..<index]) }
                index += 1
            }
            return nil
        }
        space()
        guard index < chars.count, chars[index] == "{" else { return nil }
        index += 1; space()
        var fields: [String: Token] = [:]
        while index < chars.count, chars[index] != "}" {
            guard let rawKey = stringToken(), let name = Token(raw: rawKey).string, fields[name] == nil else { return nil }
            space(); guard index < chars.count, chars[index] == ":" else { return nil }
            index += 1; space()
            let raw: String
            if index < chars.count && chars[index] == "\"" {
                guard let value = stringToken(), Token(raw: value).string != nil else { return nil }; raw = value
            } else {
                let start = index
                while index < chars.count && ![",", "}", " ", "\r", "\n", "\t"].contains(chars[index]) { index += 1 }
                raw = String(chars[start..<index])
                guard !raw.isEmpty else { return nil }
            }
            fields[name] = Token(raw: raw)
            space(); guard index < chars.count else { return nil }
            if chars[index] == "}" { break }
            guard chars[index] == "," else { return nil }
            index += 1; space()
            guard index < chars.count, chars[index] != "}" else { return nil }
        }
        guard index < chars.count, chars[index] == "}" else { return nil }
        index += 1; space()
        return index == chars.count ? fields : nil
    }
    static func decode(_ data: Data) -> Record? {
        guard let fields = tokens(data) else { return nil }
        let required: Set<String> = ["version", "companyId", "period", "source", "confirmedAt", "adjustmentsComplete"]
        let allowed = required.union(Field.allCases.map(\.rawValue))
        guard Set(fields.keys) == allowed,
              fields["version"]?.raw == "1", let companyId = fields["companyId"]?.string,
              let periodText = fields["period"]?.string,
              periodText.range(of: #"^[0-9]{4}-[0-9]{2}$"#, options: .regularExpression) != nil,
              let year = Int(periodText.prefix(4)), let month = Int(periodText.suffix(2)),
              let period = YearMonthV2(year: year, month: month),
              let source = fields["source"]?.string, let timeToken = fields["confirmedAt"]?.raw,
              timeToken.range(of: #"^[1-9][0-9]{0,18}$"#, options: .regularExpression) != nil,
              let confirmedAt = Int64(timeToken), let complete = fields["adjustmentsComplete"]?.raw,
              ["true", "false"].contains(complete) else { return nil }
        var amounts: [Field: Decimal] = [:]
        for field in Field.allCases {
            guard let token = fields[field.rawValue], token.raw != "null" else { continue }
            guard token.raw.count <= 32,
                  token.raw.range(of: #"^(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$"#, options: .regularExpression) != nil,
                  let value = Decimal(string: token.raw, locale: Locale(identifier: "en_US_POSIX")),
                  cents(value) != nil else { return nil }
            amounts[field] = value
        }
        let record = Record(companyId: companyId, period: period,
                            transportReimbursement: amounts[.transportReimbursement], garnishment: amounts[.garnishment],
                            otherAddition: amounts[.otherAddition], otherDeduction: amounts[.otherDeduction],
                            netSocialObserved: amounts[.netSocialObserved], netPaidObserved: amounts[.netPaidObserved],
                            source: source, confirmedAt: confirmedAt, adjustmentsComplete: complete == "true")
        return valid(record) ? record : nil
    }
    static func encode(_ record: Record) -> Data? {
        guard valid(record) else { return nil }
        func quoted(_ text: String) -> String {
            String(data: try! JSONEncoder().encode(text), encoding: .utf8)!
        }
        var fields = ["\"version\":1", "\"companyId\":\(quoted(record.companyId))",
                      "\"period\":\(quoted(record.period.description))", "\"source\":\(quoted(record.source))",
                      "\"confirmedAt\":\(record.confirmedAt)", "\"adjustmentsComplete\":\(record.adjustmentsComplete)"]
        for field in Field.allCases {
            fields.append("\"\(field.rawValue)\":\(record[field].map { NSDecimalNumber(decimal: $0).stringValue } ?? "null")")
        }
        let data = Data(("{" + fields.joined(separator: ",") + "}").utf8)
        return decode(data) == record ? data : nil
    }
}
