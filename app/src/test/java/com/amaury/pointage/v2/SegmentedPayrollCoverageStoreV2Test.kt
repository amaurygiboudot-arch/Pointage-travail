package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SegmentedPayrollCoverageStoreV2Test {
    private val zone = "UTC"
    private val now = ms(30, 12)

    @Test
    fun reliableRuntimeWithoutAttestationNeverBecomesExhaustive() {
        val source = SegmentedPayrollCoverageStoreV2.resolveSource(
            sessions = listOf(session("s", 4, 8, 4, 16)),
            runtimeReliable = true,
            runtimeWarnings = emptyList(),
            coverage = SegmentedPayrollCoverageReadV2(emptyList(), true, emptyList()),
            employerId = "company",
            requiredStartEpochDay = 4,
            requiredEndEpochDay = 10,
            timeZoneId = zone,
            nowMs = now
        )

        assertTrue(source.reliable)
        assertFalse(source.exhaustive)
        assertTrue(source.warnings.contains(SegmentedPayrollCoverageStoreV2.MISSING_WARNING))
    }

    @Test
    fun matchingAttestationProducesExhaustiveSource() {
        val sessions = listOf(session("s", 4, 8, 4, 16))
        val attestation = attestation("a", 4, 10, sessions)
        val source = resolve(sessions, listOf(attestation), 4, 10)

        assertTrue(source.reliable)
        assertTrue(source.exhaustive)
        assertEquals(4L, source.coveredStartEpochDay)
        assertEquals(10L, source.coveredEndEpochDay)
        assertTrue(source.sourceId.startsWith("coverage-"))
    }

    @Test
    fun editInsideCoveredRangeInvalidatesAttestation() {
        val original = listOf(session("s", 4, 8, 4, 16))
        val attestation = attestation("a", 4, 10, original)
        val edited = listOf(session("s", 4, 8, 4, 17))
        val source = resolve(edited, listOf(attestation), 4, 10)

        assertFalse(source.exhaustive)
        assertTrue(source.warnings.contains(SegmentedPayrollCoverageStoreV2.STALE_WARNING))
    }

    @Test
    fun newSessionOutsideCoveredRangeDoesNotInvalidateAttestation() {
        val original = listOf(session("inside", 4, 8, 4, 16))
        val attestation = attestation("a", 4, 10, original)
        val expanded = original + session("outside", 20, 8, 20, 16)
        val source = resolve(expanded, listOf(attestation), 4, 10)

        assertTrue(source.exhaustive)
    }

    @Test
    fun adjacentAttestationsCanCoverARequestedPeriodWithoutGap() {
        val sessions = listOf(
            session("s1", 4, 8, 4, 16),
            session("s2", 11, 8, 11, 16)
        )
        val first = attestation("a", 4, 10, sessions)
        val second = attestation("b", 11, 17, sessions)
        val source = resolve(sessions, listOf(first, second), 4, 17)

        assertTrue(source.exhaustive)
        assertEquals(4L, source.coveredStartEpochDay)
        assertEquals(17L, source.coveredEndEpochDay)
    }

    @Test
    fun oneMissingDayKeepsCoverageNonExhaustive() {
        val sessions = listOf(session("s", 4, 8, 4, 16))
        val first = attestation("a", 4, 9, sessions)
        val second = attestation("b", 11, 17, sessions)
        val source = resolve(sessions, listOf(first, second), 4, 17)

        assertFalse(source.exhaustive)
        assertTrue(source.warnings.contains(SegmentedPayrollCoverageStoreV2.MISSING_WARNING))
    }

    @Test
    fun attestationForAnotherEmployerDoesNotProveCoverage() {
        val sessions = listOf(session("s", 4, 8, 4, 16))
        val own = attestation("a", 4, 10, sessions).copy(employerId = "other")
        assertFalse(resolve(sessions, listOf(own), 4, 10).exhaustive)
    }

    @Test
    fun openSessionTouchingRangeCannotBeFingerprinted() {
        val open = session("s", 4, 8, 4, 16).copy(
            countedExitMs = null,
            realExitMs = null,
            status = SessionStatusV2.OPEN
        )
        assertNull(SegmentedPayrollCoverageStoreV2.fingerprint(listOf(open), 4, 10, zone, now))
    }

    @Test
    fun salaryRelevantPauseChangeInvalidatesFingerprint() {
        val original = session("s", 4, 8, 4, 16).copy(
            pauses = listOf(PauseV2(ms(4, 12), ms(4, 12, 30), false, EventSourceV2.MANUAL))
        )
        val changed = original.copy(
            pauses = listOf(PauseV2(ms(4, 12), ms(4, 12, 30), true, EventSourceV2.MANUAL))
        )
        val before = SegmentedPayrollCoverageStoreV2.fingerprint(listOf(original), 4, 10, zone, now)
        val after = SegmentedPayrollCoverageStoreV2.fingerprint(listOf(changed), 4, 10, zone, now)

        assertNotNull(before)
        assertNotEquals(before, after)
    }

    @Test
    fun codecRoundTripPreservesAttestationAndRejectsCorruption() {
        val sessions = listOf(session("s", 4, 8, 4, 16))
        val attestation = attestation("a", 4, 10, sessions)
        val decoded = SegmentedPayrollCoverageStoreV2.decode(
            SegmentedPayrollCoverageStoreV2.encode(listOf(attestation))
        )

        assertTrue(decoded.reliable)
        assertEquals(listOf(attestation), decoded.attestations)
        assertFalse(SegmentedPayrollCoverageStoreV2.decode("{bad").reliable)
    }

    @Test
    fun unreliableRuntimeNeverPublishesReliableCoverage() {
        val sessions = listOf(session("s", 4, 8, 4, 16))
        val source = SegmentedPayrollCoverageStoreV2.resolveSource(
            sessions = sessions,
            runtimeReliable = false,
            runtimeWarnings = listOf("runtime-corrupt"),
            coverage = SegmentedPayrollCoverageReadV2(
                listOf(attestation("a", 4, 10, sessions)),
                true,
                emptyList()
            ),
            employerId = "company",
            requiredStartEpochDay = 4,
            requiredEndEpochDay = 10,
            timeZoneId = zone,
            nowMs = now
        )

        assertFalse(source.reliable)
        assertFalse(source.exhaustive)
        assertTrue(source.warnings.contains("runtime-corrupt"))
    }

    private fun resolve(
        sessions: List<WorkSessionV2>,
        attestations: List<SegmentedPayrollCoverageAttestationV2>,
        start: Long,
        end: Long
    ) = SegmentedPayrollCoverageStoreV2.resolveSource(
        sessions = sessions,
        runtimeReliable = true,
        runtimeWarnings = emptyList(),
        coverage = SegmentedPayrollCoverageReadV2(attestations, true, emptyList()),
        employerId = "company",
        requiredStartEpochDay = start,
        requiredEndEpochDay = end,
        timeZoneId = zone,
        nowMs = now
    )

    private fun attestation(
        id: String,
        start: Long,
        end: Long,
        sessions: List<WorkSessionV2>
    ): SegmentedPayrollCoverageAttestationV2 {
        val fingerprint = requireNotNull(
            SegmentedPayrollCoverageStoreV2.fingerprint(sessions, start, end, zone, now)
        )
        return SegmentedPayrollCoverageAttestationV2(
            id = id,
            employerId = "company",
            coveredStartEpochDay = start,
            coveredEndEpochDay = end,
            confirmedAtMs = now,
            timeZoneId = zone,
            factFingerprint = fingerprint
        )
    }

    private fun session(
        id: String,
        day: Long,
        hour: Int,
        endDay: Long,
        endHour: Int
    ) = WorkSessionV2(
        id = id,
        employerId = "company",
        realArrivalMs = ms(day, hour),
        countedEntryMs = ms(day, hour),
        countedExitMs = ms(endDay, endHour),
        realExitMs = ms(endDay, endHour),
        status = SessionStatusV2.CLOSED
    )

    private fun ms(day: Long, hour: Int, minute: Int = 0): Long =
        LocalDate.ofEpochDay(day).atTime(hour, minute).atZone(ZoneId.of(zone))
            .toInstant().toEpochMilli()
}
