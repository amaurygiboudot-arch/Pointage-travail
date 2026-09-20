import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryV2ReferenceInvalidNetTests: XCTestCase {
    private let month = YearMonthV2(year: 2026, month: 9)!

    private var confirmedBenefits: CompanyBenefitInKindContractV2.Snapshot {
        CompanyBenefitInKindContractV2.resolve(
            records: .init(records: [], reliable: true, warnings: []),
            confirmations: .init(
                confirmations: [.init(period: month, source: "Bulletin du mois")],
                reliable: true,
                warnings: []
            ),
            period: month
        )
    }

    func testInvalidNetBeforeIncomeTaxNeverKeepsReferenceComplete() {
        for invalid in [Double.nan, Double.infinity, -1.0] {
            let reference = SalaryReferenceContractV2.build(
                cashGross: 2_500,
                benefits: confirmedBenefits,
                netBeforeIncomeTax: invalid,
                netTaxable: 2_050
            )

            XCTAssertFalse(reference.complete)
            XCTAssertNil(SalaryReferenceContractV2.beforeIncomeTax(reference))
            XCTAssertEqual(reference.netBeforeIncomeTax, 0, accuracy: 0.001)
            XCTAssertTrue(reference.warnings.contains { $0.hasPrefix("Net avant impôt :") })
        }
    }

    func testInvalidTaxableNetIsRemovedAndReferenceBecomesIncomplete() {
        for invalid in [Double.nan, Double.infinity, -1.0] {
            let reference = SalaryReferenceContractV2.build(
                cashGross: 2_500,
                benefits: confirmedBenefits,
                netBeforeIncomeTax: 2_000,
                netTaxable: invalid
            )

            XCTAssertFalse(reference.complete)
            XCTAssertNil(reference.netTaxable)
            XCTAssertNil(SalaryReferenceContractV2.taxable(reference))
            XCTAssertTrue(reference.warnings.contains { $0.hasPrefix("Net imposable :") })
        }
    }

    func testMissingTaxableNetRemainsDifferentFromInvalidTaxableNet() {
        let reference = SalaryReferenceContractV2.build(
            cashGross: 2_500,
            benefits: confirmedBenefits,
            netBeforeIncomeTax: 2_000,
            netTaxable: nil
        )

        XCTAssertTrue(reference.complete)
        XCTAssertEqual(SalaryReferenceContractV2.beforeIncomeTax(reference) ?? -1, 2_000, accuracy: 0.001)
        XCTAssertNil(SalaryReferenceContractV2.taxable(reference))
        XCTAssertFalse(reference.warnings.contains { $0.hasPrefix("Net imposable :") })
    }
}
