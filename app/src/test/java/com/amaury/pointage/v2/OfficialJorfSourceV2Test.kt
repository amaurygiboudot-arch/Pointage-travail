package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialJorfSourceV2Test {
    @Test
    fun `borne le nombre de derniers journaux officiels`() {
        assertEquals(100, OfficialJorfSourceV2.lastJoBody(500)["nbElement"])
        assertEquals(1, OfficialJorfSourceV2.lastJoBody(0)["nbElement"])
    }

    @Test
    fun `parse les conteneurs retournes par lastNJo`() {
        val containers = OfficialJorfSourceV2.parseLastContainers(
            mapOf(
                "containers" to listOf(
                    mapOf(
                        "cid" to "JORFCONT000052345678",
                        "titre" to "JORF n°0200 du 5 septembre 2026",
                        "relevantDate" to "2026-09-05T00:00:00Z",
                        "num" to "0200"
                    )
                )
            )
        )

        assertEquals(1, containers.size)
        assertEquals("JORFCONT000052345678", containers.single().containerId)
        assertEquals("0200", containers.single().number)
    }

    @Test
    fun `parse les JORFTEXT dans la structure et les sous sections`() {
        val response = mapOf(
            "items" to listOf(
                mapOf(
                    "joCont" to mapOf(
                        "id" to "JORFCONT000052345678",
                        "datePubli" to "2026-09-05T00:00:00Z",
                        "structure" to mapOf(
                            "liens" to listOf(
                                mapOf(
                                    "id" to "JORFTEXT000052111111",
                                    "titre" to "Décret relatif au salaire minimum",
                                    "nature" to "DECRET"
                                )
                            ),
                            "tms" to listOf(
                                mapOf(
                                    "titre" to "Ministère du travail",
                                    "liensTxt" to listOf(
                                        mapOf(
                                            "id" to "JORFTEXT000052222222",
                                            "titre" to "Arrêté relatif au temps de travail",
                                            "nature" to "ARRETE",
                                            "ministere" to "Ministère du travail"
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        val candidates = OfficialJorfSourceV2.parseContainerCandidates(
            response,
            fallbackContainerId = "JORFCONT000052345678"
        )

        assertEquals(2, candidates.size)
        assertTrue(candidates.all(OfficialJorfSourceV2::isPayrollRelevant))
        assertEquals(setOf("JORFTEXT000052111111", "JORFTEXT000052222222"), candidates.map { it.textCid }.toSet())
    }

    @Test
    fun `ecarte un titre JORF sans rapport direct avec la paie`() {
        val candidate = OfficialJorfSourceV2.Candidate(
            textCid = "JORFTEXT000052333333",
            title = "Décret relatif au patrimoine culturel",
            nature = "DECRET",
            legalState = null,
            ministry = "Ministère de la culture",
            containerId = "JORFCONT000052345678",
            publicationDate = "2026-09-05T00:00:00Z"
        )
        assertFalse(OfficialJorfSourceV2.isPayrollRelevant(candidate))
    }

    @Test
    fun `ecarte les admissions individuelles a la retraite`() {
        val candidate = OfficialJorfSourceV2.Candidate(
            textCid = "JORFTEXT000052333334",
            title = "Décret portant admission à la retraite - M. Dupont",
            nature = "DECRET",
            legalState = null,
            ministry = null,
            containerId = "JORFCONT000052345678",
            publicationDate = "2026-09-05T00:00:00Z"
        )
        assertFalse(OfficialJorfSourceV2.isPayrollRelevant(candidate))
    }

    @Test
    fun `ecarte les prix pharmaceutiques malgre la securite sociale`() {
        val candidate = OfficialJorfSourceV2.Candidate(
            textCid = "JORFTEXT000052333335",
            title = "Avis relatif aux prix de spécialités pharmaceutiques publiés en application du code de la sécurité sociale",
            nature = "AVIS",
            legalState = null,
            ministry = null,
            containerId = "JORFCONT000052345678",
            publicationDate = "2026-09-05T00:00:00Z"
        )
        assertFalse(OfficialJorfSourceV2.isPayrollRelevant(candidate))
    }

    @Test
    fun `ecarte une indemnite reservee aux fonctionnaires de police`() {
        val candidate = OfficialJorfSourceV2.Candidate(
            textCid = "JORFTEXT000052333336",
            title = "Arrêté fixant une indemnité de responsabilité et de performance allouée aux fonctionnaires de la police nationale",
            nature = "ARRETE",
            legalState = null,
            ministry = null,
            containerId = "JORFCONT000052345678",
            publicationDate = "2026-09-05T00:00:00Z"
        )
        assertFalse(OfficialJorfSourceV2.isPayrollRelevant(candidate))
    }

    @Test
    fun `conserve un vrai texte national de paie`() {
        val candidate = OfficialJorfSourceV2.Candidate(
            textCid = "JORFTEXT000052333337",
            title = "Décret portant relèvement du salaire minimum interprofessionnel de croissance",
            nature = "DECRET",
            legalState = null,
            ministry = "Ministère du travail",
            containerId = "JORFCONT000052345678",
            publicationDate = "2026-09-05T00:00:00Z"
        )
        assertTrue(OfficialJorfSourceV2.isPayrollRelevant(candidate))
    }

    @Test
    fun `parse un contenu JORF officiel consulte`() {
        val document = OfficialJorfSourceV2.parseDocument(
            mapOf(
                "cid" to "JORFTEXT000052111111",
                "id" to "JORFTEXT000052111111",
                "title" to "Décret relatif au salaire minimum",
                "nature" to "DECRET",
                "etat" to "INITIALE",
                "nor" to "TRAV2612345D",
                "dateParution" to "2026-09-05T00:00:00Z",
                "numParution" to "0200",
                "articles" to listOf(mapOf("texte" to "Article 1 : dispositions applicables."))
            )
        )

        assertNotNull(document)
        assertEquals("JORFTEXT000052111111", document!!.textCid)
        assertEquals("TRAV2612345D", document.nor)
        assertTrue(document.hasContent)
    }
}
