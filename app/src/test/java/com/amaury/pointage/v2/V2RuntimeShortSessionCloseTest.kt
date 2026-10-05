package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.DefaultTimeEngineV2
import com.amaury.pointage.v2.engine.WorkTimePolicyV2
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2RuntimeShortSessionCloseTest {
    private val day = 86_400_000L
    private fun at(hour: Int, minute: Int) = day + (hour * 60L + minute) * 60_000L

    @Test
    fun `checkout before rounded entry preserves real presence and leaves counted time to confirm`() {
        val entry = at(5, 11)
        val exit = at(5, 20)
        val countedEntry = WorkTimePolicyV2.countedEntry(entry)
        val countedExit = V2RuntimeStore.countedExitForClosure(exit, null, countedEntry)
        assertEquals(at(5, 30), countedEntry)
        assertNull(countedExit)

        val closed = WorkSessionV2(
            id = "short-shift",
            employerId = null,
            realArrivalMs = entry,
            countedEntryMs = countedEntry,
            realExitMs = exit,
            countedExitMs = countedExit,
            status = SessionStatusV2.CLOSED
        )
        val history = V2RuntimeStore.historyWithClosedSession(JSONArray(), closed, null)!!
        val recorded = history.getJSONObject(0)
        assertEquals(exit, recorded.getLong("realExit"))
        assertEquals(countedEntry, recorded.getLong("countedEntry"))
        assertTrue(recorded.isNull("countedExit"))
        assertTrue(V2RuntimeHistoryGuardV2.inspect(history).reliable)
        val result = DefaultTimeEngineV2.calculate(closed, exit)
        assertEquals(9 * 60_000L, result.presenceMs)
        assertFalse(result.reliable)
    }

    @Test
    fun `checkout exactly at rounded entry also remains recordable without inventing duration`() {
        assertNull(V2RuntimeStore.countedExitForClosure(at(5, 30), null, at(5, 30)))
    }

    @Test
    fun `ordinary checkout preserves configured end tolerance`() {
        assertEquals(at(13, 0), V2RuntimeStore.countedExitForClosure(at(13, 20), at(13, 0), at(5, 0)))
        assertEquals(at(13, 21), V2RuntimeStore.countedExitForClosure(at(13, 21), at(13, 0), at(5, 0)))
        assertEquals(at(12, 45), V2RuntimeStore.countedExitForClosure(at(12, 45), at(13, 0), at(5, 0)))
    }

    @Test
    fun `unresolved counted entry is never reconstructed when recording checkout`() {
        assertEquals(at(13, 0), V2RuntimeStore.countedExitForClosure(at(13, 0), null, null))
    }

    @Test
    fun `chronologically invalid real checkout is still refused by archive guard`() {
        val closed = WorkSessionV2(
            id = "invalid-real-order",
            employerId = null,
            realArrivalMs = at(5, 11),
            countedEntryMs = at(5, 30),
            realExitMs = at(5, 10),
            countedExitMs = null,
            status = SessionStatusV2.CLOSED
        )
        assertNull(V2RuntimeStore.historyWithClosedSession(JSONArray(), closed, null))
    }

    @Test
    fun `checkout before an already recorded pause end cannot corrupt runtime or history`() {
        val closed = WorkSessionV2(
            id = "clock-moved-backwards",
            employerId = null,
            realArrivalMs = at(5, 0),
            countedEntryMs = at(5, 0),
            realExitMs = at(8, 20),
            countedExitMs = at(8, 20),
            status = SessionStatusV2.CLOSED,
            pauses = listOf(PauseV2(at(8, 0), at(8, 30), paid = false, source = EventSourceV2.MANUAL))
        )
        assertNull(V2RuntimeStore.historyWithClosedSession(JSONArray(), closed, null))
        assertNull(V2RuntimeStore.historyWithClosedSession(
            JSONArray(),
            closed.copy(pauses = listOf(PauseV2(at(4, 50), at(5, 10), paid = true, source = EventSourceV2.MANUAL))),
            null
        ))
        assertTrue(V2RuntimeStore.historyWithClosedSession(
            JSONArray(),
            closed.copy(pauses = listOf(PauseV2(at(8, 0), at(8, 20), paid = false, source = EventSourceV2.MANUAL))),
            null
        ) != null)
    }
}
