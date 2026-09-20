import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryConventionRuleStoreV2Tests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "SalaryConventionRuleStoreV2Tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    private func rawSnapshot(
        version: String,
        from: Int64,
        to: Int64? = nil,
        overtimeMultiplier: Double = 1.25,
        publicHolidayMultiplier: Double? = nil,
        includePublicHolidayField: Bool = true
    ) -> String {
        let holidayField = includePublicHolidayField
            ? "\"publicHolidayMultiplier\":\(publicHolidayMultiplier.map { String($0) } ?? "null"),"
            : ""
        return """
        {
          "idcc":"0292",
          "versionId":"\(version)",
          "sourceId":"legifrance:KALI:\(version)",
          "effectiveFromEpochDay":\(from),
          "effectiveToEpochDay":\(to.map { String($0) } ?? "null"),
          "checkedAtMs":1,
          "rules":{
            "weeklyRegularMinutes":2100,
            \(holidayField)
            "overtimeTiers":[
              {"fromMinutes":2100,"toMinutes":2580,"multiplier":\(overtimeMultiplier)},
              {"fromMinutes":2580,"toMinutes":null,"multiplier":1.5}
            ]
          }
        }
        """
    }

    private func snapshot(
        idcc: String = "0292",
        version: String,
        from: Int64,
        to: Int64? = nil,
        publicHolidayMultiplier: Double? = nil
    ) -> SalaryConventionRuleSnapshotV2 {
        SalaryConventionRuleSnapshotV2(
            idcc: idcc,
            versionId: version,
            sourceId: "legifrance:KALI:\(version)",
            effectiveFromEpochDay: from,
            effectiveToEpochDay: to,
            rules: PayrollRulesV2(
                weeklyRegularMinutes: 2_100,
                overtimeTiers: [
                    OvertimeTierV2(fromMinutes: 2_100, toMinutes: 2_580, multiplier: 1.25),
                    OvertimeTierV2(fromMinutes: 2_580, toMinutes: nil, multiplier: 1.5)
                ],
                publicHolidayMultiplier: publicHolidayMultiplier
            ),
            checkedAtMs: 1,
            note: nil
        )
    }

    func testExplicitEmptyHistoryIsReliable() {
        let result = SalaryConventionRuleStoreV2.decodeConfirmed("[]")

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.snapshots, [])
        XCTAssertEqual(result.warnings, [])
    }

    func testUnreadableJsonIsUnreliable() {
        let result = SalaryConventionRuleStoreV2.decodeConfirmed("{cassé")

        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.snapshots, [])
        XCTAssertTrue(result.warnings.contains(SalaryConventionRuleStoreV2.storageWarning))
    }

    func testMalformedEntryMakesWholeHistoryUnreliableButKeepsReadableFacts() {
        let result = SalaryConventionRuleStoreV2.decodeConfirmed(
            "[\(rawSnapshot(version: "v1", from: 1_000)),42]"
        )

        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.snapshots.count, 1)
        XCTAssertEqual(result.snapshots.first?.versionId, "v1")
    }

    func testDuplicateVersionIsUnreliable() {
        let result = SalaryConventionRuleStoreV2.decodeConfirmed(
            "[\(rawSnapshot(version: "v1", from: 1_000, to: 1_100)),\(rawSnapshot(version: "v1", from: 1_101, to: 1_200))]"
        )

        XCTAssertFalse(result.reliable)
    }

    func testOverlappingPeriodsAreUnreliable() {
        let result = SalaryConventionRuleStoreV2.decodeConfirmed(
            "[\(rawSnapshot(version: "v1", from: 1_000, to: 1_100)),\(rawSnapshot(version: "v2", from: 1_050, to: 1_200))]"
        )

        XCTAssertFalse(result.reliable)
    }

    func testHistoryUsesOnlyVersionCoveringRequestedDayWithoutCurrentFallback() {
        let stored = SalaryConventionRuleStoreV2.decodeConfirmed(
            "[\(rawSnapshot(version: "v1", from: 1_000, to: 1_100)),\(rawSnapshot(version: "v2", from: 1_200, to: 1_300))]"
        )
        let history = SalaryConventionRuleStoreV2.history(from: stored)

        XCTAssertTrue(stored.reliable)
        XCTAssertEqual(history?.applicable(idcc: "292", epochDay: 1_050)?.versionId, "v1")
        XCTAssertNil(history?.applicable(idcc: "0292", epochDay: 1_150))
        XCTAssertEqual(history?.applicable(idcc: "0292", epochDay: 1_250)?.versionId, "v2")
        XCTAssertNil(history?.applicable(idcc: "0292", epochDay: 1_400))
        XCTAssertEqual(history?.allVersions(idcc: "292").map(\.versionId), ["v2", "v1"])
    }

    func testPublicHolidayMultiplierIsPreservedAndValidated() {
        let valid = SalaryConventionRuleStoreV2.decodeConfirmed(
            "[\(rawSnapshot(version: "v1", from: 1_000, publicHolidayMultiplier: 1.75))]"
        )
        let invalid = SalaryConventionRuleStoreV2.decodeConfirmed(
            "[\(rawSnapshot(version: "v1", from: 1_000, publicHolidayMultiplier: 0.5))]"
        )

        XCTAssertTrue(valid.reliable)
        XCTAssertEqual(valid.snapshots.single?.rules.publicHolidayMultiplier, 1.75)
        XCTAssertFalse(invalid.reliable)
    }

    func testOlderSnapshotWithoutPublicHolidayFieldRemainsCompatible() {
        let result = SalaryConventionRuleStoreV2.decodeConfirmed(
            "[\(rawSnapshot(version: "v1", from: 1_000, includePublicHolidayField: false))]"
        )

        XCTAssertTrue(result.reliable)
        XCTAssertNil(result.snapshots.single?.rules.publicHolidayMultiplier)
    }

    func testSaveNormalizesIdccAndRoundTripsAllConfirmedRules() {
        let saved = SalaryConventionRuleStoreV2.saveConfirmed(
            snapshot(
                idcc: "292",
                version: "v1",
                from: 1_000,
                to: 1_100,
                publicHolidayMultiplier: 1.5
            ),
            defaults: defaults
        )
        let reloaded = SalaryConventionRuleStoreV2.readConfirmed(defaults: defaults)
        let history = SalaryConventionRuleStoreV2.history(from: reloaded)

        XCTAssertTrue(saved)
        XCTAssertTrue(reloaded.reliable)
        XCTAssertEqual(reloaded.snapshots.single?.idcc, "0292")
        XCTAssertEqual(reloaded.snapshots.single?.rules.publicHolidayMultiplier, 1.5)
        XCTAssertEqual(history?.applicable(idcc: "292", epochDay: 1_050)?.versionId, "v1")
    }

    func testSaveRejectsOverlappingVersionAndPreservesExistingHistory() {
        XCTAssertTrue(
            SalaryConventionRuleStoreV2.saveConfirmed(
                snapshot(version: "v1", from: 1_000, to: 1_100),
                defaults: defaults
            )
        )
        XCTAssertFalse(
            SalaryConventionRuleStoreV2.saveConfirmed(
                snapshot(version: "v2", from: 1_050, to: 1_200),
                defaults: defaults
            )
        )

        let reloaded = SalaryConventionRuleStoreV2.readConfirmed(defaults: defaults)
        XCTAssertTrue(reloaded.reliable)
        XCTAssertEqual(reloaded.snapshots.map(\.versionId), ["v1"])
    }

    func testCorruptPrimaryIsRestoredFromLastKnownGoodBackup() {
        XCTAssertTrue(
            SalaryConventionRuleStoreV2.saveConfirmed(
                snapshot(version: "v1", from: 1_000, to: 1_100),
                defaults: defaults
            )
        )
        defaults.set("{cassé", forKey: "salary_convention_rules_v2.confirmed_snapshots")

        let repaired = SalaryConventionRuleStoreV2.readConfirmed(defaults: defaults)

        XCTAssertTrue(repaired.reliable)
        XCTAssertTrue(repaired.repairedFromBackup)
        XCTAssertEqual(repaired.snapshots.map(\.versionId), ["v1"])
        XCTAssertTrue(repaired.warnings.contains(SalaryConventionRuleStoreV2.repairedWarning))
    }
}

private extension Array {
    var single: Element? { count == 1 ? self[0] : nil }
}
