package com.amaury.pointage.v2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoccPayrollSourceStoreV2Test {
    private val day = 1_800_000_000_000L

    private fun recordJson(
        companyId: Any = "company-a",
        referenceAtMs: Any = day,
        idcc: Any = "292",
        fileName: Any = "bocc-001.pdf",
        path: Any = "/bocc/bocc-001.pdf",
        checkedAtMs: Any = day + 1L
    ): JSONObject = JSONObject()
        .put("companyId", companyId)
        .put("referenceAtMs", referenceAtMs)
        .put("idcc", idcc)
        .put("title", "Avenant relatif aux salaires")
        .put("fileName", fileName)
        .put("pathToFile", path)
        .put("publicationDate", "2026-01-01")
        .put("textDate", "2025-12-15")
        .put("bulletinNumber", "2026/01")
        .put("checkedAtMs", checkedAtMs)

    private fun record(index: Int): BoccPayrollSourceStoreV2.Record = BoccPayrollSourceStoreV2.Record(
        companyId = "company-a",
        referenceAtMs = day,
        idcc = "292",
        title = "Avenant $index",
        fileName = "bocc-${index.toString().padStart(4, '0')}.pdf",
        pathToFile = "/bocc/bocc-${index.toString().padStart(4, '0')}.pdf",
        publicationDate = null,
        textDate = null,
        bulletinNumber = null,
        checkedAtMs = day + index + 1L
    )

    @Test
    fun `historique vide explicite est fiable`() {
        val result = BoccPayrollSourceStoreV2.decodeRecords("[]")
        assertTrue(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `json vide illisible ou mauvais type est non fiable`() {
        assertFalse(BoccPayrollSourceStoreV2.decodeRecords("   ").reliable)
        assertFalse(BoccPayrollSourceStoreV2.decodeRecords("not-json").reliable)
        assertFalse(BoccPayrollSourceStoreV2.decodeRecords("{}").reliable)
    }

    @Test
    fun `une entree invalide contamine tout le stockage et bloque le snapshot partiel`() {
        val raw = JSONArray()
            .put(recordJson())
            .put(JSONObject().put("companyId", "company-a"))
            .toString()
        val stored = BoccPayrollSourceStoreV2.decodeRecords(raw)
        val snapshot = BoccPayrollSourceStoreV2.snapshotFrom(stored, "company-a", day, "292")

        assertFalse(stored.reliable)
        assertEquals(1, stored.records.size)
        assertFalse(snapshot.reliable)
        assertTrue(snapshot.records.isEmpty())
    }

    @Test
    fun `doublon de fichier pour la meme entreprise idcc et jour rend la source ambigue`() {
        val raw = JSONArray()
            .put(recordJson(checkedAtMs = day + 1L))
            .put(recordJson(checkedAtMs = day + 2L))
            .toString()

        val stored = BoccPayrollSourceStoreV2.decodeRecords(raw)
        assertFalse(stored.reliable)
        assertEquals(2, stored.records.size)
    }

    @Test
    fun `types invalides pour identifiants horodatages et champs optionnels sont refuses`() {
        val badCompany = JSONArray().put(recordJson(companyId = 42)).toString()
        val badIdcc = JSONArray().put(recordJson(idcc = "292A")).toString()
        val stringTimestamp = JSONArray().put(recordJson(referenceAtMs = "1800000000000")).toString()
        val badOptional = JSONArray().put(recordJson().put("bulletinNumber", 202601)).toString()

        assertFalse(BoccPayrollSourceStoreV2.decodeRecords(badCompany).reliable)
        assertFalse(BoccPayrollSourceStoreV2.decodeRecords(badIdcc).reliable)
        assertFalse(BoccPayrollSourceStoreV2.decodeRecords(stringTimestamp).reliable)
        assertFalse(BoccPayrollSourceStoreV2.decodeRecords(badOptional).reliable)
    }

    @Test
    fun `pdf et chemin obligatoires sont controles`() {
        val badFile = JSONArray().put(recordJson(fileName = "bocc-001.txt")).toString()
        val badPath = JSONArray().put(recordJson(path = " ")).toString()

        assertFalse(BoccPayrollSourceStoreV2.decodeRecords(badFile).reliable)
        assertFalse(BoccPayrollSourceStoreV2.decodeRecords(badPath).reliable)
    }

    @Test
    fun `ancien IDCC avec zeros initiaux reste lisible et est normalise`() {
        val stored = BoccPayrollSourceStoreV2.decodeRecords(
            JSONArray().put(recordJson(idcc = "0292")).toString()
        )

        assertTrue(stored.reliable)
        assertEquals("292", stored.records.single().idcc)
    }

    @Test
    fun `snapshot fiable filtre exactement entreprise jour et IDCC`() {
        val raw = JSONArray()
            .put(recordJson(fileName = "wanted.pdf", path = "/bocc/wanted.pdf"))
            .put(recordJson(companyId = "company-b", fileName = "other-company.pdf", path = "/bocc/other-company.pdf"))
            .put(recordJson(idcc = "1486", fileName = "other-idcc.pdf", path = "/bocc/other-idcc.pdf"))
            .put(recordJson(referenceAtMs = day + 86_400_000L, fileName = "other-day.pdf", path = "/bocc/other-day.pdf"))
            .toString()
        val stored = BoccPayrollSourceStoreV2.decodeRecords(raw)
        val snapshot = BoccPayrollSourceStoreV2.snapshotFrom(stored, "company-a", day, "0292")

        assertTrue(stored.reliable)
        assertTrue(snapshot.reliable)
        assertEquals(listOf("wanted.pdf"), snapshot.records.map { it.fileName })
    }

    @Test
    fun `parametres de snapshot invalides ne deviennent pas absence fiable de BOCC`() {
        val stored = BoccPayrollSourceStoreV2.decodeRecords(JSONArray().put(recordJson()).toString())
        val badCompany = BoccPayrollSourceStoreV2.snapshotFrom(stored, "", day, "292")
        val badDate = BoccPayrollSourceStoreV2.snapshotFrom(stored, "company-a", 0L, "292")
        val badIdcc = BoccPayrollSourceStoreV2.snapshotFrom(stored, "company-a", day, "29A2")

        assertFalse(badCompany.reliable)
        assertFalse(badDate.reliable)
        assertFalse(badIdcc.reliable)
    }

    @Test
    fun `limite du journal est refusee au lieu de tronquer silencieusement`() {
        val maximum = (0 until BoccPayrollSourceStoreV2.MAX_RECORDS).map(::record)
        val overflow = maximum + record(BoccPayrollSourceStoreV2.MAX_RECORDS)

        assertTrue(BoccPayrollSourceStoreV2.acceptsRecordSet(maximum))
        assertFalse(BoccPayrollSourceStoreV2.acceptsRecordSet(overflow))
    }
}
