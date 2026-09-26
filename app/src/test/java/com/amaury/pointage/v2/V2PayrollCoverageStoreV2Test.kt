package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.PayrollCoverageAttestationPolicyV2
import com.amaury.pointage.v2.engine.PayrollCoverageAttestationV2
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class V2PayrollCoverageStoreV2Test {
    private val zone = "Europe/Paris"
    private val monday = LocalDate.of(2026, 9, 21).toEpochDay()
    private val now = LocalDate.of(2026, 9, 29).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test fun `un trou d un jour ne prouve jamais la periode complete`() {
        val a = attestation("a", monday, monday + 2)
        val b = attestation("b", monday + 4, monday + 6)
        val result = V2PayrollCoverageStore.resolve(
            attestations = listOf(a, b), sessions = emptyList(), employerId = "company",
            requestedStartEpochDay = monday, requestedEndEpochDay = monday + 6,
            timeZoneId = zone, nowMs = now
        )
        assertTrue(result.reliable)
        assertFalse(result.exhaustive)
        assertTrue(result.warnings.contains(PayrollCoverageAttestationPolicyV2.MISSING_WARNING))
    }

    @Test fun `deux plages contigues peuvent prouver la periode complete`() {
        val a = attestation("a", monday, monday + 2)
        val b = attestation("b", monday + 3, monday + 6)
        val result = V2PayrollCoverageStore.resolve(
            attestations = listOf(a, b), sessions = emptyList(), employerId = "company",
            requestedStartEpochDay = monday, requestedEndEpochDay = monday + 6,
            timeZoneId = zone, nowMs = now
        )
        assertTrue(result.reliable)
        assertTrue(result.exhaustive)
        assertTrue(result.sourceId.startsWith("coverage-v1:"))
    }

    @Test fun `modifier un pointage couvert perime l attestation`() {
        val original = listOf(session("s", monday, 8, 16))
        val fingerprint = PayrollCoverageAttestationPolicyV2.fingerprint(
            original, "company", monday, monday + 6, zone
        )!!
        val attestation = PayrollCoverageAttestationV2(
            "a", "company", monday, monday + 6, now - 1_000, zone, fingerprint
        )
        val changed = listOf(session("s", monday, 8, 17))
        val result = V2PayrollCoverageStore.resolve(
            attestations = listOf(attestation), sessions = changed, employerId = "company",
            requestedStartEpochDay = monday, requestedEndEpochDay = monday + 6,
            timeZoneId = zone, nowMs = now
        )
        assertTrue(result.reliable)
        assertFalse(result.exhaustive)
        assertTrue(result.warnings.contains(PayrollCoverageAttestationPolicyV2.STALE_WARNING))
    }

    @Test fun `une pause modifiee perime aussi l attestation`() {
        val original = listOf(session("s", monday, 8, 16))
        val fingerprint = PayrollCoverageAttestationPolicyV2.fingerprint(
            original, "company", monday, monday + 6, zone
        )!!
        val item = PayrollCoverageAttestationV2(
            "a", "company", monday, monday + 6, now - 1_000, zone, fingerprint
        )
        val changed = listOf(session("s", monday, 8, 16,
            pauses = listOf(PauseV2(
                startMs = instant(monday, 12),
                endMs = instant(monday, 12, 30),
                paid = false,
                source = EventSourceV2.MANUAL
            ))))
        assertFalse(PayrollCoverageAttestationPolicyV2.isCurrent(item, changed, now))
    }

    @Test fun `une semaine encore ouverte ne peut pas etre certifiee`() {
        val end = monday + 6
        val beforeClosure = LocalDate.ofEpochDay(end).atTime(12, 0)
            .atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
        assertFalse(PayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(end, beforeClosure, zone))
    }

    @Test fun `encode decode refuse les identifiants dupliques`() {
        val item = attestation("same", monday, monday + 6)
        assertNull(V2PayrollCoverageStore.encode(listOf(item, item)))
    }

    private fun attestation(id: String, start: Long, end: Long): PayrollCoverageAttestationV2 {
        val fingerprint = PayrollCoverageAttestationPolicyV2.fingerprint(
            emptyList(), "company", start, end, zone
        )!!
        return PayrollCoverageAttestationV2(id, "company", start, end, now - 1_000, zone, fingerprint)
    }

    private fun session(
        id: String, day: Long, startHour: Int, endHour: Int,
        pauses: List<PauseV2> = emptyList()
    ) = WorkSessionV2(
        id = id, employerId = "company",
        realArrivalMs = instant(day, startHour),
        countedEntryMs = instant(day, startHour),
        countedExitMs = instant(day, endHour),
        realExitMs = instant(day, endHour),
        pauses = pauses,
        status = SessionStatusV2.CLOSED
    )

    private fun instant(day: Long, hour: Int, minute: Int = 0): Long =
        LocalDate.ofEpochDay(day).atTime(hour, minute).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
}
