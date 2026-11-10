import Foundation

/// A proposal for the existing contract form, never a saved contract/version.
struct SalaryPayslipHourlyRateDraftV2 {
    let grossHourlyRate: Decimal
    let source: String
}
enum SalaryPayslipProfileDraftParserV2 {
    static func hourlyRate(pages: [String]) -> SalaryPayslipHourlyRateDraftV2? {
        var proposals: [SalaryPayslipHourlyRateDraftV2] = []
        var blocked = false
        let pattern = #"^\s*(?:taux horaire brut|taux brut horaire)\s*[:=]?\s*(\d+(?:[,.]\d{1,4})?)\s*(?:€|eur|euros?)?\s*(?:/\s*h)?\s*$"#
        guard pages.count <= 10, let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) else { return nil }
        for (page, text) in pages.enumerated() {
            guard text.count <= 30_000 else { return nil }
            for (index, original) in text.components(separatedBy: .newlines).enumerated() {
                let line = original.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "fr_FR"))
                guard line.contains("taux horaire brut") || line.contains("taux brut horaire") else { continue }
                let ns = line as NSString
                guard let match = regex.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)),
                      let rate = Decimal(string: ns.substring(with: match.range(at: 1)).replacingOccurrences(of: ",", with: "."), locale: Locale(identifier: "en_US_POSIX")), rate > 0 else {
                    blocked = true
                    continue
                }
                proposals.append(.init(grossHourlyRate: rate, source: "Page \(page + 1), ligne \(index + 1) — \(original.prefix(160))"))
            }
        }
        guard !blocked, proposals.count == 1 else { return nil }
        return proposals[0]
    }
}
struct SalaryPayslipLocalDraftV2 {
    let observedAmounts: [SalaryPayslipImportProposalV2]
    let hourlyRate: SalaryPayslipHourlyRateDraftV2?
    let period: SalaryPayslipPeriodDraftV2?
}
