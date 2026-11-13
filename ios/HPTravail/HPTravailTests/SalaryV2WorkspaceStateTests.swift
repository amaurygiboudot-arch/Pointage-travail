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

    func testSegmentedTransitionNeverFallsBackToSingleMonthReference() {
        let benefits = CompanyBenefitInKindContractV2.Snapshot(
            applied: [], totalGross: 0, reliable: true, warnings: []
        )
        let singleMonthReference = SalaryReferenceContractV2.build(
            cashGross: 2_500,
            benefits: benefits,
            netBeforeIncomeTax: 2_000,
            netTaxable: 2_050
        )

        let segmentedMonth = SalaryWorkspaceResolverV2.resolve(
            period: month,
            requiresSegmentedSource: true,
            segmented: nil,
            reference: singleMonthReference
        )
        XCTAssertFalse(segmentedMonth.sourceReady)
        XCTAssertNil(segmentedMonth.socialGross)
        XCTAssertNil(segmentedMonth.netBeforeIncomeTax)
        XCTAssertEqual(
            segmentedMonth.warnings,
            [SalaryWorkspaceResolverV2.segmentedSourceUnavailableWarning]
        )

        let ordinaryMonth = SalaryWorkspaceResolverV2.resolve(
            period: month,
            requiresSegmentedSource: false,
            segmented: nil,
            reference: singleMonthReference
        )
        XCTAssertTrue(ordinaryMonth.sourceReady)
        XCTAssertEqual(ordinaryMonth.socialGross, 2_500)
    }
    func testDetailedPresentationUsesConfirmedLinesAndDoesNotTurnNetAfterTaxIntoPaidNet() {
        let details = SalaryCalculatedPayslipDetailsV2(
            companyId: "company-a", period: month, socialGross: 2400,
            grossLines: [.init(label: "Prime de production", amount: 42)],
            contributions: [.init(label: "Retraite", base: 2400, employeeRate: 0.04,
                                  employeeAmount: 96, employerRate: 0.06, employerAmount: 144,
                                  source: "Source synthétique datée")])
        let snapshot = SalaryWorkspaceSnapshotV2(period: month, sourceReady: true,
            socialGross: 2400, netBeforeIncomeTax: 1900, netTaxable: 1950,
            incomeTax: 0, netAfterIncomeTax: 1900, warnings: [], payslipDetails: details)
        let sections = SalaryCalculatedPayslipPresentationV2.sections(snapshot: snapshot, companyId: "company-a")
        XCTAssertTrue(sections[0].rows.contains { $0.label == "Prime de production" && $0.value.contains("42,00") })
        XCTAssertTrue(sections[1].rows[0].value.contains("4,0000"))
        XCTAssertTrue(sections[1].rows[0].value.contains("144,00"))
        XCTAssertEqual(sections[2].rows.first { $0.id == "pas" }?.value, "0,00 €")
        XCTAssertTrue(sections[2].rows.first { $0.id == "paid" }!.value.hasPrefix("À confirmer"))
        XCTAssertEqual(sections[2].rows.first { $0.id == "transportReimbursement" }?.value, "À confirmer")
        XCTAssertTrue(sections[2].rows.first { $0.id == "transportReimbursement" }?.label.contains("hors brut") == true)
    }

    func testForeignOrStaleDetailNeverAppearsInPresentation() {
        let detail = SalaryCalculatedPayslipDetailsV2(companyId: "other", period: month,
            socialGross: 2400, grossLines: [.init(label: "Foreign premium", amount: 999)], contributions: [])
        let snapshot = SalaryWorkspaceSnapshotV2(period: month, sourceReady: true,
            socialGross: 2400, netBeforeIncomeTax: nil, netTaxable: nil,
            incomeTax: nil, netAfterIncomeTax: nil, warnings: [], payslipDetails: detail)
        let sections = SalaryCalculatedPayslipPresentationV2.sections(snapshot: snapshot, companyId: "company-a")
        XCTAssertFalse(sections.flatMap(\.rows).contains { $0.label == "Foreign premium" })
        XCTAssertFalse(detail.matches(companyId: "other", period: YearMonthV2(year: 2026, month: 8)!, gross: 2400))
        XCTAssertFalse(detail.matches(companyId: "other", period: month, gross: 2401))
    }

    func testMissingDetailsRemainUnknownInsteadOfZero() {
        let snapshot = SalaryWorkspaceResolverV2.resolve(period: month, reference: nil)
        let rows = SalaryCalculatedPayslipPresentationV2.sections(snapshot: snapshot, companyId: "company-a").flatMap(\.rows)
        XCTAssertFalse(rows.contains { $0.value == "0,00 €" })
        XCTAssertEqual(rows.first { $0.id == "netSocialObserved" }?.value, "À confirmer")
    }

    func testPresentationUsesCanonicalCentRoundingAndRejectsInvalidMoney() {
        XCTAssertEqual(SalaryCalculatedPayslipPresentationV2.money(1.005), "1,01 €")
        XCTAssertEqual(SalaryCalculatedPayslipPresentationV2.money(.infinity), "À confirmer")
        XCTAssertEqual(SalaryCalculatedPayslipPresentationV2.money(-1), "À confirmer")
    }

}
