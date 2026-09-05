package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialLegalCodeSourceV2Test {

    @Test
    fun `construit une recherche LEGI datee limitee au code du travail`() {
        val body = OfficialLegalCodeSourceV2.searchBody(
            OfficialLegalCodeSourceV2.Topic.OVERTIME,
            atMs = 1788566400000,
            pageSize = 100
        )

        assertEquals("CODE_DATE", body["fond"])
        val search = body["recherche"] as Map<*, *>
        assertEquals(25, search["pageSize"])
        assertEquals("ARTICLE", search["typePagination"])

        val fields = search["champs"] as List<*>
        val firstField = fields.first() as Map<*, *>
        val criteria = firstField["criteres"] as List<*>
        val firstCriterion = criteria.first() as Map<*, *>
        assertEquals("heures supplémentaires", firstCriterion["valeur"])

        val filters = search["filtres"] as List<*>
        assertTrue(filters.any { (it as? Map<*, *>)?.get("facette") == "NOM_CODE" })
        assertTrue(filters.any { (it as? Map<*, *>)?.get("facette") == "DATE_VERSION" })
    }

    @Test
    fun `extrait uniquement les candidats article LEGI`() {
        val response = mapOf(
            "results" to listOf(
                mapOf(
                    "titles" to listOf(mapOf("id" to "LEGITEXT000000000001", "title" to "Code du travail")),
                    "article" to mapOf(
                        "id" to "LEGIARTI000012345678",
                        "numArticle" to "L3121-28",
                        "content" to "<p>Les heures supplémentaires ouvrent droit...</p>"
                    )
                ),
                mapOf("id" to "JORFTEXT000012345678", "title" to "Texte JORF")
            )
        )

        val candidates = OfficialLegalCodeSourceV2.parseCandidates(response)

        assertEquals(1, candidates.size)
        assertEquals("LEGIARTI000012345678", candidates.single().articleId)
        assertEquals("L3121-28", candidates.single().articleNumber)
        assertTrue(candidates.single().snippet!!.startsWith("Les heures supplémentaires"))
    }

    @Test
    fun `parse un article officiel sans en deduire de regle`() {
        val response = mapOf(
            "executionTime" to 12,
            "article" to mapOf(
                "id" to "LEGIARTI000012345678",
                "num" to "L3121-28",
                "etat" to "VIGUEUR",
                "dateDebut" to 1711929600000,
                "dateFin" to 32472144000000,
                "content" to "<p>Texte juridique vérifié.</p>"
            )
        )

        val article = OfficialLegalCodeSourceV2.parseArticle(response)

        assertNotNull(article)
        assertEquals("LEGIARTI000012345678", article!!.articleId)
        assertEquals("L3121-28", article.articleNumber)
        assertEquals("VIGUEUR", article.status)
        assertEquals("Texte juridique vérifié.", article.content)
        assertEquals("1711929600000", article.effectiveFrom)
    }
}
