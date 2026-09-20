import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryConventionRuleOpenEndedV2Tests: XCTestCase {
    func testLatestVersionMayRemainOpenEndedWithoutCreatingFallbackBeforeItsStart() {
        let raw = """
        [
          {
            "idcc":"0292",
            "versionId":"v1",
            "sourceId":"legifrance:KALI:v1",
            "effectiveFromEpochDay":1000,
            "effectiveToEpochDay":1100,
            "checkedAtMs":1,
            "rules":{"weeklyRegularMinutes":2100,"overtimeTiers":[]}
          },
          {
            "idcc":"0292",
            "versionId":"v2",
            "sourceId":"legifrance:KALI:v2",
            "effectiveFromEpochDay":1200,
            "effectiveToEpochDay":null,
            "checkedAtMs":2,
            "rules":{"weeklyRegularMinutes":2100,"overtimeTiers":[]}
          }
        ]
        """

        let stored = SalaryConventionRuleStoreV2.decodeConfirmed(raw)
        let history = SalaryConventionRuleStoreV2.history(from: stored)

        XCTAssertTrue(stored.reliable)
        XCTAssertNil(history?.applicable(idcc: "0292", epochDay: 1_150))
        XCTAssertEqual(history?.applicable(idcc: "0292", epochDay: 1_200)?.versionId, "v2")
        XCTAssertEqual(history?.applicable(idcc: "0292", epochDay: 9_999)?.versionId, "v2")
    }
}
