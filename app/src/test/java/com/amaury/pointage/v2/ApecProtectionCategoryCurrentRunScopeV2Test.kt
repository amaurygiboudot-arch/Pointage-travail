package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ApecProtectionCategoryCurrentRunScopeV2Test {
    private val referenceDate = LocalDate.of(2026, 9, 8)

    private val profile = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = "CADRE",
        classification = ConventionClassificationV2(coefficient = 910),
        contractType = "CDI",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun rule(id: String, scope: String) = ConventionProtectionCategoryV2.Rule(
        idcc = "292",
        ruleId = id,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = ConventionClassificationV2(coefficient = 910),
        professionalStatus = "CADRE",
        aniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        source = "Légifrance KALI — test",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1),
        conventionScopeKey = scope
    )

    @Test
    fun `une ancienne règle locale exacte mais non sauvegardée dans ce KALI est exclue`() {
        val current = rule("current", "KALITEXT000000000001")
        val stale = rule("stale", "KALITEXT000000000002")

        val selected = ApecProtectionCategoryAuditV2.exactProfileRules(
            profile = profile,
            referenceDate = referenceDate,
            rules = listOf(stale, current),
            savedRuleIds = setOf("current")
        )

        assertEquals(1, selected.size)
        assertEquals("current", selected.single().ruleId)
        assertTrue(selected.none { it.ruleId == "stale" })
    }

    @Test
    fun `liste vide du KALI courant interdit toute reprise du store`() {
        val selected = ApecProtectionCategoryAuditV2.exactProfileRules(
            profile = profile,
            referenceDate = referenceDate,
            rules = listOf(rule("stale", "KALITEXT000000000002")),
            savedRuleIds = emptySet()
        )

        assertTrue(selected.isEmpty())
    }
}
