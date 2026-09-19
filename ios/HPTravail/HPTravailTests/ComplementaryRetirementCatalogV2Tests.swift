import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class ComplementaryRetirementCatalogV2Tests: XCTestCase {
    private func fullCeiling2026() -> SocialSecurityCeilingV2.Snapshot {
        SocialSecurityCeilingV2.calculate(
            .init(
                period: YearMonthV2(year: 2026, month: 1)!,
                contractType: .fullTime,
                contractualWeeklyMinutes: 35 * 60,
                entryDate: PayrollCivilDateV2(year: 2020, month: 1, day: 1)!
            )
        )
    }

    func test2026Tranche1AndCegMatchOfficialRates() {
        let result = ComplementaryRetirementCatalogV2.estimate(
            gross: 2_500,
            year: 2026,
            professionalStatus: "NON_CADRE",
            ceiling: fullCeiling2026(),
            protectionCategory: .noConventionOverride()
        )

        XCTAssertEqual(result.lines.first { $0.id == "agirc_t1" }?.baseAmount ?? -1, 2_500, accuracy: 0.001)
        XCTAssertEqual(result.lines.first { $0.id == "agirc_t1" }?.employeeAmount ?? -1, 78.75, accuracy: 0.001)
        XCTAssertEqual(result.lines.first { $0.id == "ceg_t1" }?.employeeAmount ?? -1, 21.5, accuracy: 0.001)
        XCTAssertNil(result.lines.first { $0.id == "agirc_t2" })
        XCTAssertNil(result.lines.first { $0.id == "cet" })
        XCTAssertNil(result.lines.first { $0.id == "apec" })
        XCTAssertEqual(result.employeeDeductions, 100.25, accuracy: 0.001)
    }

    func testTranche2AndCetUseApplicableCeiling() {
        let result = ComplementaryRetirementCatalogV2.estimate(
            gross: 5_000,
            year: 2026,
            professionalStatus: "NON_CADRE",
            ceiling: fullCeiling2026(),
            protectionCategory: .noConventionOverride()
        )

        XCTAssertEqual(result.lines.first { $0.id == "agirc_t1" }?.baseAmount ?? -1, 4_005, accuracy: 0.001)
        XCTAssertEqual(result.lines.first { $0.id == "agirc_t2" }?.baseAmount ?? -1, 995, accuracy: 0.001)
        XCTAssertEqual(result.lines.first { $0.id == "cet" }?.baseAmount ?? -1, 5_000, accuracy: 0.001)
        XCTAssertEqual(result.employeeDeductions, 264.3145, accuracy: 0.001)
    }

    func testExplicitCadreGetsApecCappedAtFourPass() {
        let result = ComplementaryRetirementCatalogV2.estimate(
            gross: 20_000,
            year: 2026,
            professionalStatus: "CADRE",
            ceiling: fullCeiling2026(),
            protectionCategory: .noConventionOverride()
        )

        let apec = result.lines.first { $0.id == "apec" }
        XCTAssertEqual(apec?.baseAmount ?? -1, 16_020, accuracy: 0.001)
        XCTAssertEqual(apec?.employeeAmount ?? -1, 3.8448, accuracy: 0.001)
        XCTAssertEqual(apec?.employerAmount ?? -1, 5.7672, accuracy: 0.001)
    }

    func testConfirmedAniArticle22OverridesPlainProfessionalStatus() {
        let category = ProtectionCategoryV2.Result(
            aniCategory: .article2_2,
            confirmed: true,
            source: "Convention collective de test"
        )
        let result = ComplementaryRetirementCatalogV2.estimate(
            gross: 3_000,
            year: 2026,
            professionalStatus: "NON_CADRE",
            ceiling: fullCeiling2026(),
            protectionCategory: category
        )

        XCTAssertNotNil(result.lines.first { $0.id == "apec" })
    }

    func testUnconfirmedConventionCategoryNeverInventsApec() {
        let category = ProtectionCategoryV2.Result(
            aniCategory: .article2_1,
            confirmed: false,
            source: "Classification à confirmer"
        )
        let result = ComplementaryRetirementCatalogV2.estimate(
            gross: 3_000,
            year: 2026,
            professionalStatus: "CADRE",
            ceiling: fullCeiling2026(),
            protectionCategory: category
        )

        XCTAssertNil(result.lines.first { $0.id == "apec" })
        XCTAssertTrue(result.warnings.contains { $0.contains("à confirmer") })
    }

    func testExtensionEligibleNeverAutomaticallyGetsApec() {
        let category = ProtectionCategoryV2.Result(
            aniCategory: .extensionEligible,
            confirmed: true
        )
        let result = ComplementaryRetirementCatalogV2.estimate(
            gross: 3_000,
            year: 2026,
            professionalStatus: "CADRE",
            ceiling: fullCeiling2026(),
            protectionCategory: category
        )

        XCTAssertNil(result.lines.first { $0.id == "apec" })
        XCTAssertTrue(result.warnings.contains { $0.contains("Extension régime cadres possible") })
    }

    func testUnknownProfessionalStatusDoesNotInventApec() {
        let result = ComplementaryRetirementCatalogV2.estimate(
            gross: 3_000,
            year: 2026,
            professionalStatus: nil,
            ceiling: fullCeiling2026(),
            protectionCategory: .noConventionOverride()
        )

        XCTAssertNil(result.lines.first { $0.id == "apec" })
        XCTAssertTrue(result.warnings.contains { $0.contains("Statut professionnel à préciser") })
    }

    func testUnsupportedYearDoesNotInventRates() {
        let result = ComplementaryRetirementCatalogV2.estimate(
            gross: 3_000,
            year: 2027,
            professionalStatus: "CADRE"
        )

        XCTAssertTrue(result.lines.isEmpty)
        XCTAssertEqual(result.employeeDeductions, 0, accuracy: 0.001)
        XCTAssertTrue(result.warnings.contains { $0.contains("barème non intégré") })
    }
}
