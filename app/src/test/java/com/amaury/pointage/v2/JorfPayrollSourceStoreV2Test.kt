package com.amaury.pointage.v2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JorfPayrollSourceStoreV2Test {
    private val day = 1_800_000_000_000L

    private fun recordJson(
        textCid: String = "JORFTEXT000000000001",
        referenceAtMs: Any = day,
        title: Any = "Décret relatif à la paie",
        publicationDate: Any = "2026-01-01",
        containerId: Any = "JORFCONT000000000001",
        checkedAtMs: Any = day + 1L
    ): JSONObject = JSONObject()
        .put("referenceAtMs", referenceAtMs)
        .put("textCid", textCid)
        .put("title", title)
        .put("nature", "DECRET")
        .put("legalState", "VIGUEUR")
        .put("nor", "TEST0000001D")
        .put("publicationDate", publicationDate)
        .put("publicationNumber", "1")
        .put("containerId", containerId)
        .put("checkedAtMs", checkedAtMs)

    private fun record(index: Int): JorfPayrollSourceStoreV2.Record = JorfPayrollSourceStoreV2.Record(
        referenceAtMs = day + index * 86_400_000L,
        textCid = "JORFTEXT${index.toString().padStart(12, '0')}",
        title = "Texte $index",
        nature = null,
        legalState = null,
        nor = null,
        publicationDate = "2026-01-01",
        publicationNumber = null,
        containerId = "JORFCONT${index.toString().padStart(12, '0')}",
        checkedAtMs = day + index + 1L
    )

    @Test
    fun `historique vide explicite est fiable`() {
        val result = JorfPayrollSourceStoreV2.decodeRecords("[]")
        assertTrue(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `json vide illisible ou mauvais type est non fiable`() {
        assertFalse(JorfPayrollSourceStoreV2.decodeRecords("   ").reliable)
        assertFalse(JorfPayrollSourceStoreV2.decodeRecords("not-json").reliable)
        assertFalse(JorfPayrollSourceStoreV2.decodeRecords("{}").reliable)
    }

    @Test
    fun `une entree invalide contamine tout le stockage et bloque le snapshot partiel`() {
        val raw = JSONArray()
            .put(recordJson())
            .put(JSONObject().put("textCid", "JORFTEXT000000000002"))
            .toString()
        val stored = JorfPayrollSourceStoreV2.decodeRecords(raw)
        val snapshot = JorfPayrollSourceStoreV2.snapshotFrom(stored, day)

        assertFalse(stored.reliable)
        assertEquals(1, stored.records.size)
        assertFalse(snapshot.reliable)
        assertTrue(snapshot.records.isEmpty())
    }

    @Test
    fun `doublon de texte pour le meme jour rend la source ambigue`() {
        val raw = JSONArray()
            .put(recordJson(checkedAtMs = day + 1L))
            .put(recordJson(checkedAtMs = day + 2L))
            .toString()

        val stored = JorfPayrollSourceStoreV2.decodeRecords(raw)
        assertFalse(stored.reliable)
        assertEquals(2, stored.records.size)
    }

    @Test
    fun `identifiants officiels et date de publication invalides sont refuses`() {
        val badText = JSONArray().put(recordJson(textCid = "KALITEXT0001")).toString()
        val badContainer = JSONArray().put(recordJson(containerId = "JORF0001")).toString()
        val badDate = JSONArray().put(recordJson(publicationDate = "pas-une-date")).toString()

        assertFalse(JorfPayrollSourceStoreV2.decodeRecords(badText).reliable)
        assertFalse(JorfPayrollSourceStoreV2.decodeRecords(badContainer).reliable)
        assertFalse(JorfPayrollSourceStoreV2.decodeRecords(badDate).reliable)
    }

    @Test
    fun `types texte pour horodatages ou champs optionnels sont refuses`() {
        val stringTimestamp = JSONArray().put(recordJson(referenceAtMs = "1800000000000")).toString()
        val wrongOptional = JSONArray().put(recordJson().put("nor", 12345)).toString()

        assertFalse(JorfPayrollSourceStoreV2.decodeRecords(stringTimestamp).reliable)
        assertFalse(JorfPayrollSourceStoreV2.decodeRecords(wrongOptional).reliable)
    }

    @Test
    fun `snapshot fiable filtre seulement le jour demande`() {
        val first = JorfPayrollSourceStoreV2.decodeRecords(JSONArray()
            .put(recordJson(textCid = "JORFTEXT000000000001", referenceAtMs = day))
            .put(recordJson(textCid = "JORFTEXT000000000002", referenceAtMs = day + 86_400_000L))
            .toString())
        val snapshot = JorfPayrollSourceStoreV2.snapshotFrom(first, day)

        assertTrue(first.reliable)
        assertTrue(snapshot.reliable)
        assertEquals(listOf("JORFTEXT000000000001"), snapshot.records.map { it.textCid })
    }

    @Test
    fun `limite du journal est refusee au lieu de tronquer silencieusement`() {
        val maximum = (0 until JorfPayrollSourceStoreV2.MAX_RECORDS).map(::record)
        val overflow = maximum + record(JorfPayrollSourceStoreV2.MAX_RECORDS)

        assertTrue(JorfPayrollSourceStoreV2.acceptsRecordSet(maximum))
        assertFalse(JorfPayrollSourceStoreV2.acceptsRecordSet(overflow))
    }
}
