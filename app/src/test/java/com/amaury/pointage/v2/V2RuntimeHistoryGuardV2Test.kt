package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.EventSourceV2
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2RuntimeHistoryGuardV2Test {
    private fun pause(
        start: Long = 1_000L,
        end: Long = 2_000L,
        source: EventSourceV2 = EventSourceV2.MANUAL,
        paid: Boolean = false
    ) = JSONObject()
        .put("start", start)
        .put("end", end)
        .put("source", source.name)
        .put("paid", paid)

    private fun session(
        id: String = "session-1",
        realEntry: Long = 10_000L,
        realExit: Long = 20_000L,
        countedEntry: Long = 11_000L,
        countedExit: Long = 19_000L,
        pauses: JSONArray = JSONArray()
    ) = JSONObject()
        .put("id", id)
        .put("employerId", "company-a")
        .put("realEntry", realEntry)
        .put("countedEntry", countedEntry)
        .put("realExit", realExit)
        .put("countedExit", countedExit)
        .put("pauses", pauses)
        .put("placeId", JSONObject.NULL)
        .put("placeLabel", JSONObject.NULL)

    @Test
    fun `historique vide explicite est fiable`() {
        val result = V2RuntimeHistoryGuardV2.decode("[]")

        assertTrue(result.reliable)
        assertTrue(result.history.length() == 0)
    }

    @Test
    fun `json vide illisible ou mauvais type est non fiable`() {
        assertFalse(V2RuntimeHistoryGuardV2.decode("   ").reliable)
        assertFalse(V2RuntimeHistoryGuardV2.decode("not-json").reliable)
        assertFalse(V2RuntimeHistoryGuardV2.decode("{}").reliable)
    }

    @Test
    fun `une entree non objet contamine tout lhistorique`() {
        val raw = JSONArray().put(session()).put("broken").toString()

        val result = V2RuntimeHistoryGuardV2.decode(raw)

        assertFalse(result.reliable)
    }

    @Test
    fun `identifiant manquant nest accepte que par la phase de migration ciblee`() {
        val withoutId = session().apply { remove("id") }
        val raw = JSONArray().put(withoutId).toString()

        assertFalse(V2RuntimeHistoryGuardV2.decode(raw).reliable)
        assertTrue(V2RuntimeHistoryGuardV2.decode(raw, allowLegacyMissingIds = true).reliable)
    }

    @Test
    fun `doublon de session rend lhistorique ambigu`() {
        val raw = JSONArray().put(session()).put(session()).toString()

        assertFalse(V2RuntimeHistoryGuardV2.decode(raw).reliable)
    }

    @Test
    fun `dates de session incoherentes sont refusees`() {
        val invalidReal = session(realEntry = 20_000L, realExit = 10_000L)
        val invalidCounted = session(id = "session-2", countedEntry = 19_000L, countedExit = 11_000L)

        assertFalse(V2RuntimeHistoryGuardV2.decode(JSONArray().put(invalidReal).toString()).reliable)
        assertFalse(V2RuntimeHistoryGuardV2.decode(JSONArray().put(invalidCounted).toString()).reliable)
    }

    @Test
    fun `pause corrompue ne devient jamais aucune pause`() {
        val missingPaid = pause().apply { remove("paid") }
        val unknownSource = pause().put("source", "UNKNOWN_SOURCE")
        val inverted = pause(start = 2_000L, end = 1_000L)

        assertFalse(V2RuntimeHistoryGuardV2.validPauseArray(JSONArray().put(missingPaid)))
        assertFalse(V2RuntimeHistoryGuardV2.validPauseArray(JSONArray().put(unknownSource)))
        assertFalse(V2RuntimeHistoryGuardV2.validPauseArray(JSONArray().put(inverted)))
    }

    @Test
    fun `pause dupliquee rend le paquet ambigu`() {
        val p = pause()

        assertFalse(V2RuntimeHistoryGuardV2.validPauseArray(JSONArray().put(p).put(JSONObject(p.toString()))))
    }

    @Test
    fun `session valide avec pause valide reste fiable`() {
        val raw = JSONArray().put(session(pauses = JSONArray().put(pause()))).toString()

        assertTrue(V2RuntimeHistoryGuardV2.decode(raw).reliable)
    }
}
