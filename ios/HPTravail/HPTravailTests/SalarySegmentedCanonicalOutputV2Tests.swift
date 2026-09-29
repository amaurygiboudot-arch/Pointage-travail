import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedCanonicalOutputV2Tests: XCTestCase {
    func testRichOutputPreservesProvedLevels() {
        let worked = fixtureWorked()
        let cash = cash(worked, 1_100)
        let net = net(cash, complete: false)

        let result = SalarySegmentedCanonicalOutputAssemblerV2.assemble(
            worked: worked,
            cash: cash,
            net: net
        )

        XCTAssertTrue(result.paidTimeReliable)
        XCTAssertTrue(result.premiumTimeReliable)
        XCTAssertTrue(result.workedGrossReliable)
        XCTAssertTrue(result.cashGrossReliable)
        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertEqual(result.paidMinutes, 2_400)
        XCTAssertEqual(result.variableOvertimeMinutes, 300)
        XCTAssertEqual(result.complementaryMinutes, 0)
        XCTAssertEqual(result.nightMinutes, 120)
        XCTAssertEqual(result.workedGross, 1_000)
        XCTAssertEqual(result.cashGross, 1_100)
    }

    func testIncompleteNetKeepsProvedGrossAndTime() throws {
        let worked = fixtureWorked()
        let cash = cash(worked, 1_100)
        let result = SalarySegmentedCanonicalOutputAssemblerV2.assemble(
            worked: worked,
            cash: cash,
            net: net(cash, complete: false)
        )

        XCTAssertTrue(result.paidTimeReliable)
        XCTAssertTrue(result.workedGrossReliable)
        XCTAssertTrue(result.cashGrossReliable)
        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertEqual(result.cashGross, 1_100)
        XCTAssertNil(result.netBeforeIncomeTax)

        let period = try XCTUnwrap(YearMonthV2(year: 2026, month: 9))
        let workspace = SalaryWorkspaceResolverV2.resolve(
            period: period,
            segmented: result
        )
        XCTAssertTrue(workspace.sourceReady)
        XCTAssertNil(workspace.netBeforeIncomeTax)
        XCTAssertNil(workspace.netTaxable)
        XCTAssertNil(
            SalaryPayslipComparisonEngineV2.compare(
                snapshot: workspace,
                observed: SalaryPayslipObservedValuesV2(
                    socialGross: nil,
                    netBeforeIncomeTax: 900,
                    netTaxable: nil,
                    incomeTax: nil,
                    netAfterIncomeTax: nil
                )
            )
        )
    }

    func testMismatchedCashChainIsRejected() {
        let worked = fixtureWorked()
        let cashA = cash(worked, 1_100)
        let cashB = cash(worked, 1_200)
        let result = SalarySegmentedCanonicalOutputAssemblerV2.assemble(
            worked: worked,
            cash: cashA,
            net: net(cashB, complete: true)
        )

        XCTAssertTrue(result.workedGrossReliable)
        XCTAssertEqual(result.workedGross, 1_000)
        XCTAssertFalse(result.cashGrossReliable)
        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.cashGross)
        XCTAssertTrue(result.warnings.contains(SalarySegmentedCanonicalOutputAssemblerV2.chainWarning))
    }

    func testNetProjectionForDifferentCashGrossIsRejected() {
        let worked = fixtureWorked()
        let cash = cash(worked, 1_100)
        let projection = EmployeeNetProjectionV2.calculate(
            projectionInput(cashGross: 1_200)
        )
        XCTAssertTrue(projection.netBeforeIncomeTaxComplete)
        let inconsistent = SalarySegmentedCashGrossNetProjectionResultV2(
            cash: cash,
            projection: projection,
            cashGrossReliable: true,
            netBeforeIncomeTaxComplete: true,
            warnings: []
        )

        let result = SalarySegmentedCanonicalOutputAssemblerV2.assemble(
            worked: worked, cash: cash, net: inconsistent
        )
        XCTAssertTrue(result.cashGrossReliable)
        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertNil(result.netTaxable)
        XCTAssertTrue(result.warnings.contains(SalarySegmentedCanonicalOutputAssemblerV2.netProofWarning))
    }

    func testUnreliableGrossFlagBlocksOtherwiseCompleteNet() {
        let worked = fixtureWorked()
        let cash = cash(worked, 1_100)
        let projection = EmployeeNetProjectionV2.calculate(
            projectionInput(cashGross: 1_100)
        )
        XCTAssertTrue(projection.netBeforeIncomeTaxComplete)
        let inconsistent = SalarySegmentedCashGrossNetProjectionResultV2(
            cash: cash,
            projection: projection,
            cashGrossReliable: false,
            netBeforeIncomeTaxComplete: true,
            warnings: []
        )

        let result = SalarySegmentedCanonicalOutputAssemblerV2.assemble(
            worked: worked, cash: cash, net: inconsistent
        )
        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertTrue(result.warnings.contains(SalarySegmentedCanonicalOutputAssemblerV2.netProofWarning))
    }

    private func projectionInput(cashGross: Double) -> EmployeeNetProjectionV2.Input {
        let period = CompanyEmployeeDeductionResolverV2.YearMonth(year: 2026, month: 4)!
        let kinds: [(String, CompanyEmployeeDeductionResolverV2.Kind, Double)] = [
            ("mutual", .mutualEmployee, 42),
            ("provident", .providentEmployee, 18),
            ("transport", .transportEmployee, 0),
            ("employer-taxable", .employerProtectionTaxable, 8),
            ("employer-csg", .employerProtectionCsgCrdsBase, 12),
            ("provident-nd", .employeeProvidentNonDeductible, 4)
        ]
        let deductions = CompanyEmployeeDeductionResolverV2.resolve(
            records: kinds.map { item in
                .init(id: item.0, kind: item.1, amount: item.2,
                      effectiveFrom: period, effectiveTo: period, source: "Bulletin confirmé")
            },
            period: period
        )
        return .init(
            cashGross: cashGross,
            upstreamGrossReliable: true,
            benefits: .init(applied: [], totalGross: 0, reliable: true, warnings: []),
            year: 2026,
            ceiling: SocialSecurityCeilingV2.calculate(
                .init(period: YearMonthV2(year: 2026, month: 4)!,
                      contractType: .fullTime, contractualWeeklyMinutes: 35 * 60,
                      entryDate: PayrollCivilDateV2(year: 2020, month: 1, day: 1)!)
            ),
            alsaceMoselleLocalRegime: false,
            professionalStatus: "NON_CADRE",
            protectionCategory: ProtectionCategoryV2.noConventionOverride(),
            companyDeductions: deductions,
            period: period,
            incomeTaxRate: nil
        )
    }

    private func fixtureWorked() -> SalarySegmentedWorkedGrossProductionResultV2 {
        let week = SalarySegmentedPayrollWeekEvidenceV2(
            yearForWeekOfYear: 2026,
            weekOfYear: 40,
            week: PayrollWeekV2(
                paidMinutes: 2_400,
                nightMinutes: 120,
                saturdayMinutes: 60
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
                    companyId: "company",
                    versionId: "c1",
                    startEpochDay: 1,
                    endEpochDay: 7,
                    overtimeGross: 50,
                    complementaryGross: 0,
                    premiumGross: 25,
                    variableOvertimeMinutes: 300,
                    complementaryMinutes: 0
                )
            ]
        )
        let base = SegmentedMonthlyBaseResultV2(
            pieces: [],
            baseGross: 925,
            reliable: true,
            warnings: []
        )
        let assembly = SalarySegmentedWorkedGrossAssemblyResultV2(
            baseGross: 925,
            variableGross: 75,
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
    }

    private func cash(
        _ worked: SalarySegmentedWorkedGrossProductionResultV2,
        _ amount: Double
    ) -> SalarySegmentedCashGrossAssemblyResultV2 {
        .init(
            workedGross: worked.workedGross,
            additionalCashGross: amount - (worked.workedGross ?? 0),
            cashGross: amount,
            reliable: true,
            warnings: []
        )
    }

    private func net(
        _ cash: SalarySegmentedCashGrossAssemblyResultV2,
        complete: Bool
    ) -> SalarySegmentedCashGrossNetProjectionResultV2 {
        .init(
            cash: cash,
            projection: nil,
            cashGrossReliable: true,
            netBeforeIncomeTaxComplete: complete,
            warnings: []
        )
    }
}
