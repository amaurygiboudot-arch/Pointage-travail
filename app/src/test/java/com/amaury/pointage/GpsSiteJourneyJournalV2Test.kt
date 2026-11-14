package com.amaury.pointage

import org.junit.Assert.*
import org.junit.Test

class GpsSiteJourneyJournalV2Test {
    private fun event(zone: String, kind: GpsSiteObservationKindV2, at: Long,
        context: String = "configuration-epoch", employer: String? = "employer-a", site: String = zone,
        home: Boolean = true) = GpsSiteObservationV2("$context-$zone-$kind-$at", context, zone, site,
            zone, employer, kind, at, home)
    private fun enter(zone: String, at: Long = 1000) = event(zone, GpsSiteObservationKindV2.WORK_ENTER, at)
    private fun exit(zone: String, at: Long = 2000) = event(zone, GpsSiteObservationKindV2.WORK_EXIT, at)
    private fun journeys(vararg events: GpsSiteObservationV2) = checkNotNull(GpsSiteJourneyJournalV2.journeys(events.toList()))

    @Test fun twoSitesKeepObservedDurationButNeverClaimProfessionalPurposeOrDistance() {
        val journey = journeys(enter("A"), exit("A"), enter("B", 122000)).single()
        assertEquals(120000L, journey.durationMs)
        assertEquals("À confirmer", journey.status)
        assertNull(journey.distanceMeters)
        assertTrue(journey.reason.contains("continuité"))
    }

    @Test fun normalCommutesAndHomeVisitBetweenWorksitesAreExcluded() {
        assertTrue(journeys(event("home", GpsSiteObservationKindV2.HOME_EXIT, 500), enter("A")).isEmpty())
        assertTrue(journeys(enter("A"), exit("A"), event("home", GpsSiteObservationKindV2.HOME_ENTER, 3000)).isEmpty())
        assertTrue(journeys(enter("A"), exit("A"), event("home", GpsSiteObservationKindV2.HOME_ENTER, 3000),
            event("home", GpsSiteObservationKindV2.HOME_EXIT, 4000), enter("B", 5000)).isEmpty())
    }

    @Test fun homeOverlapAlwaysBreaksPairingRegardlessOfBatchOrder() {
        val departure = listOf(enter("A"), exit("A"))
        val home = event("home", GpsSiteObservationKindV2.HOME_ENTER, 3000)
        val work = enter("B", 3000)
        assertTrue(GpsSiteJourneyJournalV2.journeys(departure + listOf(home, work))!!.isEmpty())
        assertTrue(GpsSiteJourneyJournalV2.journeys(departure + listOf(work, home))!!.isEmpty())
    }

    @Test fun repeatCallbackDoesNotShiftFirstObservedDeparture() {
        val events = listOf(enter("A"), exit("A"))
        val duplicate = exit("A", 2200)
        assertEquals(events, GpsSiteJourneyJournalV2.append(events, duplicate))
        assertEquals(events, GpsSiteJourneyJournalV2.append(events, events.last()))
        assertEquals(3000L, journeys(*(events + enter("B", 5000)).toTypedArray()).single().durationMs)
    }

    @Test fun missingHomeAndDifferentEmployerRemainExplicitlyUncertain() {
        val noHome = journeys(enter("A").copy(homeConfigured = false), exit("A").copy(homeConfigured = false),
            enter("B", 5000).copy(homeConfigured = false)).single()
        assertTrue(noHome.reason.contains("Domicile non configuré"))
        val mixed = journeys(enter("A"), exit("A"), enter("B", 5000).copy(employerId = "employer-b")).single()
        assertTrue(mixed.reason.contains("Entreprises différentes"))
        assertEquals("À confirmer", mixed.status)
    }

    @Test fun changedConfigurationOrAccountEpochNeverPairsOldDeparture() {
        assertTrue(journeys(enter("A"), exit("A"), enter("B", 5000).copy(context = "other-epoch")).isEmpty())
    }

    @Test fun interruptionNeverManufacturesArrivalOrTrip() {
        val stop = GpsSiteObservationV2("interrupt", "configuration-epoch", "", "", "", null,
            GpsSiteObservationKindV2.INTERRUPTION, 3000, false)
        assertTrue(journeys(enter("A"), exit("A"), stop, enter("B", 5000)).isEmpty())
    }

    @Test fun sameSiteReturnAndEquivalentZonesDoNotProduceTrip() {
        assertTrue(journeys(enter("A"), exit("A"), enter("A", 5000)).isEmpty())
        assertTrue(journeys(enter("A"), exit("A"), enter("A-alias", 5000).copy(siteId = "A")).isEmpty())
    }

    @Test fun overlappingDistinctSitesPreserveFactsWithoutInventingTransfer() {
        val events = listOf(enter("A"), enter("B", 1500), exit("A", 2000), exit("B", 3000), enter("C", 5000))
        val result = GpsSiteJourneyJournalV2.journeys(events)!!
        assertEquals(5, events.size)
        assertEquals(1, result.size)
        assertEquals("B", result.single().departure.zoneId)
        assertEquals("C", result.single().arrival.zoneId)
        assertEquals(2000L, result.single().durationMs)
    }

    @Test fun simultaneousMultipleDestinationSitesDoNotChooseArbitraryWinner() {
        assertTrue(journeys(enter("A"), exit("A"), enter("B", 5000), enter("C", 5000)).isEmpty())
        assertTrue(journeys(enter("A"), exit("A"), enter("C", 5000), enter("B", 5000)).isEmpty())
    }

    @Test fun exitWithoutKnownEntryDoesNotInventContinuityAcrossUpgradeOrGap() {
        assertTrue(journeys(exit("A"), enter("B", 5000)).isEmpty())
    }

    @Test fun noNegativeDurationOrBackdatedAppend() {
        assertNull(GpsSiteJourneyJournalV2.journeys(listOf(exit("A", 5000), enter("B", 2000))))
        assertNull(GpsSiteJourneyJournalV2.append(listOf(enter("A", 5000)), exit("A", 2000)))
        assertNull(GpsSiteJourneyJournalV2.append(emptyList(), enter("A", 0)))
        assertTrue(journeys(enter("A"), exit("A", 2000), enter("B", 2000)).isEmpty())
    }

    @Test fun codecRoundTripAndOwnerIsolation() {
        val events = listOf(enter("A"), exit("A"), enter("B", 5000))
        val raw = GpsSiteJourneyJournalV2.encode("uid:A", events)!!
        assertEquals(events, GpsSiteJourneyJournalV2.decode("uid:A", raw))
        assertNull(GpsSiteJourneyJournalV2.decode("uid:B", raw))
        assertNull(GpsSiteJourneyJournalV2.decode("uid:A", "{}"))
        assertNull(GpsSiteJourneyJournalV2.decode("uid:A", raw.replace("\"at\":1000", "\"at\":\"1000\"")))
    }

    @Test fun collidingEventIdentityIsRejectedInsteadOfOverwritten() {
        val event = enter("A")
        assertNull(GpsSiteJourneyJournalV2.append(listOf(event), event.copy(label = "different site")))
    }

    @Test fun homeIsMonitoredButNeverClassifiedAsWork() {
        val stored = parsePersistedGpsZones("""[{"id":"home","latitude":1,"longitude":2,"radius":100,"pointType":"HOME"}]""") as GpsZonesReadResult.Valid
        val home = stored.zones.single()
        assertEquals(GpsZoneRoleV2.HOME, home.roleForContextV2())
        assertTrue(home.registersAutomaticGeofenceV2())
        assertEquals(com.amaury.pointage.v2.engine.GpsPointTypeV2.OTHER, GpsTriggeredZoneSelectionV2.pointType(home))
        assertTrue(planStoredGeofenceRegistrationV2(true, true, true, stored) is StoredGeofencePlanV2.Register)
    }
}
