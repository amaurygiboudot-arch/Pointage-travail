import Foundation

/// Local extraction proposals only. Never payroll rules or confirmed amounts.
enum SalaryPayslipImportFieldV2: String, CaseIterable, Identifiable {
    case gross, netBeforeTax, netTaxable, incomeTax, netAfterTax
    var id: String { rawValue }
    var label: String {
        switch self {
        case .gross: return "Brut social"
        case .netBeforeTax: return "Net avant impôt"
        case .netTaxable: return "Net imposable"
        case .incomeTax: return "Prélèvement à la source"
        case .netAfterTax: return "Net après impôt"
        }
    }
}
struct SalaryPayslipImportProposalV2: Identifiable {
    var id: String { field.rawValue }
    let field: SalaryPayslipImportFieldV2
    let amount: Decimal
    let source: String
}
enum SalaryPayslipImportParserV2 {
    static func parse(pages: [String]) -> [SalaryPayslipImportProposalV2] {
        var blocked = Set<SalaryPayslipImportFieldV2>()
        var candidates: [SalaryPayslipImportFieldV2: [SalaryPayslipImportProposalV2]] = [:]
        let pattern = #"(?<![\d.,])[-+]?\d{1,3}(?:[ \u00a0\u202f]\d{3})*[,.]\d{2}(?!\d)|(?<![\d.,])[-+]?\d+[,.]\d{2}(?!\d)"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        for (page, text) in pages.prefix(10).enumerated() {
            for (lineNumber, original) in text.components(separatedBy: .newlines).enumerated() {
                let line = original.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "fr_FR"))
                guard !line.contains("cumul"), !line.contains("annuel") else { continue }
                let field: SalaryPayslipImportFieldV2?
                if line.contains("net a payer avant impot") || line.contains("net avant impot") { field = .netBeforeTax }
                else if line.contains("net imposable") || line.contains("net fiscal") { field = .netTaxable }
                else if line.contains("prelevement a la source") || line.contains("impot sur le revenu preleve") { field = .incomeTax }
                else if (line.contains("net a payer") && !line.contains("avant")) || line.contains("net apres impot") { field = .netAfterTax }
                else if (line.contains("brut social") || line.contains("total brut") || line.contains("brut mensuel")) && !line.contains("horaire") && !line.contains("de base") { field = .gross }
                else { field = nil }
                guard let field else { continue }
                // Fail closed for alternate negative/accounting notation rather than
                // letting the unsigned amount regex turn a debit into a positive total.
                if original.range(of: #"[-−﹣－–—]\s*\d"#, options: .regularExpression) != nil || original.contains("−") || original.contains("﹣") || original.contains("－") ||
                    original.contains("(") || original.contains(")") {
                    blocked.insert(field)
                    continue
                }
                let ns = original as NSString
                let matches = regex.matches(in: original, range: NSRange(location: 0, length: ns.length))
                // A table row with bases/rates/cumuls is ambiguous: do not pick a last number.
                guard matches.count == 1, !line.contains("%") else { blocked.insert(field); continue }
                let raw = ns.substring(with: matches[0].range).replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "\u{00a0}", with: "").replacingOccurrences(of: "\u{202f}", with: "").replacingOccurrences(of: ",", with: ".")
                guard let amount = Decimal(string: raw, locale: Locale(identifier: "en_US_POSIX")), amount >= 0 else { blocked.insert(field); continue }
                candidates[field, default: []].append(.init(field: field, amount: amount, source: "Page \(page + 1), ligne \(lineNumber + 1) — \(original.prefix(160))"))
            }
        }
        return SalaryPayslipImportFieldV2.allCases.compactMap { field in
            guard !blocked.contains(field), let values = candidates[field], values.count == 1 else { return nil }
            return values[0]
        }
    }
}
