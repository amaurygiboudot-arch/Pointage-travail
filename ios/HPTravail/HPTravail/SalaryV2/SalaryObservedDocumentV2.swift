import Foundation
import CryptoKit

/// A transcription of a source document, never a payroll calculation.
enum SalaryObservedDocumentV2 {
    struct Line: Codable, Equatable, Identifiable {
        var id: String = UUID().uuidString
        var label: String
        var base: String? = nil
        var employerBase: String? = nil
        var employeeRate: String? = nil
        var employeeAmount: String? = nil
        var employerRate: String? = nil
        var employerAmount: String? = nil
    }
    struct Document: Codable, Equatable {
        var sourcePeriod: YearMonthV2
        var source: String
        var confirmedAt: Date
        var lines: [Line]
        var aggregateFingerprint: String = ""
    }
    struct Row: Equatable {
        let label: String
        let value: String
    }
    static let notice = "Montants constatés sur le document, sans recalcul. Les cellules absentes restent non renseignées. Les lignes et totaux ne sont jamais additionnés entre eux. Cette transcription ne valide ni les pointages ni le calcul de salaire."
    static func period(_ text: String) -> YearMonthV2? {
        let parts = text.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2, parts[0].count == 2, parts[1].count == 4,
              parts.allSatisfy({ $0.utf8.allSatisfy { (48...57).contains($0) } }),
              let month = Int(parts[0]), let year = Int(parts[1]), (1900...9999).contains(year) else { return nil }
        return YearMonthV2(year: year, month: month)
    }
    static func periodText(_ period: YearMonthV2) -> String { String(format: "%02d/%04d", period.month, period.year) }
    /// Bounds and precision are checked before conversion, including negative reductions.
    static func cell(_ text: String, money: Bool) -> String? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: ".")
        let digits = money ? 2 : 4
        guard value.count <= 32,
              value.range(of: "^-?[0-9]+(?:\\.[0-9]{1,\(digits)})?$", options: .regularExpression) != nil,
              let number = Decimal(string: value, locale: Locale(identifier: "en_US_POSIX")),
              !number.isNaN, abs(number) <= Decimal(1_000_000_000) else { return nil }
        return NSDecimalNumber(decimal: number).stringValue
    }
    static func singleLine(_ text: String, limit: Int) -> Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && text.utf16.count <= limit &&
        !text.unicodeScalars.contains { CharacterSet.controlCharacters.contains($0) || CharacterSet.newlines.contains($0) }
    }
    static func valid(_ document: Document, period: YearMonthV2) -> Bool {
        guard document.sourcePeriod == period, singleLine(document.source, limit: 500), document.confirmedAt.timeIntervalSince1970.isFinite,
              document.confirmedAt.timeIntervalSince1970 > 0, (1...150).contains(document.lines.count),
              Set(document.lines.map(\.id)).count == document.lines.count else { return false }
        return document.lines.allSatisfy { line in
            guard singleLine(line.id, limit: 100), singleLine(line.label, limit: 160) else { return false }
            let cells: [(String?, Bool)] = [(line.base, false), (line.employerBase, false), (line.employeeRate, false), (line.employeeAmount, true), (line.employerRate, false), (line.employerAmount, true)]
            return cells.contains { $0.0 != nil } && cells.allSatisfy { value, money in value.map { cell($0, money: money) == $0 } ?? true }
        }
    }
    static func fingerprint(amounts: [String: Double], excerpts: [String: String]) -> String {
        struct Snapshot: Encodable { let amounts: [String: Double]; let excerpts: [String: String] }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(Snapshot(amounts: amounts, excerpts: excerpts)) else { return "unavailable" }
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
    static func current(_ record: SalaryPayslipObservedStoreV2.Record) -> Bool {
        guard let document = record.detailedDocument else { return false }
        return document.aggregateFingerprint == fingerprint(amounts: record.amounts, excerpts: record.excerpts)
    }
    static func rows(_ document: Document) -> [Row] {
        document.lines.map { line in
            let values: [(String, String?)] = [("Base salarié", line.base), ("Base employeur", line.employerBase), ("Taux salarié (%)", line.employeeRate), ("Montant salarié (€)", line.employeeAmount), ("Taux employeur (%)", line.employerRate), ("Montant employeur (€)", line.employerAmount)]
            return Row(label: line.label, value: values.map { "\($0.0) : \($0.1?.replacingOccurrences(of: ".", with: ",") ?? "Non renseigné")" }.joined(separator: "\n"))
        }
    }
}
