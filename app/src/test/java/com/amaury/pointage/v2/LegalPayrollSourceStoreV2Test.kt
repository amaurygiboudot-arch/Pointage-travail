package com.amaury.pointage.v2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalPayrollSourceStoreV2Test {
    private val day = 1_780_000_000_000L

    private fun record(
        articleId: String = "LEGIARTI000000000001",
        topic: OfficialLegalCodeSourceV2.Topic = OfficialLegalCodeSourceV2.Topic.OVERTIME,
        referenceAtMs: Long = day,
        checkedAtMs: Long = day + 1L
    ) = LegalPayrollSourceStoreV2.Record(
        topic = topic,
        articleId = articleId,
        articleNumber = "L3121-36",
        status = "VIGUEUR",
        excerpt = "Les heures supplémentaires donnent lieu à une majoration.",
        effectiveFromMs = day - 86_400_000L,
        effectiveToMs = null,
        referenceAtMs = referenceAtMs,
        checkedAtMs = checkedAtMs
    )

    private fun json(value: LegalPayrollSourceStoreV2.Record): JSONObject = JSONObject()
        .put("topic", value.topic.name)
        .put("articleId", value.articleId)
        .put("articleNumber", value.articleNumber ?: JSONObject.NULL)
        .put("status", value.status)
        .put("excerpt", value.excerpt)
        .put("effectiveFromMs", value.effectiveFromMs)
        .put("effectiveToMs", value.effectiveToMs ?: JSONObject.NULL)
        .put("referenceAtMs", value.referenceAtMs)
        .put("checkedAtMs", value.checkedAtMs)

    @Test
    fun `historique vide explicite est fiable`() {
        val result = LegalPayrollSourceStoreV2.decodeRecords("[]")

        assertTrue(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `json illisible ou vide ne devient pas historique vide fiable`() {
        val malformed = LegalPayrollSourceStoreV2.decodeRecords("not-json")
        val blank = LegalPayrollSourceStoreV2.decodeRecords("   ")

        assertFalse(malformed.reliable)
        assertFalse(blank.reliable)
        assertTrue(malformed.records.isEmpty())
        assertTrue(blank.records.isEmpty())
    }

    @Test
    fun `une entree invalide contamine tout le stockage et aucune entree partielle nest applicable`() {
        val raw = JSONArray()
            .put(json(record()))
            .put(JSONObject())
            .toString()

        val stored = LegalPayrollSourceStoreV2.decodeRecords(raw)
        val applicable = LegalPayrollSourceStoreV2.applicableFrom(stored, day)

        assertFalse(stored.reliable)
        assertTrue(stored.records.size == 1)
        assertTrue(applicable.isEmpty())
    }

    @Test
    fun `statut absent nest jamais invente comme VIGUEUR`() {
        val invalid = json(record()).apply { remove("status") }
        val result = LegalPayrollSourceStoreV2.decodeRecords(JSONArray().put(invalid).toString())

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun `deux variantes de la meme identite LEGI rendent lhistorique ambigu`() {
        val first = record(checkedAtMs = day + 1L)
        val second = record(checkedAtMs = day + 2L)
        val raw = JSONArray().put(json(first)).put(json(second)).toString()

        val result = LegalPayrollSourceStoreV2.decodeRecords(raw)

        assertFalse(result.reliable)
        assertTrue(result.records.size == 2)
    }

    @Test
    fun `fin de vigueur anterieure au debut rend lenregistrement invalide`() {
        val invalid = json(record()).apply {
            put("effectiveToMs", day - 2L * 86_400_000L)
        }
        val result = LegalPayrollSourceStoreV2.decodeRecords(JSONArray().put(invalid).toString())

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun `limite du journal est refusee au lieu de tronquer silencieusement`() {
        val maximum = (0 until LegalPayrollSourceStoreV2.MAX_RECORDS).map { index ->
            record(
                articleId = "LEGIARTI${index.toString().padStart(12, '0')}",
                checkedAtMs = day + index + 1L
            )
        }
        val overflow = maximum + record(
            articleId = "LEGIARTI999999999999",
            checkedAtMs = day + 999L
        )

        assertTrue(LegalPayrollSourceStoreV2.acceptsPackage(maximum))
        assertFalse(LegalPayrollSourceStoreV2.acceptsPackage(overflow))
    }
}
