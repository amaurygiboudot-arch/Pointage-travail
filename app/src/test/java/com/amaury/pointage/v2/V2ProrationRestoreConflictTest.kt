package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConfirmedProrationSegmentV2
import com.amaury.pointage.v2.engine.ConfirmedSegmentedMonthlyProrationV2
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class V2ProrationRestoreConflictTest {
    private val prefix = "salary_segmented_proration_v2."
    private val key = prefix + "company.2026-09"
    private fun value(minutes: Int = 2100): String {
        val period = YearMonth.of(2026,9)
        return V2SegmentedProrationStore.encode(ConfirmedSegmentedMonthlyProrationV2(
            "confirmed-planning",1234L,segments=listOf(ConfirmedProrationSegmentV2(
                "contract",period.atDay(1).toEpochDay(),period.atEndOfMonth().toEpochDay(),minutes))))!!
    }
    private fun payload(values: Map<String,String>): JSONObject = JSONObject().apply {
        values.forEach { (key, raw) -> put(key,JSONObject().put("t","s").put("v",raw)) }
    }

    @Test fun actualRestorePlanningRejectsConflictBeforeAnyOtherPreferenceWrite() {
        val current = mapOf(key to value())
        var applied = false
        val result = runCatching {
            V2BackupManager.withProrationRestorePlan(payload(mapOf(key to value(2400))),{ current }) {
                applied = true
                error("No preference, runtime or planning write may start")
            }
        }
        assertTrue(result.isFailure)
        assertFalse(applied)
        assertEquals(value(),current[key])
    }

    @Test fun serializedBackupAddsMissingEmployerAndKeepsLocalMonthIdempotently() {
        val current = mapOf(key to value())
        val other = prefix + "another-company.2026-09"
        val backup = JSONObject(payload(mapOf(key to JSONObject(value()).toString(2),other to value(1800))).toString())
        var written = emptyMap<String,String>()
        val count = V2BackupManager.withProrationRestorePlan(backup,{ current }) { plan ->
            assertTrue(Thread.holdsLock(V2RuntimeStore))
            written = requireNotNull(plan)
            written.size
        }
        assertEquals(2,count)
        assertEquals(current[key],written[key])
        assertEquals(1800,V2SegmentedProrationStore.decode(written.getValue(other))!!.segments.single().scheduledMinutes)
        V2BackupManager.withProrationRestorePlan(backup,{ written }) { assertEquals(written,it) }
    }

    @Test fun invalidKeysAndInvalidLocalFactsBlockBeforeRestorationMutations() {
        for (badKey in listOf("unrelated",prefix+".2026-09",prefix+"company.2026-13",prefix+"company.2026-9")) {
            var applied = false
            assertTrue(runCatching {
                V2BackupManager.withProrationRestorePlan(payload(mapOf(badKey to value())),{ emptyMap() }) {
                    applied = true
                }
            }.isFailure)
            assertFalse(applied)
        }
        var applied = false
        assertTrue(runCatching {
            V2BackupManager.withProrationRestorePlan(payload(emptyMap()),{ mapOf(key to "broken") }) {
                applied = true
            }
        }.isFailure)
        assertFalse(applied)
        assertFalse(V2BackupManager.isRestorablePreferencePayload(V2SegmentedProrationStore.PREFS,
            payload(mapOf("invalid" to value()))))
    }

    @Test fun competingRestoreRereadsPlanningAndRejectsConflictAfterFirstCommit() {
        val executor = Executors.newFixedThreadPool(2)
        val firstRead = CountDownLatch(1)
        val secondAttempted = CountDownLatch(1)
        var stored = emptyMap<String,String>()
        try {
            val first = executor.submit<Boolean> {
                V2BackupManager.withProrationRestorePlan(payload(mapOf(key to value())), {
                    assertTrue(Thread.holdsLock(V2RuntimeStore))
                    val snapshot = stored
                    firstRead.countDown()
                    assertTrue(secondAttempted.await(5,TimeUnit.SECONDS))
                    snapshot
                }) { stored = requireNotNull(it); true }
            }
            assertTrue(firstRead.await(5,TimeUnit.SECONDS))
            val second = executor.submit<Boolean> {
                secondAttempted.countDown()
                runCatching {
                    V2BackupManager.withProrationRestorePlan(payload(mapOf(key to value(2400))), {
                        assertTrue(Thread.holdsLock(V2RuntimeStore))
                        stored
                    }) { stored = requireNotNull(it) }
                }.isSuccess
            }
            assertTrue(first.get(5,TimeUnit.SECONDS))
            assertFalse(second.get(5,TimeUnit.SECONDS))
            assertEquals(mapOf(key to value()),stored)
        } finally { executor.shutdownNow() }
    }

    @Test fun historicalBackupWithoutProrationDoesNotReadOrReplaceLocalPlanning() {
        var applied = false
        V2BackupManager.withProrationRestorePlan(null,{ error("No planning payload to merge") }) {
            assertNull(it)
            applied = true
        }
        assertTrue(applied)
    }
}
