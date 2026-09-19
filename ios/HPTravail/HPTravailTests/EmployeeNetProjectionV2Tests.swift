import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class EmployeeNetProjectionV2Tests: XCTestCase {
    private typealias Resolver = CompanyEmployeeDeductionResolverV2

    private func salaryPeriod(_ year: Int = 2026, _ month: Int = 4) -> YearMonthV2 {
        YearMonthV2(year: year, month: month)!
    }

    private func deductionPeriod(_ year: Int = 2026, _ month: Int = 4) -> Resolver.YearMonth {
        Resolver.YearMonth(year: year, month: month)!
    }

    private func fullCeiling(_ year: Int = 2026) -> SocialSecurityCeilingV2.Snapshot {
        SocialSecurityCeilingV2.calculate(
            .init(
                period: salaryPeriod(year, 4),
                contractType: .fullTime,
                contractualWeeklyMinutes: 35 * 60,
                entryDate: PayrollCivilDateV2(year: 2020, month: 1, day: 1)!
            )
        )
    }

    private func benefits(total: Double = 0, reliable: Bool = true) -> CompanyBenefitInKindContractV2.Snapshot {
        CompanyBenefitInKindContractV2.Snapshot(
            applied: total > 0
                ? [.init(id: "benefit", label: "Avantage confirmé", grossValue: total)]
                : [],
            totalGross: total,
            reliable: reliable,
            warnings: reliable ? [] : ["Avantages en nature non confirmés"]
        )
    }

    private func record(
        id: String,
        kind: Resolver.Kind,
        amount: Double,
        period: Resolver.YearMonth
    ) -> Resolver.Record {
        Resolver.Record(
            id: id,
            kind: kind,
            amount: amount,
            effectiveFrom: period,
            effectiveTo: period,
            source: "Bulletin confirmé"
        )
    }

    private func deductions(
        period: Resolver.YearMonth,
        omitted: Set<Resolver.Kind> = []
    ) -> Resolver.Snapshot {
        let all: [(String, Resolver.Kind, Double)] = [
            ("mutual", .mutualEmployee, 42),
            ("provident", .providentEmployee, 18),
            ("transport", .transportEmployee, 0),
            ("employer-taxable", .employerProtectionTaxable, 8),
            ("employer-csg", .employerProtectionCsgCrdsBase, 12),
            ("provident-nondeductible", .employeeProvidentNonDeductible, 4)
        ]
        return Resolver.resolve(
            records: all.compactMap { item in
                let (id, kind, amount) = item
                return omitted.contains(kind)
                    ? nil
                    : record(id: id, kind: kind, amount: amount, period: period)
            },
            period: period
        )
    }

    private func input(
        cashGross: Double = 3_000,
        upstreamReliable: Bool = true,
        benefits: CompanyBenefitInKindContractV2.Snapshot? = nil,
        year: Int = 2026,
        ceiling: SocialSecurityCeilingV2.Snapshot? = nil,
        alsaceMoselle: Bool? = false,
        professionalStatus: String? = "NON_CADRE",
        protectionCategory: ProtectionCategoryV2.Result = ProtectionCategoryV2.noConventionOverride(),
        omittedDeductions: Set<Resolver.Kind> = []
    ) -> EmployeeNetProjectionV2.Input {
        let period = deductionPeriod(year, 4)
        return .init(
            cashGross: cashGross,
            upstreamGrossReliable: upstreamReliable,
            benefits: benefits ?? self.benefits(),
            year: year,
            ceiling: ceiling ?? fullCeiling(year),
            alsaceMoselleLocalRegime: alsaceMoselle,
            professionalStatus: professionalStatus,
            protectionCategory: protectionCategory,
            companyDeductions: deductions(period: period, omitted: omittedDeductions),
            period: period
        )
    }

    func testCompleteInputsPublishBeforeTaxAndTaxableNet() {
        let result = EmployeeNetProjectionV2.calculate(
            input(benefits: benefits(total: 100))
        )

        XCTAssertTrue(result.grossReliable)
        XCTAssertTrue(result.netBeforeIncomeTaxComplete)
        XCTAssertTrue(result.netTaxableComplete)
        XCTAssertEqual(result.contributionGross, 3_100, accuracy: 0.001)
        XCTAssertNotNil(result.netBeforeIncomeTax)
        XCTAssertNotNil(result.netTaxable)
    }

    func testBenefitsIncreaseContributionGrossButNotCashGross() {
        let withoutBenefit = EmployeeNetProjectionV2.calculate(input())
        let withBenefit = EmployeeNetProjectionV2.calculate(input(benefits: benefits(total: 100)))

        XCTAssertEqual(withoutBenefit.cashGross, 3_000, accuracy: 0.001)
        XCTAssertEqual(withBenefit.cashGross, 3_000, accuracy: 0.001)
        XCTAssertEqual(withBenefit.contributionGross - withoutBenefit.contributionGross, 100, accuracy: 0.001)
        XCTAssertGreaterThan(withBenefit.statutory.employeeDeductions, withoutBenefit.statutory.employeeDeductions)
        XCTAssertGreaterThan(withBenefit.complementaryRetirement.employeeDeductions, withoutBenefit.complementaryRetirement.employeeDeductions)
    }

    func testNonDeductibleProvidentShareIsNotSubtractedTwiceFromCashNet() {
        let result = EmployeeNetProjectionV2.calculate(input())
        let expectedCashDeductions = 42.0 + 18.0
        let statutoryRounded = result.statutory.lines.reduce(0) {
            $0 + (($1.employeeAmount * 100).rounded() / 100)
        }
        let retirementRounded = result.complementaryRetirement.lines.reduce(0) {
            $0 + (($1.employeeAmount * 100).rounded() / 100)
        }
        let expected = 3_000 -
            statutoryRounded -
            retirementRounded -
            expectedCashDeductions

        XCTAssertEqual(result.companyCashDeductions.deductions.reduce(0) { $0 + $1.amount }, 60, accuracy: 0.001)
        XCTAssertEqual(result.knownNetBeforeIncomeTax, expected, accuracy: 0.001)
        XCTAssertEqual(result.netBeforeIncomeTax ?? -1, expected, accuracy: 0.001)
    }

    func testNonDeductibleProvidentShareIsReintegratedOnlyIntoTaxableNet() {
        let result = EmployeeNetProjectionV2.calculate(input())
        let nonDeductibleCsgCrds = result.statutory.lines
            .filter { $0.id == "csg_taxable" || $0.id == "crds" }
            .reduce(0) { $0 + $1.employeeAmount }
        let expectedTaxable = result.knownNetBeforeIncomeTax + nonDeductibleCsgCrds + 8 + 4

        XCTAssertEqual(result.netTaxable ?? -1, expectedTaxable, accuracy: 0.001)
    }

    func testMissingTaxOnlyDataKeepsBeforeTaxNetButBlocksTaxableNet() {
        let result = EmployeeNetProjectionV2.calculate(
            input(omittedDeductions: [.employeeProvidentNonDeductible])
        )

        XCTAssertTrue(result.netBeforeIncomeTaxComplete)
        XCTAssertNotNil(result.netBeforeIncomeTax)
        XCTAssertFalse(result.netTaxableComplete)
        XCTAssertNil(result.netTaxable)
        XCTAssertTrue(result.warnings.contains { $0.contains("Net imposable incomplet") })
    }

    func testMissingCsgProtectionBaseBlocksBeforeTaxNet() {
        let result = EmployeeNetProjectionV2.calculate(
            input(omittedDeductions: [.employerProtectionCsgCrdsBase])
        )

        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertTrue(result.warnings.contains { $0.contains("CSG/CRDS") })
    }

    func testMissingCashDeductionBlocksBeforeTaxNet() {
        let result = EmployeeNetProjectionV2.calculate(
            input(omittedDeductions: [.transportEmployee])
        )

        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertTrue(result.warnings.contains { $0.contains("transport") })
    }

    func testUnknownProfessionalStatusBlocksBeforeTaxNet() {
        let result = EmployeeNetProjectionV2.calculate(input(professionalStatus: nil))

        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertTrue(result.warnings.contains { $0.contains("statut professionnel") })
    }

    func testUnconfirmedAniCategoryBlocksBeforeTaxNet() {
        let category = ProtectionCategoryV2.Result(
            aniCategory: .article2_1,
            confirmed: false,
            source: "Classification à confirmer"
        )
        let result = EmployeeNetProjectionV2.calculate(
            input(professionalStatus: "CADRE", protectionCategory: category)
        )

        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertTrue(result.warnings.contains { $0.contains("catégorie ANI") })
    }

    func testUnreliableBenefitsBlockAllFinalNetPublication() {
        let result = EmployeeNetProjectionV2.calculate(
            input(benefits: benefits(total: 0, reliable: false))
        )

        XCTAssertFalse(result.grossReliable)
        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertNil(result.netTaxable)
    }


    func testCanonicalNetUsesCentRoundedContributionLines() {
        let result = EmployeeNetProjectionV2.calculate(input())
        let statutoryRounded = result.statutory.lines.reduce(0) {
            $0 + (($1.employeeAmount * 100).rounded() / 100)
        }
        let retirementRounded = result.complementaryRetirement.lines.reduce(0) {
            $0 + (($1.employeeAmount * 100).rounded() / 100)
        }
        let directRounded = result.companyCashDeductions.deductions.reduce(0) {
            $0 + (($1.amount * 100).rounded() / 100)
        }
        let expected = max(0, 3_000 - statutoryRounded - retirementRounded - directRounded)

        XCTAssertEqual(result.knownNetBeforeIncomeTax, expected, accuracy: 0.0001)
        XCTAssertEqual(result.netBeforeIncomeTax ?? -1, expected, accuracy: 0.0001)
    }

    func testTaxableNetKeepsNegativeUnclampedPreTaxBalance() {
        let result = EmployeeNetProjectionV2.calculate(
            input(cashGross: 0, benefits: benefits(total: 1_000))
        )
        let statutoryRounded = result.statutory.lines.reduce(0) {
            $0 + (($1.employeeAmount * 100).rounded() / 100)
        }
        let retirementRounded = result.complementaryRetirement.lines.reduce(0) {
            $0 + (($1.employeeAmount * 100).rounded() / 100)
        }
        let directRounded = result.companyCashDeductions.deductions.reduce(0) {
            $0 + (($1.amount * 100).rounded() / 100)
        }
        let rawBeforeTax = -statutoryRounded - retirementRounded - directRounded
        let nonDeductibleCsgCrds = result.statutory.lines
            .filter { $0.id == "csg_taxable" || $0.id == "crds" }
            .reduce(0) { $0 + $1.employeeAmount }
        let expectedTaxable = max(0, rawBeforeTax + 1_000 + nonDeductibleCsgCrds + 8 + 4)

        XCTAssertEqual(result.knownNetBeforeIncomeTax, 0, accuracy: 0.0001)
        XCTAssertEqual(result.netTaxable ?? -1, expectedTaxable, accuracy: 0.0001)
    }

    func testUnsupportedYearDoesNotInventFinalNet() {
        let result = EmployeeNetProjectionV2.calculate(input(year: 2027))

        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertNil(result.netTaxable)
        XCTAssertTrue(result.warnings.contains { $0.contains("barèmes nationaux non intégrés") })
    }
}
