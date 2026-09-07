package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliMinimumSalaryParserV2Test {
    private val auditDate = LocalDate.of(2026, 9, 30)
    private val extensionDate = LocalDate.of(2026, 2, 1)

    private fun profile(coefficient: Int = 800, status: String = "NON_CADRE") = ConventionLegalProfileV2(
        companyId = "c1",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = status,
        classification = ConventionClassificationV2(coefficient = coefficient),
        contractType = "FULL_TIME",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun article(
        content: String,
        status: String = "VIGUEUR_ETEN",
        extension: LocalDate? = extensionDate
    ) = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
        articleId = "KALIARTI000000000001",
        status = status,
        content = content,
        effectiveFrom = LocalDate.of(2026, 1, 1),
        effectiveTo = null,
        title = "Salaires minima",
        extensionEffectiveFrom = extension
    )

    @Test
    fun `minimum exact coefficient and status is structured`() {
        val diagnostic = OfficialKaliMinimumSalaryParserV2.parse(
            article("Salaires minima mensuels des non-cadres. Coefficient 800 : salaire minimum mensuel 2 100,50 €."),
            profile(),
            auditDate
        )
        val rule = diagnostic.rule!!
        assertEquals(2100.50, rule.amount, 0.001)
        assertEquals(ConventionMinimumSalaryV2.Periodicity.MONTHLY, rule.periodicity)
        assertEquals(ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED, rule.extensionStatus)
        assertEquals(extensionDate, rule.extensionEffectiveFrom)
        assertEquals(800, rule.classification.coefficient)
    }

    @Test
    fun `extended status without exact extension date stays unknown`() {
        val diagnostic = OfficialKaliMinimumSalaryParserV2.parse(
            article("Salaires minima mensuels des non-cadres. Coefficient 800 : salaire minimum mensuel 2 100,50 €.", extension = null),
            profile(),
            auditDate
        )
        val rule = diagnostic.rule!!
        assertEquals(ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN, rule.extensionStatus)
        assertNull(rule.extensionEffectiveFrom)
        assertTrue(diagnostic.reasons.any { it.contains("date exacte d'extension") })
    }

    @Test
    fun `wrong coefficient is rejected`() {
        val diagnostic = OfficialKaliMinimumSalaryParserV2.parse(
            article("Salaires minima mensuels des non-cadres. Coefficient 700 : salaire minimum mensuel 2 000 €."),
            profile(800),
            auditDate
        )
        assertNull(diagnostic.rule)
    }

    @Test
    fun `two different amounts near same classification are rejected`() {
        val diagnostic = OfficialKaliMinimumSalaryParserV2.parse(
            article("Salaires minima mensuels des non-cadres. Coefficient 800 : salaire minimum mensuel 2 100 € puis prime 50 €."),
            profile(),
            auditDate
        )
        assertNull(diagnostic.rule)
    }

    @Test
    fun `cadre article cannot be applied to non cadre profile`() {
        val diagnostic = OfficialKaliMinimumSalaryParserV2.parse(
            article("Salaires minima mensuels des cadres. Coefficient 800 : salaire minimum mensuel 2 500 €."),
            profile(status = "NON_CADRE"),
            auditDate
        )
        assertNull(diagnostic.rule)
    }

    @Test
    fun `non extended rule is stored as non extended and not silently promoted`() {
        val diagnostic = OfficialKaliMinimumSalaryParserV2.parse(
            article(
                "Salaires minima mensuels des non-cadres. Coefficient 800 : salaire minimum mensuel 2 100 €.",
                status = "VIGUEUR_NON_ETEN",
                extension = null
            ),
            profile(),
            auditDate
        )
        val rule = diagnostic.rule!!
        assertEquals(ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED, rule.extensionStatus)
        assertNull(rule.extensionEffectiveFrom)
        assertTrue(rule.structurallyValid())
    }
}
