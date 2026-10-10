package com.amaury.pointage

import com.amaury.pointage.v2.engine.GpsEventV2
import com.amaury.pointage.v2.engine.GpsPointTypeV2
import com.amaury.pointage.v2.engine.GpsTransitionV2
import org.junit.Assert.*
import org.junit.Test

class GpsExitDeliveryRecordV2Test {
    private val record = GpsExitDeliveryRecordV2(
        GpsEventV2("exit|α", 57_600_000L, "atelier|à% +", GpsPointTypeV2.POSTE, GpsTransitionV2.EXIT),
        "session-a", 36_000_000L, "uid:account-a", "enabled|valid|{site}")

    @Test fun recordRoundTripPreservesObservedTimeAndAllBindings() {
        assertEquals(record, GpsExitDeliveryRecordV2.decode(record.encode()))
        assertEquals(record.receiptId(), GpsExitDeliveryRecordV2.decode(record.encode())?.receiptId())
        assertEquals(64, record.receiptId().length)
    }

    @Test fun replayIdentityChangesWithEveryMaterialBinding() {
        for (other in listOf(
            record.copy(sessionId = "session-b"),
            record.copy(sessionArrivalMs = 36_000_001L),
            record.copy(accountScope = "uid:account-b"),
            record.copy(accountScope = "guest"),
            record.copy(configurationFingerprint = "enabled|valid|other"),
            record.copy(event = record.event.copy(atMs = record.event.atMs + 1L))
        )) assertNotEquals(record.receiptId(), other.receiptId())
    }

    @Test fun partialObservationContextDoesNotDependOnDelayedExitSelection() {
        val later = record.copy(event = record.event.copy(atMs = record.event.atMs + 420_000L))
        assertEquals(record.observationContext(), later.observationContext())
        assertNotEquals(record.observationContext(), record.copy(accountScope = "guest").observationContext())
        assertNotEquals(record.observationContext(), record.copy(sessionId = "new").observationContext())
    }

    @Test fun oldRecordCannotMatchAnotherOrAbsentSession() {
        assertTrue(record.matchesSession("session-a", 36_000_000L))
        assertFalse(record.matchesSession("session-b", 36_000_000L))
        assertFalse(record.matchesSession("session-a", 36_000_001L))
        assertFalse(record.matchesSession(null, null))
    }

    @Test fun unknownOrCorruptRecordIsNotInvented() {
        for (raw in listOf("", "old-record", "1|x|0|work|POSTE|session|1|guest|config",
            "1|x|2|work|POSTE|session|1|unknown|config", "1|%xx|2|work|POSTE|session|1|guest|config")) {
            assertNull(GpsExitDeliveryRecordV2.decode(raw))
        }
    }

    @Test fun partialExitFromOldContextCannotBeWrappedByTheNextContext() {
        val previous = mapOf(record.event.placeId to GpsExitObservationV2.Observation(record.event.atMs, emptySet()))
        assertEquals(previous, GpsExitObservationV2.inContext(previous,
            record.observationContext(), record.observationContext()))
        for (next in listOf(record.copy(accountScope = "guest"), record.copy(sessionId = "new"),
            record.copy(configurationFingerprint = "changed"))) {
            val accepted = GpsExitObservationV2.inContext(previous, record.observationContext(), next.observationContext())
            assertTrue(accepted.isEmpty())
            assertNull(GpsExitObservationV2.resolveExitAtMs(record.event.placeId, previous.keys,
                accepted, record.event.atMs + 420_000L) { _, _ -> false })
        }
        assertTrue(GpsExitObservationV2.inContext(previous, null, record.observationContext()).isEmpty())
    }
}
