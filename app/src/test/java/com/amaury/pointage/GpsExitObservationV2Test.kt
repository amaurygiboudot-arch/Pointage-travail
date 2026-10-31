package com.amaury.pointage

import com.amaury.pointage.v2.engine.GpsTransitionV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsExitObservationV2Test {
    private val sixteen = 57_600_000L
    private val sevenMinutesLater = sixteen + 420_000L
    private val sameWorksite: (String, String) -> Boolean = { a, b ->
        a != b && a.startsWith("work-") && b.startsWith("work-")
    }

    private class Presence(
        var active: Set<String>,
        val equivalent: (String, String) -> Boolean
    ) {
        var pending = emptySet<String>()
        var observations = emptyMap<String, GpsExitObservationV2.Observation>()

        fun event(ids: List<String>, transition: GpsTransitionV2, at: Long, selected: String): Long? {
            val observed = GpsExitObservationV2.advance(observations, active, ids, transition, at, equivalent)
            val plan = GpsActiveZoneTransitionV2.plan(active, ids, transition, false, pending)
            val action = plan.action
            val result = if (action is GpsActiveZoneTransitionV2.Action.ResolveExit) {
                GpsExitObservationV2.resolveExitAtMs(selected, action.zoneIds.toSet(), observed, at, equivalent)
            } else null
            active = plan.activeZoneIds
            pending = plan.pendingExitZoneIds
            // Exercise the persisted/reloaded representation between every platform callback.
            observations = GpsExitObservationV2.decode(GpsExitObservationV2.encode(
                observed.filterKeys { it in pending }
            ))
            return result
        }
    }

    @Test fun workExitAtSixteenIsNotMovedToLaterParkingDepartureAfterReload() {
        val presence = Presence(setOf("work-a", "parking"), sameWorksite)
        assertNull(presence.event(listOf("work-a"), GpsTransitionV2.EXIT, sixteen, "work-a"))
        assertEquals(sixteen, presence.observations.getValue("work-a").atMs)
        assertEquals(sixteen, presence.event(listOf("parking"), GpsTransitionV2.EXIT,
            sevenMinutesLater, "work-a"))
        assertTrue(presence.observations.isEmpty())
    }

    @Test fun continuouslyActiveEquivalentWorkZoneRetainsItsLastObservedDeparture() {
        val presence = Presence(setOf("work-a", "work-b", "parking"), sameWorksite)
        presence.event(listOf("work-a"), GpsTransitionV2.EXIT, sixteen, "work-a")
        presence.event(listOf("work-b"), GpsTransitionV2.EXIT, sevenMinutesLater, "work-a")
        assertEquals(sevenMinutesLater, presence.event(listOf("parking"), GpsTransitionV2.EXIT,
            sevenMinutesLater + 60_000L, "work-a"))
    }

    @Test fun workZoneOfAnotherEmployerOrPlaceCannotMoveTheSelectedWorkExit() {
        val presence = Presence(setOf("work-a", "other-employer-work"), sameWorksite)
        presence.event(listOf("work-a"), GpsTransitionV2.EXIT, sixteen, "work-a")
        assertEquals(sixteen, presence.event(listOf("other-employer-work"), GpsTransitionV2.EXIT,
            sevenMinutesLater, "work-a"))
    }

    @Test fun simultaneousExitsKeepTheSharedObservationTime() {
        val presence = Presence(setOf("work-a", "work-b"), sameWorksite)
        assertEquals(sixteen, presence.event(listOf("work-b", "work-a"), GpsTransitionV2.EXIT,
            sixteen, "work-a"))
    }

    @Test fun chainOfProvenOverlappingWorkZonesKeepsTheLastExit() {
        val linked: (String, String) -> Boolean = { a, b ->
            setOf(a, b) == setOf("a", "b") || setOf(a, b) == setOf("b", "c")
        }
        val presence = Presence(setOf("a", "b", "parking"), linked)
        presence.event(listOf("a"), GpsTransitionV2.EXIT, sixteen, "a")
        presence.event(listOf("c"), GpsTransitionV2.ENTER, sixteen + 60_000L, "a")
        presence.event(listOf("b"), GpsTransitionV2.EXIT, sixteen + 120_000L, "a")
        presence.event(listOf("c"), GpsTransitionV2.EXIT, sevenMinutesLater, "a")
        assertEquals(sevenMinutesLater, presence.event(listOf("parking"), GpsTransitionV2.EXIT,
            sevenMinutesLater + 60_000L, "a"))
    }

    @Test fun workZoneEnteredAfterDepartureCannotFillTheUnknownGap() {
        val presence = Presence(setOf("work-a", "parking"), sameWorksite)
        presence.event(listOf("work-a"), GpsTransitionV2.EXIT, sixteen, "work-a")
        presence.event(listOf("work-b"), GpsTransitionV2.ENTER, sixteen + 120_000L, "work-a")
        presence.event(listOf("work-b"), GpsTransitionV2.EXIT, sevenMinutesLater, "work-a")
        assertEquals(sixteen, presence.event(listOf("parking"), GpsTransitionV2.EXIT,
            sevenMinutesLater + 60_000L, "work-a"))
    }

    @Test fun staleDuplicateExitDoesNotReplaceTheFirstObservation() {
        val presence = Presence(setOf("work-a", "parking"), sameWorksite)
        presence.event(listOf("work-a"), GpsTransitionV2.EXIT, sixteen, "work-a")
        presence.event(listOf("work-a"), GpsTransitionV2.EXIT, sixteen + 120_000L, "work-a")
        assertEquals(sixteen, presence.event(listOf("parking"), GpsTransitionV2.EXIT,
            sevenMinutesLater, "work-a"))
    }

    @Test fun reentryStartsNewObservationCycleAndDoesNotReusePreviousExit() {
        val presence = Presence(setOf("work-a", "parking"), sameWorksite)
        presence.event(listOf("work-a"), GpsTransitionV2.EXIT, sixteen, "work-a")
        presence.event(listOf("work-a"), GpsTransitionV2.ENTER, sixteen + 120_000L, "work-a")
        assertTrue(presence.observations.isEmpty())
        presence.event(listOf("work-a"), GpsTransitionV2.EXIT, sevenMinutesLater, "work-a")
        assertEquals(sevenMinutesLater, presence.event(listOf("parking"), GpsTransitionV2.EXIT,
            sevenMinutesLater + 60_000L, "work-a"))
    }

    @Test fun reentryOfLinkedWorkZoneDoesNotAttachOldEvidenceToItsNewPresence() {
        val presence = Presence(setOf("work-a", "work-b", "parking"), sameWorksite)
        presence.event(listOf("work-a"), GpsTransitionV2.EXIT, sixteen, "work-a")
        presence.event(listOf("work-b"), GpsTransitionV2.EXIT, sixteen + 30_000L, "work-a")
        presence.event(listOf("work-b"), GpsTransitionV2.ENTER, sixteen + 120_000L, "work-a")
        presence.event(listOf("work-b"), GpsTransitionV2.EXIT, sevenMinutesLater, "work-a")
        assertNull(presence.event(listOf("parking"), GpsTransitionV2.EXIT,
            sevenMinutesLater + 60_000L, "work-a"))
    }

    @Test fun oldPendingIdsWithoutTimestampsNeverBorrowParkingTime() {
        val presence = Presence(setOf("parking"), sameWorksite)
        presence.pending = setOf("work-a")
        assertNull(presence.event(listOf("parking"), GpsTransitionV2.EXIT, sevenMinutesLater, "work-a"))
    }

    @Test fun codecRejectsCorruptionAndPreservesArbitraryZoneIds() {
        val original = mapOf("atelier|entrée : +\n" to GpsExitObservationV2.Observation(sixteen, setOf("zone%2F|+")))
        assertEquals(original, GpsExitObservationV2.decode(GpsExitObservationV2.encode(original)))
        for (corrupt in listOf("0|work", "bad|work", "42|", "42|%xx", "42|work|work")) {
            assertTrue(GpsExitObservationV2.decode(setOf(corrupt)).isEmpty())
        }
        assertTrue(GpsExitObservationV2.decode(setOf("42|work", "43|work")).isEmpty())
    }

    @Test fun missingContinuityProofOrClockRegressionNeverInventsAnExit() {
        val observations = mapOf("work-a" to GpsExitObservationV2.Observation(sixteen, setOf("work-b")))
        assertNull(GpsExitObservationV2.resolveExitAtMs("work-a", setOf("work-a", "work-b"),
            observations, sevenMinutesLater, sameWorksite))
        assertNull(GpsExitObservationV2.resolveExitAtMs("work-a", setOf("work-a"),
            mapOf("work-a" to GpsExitObservationV2.Observation(sevenMinutesLater, emptySet())),
            sixteen, sameWorksite))
    }

    @Test fun convergingContinuityPathsAreNotTreatedAsACycle() {
        val observations = mapOf(
            "work-a" to GpsExitObservationV2.Observation(sixteen, setOf("work-b", "work-c")),
            "work-b" to GpsExitObservationV2.Observation(sixteen + 60_000L, setOf("work-c")),
            "work-c" to GpsExitObservationV2.Observation(sevenMinutesLater, emptySet())
        )
        assertEquals(sevenMinutesLater, GpsExitObservationV2.resolveExitAtMs("work-a", observations.keys,
            observations, sevenMinutesLater, sameWorksite))
    }
}
