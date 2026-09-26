import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedProductionV2Tests: XCTestCase {
    private typealias Resolver = CompanyEmployeeDeductionResolverV2

    func testReliableFixedFactsReachCashAndNetOnce() {
        let result = SalarySegmentedProductionV2.calculate(
            worked: worked(1_000),
            fixedFacts: [
                .init(id: "seniority", amount: 50, applicable: true, reliable: true)
            ],
            fixedExhaustive: true,
            fixedSourceId: "fixed",
            netContext: completeContext()
        )

        XCTAssertTrue(result.cashGrossReliable)
        XCTAssertTrue(result.netComplete)
        XCTAssertEqual(result.cash.cashGross, 1_050)
        XCTAssertEqual(result.net.projection?.cashGross, 1_050)
    }

    func testUnknownFixedFactBlocksBeforeNetInsteadOfBecomingZero() {
        let result = SalarySegmentedProductionV2.calculate(
            worked: worked(1_000),
            fixedFacts: [
                .init(id: "seniority", amount: nil, applicable: true, reliable: false)
            ],
            fixedExhaustive: true,
            fixedSourceId: "fixed",
            netContext: completeContext()
        )

        XCTAssertFalse(result.cashGrossReliable)
        XCTAssertFalse(result.netComplete)
        XCTAssertNil(result.cash.cashGross)
        XCTAssertNil(result.net.projection)
        XCTAssertTrue(result.warnings.contains(SalaryConfirmedCashGrossComponentsResolverV2.factWarning))
    }

    private func worked(_ amount: Double) -> SalarySegmentedWorkedGrossProductionResultV2 {
        .init(
            evidence: .init(
                slices: [],
                reliable: true,
                warnings: [],
                sourceId: "worked-test",
                contributingSessionIds: []
            ),
            variables: .init(pieces: [], reliable: true, warnings: []),
            base: .init(pieces: [], baseGross: amount, reliable: true, warnings: []),
            assembly: .init(
                baseGross: amount,
                variableGross: 0,
                workedGross: amount,
                reliable: true,
                warnings: []
            )
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
                    record("mutual", .mutualEmployee, 0, period),
                    record("provident", .providentEmployee, 0, period),
                    record("transport", .transportEmployee, 0, period),
                    record("employer-taxable", .employerProtectionTaxable, 0, period),
                    record("employer-csg", .employerProtectionCsgCrdsBase, 0, period),
                    record("provident-nd", .employeeProvidentNonDeductible, 0, period)
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
            source: "Test confirmé"
        )
    }
}
