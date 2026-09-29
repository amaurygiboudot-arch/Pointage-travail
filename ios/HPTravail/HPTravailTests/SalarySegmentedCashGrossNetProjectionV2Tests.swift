import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedCashGrossNetProjectionV2Tests: XCTestCase {
    private typealias Resolver = CompanyEmployeeDeductionResolverV2

    func testReliableCashGrossReachesCanonicalNetWithoutChangingCashAmount() {
        let context = completeContext()
        let expected = EmployeeNetProjectionV2.calculate(
            .init(
                cashGross: 3_000,
                upstreamGrossReliable: true,
                benefits: context.benefits,
                year: context.year,
                ceiling: context.ceiling,
                alsaceMoselleLocalRegime: context.alsaceMoselleLocalRegime,
                professionalStatus: context.professionalStatus,
                protectionCategory: context.protectionCategory,
                companyDeductions: context.companyDeductions,
                period: context.period,
                incomeTaxRate: context.incomeTaxRate
            )
        )
        let actual = SalarySegmentedCashGrossNetProjectionV2.project(
            cash: cash(3_000),
            context: context
        )

        XCTAssertTrue(actual.cashGrossReliable)
        XCTAssertTrue(actual.netBeforeIncomeTaxComplete)
        XCTAssertEqual(actual.projection?.cashGross, 3_000)
        XCTAssertEqual(actual.projection?.netBeforeIncomeTax, expected.netBeforeIncomeTax)
    }

    func testUnreliableCashGrossBlocksBeforeCanonicalNet() {
        let actual = SalarySegmentedCashGrossNetProjectionV2.project(
            cash: cash(3_000, reliable: false),
            context: completeContext()
        )
        XCTAssertFalse(actual.cashGrossReliable)
        XCTAssertFalse(actual.netBeforeIncomeTaxComplete)
        XCTAssertNil(actual.projection)
    }

    func testIncompleteCeilingKeepsCashButBlocksFinalNet() {
        var context = completeContext()
        context = .init(
            benefits: context.benefits,
            year: context.year,
            ceiling: SocialSecurityCeilingV2.calculate(
                .init(
                    period: YearMonthV2(year: 2026, month: 4)!,
                    contractType: .partTime,
                    contractualWeeklyMinutes: 17 * 60 + 30,
                    complementaryMinutes: nil,
                    entryDate: PayrollCivilDateV2(year: 2020, month: 1, day: 1)!
                )
            ),
            alsaceMoselleLocalRegime: context.alsaceMoselleLocalRegime,
            professionalStatus: context.professionalStatus,
            protectionCategory: context.protectionCategory,
            companyDeductions: context.companyDeductions,
            period: context.period,
            incomeTaxRate: context.incomeTaxRate
        )
        let actual = SalarySegmentedCashGrossNetProjectionV2.project(
            cash: cash(3_000),
            context: context
        )

        XCTAssertTrue(actual.cashGrossReliable)
        XCTAssertFalse(actual.netBeforeIncomeTaxComplete)
        XCTAssertNil(actual.projection?.netBeforeIncomeTax)
    }

    func testMissingEmployeeDeductionNeverBecomesZero() {
        let period = Resolver.YearMonth(year: 2026, month: 4)!
        let incomplete = Resolver.resolve(
            records: [
                record("provident", .providentEmployee, 18, period),
                record("transport", .transportEmployee, 0, period),
                record("employer-taxable", .employerProtectionTaxable, 8, period),
                record("employer-csg", .employerProtectionCsgCrdsBase, 12, period),
                record("provident-nd", .employeeProvidentNonDeductible, 4, period)
            ],
            period: period
        )
        let base = completeContext()
        let context = SalarySegmentedNetProjectionContextV2(
            benefits: base.benefits,
            year: base.year,
            ceiling: base.ceiling,
            alsaceMoselleLocalRegime: base.alsaceMoselleLocalRegime,
            professionalStatus: base.professionalStatus,
            protectionCategory: base.protectionCategory,
            companyDeductions: incomplete,
            period: period,
            incomeTaxRate: base.incomeTaxRate
        )
        let actual = SalarySegmentedCashGrossNetProjectionV2.project(
            cash: cash(3_000),
            context: context
        )

        XCTAssertTrue(actual.cashGrossReliable)
        XCTAssertFalse(actual.netBeforeIncomeTaxComplete)
        XCTAssertNil(actual.projection?.netBeforeIncomeTax)
    }

    private func cash(
        _ amount: Double,
        reliable: Bool = true
    ) -> SalarySegmentedCashGrossAssemblyResultV2 {
        .init(
            workedGross: amount,
            additionalCashGross: 0,
            cashGross: reliable ? amount : nil,
            reliable: reliable,
            warnings: []
        )
    }

    private func completeContext() -> SalarySegmentedNetProjectionContextV2 {
        let period = Resolver.YearMonth(year: 2026, month: 4)!
        return .init(
            benefits: .init(applied: [], totalGross: 0, reliable: true, warnings: []),
            year: 2026,
            ceiling: SocialSecurityCeilingV2.calculate(
                .init(
                    period: YearMonthV2(year: 2026, month: 4)!,
                    contractType: .fullTime,
                    contractualWeeklyMinutes: 35 * 60,
                    entryDate: PayrollCivilDateV2(year: 2020, month: 1, day: 1)!
                )
            ),
            alsaceMoselleLocalRegime: false,
            professionalStatus: "NON_CADRE",
            protectionCategory: ProtectionCategoryV2.noConventionOverride(),
            companyDeductions: Resolver.resolve(
                records: [
                    record("mutual", .mutualEmployee, 42, period),
                    record("provident", .providentEmployee, 18, period),
                    record("transport", .transportEmployee, 0, period),
                    record("employer-taxable", .employerProtectionTaxable, 8, period),
                    record("employer-csg", .employerProtectionCsgCrdsBase, 12, period),
                    record("provident-nd", .employeeProvidentNonDeductible, 4, period)
                ],
                period: period
            ),
            period: period,
            incomeTaxRate: nil
        )
    }

    private func record(
        _ id: String,
        _ kind: Resolver.Kind,
        _ amount: Double,
        _ period: Resolver.YearMonth
    ) -> Resolver.Record {
        .init(
            id: id,
            kind: kind,
            amount: amount,
            effectiveFrom: period,
            effectiveTo: period,
            source: "Bulletin confirmé"
        )
    }
}
