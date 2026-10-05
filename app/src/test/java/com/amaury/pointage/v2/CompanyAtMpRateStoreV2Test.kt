package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.EmployerAtMpRateHistoryV2
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CompanyAtMpRateStoreV2Test {
    private val siret = "12345678901234"
    private val record = EmployerAtMpRateHistoryV2.Record("v1", siret, 0.0208, LocalDate.of(2026, 1, 1), null, "Notification Carsat 2026", 1L)
    private fun encoded() = CompanyAtMpRateStoreV2.encodeRecords(listOf(record))

    @Test
    fun `encodage preserve etablissement taux dates provenance confirmation`() {
        val read = CompanyAtMpRateStoreV2.decodeRecords(encoded())
        assertTrue(read.reliable)
        assertEquals(listOf(record), read.records)
        val resolved = CompanyAtMpRateStoreV2.resolve(read, siret, YearMonth.of(2026, 1))
        assertTrue(resolved.reliable)
        assertEquals(0.0208, resolved.rate!!, 0.0)
    }

    @Test
    fun `json malforme ne retient jamais le sous ensemble lisible`() {
        val envelope = JSONObject(encoded())
        envelope.getJSONArray("records").put(JSONObject().put("id", "broken"))
        val read = CompanyAtMpRateStoreV2.decodeRecords(envelope.toString())
        assertFalse(read.reliable)
        assertTrue(read.records.isEmpty())
        assertNull(CompanyAtMpRateStoreV2.resolve(read, siret, YearMonth.of(2026, 1)).rate)
        assertNull(CompanyAtMpRateStoreV2.recordsAfterSave(read, record, siret))
    }

    @Test
    fun `schema inconnu type taux texte et confirmation fractionnaire bloquent`() {
        val unknown = JSONObject(encoded()).put("schemaVersion", 2).toString()
        val wrongRate = mutated("rate", "0.0208")
        val fractional = mutated("confirmedAtMs", 1.5)
        listOf(unknown, wrongRate, fractional, "invalid", JSONArray().toString())
            .forEach { assertFalse(CompanyAtMpRateStoreV2.decodeRecords(it).reliable) }
    }

    @Test
    fun `preuve confirmation periode et siret invalides bloquent meme taux lisible`() {
        listOf(mutated("source", ""), mutated("confirmedAtMs", 0), mutated("establishmentSiret", "123"),
            mutated("effectiveFrom", "2026-02-30"), mutated("effectiveTo", "2025-12-31"))
            .forEach { assertFalse(CompanyAtMpRateStoreV2.decodeRecords(it).reliable) }
    }

    @Test
    fun `ids dupliques bloquent et absence de taux historique ne devient pas zero`() {
        assertFalse(CompanyAtMpRateStoreV2.decodeRecords(CompanyAtMpRateStoreV2.encodeRecords(listOf(record, record))).reliable)
        val empty = CompanyAtMpRateStoreV2.ReadResult(emptyList(), true, emptyList())
        assertNull(CompanyAtMpRateStoreV2.resolve(empty, siret, YearMonth.of(2026, 1)).rate)
    }

    @Test
    fun `mise a jour conserve les autres versions et refuse stockage non fiable`() {
        val prior = record.copy(id = "prior", effectiveFrom = LocalDate.of(2025, 1, 1), effectiveTo = LocalDate.of(2025, 12, 31))
        val read = CompanyAtMpRateStoreV2.ReadResult(listOf(prior, record), true, emptyList())
        val changed = record.copy(source = "Notification rectificative", rate = 0.03)
        assertEquals(listOf(prior, changed), CompanyAtMpRateStoreV2.recordsAfterSave(read, changed, siret))
        assertNull(CompanyAtMpRateStoreV2.recordsAfterSave(read.copy(reliable = false), changed, siret))
        assertNull(CompanyAtMpRateStoreV2.recordsAfterSave(read, changed.copy(confirmedAtMs = 0), siret))
    }

    @Test
    fun `confirmation refuse le siret autre que celui de entreprise selectionnee`() {
        val read = CompanyAtMpRateStoreV2.ReadResult(emptyList(), true, emptyList())
        assertNull(CompanyAtMpRateStoreV2.recordsAfterSave(read, record, "99999999999999"))
        assertNull(CompanyAtMpRateStoreV2.recordsAfterSave(read, record, ""))
        assertEquals(listOf(record), CompanyAtMpRateStoreV2.recordsAfterSave(read, record, siret))
    }

    private fun mutated(key: String, value: Any): String {
        val envelope = JSONObject(encoded())
        envelope.getJSONArray("records").getJSONObject(0).put(key, value)
        return envelope.toString()
    }
}
