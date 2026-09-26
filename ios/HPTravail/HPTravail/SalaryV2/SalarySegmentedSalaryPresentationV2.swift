import Foundation

enum SalarySegmentedSalaryPresentationV2 {
    enum State: Equatable {
        case unreliable
        case grossAvailableNetIncomplete
        case available
    }

    struct Result {
        let state: State
        let workedGross: Double?
        let additionalCashGross: Double?
        let cashGross: Double?
        let socialGross: Double?
        let netBeforeIncomeTax: Double?
        let netTaxable: Double?
        let incomeTax: Double?
        let netAfterIncomeTax: Double?
        let contributingSessionCount: Int?
        let warnings: [String]
    }

    static func from(_ source: SalarySegmentedSalaryProductionResultV2) -> Result {
        let warnings = unique(source.warnings)
        guard source.worked.reliable, source.cash.reliable, source.net.cashGrossReliable else {
            return .init(
                state: .unreliable,
                workedGross: nil,
                additionalCashGross: nil,
                cashGross: nil,
                socialGross: nil,
                netBeforeIncomeTax: nil,
                netTaxable: nil,
                incomeTax: nil,
                netAfterIncomeTax: nil,
                contributingSessionCount: nil,
                warnings: warnings
            )
        }

        let projection = source.net.projection
        guard let projection, source.net.netBeforeIncomeTaxComplete else {
            return .init(
                state: .grossAvailableNetIncomplete,
                workedGross: source.cash.workedGross,
                additionalCashGross: source.cash.additionalCashGross,
                cashGross: source.cash.cashGross,
                socialGross: projection?.contributionGross,
                netBeforeIncomeTax: nil,
                netTaxable: nil,
                incomeTax: nil,
                netAfterIncomeTax: nil,
                contributingSessionCount: source.worked.evidence.contributingSessionIds.count,
                warnings: warnings
            )
        }

        return .init(
            state: .available,
            workedGross: source.cash.workedGross,
            additionalCashGross: source.cash.additionalCashGross,
            cashGross: source.cash.cashGross,
            socialGross: projection.contributionGross,
            netBeforeIncomeTax: projection.netBeforeIncomeTax,
            netTaxable: projection.netTaxable,
            incomeTax: projection.incomeTax,
            netAfterIncomeTax: projection.netAfterIncomeTax,
            contributingSessionCount: source.worked.evidence.contributingSessionIds.count,
            warnings: warnings
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
