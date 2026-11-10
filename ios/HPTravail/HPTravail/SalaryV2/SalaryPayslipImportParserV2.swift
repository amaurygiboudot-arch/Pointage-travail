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
    struct ParseResult {
        let proposals: [SalaryPayslipImportProposalV2]
        let hasUnrepresentableAmounts: Bool
    }

    static func parse(pages: [String]) -> [SalaryPayslipImportProposalV2] {
        let result = parseChecked(pages: pages)
        return result.hasUnrepresentableAmounts ? [] : result.proposals
    }

    static func parseChecked(pages: [String]) -> ParseResult {
        var hasUnrepresentableAmounts = false
        var blocked = Set<SalaryPayslipImportFieldV2>()
        var candidates: [SalaryPayslipImportFieldV2: [SalaryPayslipImportProposalV2]] = [:]
        // Extract whole numeric spans before validating their monetary grammar.
        // Matching only a valid suffix can turn malformed grouping into a smaller amount.
        let tokenPattern = #"[-+−﹣－–—.,/0-9][-+−﹣－–—.,/'’:_0-9\h]*"#
        let amountPattern = #"[+]?(?:[0-9]+|[0-9]{1,3}(?:[ \u00a0\u202f][0-9]{3})+)[,.][0-9]{2}"#
        guard let regex = try? NSRegularExpression(pattern: tokenPattern),
              let singleAmount = try? NSRegularExpression(pattern: "^(?:" + amountPattern + ")$"),
              let multipleAmounts = try? NSRegularExpression(pattern: "^(?:" + amountPattern + #")(?:\h+"# + amountPattern + ")+$")
        else { return ParseResult(proposals: [], hasUnrepresentableAmounts: false) }
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
                    hasUnrepresentableAmounts = true
                    blocked.insert(field)
                    continue
                }
                // Rate/base rows are outside the unambiguous monthly-total scope.
                guard !line.contains("%") else { blocked.insert(field); continue }
                let ns = original as NSString
                let matches = regex.matches(in: original, range: NSRange(location: 0, length: ns.length))
                var monetaryTokens: [String] = []
                var ambiguous = false
                var malformed = false
                for match in matches {
                    let token = ns.substring(with: match.range).trimmingCharacters(in: .whitespaces)
                    guard token.rangeOfCharacter(from: .decimalDigits) != nil,
                          token.contains(",") || token.contains(".") else { continue }
                    // A fragment inside an alphanumeric/scientific token is not a total.
                    let start = match.range.location
                    let end = start + (token as NSString).length
                    let left = start > 0 ? ns.substring(with: NSRange(location: start - 1, length: 1)) : ""
                    let right = end < ns.length ? ns.substring(with: NSRange(location: end, length: 1)) : ""
                    if (left + right).rangeOfCharacter(from: .alphanumerics) != nil {
                        malformed = true
                        continue
                    }
                    let range = NSRange(location: 0, length: (token as NSString).length)
                    if singleAmount.firstMatch(in: token, range: range)?.range == range {
                        monetaryTokens.append(token)
                    } else if multipleAmounts.firstMatch(in: token, range: range)?.range == range {
                        ambiguous = true
                    } else {
                        malformed = true
                    }
                }
                if malformed {
                    hasUnrepresentableAmounts = true
                    blocked.insert(field)
                    continue
                }
                // Several complete amounts remain ambiguous; never choose a column.
                guard monetaryTokens.count == 1, !ambiguous else { blocked.insert(field); continue }
                let raw = monetaryTokens[0].replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "\u{00a0}", with: "").replacingOccurrences(of: "\u{202f}", with: "").replacingOccurrences(of: ",", with: ".")
                guard let amount = Decimal(string: raw, locale: Locale(identifier: "en_US_POSIX")),
                      SalaryPayslipAmountV2.importedAmount(amount) != nil else {
                    hasUnrepresentableAmounts = true
                    blocked.insert(field)
                    continue
                }
                candidates[field, default: []].append(.init(field: field, amount: amount, source: "Page \(page + 1), ligne \(lineNumber + 1) — \(original.prefix(160))"))
            }
        }
        let proposals = SalaryPayslipImportFieldV2.allCases.compactMap { field -> SalaryPayslipImportProposalV2? in
            guard !blocked.contains(field), let values = candidates[field], values.count == 1 else { return nil }
            return values[0]
        }
        return ParseResult(proposals: proposals, hasUnrepresentableAmounts: hasUnrepresentableAmounts)
    }
}
