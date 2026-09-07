package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionSeniorityPremiumV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliSeniorityPremiumParserV2Test {
    private val auditDate = LocalDate.of(2026, 9, 30)

    private fun profile(coefficient: Int = 800) = ConventionLegalProfileV2(
        companyId = "c1", idcc = "292", siret = "12345678901234",
        professionalStatus = "NON_CADRE",
        classification = ConventionClassificationV2(coefficient = coefficient),
        contractType = "FULL_TIME",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0, forfaitAnnualHours = null, forfaitAnnualDays = null
    )

    private fun article(content: String, status: String = "VIGUEUR_ETEN") =
        OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
            articleId = "KALIARTI000000000002",
            status = status,
            content = content,
            effectiveFrom = LocalDate.of(2026, 1, 1),
            effectiveTo = null,
            title = "Prime d'ancienneté"
        )

    @Test
    fun `explicit percentage steps on actual base are structured`() {
        val result = OfficialKaliSeniorityPremiumParserV2.parse(
            article("Prime d'ancienneté des non-cadres coefficient 800, calculée sur le salaire de base mensuel : 3 ans 2,4 %, 6 ans 4,8 %, 9 ans 7,2 %."),
            profile(), auditDate
        )
        val rule = result.rule!!
        assertEquals(ConventionSeniorityPremiumV2.Basis.ACTUAL_MONTHLY_BASE, rule.basis)
        assertEquals(3, rule.steps.size)
        assertEquals(0.024, rule.steps[0].rate!!, 0.00001)
        assertEquals(9, rule.steps.last().years)
        assertEquals(ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED, rule.extensionStatus)
        assertEquals(auditDate, rule.extensionEffectiveFrom)
    }

    @Test
    fun `wrong classification is rejected`() {
        val result = OfficialKaliSeniorityPremiumParserV2.parse(
            article("Prime d'ancienneté des non-cadres coefficient 700, calculée sur le salaire de base : 3 ans 3 %."),
            profile(800), auditDate
        )
        assertNull(result.rule)
    }

    @Test
    fun `ambiguous basis blocks rule`() {
        val result = OfficialKaliSeniorityPremiumParserV2.parse(
            article("Prime d'ancienneté des non-cadres coefficient 800 : calcul sur le salaire de base ou le minimum conventionnel selon le cas ; 3 ans 3 %."),
            profile(), auditDate
        )
        assertNull(result.rule)
    }

    @Test
    fun `fixed monthly steps are structured only with monthly wording`() {
        val result = OfficialKaliSeniorityPremiumParserV2.parse(
            article("Prime d'ancienneté mensuelle des non-cadres coefficient 800 : 3 ans 50 €, 6 ans 80 €."),
            profile(), auditDate
        )
        val rule = result.rule!!
        assertEquals(ConventionSeniorityPremiumV2.Basis.FIXED_MONTHLY, rule.basis)
        assertEquals(50.0, rule.steps.first().fixedMonthlyAmount!!, 0.001)
        assertTrue(rule.structurallyValid())
    }

    @Test
    fun `non extended status remains non extended`() {
        val result = OfficialKaliSeniorityPremiumParserV2.parse(
            article("Prime d'ancienneté des non-cadres coefficient 800, calculée sur le salaire de base : 3 ans 3 %.", "VIGUEUR_NON_ETEN"),
            profile(), auditDate
        )
        val rule = result.rule!!
        assertEquals(ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED, rule.extensionStatus)
        assertNull(rule.extensionEffectiveFrom)
    }
}
