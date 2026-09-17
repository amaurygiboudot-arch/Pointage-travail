package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class V2BackupManagerTest {
    @Test
    fun `versions de sauvegarde historiques et courante sont acceptees`() {
        assertTrue(V2BackupManager.isSupportedFormatVersion(1))
        assertTrue(V2BackupManager.isSupportedFormatVersion(2))
        assertTrue(V2BackupManager.isSupportedFormatVersion(3))
        assertTrue(V2BackupManager.isSupportedFormatVersion(4))
        assertFalse(V2BackupManager.isSupportedFormatVersion(0))
        assertFalse(V2BackupManager.isSupportedFormatVersion(5))
    }

    @Test
    fun `les fichiers entreprises salaire v2 sont geres par le backup`() {
        assertTrue(V2BackupManager.isManagedPreferenceFileName("salary_companies_v2"))
        assertTrue(V2BackupManager.isManagedPreferenceFileName("salary_company_siret_12345678901234"))
        assertTrue(V2BackupManager.isManagedPreferenceFileName("salary_company_name_entreprise_test"))
        assertFalse(V2BackupManager.isManagedPreferenceFileName("salary_company"))
        assertFalse(V2BackupManager.isManagedPreferenceFileName("firebase_device_registry"))
    }

    @Test
    fun `un paquet de preferences doit etre entierement type`() {
        val valid = JSONObject()
            .put("name", typed("s", "HoraTrack"))
            .put("enabled", typed("b", true))
            .put("count", typed("i", 2))
            .put("timestamp", typed("l", 2_000L))
            .put("ratio", typed("f", 1.5))
            .put("labels", typed("set", JSONArray().put("a").put("b")))

        assertTrue(V2BackupManager.isValidTypedPreferencePayload(valid))
        assertFalse(V2BackupManager.isValidTypedPreferencePayload(JSONObject().put("broken", "raw")))
        assertFalse(V2BackupManager.isValidTypedPreferencePayload(JSONObject().put("unknown", typed("x", "value"))))
        assertFalse(V2BackupManager.isValidTypedPreferencePayload(JSONObject().put("fraction", typed("i", 1.5))))
        assertFalse(V2BackupManager.isValidTypedPreferencePayload(JSONObject().put("set", typed("set", JSONArray().put(1)))))
    }

    @Test
    fun `historique absent reste compatible avec les anciennes sauvegardes`() {
        assertEquals(0, V2BackupManager.decodeBackupHistory(JSONObject()).length())
    }

    @Test
    fun `historique present mais corrompu bloque la restauration`() {
        val saved = JSONObject().put("history", typed("s", "not-json"))

        assertTrue(runCatching { V2BackupManager.decodeBackupHistory(saved) }.isFailure)
    }

    @Test
    fun `fusion historique ignore une session strictement identique et ajoute une nouvelle`() {
        val local = session("session-1", 10_000L, 20_000L)
        val remoteSameId = JSONObject(local.toString())
        val remoteNew = session("session-2", 50_000L, 60_000L)

        val result = V2BackupManager.mergeHistories(
            JSONArray().put(local),
            JSONArray().put(remoteSameId).put(remoteNew)
        )

        assertEquals(1, result.added)
        assertEquals(2, result.history.length())
        assertEquals(10_000L, result.history.getJSONObject(0).getLong("realEntry"))
        assertEquals("session-2", result.history.getJSONObject(1).getString("id"))
    }

    @Test
    fun `fusion historique refuse un meme identifiant avec un contenu different`() {
        val local = JSONArray().put(session("session-1", 10_000L, 20_000L))
        val conflicting = JSONArray().put(session("session-1", 30_000L, 40_000L))

        assertTrue(runCatching { V2BackupManager.mergeHistories(local, conflicting) }.isFailure)
    }

    @Test
    fun `fusion historique refuse toute source corrompue`() {
        val malformed = JSONArray().put(session("session-1", 20_000L, 10_000L))

        assertTrue(runCatching { V2BackupManager.mergeHistories(malformed, JSONArray()) }.isFailure)
        assertTrue(runCatching { V2BackupManager.mergeHistories(JSONArray(), malformed) }.isFailure)
    }

    private fun typed(type: String, value: Any) = JSONObject().put("t", type).put("v", value)

    private fun session(id: String, entry: Long, exit: Long) = JSONObject()
        .put("id", id)
        .put("employerId", "company-a")
        .put("realEntry", entry)
        .put("countedEntry", entry)
        .put("realExit", exit)
        .put("countedExit", exit)
        .put("pauses", JSONArray())
        .put("placeId", JSONObject.NULL)
        .put("placeLabel", JSONObject.NULL)
}
