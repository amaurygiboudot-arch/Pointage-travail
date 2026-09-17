package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsReliabilityV2Test {
    private val minute = 60_000L

    @Test
    fun `confirmed place address receives the canonical paid total`() {
        val analytics = AnalyticsEngineV2.summarize(
            sessions = listOf(session("known", placeLabel = "Atelier — 12 rue des Forges")),
            timeEngine = DefaultTimeEngineV2,
            nowMs = 120 * minute
        )

        val place = AnalyticsEngineV2.placeTotalForAddress(analytics, "12 RUE DES FORGES")

        assertTrue(analytics.timeTotalsReliable)
        assertTrue(analytics.placeTotalsReliable)
        assertTrue(place.reliable)
        assertEquals(60 * minute, place.paidMs)
        assertEquals(1, place.sessions)
    }

    @Test
    fun `unassigned session makes every place subtotal incomplete without corrupting time total`() {
        val analytics = AnalyticsEngineV2.summarize(
            sessions = listOf(
                session("known", placeLabel = "12 rue des Forges"),
                session("unknown", start = 70 * minute, end = 130 * minute, placeLabel = null)
            ),
            timeEngine = DefaultTimeEngineV2,
            nowMs = 140 * minute
        )

        val place = AnalyticsEngineV2.placeTotalForAddress(analytics, "12 rue des Forges")

        assertTrue(analytics.timeTotalsReliable)
        assertFalse(analytics.placeTotalsReliable)
        assertFalse(place.reliable)
    }

    @Test
    fun `unresolved pause blocks partial time totals`() {
        val unresolvedPause = PauseV2(
            startMs = 20 * minute,
            endMs = 30 * minute,
            paid = null,
            source = EventSourceV2.MANUAL,
            status = DecisionStatusV2.TO_CONFIRM
        )
        val analytics = AnalyticsEngineV2.summarize(
            sessions = listOf(session("uncertain", placeLabel = "Atelier", pauses = listOf(unresolvedPause))),
            timeEngine = DefaultTimeEngineV2,
            nowMs = 120 * minute
        )

        val place = AnalyticsEngineV2.placeTotalForAddress(analytics, "Atelier")

        assertFalse(analytics.timeTotalsReliable)
        assertFalse(analytics.placeTotalsReliable)
        assertFalse(place.reliable)
        assertTrue(analytics.warnings > 0)
    }

    private fun session(
        id: String,
        start: Long = minute,
        end: Long = 61 * minute,
        placeLabel: String?,
        pauses: List<PauseV2> = emptyList()
    ) = WorkSessionV2(
        id = id,
        employerId = "employer",
        realArrivalMs = start,
        countedEntryMs = start,
        countedExitMs = end,
        realExitMs = end,
        pauses = pauses,
        status = SessionStatusV2.CLOSED,
        placeLabel = placeLabel
    )
}
