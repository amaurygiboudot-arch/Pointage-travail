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
        XCTAssertEqual(result.complementaryMinutes, 0)
        XCTAssertEqual(result.nightMinutes, 120)
        XCTAssertEqual(result.workedGross, 1_000)
        XCTAssertEqual(result.cashGross, 1_100)
    }

    func testIncompleteNetKeepsProvedGrossAndTime() {
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

        XCTAssertFalse(result.workedGrossReliable)
        XCTAssertFalse(result.cashGrossReliable)
        XCTAssertFalse(result.netBeforeIncomeTaxComplete)
        XCTAssertNil(result.cashGross)
        XCTAssertTrue(result.warnings.contains(SalarySegmentedCanonicalOutputAssemblerV2.chainWarning))
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
