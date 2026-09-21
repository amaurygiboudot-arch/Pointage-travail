import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryEmploymentContractTimelineV2Tests: XCTestCase {
    func testFirstVersionStartsOnConfirmedDayAndStaysOpen() throws {
        let updated = try XCTUnwrap(
            SalaryEmploymentContractTimelineV2.upsertEffectiveVersion(
                existing: [],
                contract: contract(companyId: "company-a", rate: 13.5),
                effectiveFromEpochDay: 100,
                sourceId: "user-confirmed",
                checkedAtMs: 1
            )
        )

        XCTAssertEqual(updated.count, 1)
        XCTAssertEqual(updated[0].effectiveFromEpochDay, 100)
        XCTAssertNil(updated[0].effectiveToEpochDay)
        XCTAssertEqual(updated[0].versionId, "effective-100")
    }

    func testNewVersionClosesOpenVersionDayBefore() throws {
        let first = snapshot(version: "v1", companyId: "company-a", from: 100, to: nil, rate: 13.5)
        let updated = try XCTUnwrap(
            SalaryEmploymentContractTimelineV2.upsertEffectiveVersion(
                existing: [first],
                contract: contract(companyId: "company-a", rate: 14.0),
                effectiveFromEpochDay: 200,
                sourceId: "avenant",
                checkedAtMs: 2
            )
        )

        let history = try XCTUnwrap(SalaryEmploymentContractHistoryV2(updated))
        XCTAssertEqual(history.allVersions(companyId: "company-a").first(where: { $0.versionId == "v1" })?.effectiveToEpochDay, 199)
        XCTAssertEqual(history.applicable(companyId: "company-a", epochDay: 200)?.contract.grossHourlyRate, 14.0)
    }

    func testInsertionInsideTimelineStopsBeforeNextVersion() throws {
        let first = snapshot(version: "v1", companyId: "company-a", from: 100, to: 300, rate: 13.5)
        let third = snapshot(version: "v3", companyId: "company-a", from: 400, to: nil, rate: 15.0)
        let updated = try XCTUnwrap(
            SalaryEmploymentContractTimelineV2.upsertEffectiveVersion(
                existing: [first, third],
                contract: contract(companyId: "company-a", rate: 14.0),
                effectiveFromEpochDay: 250,
                sourceId: "avenant",
                checkedAtMs: 2
            )
        )

        let history = try XCTUnwrap(SalaryEmploymentContractHistoryV2(updated))
        XCTAssertEqual(history.allVersions(companyId: "company-a").first(where: { $0.versionId == "v1" })?.effectiveToEpochDay, 249)
        let inserted = try XCTUnwrap(history.applicable(companyId: "company-a", epochDay: 300))
        XCTAssertEqual(inserted.effectiveFromEpochDay, 250)
        XCTAssertEqual(inserted.effectiveToEpochDay, 399)
        XCTAssertEqual(history.applicable(companyId: "company-a", epochDay: 400)?.versionId, "v3")
    }

    func testKnownHistoricalGapBeforeConfirmedDateStaysGap() throws {
        let old = snapshot(version: "old", companyId: "company-a", from: 10, to: 20, rate: 12.0)
        let future = snapshot(version: "future", companyId: "company-a", from: 100, to: nil, rate: 15.0)
        let updated = try XCTUnwrap(
            SalaryEmploymentContractTimelineV2.upsertEffectiveVersion(
                existing: [old, future],
                contract: contract(companyId: "company-a", rate: 14.0),
                effectiveFromEpochDay: 50,
                sourceId: "user-confirmed",
                checkedAtMs: 3
            )
        )

        let history = try XCTUnwrap(SalaryEmploymentContractHistoryV2(updated))
        XCTAssertNil(history.applicable(companyId: "company-a", epochDay: 30))
        XCTAssertEqual(history.applicable(companyId: "company-a", epochDay: 20)?.versionId, "old")
        XCTAssertEqual(history.applicable(companyId: "company-a", epochDay: 50)?.contract.grossHourlyRate, 14.0)
        XCTAssertEqual(history.applicable(companyId: "company-a", epochDay: 50)?.effectiveToEpochDay, 99)
    }

    func testEditingSameEffectiveDateKeepsVersionIdAndEndBoundary() throws {
        let old = snapshot(version: "stable-id", companyId: "company-a", from: 100, to: 199, rate: 13.5)
        let next = snapshot(version: "next", companyId: "company-a", from: 200, to: nil, rate: 15.0)
        let updated = try XCTUnwrap(
            SalaryEmploymentContractTimelineV2.upsertEffectiveVersion(
                existing: [old, next],
                contract: contract(companyId: "company-a", rate: 14.25),
                effectiveFromEpochDay: 100,
                sourceId: "correction-user",
                checkedAtMs: 4
            )
        )

        let corrected = try XCTUnwrap(
            SalaryEmploymentContractHistoryV2(updated)?.applicable(companyId: "company-a", epochDay: 150)
        )
        XCTAssertEqual(corrected.versionId, "stable-id")
        XCTAssertEqual(corrected.effectiveToEpochDay, 199)
        XCTAssertEqual(corrected.contract.grossHourlyRate, 14.25)
    }

    func testOtherCompanyIsStrictlyPreserved() throws {
        let companyB = snapshot(version: "b1", companyId: "company-b", from: 1, to: nil, rate: 20.0)
        let updated = try XCTUnwrap(
            SalaryEmploymentContractTimelineV2.upsertEffectiveVersion(
                existing: [companyB],
                contract: contract(companyId: "company-a", rate: 13.5),
                effectiveFromEpochDay: 100,
                sourceId: "user-confirmed",
                checkedAtMs: 5
            )
        )

        XCTAssertTrue(updated.contains(companyB))
        XCTAssertEqual(
            SalaryEmploymentContractHistoryV2(updated)?.applicable(companyId: "company-b", epochDay: 500)?.contract.grossHourlyRate,
            20.0
        )
    }

    func testIncoherentExistingHistoryIsRejectedInsteadOfImplicitlyRepaired() {
        let overlapping = [
            snapshot(version: "v1", companyId: "company-a", from: 1, to: 100, rate: 13.0),
            snapshot(version: "v2", companyId: "company-a", from: 50, to: nil, rate: 14.0)
        ]

        let result = SalaryEmploymentContractTimelineV2.upsertEffectiveVersion(
            existing: overlapping,
            contract: contract(companyId: "company-a", rate: 15.0),
            effectiveFromEpochDay: 200,
            sourceId: "user-confirmed",
            checkedAtMs: 6
        )

        XCTAssertNil(result)
    }

    private func contract(companyId: String, rate: Double) -> ContractV2 {
        ContractV2(
            id: "contract-\(companyId)",
            employerId: companyId,
            type: .fullTime,
            contractualWeeklyMinutes: 35 * 60,
            grossHourlyRate: rate,
            hireDateEpochDay: nil,
            payrollCutoffDay: nil
        )
    }

    private func snapshot(
        version: String,
        companyId: String,
        from: Int64,
        to: Int64?,
        rate: Double
    ) -> SalaryEmploymentContractSnapshotV2 {
        SalaryEmploymentContractSnapshotV2(
            versionId: version,
            sourceId: "test",
            effectiveFromEpochDay: from,
            effectiveToEpochDay: to,
            contract: contract(companyId: companyId, rate: rate),
            checkedAtMs: 1,
            note: nil
        )
    }
}
