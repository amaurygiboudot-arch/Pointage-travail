import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryV2WorkspaceStateTests: XCTestCase {
    private let month = YearMonthV2(year: 2026, month: 9)!

    func testMissingUpstreamSourceNeverCreatesSalaryAmounts() {
        let snapshot = SalaryWorkspaceResolverV2.resolve(period: month, reference: nil)

        XCTAssertFalse(snapshot.sourceReady)
        XCTAssertNil(snapshot.socialGross)
        XCTAssertNil(snapshot.netBeforeIncomeTax)
        XCTAssertNil(snapshot.netTaxable)
        XCTAssertEqual(snapshot.warnings, [SalaryWorkspaceResolverV2.upstreamUnavailableWarning])
    }

    func testBlockedSocialGrossCannotAppearInWorkspace() {
        let reference = SalaryReferenceContractV2(
            gross: 2_500,
            grossReliable: false,
            netBeforeIncomeTax: 2_000,
            netTaxable: 2_050,
            complete: true,
            warnings: [],
            benefitsInKindDeduction: 0
        )

        let snapshot = SalaryWorkspaceResolverV2.resolve(period: month, reference: reference)

        XCTAssertTrue(snapshot.sourceReady)
        XCTAssertNil(snapshot.socialGross)
        XCTAssertNil(snapshot.netBeforeIncomeTax)
        XCTAssertNil(snapshot.netTaxable)
        XCTAssertTrue(snapshot.warnings.contains { $0.hasPrefix("Brut social :") })
    }

    func testReliableCanonicalReferenceIsDisplayedWithoutRecalculation() {
        let benefits = CompanyBenefitInKindContractV2.Snapshot(
            applied: [],
            totalGross: 0,
            reliable: true,
            warnings: []
        )
        let reference = SalaryReferenceContractV2.build(
            cashGross: 2_500,
            benefits: benefits,
            netBeforeIncomeTax: 2_000,
            netTaxable: 2_050
        )

        let snapshot = SalaryWorkspaceResolverV2.resolve(period: month, reference: reference)

        XCTAssertNotNil(snapshot.socialGross)
        XCTAssertNotNil(snapshot.netBeforeIncomeTax)
        XCTAssertNotNil(snapshot.netTaxable)
        XCTAssertEqual(snapshot.socialGross ?? -1, 2_500, accuracy: 0.001)
        XCTAssertEqual(snapshot.netBeforeIncomeTax ?? -1, 2_000, accuracy: 0.001)
        XCTAssertEqual(snapshot.netTaxable ?? -1, 2_050, accuracy: 0.001)
        XCTAssertTrue(snapshot.warnings.contains { $0.contains("PAS") })
    }

    func testIncompleteReferenceMayExposeReliableGrossButKeepsNetBlocked() {
        let benefits = CompanyBenefitInKindContractV2.Snapshot(
            applied: [],
            totalGross: 0,
            reliable: true,
            warnings: []
        )
        let reference = SalaryReferenceContractV2.build(
            cashGross: 2_500,
            benefits: benefits,
            netBeforeIncomeTax: 2_000,
            netTaxable: 2_050,
            additionalWarnings: ["Cotisation salariale à confirmer"]
        )

        let snapshot = SalaryWorkspaceResolverV2.resolve(period: month, reference: reference)

        XCTAssertNotNil(snapshot.socialGross)
        XCTAssertEqual(snapshot.socialGross ?? -1, 2_500, accuracy: 0.001)
        XCTAssertNil(snapshot.netBeforeIncomeTax)
        XCTAssertNil(snapshot.netTaxable)
        XCTAssertTrue(snapshot.warnings.contains("Cotisation salariale à confirmer"))
    }
}
