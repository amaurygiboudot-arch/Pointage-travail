import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryEmploymentContractHistoryStoreV2Tests: XCTestCase {
    func testExplicitEmptyHistoryIsReliable() {
        let result = SalaryEmploymentContractHistoryStoreV2.decodeConfirmed("[]")

        XCTAssertTrue(result.reliable)
        XCTAssertTrue(result.snapshots.isEmpty)
        XCTAssertNotNil(SalaryEmploymentContractHistoryStoreV2.history(from: result))
    }

    func testValidSnapshotRoundTripsAndResolvesPeriod() throws {
        let snapshot = makeSnapshot(version: "v1", from: 100, to: 199, rate: 13.5)
        let raw = try XCTUnwrap(SalaryEmploymentContractHistoryStoreV2.encodeConfirmed([snapshot]))
        let result = SalaryEmploymentContractHistoryStoreV2.decodeConfirmed(raw)

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.snapshots, [snapshot])
        let history = try XCTUnwrap(SalaryEmploymentContractHistoryStoreV2.history(from: result))
        XCTAssertEqual(history.applicable(companyId: "company-a", epochDay: 120)?.versionId, "v1")
        XCTAssertNil(history.applicable(companyId: "company-a", epochDay: 99))
        XCTAssertEqual(
            history.coverage(companyId: "company-a", periodStartEpochDay: 100, periodEndEpochDay: 199)?.fullyCovered,
            true
        )
    }

    func testMalformedStorageIsUnreliableInsteadOfEmpty() {
        let result = SalaryEmploymentContractHistoryStoreV2.decodeConfirmed("{not-json}")

        XCTAssertFalse(result.reliable)
        XCTAssertNil(SalaryEmploymentContractHistoryStoreV2.history(from: result))
        XCTAssertFalse(result.warnings.isEmpty)
    }

    func testOverlappingVersionsMakeWholeHistoryUnreliable() throws {
        let snapshots = [
            makeSnapshot(version: "v1", from: 100, to: 180, rate: 13.5),
            makeSnapshot(version: "v2", from: 150, to: 220, rate: 14.0)
        ]
        let raw = try XCTUnwrap(SalaryEmploymentContractHistoryStoreV2.encodeConfirmed(snapshots))
        let result = SalaryEmploymentContractHistoryStoreV2.decodeConfirmed(raw)

        XCTAssertFalse(result.reliable)
        XCTAssertNil(SalaryEmploymentContractHistoryStoreV2.history(from: result))
    }

    func testLegacyForfaitCannotBecomeHistoricalTruth() throws {
        let object: [[String: Any]] = [[
            "versionId": "legacy",
            "sourceId": "user-confirmed",
            "effectiveFromEpochDay": 100,
            "effectiveToEpochDay": NSNull(),
            "checkedAtMs": 1,
            "note": NSNull(),
            "contract": [
                "id": "legacy-contract",
                "employerId": "company-a",
                "type": "FORFAIT",
                "contractualWeeklyMinutes": 2_100,
                "grossHourlyRate": 13.5,
                "hireDateEpochDay": NSNull(),
                "payrollCutoffDay": NSNull(),
                "forfaitHoursPeriod": NSNull(),
                "forfaitHours": NSNull(),
                "forfaitAnnualDays": NSNull(),
                "monthlyGrossSalary": NSNull()
            ]
        ]]
        let data = try JSONSerialization.data(withJSONObject: object)
        let raw = try XCTUnwrap(String(data: data, encoding: .utf8))
        let result = SalaryEmploymentContractHistoryStoreV2.decodeConfirmed(raw)

        XCTAssertFalse(result.reliable)
    }

    func testBooleanCannotBeDecodedAsNumericContractValue() throws {
        let raw = """
        [{
          "versionId":"v1",
          "sourceId":"user-confirmed",
          "effectiveFromEpochDay":100,
          "effectiveToEpochDay":199,
          "checkedAtMs":1,
          "note":null,
          "contract":{
            "id":"contract-a",
            "employerId":"company-a",
            "type":"FULL_TIME",
            "contractualWeeklyMinutes":true,
            "grossHourlyRate":13.5,
            "hireDateEpochDay":50,
            "payrollCutoffDay":31,
            "forfaitHoursPeriod":null,
            "forfaitHours":null,
            "forfaitAnnualDays":null,
            "monthlyGrossSalary":null
          }
        }]
        """

        let result = SalaryEmploymentContractHistoryStoreV2.decodeConfirmed(raw)
        XCTAssertFalse(result.reliable)
    }

    func testSaveAndReloadKeepsEmployersIsolated() throws {
        let suiteName = "SalaryEmploymentContractHistoryStoreV2Tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let companyA = makeSnapshot(version: "v1", from: 100, to: nil, rate: 13.5, companyId: "company-a")
        let companyB = makeSnapshot(version: "v1", from: 100, to: nil, rate: 18.0, companyId: "company-b")

        XCTAssertTrue(SalaryEmploymentContractHistoryStoreV2.saveConfirmed(companyA, defaults: defaults))
        XCTAssertTrue(SalaryEmploymentContractHistoryStoreV2.saveConfirmed(companyB, defaults: defaults))

        let stored = SalaryEmploymentContractHistoryStoreV2.readConfirmed(defaults: defaults)
        XCTAssertTrue(stored.reliable)
        let history = try XCTUnwrap(SalaryEmploymentContractHistoryStoreV2.history(from: stored))
        XCTAssertEqual(history.applicable(companyId: "company-a", epochDay: 120)?.contract.grossHourlyRate, 13.5)
        XCTAssertEqual(history.applicable(companyId: "company-b", epochDay: 120)?.contract.grossHourlyRate, 18.0)
    }

    private func makeSnapshot(
        version: String,
        from: Int64,
        to: Int64?,
        rate: Double,
        companyId: String = "company-a"
    ) -> SalaryEmploymentContractSnapshotV2 {
        SalaryEmploymentContractSnapshotV2(
            versionId: version,
            sourceId: "user-confirmed",
            effectiveFromEpochDay: from,
            effectiveToEpochDay: to,
            contract: ContractV2(
                id: "contract-\(companyId)-\(version)",
                employerId: companyId,
                type: .fullTime,
                contractualWeeklyMinutes: 35 * 60,
                grossHourlyRate: rate,
                hireDateEpochDay: nil,
                payrollCutoffDay: 31
            ),
            checkedAtMs: 1,
            note: nil
        )
    }
}
