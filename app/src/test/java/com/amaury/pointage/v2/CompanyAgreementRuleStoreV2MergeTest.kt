package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyAgreementRuleStoreV2MergeTest {
    @Test
    fun reanalysisPreservesManualValidationForAnUnchangedCandidate() {
        val existing = CompanyAgreementRuleStoreV2.StoredCandidate(
            agreementId = "LOCAL-ACCO-1",
            category = CompanyAgreementRuleExtractorV2.Category.OVERTIME,
            excerpt = "Les heures supplémentaires sont majorées de 25 %.",
            confidence = 0.65,
            verified = true,
            effectiveFrom = "01/01/2026",
            scope = "Tous les salariés",
            calculationValueVerified = true
        )
        val refreshed = CompanyAgreementRuleExtractorV2.Candidate(
            category = existing.category,
            excerpt = existing.excerpt,
            confidence = 0.90
        )

        val result = CompanyAgreementRuleStoreV2.mergePreservingValidation(
            listOf(existing),
            existing.agreementId,
            listOf(refreshed)
        ).single()

        assertEquals(0.90, result.confidence, 0.0)
        assertTrue(result.verified)
        assertTrue(result.calculationValueVerified)
        assertEquals("01/01/2026", result.effectiveFrom)
        assertEquals("Tous les salariés", result.scope)
    }

    @Test
    fun aNewCandidateAlwaysRequiresManualValidation() {
        val result = CompanyAgreementRuleStoreV2.mergePreservingValidation(
            emptyList(),
            "LOCAL-ACCO-2",
            listOf(
                CompanyAgreementRuleExtractorV2.Candidate(
                    category = CompanyAgreementRuleExtractorV2.Category.NIGHT,
                    excerpt = "Le travail de nuit ouvre droit à une majoration.",
                    confidence = 0.80
                )
            )
        ).single()

        assertFalse(result.verified)
        assertFalse(result.calculationValueVerified)
    }

    @Test
    fun anEmptyReanalysisNeverErasesPreviouslyValidatedRules() {
        val existing = CompanyAgreementRuleStoreV2.StoredCandidate(
            agreementId = "LOCAL-ACCO-3",
            category = CompanyAgreementRuleExtractorV2.Category.RTT,
            excerpt = "Une journée de RTT est accordée.",
            confidence = 0.65,
            verified = true
        )
        assertEquals(
            listOf(existing),
            CompanyAgreementRuleStoreV2.mergePreservingValidation(listOf(existing), existing.agreementId, emptyList())
        )
    }
}
