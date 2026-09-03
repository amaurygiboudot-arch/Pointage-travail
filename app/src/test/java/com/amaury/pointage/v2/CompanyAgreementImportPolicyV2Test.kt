package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyAgreementImportPolicyV2Test {
    @Test
    fun detectsSupportedDocumentsFromMimeExtensionAndMagicBytes() {
        assertEquals(
            CompanyAgreementImportPolicyV2.Kind.PDF,
            CompanyAgreementImportPolicyV2.detectKind("application/pdf", "accord.pdf", "%PDF-1.7".encodeToByteArray())
        )
        assertEquals(
            CompanyAgreementImportPolicyV2.Kind.DOCX,
            CompanyAgreementImportPolicyV2.detectKind(null, "accord.docx", byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        )
        assertEquals(
            CompanyAgreementImportPolicyV2.Kind.TEXT,
            CompanyAgreementImportPolicyV2.detectKind("application/octet-stream", "accord.txt", "Accord".encodeToByteArray())
        )
    }

    @Test
    fun rejectsAFileWhoseDeclaredPdfTypeHasNoPdfSignature() {
        assertEquals(
            CompanyAgreementImportPolicyV2.Kind.UNSUPPORTED,
            CompanyAgreementImportPolicyV2.detectKind("application/pdf", "accord.pdf", "not a pdf".encodeToByteArray())
        )
    }

    @Test
    fun createsAStableNonPathBasedIdentifier() {
        val hash = "a".repeat(64)
        assertEquals("LOCAL-ACCO-${"A".repeat(24)}", CompanyAgreementImportPolicyV2.stableAgreementId(hash))
    }

    @Test
    fun normalizesTextAndRequiresEnoughReadableContent() {
        val normalized = CompanyAgreementImportPolicyV2.normalizeExtractedText(
            "  Le salaire est majoré.\u0000\r\n\r\n\r\n  Les heures supplémentaires sont rémunérées.  "
        )
        assertFalse(normalized.contains('\u0000'))
        assertFalse(normalized.contains("\n\n\n"))
        assertTrue(CompanyAgreementImportPolicyV2.isMeaningfulText(normalized + " Conditions applicables à tous les salariés de l’entreprise."))
        assertFalse(CompanyAgreementImportPolicyV2.isMeaningfulText("Accord illisible"))
    }

    @Test
    fun enforcesTheImportSizeLimit() {
        assertTrue(CompanyAgreementImportPolicyV2.isSizeAllowed(1))
        assertTrue(CompanyAgreementImportPolicyV2.isSizeAllowed(CompanyAgreementImportPolicyV2.MAX_FILE_BYTES))
        assertFalse(CompanyAgreementImportPolicyV2.isSizeAllowed(0))
        assertFalse(CompanyAgreementImportPolicyV2.isSizeAllowed(CompanyAgreementImportPolicyV2.MAX_FILE_BYTES + 1))
    }
}
