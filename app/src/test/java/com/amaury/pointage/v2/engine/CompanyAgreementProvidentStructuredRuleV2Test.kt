package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.CompanyAgreementRuleExtractorV2
import com.amaury.pointage.v2.CompanyAgreementRuleStoreV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CompanyAgreementProvidentStructuredRuleV2Test {
    private fun candidate(category: CompanyAgreementRuleExtractorV2.Category) =
        CompanyAgreementRuleStoreV2.StoredCandidate(
            agreementId = "ACCOTEXT000000000001",
            category = category,
            excerpt = "La cotisation de prévoyance est fixée à 1,20 %.",
            confidence = 0.9,
            verified = true,
            effectiveFrom = "2026-01-01",
            effectiveTo = null,
            scope = "Tous les salariés",
            calculationValueVerified = true
        )

    @Test
    fun `cotisation prevoyance ne passe jamais par le parseur percent generique`() {
        val structured = CompanyAgreementStructuredRuleV2.structure(
            candidate(CompanyAgreementRuleExtractorV2.Category.PROVIDENT_CONTRIBUTION)
        )

        assertNull(structured.value)
        assertFalse(structured.calculationReady)
    }

    @Test
    fun `garantie prevoyance ne passe jamais par le parseur generique`() {
        val structured = CompanyAgreementStructuredRuleV2.structure(
            candidate(CompanyAgreementRuleExtractorV2.Category.PROVIDENT_BENEFITS)
        )

        assertNull(structured.value)
        assertFalse(structured.calculationReady)
    }
}
