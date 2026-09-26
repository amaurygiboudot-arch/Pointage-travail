import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedCanonicalProductionV2Tests: XCTestCase {
    private typealias Resolver = CompanyEmployeeDeductionResolverV2

    func testReliableChainProducesCanonicalOutputWithoutConsumerRecalculation() {
        let result = SalarySegmentedCanonicalProductionV2.calculate(
            worked: worked(),
            fixed: .init(
                components: [],
                exhaustive: true,
                sourceId: "fixed-confirmed",
                warnings: []
            ),
            netContext: completeContext()
        )

        XCTAssertTrue(result.output.workedGrossReliable)
        XCTAssertTrue(result.output.cashGrossReliable)
        XCTAssertTrue(result.output.netBeforeIncomeTaxComplete)
        XCTAssertEqual(result.output.cashGross, 1_000)
        XCTAssertEqual(result.output.complementaryMinutes, 0)
    }    func testUnconfirmedFixedComponentsBlockCashAndNetWithoutErasingWorkedGross() {
        let result = SalarySegmentedCanonicalProductionV2.calculate(
            worked: worked(),
            fixed: .init(
                components: [],
                exhaustive: false,
                sourceId: "",
                warnings: ["fixed-unconfirmed"]
            ),
            netContext: completeContext()
        )

        XCTAssertTrue(result.output.workedGrossReliable)
        XCTAssertFalse(result.output.cashGrossReliable)
        XCTAssertFalse(result.output.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.output.cashGross)
        XCTAssertNil(result.output.netBeforeIncomeTax)
        XCTAssertTrue(result.output.warnings.contains("fixed-unconfirmed"))
    }

    private func worked() -> SalarySegmentedWorkedGrossProductionResultV2 {
        let week = SalarySegmentedPayrollWeekEvidenceV2(
            yearForWeekOfYear: 2026,
            weekOfYear: 40,
            week: PayrollWeekV2(
                paidMinutes: 2_100,
                nightMinutes: 0,                saturdayMinutes: 0,
                sundayMinutes: 0,
                publicHolidayMinutes: 0
            ),
            fullWeekContextReliable: true
        )
        let inputEvidence = PayrollInputEvidenceV2(
            paidTimeReliable: true,
            premiumTimeBreakdownReliable: true,
            payrollRulesReliable: true
        )
        let slice = SalarySegmentedPayrollSliceEvidenceV2(
            startEpochDay: 1,
            endEpochDay: 7,
            contractVersionId: "c1",
            ruleVersionId: "r1",
            weeks: [week],
            evidence: inputEvidence,
            warnings: []
        )
        let evidence = SalarySegmentedPayrollSessionEvidenceResultV2(
            slices: [slice],
            reliable: true,
            warnings: [],
            sourceId: "source",
            contributingSessionIds: ["s1"]
        )
        let variables = SalarySegmentedWorkedVariableGrossSourceResultV2(
            pieces: [],
            reliable: true,
            warnings: [],
            breakdowns: [
                .init(
                    companyId: "company",                    versionId: "c1",
                    startEpochDay: 1,
                    endEpochDay: 7,
                    overtimeGross: 0,
                    complementaryGross: 0,
                    premiumGross: 0,
                    variableOvertimeMinutes: 0,
                    complementaryMinutes: 0
                )
            ]
        )
        let base = SegmentedMonthlyBaseResultV2(
            pieces: [],
            baseGross: 1_000,
            reliable: true,
            warnings: []
        )
        let assembly = SalarySegmentedWorkedGrossAssemblyResultV2(
            baseGross: 1_000,
            variableGross: 0,
            workedGross: 1_000,
            reliable: true,
            warnings: []
        )
        return .init(
            evidence: evidence,
            variables: variables,
            base: base,
            assembly: assembly
        )
    }    private func completeContext() -> SalarySegmentedNetProjectionContextV2 {
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
                ],                period: period
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
