package com.amaury.pointage

import com.amaury.pointage.v2.engine.GpsEventV2
import com.amaury.pointage.v2.engine.GpsPointTypeV2
import com.amaury.pointage.v2.engine.GpsTransitionV2
import org.junit.Assert.*
import org.junit.Test

class GpsReturnObservationV2Test {
    private val delivery = GpsExitDeliveryRecordV2(
        GpsEventV2("exit", 60_000L, "work-a", GpsPointTypeV2.POSTE, GpsTransitionV2.EXIT),
        "session", 1_000L, "uid:account-a", "config")
    private val context = delivery.observationContext()

    private fun observed(zone: String = "work-a", at: Long = 80_000L, scope: String? = context) =
        GpsReturnObservationV2.observe(emptyMap(), setOf("work-a", "work-b"), setOf(zone), at, scope)

    @Test fun exactZoneReturnSurvivesSerializationWithoutTimerExecution() {
        val stored = observed().values.map { it.encode() }.toSet()
        assertTrue(GpsReturnObservationV2.provesReturn(GpsReturnObservationV2.decode(stored), delivery,
            context, 90_000L))
    }

    @Test fun noReturnIsInferredFromAnotherZoneMissingTimeOrFutureTime() {
        assertFalse(GpsReturnObservationV2.provesReturn(observed("work-b"), delivery, context, 90_000L))
        assertFalse(GpsReturnObservationV2.provesReturn(observed(at = 59_999L), delivery, context, 90_000L))
        assertFalse(GpsReturnObservationV2.provesReturn(observed(at = 90_001L), delivery, context, 90_000L))
        assertFalse(GpsReturnObservationV2.provesReturn(emptyMap(), delivery, context, 90_000L))
    }

    @Test fun accountSessionArrivalAndConfigurationMustAllMatch() {
        for (other in listOf(delivery.copy(accountScope = "guest"), delivery.copy(sessionId = "new"),
            delivery.copy(sessionArrivalMs = 2_000L), delivery.copy(configurationFingerprint = "new"))) {
            assertFalse(GpsReturnObservationV2.provesReturn(observed(), delivery, other.observationContext(), 90_000L))
            assertFalse(GpsReturnObservationV2.provesReturn(observed(scope = other.observationContext()),
                delivery, context, 90_000L))
        }
    }

    @Test fun returnBeforeNewDepartureDoesNotCancelThatLaterDeparture() {
        val later = delivery.copy(event = delivery.event.copy(id = "second-exit", atMs = 85_000L))
        assertFalse(GpsReturnObservationV2.provesReturn(observed(), later, context, 90_000L))
    }

    @Test fun equalTimestampsCannotProveReturnAfterDeparture() {
        assertFalse(GpsReturnObservationV2.provesReturn(observed(at = delivery.event.atMs),
            delivery, context, 90_000L))
    }

    @Test fun returnQualificationRoundTripsAndRejectsCorruptOrMissingDeadline() {
        val qualification = GpsReturnObservationV2.Qualification("zone|with space", 90_000L)
        assertEquals(qualification, GpsReturnObservationV2.Qualification.decode(qualification.encode()))
        for (raw in listOf(null, "", "0|zone", "90000|", "tomorrow|zone", "90000|zone|extra")) {
            assertNull(GpsReturnObservationV2.Qualification.decode(raw))
        }
    }

    @Test fun unknownContextOrCorruptStorageCannotProveReturn() {
        assertTrue(observed(scope = null).isEmpty())
        assertTrue(GpsReturnObservationV2.decode(setOf("work-a|0|context")).isEmpty())
        assertFalse(GpsReturnObservationV2.provesReturn(observed(), delivery, null, 90_000L))
    }

    @Test fun worksiteReturnNeverCancelsAnAmbiguousParkingOrPauseTransition() {
        for (type in listOf(GpsPointTypeV2.PARKING, GpsPointTypeV2.OTHER)) {
            assertFalse(GpsReturnObservationV2.provesReturn(observed(),
                delivery.copy(event = delivery.event.copy(pointType = type)), context, 90_000L))
        }
    }
}
