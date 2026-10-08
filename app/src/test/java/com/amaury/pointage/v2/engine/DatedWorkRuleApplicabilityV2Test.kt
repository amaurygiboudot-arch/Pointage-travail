package com.amaury.pointage.v2.engine

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DatedWorkRuleApplicabilityV2Test {
    private val october = LocalDate.of(2026, 10, 1).toEpochDay()
    private val november = LocalDate.of(2026, 11, 1).toEpochDay()
    private val owner = WorkRuleOwnerV2("account-1", "employee-1", "employer-a", "contract-v1")
    private val night = WorkRuleTopicV2.NIGHT_WORK
    private fun rule(
        id: String = "r1", owner: WorkRuleOwnerV2 = this.owner,
        topic: WorkRuleTopicV2 = night, from: Long = october, to: Long? = november,
        source: String = "collective-source-2026",
        checked: Long = 1_790_000_000_000L,
        status: WorkRuleConfirmationV2 = WorkRuleConfirmationV2.CONFIRMED,
        notApplicable: Boolean = false
    ) = DatedWorkRuleV2(
        id, owner, topic, from, to, source, "rule-article", checked, status, notApplicable
    )

    @Test fun exactDayBoundarySelectsOnlyVersionInEffect() {
        val first = rule()
        val second = rule("r2", from = november, to = null)
        assertTrue(DatedWorkRuleApplicabilityV2.validTimeline(owner, listOf(first, second)))
        val octoberLast = DatedWorkRuleApplicabilityV2.resolveDay(owner, night, november - 1,
            listOf(first, second), true)
        val novemberFirst = DatedWorkRuleApplicabilityV2.resolveDay(owner, night, november,
            listOf(first, second), true)
        assertEquals("r1", octoberLast.record?.id)
        assertEquals("r2", novemberFirst.record?.id)
        assertTrue(octoberLast.reliable)
        assertTrue(novemberFirst.reliable)
    }

    @Test fun dateGapIsUnknownNotZeroOrAnotherMonthRule() {
        val result = DatedWorkRuleApplicabilityV2.resolveDay(
            owner, night, november + 2, listOf(rule()), true
        )
        assertEquals(WorkRuleResolutionStateV2.MISSING, result.state)
        assertFalse(result.reliable)
        assertNull(result.record)
    }

    @Test fun sameJobDifferentEmployeeAccountEmployerAndContractNeverBorrowRules() {
        val existing = listOf(rule())
        val variants = listOf(
            owner.copy(accountId = "account-2"),
            owner.copy(employeeId = "employee-2"),
            owner.copy(employerId = "employer-b"),
            owner.copy(contractVersionId = "contract-v2")
        )
        variants.forEach {
            val result = DatedWorkRuleApplicabilityV2.resolveDay(it, night, october + 5, existing, true)
            assertEquals(WorkRuleResolutionStateV2.MISSING, result.state)
        }
    }

    @Test fun pendingRecordAlwaysBlocksEvenBesideConfirmedRecord() {
        val records = listOf(rule(), rule("draft", status = WorkRuleConfirmationV2.TO_CONFIRM))
        val result = DatedWorkRuleApplicabilityV2.resolveDay(owner, night, october + 5, records, true)
        assertEquals(WorkRuleResolutionStateV2.PENDING, result.state)
        assertFalse(result.reliable)
    }

    @Test fun conflictingConfirmedVersionsAreNeverArbitrarilyChosen() {
        val records = listOf(rule(), rule("collision", from = october + 5))
        assertFalse(DatedWorkRuleApplicabilityV2.validTimeline(owner, records))
        val result = DatedWorkRuleApplicabilityV2.resolveDay(owner, night, october + 10, records, true)
        assertEquals(WorkRuleResolutionStateV2.CONFLICT, result.state)
    }

    @Test fun missingOrUnconfirmedSourceAndCorruptStorageAreUnreliable() {
        assertFalse(DatedWorkRuleApplicabilityV2.validRecord(rule(source = "")))
        assertFalse(DatedWorkRuleApplicabilityV2.validRecord(rule(checked = 0)))
        assertFalse(DatedWorkRuleApplicabilityV2.validTimeline(owner, listOf(rule("r"), rule("r"))))
        assertEquals(WorkRuleResolutionStateV2.INVALID,
            DatedWorkRuleApplicabilityV2.resolveDay(owner, night, october, listOf(rule()), false).state)
    }

    @Test fun twoConfirmedRulesRequireDatedSegmentsAndNeverOneBlendedRate() {
        val records = listOf(
            rule("a", to = october + 10),
            rule("b", from = october + 10, to = november)
        )
        val result = DatedWorkRuleApplicabilityV2.resolvePeriod(
            owner, night, october, november, records, true
        )
        assertTrue(result.reliable)
        assertTrue(result.requiresSegmentedCalculation)
        assertEquals(2, result.segments.size)
        assertEquals(october + 10, result.segments.first().endExclusiveEpochDay)
        assertEquals(october + 10, result.segments.last().startEpochDay)
        assertTrue(result.warnings.contains(DatedWorkRuleApplicabilityV2.SEGMENTED_WARNING))
    }

    @Test fun incompletePeriodIsNeverReportedAsFullyCovered() {
        val result = DatedWorkRuleApplicabilityV2.resolvePeriod(
            owner, night, october, october + 8,
            listOf(rule(to = october + 3)), true
        )
        assertFalse(result.reliable)
        assertEquals(1, result.segments.size)
        assertEquals(october + 3, result.segments.first().endExclusiveEpochDay)
    }

    @Test fun explicitlyNotApplicableRequiresOwnConfirmedSourceNotImplicitZero() {
        val result = DatedWorkRuleApplicabilityV2.resolveDay(owner, WorkRuleTopicV2.MEAL_ALLOWANCE,
            october + 1, listOf(rule(topic = WorkRuleTopicV2.MEAL_ALLOWANCE, notApplicable = true)), true)
        assertTrue(result.reliable)
        assertTrue(result.record!!.explicitlyNotApplicable)
        assertEquals("collective-source-2026", result.record!!.sourceId)
    }
}
