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

    private fun defaultStatus(category: ProtectionCategoryV2.AniCategory): String = when (category) {
        ProtectionCategoryV2.AniCategory.ARTICLE_2_1 -> "CADRE"
        else -> "NON_CADRE"
    }

    private fun rule(
        id: String = "r1",
        category: ProtectionCategoryV2.AniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        selector: ConventionClassificationV2 = classification,
        status: String? = null,
        effectiveFrom: LocalDate = LocalDate.of(2025, 1, 1),
        extension: ConventionMinimumSalaryV2.ExtensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionDate: LocalDate? = LocalDate.of(2024, 12, 26)
    ) = ConventionProtectionCategoryV2.Rule(
        idcc = "292",
        ruleId = id,
        effectiveFrom = effectiveFrom,
        classification = selector,
        professionalStatus = status ?: defaultStatus(category),
        aniCategory = category,
        source = "Légifrance KALI — test",
        extensionStatus = extension,
        extensionEffectiveFrom = extensionDate
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
    fun `règle sans statut exact est structurellement refusée`() {
        val unsafe = ConventionProtectionCategoryV2.Rule(
            idcc = "292",
            ruleId = "unsafe",
            effectiveFrom = LocalDate.of(2025, 1, 1),
            classification = classification,
            professionalStatus = null,
            aniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
            source = "Légifrance KALI — test",
            extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
            extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
        )

        assertFalse(unsafe.structurallyValid())
    }

    @Test
    fun `TO_CONFIRM ne peut jamais devenir une règle fiable`() {
        val unsafe = ConventionProtectionCategoryV2.Rule(
            idcc = "292",
            ruleId = "unsafe-confirm",
            effectiveFrom = LocalDate.of(2025, 1, 1),
            classification = classification,
            professionalStatus = "NON_CADRE",
            aniCategory = ProtectionCategoryV2.AniCategory.TO_CONFIRM,
            source = "Légifrance KALI — test",
            extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
            extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
        )
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "NON_CADRE",
            rules = listOf(unsafe)
        )

        assertFalse(unsafe.structurallyValid())
        assertFalse(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.category.aniCategory)
    }

    @Test
    fun `catégorie et statut juridiquement incohérents sont refusés`() {
        val unsafe = rule(category = ProtectionCategoryV2.AniCategory.ARTICLE_2_2, status = "CADRE")
        assertFalse(unsafe.structurallyValid())
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
    fun `deux catégories non cadre contradictoires bloquent toute sélection`() {
        val nonCadreClassification = ConventionClassificationV2(coefficient = 830)
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = date,
            classification = nonCadreClassification,
            professionalStatus = "NON_CADRE",
            rules = listOf(
                rule(
                    id = "r1",
                    category = ProtectionCategoryV2.AniCategory.ARTICLE_2_2,
                    selector = nonCadreClassification
                ),
                rule(
                    id = "r2",
                    category = ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE,
                    selector = nonCadreClassification
                )
            )
        )

        assertFalse(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.category.aniCategory)
        assertTrue(result.warnings.any { it.contains("contradictoires") })
    }

    @Test
    fun `absence de règle n est fiable que si couverture PROVIDENT_CATEGORY prouve le bon périmètre`() {
        val scope = ConventionClassificationV2(level = "VII", echelon = "A")
        val record = ConventionMatterCoverageV2.Record(
            idcc = "1979",
            matter = ConventionMatterCoverageV2.Matter.PROVIDENT_CATEGORY,
            effectiveFrom = LocalDate.of(2026, 9, 1),
            effectiveTo = LocalDate.of(2026, 9, 30),
            classification = scope,
            professionalStatus = "CADRE",
            state = ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE,
            source = "Légifrance KALI — couverture exhaustive test",
            checkedAtMs = 1L
        )
        val coverage = ConventionMatterCoverageV2.Snapshot(
            state = ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE,
            record = record,
            reliable = true,
            warnings = emptyList()
        )
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "1979",
            referenceDate = date,
            classification = scope,
            professionalStatus = "CADRE",
            rules = emptyList(),
            coverage = coverage
        )

        assertTrue(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE, result.category.aniCategory)
        assertTrue(result.category.confirmed)
    }

    @Test
    fun `snapshot sans record ne peut jamais fabriquer une absence de règle`() {
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

        assertFalse(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.category.aniCategory)
    }

    @Test
    fun `couverture d une autre matière ne vaut jamais preuve d absence ANI`() {
        val scope = ConventionClassificationV2(level = "VII", echelon = "A")
        val record = ConventionMatterCoverageV2.Record(
            idcc = "1979",
            matter = ConventionMatterCoverageV2.Matter.MINIMUM_SALARY,
            effectiveFrom = LocalDate.of(2026, 9, 1),
            effectiveTo = LocalDate.of(2026, 9, 30),
            classification = scope,
            professionalStatus = "CADRE",
            state = ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE,
            source = "Légifrance KALI — test mauvaise matière",
            checkedAtMs = 1L
        )
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "1979",
            referenceDate = date,
            classification = scope,
            professionalStatus = "CADRE",
            rules = emptyList(),
            coverage = ConventionMatterCoverageV2.Snapshot(
                state = ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE,
                record = record,
                reliable = true,
                warnings = emptyList()
            )
        )

        assertFalse(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.category.aniCategory)
    }

    @Test
    fun `règle plus récente remplace une ancienne catégorie sans conflit historique`() {
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            rules = listOf(
                rule(id = "old", category = ProtectionCategoryV2.AniCategory.ARTICLE_2_1, effectiveFrom = LocalDate.of(2024, 1, 1)),
                rule(id = "new", category = ProtectionCategoryV2.AniCategory.ARTICLE_2_1, effectiveFrom = LocalDate.of(2025, 1, 1))
            )
        )

        assertTrue(result.reliable)
        assertEquals("new", result.selectedRule?.ruleId)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, result.category.aniCategory)
    }
}
