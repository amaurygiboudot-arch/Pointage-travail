import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SocialContributionCatalogV2Tests: XCTestCase {
    private func fullCeiling2026() -> SocialSecurityCeilingV2.Snapshot {
        SocialSecurityCeilingV2.calculate(
            .init(
                period: YearMonthV2(year: 2026, month: 1)!,
                contractType: .fullTime,
                contractualWeeklyMinutes: 35 * 60,
                entryDate: PayrollCivilDateV2(year: 2020, month: 1, day: 1)!,
                unpaidAbsenceDays: 0
            )
        )
    }

    func test2026EmployeeDeductionsUseLineByLineBases() {
        let result = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross: 2_500,
            year: 2026,
            ceiling: fullCeiling2026(),
            alsaceMoselleLocalRegime: false,
            employerProtectionCsgCrdsBaseAmount: 0
        )

        XCTAssertEqual(result.lines.first { $0.id == "old_age_uncapped" }?.employeeAmount ?? -1, 10, accuracy: 0.001)
        XCTAssertEqual(result.lines.first { $0.id == "old_age_capped" }?.employeeAmount ?? -1, 172.5, accuracy: 0.001)
        XCTAssertEqual(result.lines.first { $0.id == "csg_deductible" }?.baseAmount ?? -1, 2_456.25, accuracy: 0.001)
        XCTAssertEqual(result.lines.first { $0.id == "csg_deductible" }?.employeeAmount ?? -1, 167.025, accuracy: 0.001)
        XCTAssertEqual(result.lines.first { $0.id == "csg_taxable" }?.employeeAmount ?? -1, 58.95, accuracy: 0.001)
        XCTAssertEqual(result.lines.first { $0.id == "crds" }?.employeeAmount ?? -1, 12.28125, accuracy: 0.001)
        XCTAssertEqual(result.employeeDeductions, 420.75625, accuracy: 0.001)
        XCTAssertEqual(result.netBeforeIncomeTax, 2_079.24375, accuracy: 0.001)
    }

    func testOldAgeCappedUsesApplicableMonthlyCeiling() {
        let result = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross: 5_000,
            year: 2026,
            ceiling: fullCeiling2026(),
            alsaceMoselleLocalRegime: false,
            employerProtectionCsgCrdsBaseAmount: 0
        )

        let capped = result.lines.first { $0.id == "old_age_capped" }
        XCTAssertEqual(capped?.baseAmount ?? -1, 4_005, accuracy: 0.001)
        XCTAssertEqual(capped?.employeeAmount ?? -1, 276.345, accuracy: 0.001)
    }

    func testAlsaceMoselleIsNeverAppliedWithoutExplicitConfirmation() {
        let unknown = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross: 2_500,
            year: 2026,
            ceiling: fullCeiling2026(),
            alsaceMoselleLocalRegime: nil,
            employerProtectionCsgCrdsBaseAmount: 0
        )
        XCTAssertNil(unknown.lines.first { $0.id == "alsace_moselle_local_health" })
        XCTAssertTrue(unknown.warnings.contains { $0.contains("affiliation à confirmer") })

        let confirmed = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross: 2_500,
            year: 2026,
            ceiling: fullCeiling2026(),
            alsaceMoselleLocalRegime: true,
            employerProtectionCsgCrdsBaseAmount: 0
        )
        XCTAssertEqual(
            confirmed.lines.first { $0.id == "alsace_moselle_local_health" }?.employeeAmount ?? -1,
            32.5,
            accuracy: 0.001
        )
    }

    func testEmployerProtectionAmountIsAddedAfterSalaryAbatement() {
        let result = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross: 2_500,
            year: 2026,
            ceiling: fullCeiling2026(),
            alsaceMoselleLocalRegime: false,
            employerProtectionCsgCrdsBaseAmount: 100
        )

        let expectedBase = 2_500 * 0.9825 + 100
        XCTAssertEqual(result.lines.first { $0.id == "csg_deductible" }?.baseAmount ?? -1, expectedBase, accuracy: 0.001)
        XCTAssertTrue(result.warnings.contains { $0.contains("confirmée ajoutée") })
    }

    func testMissingEmployerProtectionAmountStaysExplicitlyUnknown() {
        let result = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross: 2_500,
            year: 2026,
            ceiling: fullCeiling2026(),
            alsaceMoselleLocalRegime: false,
            employerProtectionCsgCrdsBaseAmount: nil
        )

        XCTAssertTrue(result.warnings.contains { $0.contains("à confirmer") })
        XCTAssertEqual(result.lines.first { $0.id == "csg_deductible" }?.baseAmount ?? -1, 2_456.25, accuracy: 0.001)
    }

    func testUnsupportedYearDoesNotInventRates() {
        let result = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross: 2_500,
            year: 2027,
            alsaceMoselleLocalRegime: false,
            employerProtectionCsgCrdsBaseAmount: 0
        )

        XCTAssertTrue(result.lines.isEmpty)
        XCTAssertEqual(result.employeeDeductions, 0, accuracy: 0.001)
        XCTAssertEqual(result.netBeforeIncomeTax, 2_500, accuracy: 0.001)
        XCTAssertTrue(result.warnings.contains { $0.contains("barème non intégré") })
    }
}
