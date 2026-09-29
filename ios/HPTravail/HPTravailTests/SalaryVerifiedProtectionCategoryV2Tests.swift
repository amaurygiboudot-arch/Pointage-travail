import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryVerifiedProtectionCategoryV2Tests: XCTestCase {
    private let date = PayrollCivilDateV2(year: 2026, month: 9, day: 8)!
    private let classification = ConventionClassificationV2(coefficient: 910)
    private let scope = "KALITEXT000000000001"

    private func profile(
        classification: ConventionClassificationV2? = nil,
        status: String? = "CADRE"
    ) -> SalaryConventionLegalProfileV2 {
        SalaryConventionLegalProfileV2(
            companyId: "c1",
            idcc: "292",
            professionalStatus: status,
            classification: classification ?? self.classification
        )
    }

    private func rule(
        approved: Bool = true,
        selector: ConventionClassificationV2? = nil
    ) -> ConventionProtectionCategoryV2.Rule {
        let chosen = selector ?? classification
        return ConventionProtectionCategoryV2.Rule(
            idcc: "292",
            ruleId: "KALI-PROTECTION-CATEGORY-test",
            effectiveFrom: PayrollCivilDateV2(year: 2025, month: 1, day: 1)!,
            classification: chosen,
            professionalStatus: "CADRE",
            aniCategory: .article2_1,
            source: "Légifrance KALI — KALIARTI000050828557",
            extensionStatus: .extended,
            extensionEffectiveFrom: PayrollCivilDateV2(year: 2025, month: 1, day: 1)!,
            conventionScopeKey: scope,
            approvalStatus: approved ? .apecApproved : .apecRequiredUnverified,
            approvalEffectiveFrom: approved
                ? PayrollCivilDateV2(year: 2025, month: 1, day: 1)!
                : nil,
            approvalSource: approved
                ? "https://commission-paritaire.apec.fr/test.pdf"
                : nil,
            approvalScopeKey: approved ? scope : nil,
            approvalClassification: approved ? chosen : nil,
            approvalAniCategory: approved ? .article2_1 : nil
        )
    }

    private func coverage(
        state: ConventionMatterCoverageV2.State,
        authorities: Set<ConventionMatterCoverageV2.Authority>
    ) -> ConventionMatterCoverageV2.Snapshot {
        let record = ConventionMatterCoverageV2.Record(
            idcc: "292",
            matter: .providentCategory,
            effectiveFrom: PayrollCivilDateV2(year: 2026, month: 9, day: 1)!,
            effectiveTo: PayrollCivilDateV2(year: 2026, month: 9, day: 30)!,
            classification: classification,
            professionalStatus: "CADRE",
            state: state,
            source: "audit test",
            checkedAtMs: 1,
            authorities: authorities
        )
        return ConventionMatterCoverageV2.Snapshot(
            state: state,
            record: record,
            reliable: state != .incomplete,
            warnings: []
        )
    }

    func testKaliAndApecApprovedRuleBecomesReliableGenericCategory() {
        let result = SalaryVerifiedProtectionCategoryProviderV2.resolve(
            profile: profile(),
            referenceDate: date,
            rules: [rule()]
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.category.aniCategory, .article2_1)
        XCTAssertTrue(result.category.confirmed)
    }

    func testKaliWithoutApecRemainsToConfirm() {
        let result = SalaryVerifiedProtectionCategoryProviderV2.resolve(
            profile: profile(),
            referenceDate: date,
            rules: [rule(approved: false)]
        )

        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.category.aniCategory, .toConfirm)
    }

    func testNoRuleIsReliableOnlyWhenKaliAndApecConfirmTogether() {
        let result = SalaryVerifiedProtectionCategoryProviderV2.resolve(
            profile: profile(),
            referenceDate: date,
            rules: [],
            coverage: coverage(
                state: .confirmedNoRule,
                authorities: [.kali, .apec]
            )
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.category.aniCategory, .noConventionOverride)
    }

    func testKaliOnlyCoverageCannotProveNoRule() {
        let result = SalaryVerifiedProtectionCategoryProviderV2.resolve(
            profile: profile(),
            referenceDate: date,
            rules: [],
            coverage: coverage(
                state: .confirmedNoRule,
                authorities: [.kali]
            )
        )

        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.category.aniCategory, .toConfirm)
    }

    func testApprovedRuleForNeighborClassificationNeverSpillsOver() {
        let result = SalaryVerifiedProtectionCategoryProviderV2.resolve(
            profile: profile(),
            referenceDate: date,
            rules: [rule(selector: ConventionClassificationV2(coefficient: 920))]
        )

        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.category.aniCategory, .toConfirm)
    }

    func testMissingClassificationNeverInventsCategory() {
        let result = SalaryVerifiedProtectionCategoryProviderV2.resolve(
            profile: profile(classification: ConventionClassificationV2()),
            referenceDate: date,
            rules: [rule()]
        )

        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.category.aniCategory, .toConfirm)
        XCTAssertTrue(result.warnings.contains { $0.contains("classification") })
    }
}
