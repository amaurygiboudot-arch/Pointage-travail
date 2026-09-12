package com.amaury.pointage.v2

import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyAgreementSeniorityExtractionV2Test {
    @Test
    fun `prime anciennete est extraite dans une famille dediee`() {
        val candidates = CompanyAgreementRuleExtractorV2.extract(
            "La prime d'ancienneté est fixée à 3 % du salaire de base après trois années de présence."
        )

        assertTrue(candidates.any { it.category == CompanyAgreementRuleExtractorV2.Category.SENIORITY })
    }

    @Test
    fun `clause anciennete sans mot prime reste visible pour arbitrage`() {
        val candidates = CompanyAgreementRuleExtractorV2.extract(
            "L'ancienneté acquise au sein de l'entreprise ouvre droit à une majoration spécifique de rémunération."
        )

        assertTrue(candidates.any { it.category == CompanyAgreementRuleExtractorV2.Category.SENIORITY })
    }
}
