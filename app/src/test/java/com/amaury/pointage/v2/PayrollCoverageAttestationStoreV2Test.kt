package com.amaury.pointage.v2

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayrollCoverageAttestationStoreV2Test {
    private val zoneId = "Europe/Paris"
    private val zone = ZoneId.of(zoneId)
    private val monday = LocalDate.of(2026, 9, 21).toEpochDay()
    private val sunday = monday + 6

    @Test
    fun `historique lisible sans attestation ne prouve jamais une semaine vide`() {
        val result = resolve(emptyList())

        assertTrue(result.reliable)
        assertFalse(result.exhaustive)
        assertTrue(result.warnings.contains(PayrollCoverageAttestationStoreV2.MISSING_WARNING))
    }

    @Test
    fun `deux attestations adjacentes couvrent une semaine complete`() {
        val first = attestation("a", monday, monday + 2, confirmedAt(monday + 3))
        val second = attestation("b", monday + 3, sunday, confirmedAt(sunday + 1))

        val result = resolve(listOf(second, first))

        assertTrue(result.reliable)
        assertTrue(result.exhaustive)
        assertEquals(monday, result.coveredStartEpochDay)
        assertEquals(sunday, result.coveredEndEpochDay)
        assertTrue(result.sourceId.contains("a"))
        assertTrue(result.sourceId.contains("b"))
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `un trou d un jour reste non exhaustif`() {
        val first = attestation("a", monday, monday + 1, confirmedAt(monday + 2))
        val second = attestation("b", monday + 3, sunday, confirmedAt(sunday + 1))

        val result = resolve(listOf(first, second))

        assertTrue(result.reliable)
        assertFalse(result.exhaustive)
        assertTrue(result.warnings.contains(PayrollCoverageAttestationStoreV2.MISSING_WARNING))
    }

    @Test
    fun `une autre entreprise ne couvre jamais la periode demandee`() {
        val foreign = attestation(
            "foreign",
            monday,
            sunday,
            confirmedAt(sunday + 1),
            employerId = "company-b"
        )

        val result = resolve(listOf(foreign))

        assertTrue(result.reliable)
        assertFalse(result.exhaustive)
    }

    @Test
    fun `une attestation future rend la source non fiable`() {
        val item = attestation("future", monday, sunday, confirmedAt(sunday + 1))
        val now = item.confirmedAtMs - 1

        val result = PayrollCoverageAttestationStoreV2.resolve(
            attestations = listOf(item),
            employerId = "company-a",
            requestedStartEpochDay = monday,
            requestedEndEpochDay = sunday,
            timeZoneId = zoneId,
            nowMs = now
        )

        assertFalse(result.reliable)
        assertFalse(result.exhaustive)
        assertTrue(result.warnings.contains(PayrollCoverageAttestationStoreV2.CORRUPT_WARNING))
    }

    @Test
    fun `une semaine ne peut pas etre certifiee avant sa fin civile`() {
        val tooEarly = PayrollCoverageAttestationV2(
            id = "early",
            employerId = "company-a",
            startEpochDay = monday,
            endEpochDay = sunday,
            confirmedAtMs = LocalDate.ofEpochDay(sunday)
                .atTime(12, 0)
                .atZone(zone)
                .toInstant()
                .toEpochMilli(),
            timeZoneId = zoneId
        )

        val result = PayrollCoverageAttestationStoreV2.resolve(
            attestations = listOf(tooEarly),
            employerId = "company-a",
            requestedStartEpochDay = monday,
            requestedEndEpochDay = sunday,
            timeZoneId = zoneId,
            nowMs = confirmedAt(sunday + 1)
        )

        assertFalse(result.reliable)
        assertFalse(result.exhaustive)
    }

    @Test
    fun `le codec refuse les identifiants dupliques`() {
        val first = attestation("dup", monday, monday + 1, confirmedAt(monday + 2))
        val second = attestation("dup", monday + 2, sunday, confirmedAt(sunday + 1))

        assertEquals(null, PayrollCoverageAttestationStoreV2.encode(listOf(first, second)))
    }

    @Test
    fun `le codec preserve exactement une attestation valide`() {
        val original = listOf(attestation("one", monday, sunday, confirmedAt(sunday + 1)))
        val encoded = requireNotNull(PayrollCoverageAttestationStoreV2.encode(original))
        val decoded = PayrollCoverageAttestationStoreV2.decode(encoded)

        assertTrue(decoded.reliable)
        assertEquals(original, decoded.attestations)
    }

    private fun resolve(items: List<PayrollCoverageAttestationV2>) =
        PayrollCoverageAttestationStoreV2.resolve(
            attestations = items,
            employerId = "company-a",
            requestedStartEpochDay = monday,
            requestedEndEpochDay = sunday,
            timeZoneId = zoneId,
            nowMs = confirmedAt(sunday + 2)
        )

    private fun attestation(
        id: String,
        start: Long,
        end: Long,
        confirmedAtMs: Long,
        employerId: String = "company-a"
    ) = PayrollCoverageAttestationV2(
        id = id,
        employerId = employerId,
        startEpochDay = start,
        endEpochDay = end,
        confirmedAtMs = confirmedAtMs,
        timeZoneId = zoneId
    )

    private fun confirmedAt(day: Long): Long =
        LocalDate.ofEpochDay(day)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
}
