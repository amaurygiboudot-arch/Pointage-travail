import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryConfirmedCashGrossComponentsBridgeV2Tests: XCTestCase {
    private var period: YearMonthV2 {
        YearMonthV2(year: 2026, month: 9)!
    }

    func testConfirmedZeroSourcesProduceReliableZero() {
        let result = SalaryConfirmedCashGrossComponentsBridgeV2.assemble(
            period: period,
            seniority: seniority(amount: 0),
            companyPremiums: premiums([], reliable: true),
            coverage: coverage(confirmed: true, source: "user-confirmed")
        )

        XCTAssertTrue(result.exhaustive)
        XCTAssertFalse(result.sourceId.isEmpty)
        XCTAssertEqual(result.components.count, 1)
        XCTAssertEqual(result.components[0].amount, 0, accuracy: 0.0001)
    }

    func testConfirmedCompanyPremiumsKeepStableIdsAndAmounts() {
        let result = SalaryConfirmedCashGrossComponentsBridgeV2.assemble(
            period: period,
            seniority: seniority(amount: 24),
            companyPremiums: premiums([
                .init(id: "team", label: "Équipe", grossAmount: 100),
                .init(id: "tool", label: "Outillage", grossAmount: 15)
            ], reliable: true),
            coverage: coverage(confirmed: true, source: "bulletin-confirmed")
        )

        XCTAssertTrue(result.exhaustive)
        XCTAssertEqual(
            result.components.map(\.id),
            ["seniority-premium", "company-premium:team", "company-premium:tool"]
        )
        XCTAssertEqual(result.components.reduce(0) { $0 + $1.amount }, 139, accuracy: 0.0001)
    }

    func testMissingPremiumCoverageNeverBecomesImplicitZero() {
        let result = SalaryConfirmedCashGrossComponentsBridgeV2.assemble(
            period: period,
            seniority: seniority(amount: 0),
            companyPremiums: premiums([], reliable: false),
            coverage: coverage(confirmed: false, source: nil)
        )

        XCTAssertFalse(result.exhaustive)
        XCTAssertTrue(result.sourceId.isEmpty)
        XCTAssertEqual(result.components.map(\.id), ["seniority-premium"])
    }

    func testUnknownSeniorityBlocksWholeFixedPackage() {
        let result = SalaryConfirmedCashGrossComponentsBridgeV2.assemble(
            period: period,
            seniority: seniority(amount: nil, reliable: false),
            companyPremiums: premiums([], reliable: true),
            coverage: coverage(confirmed: true, source: "user-confirmed")
        )

        XCTAssertFalse(result.exhaustive)
        XCTAssertTrue(result.sourceId.isEmpty)
        XCTAssertTrue(result.components.isEmpty)
    }

    private func seniority(
        amount: Double?,
        reliable: Bool = true
    ) -> SalaryConventionSeniorityPremiumV2.Result {
        .init(
            applicable: amount != nil,
            reliable: reliable,
            selectedRule: nil,
            stepYears: nil,
            rate: amount == nil ? nil : 0.024,
            monthlyAmount: amount,
            warnings: reliable ? [] : ["seniority-unconfirmed"]
        )
    }

    private func premiums(
        _ applied: [CompanyPremiumContractV2.Applied],
        reliable: Bool
    ) -> CompanyPremiumContractV2.Snapshot {
        .init(
            applied: applied,
            totalGross: applied.reduce(0) { $0 + $1.grossAmount },
            reliable: reliable,
            warnings: reliable ? [] : ["premiums-unconfirmed"]
        )
    }

    private func coverage(
        confirmed: Bool,
        source: String?
    ) -> CompanyPremiumStoreV2.MonthCoverageSnapshot {
        .init(
            confirmed: confirmed,
            source: source,
            storageReliable: true,
            warnings: confirmed ? [] : ["coverage-unconfirmed"]
        )
    }
}
