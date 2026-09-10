package com.amaury.pointage.v2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MayFirstLegalRuleStoreV2Test {
    private val day = 1_780_000_000_000L

    private fun snapshot(
        referenceAtMs: Long = day,
        checkedAtMs: Long = day + 1L
    ) = MayFirstLegalRuleStoreV2.Snapshot(
        articleId = "LEGIARTI000000000001",
        articleNumber = "L3133-6",
        effectiveFromMs = day - 365L * 86_400_000L,
        effectiveToMs = null,
        referenceAtMs = referenceAtMs,
        checkedAtMs = checkedAtMs,
        extraMultiplier = 1.0
    )

    private fun json(value: MayFirstLegalRuleStoreV2.Snapshot): JSONObject = JSONObject()
        .put("articleId", value.articleId)
        .put("articleNumber", value.articleNumber)
        .put("effectiveFromMs", value.effectiveFromMs)
        .put("effectiveToMs", value.effectiveToMs ?: JSONObject.NULL)
        .put("referenceAtMs", value.referenceAtMs)
        .put("checkedAtMs", value.checkedAtMs)
        .put("extraMultiplier", value.extraMultiplier)

    @Test
    fun `historique vide explicite est fiable`() {
        val result = MayFirstLegalRuleStoreV2.decodeSnapshots("[]")

        assertTrue(result.reliable)
        assertTrue(result.snapshots.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `json illisible ou vide nest pas assimile a aucune regle`() {
        val malformed = MayFirstLegalRuleStoreV2.decodeSnapshots("not-json")
        val blank = MayFirstLegalRuleStoreV2.decodeSnapshots("   ")

        assertFalse(malformed.reliable)
        assertFalse(blank.reliable)
        assertTrue(malformed.snapshots.isEmpty())
        assertTrue(blank.snapshots.isEmpty())
    }

    @Test
    fun `une entree corrompue bloque aussi une entree valide du meme stockage`() {
        val raw = JSONArray()
            .put(json(snapshot()))
            .put(JSONObject())
            .toString()

        val stored = MayFirstLegalRuleStoreV2.decodeSnapshots(raw)
        val resolution = MayFirstLegalRuleStoreV2.resolveFrom(stored, day)

        assertFalse(stored.reliable)
        assertEquals(1, stored.snapshots.size)
        assertFalse(resolution.reliable)
        assertNull(resolution.snapshot)
        assertTrue(resolution.warnings.isNotEmpty())
    }

    @Test
    fun `fin de vigueur absente du json rend le snapshot non fiable`() {
        val invalid = json(snapshot()).apply { remove("effectiveToMs") }
        val result = MayFirstLegalRuleStoreV2.decodeSnapshots(JSONArray().put(invalid).toString())

        assertFalse(result.reliable)
        assertTrue(result.snapshots.isEmpty())
    }

    @Test
    fun `fin de vigueur anterieure au debut est refusee`() {
        val invalid = json(snapshot()).apply {
            put("effectiveToMs", day - 500L * 86_400_000L)
        }
        val result = MayFirstLegalRuleStoreV2.decodeSnapshots(JSONArray().put(invalid).toString())

        assertFalse(result.reliable)
        assertTrue(result.snapshots.isEmpty())
    }

    @Test
    fun `deux preuves concurrentes pour le meme jour rendent lhistorique ambigu`() {
        val first = snapshot(checkedAtMs = day + 1L)
        val second = snapshot(checkedAtMs = day + 2L)
        val raw = JSONArray().put(json(first)).put(json(second)).toString()

        val result = MayFirstLegalRuleStoreV2.decodeSnapshots(raw)

        assertFalse(result.reliable)
        assertEquals(2, result.snapshots.size)
    }

    @Test
    fun `limite 24 est refusee au lieu de supprimer silencieusement une ancienne preuve`() {
        val maximum = (0 until MayFirstLegalRuleStoreV2.MAX_RECORDS).map { index ->
            snapshot(
                referenceAtMs = day + index * 86_400_000L,
                checkedAtMs = day + index + 1L
            )
        }
        val overflow = maximum + snapshot(
            referenceAtMs = day + MayFirstLegalRuleStoreV2.MAX_RECORDS * 86_400_000L,
            checkedAtMs = day + 999L
        )

        assertTrue(MayFirstLegalRuleStoreV2.acceptsPackage(maximum))
        assertFalse(MayFirstLegalRuleStoreV2.acceptsPackage(overflow))
    }

    @Test
    fun `preuve fiable applicable reste utilisable`() {
        val stored = MayFirstLegalRuleStoreV2.ReadResult(
            snapshots = listOf(snapshot()),
            reliable = true,
            warnings = emptyList()
        )

        val resolution = MayFirstLegalRuleStoreV2.resolveFrom(stored, day)

        assertTrue(resolution.reliable)
        assertNotNull(resolution.snapshot)
        assertEquals(1.0, resolution.snapshot?.extraMultiplier ?: 0.0, 0.0)
    }
}
