import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryConfirmedCashGrossComponentsResolverV2Tests: XCTestCase {
    func testReliableApplicableFactsBecomeExhaustiveComponents() {
        let result = SalaryConfirmedCashGrossComponentsResolverV2.resolve(
            facts: [
                .init(id: "seniority", amount: 50, applicable: true, reliable: true),
                .init(id: "company-premium", amount: 25, applicable: true, reliable: true)
            ],
            exhaustive: true,
            sourceId: "fixed-stores"
        )
        XCTAssertTrue(result.exhaustive)
        XCTAssertEqual(result.components.map(\.id), ["seniority", "company-premium"])
        XCTAssertEqual(result.components.reduce(0) { $0 + $1.amount }, 75, accuracy: 0.0001)
    }

    func testConfirmedEmptyFactsCanRepresentZero() {
        let result = SalaryConfirmedCashGrossComponentsResolverV2.resolve(
            facts: [],
            exhaustive: true,
            sourceId: "fixed-stores-empty"
        )
        XCTAssertTrue(result.exhaustive)
        XCTAssertTrue(result.components.isEmpty)
    }

    func testUnknownApplicableFactNeverBecomesZero() {
        let result = SalaryConfirmedCashGrossComponentsResolverV2.resolve(
            facts: [
                .init(id: "seniority", amount: nil, applicable: true, reliable: false, warnings: ["à confirmer"])
            ],
            exhaustive: true,
            sourceId: "fixed-stores"
        )
        XCTAssertFalse(result.exhaustive)
        XCTAssertTrue(result.components.isEmpty)
        XCTAssertTrue(result.warnings.contains(SalaryConfirmedCashGrossComponentsResolverV2.factWarning))
    }

    func testDuplicateIdsBlockCoverage() {
        let result = SalaryConfirmedCashGrossComponentsResolverV2.resolve(
            facts: [
                .init(id: "x", amount: 10, applicable: true, reliable: true),
                .init(id: "x", amount: 20, applicable: true, reliable: true)
            ],
            exhaustive: true,
            sourceId: "fixed-stores"
        )
        XCTAssertFalse(result.exhaustive)
    }
}
