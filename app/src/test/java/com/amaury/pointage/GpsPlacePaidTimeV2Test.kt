package com.amaury.pointage

import com.amaury.pointage.v2.engine.DefaultTimeEngineV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsPlacePaidTimeV2Test {
    private val hour = 3_600_000L
    private fun session(id: String, company: String, place: String, start: Int, end: Int) = WorkSessionV2(
        id = id, employerId = company, placeId = place, placeLabel = place,
        realArrivalMs = start * hour, countedEntryMs = start * hour,
        countedExitMs = end * hour, realExitMs = end * hour, status = SessionStatusV2.CLOSED
    )

    @Test fun `un chevauchement entre lieux du meme employeur bloque chaque sous total`() {
        val sessions = listOf(session("a", "company", "site-a", 8, 10), session("b", "company", "site-b", 9, 11))
        for (site in listOf("site-a", "site-b")) {
            assertNull(gpsPlacePaidTimeV2(sessions, "company", setOf(site), DefaultTimeEngineV2, 12 * hour))
        }
    }

    @Test fun `les lieux successifs et un autre employeur conservent le sous total exact`() {
        val sessions = listOf(
            session("a", "company", "site-a", 8, 10),
            session("b", "company", "site-b", 10, 12),
            session("c", "other-company", "site-c", 9, 11)
        )
        assertEquals(2 * hour, gpsPlacePaidTimeV2(sessions, "company", setOf("site-a"), DefaultTimeEngineV2, 13 * hour))
    }
}
