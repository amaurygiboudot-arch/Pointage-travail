import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryDatedWorkRuleApplicabilityV2Tests: XCTestCase {
    private let october: Int64 = 20_727
    private let november: Int64 = 20_758
    private let owner = SalaryWorkRuleOwnerV2(
        accountId: "account-1", employeeId: "employee-1",
        employerId: "employer-a", contractVersionId: "contract-v1"
    )

    private func record(_ id: String = "r1",
                        owner: SalaryWorkRuleOwnerV2? = nil,
                        from: Int64 = 20_727, to: Int64? = 20_758,
                        topic: SalaryWorkRuleTopicV2 = .nightWork,
                        confirmation: SalaryWorkRuleConfirmationV2 = .confirmed,
                        source: String = "collective-source-2026",
                        checked: Int64 = 1_790_000_000_000,
                        notApplicable: Bool = false) -> SalaryDatedWorkRuleV2 {
        SalaryDatedWorkRuleV2(
            id: id, owner: owner ?? self.owner, topic: topic,
            effectiveFromEpochDay: from, effectiveToEpochDay: to,
            sourceId: source, ruleReference: "rule-article",
            checkedAtMs: checked, confirmation: confirmation,
            explicitlyNotApplicable: notApplicable
        )
    }

    func testExclusiveBoundaryPreservesBothVersions() {
        let records = [record(), record("r2", from: november, to: nil)]
        XCTAssertTrue(SalaryDatedWorkRuleApplicabilityV2.validTimeline(owner, records: records))
        let old = SalaryDatedWorkRuleApplicabilityV2.resolveDay(
            owner: owner, topic: .nightWork, epochDay: november - 1,
            records: records, sourceReliable: true
        )
        let new = SalaryDatedWorkRuleApplicabilityV2.resolveDay(
            owner: owner, topic: .nightWork, epochDay: november,
            records: records, sourceReliable: true
        )
        XCTAssertEqual(old.record?.id, "r1")
        XCTAssertEqual(new.record?.id, "r2")
        XCTAssertTrue(old.reliable)
        XCTAssertTrue(new.reliable)
    }

    func testDifferentAccountsEmployeesEmployersAndContractsCannotBorrowPolicy() {
        let variants = [
            SalaryWorkRuleOwnerV2(accountId: "account-2", employeeId: "employee-1", employerId: "employer-a", contractVersionId: "contract-v1"),
            SalaryWorkRuleOwnerV2(accountId: "account-1", employeeId: "employee-2", employerId: "employer-a", contractVersionId: "contract-v1"),
            SalaryWorkRuleOwnerV2(accountId: "account-1", employeeId: "employee-1", employerId: "employer-b", contractVersionId: "contract-v1"),
            SalaryWorkRuleOwnerV2(accountId: "account-1", employeeId: "employee-1", employerId: "employer-a", contractVersionId: "contract-v2")
        ]
        for candidate in variants {
            let resolution = SalaryDatedWorkRuleApplicabilityV2.resolveDay(
                owner: candidate, topic: .nightWork, epochDay: october + 5,
                records: [record()], sourceReliable: true
            )
            XCTAssertEqual(resolution.state, .missing)
            XCTAssertFalse(resolution.reliable)
        }
    }

    func testNoDateCoverageAndPendingAreNotZero() {
        let empty = SalaryDatedWorkRuleApplicabilityV2.resolveDay(
            owner: owner, topic: .nightWork, epochDay: november + 2,
            records: [record()], sourceReliable: true
        )
        XCTAssertEqual(empty.state, .missing)
        let draft = SalaryDatedWorkRuleApplicabilityV2.resolveDay(
            owner: owner, topic: .nightWork, epochDay: october,
            records: [record(), record("draft", confirmation: .toConfirm)],
            sourceReliable: true
        )
        XCTAssertEqual(draft.state, .pending)
    }

    func testConfirmedConflictIsNotResolvedByListOrdering() {
        let overlapping = [record(), record("conflict", from: october + 5)]
        XCTAssertFalse(SalaryDatedWorkRuleApplicabilityV2.validTimeline(owner, records: overlapping))
        let result = SalaryDatedWorkRuleApplicabilityV2.resolveDay(
            owner: owner, topic: .nightWork, epochDay: october + 8,
            records: overlapping, sourceReliable: true
        )
        XCTAssertEqual(result.state, .conflict)
    }

    func testUnknownSourceAndUnreliableStorageBlockCertification() {
        XCTAssertFalse(SalaryDatedWorkRuleApplicabilityV2.validRecord(record(source: "")))
        XCTAssertFalse(SalaryDatedWorkRuleApplicabilityV2.validRecord(record(checked: 0)))
        let result = SalaryDatedWorkRuleApplicabilityV2.resolveDay(
            owner: owner, topic: .nightWork, epochDay: october,
            records: [record()], sourceReliable: false
        )
        XCTAssertEqual(result.state, .invalid)
    }

    func testContractMidMonthChangeProducesTwoRuleSegments() {
        let records = [record("first", to: october + 10), record("next", from: october + 10)]
        let period = SalaryDatedWorkRuleApplicabilityV2.resolvePeriod(
            owner: owner, topic: .nightWork,
            fromEpochDay: october, toExclusiveEpochDay: november,
            records: records, sourceReliable: true
        )
        XCTAssertTrue(period.reliable)
        XCTAssertTrue(period.requiresSegmentedCalculation)
        XCTAssertEqual(period.segments.count, 2)
        XCTAssertEqual(period.segments[0].endExclusiveEpochDay, october + 10)
        XCTAssertEqual(period.segments[1].startEpochDay, october + 10)
    }

    func testMissingPeriodDaysStayUnverified() {
        let period = SalaryDatedWorkRuleApplicabilityV2.resolvePeriod(
            owner: owner, topic: .nightWork, fromEpochDay: october,
            toExclusiveEpochDay: october + 8,
            records: [record(to: october + 3)], sourceReliable: true
        )
        XCTAssertFalse(period.reliable)
        XCTAssertEqual(period.segments.count, 1)
    }

    func testCrossPlatformJSONNamesAndOwnerIsolation() {
        let rule = record(topic: .mealAllowance, notApplicable: true)
        guard let raw = SalaryDatedWorkRuleStoreV2.encode([rule], owner: owner),
              let snapshot = SalaryDatedWorkRuleStoreV2.decode(raw, owner: owner) else {
            return XCTFail("Serialization missing")
        }
        XCTAssertTrue(snapshot.reliable)
        XCTAssertEqual(snapshot.records, [rule])
        XCTAssertTrue(raw.contains("\"fromDay\""))
        XCTAssertTrue(raw.contains("\"notApplicable\""))
        let stranger = SalaryWorkRuleOwnerV2(
            accountId: "account-2", employeeId: owner.employeeId,
            employerId: owner.employerId, contractVersionId: owner.contractVersionId
        )
        XCTAssertNil(SalaryDatedWorkRuleStoreV2.decode(raw, owner: stranger))
        XCTAssertNotEqual(SalaryDatedWorkRuleStoreV2.ownerStorageKey(owner),
                          SalaryDatedWorkRuleStoreV2.ownerStorageKey(stranger))
    }
}
