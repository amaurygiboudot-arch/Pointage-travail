package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialBoccSourceV2Test {
    @Test
    fun `construit une liste BOCC bornee a un IDCC et une periode`() {
        val body = OfficialBoccSourceV2.listBody(
            idcc = "IDCC 3248",
            from = LocalDate.of(2024, 9, 30),
            to = LocalDate.of(2026, 9, 30),
            pageSize = 500
        )

        assertEquals("3248", body["idcc"])
        assertEquals("30/09/2024 > 30/09/2026", body["intervalPublication"])
        assertEquals(1, body["pageNumber"])
        assertEquals(100, body["pageSize"])
        assertNull(body["searchForGlobalBocc"])
        assertNull(body["searchForTextsBocc"])
        assertEquals("BOCC_SORT_DESC", body["sortValue"])
    }

    @Test
    fun `parse la reponse unitaire BOCC si elle est renvoyee`() {
        val response = mapOf(
            "totalResultNumber" to 1,
            "texts" to listOf(
                mapOf(
                    "title" to "Avenant relatif aux salaires minima",
                    "fileName" to "boc_20260017_0001_p000.pdf",
                    "pathFile" to "/BOCC/2026/0017/boc_20260017_0001_p000.pdf",
                    "idccs" to listOf("3248"),
                    "texteDate" to "2026-07-01T00:00:00.000Z",
                    "idMainBocc" to "CCO20260017",
                    "department" to "Ministère chargé du travail",
                    "displaySize" to "112 Ko"
                )
            )
        )

        val candidates = OfficialBoccSourceV2.parseCandidates(response)

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals("boc_20260017_0001_p000.pdf", candidate.fileName)
        assertEquals(listOf("3248"), candidate.idccs)
        assertEquals("CCO20260017", candidate.idMainBocc)
        assertTrue(OfficialBoccSourceV2.isPayrollRelevant(candidate))
        assertEquals(1, OfficialBoccSourceV2.totalResultNumber(response))
    }

    @Test
    fun `parse la forme officielle boccsAndTexts`() {
        val response = mapOf(
            "results" to listOf(
                mapOf(
                    "globalBocc" to mapOf(
                        "dateParution" to "22/07/2026",
                        "numParution" to "2026/0017"
                    ),
                    "texts" to listOf(
                        mapOf(
                            "title" to "Avenant relatif aux salaires minima",
                            "fileName" to "boc_20260017_0001_p000.pdf",
                            "idccs" to listOf("3248")
                        )
                    )
                )
            )
        )

        val candidate = OfficialBoccSourceV2.parseCandidates(response).single()
        assertEquals("2026/0017", candidate.bulletinNumber)
        assertEquals("22/07/2026", candidate.publicationDate)
    }

    @Test
    fun `ignore les entrees BOCC sans fichier PDF sur`() {
        val response = mapOf(
            "texts" to listOf(
                mapOf("title" to "Salaires", "fileName" to "document.txt"),
                mapOf("title" to "Salaires", "fileName" to "nom avec espace.pdf")
            )
        )

        assertTrue(OfficialBoccSourceV2.parseCandidates(response).isEmpty())
    }

    @Test
    fun `distingue un titre paie d un titre de gouvernance`() {
        val base = OfficialBoccSourceV2.Candidate(
            title = "Prime et rémunération minimale",
            fileName = "boc_20260001_0001_p000.pdf",
            pathFile = null,
            publicationDate = null,
            textDate = null,
            bulletinNumber = null,
            idMainBocc = null,
            idccs = listOf("3248"),
            department = null,
            displaySize = null
        )
        assertTrue(OfficialBoccSourceV2.isPayrollRelevant(base))
        assertFalse(OfficialBoccSourceV2.isPayrollRelevant(base.copy(title = "Composition de la commission paritaire")))
    }

    @Test
    fun `parse les metadonnees officielles du PDF BOCC`() {
        val metadata = OfficialBoccSourceV2.parsePdfMetadata(
            mapOf(
                "fileName" to "boc_20260017_0001_p000.pdf",
                "pathToFile" to "/BOCC/2026/0017/boc_20260017_0001_p000.pdf",
                "title" to "Avenant salaires",
                "dateParution" to "22/07/2026",
                "numParution" to "2026/0017",
                "displaySize" to "112 Ko"
            )
        )

        assertNotNull(metadata)
        assertEquals("boc_20260017_0001_p000.pdf", metadata!!.fileName)
        assertEquals("2026/0017", metadata.bulletinNumber)
    }
}
