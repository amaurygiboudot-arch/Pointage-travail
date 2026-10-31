import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryDatedWorkRuleOwnerAuthorizationV2Tests: XCTestCase {
    private let start: Int64 = 20_727
    private var owner: SalaryWorkRuleOwnerV2 {
        SalaryDatedWorkRuleOwnerAuthorizationV2.ownerForSelf(
            authenticatedUid: "firebase-a", employerId: "company-a", contractVersionId: "version-1"
        )!
    }
    private var contract: SalaryEmploymentContractSnapshotV2 {
        SalaryEmploymentContractSnapshotV2(
            versionId: "version-1",
            sourceId: "contract-source",
            effectiveFromEpochDay: start,
            effectiveToEpochDay: start + 20,
            contract: ContractV2(
                id: "contract-id", employerId: "company-a", type: .fullTime,
                contractualWeeklyMinutes: 2100, grossHourlyRate: 15,
                hireDateEpochDay: start - 50
            ),
            checkedAtMs: 1_790_000_000_000,
            note: nil
        )
    }
    private func rule(
        topic: SalaryWorkRuleTopicV2 = .timeAccounting,
        confirmation: SalaryWorkRuleConfirmationV2 = .confirmed,
        from: Int64 = 20_727, until: Int64? = 20_748,
        reference: String = "contract:version-1"
    ) -> SalaryDatedWorkRuleV2 {
        SalaryDatedWorkRuleV2(
            id: "r", owner: owner, topic: topic, effectiveFromEpochDay: from,
            effectiveToEpochDay: until, sourceId: "contract-source",
            ruleReference: reference, checkedAtMs: 1_790_000_000_000,
            confirmation: confirmation
        )
    }

    func testLiveFirebaseAccountIsolation() {
        XCTAssertNil(SalaryDatedWorkRuleOwnerAuthorizationV2.ownerForSelf(
            authenticatedUid: nil, employerId: "company-a", contractVersionId: "version-1"
        ))
        XCTAssertFalse(SalaryDatedWorkRuleOwnerAuthorizationV2.authorizes(owner, authenticatedUid: nil))
        XCTAssertFalse(SalaryDatedWorkRuleOwnerAuthorizationV2.authorizes(
            owner, authenticatedUid: "firebase-b"
        ))
        XCTAssertTrue(SalaryDatedWorkRuleOwnerAuthorizationV2.authorizes(
            owner, authenticatedUid: "firebase-a"
        ))
    }

    func testContractVersionAndDatesMustMatch() {
        XCTAssertTrue(SalaryDatedWorkRuleContractScopeV2.containedInContract(rule(), contract: contract))
        XCTAssertFalse(SalaryDatedWorkRuleContractScopeV2.containedInContract(
            rule(until: nil), contract: contract
        ))
        XCTAssertFalse(SalaryDatedWorkRuleContractScopeV2.containedInContract(
            rule(until: start + 23), contract: contract
        ))
        XCTAssertFalse(SalaryDatedWorkRuleContractScopeV2.containedInContract(
            rule(from: start - 1), contract: contract
        ))
    }

    func testUnverifiedPremiumSourceCannotBecomeConfirmedRate() {
        XCTAssertTrue(SalaryDatedWorkRuleContractScopeV2.verifiedApplicability(
            rule(), contract: contract
        ))
        XCTAssertFalse(SalaryDatedWorkRuleContractScopeV2.verifiedApplicability(
            rule(topic: .nightWork), contract: contract
        ))
        XCTAssertFalse(SalaryDatedWorkRuleContractScopeV2.verifiedApplicability(
            rule(reference: "free-text"), contract: contract
        ))
        XCTAssertTrue(SalaryDatedWorkRuleContractScopeV2.verifiedApplicability(
            rule(topic: .nightWork, confirmation: .toConfirm),
            contract: contract
        ))
    }
}
