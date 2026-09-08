package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ConventionProtectionCategoryV2Test {
    private val date = LocalDate.of(2026, 9, 30)
    private val classification = ConventionClassificationV2(coefficient = 910)

    private fun rule(
        id: String = "r1",
        category: ProtectionCategoryV2.AniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        selector: ConventionClassificationV2 = classification,
        status: String? = "CADRE",
        effectiveFrom: LocalDate = LocalDate.of(2025, 1, 1),
        extension: ConventionMinimumSalaryV2.ExtensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionDate: LocalDate? = LocalDate.of(2024, 12, 26),
        condition: String? = null,
        conditionConfirmed: Boolean = condition == null
    ) = ConventionProtectionCategoryV2.Rule(
        idcc = "292",
        ruleId = id,
        effectiveFrom = effectiveFrom,
        classification = selector,
        professionalStatus = status,
        aniCategory = category,
        source = "Légifrance KALI — test",
        extensionStatus = extension,
        extensionEffectiveFrom = extensionDate,
        additionalApplicabilityCondition = condition,
        additionalApplicabilityConfirmed = conditionConfirmed
    )

    @Test
    fun `règle exacte étendue confirme la catégorie`() {
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "0292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            rules = listOf(rule())
        )

        assertTrue(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, result.category.aniCategory)
        assertTrue(result.category.confirmed)
        assertEquals("r1", result.selectedRule?.ruleId)
    }

    @Test
    fun `classification voisine ne reçoit jamais la règle`() {
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = date,
            classification = ConventionClassificationV2(coefficient = 900),
            professionalStatus = "CADRE",
            rules = listOf(rule())
        )

        assertFalse(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.category.aniCategory)
        assertNull(result.selectedRule)
    }

    @Test
    fun `mauvais statut ne reçoit jamais la règle`() {
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "NON_CADRE",
            rules = listOf(rule())
        )

        assertFalse(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.category.aniCategory)
    }

    @Test
    fun `texte non étendu bloque le classement automatique`() {
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            rules = listOf(
                rule(
                    extension = ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED,
                    extensionDate = null
                )
            )
        )

        assertFalse(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.category.aniCategory)
    }

    @Test
    fun `extension future bloque la période antérieure`() {
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = LocalDate.of(2025, 1, 15),
            classification = classification,
            professionalStatus = "CADRE",
            rules = listOf(rule(extensionDate = LocalDate.of(2025, 2, 1)))
        )

        assertFalse(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.category.aniCategory)
    }

    @Test
    fun `agrément externe non confirmé bloque malgré texte étendu`() {
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            rules = listOf(
                rule(
                    condition = "Agrément APEC requis par le texte source",
                    conditionConfirmed = false
                )
            )
        )

        assertFalse(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.category.aniCategory)
        assertTrue(result.warnings.any { it.contains("Agrément APEC") })
    }

    @Test
    fun `agrément externe confirmé permet ensuite la règle`() {
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            rules = listOf(
                rule(
                    condition = "Agrément APEC requis par le texte source",
                    conditionConfirmed = true
                )
            )
        )

        assertTrue(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, result.category.aniCategory)
    }

    @Test
    fun `deux catégories contradictoires bloquent toute sélection`() {
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            rules = listOf(
                rule(id = "r1", category = ProtectionCategoryV2.AniCategory.ARTICLE_2_1),
                rule(id = "r2", category = ProtectionCategoryV2.AniCategory.ARTICLE_2_2)
            )
        )

        assertFalse(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.category.aniCategory)
        assertTrue(result.warnings.any { it.contains("contradictoires") })
    }

    @Test
    fun `absence de règle n est fiable que si couverture exhaustive confirme aucune règle`() {
        val coverage = ConventionMatterCoverageV2.Snapshot(
            state = ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE,
            record = null,
            reliable = true,
            warnings = emptyList()
        )
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "1979",
            referenceDate = date,
            classification = ConventionClassificationV2(level = "VII", echelon = "A"),
            professionalStatus = "CADRE",
            rules = emptyList(),
            coverage = coverage
        )

        assertTrue(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE, result.category.aniCategory)
        assertTrue(result.category.confirmed)
    }

    @Test
    fun `règle plus récente remplace une ancienne catégorie sans conflit historique`() {
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            rules = listOf(
                rule(id = "old", category = ProtectionCategoryV2.AniCategory.ARTICLE_2_2, effectiveFrom = LocalDate.of(2024, 1, 1)),
                rule(id = "new", category = ProtectionCategoryV2.AniCategory.ARTICLE_2_1, effectiveFrom = LocalDate.of(2025, 1, 1))
            )
        )

        assertTrue(result.reliable)
        assertEquals("new", result.selectedRule?.ruleId)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, result.category.aniCategory)
    }
}
