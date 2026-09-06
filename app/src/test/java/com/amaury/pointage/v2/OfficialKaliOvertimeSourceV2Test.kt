package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialKaliOvertimeSourceV2Test {
    @Test
    fun `la recherche KALI borne exactement l IDCC et les heures supplementaires`() {
        val body = OfficialKaliOvertimeSourceV2.searchBody("0292", pageNumber = 2, pageSize = 25)
        assertEquals("KALI", body["fond"])
        val recherche = body["recherche"] as Map<*, *>
        assertEquals(2, recherche["pageNumber"])
        assertEquals(25, recherche["pageSize"])
        assertEquals("ARTICLE", recherche["typePagination"])

        val champs = recherche["champs"] as List<*>
        val idcc = champs[0] as Map<*, *>
        assertEquals("IDCC", idcc["typeChamp"])
        val idccCritere = (idcc["criteres"] as List<*>).first() as Map<*, *>
        assertEquals("292", idccCritere["valeur"])

        val article = champs[1] as Map<*, *>
        assertEquals("ARTICLE", article["typeChamp"])
        val articleCritere = (article["criteres"] as List<*>).first() as Map<*, *>
        assertTrue(articleCritere["valeur"].toString().contains("heures supplémentaires"))
    }

    @Test
    fun `le parseur recupere les identifiants KALI sans promouvoir une regle`() {
        val data = mapOf(
            "totalResultNumber" to 2,
            "results" to listOf(
                mapOf(
                    "title" to "Convention Plasturgie",
                    "sections" to listOf(
                        mapOf(
                            "extracts" to listOf(
                                mapOf(
                                    "id" to "KALIARTI000012345678",
                                    "values" to listOf("Les heures supplémentaires donnent lieu à une majoration.")
                                )
                            )
                        )
                    )
                ),
                mapOf(
                    "title" to "Avenant",
                    "id" to "KALITEXT000087654321",
                    "content" to "Majoration des heures supplémentaires"
                )
            )
        )

        val page = OfficialKaliOvertimeSourceV2.parsePage(data, requestedPage = 1, requestedPageSize = 25)
        assertEquals(2, page.candidates.size)
        assertEquals(2, page.totalResults)
        assertTrue(page.lastPageConfirmed)
        assertEquals("KALIARTI000012345678", page.candidates.first().id)
        assertTrue(page.candidates.first().snippet.orEmpty().contains("majoration"))
    }

    @Test
    fun `un total absent ne permet jamais de declarer la recherche exhaustive`() {
        val page = OfficialKaliOvertimeSourceV2.parsePage(
            data = mapOf("results" to emptyList<Any>()),
            requestedPage = 1,
            requestedPageSize = 25
        )

        assertNull(page.totalResults)
        assertFalse(page.lastPageConfirmed)
    }

    @Test
    fun `les identifiants hors KALI sont ignores`() {
        val page = OfficialKaliOvertimeSourceV2.parsePage(
            data = mapOf(
                "totalResults" to 1,
                "results" to listOf(
                    mapOf("id" to "LEGIARTI000033020341", "content" to "25 %")
                )
            ),
            requestedPage = 1,
            requestedPageSize = 25
        )

        assertTrue(page.candidates.isEmpty())
    }
}
