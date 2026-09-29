import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedSocialSecurityCeilingV2Tests: XCTestCase {
    private let period = YearMonthV2(year: 2026, month: 9)!

    func testRateChangeKeepsCanonicalMonthlyCeilingWhenRelevantInputsMatch() throws {
        let contracts = try resolution(
            first: snapshot("v1", rate: 13.5, weekly: 2_100),
            second: snapshot("v2", rate: 14.0, weekly: 2_100)
        )

        let result = SalarySegmentedSocialSecurityCeilingV2.resolve(
            period: period,
            contracts: contracts,
            complementaryMinutes: 0,
            unpaidAbsenceDays: 0
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.ceiling?.applicableMonthly ?? -1, 4_005, accuracy: 0.001)
    }

    func testWorkDurationTransitionBlocksCeilingInsteadOfUsingLastContract() throws {
        let contracts = try resolution(
            first: snapshot("v1", rate: 13.5, weekly: 1_050, type: .partTime),
            second: snapshot("v2", rate: 14.0, weekly: 1_200, type: .partTime)
        )

        let result = SalarySegmentedSocialSecurityCeilingV2.resolve(
            period: period,
            contracts: contracts,
            complementaryMinutes: 0,
            unpaidAbsenceDays: 0
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.ceiling)
        XCTAssertTrue(result.warnings.contains(SalarySegmentedSocialSecurityCeilingV2.transitionWarning))
    }

    func testMissingAbsenceEvidenceKeepsCeilingUnknown() throws {
        let contracts = try resolution(
            first: snapshot("v1", rate: 13.5, weekly: 2_100),
            second: snapshot("v2", rate: 14.0, weekly: 2_100)
        )

        let result = SalarySegmentedSocialSecurityCeilingV2.resolve(
            period: period,
            contracts: contracts,
            complementaryMinutes: 0,
            unpaidAbsenceDays: nil
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.ceiling)
        XCTAssertTrue(result.warnings.contains { $0.contains("absence non rémunérée") })
    }

    func testHireDateAfterFullyCoveredMonthStartIsRejected() throws {
        let range = try XCTUnwrap(SalaryConventionCoverageResolverV2.monthEpochDayRange(period))
        let contracts = try resolution(
            first: snapshot("v1", rate: 13.5, weekly: 2_100, hireEpochDay: range.start + 1),
            second: snapshot("v2", rate: 14.0, weekly: 2_100, hireEpochDay: range.start + 1)
        )

        let result = SalarySegmentedSocialSecurityCeilingV2.resolve(
            period: period, contracts: contracts,
            complementaryMinutes: 0, unpaidAbsenceDays: 0
        )
        XCTAssertNil(result.ceiling)
        XCTAssertTrue(result.warnings.contains(SalarySegmentedSocialSecurityCeilingV2.hireDateWarning))
    }

    private func resolution(
        first: SalaryEmploymentContractSnapshotV2,
        second: SalaryEmploymentContractSnapshotV2
    ) throws -> SalaryEmploymentContractPeriodResolutionV2 {
        let range = try XCTUnwrap(SalaryConventionCoverageResolverV2.monthEpochDayRange(period))
        let split = range.start + 14
        let versions = [
            SalaryEmploymentContractSnapshotV2(
                versionId: first.versionId,
                sourceId: first.sourceId,
                effectiveFromEpochDay: range.start - 100,
                effectiveToEpochDay: split - 1,
                contract: first.contract,
                checkedAtMs: 1,
                note: nil
            ),
            SalaryEmploymentContractSnapshotV2(
                versionId: second.versionId,
                sourceId: second.sourceId,
                effectiveFromEpochDay: split,
                effectiveToEpochDay: nil,
                contract: second.contract,
                checkedAtMs: 1,
                note: nil
            )
        ]
        return try XCTUnwrap(
            SalaryEmploymentContractPeriodResolverV2.resolve(
                companyId: "company-a",
                periodStartEpochDay: range.start,
                periodEndEpochDay: range.end,
                sourceReliable: true,
                snapshots: versions
            )
        )
    }

    private func snapshot(
        _ version: String,
        rate: Double,
        weekly: Int,
        type: ContractTypeV2 = .fullTime,
        hireEpochDay: Int64 = 18_262
    ) -> SalaryEmploymentContractSnapshotV2 {
        .init(
            versionId: version,
            sourceId: "Contrat confirmé",
            effectiveFromEpochDay: 0,
            effectiveToEpochDay: nil,
            contract: .init(
                id: "contract-\(version)",
                employerId: "company-a",
                type: type,
                contractualWeeklyMinutes: weekly,
                grossHourlyRate: rate,
                hireDateEpochDay: hireEpochDay
            ),
            checkedAtMs: 1,
            note: nil
        )
    }
}
