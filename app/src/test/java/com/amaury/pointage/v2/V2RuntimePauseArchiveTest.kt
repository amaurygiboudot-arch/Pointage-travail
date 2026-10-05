package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2RuntimePauseArchiveTest {
    private fun closedSession(id: String = "closed-current") = WorkSessionV2(
        id = id,
        employerId = "employer-a",
        realArrivalMs = 10_000L,
        countedEntryMs = 10_000L,
        countedExitMs = 20_000L,
        realExitMs = 20_000L,
        status = SessionStatusV2.CLOSED,
        placeLabel = "Atelier"
    )

    private fun history(session: WorkSessionV2 = closedSession()): JSONArray =
        V2RuntimeStore.historyWithClosedSession(JSONArray(), session, companySlot = 2)!!

    private fun pause(start: Long, end: Long, paid: Boolean) = JSONObject()
        .put("start", start)
        .put("end", end)
        .put("source", "MANUAL")
        .put("paid", paid)

    @Test
    fun `pause added after checkout remains in history after current session is replaced`() {
        val source = history()
        val updated = JSONArray()
            .put(pause(12_000L, 13_000L, paid = true))
            .put(pause(15_000L, 16_000L, paid = false))

        val saved = V2RuntimeStore.historyWithUpdatedCurrentPauses(source, closedSession(), updated)!!
        // The next entry clears the current pause keys; an independent archive reload must retain both.
        val reloaded = JSONArray(saved.toString()).getJSONObject(0)
        assertEquals(2, reloaded.getJSONArray("pauses").length())
        assertTrue(reloaded.getJSONArray("pauses").getJSONObject(0).getBoolean("paid"))
        assertEquals(false, reloaded.getJSONArray("pauses").getJSONObject(1).getBoolean("paid"))
        assertEquals("employer-a", reloaded.getString("employerId"))
        assertEquals("Atelier", reloaded.getString("placeLabel"))
        assertEquals(2, reloaded.getInt("companySlot"))
        assertEquals(0, source.getJSONObject(0).getJSONArray("pauses").length())
        assertTrue(V2RuntimeHistoryGuardV2.inspect(saved).reliable)
    }

    @Test
    fun `archive update selects session identity and preserves other sessions`() {
        val source = history(
            closedSession("other").copy(
                realArrivalMs = 1_000L,
                countedEntryMs = 1_000L,
                countedExitMs = 9_000L,
                realExitMs = 9_000L
            )
        )
        source.put(history().getJSONObject(0))
        val previous = source.getJSONObject(0).toString()
        val pauses = JSONArray().put(pause(12_000L, 13_000L, paid = false))

        val saved = V2RuntimeStore.historyWithUpdatedCurrentPauses(source, closedSession(), pauses)!!

        assertEquals(previous, saved.getJSONObject(0).toString())
        assertEquals(1, saved.getJSONObject(1).getJSONArray("pauses").length())
    }

    @Test
    fun `same archive preparation can be retried without duplicating pauses`() {
        val pauses = JSONArray().put(pause(12_000L, 13_000L, paid = false))
        val once = V2RuntimeStore.historyWithUpdatedCurrentPauses(history(), closedSession(), pauses)!!
        val twice = V2RuntimeStore.historyWithUpdatedCurrentPauses(once, closedSession(), pauses)!!

        assertEquals(1, twice.length())
        assertEquals(1, twice.getJSONObject(0).getJSONArray("pauses").length())
    }

    @Test
    fun `missing or inconsistent archived session blocks both writes`() {
        val pauses = JSONArray().put(pause(12_000L, 13_000L, paid = false))
        assertNull(V2RuntimeStore.historyWithUpdatedCurrentPauses(JSONArray(), closedSession(), pauses))
        val inconsistent = history()
        inconsistent.getJSONObject(0).put("realExit", 21_000L)
        assertNull(V2RuntimeStore.historyWithUpdatedCurrentPauses(inconsistent, closedSession(), pauses))
    }

    @Test
    fun `out of session pause and corrupt history cannot be archived`() {
        assertNull(V2RuntimeStore.historyWithUpdatedCurrentPauses(
            history(), closedSession(), JSONArray().put(pause(19_000L, 21_000L, paid = true))
        ))
        assertNull(V2RuntimeStore.historyWithUpdatedCurrentPauses(
            JSONArray().put("corrupt"), closedSession(), JSONArray().put(pause(12_000L, 13_000L, paid = false))
        ))
    }
}
