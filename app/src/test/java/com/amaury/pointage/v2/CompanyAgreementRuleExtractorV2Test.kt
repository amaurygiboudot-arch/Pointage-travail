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

    @Test
    fun `cotisation prevoyance est extraite sans devenir une garantie`() {
        val candidates = CompanyAgreementRuleExtractorV2.extract(
            "La cotisation de prévoyance est fixée à 1,20 %, dont 0,60 % à la charge du salarié et 0,60 % à la charge de l'employeur."
        )

        assertTrue(candidates.any { it.category == CompanyAgreementRuleExtractorV2.Category.PROVIDENT_CONTRIBUTION })
        assertTrue(candidates.none { it.category == CompanyAgreementRuleExtractorV2.Category.PROVIDENT_BENEFITS })
    }

    @Test
    fun `garantie deces est extraite sans devenir une cotisation`() {
        val candidates = CompanyAgreementRuleExtractorV2.extract(
            "La garantie décès prévoit un capital décès égal à 200 % du salaire annuel de référence."
        )

        assertTrue(candidates.any { it.category == CompanyAgreementRuleExtractorV2.Category.PROVIDENT_BENEFITS })
        assertTrue(candidates.none { it.category == CompanyAgreementRuleExtractorV2.Category.PROVIDENT_CONTRIBUTION })
    }

    @Test
    fun `mention generique prevoyance ne suffit pas a creer un candidat`() {
        val candidates = CompanyAgreementRuleExtractorV2.extract(
            "Le présent accord comporte diverses dispositions relatives à la prévoyance collective des salariés."
        )

        assertTrue(candidates.none {
            it.category == CompanyAgreementRuleExtractorV2.Category.PROVIDENT_CONTRIBUTION ||
                it.category == CompanyAgreementRuleExtractorV2.Category.PROVIDENT_BENEFITS
        })
    }
}
