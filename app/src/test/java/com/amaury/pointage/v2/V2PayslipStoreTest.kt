package com.amaury.pointage.v2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class V2PayslipStoreTest {
    private fun recordJson(
        id: String = "payslip-1",
        year: Int = 2026,
        month: Int = 0,
        gross: Double? = 2_000.0,
        net: Double? = 1_600.0,
        confidence: Double = 1.0,
        confirmed: Boolean = true,
        importedAt: Long = 100L,
        companyId: String = "company-a"
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("year", year)
        .put("month", month)
        .put("uri", "content://payslip/$id")
        .put("mime", "application/pdf")
        .put("gross", gross ?: JSONObject.NULL)
        .put("net", net ?: JSONObject.NULL)
        .put("confidence", confidence)
        .put("confirmed", confirmed)
        .put("importedAt", importedAt)
        .put("companyId", companyId)

    @Test
    fun `historique vide explicite est fiable`() {
        val result = V2PayslipStore.decodeRecords("[]")

        assertTrue(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `json vide illisible ou mauvais type ne devient pas historique vide fiable`() {
        val blank = V2PayslipStore.decodeRecords("   ")
        val malformed = V2PayslipStore.decodeRecords("not-json")
        val wrongRoot = V2PayslipStore.decodeRecords("{}")

        assertFalse(blank.reliable)
        assertFalse(malformed.reliable)
        assertFalse(wrongRoot.reliable)
        assertTrue(blank.records.isEmpty())
        assertTrue(malformed.records.isEmpty())
        assertTrue(wrongRoot.records.isEmpty())
    }

    @Test
    fun `une entree invalide contamine tout le stockage et bloque la base IJSS`() {
        val raw = JSONArray()
            .put(recordJson())
            .put(JSONObject().put("year", 2026).put("month", 0))
            .toString()

        val stored = V2PayslipStore.decodeRecords(raw)
        val grossByMonth = V2PayslipStore.confirmedGrossByMonth(stored, "company-a")

        assertFalse(stored.reliable)
        assertEquals(1, stored.records.size)
        assertNull(grossByMonth)
    }

    @Test
    fun `deux bulletins avec le meme identifiant rendent le stockage ambigu`() {
        val raw = JSONArray()
            .put(recordJson(id = "same", importedAt = 100L))
            .put(recordJson(id = "same", importedAt = 200L))
            .toString()

        val stored = V2PayslipStore.decodeRecords(raw)

        assertFalse(stored.reliable)
        assertEquals(2, stored.records.size)
        assertNull(V2PayslipStore.confirmedGrossByMonth(stored, "company-a"))
    }

    @Test
    fun `montant de mauvais type ou negatif rend le stockage non fiable`() {
        val stringGross = recordJson().put("gross", "2000,00")
        val negativeNet = recordJson(id = "negative").put("net", -1.0)

        assertFalse(V2PayslipStore.decodeRecords(JSONArray().put(stringGross).toString()).reliable)
        assertFalse(V2PayslipStore.decodeRecords(JSONArray().put(negativeNet).toString()).reliable)
    }

    @Test
    fun `confiance hors bornes nest pas silencieusement corrigee`() {
        val invalid = recordJson().put("confidence", 1.5)

        val stored = V2PayslipStore.decodeRecords(JSONArray().put(invalid).toString())

        assertFalse(stored.reliable)
        assertTrue(stored.records.isEmpty())
    }

    @Test
    fun `bulletin confirme sans brut ni net est invalide`() {
        val invalid = recordJson(gross = null, net = null, confirmed = true)

        val stored = V2PayslipStore.decodeRecords(JSONArray().put(invalid).toString())

        assertFalse(stored.reliable)
        assertTrue(stored.records.isEmpty())
    }

    @Test
    fun `ancien bulletin sans champs optionnels reste lisible`() {
        val old = JSONObject()
            .put("id", "legacy")
            .put("year", 2025)
            .put("month", 11)
            .put("uri", "content://payslip/legacy")
            .put("gross", 1_900.0)
            .put("net", JSONObject.NULL)

        val stored = V2PayslipStore.decodeRecords(JSONArray().put(old).toString())

        assertTrue(stored.reliable)
        assertEquals(1, stored.records.size)
        assertFalse(stored.records.single().confirmedByUser)
        assertEquals("", stored.records.single().companyId)
        assertEquals(0L, stored.records.single().importedAtMs)
    }

    @Test
    fun `la base IJSS prend le dernier brut confirme du bon employeur seulement`() {
        val raw = JSONArray()
            .put(recordJson(id = "older", gross = 2_000.0, importedAt = 100L))
            .put(recordJson(id = "newer", gross = 2_100.0, importedAt = 200L))
            .put(recordJson(id = "other-company", gross = 9_999.0, importedAt = 300L, companyId = "company-b"))
            .put(recordJson(id = "unconfirmed", gross = 3_000.0, importedAt = 400L, confirmed = false))
            .toString()

        val stored = V2PayslipStore.decodeRecords(raw)
        val grossByMonth = V2PayslipStore.confirmedGrossByMonth(stored, "company-a")

        assertTrue(stored.reliable)
        assertEquals(1, grossByMonth?.size)
        assertEquals(2_100.0, grossByMonth?.get(YearMonth.of(2026, 1)) ?: -1.0, 0.001)
    }
}
