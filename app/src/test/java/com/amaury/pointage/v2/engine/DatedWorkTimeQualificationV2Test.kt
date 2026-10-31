package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.DatedWorkRuleStoreV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.TimeBasisV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DatedWorkTimeQualificationV2Test {
    private val zone = ZoneId.of("Europe/Paris")
    private val oct8 = LocalDate.of(2026, 10, 8).toEpochDay()
    private val owner = WorkRuleOwnerV2("account-1", "employee-1", "employer-1", "contract-1")
    private fun at(day: Int, hour: Int) = LocalDate.of(2026, 10, day).atTime(hour, 0)
        .atZone(zone).toInstant().toEpochMilli()

    private fun rule(topic: WorkRuleTopicV2, id: String = topic.name,
                     from: Long = oct8, to: Long? = null) =
        DatedWorkRuleV2(id, owner, topic, from, to, "verified-source-${topic.name}",
            "rule-article", at(8, 12), WorkRuleConfirmationV2.CONFIRMED)

    private fun session(start: Long = at(8, 8), end: Long = at(8, 16)) =
        WorkSessionV2("s", owner.employerId, start, start, end, end,
            status = SessionStatusV2.CLOSED, timeBasis = TimeBasisV2.REAL_FACTS)

    @Test fun checkedRuleSourcesPreserveActualMinutesWithoutInventingPremium() {
        val rules = listOf(rule(WorkRuleTopicV2.TIME_ACCOUNTING), rule(WorkRuleTopicV2.PAUSE_COMPENSATION))
        val output = DatedWorkTimeQualificationV2.assess(
            session(), owner, zone,
            setOf(WorkRuleTopicV2.TIME_ACCOUNTING, WorkRuleTopicV2.PAUSE_COMPENSATION),
            rules, true
        )
        assertEquals(8L * 3_600_000L, output.time.paidWorkMs)
        assertTrue(output.reliable)
        assertEquals("verified-source-TIME_ACCOUNTING",
            output.selectedSourceIds[WorkRuleTopicV2.TIME_ACCOUNTING])
    }

    @Test fun missingSourceOrUnknownAccountNeverCertifies() {
        val onlyAccounting = listOf(rule(WorkRuleTopicV2.TIME_ACCOUNTING))
        val insufficient = DatedWorkTimeQualificationV2.assess(session(), owner, zone,
            setOf(WorkRuleTopicV2.TIME_ACCOUNTING, WorkRuleTopicV2.PAUSE_COMPENSATION),
            onlyAccounting, true)
        assertFalse(insufficient.reliable)
        assertTrue(insufficient.warnings.contains(DatedWorkRuleApplicabilityV2.MISSING_WARNING))
        val other = DatedWorkTimeQualificationV2.assess(session(), owner.copy(accountId = "other"),
            zone, setOf(WorkRuleTopicV2.TIME_ACCOUNTING), onlyAccounting, true)
        assertFalse(other.reliable)
        assertTrue(other.selectedSourceIds.isEmpty())
    }

    @Test fun ruleChangeWithinSingleNightRequiresSegmentedQualification() {
        val rules = listOf(
            rule(WorkRuleTopicV2.TIME_ACCOUNTING, "before", oct8, oct8 + 1),
            rule(WorkRuleTopicV2.TIME_ACCOUNTING, "after", oct8 + 1, null)
        )
        val overnight = session(start = at(8, 22), end = at(9, 6))
        val output = DatedWorkTimeQualificationV2.assess(overnight, owner, zone,
            setOf(WorkRuleTopicV2.TIME_ACCOUNTING), rules, true)
        assertEquals(8L * 3_600_000L, output.time.paidWorkMs)
        assertFalse(output.reliable)
        assertTrue(output.warnings.contains(DatedWorkTimeQualificationV2.MULTIPLE_RULES_WARNING))
    }

    @Test fun ownerScopedStorageRoundtripRejectsForeignScopeAndCorruption() {
        val records = listOf(rule(WorkRuleTopicV2.TIME_ACCOUNTING))
        val raw = DatedWorkRuleStoreV2.encode(owner, records)
        val correct = DatedWorkRuleStoreV2.decode(raw, owner)
        assertTrue(correct.reliable)
        assertEquals(records, correct.records)
        assertFalse(DatedWorkRuleStoreV2.decode(raw, owner.copy(employeeId = "other")).reliable)
        assertFalse(DatedWorkRuleStoreV2.decode("{broken}", owner).reliable)
        assertNotEquals(
            DatedWorkRuleStoreV2.ownerStorageKey(owner),
            DatedWorkRuleStoreV2.ownerStorageKey(owner.copy(accountId = "other"))
        )
    }
}
