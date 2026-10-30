package com.amaury.pointage

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class TravelJourneyCodecV2Test {
    private val owner = "uid:account-one"
    private val start = 1_800_000_000_000L

    @Test
    fun roundTripPreservesKnownAndUnknownFacts() {
        val departure = TravelFixV2(46.78, -1.75, 14.0, start + 4_000)
        val trip = TravelJourneyV2("trip-1", start, start + 3_600_000,
            "employer-1", departure, null)
        val raw = requireNotNull(TravelJourneyCodecV2.encode(owner, listOf(trip)))
        assertEquals(listOf(trip), TravelJourneyCodecV2.decode(owner, raw))
        assertNull(TravelJourneyCodecV2.decode(owner, raw)!![0].arrival)
        assertNull(TravelJourneyCodecV2.decode("uid:other-user", raw))
    }

    @Test
    fun rejectsTwoOpenJourneysAndImpossibleTimes() {
        val first = TravelJourneyV2("one", start)
        val second = TravelJourneyV2("two", start + 100)
        assertNull(TravelJourneyCodecV2.encode(owner, listOf(first, second)))
        assertNull(TravelJourneyCodecV2.encode(owner,
            listOf(first.copy(endedAtMs = start - 1))))
        assertNull(TravelJourneyCodecV2.encode(owner,
            listOf(first, first)))
    }

    @Test
    fun rejectsUnknownVersionsAndInvalidGps() {
        val trip = TravelJourneyV2("t", start, start + 50_000)
        val raw = requireNotNull(TravelJourneyCodecV2.encode(owner, listOf(trip)))
        assertNull(TravelJourneyCodecV2.decode(owner,
            JSONObject(raw).put("version", 999).toString()))
        assertNull(TravelJourneyCodecV2.encode(owner, listOf(trip.copy(
            departure = TravelFixV2(200.0, 0.0, 10.0, start)))))
        assertNull(TravelJourneyCodecV2.encode(owner, listOf(trip.copy(
            arrival = TravelFixV2(46.0, -1.0, 10.0, start + 3_600_000)))))
    }

    @Test
    fun doesNotEraseUnreadableJournal() {
        assertNull(TravelJourneyCodecV2.decode(owner, "{broken"))
        assertNull(TravelJourneyCodecV2.decode(owner,
            "{\"version\":1,\"owner\":\"uid:account-one\",\"journeys\":[{}]}"))
    }
}
