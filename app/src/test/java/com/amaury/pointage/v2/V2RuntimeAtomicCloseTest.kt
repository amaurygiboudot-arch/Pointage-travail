package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2RuntimeAtomicCloseTest {
    private fun closedSession(id: String = "session-atomic") = WorkSessionV2(
        id = id,
        employerId = "employer-a",
        realArrivalMs = 10_000L,
        countedEntryMs = 11_000L,
        countedExitMs = 19_000L,
        realExitMs = 20_000L,
        status = SessionStatusV2.CLOSED
    )

    @Test
    fun `une session fermee fiable est preparee pour le meme commit que le runtime`() {
        val history = V2RuntimeStore.historyWithClosedSession(
            sourceHistory = JSONArray(),
            session = closedSession(),
            companySlot = 2
        )

        assertNotNull(history)
        assertTrue(V2RuntimeHistoryGuardV2.inspect(history!!).reliable)
        assertEquals(1, history.length())
        val stored = history.getJSONObject(0)
        assertEquals("session-atomic", stored.getString("id"))
        assertEquals(10_000L, stored.getLong("realEntry"))
        assertEquals(20_000L, stored.getLong("realExit"))
        assertEquals(11_000L, stored.getLong("countedEntry"))
        assertEquals(19_000L, stored.getLong("countedExit"))
        assertEquals(2, stored.getInt("companySlot"))
    }

    @Test
    fun `un identifiant deja historique est refuse au lieu de creer une seconde verite`() {
        val first = V2RuntimeStore.historyWithClosedSession(
            sourceHistory = JSONArray(),
            session = closedSession(),
            companySlot = null
        )!!

        val duplicate = V2RuntimeStore.historyWithClosedSession(
            sourceHistory = first,
            session = closedSession(),
            companySlot = null
        )

        assertNull(duplicate)
    }

    @Test
    fun `une session encore ouverte ne peut jamais etre injectee dans historique`() {
        val open = closedSession().copy(
            countedExitMs = null,
            realExitMs = null,
            status = SessionStatusV2.OPEN
        )

        assertNull(
            V2RuntimeStore.historyWithClosedSession(
                sourceHistory = JSONArray(),
                session = open,
                companySlot = null
            )
        )
    }

    @Test
    fun `une pause fermee sans statut paye explicite est refusee`() {
        val ambiguousPause = PauseV2(
            startMs = 12_000L,
            endMs = 13_000L,
            paid = null,
            source = EventSourceV2.MANUAL
        )

        assertNull(
            V2RuntimeStore.historyWithClosedSession(
                sourceHistory = JSONArray(),
                session = closedSession().copy(pauses = listOf(ambiguousPause)),
                companySlot = null
            )
        )
    }

    @Test
    fun `une pause encore ouverte est refusee dans une session fermee`() {
        val openPause = PauseV2(
            startMs = 12_000L,
            endMs = null,
            paid = true,
            source = EventSourceV2.MANUAL
        )

        assertNull(
            V2RuntimeStore.historyWithClosedSession(
                sourceHistory = JSONArray(),
                session = closedSession().copy(pauses = listOf(openPause)),
                companySlot = null
            )
        )
    }
}
