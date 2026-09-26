package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.V2PayrollCoverageStore
import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PayrollCoverageAttestationV2Test {
    private val zone = "Europe/Paris"
    private val weekStart = LocalDate.of(2026, 9, 21).toEpochDay()
    private val weekEnd = LocalDate.of(2026, 9, 27).toEpochDay()
    private val checkedAt = startOfDay(LocalDate.of(2026, 9, 28))

    @Test
    fun `attestation exacte reste valide et correction horaire l invalide`() {
        val original = listOf(session("a", LocalDate.of(2026, 9, 22)))
        val attestation = attestation("one", weekStart, weekEnd, original)
        assertTrue(PayrollCoverageAttestationPolicyV2.isCurrent(
            attestation, original, checkedAt + 1
        ))

        val edited = listOf(original.first().copy(
            countedExitMs = original.first().countedExitMs!! + 60_000L
        ))
        assertFalse(PayrollCoverageAttestationPolicyV2.isCurrent(
            attestation, edited, checkedAt + 1
        ))
    }

    @Test
    fun `correction pause ou nouveau pointage sans employeur invalide la plage`() {
        val original = listOf(session("a", LocalDate.of(2026, 9, 22)))
        val attestation = attestation("one", weekStart, weekEnd, original)

        val pause = original.first().pauses.first().copy(paid = true)
        val pauseEdited = listOf(original.first().copy(pauses = listOf(pause)))
        assertFalse(PayrollCoverageAttestationPolicyV2.isCurrent(
            attestation, pauseEdited, checkedAt + 1
        ))

        val unassigned = session("unknown", LocalDate.of(2026, 9, 23), employerId = null)
        assertFalse(PayrollCoverageAttestationPolicyV2.isCurrent(
            attestation, original + unassigned, checkedAt + 1
        ))
    }

    @Test
    fun `autre employeur et modification hors plage ne rendent pas la preuve stale`() {
        val original = listOf(session("a", LocalDate.of(2026, 9, 22)))
        val first = PayrollCoverageAttestationPolicyV2.fingerprint(
            original, "company-a", weekStart, weekEnd, zone
        )
        val otherEmployer = session(
            "b",
            LocalDate.of(2026, 9, 23),
            employerId = "company-b"
        ).copy(countedExitMs = startOfDay(LocalDate.of(2026, 9, 24)) - 60_000L)
        val outside = session("outside", LocalDate.of(2026, 10, 20))
        val second = PayrollCoverageAttestationPolicyV2.fingerprint(
            original + otherEmployer + outside,
            "company-a",
            weekStart,
            weekEnd,
            zone
        )
        assertTrue(first == second)
    }

    @Test
    fun `deux attestations adjacentes couvrent la semaine complete`() {
        val sessions = listOf(
            session("a", LocalDate.of(2026, 9, 22)),
            session("b", LocalDate.of(2026, 9, 25))
        )
        val first = attestation("first", weekStart, weekStart + 2, sessions)
        val second = attestation("second", weekStart + 3, weekEnd, sessions)

        val result = V2PayrollCoverageStore.resolve(
            attestations = listOf(second, first),
            sessions = sessions,
            employerId = "company-a",
            requestedStartEpochDay = weekStart,
            requestedEndEpochDay = weekEnd,
            timeZoneId = zone,
            nowMs = checkedAt + 1
        )

        assertTrue(result.reliable)
        assertTrue(result.exhaustive)
        assertTrue(result.sourceId.contains("first"))
        assertTrue(result.sourceId.contains("second"))
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `un jour manquant reste non exhaustif`() {
        val sessions = listOf(session("a", LocalDate.of(2026, 9, 22)))
        val first = attestation("first", weekStart, weekStart + 1, sessions)
        val second = attestation("second", weekStart + 3, weekEnd, sessions)

        val result = V2PayrollCoverageStore.resolve(
            attestations = listOf(first, second),
            sessions = sessions,
            employerId = "company-a",
            requestedStartEpochDay = weekStart,
            requestedEndEpochDay = weekEnd,
            timeZoneId = zone,
            nowMs = checkedAt + 1
        )

        assertTrue(result.reliable)
        assertFalse(result.exhaustive)
        assertTrue(result.warnings.contains(PayrollCoverageAttestationPolicyV2.MISSING_WARNING))
    }

    @Test
    fun `une attestation stale ne peut pas completer une chaine de couverture`() {
        val original = listOf(session("a", LocalDate.of(2026, 9, 22)))
        val first = attestation("first", weekStart, weekStart + 2, original)
        val second = attestation("second", weekStart + 3, weekEnd, original)
        val changed = original + session("new", LocalDate.of(2026, 9, 25))

        val result = V2PayrollCoverageStore.resolve(
            attestations = listOf(first, second),
            sessions = changed,
            employerId = "company-a",
            requestedStartEpochDay = weekStart,
            requestedEndEpochDay = weekEnd,
            timeZoneId = zone,
            nowMs = checkedAt + 1
        )

        assertTrue(result.reliable)
        assertFalse(result.exhaustive)
        assertTrue(result.warnings.contains(PayrollCoverageAttestationPolicyV2.STALE_WARNING))
    }

    @Test
    fun `attestation future rend la resolution non fiable`() {
        val sessions = listOf(session("a", LocalDate.of(2026, 9, 22)))
        val proof = attestation("future", weekStart, weekEnd, sessions)
            .copy(checkedAtMs = checkedAt + 10_000L)

        val result = V2PayrollCoverageStore.resolve(
            attestations = listOf(proof),
            sessions = sessions,
            employerId = "company-a",
            requestedStartEpochDay = weekStart,
            requestedEndEpochDay = weekEnd,
            timeZoneId = zone,
            nowMs = checkedAt
        )

        assertFalse(result.reliable)
        assertFalse(result.exhaustive)
        assertTrue(result.warnings.contains(PayrollCoverageAttestationPolicyV2.CORRUPT_WARNING))
    }

    @Test
    fun `codec refuse doublons et payload incomplet`() {
        val sessions = listOf(session("a", LocalDate.of(2026, 9, 22)))
        val proof = attestation("same", weekStart, weekEnd, sessions)
        assertNotNull(V2PayrollCoverageStore.encode(listOf(proof)))
        assertTrue(V2PayrollCoverageStore.encode(listOf(proof, proof)) == null)
        assertFalse(V2PayrollCoverageStore.decode("{}").reliable)
    }

    @Test
    fun `periode doit etre close avant confirmation`() {
        assertTrue(PayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(
            weekEnd, checkedAt, zone
        ))
        assertFalse(PayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(
            weekEnd, checkedAt - 1L, zone
        ))
    }

    @Test
    fun `empreinte change si la session de l employeur change`() {
        val original = listOf(session("a", LocalDate.of(2026, 9, 22)))
        val changed = listOf(original.first().copy(realExitMs = original.first().realExitMs!! + 1_000L))
        val first = PayrollCoverageAttestationPolicyV2.fingerprint(
            original, "company-a", weekStart, weekEnd, zone
        )
        val second = PayrollCoverageAttestationPolicyV2.fingerprint(
            changed, "company-a", weekStart, weekEnd, zone
        )
        assertNotEquals(first, second)
    }

    private fun attestation(
        id: String,
        start: Long,
        end: Long,
        sessions: List<WorkSessionV2>
    ): PayrollCoverageAttestationV2 {
        val check = startOfDay(LocalDate.ofEpochDay(end).plusDays(1))
        val fingerprint = PayrollCoverageAttestationPolicyV2.fingerprint(
            sessions, "company-a", start, end, zone
        )!!
        return PayrollCoverageAttestationV2(
            id = id,
            employerId = "company-a",
            coveredStartEpochDay = start,
            coveredEndEpochDay = end,
            checkedAtMs = check,
            timeZoneId = zone,
            sessionFingerprint = fingerprint
        )
    }

    private fun session(
        id: String,
        day: LocalDate,
        employerId: String? = "company-a"
    ): WorkSessionV2 {
        val start = day.atTime(8, 0).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
        val end = day.atTime(17, 0).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
        return WorkSessionV2(
            id = id,
            employerId = employerId,
            realArrivalMs = start,
            countedEntryMs = start,
            countedExitMs = end,
            realExitMs = end,
            pauses = listOf(
                PauseV2(
                    startMs = start + 4 * 60 * 60_000L,
                    endMs = start + 5 * 60 * 60_000L,
                    paid = false,
                    source = EventSourceV2.MANUAL,
                    status = DecisionStatusV2.CONFIRMED
                )
            ),
            status = SessionStatusV2.CLOSED
        )
    }

    private fun startOfDay(day: LocalDate): Long =
        day.atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()
}
