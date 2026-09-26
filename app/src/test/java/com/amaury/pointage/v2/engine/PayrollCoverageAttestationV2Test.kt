package com.amaury.pointage.v2.engine

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
    private val startDay = LocalDate.of(2026, 9, 21).toEpochDay()
    private val endDay = LocalDate.of(2026, 9, 27).toEpochDay()
    private val checkedAt = LocalDate.of(2026, 9, 28).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test
    fun `attestation exacte reste valide`() {
        val sessions = listOf(session("a"))
        val fingerprint = PayrollCoverageAttestationPolicyV2.fingerprint(sessions, startDay, endDay, zone)
        assertNotNull(fingerprint)
        val proof = proof(fingerprint!!)
        assertTrue(PayrollCoverageAttestationPolicyV2.isValid(
            proof, sessions, "company-a", startDay, endDay, zone, checkedAt + 1
        ))
    }

    @Test
    fun `correction horaire invalide attestation`() {
        val original = listOf(session("a"))
        val changed = listOf(original.first().copy(countedExitMs = original.first().countedExitMs!! + 60_000))
        val proof = proof(PayrollCoverageAttestationPolicyV2.fingerprint(original, startDay, endDay, zone)!!)
        assertFalse(PayrollCoverageAttestationPolicyV2.isValid(
            proof, changed, "company-a", startDay, endDay, zone, checkedAt + 1
        ))
    }

    @Test
    fun `correction pause invalide attestation`() {
        val original = listOf(session("a"))
        val changedPause = original.first().pauses.first().copy(paid = true)
        val changed = listOf(original.first().copy(pauses = listOf(changedPause)))
        val proof = proof(PayrollCoverageAttestationPolicyV2.fingerprint(original, startDay, endDay, zone)!!)
        assertFalse(PayrollCoverageAttestationPolicyV2.isValid(
            proof, changed, "company-a", startDay, endDay, zone, checkedAt + 1
        ))
    }

    @Test
    fun `nouveau pointage dans periode invalide attestation`() {
        val original = listOf(session("a"))
        val changed = original + session("b", dayOffset = 1)
        val first = PayrollCoverageAttestationPolicyV2.fingerprint(original, startDay, endDay, zone)
        val second = PayrollCoverageAttestationPolicyV2.fingerprint(changed, startDay, endDay, zone)
        assertNotEquals(first, second)
        val proof = proof(first!!)
        assertFalse(PayrollCoverageAttestationPolicyV2.isValid(
            proof, changed, "company-a", startDay, endDay, zone, checkedAt + 1
        ))
    }

    @Test
    fun `modification hors periode ne rend pas attestation stale`() {
        val original = listOf(session("a"))
        val outside = session("outside", dayOffset = 20)
        val first = PayrollCoverageAttestationPolicyV2.fingerprint(original, startDay, endDay, zone)
        val second = PayrollCoverageAttestationPolicyV2.fingerprint(original + outside, startDay, endDay, zone)
        assertTrue(first == second)
    }

    @Test
    fun `attestation future ou autre fuseau est refusee`() {
        val sessions = listOf(session("a"))
        val fingerprint = PayrollCoverageAttestationPolicyV2.fingerprint(sessions, startDay, endDay, zone)!!
        assertFalse(PayrollCoverageAttestationPolicyV2.isValid(
            proof(fingerprint).copy(checkedAtMs = checkedAt + 10_000),
            sessions, "company-a", startDay, endDay, zone, checkedAt
        ))
        assertFalse(PayrollCoverageAttestationPolicyV2.isValid(
            proof(fingerprint), sessions, "company-a", startDay, endDay, "UTC", checkedAt + 1
        ))
    }

    @Test
    fun `periode doit etre close avant confirmation`() {
        assertTrue(PayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(endDay, checkedAt, zone))
        assertFalse(PayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(endDay, checkedAt - 1, zone))
    }

    private fun proof(fingerprint: String) = PayrollCoverageAttestationV2(
        employerId = "company-a",
        coveredStartEpochDay = startDay,
        coveredEndEpochDay = endDay,
        checkedAtMs = checkedAt,
        timeZoneId = zone,
        sourceId = "coverage:test",
        sessionFingerprint = fingerprint
    )

    private fun session(id: String, dayOffset: Long = 0): WorkSessionV2 {
        val day = LocalDate.of(2026, 9, 22).plusDays(dayOffset)
        val start = day.atTime(8, 0).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
        val end = day.atTime(17, 0).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
        return WorkSessionV2(
            id = id,
            employerId = "company-a",
            realArrivalMs = start,
            countedEntryMs = start,
            countedExitMs = end,
            realExitMs = end,
            pauses = listOf(PauseV2(
                start + 4 * 60 * 60_000L,
                start + 5 * 60 * 60_000L,
                paid = false,
                source = EventSourceV2.MANUAL,
                status = DecisionStatusV2.CONFIRMED
            )),
            status = SessionStatusV2.CLOSED
        )
    }
}
