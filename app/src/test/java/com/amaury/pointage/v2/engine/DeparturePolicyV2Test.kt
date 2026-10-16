package com.amaury.pointage.v2.engine

import org.junit.Assert.*
import org.junit.Test

class DeparturePolicyV2Test {
    private val facts = DeparturePolicyV2.Facts(1_000_000, 100_000, 1_300_000, true, true, false, false)
    @Test fun manualCanUseObservedTimeOnlyAfterReturnWindow() {
        assertTrue(DeparturePolicyV2.eligible(facts))
        assertFalse(DeparturePolicyV2.eligible(facts.copy(nowMs = 1_119_999)))
        assertTrue(DeparturePolicyV2.eligible(facts.copy(nowMs = 1_120_000)))
    }
    @Test fun automaticRequiresExplicitPolicyAndObservedDepartureAfterExpectedEnd() {
        assertFalse(DeparturePolicyV2.automatic(facts, false, 900_000))
        assertFalse(DeparturePolicyV2.automatic(facts, true, null))
        assertFalse(DeparturePolicyV2.automatic(facts, true, 1_100_000))
        assertTrue(DeparturePolicyV2.automatic(facts, true, 900_000))
    }
    @Test fun returnsOtherEmployersOverlapsPausesTravelAndUnknownContextBlock() {
        for (blocked in listOf(facts.copy(contextMatches = false), facts.copy(sessionOpen = false),
            facts.copy(hasActiveZone = true), facts.copy(unresolved = true),
            facts.copy(observedAtMs = facts.arrivalMs), facts.copy(nowMs = 999_999))) {
            assertFalse(DeparturePolicyV2.eligible(blocked))
            assertFalse(DeparturePolicyV2.automatic(blocked, true, 900_000))
        }
    }
    @Test fun laterParkingExitDefersResolutionWithoutRedatingWorkDeparture() {
        val parkingExit = facts.copy(lastObservedAtMs = facts.nowMs)
        assertFalse(DeparturePolicyV2.eligible(parkingExit))
        assertFalse(DeparturePolicyV2.automatic(parkingExit, true, 900_000))
        assertTrue(DeparturePolicyV2.eligible(parkingExit.copy(nowMs = parkingExit.nowMs + 120_000)))
        assertEquals(1_000_000L, parkingExit.observedAtMs)
    }

    @Test fun overnightUsesAbsoluteInstantsWithoutCalendarOrEightHourAssumptions() {
        val overnight = facts.copy(arrivalMs = 86_000_000, observedAtMs = 90_000_000, nowMs = 90_120_000, lastObservedAtMs = 90_000_000)
        assertTrue(DeparturePolicyV2.automatic(overnight, true, 89_000_000))
        assertFalse(DeparturePolicyV2.automatic(overnight, true, 91_000_000))
    }
}
