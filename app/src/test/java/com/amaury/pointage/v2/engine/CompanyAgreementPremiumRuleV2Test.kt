package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.CompanyAgreementRuleExtractorV2
import com.amaury.pointage.v2.CompanyAgreementRuleStoreV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CompanyAgreementPremiumRuleV2Test {
    private fun stored(
        category: CompanyAgreementRuleExtractorV2.Category,
        excerpt: String,
        verified: Boolean = true,
        valueVerified: Boolean = true
    ) = CompanyAgreementRuleStoreV2.StoredCandidate(
        agreementId = "ACCOTEXT000000001",
        category = category,
        excerpt = excerpt,
        confidence = 0.9,
        verified = verified,
        effectiveFrom = "2026-01-01",
        effectiveTo = null,
        scope = "Tous les salariés",
        calculationValueVerified = valueVerified
    )

    @Test
    fun `accord nuit valide exige plage et taux uniques`() {
        val structured = CompanyAgreementStructuredRuleV2.structure(
            stored(
                CompanyAgreementRuleExtractorV2.Category.NIGHT,
                "Les heures de nuit effectuées de 21 h à 6 h donnent lieu à une majoration de 25 %."
            )
        )
        val rule = CompanyAgreementPremiumRuleV2.night(structured)
        assertNotNull(rule)
        assertEquals(21 * 60, rule!!.rule.startMinute)
        assertEquals(6 * 60, rule.rule.endMinute)
        assertEquals(1.25, rule.rule.multiplier, 0.0001)
    }

    @Test
    fun `accord samedi simple valide devient calculable`() {
        val structured = CompanyAgreementStructuredRuleV2.structure(
            stored(
                CompanyAgreementRuleExtractorV2.Category.SATURDAY,
                "Les heures travaillées le samedi donnent lieu à une majoration de 25 %."
            )
        )
        val rule = CompanyAgreementPremiumRuleV2.weekday(structured, WeekdayPremiumKindV2.SATURDAY)
        assertNotNull(rule)
        assertEquals(1.25, rule!!.rule.multiplier, 0.0001)
    }

    @Test
    fun `accord dimanche melange aux jours feries reste non calculable`() {
        val structured = CompanyAgreementStructuredRuleV2.structure(
            stored(
                CompanyAgreementRuleExtractorV2.Category.SUNDAY,
                "Le dimanche et les jours fériés donnent lieu à une majoration de 100 %."
            )
        )
        assertNull(CompanyAgreementPremiumRuleV2.weekday(structured, WeekdayPremiumKindV2.SUNDAY))
    }

    @Test
    fun `valeur non explicitement validee reste bloquee`() {
        val structured = CompanyAgreementStructuredRuleV2.structure(
            stored(
                CompanyAgreementRuleExtractorV2.Category.SATURDAY,
                "Le samedi est majoré de 25 %.",
                valueVerified = false
            )
        )
        assertNull(CompanyAgreementPremiumRuleV2.weekday(structured, WeekdayPremiumKindV2.SATURDAY))
    }
}
