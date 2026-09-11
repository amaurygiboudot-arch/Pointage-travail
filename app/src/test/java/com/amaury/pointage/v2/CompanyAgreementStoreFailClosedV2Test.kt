package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyAgreementStoreFailClosedV2Test {
    @Test
    fun `liste vide explicite reste fiable`() {
        val decoded = CompanyAgreementStoreV2.decodeRecords("[]")

        assertTrue(decoded.reliable)
        assertTrue(decoded.agreements.isEmpty())
    }

    @Test
    fun `json illisible ne devient pas une absence fiable daccord`() {
        val decoded = CompanyAgreementStoreV2.decodeRecords("{broken-json")

        assertFalse(decoded.reliable)
        assertTrue(decoded.agreements.isEmpty())
        assertTrue(decoded.warnings.isNotEmpty())
    }

    @Test
    fun `entree invalide ne disparait pas silencieusement`() {
        val decoded = CompanyAgreementStoreV2.decodeRecords(
            """[
                {"id":"ACCOTEXT000001","title":"Accord temps de travail","effectiveFrom":"2026-01-01","effectiveTo":"","sourceLabel":"Légifrance","status":"VERIFIED"},
                {"id":"","title":"Accord cassé","sourceLabel":"Légifrance","status":"UNKNOWN"}
            ]""".trimIndent()
        )

        assertFalse(decoded.reliable)
        assertEquals(1, decoded.agreements.size)
        assertEquals("ACCOTEXT000001", decoded.agreements.single().id)
    }

    @Test
    fun `statut inconnu dans le json est une corruption et non UNKNOWN implicite`() {
        val decoded = CompanyAgreementStoreV2.decodeRecords(
            """[{"id":"ACCOTEXT000001","title":"Accord","sourceLabel":"Légifrance","status":"SUPER_VERIFIED"}]"""
        )

        assertFalse(decoded.reliable)
        assertTrue(decoded.agreements.isEmpty())
    }

    @Test
    fun `doublon didentifiant rend le stockage incoherent`() {
        val record = """{"id":"ACCOTEXT000001","title":"Accord","sourceLabel":"Légifrance","status":"UNKNOWN"}"""
        val decoded = CompanyAgreementStoreV2.decodeRecords("[$record,$record]")

        assertFalse(decoded.reliable)
        assertEquals(2, decoded.agreements.size)
    }

    @Test
    fun `champ textuel avec type numerique bloque le stockage`() {
        val decoded = CompanyAgreementStoreV2.decodeRecords(
            """[{"id":"ACCOTEXT000001","title":42,"sourceLabel":"Légifrance","status":"UNKNOWN"}]"""
        )

        assertFalse(decoded.reliable)
        assertTrue(decoded.agreements.isEmpty())
    }

    @Test
    fun `ancienne entree sans champs facultatifs reste compatible`() {
        val decoded = CompanyAgreementStoreV2.decodeRecords(
            """[{"id":"ACCOTEXT000001","title":"Accord historique","sourceLabel":"Légifrance"}]"""
        )

        assertTrue(decoded.reliable)
        assertEquals(1, decoded.agreements.size)
        assertEquals(CompanyAgreementStoreV2.Status.UNKNOWN, decoded.agreements.single().status)
        assertEquals("", decoded.agreements.single().documentName)
    }

    @Test
    fun `timestamp importe fractionnaire est refuse`() {
        val decoded = CompanyAgreementStoreV2.decodeRecords(
            """[{"id":"LOCAL-ACCO-ABC","title":"Import","sourceLabel":"Import local","status":"IMPORTED","importedAtEpochMs":123.5}]"""
        )

        assertFalse(decoded.reliable)
        assertTrue(decoded.agreements.isEmpty())
    }

    @Test
    fun `copie locale valide repare des metadonnees ACCO corrompues`() {
        val backup = CompanyAgreementStoreV2.encode(listOf(agreement("ACCOTEXT000001", CompanyAgreementStoreV2.Status.VERIFIED)))

        val resolution = CompanyAgreementStoreV2.resolveStoredAgreements(
            primaryRaw = "{broken",
            backupRaw = backup
        )

        assertEquals(CompanyAgreementStoreV2.StorageSource.LAST_KNOWN_GOOD, resolution.source)
        assertTrue(resolution.result.reliable)
        assertTrue(resolution.result.repairedFromBackup)
        assertEquals("ACCOTEXT000001", resolution.result.agreements.single().id)
        assertTrue(resolution.result.warnings.any { it.contains("restauré") })
    }

    @Test
    fun `metadonnees principales valides restent prioritaires sur une ancienne copie`() {
        val primary = CompanyAgreementStoreV2.encode(listOf(agreement("ACCOTEXT000001", CompanyAgreementStoreV2.Status.UNKNOWN)))
        val backup = CompanyAgreementStoreV2.encode(listOf(agreement("ACCOTEXT000001", CompanyAgreementStoreV2.Status.VERIFIED)))

        val resolution = CompanyAgreementStoreV2.resolveStoredAgreements(primary, backup)

        assertEquals(CompanyAgreementStoreV2.StorageSource.PRIMARY, resolution.source)
        assertTrue(resolution.result.reliable)
        assertFalse(resolution.result.repairedFromBackup)
        assertEquals(CompanyAgreementStoreV2.Status.UNKNOWN, resolution.result.agreements.single().status)
    }

    @Test
    fun `principal et copie corrompus restent bloques sans auto nettoyage`() {
        val resolution = CompanyAgreementStoreV2.resolveStoredAgreements(
            primaryRaw = """[
                {"id":"ACCOTEXT000001","title":"Accord temps de travail","sourceLabel":"Légifrance","status":"UNKNOWN"},
                "entree-cassee"
            ]""".trimIndent(),
            backupRaw = "{backup-broken"
        )

        assertEquals(CompanyAgreementStoreV2.StorageSource.NONE, resolution.source)
        assertFalse(resolution.result.reliable)
        assertFalse(resolution.result.repairedFromBackup)
        assertEquals(1, resolution.result.agreements.size)
    }

    private fun agreement(id: String, status: CompanyAgreementStoreV2.Status) = CompanyAgreementStoreV2.Agreement(
        id = id,
        title = "Accord temps de travail",
        effectiveFrom = "2026-01-01",
        effectiveTo = null,
        sourceLabel = "Légifrance",
        status = status
    )
}
