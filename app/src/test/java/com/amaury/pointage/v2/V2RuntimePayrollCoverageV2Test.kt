package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2RuntimePayrollCoverageV2Test {
    @Test
    fun confirmedSnapshotMakesSourceExhaustive() {
        val sessions = listOf(session("s1", 2))
        val read = V2RuntimeReader.SessionsRead(sessions, true, emptyList())
        val attestation = attestation(sessions, checkedAtMs = ms(8, 0))

        val source = V2RuntimePayrollCoverageV2.sourceFrom(
            read, attestation, "company", 0, 6, "UTC", ms(8, 1)
        )

        assertTrue(source.reliable)
        assertTrue(source.exhaustive)
        assertEquals(attestation.sourceId, source.sourceId)
        assertTrue(source.warnings.isEmpty())
    }

    @Test
    fun missingAttestationNeverTurnsReliableReadIntoExhaustiveHistory() {
        val source = V2RuntimePayrollCoverageV2.sourceFrom(
            V2RuntimeReader.SessionsRead(listOf(session("s1", 2)), true, emptyList()),
            null,
            "company",
            0,
            6,
            "UTC",
            ms(8, 1)
        )

        assertTrue(source.reliable)
        assertFalse(source.exhaustive)
        assertTrue(source.warnings.contains(V2RuntimePayrollCoverageV2.MISSING_ATTESTATION_WARNING))
    }

    @Test
    fun changingPauseInsideCoveredRangeInvalidatesAttestation() {
        val original = listOf(session("s1", 2))
        val attestation = attestation(original, checkedAtMs = ms(8, 0))
        val changed = original.single().copy(
            pauses = listOf(
                PauseV2(
                    startMs = ms(2, 12),
                    endMs = ms(2, 12, 30),
                    paid = false,
                    source = EventSourceV2.MANUAL
                )
            )
        )

        val source = V2RuntimePayrollCoverageV2.sourceFrom(
            V2RuntimeReader.SessionsRead(listOf(changed), true, emptyList()),
            attestation,
            "company",
            0,
            6,
            "UTC",
            ms(8, 1)
        )

        assertFalse(source.exhaustive)
        assertTrue(source.warnings.contains(V2RuntimePayrollCoverageV2.STALE_ATTESTATION_WARNING))
    }

    @Test
    fun sessionAddedCompletelyOutsideCoveredRangeDoesNotInvalidatePastCoverage() {
        val original = listOf(session("s1", 2))
        val attestation = attestation(original, checkedAtMs = ms(8, 0))
        val later = session("later", 20)

        val source = V2RuntimePayrollCoverageV2.sourceFrom(
            V2RuntimeReader.SessionsRead(original + later, true, emptyList()),
            attestation,
            "company",
            0,
            6,
            "UTC",
            ms(21, 0)
        )

        assertTrue(source.exhaustive)
    }

    @Test
    fun unreliableRuntimeAlwaysBlocksExhaustivityEvenWithMatchingFingerprint() {
        val sessions = listOf(session("s1", 2))
        val attestation = attestation(sessions, checkedAtMs = ms(8, 0))

        val source = V2RuntimePayrollCoverageV2.sourceFrom(
            V2RuntimeReader.SessionsRead(sessions, false, listOf("runtime-broken")),
            attestation,
            "company",
            0,
            6,
            "UTC",
            ms(8, 1)
        )

        assertFalse(source.reliable)
        assertFalse(source.exhaustive)
        assertTrue(source.warnings.contains("runtime-broken"))
    }

    @Test
    fun futureAttestationIsRejected() {
        val sessions = listOf(session("s1", 2))
        val future = attestation(sessions, checkedAtMs = ms(9, 0))

        val source = V2RuntimePayrollCoverageV2.sourceFrom(
            V2RuntimeReader.SessionsRead(sessions, true, emptyList()),
            future,
            "company",
            0,
            6,
            "UTC",
            ms(8, 0)
        )

        assertFalse(source.exhaustive)
        assertTrue(source.warnings.contains(V2RuntimePayrollCoverageV2.STALE_ATTESTATION_WARNING))
    }

    private fun attestation(
        sessions: List<WorkSessionV2>,
        checkedAtMs: Long
    ): PayrollCoverageAttestationV2 =
        PayrollCoverageAttestationV2(
            sourceId = "coverage-test",
            employerId = "company",
            coveredStartEpochDay = 0,
            coveredEndEpochDay = 6,
            checkedAtMs = checkedAtMs,
            timeZoneId = "UTC",
            snapshotId = requireNotNull(
                V2RuntimePayrollCoverageV2.snapshotId(sessions, 0, 6, "UTC")
            )
        )

    private fun session(id: String, day: Long): WorkSessionV2 =
        WorkSessionV2(
            id = id,
            employerId = "company",
            realArrivalMs = ms(day, 8),
            countedEntryMs = ms(day, 8),
            countedExitMs = ms(day, 16),
            realExitMs = ms(day, 16),
            status = SessionStatusV2.CLOSED
        )

    private fun ms(day: Long, hour: Int, minute: Int = 0): Long =
        LocalDate.ofEpochDay(day)
            .atTime(hour, minute)
            .atZone(ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()
}
