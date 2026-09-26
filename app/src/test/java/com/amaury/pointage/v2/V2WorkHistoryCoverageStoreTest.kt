package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class V2WorkHistoryCoverageStoreTest {
    private val zone = "Europe/Paris"

    @Test
    fun `deux attestations contigues couvrent toute la periode`() {
        val stored = reliable(
            attestation("a", 10, 16, completeAt(16)),
            attestation("b", 17, 23, completeAt(23))
        )
        val result = V2WorkHistoryCoverageStore.coverageFrom(
            stored, "company-a", 10, 23, zone, completeAt(23) + 1_000
        )

        assertTrue(result.reliable)
        assertTrue(result.fullyCovered)
        assertEquals(2, result.attestations.size)
        assertEquals("coverage:a,b", result.sourceId)
        assertEquals(completeAt(23), result.checkedAtMs)
    }

    @Test
    fun `un trou ne devient jamais un zero implicite`() {
        val stored = reliable(
            attestation("a", 10, 15, completeAt(15)),
            attestation("b", 17, 23, completeAt(23))
        )
        val result = V2WorkHistoryCoverageStore.coverageFrom(
            stored, "company-a", 10, 23, zone, completeAt(23) + 1_000
        )

        assertTrue(result.reliable)
        assertFalse(result.fullyCovered)
        assertTrue(result.warnings.contains(V2WorkHistoryCoverageStore.COVERAGE_WARNING))
    }

    @Test
    fun `autre employeur ou autre fuseau ne couvre pas la periode`() {
        val stored = reliable(attestation("a", 10, 23, completeAt(23)))

        assertFalse(
            V2WorkHistoryCoverageStore.coverageFrom(
                stored, "company-b", 10, 23, zone, completeAt(23) + 1_000
            ).fullyCovered
        )
        assertFalse(
            V2WorkHistoryCoverageStore.coverageFrom(
                stored, "company-a", 10, 23, "UTC", completeAt(23) + 1_000
            ).fullyCovered
        )
    }

    @Test
    fun `attestation future bloque la source`() {
        val future = attestation("a", 10, 23, completeAt(23) + 60_000)
        val result = V2WorkHistoryCoverageStore.coverageFrom(
            reliable(future), "company-a", 10, 23, zone, completeAt(23)
        )

        assertFalse(result.reliable)
        assertFalse(result.fullyCovered)
        assertTrue(result.warnings.contains(V2WorkHistoryCoverageStore.FUTURE_WARNING))
    }

    @Test
    fun `correction de pointage invalide uniquement les attestations touchees`() {
        val first = attestation("a", 10, 16, completeAt(16))
        val second = attestation("b", 17, 23, completeAt(23))
        val affectedDay = LocalDate.ofEpochDay(12)
        val start = affectedDay.atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()
        val end = affectedDay.plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()

        val kept = V2WorkHistoryCoverageStore.invalidateAttestations(
            listOf(first, second), start, end
        )

        assertEquals(listOf(second), kept)
    }

    @Test
    fun `encodage puis decodage conserve la provenance`() {
        val values = listOf(attestation("a", 10, 16, completeAt(16), sourceId = "user-confirmed"))
        val decoded = V2WorkHistoryCoverageStore.decode(
            V2WorkHistoryCoverageStore.encode(values)
        )

        assertTrue(decoded.reliable)
        assertEquals(values, decoded.attestations)
    }

    private fun reliable(vararg values: V2WorkHistoryCoverageStore.Attestation) =
        V2WorkHistoryCoverageStore.ReadResult(values.toList(), true, false, emptyList())

    private fun attestation(
        id: String,
        start: Long,
        end: Long,
        confirmedAt: Long,
        sourceId: String = "user"
    ) = V2WorkHistoryCoverageStore.Attestation(
        id = id,
        sourceId = sourceId,
        employerId = "company-a",
        startEpochDay = start,
        endEpochDay = end,
        confirmedAtMs = confirmedAt,
        timeZoneId = zone
    )

    private fun completeAt(endEpochDay: Long): Long =
        LocalDate.ofEpochDay(endEpochDay).plusDays(1)
            .atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()
}
