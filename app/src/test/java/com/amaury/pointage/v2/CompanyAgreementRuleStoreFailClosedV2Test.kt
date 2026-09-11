package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CompanyAgreementRuleStoreFailClosedV2Test {
    private val date = LocalDate.of(2026, 9, 30)

    @Test
    fun `entreprise indisponible bloque toute lecture des regles ACCO`() {
        val result = CompanyAgreementRuleStoreV2.unavailableCompanyReadResult()

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.any { it.contains("entreprise", ignoreCase = true) })
    }

    @Test
    fun `liste vide explicite reste un stockage lisible`() {
        val decoded = CompanyAgreementRuleStoreV2.decodeRecords("[]")

        assertTrue(decoded.reliable)
        assertTrue(decoded.records.isEmpty())
    }

    @Test
    fun `json illisible ne devient jamais une liste vide fiable`() {
        val decoded = CompanyAgreementRuleStoreV2.decodeRecords("{not-json")

        assertFalse(decoded.reliable)
        assertTrue(decoded.records.isEmpty())
        assertTrue(decoded.warnings.isNotEmpty())
    }

    @Test
    fun `entree invalide ne disparait pas silencieusement`() {
        val decoded = CompanyAgreementRuleStoreV2.decodeRecords(
            """[
                {"agreementId":"ACCO-1","category":"OVERTIME","excerpt":"Majoration 25 %","confidence":0.9,"verified":false,"effectiveFrom":"","effectiveTo":"","scope":"","calculationValueVerified":false},
                {"agreementId":"","category":"OVERTIME","excerpt":"Règle cassée","confidence":0.8,"verified":false,"effectiveFrom":"","effectiveTo":"","scope":"","calculationValueVerified":false}
            ]""".trimIndent()
        )

        assertFalse(decoded.reliable)
        assertEquals(1, decoded.records.size)
    }

    @Test
    fun `doublon de candidate ACCO rend le stockage incoherent`() {
        val raw = """{"agreementId":"ACCO-1","category":"OVERTIME","excerpt":"Majoration 25 %","confidence":0.9,"verified":false,"effectiveFrom":"","effectiveTo":"","scope":"","calculationValueVerified":false}"""
        val decoded = CompanyAgreementRuleStoreV2.decodeRecords("[$raw,$raw]")

        assertFalse(decoded.reliable)
        assertEquals(2, decoded.records.size)
    }

    @Test
    fun `valeur de calcul ne peut pas etre verifiee si la regle ne lest pas`() {
        val decoded = CompanyAgreementRuleStoreV2.decodeRecords(
            """[{"agreementId":"ACCO-1","category":"OVERTIME","excerpt":"Majoration 25 %","confidence":0.9,"verified":false,"effectiveFrom":"","effectiveTo":"","scope":"","calculationValueVerified":true}]"""
        )

        assertFalse(decoded.reliable)
        assertTrue(decoded.records.isEmpty())
    }

    @Test
    fun `copie locale valide repare un stockage principal corrompu`() {
        val backup = CompanyAgreementRuleStoreV2.encode(
            listOf(candidate(verified = true, effectiveFrom = "01/01/2026", scope = "Tous les salariés"))
        )

        val resolution = CompanyAgreementRuleStoreV2.resolveStoredRecords(
            primaryRaw = "{broken",
            backupRaw = backup
        )

        assertEquals(CompanyAgreementRuleStoreV2.StorageSource.LAST_KNOWN_GOOD, resolution.source)
        assertTrue(resolution.result.reliable)
        assertTrue(resolution.result.repairedFromBackup)
        assertEquals(1, resolution.result.records.size)
        assertTrue(resolution.result.warnings.any { it.contains("restauré") })
    }

    @Test
    fun `stockage principal valide reste prioritaire sur une ancienne copie`() {
        val primary = CompanyAgreementRuleStoreV2.encode(
            listOf(candidate(verified = false, effectiveFrom = null, scope = null))
        )
        val backup = CompanyAgreementRuleStoreV2.encode(
            listOf(candidate(verified = true, effectiveFrom = "01/01/2026", scope = "Tous les salariés"))
        )

        val resolution = CompanyAgreementRuleStoreV2.resolveStoredRecords(primary, backup)

        assertEquals(CompanyAgreementRuleStoreV2.StorageSource.PRIMARY, resolution.source)
        assertTrue(resolution.result.reliable)
        assertFalse(resolution.result.repairedFromBackup)
        assertFalse(resolution.result.records.single().verified)
    }

    @Test
    fun `stockage et copie corrompus restent bloques sans auto nettoyage`() {
        val resolution = CompanyAgreementRuleStoreV2.resolveStoredRecords(
            primaryRaw = """[
                {"agreementId":"ACCO-1","category":"OVERTIME","excerpt":"Majoration 25 %","confidence":0.9,"verified":false,"effectiveFrom":"","effectiveTo":"","scope":"","calculationValueVerified":false},
                "entree-cassee"
            ]""".trimIndent(),
            backupRaw = "{backup-broken"
        )

        assertEquals(CompanyAgreementRuleStoreV2.StorageSource.NONE, resolution.source)
        assertFalse(resolution.result.reliable)
        assertFalse(resolution.result.repairedFromBackup)
        assertEquals(1, resolution.result.records.size)
    }

    @Test
    fun `regle verifiee sans periode exploitable bloque le repli`() {
        val stored = CompanyAgreementRuleStoreV2.ReadResult(
            records = listOf(candidate(verified = true, effectiveFrom = null, scope = "Tous les salariés")),
            reliable = true,
            warnings = emptyList()
        )

        val result = ApplicableCompanyAgreementRulesV2.resolve(stored, date)

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `regle verifiee datee et scoped reste exploitable`() {
        val stored = CompanyAgreementRuleStoreV2.ReadResult(
            records = listOf(candidate(verified = true, effectiveFrom = "01/01/2026", scope = "Tous les salariés")),
            reliable = true,
            warnings = emptyList()
        )

        val result = ApplicableCompanyAgreementRulesV2.resolve(stored, date)

        assertTrue(result.reliable)
        assertEquals(1, result.rules.size)
    }

    private fun candidate(
        verified: Boolean,
        effectiveFrom: String?,
        scope: String?
    ) = CompanyAgreementRuleStoreV2.StoredCandidate(
        agreementId = "ACCO-1",
        category = CompanyAgreementRuleExtractorV2.Category.OVERTIME,
        excerpt = "Les heures supplémentaires sont majorées de 25 %.",
        confidence = 0.9,
        verified = verified,
        effectiveFrom = effectiveFrom,
        scope = scope,
        calculationValueVerified = false
    )
}
