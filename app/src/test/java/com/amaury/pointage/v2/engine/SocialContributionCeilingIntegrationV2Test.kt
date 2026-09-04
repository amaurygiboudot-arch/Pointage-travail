package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SocialContributionCeilingIntegrationV2Test {
    private fun partTime28hCeiling() = SocialSecurityCeilingV2.calculate(
        SocialSecurityCeilingV2.Input(
            year = 2026,
            referenceDate = LocalDate.of(2026, 3, 31),
            contractType = ContractTypeV2.PART_TIME,
            contractualWeeklyMinutes = 28 * 60,
            complementaryMinutes = 0,
            entryDate = LocalDate.of(2020, 1, 1)
        )
    )

    @Test
    fun `vieillesse plafonnee utilise le plafond temps partiel`() {
        val ceiling = partTime28hCeiling()
        val estimate = SocialContributionCatalogV2.estimateEmployeeDeductions(5000.0, 2026, ceiling)
        val line = estimate.lines.firstOrNull { it.id == "old_age_capped" }

        assertNotNull(line)
        assertEquals(3204.0, line!!.baseAmount, 0.001)
    }

    @Test
    fun `abattement csg crds utilise quatre fois le plafond proratisé`() {
        val ceiling = partTime28hCeiling()
        val gross = 15000.0
        val estimate = SocialContributionCatalogV2.estimateEmployeeDeductions(gross, 2026, ceiling)
        val line = estimate.lines.firstOrNull { it.id == "csg_deductible" }
        val cap = 3204.0 * 4.0
        val expectedBase = cap * 0.9825 + (gross - cap)

        assertNotNull(line)
        assertEquals(expectedBase, line!!.baseAmount, 0.001)
    }

    @Test
    fun `agirc arrco utilise le meme plafond proratisé pour T1 et T2`() {
        val ceiling = partTime28hCeiling()
        val estimate = ComplementaryRetirementCatalogV2.estimate(5000.0, 2026, "NON_CADRE", ceiling)
        val t1 = estimate.lines.firstOrNull { it.id == "agirc_t1" }
        val t2 = estimate.lines.firstOrNull { it.id == "agirc_t2" }

        assertNotNull(t1)
        assertNotNull(t2)
        assertEquals(3204.0, t1!!.baseAmount, 0.001)
        assertEquals(1796.0, t2!!.baseAmount, 0.001)
    }

    @Test
    fun `minimum employeur cadre utilise le plafond proratisé`() {
        val ceiling = partTime28hCeiling()
        val estimate = ProfessionalStatusContributionCatalogV2.estimate(5000.0, 2026, "CADRE", ceiling)
        val line = estimate.lines.firstOrNull { it.id == "cadre_provident_employer_minimum" }

        assertNotNull(line)
        assertEquals(3204.0, line!!.baseAmount, 0.001)
    }

    @Test
    fun `assimile cadre 830 recoit le minimum employeur ANI meme si statut non cadre`() {
        val ceiling = partTime28hCeiling()
        val category = PlasturgieProtectionCategoryV2.classify("292", LocalDate.of(2026, 3, 31), 830)
        val estimate = ProfessionalStatusContributionCatalogV2.estimate(
            gross = 5000.0,
            year = 2026,
            professionalStatus = "NON_CADRE",
            ceiling = ceiling,
            protectionCategory = category
        )
        val line = estimate.lines.firstOrNull { it.id == "cadre_provident_employer_minimum" }

        assertNotNull(line)
        assertEquals(3204.0, line!!.baseAmount, 0.001)
        assertEquals(3204.0 * 0.015, line.employerAmount, 0.001)
    }

    @Test
    fun `hors ANI 700 ne recoit pas le 1 50 par simple statut cadre`() {
        val category = PlasturgieProtectionCategoryV2.classify("292", LocalDate.of(2026, 3, 31), 700)
        val estimate = ProfessionalStatusContributionCatalogV2.estimate(
            gross = 5000.0,
            year = 2026,
            professionalStatus = "CADRE",
            ceiling = partTime28hCeiling(),
            protectionCategory = category
        )

        assertNull(estimate.lines.firstOrNull { it.id == "cadre_provident_employer_minimum" })
    }

    @Test
    fun `prevoyance plasturgie utilise quatre fois le plafond proratisé hors ANI`() {
        val ceiling = partTime28hCeiling()
        val category = PlasturgieProtectionCategoryV2.classify("292", LocalDate.of(2026, 3, 31), 700)
        val estimate = ConventionProvidentCatalogV2.estimate(
            gross = 15000.0,
            year = 2026,
            idcc = "292",
            protectionCategory = category,
            seniorityMonths = 12,
            ceiling = ceiling
        )
        val line = estimate.lines.firstOrNull { it.id == "plasturgie_292_non_cadre_provident" }

        assertNotNull(line)
        assertEquals(12816.0, line!!.baseAmount, 0.001)
    }

    @Test
    fun `assimile cadre 830 ne recoit pas le minimum non cadre`() {
        val category = PlasturgieProtectionCategoryV2.classify("292", LocalDate.of(2026, 3, 31), 830)
        val estimate = ConventionProvidentCatalogV2.estimate(
            gross = 3000.0,
            year = 2026,
            idcc = "292",
            protectionCategory = category,
            seniorityMonths = 12,
            ceiling = partTime28hCeiling()
        )

        assertNull(estimate.lines.firstOrNull { it.id == "plasturgie_292_non_cadre_provident" })
        assertEquals(0.0, estimate.employeeDeductions, 0.001)
    }

    @Test
    fun `entree en cours de mois descend aussi dans la vieillesse plafonnee`() {
        val ceiling = SocialSecurityCeilingV2.calculate(
            SocialSecurityCeilingV2.Input(
                year = 2026,
                referenceDate = LocalDate.of(2026, 1, 31),
                contractType = ContractTypeV2.FULL_TIME,
                contractualWeeklyMinutes = 35 * 60,
                entryDate = LocalDate.of(2026, 1, 16)
            )
        )
        val estimate = SocialContributionCatalogV2.estimateEmployeeDeductions(5000.0, 2026, ceiling)
        val line = estimate.lines.first { it.id == "old_age_capped" }

        assertEquals(4005.0 * 16.0 / 31.0, line.baseAmount, 0.001)
    }
}
