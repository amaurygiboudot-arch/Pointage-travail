package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.*
import org.junit.Assert.*
import org.junit.Test

class V2PayrollCoverageAttestationV2Test {
    @Test fun `fingerprint ignore l ordre des sessions mais pas leurs faits`() {
        val a = session("a", 1_000L, 2_000L)
        val b = session("b", 3_000L, 4_000L)
        assertEquals(
            V2PayrollCoverageAttestationStoreV2.fingerprint(listOf(a, b)),
            V2PayrollCoverageAttestationStoreV2.fingerprint(listOf(b, a))
        )
        assertNotEquals(
            V2PayrollCoverageAttestationStoreV2.fingerprint(listOf(a)),
            V2PayrollCoverageAttestationStoreV2.fingerprint(
                listOf(a.copy(employerId = "other"))
            )
        )
    }

    @Test fun `correction pause invalide automatiquement l empreinte`() {
        val original = session("a", 1_000L, 5_000L).copy(
            pauses = listOf(PauseV2(2_000L, 3_000L, false, EventSourceV2.MANUAL))
        )
        val corrected = original.copy(
            pauses = listOf(PauseV2(2_000L, 3_000L, true, EventSourceV2.MANUAL))
        )
        assertNotEquals(
            V2PayrollCoverageAttestationStoreV2.fingerprint(listOf(original)),
            V2PayrollCoverageAttestationStoreV2.fingerprint(listOf(corrected))
        )
    }

    @Test fun `correction deplacement invalide automatiquement l empreinte`() {
        val original = session("a", 1_000L, 5_000L).copy(
            travels = listOf(
                TravelV2(2_000L, 3_000L, "company", "company", 1_000.0, TravelClassificationV2.PAID)
            )
        )
        val corrected = original.copy(
            travels = original.travels.map { it.copy(distanceMeters = 1_001.0) }
        )
        assertNotEquals(
            V2PayrollCoverageAttestationStoreV2.fingerprint(listOf(original)),
            V2PayrollCoverageAttestationStoreV2.fingerprint(listOf(corrected))
        )
    }

    @Test fun `attestation superset la plus recente couvre la periode demandee`() {
        val fingerprint = "abc"
        val older = attestation("old", 1, 31, 100, fingerprint)
        val latest = attestation("latest", 1, 31, 200, fingerprint)
        val selected = V2PayrollCoverageAttestationStoreV2.selectMatching(
            listOf(older, latest),
            fingerprint,
            "company",
            8,
            14,
            "Europe/Paris",
            300
        )
        assertEquals("latest", selected?.sourceId)
    }

    @Test fun `empreinte fuseau periode ou futur incompatibles ne certifient rien`() {
        val a = attestation("proof", 1, 31, 200, "abc")
        fun selected(
            fingerprint: String = "abc",
            employer: String = "company",
            start: Long = 8,
            end: Long = 14,
            zone: String = "Europe/Paris",
            now: Long = 300
        ) = V2PayrollCoverageAttestationStoreV2.selectMatching(
            listOf(a), fingerprint, employer, start, end, zone, now
        )

        assertNull(selected(fingerprint = "changed"))
        assertNull(selected(employer = "other"))
        assertNull(selected(start = 0))
        assertNull(selected(end = 40))
        assertNull(selected(zone = "UTC"))
        assertNull(selected(now = 199))
        assertNotNull(selected())
    }

    @Test fun `historique vide peut etre certifie uniquement par attestation explicite`() {
        val fingerprint = V2PayrollCoverageAttestationStoreV2.fingerprint(emptyList())
        assertNull(
            V2PayrollCoverageAttestationStoreV2.selectMatching(
                emptyList(), fingerprint, "company", 1, 7, "Europe/Paris", 1_000
            )
        )
        assertNotNull(
            V2PayrollCoverageAttestationStoreV2.selectMatching(
                listOf(attestation("explicit-zero", 1, 7, 900, fingerprint)),
                fingerprint, "company", 1, 7, "Europe/Paris", 1_000
            )
        )
    }

    private fun attestation(
        sourceId: String,
        start: Long,
        end: Long,
        checkedAt: Long,
        fingerprint: String
    ) = V2PayrollCoverageAttestationStoreV2.Attestation(
        employerId = "company",
        coveredStartEpochDay = start,
        coveredEndEpochDay = end,
        checkedAtMs = checkedAt,
        timeZoneId = "Europe/Paris",
        sourceId = sourceId,
        historyFingerprint = fingerprint
    )

    private fun session(id: String, start: Long, end: Long) = WorkSessionV2(
        id = id,
        employerId = "company",
        realArrivalMs = start,
        countedEntryMs = start,
        countedExitMs = end,
        realExitMs = end,
        status = SessionStatusV2.CLOSED
    )
}
