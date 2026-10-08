package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DatedWorkRuleOwnerAuthorizationV2Test {
    private val october = LocalDate.of(2026, 10, 1).toEpochDay()
    private val owner = DatedWorkRuleOwnerAuthorizationV2.ownerForSelf("firebase-uid-a", "company-a", "v-1")!!
    private val snapshot = EmploymentContractSnapshotV2(
        "v-1", "contract-source", october, october + 20,
        ContractV2("contract-a", "company-a", ContractTypeV2.FULL_TIME,
            2100, 15.0, october - 50), 1_790_000_000_000L
    )
    private fun rule(topic: WorkRuleTopicV2, confirmation: WorkRuleConfirmationV2,
                     from: Long = october, to: Long? = october + 21,
                     sourceId: String = snapshot.sourceId,
                     ref: String = "contract:v-1") = DatedWorkRuleV2(
        "rule", owner, topic, from, to, sourceId, ref,
        snapshot.checkedAtMs, confirmation
    )

    @Test fun disconnectedOrDifferentFirebaseUserCannotOwnRules() {
        assertNull(DatedWorkRuleOwnerAuthorizationV2.ownerForSelf(null, "company-a", "v-1"))
        assertFalse(DatedWorkRuleOwnerAuthorizationV2.authorizes(owner, null))
        assertFalse(DatedWorkRuleOwnerAuthorizationV2.authorizes(owner, "firebase-uid-b"))
        assertFalse(DatedWorkRuleOwnerAuthorizationV2.authorizes(
            owner.copy(employeeId = "another-person"), "firebase-uid-a"
        ))
        assertTrue(DatedWorkRuleOwnerAuthorizationV2.authorizes(owner, "firebase-uid-a"))
    }

    @Test fun exactVersionBoundaryMustCoverEntireRuleDateRange() {
        val r = rule(WorkRuleTopicV2.TIME_ACCOUNTING, WorkRuleConfirmationV2.CONFIRMED)
        assertTrue(DatedWorkRuleContractScopeV2.containedInContract(r, snapshot))
        assertFalse(DatedWorkRuleContractScopeV2.containedInContract(
            r.copy(effectiveToEpochDay = null), snapshot
        ))
        assertFalse(DatedWorkRuleContractScopeV2.containedInContract(
            r.copy(effectiveToEpochDay = october + 22), snapshot
        ))
        assertFalse(DatedWorkRuleContractScopeV2.containedInContract(
            r.copy(effectiveFromEpochDay = october - 1), snapshot
        ))
        assertFalse(DatedWorkRuleContractScopeV2.containedInContract(
            r.copy(owner = owner.copy(contractVersionId = "v-2")), snapshot
        ))
    }

    @Test fun freeTextCannotConfirmPremiumButCanRemainVisibleAsDraft() {
        assertTrue(DatedWorkRuleContractScopeV2.verifiedApplicability(
            rule(WorkRuleTopicV2.TIME_ACCOUNTING, WorkRuleConfirmationV2.CONFIRMED), snapshot
        ))
        assertFalse(DatedWorkRuleContractScopeV2.verifiedApplicability(
            rule(WorkRuleTopicV2.NIGHT_WORK, WorkRuleConfirmationV2.CONFIRMED), snapshot
        ))
        assertFalse(DatedWorkRuleContractScopeV2.verifiedApplicability(
            rule(WorkRuleTopicV2.TIME_ACCOUNTING, WorkRuleConfirmationV2.CONFIRMED,
                ref = "random-rule-article"), snapshot
        ))
        assertTrue(DatedWorkRuleContractScopeV2.verifiedApplicability(
            rule(WorkRuleTopicV2.NIGHT_WORK, WorkRuleConfirmationV2.TO_CONFIRM,
                ref = "article à vérifier"), snapshot
        ))
    }
}
