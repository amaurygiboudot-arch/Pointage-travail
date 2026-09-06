package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialKaliTextExpansionV2Test {
    @Test
    fun `extrait les articles imbriques d une section heures supplementaires`() {
        val data = mapOf(
            "id" to "KALITEXT000027918941",
            "sections" to listOf(
                mapOf(
                    "id" to "KALISCTA000012345678",
                    "titre" to "Heures supplémentaires",
                    "articles" to listOf(
                        mapOf("id" to "KALIARTI000005856335"),
                        mapOf("cid" to "kaliarti000099999999")
                    )
                )
            )
        )

        val expansion = OfficialKaliTextExpansionV2.parse(data)
        assertEquals(listOf("KALIARTI000005856335", "KALIARTI000099999999"), expansion.articleIds)
        assertTrue(expansion.textIds.contains("KALITEXT000027918941"))
        assertTrue(expansion.sectionIds.contains("KALISCTA000012345678"))
    }

    @Test
    fun `garde un article pertinent par son propre contenu mais ignore les autres`() {
        val data = mapOf(
            "id" to "KALITEXT000000000001",
            "articles" to listOf(
                mapOf(
                    "id" to "KALIARTI000000000001",
                    "content" to "Les heures supplémentaires sont majorées de 25 %."
                ),
                mapOf(
                    "id" to "KALIARTI000000000002",
                    "content" to "Les congés pour événements familiaux sont définis ci-dessous."
                )
            )
        )

        val expansion = OfficialKaliTextExpansionV2.parse(data)
        assertEquals(listOf("KALIARTI000000000001"), expansion.articleIds)
    }

    @Test
    fun `ignore les identifiants hors fonds KALI`() {
        val expansion = OfficialKaliTextExpansionV2.parse(
            mapOf("id" to "LEGIARTI000033020341", "content" to "JORFTEXT000000000001")
        )

        assertTrue(expansion.articleIds.isEmpty())
        assertTrue(expansion.textIds.isEmpty())
        assertTrue(expansion.sectionIds.isEmpty())
    }
}
