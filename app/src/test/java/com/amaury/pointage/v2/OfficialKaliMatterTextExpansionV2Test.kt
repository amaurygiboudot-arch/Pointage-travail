package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialKaliMatterTextExpansionV2Test {
    @Test
    fun `collecte tous les articles sans filtre metier et conserve leur KALITEXT parent`() {
        val data = mapOf(
            "id" to "KALITEXT000000000001",
            "sections" to listOf(
                mapOf(
                    "id" to "KALISCTA000000000001",
                    "titre" to "Prévoyance",
                    "articles" to listOf(
                        mapOf("id" to "KALIARTI000000000001", "content" to "Catégories objectives ANI"),
                        mapOf("id" to "KALIARTI000000000002", "content" to "Garantie décès")
                    )
                ),
                mapOf(
                    "id" to "KALISCTA000000000002",
                    "titre" to "Congés",
                    "articles" to listOf(
                        mapOf("id" to "KALIARTI000000000003", "content" to "Congés familiaux")
                    )
                )
            )
        )

        val result = OfficialKaliMatterTextExpansionV2.parse(data, "KALITEXT000000000001")

        assertTrue(result.reliable)
        assertEquals(
            listOf("KALIARTI000000000001", "KALIARTI000000000002", "KALIARTI000000000003"),
            result.articleIds
        )
        result.articleIds.forEach {
            assertEquals("KALITEXT000000000001", result.articleTextIds[it])
        }
    }

    @Test
    fun `un KALITEXT imbrique devient le parent exact de ses propres articles`() {
        val data = mapOf(
            "id" to "KALITEXT000000000001",
            "children" to listOf(
                mapOf(
                    "id" to "KALITEXT000000000002",
                    "articles" to listOf(mapOf("id" to "KALIARTI000000000002"))
                )
            ),
            "articles" to listOf(mapOf("id" to "KALIARTI000000000001"))
        )

        val result = OfficialKaliMatterTextExpansionV2.parse(data, "KALITEXT000000000001")

        assertTrue(result.reliable)
        assertEquals("KALITEXT000000000002", result.articleTextIds["KALIARTI000000000002"])
        assertEquals("KALITEXT000000000001", result.articleTextIds["KALIARTI000000000001"])
    }

    @Test
    fun `un article vu sous plusieurs KALITEXT dans une meme reponse est bloque`() {
        val articleId = "KALIARTI000000000001"
        val data = mapOf(
            "id" to "KALITEXT000000000001",
            "children" to listOf(
                mapOf(
                    "id" to "KALITEXT000000000002",
                    "articles" to listOf(mapOf("id" to articleId))
                )
            ),
            "articles" to listOf(mapOf("id" to articleId))
        )

        val result = OfficialKaliMatterTextExpansionV2.parse(data, "KALITEXT000000000001")

        assertFalse(result.reliable)
        assertTrue(articleId in result.articleIds)
        assertFalse(result.articleTextIds.containsKey(articleId))
        assertTrue(result.warnings.any { it.contains("plusieurs KALITEXT") })
    }

    @Test
    fun `une profondeur depassee ne peut pas certifier une expansion exhaustive`() {
        var nested: Any = mapOf("id" to "KALIARTI000000000099")
        repeat(22) {
            nested = mapOf("child" to nested)
        }
        val data = mapOf(
            "id" to "KALITEXT000000000001",
            "child" to nested
        )

        val result = OfficialKaliMatterTextExpansionV2.parse(data, "KALITEXT000000000001")

        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("profondeur maximale") })
    }

    @Test
    fun `réponse portant sur un autre KALITEXT reste fail closed`() {
        val data = mapOf(
            "id" to "KALITEXT000000000002",
            "articles" to listOf(mapOf("id" to "KALIARTI000000000001"))
        )

        val result = OfficialKaliMatterTextExpansionV2.parse(data, "KALITEXT000000000001")

        assertFalse(result.reliable)
        assertTrue(result.articleIds.isEmpty())
    }

    @Test
    fun `identifiant demandé invalide reste fail closed`() {
        val result = OfficialKaliMatterTextExpansionV2.parse(emptyMap<String, Any>(), "KALIARTI000000000001")

        assertFalse(result.reliable)
        assertTrue(result.articleIds.isEmpty())
    }
}
