package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProtectionCategoryV2Test {
    private val date = LocalDate.of(2026, 9, 30)

    @Test
    fun `Plasturgie 830 conserve exactement ANI 2 2`() {
        val legacy = PlasturgieProtectionCategoryV2.classify("292", date, 830)
        val generic = PlasturgieProtectionCategoryV2.toGeneric(legacy)

        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_2, generic.aniCategory)
        assertTrue(generic.confirmed)
        assertTrue(generic.conventionControlsAni)
        assertTrue(generic.aniBeneficiaryConfirmed)
    }

    @Test
    fun `Plasturgie 900 conserve exactement ANI 2 1`() {
        val generic = PlasturgieProtectionCategoryV2.classifyGeneric("0292", date, 900)

        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, generic.aniCategory)
        assertTrue(generic.aniBeneficiaryConfirmed)
    }

    @Test
    fun `autre convention ne fabrique aucun override ANI`() {
        val generic = PlasturgieProtectionCategoryV2.classifyGeneric("1979", date, 800)

        assertEquals(ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE, generic.aniCategory)
        assertTrue(generic.confirmed)
        assertFalse(generic.conventionControlsAni)
    }

    @Test
    fun `retraite générique garde la parité avec le wrapper Plasturgie`() {
        val legacy = PlasturgieProtectionCategoryV2.classify("292", date, 830)
        val generic = PlasturgieProtectionCategoryV2.toGeneric(legacy)

        val oldPath = ComplementaryRetirementCatalogV2.estimate(
            gross = 3500.0,
            year = 2026,
            professionalStatus = "NON_CADRE",
            protectionCategory = legacy
        )
        val genericPath = ComplementaryRetirementCatalogV2.estimateGeneric(
            gross = 3500.0,
            year = 2026,
            professionalStatus = "NON_CADRE",
            protectionCategory = generic
        )

        assertEquals(oldPath.employeeDeductions, genericPath.employeeDeductions, 0.000001)
        assertEquals(oldPath.employerContributions, genericPath.employerContributions, 0.000001)
        assertEquals(oldPath.lines.map { it.id }, genericPath.lines.map { it.id })
    }

    @Test
    fun `minimum ANI générique garde la parité avec le wrapper Plasturgie`() {
        val legacy = PlasturgieProtectionCategoryV2.classify("292", date, 830)
        val generic = PlasturgieProtectionCategoryV2.toGeneric(legacy)

        val oldPath = ProfessionalStatusContributionCatalogV2.estimate(
            gross = 3500.0,
            year = 2026,
            professionalStatus = "NON_CADRE",
            protectionCategory = legacy
        )
        val genericPath = ProfessionalStatusContributionCatalogV2.estimateGeneric(
            gross = 3500.0,
            year = 2026,
            professionalStatus = "NON_CADRE",
            protectionCategory = generic
        )

        assertEquals(oldPath.employerContributions, genericPath.employerContributions, 0.000001)
        assertEquals(oldPath.lines.map { it.id }, genericPath.lines.map { it.id })
    }

    @Test
    fun `sans override conventionnel le statut cadre reste le repli prudent`() {
        val generic = ProtectionCategoryV2.noConventionOverride()

        val retirement = ComplementaryRetirementCatalogV2.estimateGeneric(
            gross = 3500.0,
            year = 2026,
            professionalStatus = "CADRE",
            protectionCategory = generic
        )
        val status = ProfessionalStatusContributionCatalogV2.estimateGeneric(
            gross = 3500.0,
            year = 2026,
            professionalStatus = "CADRE",
            protectionCategory = generic
        )

        assertTrue(retirement.lines.any { it.id == "apec" })
        assertTrue(status.lines.any { it.id == "cadre_provident_employer_minimum" })
    }
}
