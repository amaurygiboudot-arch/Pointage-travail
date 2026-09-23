import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryConventionNightRuleStoreV2Tests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "SalaryConventionNightRuleStoreV2Tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
    }

    override func tearDown() {
        if let suiteName {
            defaults.removePersistentDomain(forName: suiteName)
        }
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testExplicitEmptyStorageIsReadableButDoesNotProveAbsenceOfNightRule() {
        let stored = SalaryConventionNightRuleStoreV2.decodeConfirmed("[]")
        XCTAssertTrue(stored.reliable)
        XCTAssertTrue(stored.snapshots.isEmpty)

        let resolution = SalaryConventionNightRuleStoreV2.resolve(
            stored: stored,
            idcc: "0292",
            period: YearMonthV2(year: 2026, month: 9)!
        )
        XCTAssertFalse(resolution.reliable)
        XCTAssertNil(resolution.rule)
        XCTAssertTrue(
            resolution.warnings.contains(
                SalaryConventionNightRuleStoreV2.missingWarning
            )
        )
    }

    func testUnreadableJsonIsFailClosed() {
        let stored = SalaryConventionNightRuleStoreV2.decodeConfirmed("{cassé")
        XCTAssertFalse(stored.reliable)
        XCTAssertTrue(stored.snapshots.isEmpty)
        XCTAssertTrue(
            stored.warnings.contains(
                SalaryConventionNightRuleStoreV2.storageWarning
            )
        )
    }

    func testInvalidRuleObjectIsNeverIgnoredSilently() {
        let raw = """
        [
          {
            "idcc":"0292",
            "versionId":"v1",
            "sourceId":"legifrance:KALI:v1",
            "effectiveFromEpochDay":1000,
            "effectiveToEpochDay":null,
            "checkedAtMs":1,
            "note":null,
            "rule":null
          }
        ]
        """
        let stored = SalaryConventionNightRuleStoreV2.decodeConfirmed(raw)
        XCTAssertFalse(stored.reliable)
        XCTAssertTrue(stored.snapshots.isEmpty)
    }

    func testDuplicateVersionAndOverlappingPeriodsAreRejected() {
        let duplicate = SalaryConventionNightRuleStoreV2.decodeConfirmed(
            json([
                snapshotJSON(version: "v1", from: 1000, to: 1100),
                snapshotJSON(version: "v1", from: 1101, to: 1200)
            ])
        )
        XCTAssertFalse(duplicate.reliable)

        let overlap = SalaryConventionNightRuleStoreV2.decodeConfirmed(
            json([
                snapshotJSON(version: "v1", from: 1000, to: 1100),
                snapshotJSON(version: "v2", from: 1050, to: 1200)
            ])
        )
        XCTAssertFalse(overlap.reliable)
    }

    func testHistoryNormalizesIdccAndFindsOnlyApplicableVersion() {
        let snapshots = [
            snapshot(
                version: "v1",
                from: epochDay(2025, 1, 1),
                to: epochDay(2025, 12, 31),
                multiplier: 1.10
            ),
            snapshot(
                version: "v2",
                from: epochDay(2026, 1, 1),
                to: nil,
                multiplier: 1.25
            )
        ]
        let history = SalaryConventionNightRuleHistoryV2(snapshots)

        XCTAssertNotNil(history)
        XCTAssertEqual(
            history?.applicable(
                idcc: "292",
                epochDay: epochDay(2025, 6, 1)
            )?.versionId,
            "v1"
        )
        XCTAssertEqual(
            history?.applicable(
                idcc: "0292",
                epochDay: epochDay(2026, 9, 30)
            )?.versionId,
            "v2"
        )
        XCTAssertNil(
            history?.applicable(
                idcc: "0292",
                epochDay: epochDay(2024, 12, 31)
            )
        )
    }

    func testWholeMonthResolutionReturnsConfirmedRule() {
        let stored = SalaryConventionNightRuleReadResultV2(
            snapshots: [
                snapshot(
                    version: "v1",
                    from: epochDay(2026, 1, 1),
                    to: nil,
                    multiplier: 1.25
                )
            ],
            reliable: true,
            repairedFromBackup: false,
            warnings: []
        )

        let resolution = SalaryConventionNightRuleStoreV2.resolve(
            stored: stored,
            idcc: "292",
            period: YearMonthV2(year: 2026, month: 9)!
        )

        XCTAssertTrue(resolution.reliable)
        XCTAssertEqual(resolution.rule?.startMinute, 21 * 60)
        XCTAssertEqual(resolution.rule?.endMinute, 6 * 60)
        XCTAssertEqual(resolution.rule?.multiplier ?? 0, 1.25, accuracy: 0.000001)
    }

    func testGapInsideMonthBlocksResolution() {
        let stored = SalaryConventionNightRuleReadResultV2(
            snapshots: [
                snapshot(
                    version: "v1",
                    from: epochDay(2026, 1, 1),
                    to: epochDay(2026, 9, 10),
                    multiplier: 1.25
                ),
                snapshot(
                    version: "v2",
                    from: epochDay(2026, 9, 12),
                    to: nil,
                    multiplier: 1.25
                )
            ],
            reliable: true,
            repairedFromBackup: false,
            warnings: []
        )

        let resolution = SalaryConventionNightRuleStoreV2.resolve(
            stored: stored,
            idcc: "0292",
            period: YearMonthV2(year: 2026, month: 9)!
        )

        XCTAssertFalse(resolution.reliable)
        XCTAssertTrue(
            resolution.warnings.contains(
                SalaryConventionNightRuleStoreV2.coverageWarning
            )
        )
    }

    func testRealRuleChangeInsideMonthBlocksSingleMonthlyRule() {
        let change = epochDay(2026, 9, 16)
        let stored = SalaryConventionNightRuleReadResultV2(
            snapshots: [
                snapshot(
                    version: "v1",
                    from: epochDay(2026, 1, 1),
                    to: change - 1,
                    multiplier: 1.10
                ),
                snapshot(
                    version: "v2",
                    from: change,
                    to: nil,
                    multiplier: 1.25
                )
            ],
            reliable: true,
            repairedFromBackup: false,
            warnings: []
        )

        let resolution = SalaryConventionNightRuleStoreV2.resolve(
            stored: stored,
            idcc: "0292",
            period: YearMonthV2(year: 2026, month: 9)!
        )

        XCTAssertFalse(resolution.reliable)
        XCTAssertNil(resolution.rule)
        XCTAssertTrue(
            resolution.warnings.contains(
                SalaryConventionNightRuleStoreV2.changedDuringPeriodWarning
            )
        )
    }

    func testSourceVersionChangeWithSameRuleKeepsMonthReliable() {
        let change = epochDay(2026, 9, 16)
        let stored = SalaryConventionNightRuleReadResultV2(
            snapshots: [
                snapshot(
                    version: "v1",
                    from: epochDay(2026, 1, 1),
                    to: change - 1,
                    multiplier: 1.25,
                    source: "legifrance:KALI:a"
                ),
                snapshot(
                    version: "v2",
                    from: change,
                    to: nil,
                    multiplier: 1.25,
                    source: "legifrance:KALI:b"
                )
            ],
            reliable: true,
            repairedFromBackup: false,
            warnings: []
        )

        let resolution = SalaryConventionNightRuleStoreV2.resolve(
            stored: stored,
            idcc: "0292",
            period: YearMonthV2(year: 2026, month: 9)!
        )

        XCTAssertTrue(resolution.reliable)
        XCTAssertEqual(resolution.rule?.multiplier ?? 0, 1.25, accuracy: 0.000001)
        XCTAssertEqual(Set(resolution.sourceIds), Set(["legifrance:KALI:a", "legifrance:KALI:b"]))
    }

    func testSaveRejectsOverlapAndPersistsNonOverlappingHistory() {
        XCTAssertTrue(
            SalaryConventionNightRuleStoreV2.saveConfirmed(
                snapshot(
                    version: "v1",
                    from: epochDay(2026, 1, 1),
                    to: epochDay(2026, 6, 30),
                    multiplier: 1.10
                ),
                defaults: defaults
            )
        )
        XCTAssertFalse(
            SalaryConventionNightRuleStoreV2.saveConfirmed(
                snapshot(
                    version: "bad",
                    from: epochDay(2026, 6, 1),
                    to: nil,
                    multiplier: 1.25
                ),
                defaults: defaults
            )
        )
        XCTAssertTrue(
            SalaryConventionNightRuleStoreV2.saveConfirmed(
                snapshot(
                    version: "v2",
                    from: epochDay(2026, 7, 1),
                    to: nil,
                    multiplier: 1.25
                ),
                defaults: defaults
            )
        )

        let stored = SalaryConventionNightRuleStoreV2.readConfirmed(defaults: defaults)
        XCTAssertTrue(stored.reliable)
        XCTAssertEqual(stored.snapshots.count, 2)
    }

    func testCorruptPrimaryCanRecoverFromLastKnownGoodBackup() {
        let valid = json([
            snapshotJSON(
                version: "v1",
                from: epochDay(2026, 1, 1),
                to: nil
            )
        ])
        defaults.set(
            "{cassé",
            forKey: "salary_convention_night_rules_v2.confirmed_snapshots"
        )
        defaults.set(
            valid,
            forKey: "salary_convention_night_rules_v2.confirmed_snapshots_last_known_good"
        )

        let stored = SalaryConventionNightRuleStoreV2.readConfirmed(defaults: defaults)

        XCTAssertTrue(stored.reliable)
        XCTAssertTrue(stored.repairedFromBackup)
        XCTAssertEqual(stored.snapshots.count, 1)
        XCTAssertEqual(
            defaults.string(
                forKey: "salary_convention_night_rules_v2.confirmed_snapshots"
            ),
            valid
        )
    }

    private func snapshot(
        version: String,
        from: Int64,
        to: Int64?,
        multiplier: Double,
        source: String? = nil
    ) -> SalaryConventionNightRuleSnapshotV2 {
        SalaryConventionNightRuleSnapshotV2(
            idcc: "0292",
            versionId: version,
            sourceId: source ?? "legifrance:KALI:\(version)",
            effectiveFromEpochDay: from,
            effectiveToEpochDay: to,
            rule: NightPremiumRuleV2(
                startMinute: 21 * 60,
                endMinute: 6 * 60,
                multiplier: multiplier
            )!,
            checkedAtMs: 1,
            note: nil
        )
    }

    private func snapshotJSON(
        version: String,
        from: Int64,
        to: Int64?,
        multiplier: Double = 1.25
    ) -> String {
        """
        {
          "idcc":"0292",
          "versionId":"\(version)",
          "sourceId":"legifrance:KALI:\(version)",
          "effectiveFromEpochDay":\(from),
          "effectiveToEpochDay":\(to.map(String.init) ?? "null"),
          "checkedAtMs":1,
          "note":null,
          "rule":{
            "startMinute":1260,
            "endMinute":360,
            "multiplier":\(multiplier)
          }
        }
        """
    }

    private func json(_ objects: [String]) -> String {
        "[" + objects.joined(separator: ",") + "]"
    }

    private func epochDay(
        _ year: Int,
        _ month: Int,
        _ day: Int
    ) -> Int64 {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = calendar.date(
            from: DateComponents(
                year: year,
                month: month,
                day: day
            )
        )!
        return Int64(floor(date.timeIntervalSince1970 / 86_400.0))
    }
}
