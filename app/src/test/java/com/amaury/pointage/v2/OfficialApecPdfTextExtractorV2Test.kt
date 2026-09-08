package com.amaury.pointage.v2

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialApecPdfTextExtractorV2Test {
    @Test
    fun validPdfHeaderPassesPreflight() {
        val bytes = "%PDF-1.7\nbody".toByteArray()
        assertNull(OfficialApecPdfTextExtractorV2.preflight(bytes))
    }

    @Test
    fun nonPdfPayloadIsRejected() {
        val reason = OfficialApecPdfTextExtractorV2.preflight("<html>erreur</html>".toByteArray())
        assertTrue(reason.orEmpty().contains("signature PDF"))
    }

    @Test
    fun encryptedDocumentIsRejected() {
        val reason = OfficialApecPdfTextExtractorV2.documentReason(encrypted = true, pages = 4)
        assertTrue(reason.orEmpty().contains("chiffré"))
    }

    @Test
    fun emptyAndOversizedPageCountsAreRejected() {
        assertTrue(OfficialApecPdfTextExtractorV2.documentReason(false, 0).orEmpty().contains("aucune page"))
        assertTrue(OfficialApecPdfTextExtractorV2.documentReason(false, 81).orEmpty().contains("nombre de pages"))
        assertNull(OfficialApecPdfTextExtractorV2.documentReason(false, 9))
    }

    @Test
    fun missingNativeTextNeverFallsBackToOcr() {
        val reason = OfficialApecPdfTextExtractorV2.extractedTextReason("image only")
        assertTrue(reason.orEmpty().contains("aucun OCR"))
    }

    @Test
    fun substantialNativeTextIsAccepted() {
        val text = "AGREMENT APEC IDCC 292 ".repeat(10)
        assertNull(OfficialApecPdfTextExtractorV2.extractedTextReason(text))
    }
}
