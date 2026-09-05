package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialBoccVerifierV2Test {
    private val candidate = OfficialBoccSourceV2.Candidate(
        title = "Avenant relatif aux salaires minima",
        fileName = "boc_20260017_0001_p000.pdf",
        pathFile = "/BOCC/2026/0017/boc_20260017_0001_p000.pdf",
        publicationDate = "22/07/2026",
        textDate = "01/07/2026",
        bulletinNumber = "2026/0017",
        idMainBocc = "CCO20260017",
        idccs = listOf("3248"),
        department = "Ministère chargé du travail",
        displaySize = "112 Ko"
    )

    @Test
    fun `accepte les metadonnees du meme PDF officiel`() {
        val metadata = OfficialBoccSourceV2.PdfMetadata(
            fileName = candidate.fileName,
            pathToFile = "/BOCC/2026/0017/${candidate.fileName}",
            title = candidate.title,
            publicationDate = "22/07/2026",
            bulletinNumber = "2026/0017",
            displaySize = "112 Ko"
        )

        val verified = OfficialBoccVerifierV2.validate(candidate, metadata, checkedAtMs = 10L)

        assertNotNull(verified)
        assertEquals(candidate.fileName, verified!!.fileName)
        assertEquals(candidate.title, verified.title)
    }

    @Test
    fun `rejette un PDF dont l identifiant ne correspond pas`() {
        val metadata = OfficialBoccSourceV2.PdfMetadata(
            fileName = "boc_20260017_9999_p000.pdf",
            pathToFile = "/BOCC/2026/0017/boc_20260017_9999_p000.pdf",
            title = candidate.title,
            publicationDate = null,
            bulletinNumber = null,
            displaySize = null
        )

        assertNull(OfficialBoccVerifierV2.validate(candidate, metadata, checkedAtMs = 10L))
    }
}
