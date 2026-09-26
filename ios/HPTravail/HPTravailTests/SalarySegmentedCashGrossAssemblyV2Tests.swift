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

    private func worked(
        _ amount: Double,
        reliable: Bool = true,
        warnings: [String] = []
    ) -> SalarySegmentedWorkedGrossProductionResultV2 {
        let source = SalarySegmentedPayrollSessionSourceV2(
            employerId: "company",
            work: .init(sessions: [], reliable: reliable),
            sourceId: "source",
            exhaustive: reliable,
            coveredStartEpochDay: 0,
            coveredEndEpochDay: 0,
            checkedAt: Date(timeIntervalSince1970: 1),
            timeZoneId: "UTC",
            warnings: warnings
        )
        let variables = SalarySegmentedWorkedVariableGrossSourceResultV2(
            pieces: [], reliable: reliable, warnings: warnings
        )
        let assembled = SalarySegmentedWorkedGrossAssemblyResultV2(
            baseGross: reliable ? amount : nil,
            variableGross: reliable ? 0 : nil,
            workedGross: reliable ? amount : nil,
            reliable: reliable,
            warnings: warnings
        )
        return .init(
            source: source,
            variables: variables,
            worked: assembled,
            reliable: reliable,
            warnings: warnings
        )
    }
}
