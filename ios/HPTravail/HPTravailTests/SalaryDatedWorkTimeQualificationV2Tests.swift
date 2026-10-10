import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryDatedWorkTimeQualificationV2Tests: XCTestCase {
    private let owner = SalaryWorkRuleOwnerV2(
        accountId: "account-1", employeeId: "employee-1",
        employerId: "employer-1", contractVersionId: "contract-1"
    )
    private let startDay: Int64 = 20_734
    private var paris: Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Europe/Paris")!
        return cal
    }
    private func at(day: Int, hour: Int) -> Date {
        paris.date(from: DateComponents(
            year: 2026, month: 10, day: day, hour: hour, minute: 0, second: 0
        ))!
    }
    private func session(start: Date? = nil, end: Date? = nil) -> SalarySessionFactV2 {
        SalarySessionFactV2(
            id: "test", entry: start ?? at(day: 8, hour: 8),
            exit: end ?? at(day: 8, hour: 16), employerId: owner.employerId, pauses: []
        )
    }
    private func policy(
        id: String = "r", from: Int64 = 20_734, until: Int64? = nil
    ) -> SalaryDatedWorkRuleV2 {
        SalaryDatedWorkRuleV2(
            id: id, owner: owner, topic: .timeAccounting,
            effectiveFromEpochDay: from, effectiveToEpochDay: until,
            sourceId: "verified-source-" + id, ruleReference: "article",
            checkedAtMs: 1_790_000_000_000, confirmation: .confirmed
        )
    }

    func testExplicitVerifiedSourceKeepsObservedEightHours() {
        let result = SalaryDatedWorkTimeQualificationV2.assess(
            session: session(), owner: owner, calendar: paris,
            requiredTopics: [.timeAccounting], rules: [policy()],
            sourceReliable: true
        )
        XCTAssertEqual(result.time.paidDuration, 8 * 3_600, accuracy: 0.01)
        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.selectedSourceIds[.timeAccounting], "verified-source-r")
    }

    func testMissingSourceAndWrongAccountStayUnverified() {
        let absent = SalaryDatedWorkTimeQualificationV2.assess(
            session: session(), owner: owner, calendar: paris,
            requiredTopics: [.timeAccounting], rules: [], sourceReliable: true
        )
        XCTAssertFalse(absent.reliable)
        XCTAssertTrue(absent.warnings.contains(SalaryDatedWorkRuleApplicabilityV2.missingWarning))

        let someoneElse = SalaryWorkRuleOwnerV2(
            accountId: "account-other", employeeId: owner.employeeId,
            employerId: owner.employerId, contractVersionId: owner.contractVersionId
        )
        let wrong = SalaryDatedWorkTimeQualificationV2.assess(
            session: session(), owner: someoneElse, calendar: paris,
            requiredTopics: [.timeAccounting], rules: [policy()], sourceReliable: true
        )
        XCTAssertFalse(wrong.reliable)
        XCTAssertTrue(wrong.selectedSourceIds.isEmpty)
    }

    func testNightSpanningTwoPoliciesNeedsSegmentedCalculation() {
        let result = SalaryDatedWorkTimeQualificationV2.assess(
            session: session(start: at(day: 8, hour: 22), end: at(day: 9, hour: 6)),
            owner: owner, calendar: paris, requiredTopics: [.timeAccounting],
            rules: [policy(id: "before", until: startDay + 1),
                    policy(id: "after", from: startDay + 1)],
            sourceReliable: true
        )
        XCTAssertEqual(result.time.paidDuration, 8 * 3_600, accuracy: 0.01)
        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.warnings.contains(SalaryDatedWorkTimeQualificationV2.multipleRulesWarning))
    }
}
