import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedCashGrossAssemblyV2Tests: XCTestCase {
    func testReliableB20PlusConfirmedFixedComponentsProducesCashGrossOnce() {
        let result = SalarySegmentedCashGrossAssemblerV2.assemble(
            worked: worked(1_000),
            fixed: .init(
                components: [
                    .init(id: "seniority", amount: 50, reliable: true),
                    .init(id: "company-premium", amount: 25, reliable: true)
                ],
                exhaustive: true,
                sourceId: "fixed-2026-09"
            )
        )
        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.workedGross, 1_000)
        XCTAssertEqual(result.additionalCashGross, 75)
        XCTAssertEqual(result.cashGross, 1_075)
    }

    func testEmptyFixedListIsZeroOnlyWhenExplicitlyExhaustive() {
        let confirmed = SalarySegmentedCashGrossAssemblerV2.assemble(
            worked: worked(800),
            fixed: .init(components: [], exhaustive: true, sourceId: "none-confirmed")
        )
        XCTAssertTrue(confirmed.reliable)
        XCTAssertEqual(confirmed.cashGross, 800)

        let unknown = SalarySegmentedCashGrossAssemblerV2.assemble(
            worked: worked(800),
            fixed: .init(components: [], exhaustive: false, sourceId: "")
        )
        XCTAssertFalse(unknown.reliable)
        XCTAssertNil(unknown.cashGross)
    }

    func testUnreliableB20BlocksCashGross() {
        let result = SalarySegmentedCashGrossAssemblerV2.assemble(
            worked: worked(1_000, reliable: false),
            fixed: .init(components: [], exhaustive: true, sourceId: "fixed")
        )
        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.cashGross)
    }

    func testDuplicateOrInvalidFixedComponentBlocksWithoutPartialSubtotal() {
        let duplicate = SalarySegmentedCashGrossAssemblerV2.assemble(
            worked: worked(1_000),
            fixed: .init(
                components: [
                    .init(id: "x", amount: 10, reliable: true),
                    .init(id: "x", amount: 20, reliable: true)
                ],
                exhaustive: true,
                sourceId: "fixed"
            )
        )
        XCTAssertFalse(duplicate.reliable)
        XCTAssertNil(duplicate.cashGross)

        let invalid = SalarySegmentedCashGrossAssemblerV2.assemble(
            worked: worked(1_000),
            fixed: .init(
                components: [.init(id: "bad", amount: -1, reliable: true)],
                exhaustive: true,
                sourceId: "fixed"
            )
        )
        XCTAssertFalse(invalid.reliable)
        XCTAssertNil(invalid.cashGross)
    }

    func testWarningsAreDeduplicated() {
        let result = SalarySegmentedCashGrossAssemblerV2.assemble(
            worked: worked(1_000, warnings: ["trace"]),
            fixed: .init(
                components: [.init(id: "x", amount: 10, reliable: true, warnings: ["trace"])],
                exhaustive: true,
                sourceId: "fixed",
                warnings: ["fixed-warning"]
            )
        )
        XCTAssertEqual(result.warnings.filter { $0 == "trace" }.count, 1)
        XCTAssertTrue(result.warnings.contains("fixed-warning"))
    }

    func testConfirmedComponentLabelsSurviveCashAssembly() {
        let components: [SalaryConfirmedCashGrossComponentV2] = [
            .init(id: "seniority-premium", amount: 20, reliable: true, label: "Ancienneté"),
            .init(id: "company-premium:production", amount: 30, reliable: true, label: "Production")
        ]
        let result = SalarySegmentedCashGrossAssemblerV2.assemble(worked: worked(1000),
            fixed: .init(components: components, exhaustive: true, sourceId: "confirmed-month"))
        XCTAssertEqual(result.confirmedComponents, components)
        XCTAssertEqual(result.additionalCashGross, 50)
        XCTAssertEqual(result.cashGross, 1050)
        let blocked = SalarySegmentedCashGrossAssemblerV2.assemble(worked: worked(1000),
            fixed: .init(components: components, exhaustive: false, sourceId: ""))
        XCTAssertTrue(blocked.confirmedComponents.isEmpty)
    }

    private func worked(
        _ amount: Double,
        reliable: Bool = true,
        warnings: [String] = []
    ) -> SalarySegmentedWorkedGrossProductionResultV2 {
        let evidence = SalarySegmentedPayrollSessionEvidenceResultV2(
            slices: [],
            reliable: reliable,
            warnings: warnings,
            sourceId: "source",
            contributingSessionIds: []
        )
        let variables = SalarySegmentedWorkedVariableGrossSourceResultV2(
            pieces: [], reliable: reliable, warnings: warnings
        )
        let base = SegmentedMonthlyBaseResultV2(
            pieces: [],
            baseGross: reliable ? amount : nil,
            reliable: reliable,
            warnings: warnings
        )
        let assembly = SalarySegmentedWorkedGrossAssemblyResultV2(
            baseGross: reliable ? amount : nil,
            variableGross: reliable ? 0 : nil,
            workedGross: reliable ? amount : nil,
            reliable: reliable,
            warnings: warnings
        )
        return .init(
            evidence: evidence,
            variables: variables,
            base: base,
            assembly: assembly
        )
    }
}
