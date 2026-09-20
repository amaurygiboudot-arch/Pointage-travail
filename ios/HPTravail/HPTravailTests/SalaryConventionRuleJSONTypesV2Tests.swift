import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryConventionRuleJSONTypesV2Tests: XCTestCase {
    func testJsonIntegerOneRemainsANumberAndIsAccepted() {
        let raw = """
        [{
          "idcc":"0292",
          "versionId":"v1",
          "sourceId":"legifrance:KALI:v1",
          "effectiveFromEpochDay":1,
          "effectiveToEpochDay":2,
          "checkedAtMs":1,
          "rules":{"weeklyRegularMinutes":2100,"overtimeTiers":[]}
        }]
        """

        let result = SalaryConventionRuleStoreV2.decodeConfirmed(raw)

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.snapshots.count, 1)
        XCTAssertEqual(result.snapshots.first?.effectiveFromEpochDay, 1)
        XCTAssertEqual(result.snapshots.first?.checkedAtMs, 1)
    }

    func testJsonBooleanIsNeverAcceptedAsIntegerOrDouble() {
        let raw = """
        [{
          "idcc":"0292",
          "versionId":"v1",
          "sourceId":"legifrance:KALI:v1",
          "effectiveFromEpochDay":1000,
          "effectiveToEpochDay":1100,
          "checkedAtMs":true,
          "rules":{"weeklyRegularMinutes":2100,"nightMultiplier":true,"overtimeTiers":[]}
        }]
        """

        let result = SalaryConventionRuleStoreV2.decodeConfirmed(raw)

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.snapshots.isEmpty)
        XCTAssertTrue(result.warnings.contains(SalaryConventionRuleStoreV2.storageWarning))
    }
}
