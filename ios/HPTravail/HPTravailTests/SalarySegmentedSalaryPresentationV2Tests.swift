import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedSalaryPresentationV2Tests: XCTestCase {
    func testUnreliableUpstreamExposesNoAmounts() {
        let result = SalarySegmentedSalaryPresentationV2.from(
            production(workedReliable: false, cashReliable: true, netReliable: true, netComplete: false)
        )
        XCTAssertEqual(result.state, .unreliable)
        XCTAssertNil(result.workedGross)
        XCTAssertNil(result.cashGross)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertNil(result.contributingSessionCount)
    }

    func testReliableGrossWithIncompleteNetKeepsGrossAndHidesNet() {
        let result = SalarySegmentedSalaryPresentationV2.from(
            production(workedReliable: true, cashReliable: true, netReliable: true, netComplete: false)
        )
        XCTAssertEqual(result.state, .grossAvailableNetIncomplete)
        XCTAssertEqual(result.workedGross, 1000)
        XCTAssertEqual(result.additionalCashGross, 50)
        XCTAssertEqual(result.cashGross, 1050)
        XCTAssertNil(result.netBeforeIncomeTax)
        XCTAssertNil(result.netTaxable)
        XCTAssertEqual(result.contributingSessionCount, 2)
        XCTAssertEqual(result.warnings, ["worked-warning", "cash-warning", "net-warning"])
    }

    private func production(
        workedReliable: Bool,
        cashReliable: Bool,
        netReliable: Bool,
        netComplete: Bool
    ) -> SalarySegmentedSalaryProductionResultV2 {
        let worked = SalarySegmentedWorkedGrossProductionResultV2(
            evidence: .init(
                slices: [],
                reliable: workedReliable,
                warnings: ["worked-warning"],
                sourceId: "test-source",
                contributingSessionIds: workedReliable ? ["s1", "s2"] : []
            ),
            variables: .init(
                pieces: [],
                reliable: workedReliable,
                warnings: ["worked-warning"]
            ),
            base: .init(
                pieces: [],
                baseGross: workedReliable ? 1000 : nil,
                reliable: workedReliable,
                warnings: ["worked-warning"]
            ),
            assembly: .init(
                baseGross: workedReliable ? 1000 : nil,
                variableGross: workedReliable ? 0 : nil,
                workedGross: workedReliable ? 1000 : nil,
                reliable: workedReliable,
                warnings: ["worked-warning"]
            )
        )
        let cash = SalarySegmentedCashGrossAssemblyResultV2(
            workedGross: cashReliable ? 1000 : nil,
            additionalCashGross: cashReliable ? 50 : nil,
            cashGross: cashReliable ? 1050 : nil,
            reliable: cashReliable,
            warnings: ["cash-warning"]
        )
        let net = SalarySegmentedCashGrossNetProjectionResultV2(
            cash: cash,
            projection: nil,
            cashGrossReliable: netReliable,
            netBeforeIncomeTaxComplete: netComplete,
            warnings: ["net-warning"]
        )
        return .init(worked: worked, cash: cash, net: net)
    }
}
