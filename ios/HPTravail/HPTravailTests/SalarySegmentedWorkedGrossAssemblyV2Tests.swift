import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedWorkedGrossAssemblyV2Tests: XCTestCase {
    func testProvenBaseAndVariablesProduceWorkedGross() throws {
        let result = SalarySegmentedWorkedGrossAssemblerV2.assemble(
            contracts: contracts(),
            base: base(),
            variables: [
                variable("v1", 0, 14, 120),
                variable("v2", 15, 30, 80)
            ]
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(try XCTUnwrap(result.baseGross), 1_500, accuracy: 0.0001)
        XCTAssertEqual(try XCTUnwrap(result.variableGross), 200, accuracy: 0.0001)
        XCTAssertEqual(try XCTUnwrap(result.workedGross), 1_700, accuracy: 0.0001)
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testB21GlobalWarningSurvivesSuccessfulB20Assembly() throws {
        let source = SalarySegmentedWorkedVariableGrossSourceResultV2(
            pieces: [
                variable("v1", 0, 14, 120),
                variable("v2", 15, 30, 80)
            ],
            reliable: true,
            warnings: ["avertissement global B21"]
        )

        let result = SalarySegmentedWorkedGrossAssemblerV2.assembleFromSource(
            contracts: contracts(),
            base: base(),
            source: source
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(try XCTUnwrap(result.workedGross), 1_700, accuracy: 0.0001)
        XCTAssertTrue(result.warnings.contains("avertissement global B21"))
    }

    func testUnreliableB21ResultBlocksB20WithoutPublishingPartialAmounts() {
        let source = SalarySegmentedWorkedVariableGrossSourceResultV2(
            pieces: [
                variable("v1", 0, 14, 120),
                variable("v2", 15, 30, 80)
            ],
            reliable: false,
            warnings: ["couverture B21 incomplete"]
        )

        let result = SalarySegmentedWorkedGrossAssemblerV2.assembleFromSource(
            contracts: contracts(),
            base: base(),
            source: source
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.baseGross)
        XCTAssertNil(result.variableGross)
        XCTAssertNil(result.workedGross)
        XCTAssertTrue(result.warnings.contains("couverture B21 incomplete"))
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedWorkedGrossAssemblerV2.variableReliabilityWarning
            )
        )
    }

    func testExplicitReliableZeroVariableIsAccepted() throws {
        let result = SalarySegmentedWorkedGrossAssemblerV2.assemble(
            contracts: contracts(),
            base: base(),
            variables: [
                variable("v1", 0, 14, 0),
                variable("v2", 15, 30, 0)
            ]
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(try XCTUnwrap(result.variableGross), 0, accuracy: 0)
        XCTAssertEqual(try XCTUnwrap(result.workedGross), 1_500, accuracy: 0.0001)
    }

    func testMissingVariablePieceNeverBecomesImplicitZero() {
        let result = SalarySegmentedWorkedGrossAssemblerV2.assemble(
            contracts: contracts(),
            base: base(),
            variables: [
                variable("v1", 0, 14, 120)
            ]
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.workedGross)
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedWorkedGrossAssemblerV2.coverageWarning
            )
        )
    }

    func testUnreliableVariablePieceBlocksAssembly() {
        let result = SalarySegmentedWorkedGrossAssemblerV2.assemble(
            contracts: contracts(),
            base: base(),
            variables: [
                variable("v1", 0, 14, 120),
                variable(
                    "v2",
                    15,
                    30,
                    80,
                    reliable: false,
                    warnings: ["preuve variable absente"]
                )
            ]
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.workedGross)
        XCTAssertTrue(result.warnings.contains("preuve variable absente"))
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedWorkedGrossAssemblerV2.variableReliabilityWarning
            )
        )
    }

    func testDuplicateVariableKeyBlocksAssembly() {
        let duplicate = variable("v1", 0, 14, 10)
        let result = SalarySegmentedWorkedGrossAssemblerV2.assemble(
            contracts: contracts(),
            base: base(),
            variables: [
                duplicate,
                duplicate,
                variable("v2", 15, 30, 20)
            ]
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.variableGross)
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedWorkedGrossAssemblerV2.coverageWarning
            )
        )
    }

    func testInconsistentBaseTotalBlocksAssembly() {
        let value = base()
        let inconsistent = SegmentedMonthlyBaseResultV2(
            pieces: value.pieces,
            baseGross: 1_499,
            reliable: true,
            warnings: []
        )

        let result = SalarySegmentedWorkedGrossAssemblerV2.assemble(
            contracts: contracts(),
            base: inconsistent,
            variables: [
                variable("v1", 0, 14, 0),
                variable("v2", 15, 30, 0)
            ]
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.workedGross)
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedWorkedGrossAssemblerV2.baseWarning
            )
        )
    }

    func testTamperedBasePieceFactorBlocksAssembly() {
        let original = base()
        let first = original.pieces[0]
        let badPiece = SegmentedMonthlyBasePieceV2(
            versionId: first.versionId,
            startEpochDay: first.startEpochDay,
            endEpochDay: first.endEpochDay,
            scheduledMinutes: first.scheduledMinutes,
            factor: 0.6,
            fullMonthBaseGross: first.fullMonthBaseGross,
            proratedBaseGross: 1_200
        )
        let second = original.pieces[1]
        let badSecond = SegmentedMonthlyBasePieceV2(
            versionId: second.versionId,
            startEpochDay: second.startEpochDay,
            endEpochDay: second.endEpochDay,
            scheduledMinutes: second.scheduledMinutes,
            factor: 0.4,
            fullMonthBaseGross: second.fullMonthBaseGross,
            proratedBaseGross: 400
        )
        let tampered = SegmentedMonthlyBaseResultV2(
            pieces: [badPiece, badSecond],
            baseGross: 1_600,
            reliable: true,
            warnings: []
        )

        let result = SalarySegmentedWorkedGrossAssemblerV2.assemble(
            contracts: contracts(),
            base: tampered,
            variables: [
                variable("v1", 0, 14, 0),
                variable("v2", 15, 30, 0)
            ]
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.workedGross)
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedWorkedGrossAssemblerV2.baseWarning
            )
        )
    }

    func testInvertedVariableBoundsBlockAssembly() {
        let result = SalarySegmentedWorkedGrossAssemblerV2.assemble(
            contracts: contracts(),
            base: base(),
            variables: [
                variable("v1", 14, 0, 0),
                variable("v2", 15, 30, 0)
            ]
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.workedGross)
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedWorkedGrossAssemblerV2.coverageWarning
            )
        )
    }

    func testInvalidVariableAmountBlocksAssembly() {
        let result = SalarySegmentedWorkedGrossAssemblerV2.assemble(
            contracts: contracts(),
            base: base(),
            variables: [
                variable("v1", 0, 14, -1),
                variable("v2", 15, 30, 0)
            ]
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.workedGross)
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedWorkedGrossAssemblerV2.amountWarning
            )
        )
    }

    func testBaseThatDoesNotMatchContractTimelineBlocksAssembly() {
        let result = SalarySegmentedWorkedGrossAssemblerV2.assemble(
            contracts: contracts(secondVersionId: "other-v2"),
            base: base(),
            variables: [
                variable("v1", 0, 14, 0),
                variable("v2", 15, 30, 0)
            ]
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.workedGross)
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedWorkedGrossAssemblerV2.contractWarning
            )
        )
    }

    func testVariableFromAnotherCompanyBlocksAssembly() {
        let result = SalarySegmentedWorkedGrossAssemblerV2.assemble(
            contracts: contracts(),
            base: base(),
            variables: [
                variable("v1", 0, 14, 0, companyId: "other-company"),
                variable("v2", 15, 30, 0)
            ]
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.workedGross)
        XCTAssertTrue(
            result.warnings.contains(
                SalarySegmentedWorkedGrossAssemblerV2.coverageWarning
            )
        )
    }

    func testVariableOrderDoesNotChangeResult() throws {
        let result = SalarySegmentedWorkedGrossAssemblerV2.assemble(
            contracts: contracts(),
            base: base(),
            variables: [
                variable("v2", 15, 30, 80),
                variable("v1", 0, 14, 120)
            ]
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(try XCTUnwrap(result.workedGross), 1_700, accuracy: 0.0001)
    }

    private func contracts(
        secondVersionId: String = "v2"
    ) -> SalaryEmploymentContractPeriodResolutionV2 {
        let segments = [
            contractSegment("v1", start: 0, end: 14, rate: 10),
            contractSegment(secondVersionId, start: 15, end: 30, rate: 20)
        ]
        return SalaryEmploymentContractPeriodResolutionV2(
            companyId: "company",
            periodStartEpochDay: 0,
            periodEndEpochDay: 30,
            sourceReliable: true,
            coverage: SalaryEmploymentContractCoverageV2(
                companyId: "company",
                periodStartEpochDay: 0,
                periodEndEpochDay: 30,
                segments: segments,
                fullyCovered: true
            ),
            contract: nil,
            warnings: []
        )
    }

    private func contractSegment(
        _ versionId: String,
        start: Int64,
        end: Int64,
        rate: Double
    ) -> SalaryEmploymentContractCoverageSegmentV2 {
        SalaryEmploymentContractCoverageSegmentV2(
            startEpochDay: start,
            endEpochDay: end,
            snapshot: SalaryEmploymentContractSnapshotV2(
                versionId: versionId,
                sourceId: "test",
                effectiveFromEpochDay: start,
                effectiveToEpochDay: end,
                contract: ContractV2(
                    id: versionId,
                    employerId: "company",
                    type: .fullTime,
                    contractualWeeklyMinutes: 35 * 60,
                    grossHourlyRate: rate,
                    hireDateEpochDay: 0
                ),
                checkedAtMs: 1,
                note: nil
            )
        )
    }

    private func base() -> SegmentedMonthlyBaseResultV2 {
        SegmentedMonthlyBaseResultV2(
            pieces: [
                SegmentedMonthlyBasePieceV2(
                    versionId: "v1",
                    startEpochDay: 0,
                    endEpochDay: 14,
                    scheduledMinutes: 4_200,
                    factor: 0.5,
                    fullMonthBaseGross: 2_000,
                    proratedBaseGross: 1_000
                ),
                SegmentedMonthlyBasePieceV2(
                    versionId: "v2",
                    startEpochDay: 15,
                    endEpochDay: 30,
                    scheduledMinutes: 4_200,
                    factor: 0.5,
                    fullMonthBaseGross: 1_000,
                    proratedBaseGross: 500
                )
            ],
            baseGross: 1_500,
            reliable: true,
            warnings: []
        )
    }

    private func variable(
        _ versionId: String,
        _ start: Int64,
        _ end: Int64,
        _ amount: Double,
        reliable: Bool = true,
        warnings: [String] = [],
        companyId: String = "company"
    ) -> SalarySegmentedWorkedVariableGrossPieceV2 {
        SalarySegmentedWorkedVariableGrossPieceV2(
            companyId: companyId,
            versionId: versionId,
            startEpochDay: start,
            endEpochDay: end,
            variableGross: amount,
            reliable: reliable,
            warnings: warnings
        )
    }
}
