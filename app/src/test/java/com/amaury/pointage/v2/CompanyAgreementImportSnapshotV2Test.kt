package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyAgreementImportSnapshotV2Test {
    @Test
    fun `import valide prepare principal et copie saine pour les deux stores ACCO`() {
        val snapshot = requireNotNull(
            CompanyAgreementImportCommitV2.buildSnapshotEntries(
                agreements = listOf(agreement()),
                candidates = listOf(candidate())
            )
        )

        assertEquals(4, snapshot.size)
        assertEquals(
            snapshot[CompanyAgreementStoreV2.KEY],
            snapshot[CompanyAgreementStoreV2.KEY_LAST_KNOWN_GOOD]
        )
        assertEquals(
            snapshot[CompanyAgreementRuleStoreV2.KEY],
            snapshot[CompanyAgreementRuleStoreV2.KEY_LAST_KNOWN_GOOD]
        )
        assertTrue(CompanyAgreementStoreV2.decodeRecords(snapshot.getValue(CompanyAgreementStoreV2.KEY)).reliable)
        assertTrue(CompanyAgreementRuleStoreV2.decodeRecords(snapshot.getValue(CompanyAgreementRuleStoreV2.KEY)).reliable)
    }

    @Test
    fun `metadonnees ACCO invalides bloquent tout le snapshot atomique`() {
        val entries = CompanyAgreementImportCommitV2.buildSnapshotEntries(
            agreements = listOf(agreement().copy(id = "")),
            candidates = listOf(candidate())
        )

        assertNull(entries)
    }

    @Test
    fun `regle ACCO invalide bloque tout le snapshot atomique`() {
        val entries = CompanyAgreementImportCommitV2.buildSnapshotEntries(
            agreements = listOf(agreement()),
            candidates = listOf(candidate().copy(confidence = 1.5))
        )

        assertNull(entries)
    }

    private fun agreement() = CompanyAgreementStoreV2.Agreement(
        id = "LOCAL-ACCO-TEST",
        title = "Accord importé",
        effectiveFrom = "2026-01-01",
        effectiveTo = null,
        sourceLabel = "Import local",
        status = CompanyAgreementStoreV2.Status.IMPORTED,
        documentName = "accord.pdf",
        importedAtEpochMs = 1_000L
    )

    private fun candidate() = CompanyAgreementRuleStoreV2.StoredCandidate(
        agreementId = "LOCAL-ACCO-TEST",
        category = CompanyAgreementRuleExtractorV2.Category.OVERTIME,
        excerpt = "Les heures supplémentaires sont majorées de 25 %.",
        confidence = 0.9,
        verified = false
    )
}
