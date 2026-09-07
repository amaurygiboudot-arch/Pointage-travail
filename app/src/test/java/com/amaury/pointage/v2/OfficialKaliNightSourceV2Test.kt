package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialKaliNightSourceV2Test {
    @Test
    fun `la recherche KALI borne l IDCC et exige nuit plus majoration`() {
        val body = OfficialKaliNightSourceV2.searchBody("0292", pageNumber = 3, pageSize = 25)
        assertEquals("KALI", body["fond"])
        val recherche = body["recherche"] as Map<*, *>
        assertEquals(3, recherche["pageNumber"])
        assertEquals(25, recherche["pageSize"])
        assertEquals("DEFAUT", recherche["typePagination"])

        val champs = recherche["champs"] as List<*>
        val idcc = champs[0] as Map<*, *>
        val idccCritere = (idcc["criteres"] as List<*>).first() as Map<*, *>
        assertEquals("292", idccCritere["valeur"])

        val article = champs[1] as Map<*, *>
        assertEquals("ARTICLE", article["typeChamp"])
        val articleCritere = (article["criteres"] as List<*>).first() as Map<*, *>
        assertEquals("nuit majoration", articleCritere["valeur"])
        assertEquals("TOUS_LES_MOTS_DANS_UN_CHAMP", articleCritere["typeRecherche"])
    }

    @Test
    fun `le parseur conserve seulement les identifiants KALI sans promouvoir une regle`() {
        val data = mapOf(
            "totalResultNumber" to 2,
            "results" to listOf(
                mapOf(
                    "title" to "Travail de nuit",
                    "id" to "KALIARTI000012345678",
                    "content" to "Majoration de nuit"
                ),
                mapOf(
                    "title" to "Avenant nuit",
                    "id" to "KALITEXT000087654321"
                )
            )
        )

        val page = OfficialKaliNightSourceV2.parsePage(data, 1, 25)
        assertEquals(2, page.candidates.size)
        assertEquals(2, page.totalResults)
        assertTrue(page.lastPageConfirmed)
        assertEquals("KALIARTI000012345678", page.candidates.first().id)
    }

    @Test
    fun `un total absent ne devient jamais une preuve d exhaustivite`() {
        val page = OfficialKaliNightSourceV2.parsePage(
            mapOf("results" to emptyList<Any>()),
            1,
            25
        )
        assertNull(page.totalResults)
        assertFalse(page.lastPageConfirmed)
    }
}
