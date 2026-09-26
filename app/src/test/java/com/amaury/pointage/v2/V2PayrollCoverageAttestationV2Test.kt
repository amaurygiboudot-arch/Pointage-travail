package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class V2PayrollCoverageAttestationV2Test {
    private val zone = "Europe/Paris"
    private val weekStart = LocalDate.of(2026, 9, 21).toEpochDay()
    private val weekEnd = weekStart + 6
    private val checkedAt = LocalDate.of(2026, 9, 28).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test fun exactAttestationRemainsValidAndTimeEditInvalidatesIt() {
        val original = listOf(session("a", 0))
        val proof = attestation("00000000-0000-0000-0000-000000000001", weekStart, weekEnd, original)

        assertTrue(V2PayrollCoverageAttestationPolicyV2.isCurrent(proof, original, checkedAt + 1))

        val edited = listOf(session("a", 0).copy(realExitMs = ms(0, 17, 1), countedExitMs = ms(0, 17, 1)))
        assertFalse(V2PayrollCoverageAttestationPolicyV2.isCurrent(proof, edited, checkedAt + 1))
    }

    @Test fun pauseEditOrUnassignedSessionInvalidatesCoverage() {
        val originalSession = session("a", 0).copy(
            pauses = listOf(PauseV2(ms(0, 12), ms(0, 13), false, EventSourceV2.MANUAL))
        )
        val original = listOf(originalSession)
        val proof = attestation("00000000-0000-0000-0000-000000000002", weekStart, weekEnd, original)

        val pauseEdited = listOf(originalSession.copy(
            pauses = listOf(PauseV2(ms(0, 12), ms(0, 13), true, EventSourceV2.MANUAL))
        ))
        assertFalse(V2PayrollCoverageAttestationPolicyV2.isCurrent(proof, pauseEdited, checkedAt + 1))

        val withUnassigned = original + session("unknown", 1).copy(employerId = null)
        assertFalse(V2PayrollCoverageAttestationPolicyV2.isCurrent(proof, withUnassigned, checkedAt + 1))
    }

    @Test fun otherEmployerAndOutOfRangeChangesDoNotInvalidateEmployerCoverage() {
        val original = listOf(session("a", 0))
        val first = fingerprint(original)
        val other = session("other", 1).copy(employerId = "company-b")
        val outside = session("outside", 20)
        val second = fingerprint(original + other + outside)
        assertEquals(first, second)
    }

    @Test fun adjacentAttestationsCoverRequestedWeek() {
        val sessions = listOf(session("a", 0), session("b", 3))
        val first = attestation("00000000-0000-0000-0000-000000000003", weekStart, weekStart + 2, sessions)
        val second = attestation("00000000-0000-0000-0000-000000000004", weekStart + 3, weekEnd, sessions)

        val result = resolve(listOf(second, first), sessions)
        assertTrue(result.reliable)
        assertTrue(result.exhaustive)
        assertTrue(result.sourceId.contains(first.id))
        assertTrue(result.sourceId.contains(second.id))
        assertTrue(result.warnings.isEmpty())
    }

    @Test fun gapRemainsNonExhaustive() {
        val sessions = listOf(session("a", 0))
        val first = attestation("00000000-0000-0000-0000-000000000005", weekStart, weekStart + 1, sessions)
        val second = attestation("00000000-0000-0000-0000-000000000006", weekStart + 3, weekEnd, sessions)

        val result = resolve(listOf(first, second), sessions)
        assertTrue(result.reliable)
        assertFalse(result.exhaustive)
        assertTrue(result.warnings.contains(V2PayrollCoverageAttestationPolicyV2.MISSING_WARNING))
    }

    @Test fun staleAttestationCannotCompleteCoverageChain() {
        val original = listOf(session("a", 0))
        val first = attestation("00000000-0000-0000-0000-000000000007", weekStart, weekStart + 2, original)
        val second = attestation("00000000-0000-0000-0000-000000000008", weekStart + 3, weekEnd, original)
        val changed = original + session("new", 4)

        val result = resolve(listOf(first, second), changed)
        assertTrue(result.reliable)
        assertFalse(result.exhaustive)
        assertTrue(result.warnings.contains(V2PayrollCoverageAttestationPolicyV2.STALE_WARNING))
    }

    @Test fun futureAttestationMakesResolutionUnreliable() {
        val sessions = listOf(session("a", 0))
        val proof = attestation(
            "00000000-0000-0000-0000-000000000009", weekStart, weekEnd, sessions
        ).copy(checkedAtMs = checkedAt + 10_000)

        val result = resolve(listOf(proof), sessions, now = checkedAt)
        assertFalse(result.reliable)
        assertFalse(result.exhaustive)
        assertTrue(result.warnings.contains(V2PayrollCoverageAttestationPolicyV2.CORRUPT_WARNING))
    }

    @Test fun reliableStoreAloneNeverProvesExhaustiveCoverage() {
        val result = resolve(emptyList(), listOf(session("a", 0)))
        assertTrue(result.reliable)
        assertFalse(result.exhaustive)
        assertTrue(result.warnings.contains(V2PayrollCoverageAttestationPolicyV2.MISSING_WARNING))
    }

    @Test fun unreliableRuntimeBlocksEvenWithMatchingAttestation() {
        val sessions = listOf(session("a", 0))
        val proof = attestation("00000000-0000-0000-0000-000000000010", weekStart, weekEnd, sessions)
        val result = V2PayrollCoverageAttestationStoreV2.resolve(
            attestations = listOf(proof),
            sessions = sessions,
            sourceReliable = false,
            sourceWarnings = listOf("runtime-broken"),
            employerId = "company-a",
            requestedStartEpochDay = weekStart,
            requestedEndEpochDay = weekEnd,
            timeZoneId = zone,
            nowMs = checkedAt + 1
        )
        assertFalse(result.reliable)
        assertFalse(result.exhaustive)
        assertTrue(result.warnings.contains("runtime-broken"))
        assertTrue(result.warnings.contains(V2PayrollCoverageAttestationPolicyV2.SOURCE_WARNING))
    }

    @Test fun coverageMustBeClosedBeforeConfirmation() {
        assertTrue(V2PayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(weekEnd, checkedAt, zone))
        assertFalse(V2PayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(weekEnd, checkedAt - 1, zone))
    }

    private fun resolve(
        attestations: List<V2PayrollCoverageAttestationV2>,
        sessions: List<WorkSessionV2>,
        now: Long = checkedAt + 1
    ) = V2PayrollCoverageAttestationStoreV2.resolve(
        attestations = attestations,
        sessions = sessions,
        sourceReliable = true,
        sourceWarnings = emptyList(),
        employerId = "company-a",
        requestedStartEpochDay = weekStart,
        requestedEndEpochDay = weekEnd,
        timeZoneId = zone,
        nowMs = now
    )

    private fun attestation(
        id: String,
        start: Long,
        end: Long,
        sessions: List<WorkSessionV2>
    ): V2PayrollCoverageAttestationV2 {
        val fingerprint = V2PayrollCoverageAttestationPolicyV2.fingerprint(
            sessions, "company-a", start, end, zone
        )!!
        val check = LocalDate.ofEpochDay(end + 1).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()
        return V2PayrollCoverageAttestationV2(
            id = id,
            employerId = "company-a",
            coveredStartEpochDay = start,
            coveredEndEpochDay = end,
            checkedAtMs = check,
            timeZoneId = zone,
            sessionFingerprint = fingerprint
        )
    }

    private fun fingerprint(sessions: List<WorkSessionV2>) =
        V2PayrollCoverageAttestationPolicyV2.fingerprint(
            sessions, "company-a", weekStart, weekEnd, zone
        )

    private fun session(id: String, dayOffset: Int): WorkSessionV2 {
        val entry = ms(dayOffset, 8)
        val exit = ms(dayOffset, 17)
        return WorkSessionV2(
            id = id,
            employerId = "company-a",
            realArrivalMs = entry,
            countedEntryMs = entry,
            countedExitMs = exit,
            realExitMs = exit,
            status = SessionStatusV2.CLOSED
        )
    }

    private fun ms(dayOffset: Int, hour: Int, minute: Int = 0) =
        LocalDate.ofEpochDay(weekStart + dayOffset)
            .atTime(hour, minute)
            .atZone(ZoneId.of(zone))
            .toInstant()
            .toEpochMilli()
}
