import Foundation

struct SalaryPayslipPeriodDraftV2 {
    let year: Int
    let month: Int
    let source: String
    var description: String { String(format: "%02d/%04d", month, year) }
}
/// Only explicitly labelled bulletin months. Payment dates never determine payroll period.
enum SalaryPayslipPeriodParserV2 {
    static func parse(pages: [String]) -> SalaryPayslipPeriodDraftV2? {
        guard pages.count <= 10,
              let label = try? NSRegularExpression(pattern: #"^\s*(?:periode|mois)(?:\s+(?:de\s+paie|du\s+bulletin))?\s*[:=]\s*(.*)$"#),
              let period = try? NSRegularExpression(pattern: #"^(0?[1-9]|1[0-2])[/.-](20\d{2})$"#) else { return nil }
        var found: [SalaryPayslipPeriodDraftV2] = []
        var invalid = false
        for (page, text) in pages.enumerated() {
            guard text.count <= 30_000 else { return nil }
            for (index, original) in text.components(separatedBy: .newlines).enumerated() {
                let line = original.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "fr_FR"))
                let ns = line as NSString
                guard let labelled = label.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)) else { continue }
                let value = ns.substring(with: labelled.range(at: 1)).trimmingCharacters(in: .whitespacesAndNewlines)
                let vn = value as NSString
                guard let match = period.firstMatch(in: value, range: NSRange(location: 0, length: vn.length)),
                      let month = Int(vn.substring(with: match.range(at: 1))),
                      let year = Int(vn.substring(with: match.range(at: 2))) else { invalid = true; continue }
                found.append(.init(year: year, month: month, source: "Page \(page + 1), ligne \(index + 1) — \(original.prefix(180))"))
            }
        }
        guard !invalid, let first = found.first,
              found.allSatisfy({ $0.year == first.year && $0.month == first.month }) else { return nil }
        return first
    }
}
