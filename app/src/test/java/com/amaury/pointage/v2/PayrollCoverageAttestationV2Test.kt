package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.TravelClassificationV2
import com.amaury.pointage.v2.model.TravelV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayrollCoverageAttestationV2Test {
    @Test
    fun `empreinte est stable quel que soit l ordre des sessions`() {
        val first = session("a", 1_000L)
        val second = session("b", 5_000L)
        assertEquals(
            PayrollCoverageAttestationStoreV2.fingerprint(listOf(first, second)),
            PayrollCoverageAttestationStoreV2.fingerprint(listOf(second, first))
        )
    }

    @Test
    fun `modifier une pause invalide l empreinte`() {
        val base = session("a", 1_000L)
        val changed = base.copy(
            pauses = listOf(PauseV2(2_000L, 3_000L, paid = true, source = EventSourceV2.MANUAL))
        )
        assertNotEquals(
            PayrollCoverageAttestationStoreV2.fingerprint(listOf(base)),
            PayrollCoverageAttestationStoreV2.fingerprint(listOf(changed))
        )
    }

    @Test
    fun `modifier un deplacement invalide l empreinte`() {
        val base = session("a", 1_000L)
        val changed = base.copy(
            travels = listOf(
                TravelV2(
                    startMs = 2_000L,
                    endMs = 3_000L,
                    employerBeforeId = "company",
                    employerAfterId = "company",
                    distanceMeters = 1200.0,
                    classification = TravelClassificationV2.PAID
                )
            )
        )
        assertNotEquals(
            PayrollCoverageAttestationStoreV2.fingerprint(listOf(base)),
            PayrollCoverageAttestationStoreV2.fingerprint(listOf(changed))
        )
    }

    @Test
    fun `attestation encodee refuse doublon employeur et fingerprint invalide`() {
        val raw = """[
          {"employerId":"company","sourceId":"source","coveredStartEpochDay":20000,
           "coveredEndEpochDay":20001,"checkedAtMs":2000000000000,
           "timeZoneId":"Europe/Paris","historyFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
        ]"""
        val decoded = PayrollCoverageAttestationStoreV2.decode(raw)
        assertEquals(1, decoded.size)
        assertTrue(decoded.first().sourceId == "source")
    }

    private fun session(id: String, start: Long) = WorkSessionV2(
        id = id,
        employerId = "company",
        realArrivalMs = start,
        countedEntryMs = start,
        countedExitMs = start + 3_600_000L,
        realExitMs = start + 3_600_000L,
        pauses = emptyList(),
        travels = emptyList(),
        status = SessionStatusV2.CLOSED,
        placeId = "worksite",
        placeLabel = "Atelier",
        legacyFixedUnpaidPauseMs = 0L
    )
}
