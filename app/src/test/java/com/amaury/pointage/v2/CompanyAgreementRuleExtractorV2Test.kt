package com.amaury.pointage.v2

import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyAgreementRuleExtractorV2Test {
    @Test
    fun `jours feries sont extraits dans une famille distincte des conges payes`() {
        val candidates = CompanyAgreementRuleExtractorV2.extract(
            "Les heures travaillées les jours fériés donnent lieu à une majoration de 50 %."
        )

        assertTrue(candidates.any { it.category == CompanyAgreementRuleExtractorV2.Category.PUBLIC_HOLIDAY })
        assertTrue(candidates.none { it.category == CompanyAgreementRuleExtractorV2.Category.PAID_LEAVE })
    }

    @Test
    fun `panier repas est extrait dans une famille dediee`() {
        val candidates = CompanyAgreementRuleExtractorV2.extract(
            "Une indemnité repas dite prime de panier de 5,50 euros est versée aux salariés concernés."
        )

        assertTrue(candidates.any { it.category == CompanyAgreementRuleExtractorV2.Category.MEAL })
    }
}
