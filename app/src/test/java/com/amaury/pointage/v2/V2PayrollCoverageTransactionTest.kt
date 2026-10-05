package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.PayrollCoverageAttestationV2
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class V2PayrollCoverageTransactionTest {
    private val start = LocalDate.of(2026,9,21).toEpochDay()
    private val end = start + 6
    private val checkedAt = LocalDate.ofEpochDay(end + 1).atStartOfDay(ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()

    @Test fun confirmationRereadsCoverageAfterRestorePlanIsApplied() {
        val executor = Executors.newFixedThreadPool(2)
        val restorePrepared = CountDownLatch(1)
        val confirmationAttempted = CountDownLatch(1)
        var stored = emptyList<PayrollCoverageAttestationV2>()
        val recovered = confirm("restored", { emptyList() }, { true })!!
        try {
            val restore = executor.submit<Boolean> {
                V2RuntimeStore.withTransaction {
                    val plan = V2BackupManager.mergeCoverageAttestations(stored,listOf(recovered))
                    restorePrepared.countDown()
                    assertTrue(confirmationAttempted.await(5,TimeUnit.SECONDS))
                    V2PayrollCoverageStore.replaceAllForRestore(plan) {
                        assertTrue(Thread.holdsLock(V2RuntimeStore))
                        stored = it
                        true
                    }
                }
            }
            assertTrue(restorePrepared.await(5,TimeUnit.SECONDS))
            val confirmation = executor.submit<PayrollCoverageAttestationV2?> {
                confirmationAttempted.countDown()
                confirm("new", { stored }, { stored = it; true })
            }
            assertTrue(restore.get(5,TimeUnit.SECONDS))
            assertNotNull(confirmation.get(5,TimeUnit.SECONDS))
            assertEquals(setOf("restored","new"),stored.map { it.employerId }.toSet())
            // Reconfirming one range replaces it; it does not duplicate or remove another employer.
            assertNotNull(confirm("new", { stored }, { stored = it; true }))
            assertEquals(2,stored.size)
        } finally { executor.shutdownNow() }
    }

    @Test fun directRestoreCallerAlsoHoldsRuntimeTransaction() {
        val proof = confirm("company", { emptyList() }, { true })!!
        assertTrue(V2PayrollCoverageStore.replaceAllForRestore(listOf(proof)) {
            assertTrue(Thread.holdsLock(V2RuntimeStore))
            assertEquals(listOf(proof),it)
            true
        })
    }

    @Test fun failedWriteCannotAnnounceSuccessfulConfirmation() {
        assertNull(confirm("company", { emptyList() }, { false }))
    }

    private fun confirm(employer: String, read: () -> List<PayrollCoverageAttestationV2>,
                        write: (List<PayrollCoverageAttestationV2>) -> Boolean) =
        V2PayrollCoverageStore.saveConfirmed(employer,start,end,checkedAt,"Europe/Paris",checkedAt,
            readSessions = {
                // Executes the same callback path used by saveConfirmed(Context,...).
                assertTrue(Thread.holdsLock(V2RuntimeStore))
                V2RuntimeReader.SessionsRead(emptyList(),true,emptyList())
            }, readCoverage = {
                assertTrue(Thread.holdsLock(V2RuntimeStore))
                PayrollCoverageReadResultV2(read(),true,emptyList())
            }, writeCoverage = {
                assertTrue(Thread.holdsLock(V2RuntimeStore))
                write(it)
            })
}
