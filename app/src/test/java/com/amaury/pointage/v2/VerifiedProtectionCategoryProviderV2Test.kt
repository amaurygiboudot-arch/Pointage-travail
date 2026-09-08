package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class VerifiedProtectionCategoryProviderV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val classification = ConventionClassificationV2(coefficient = 910)
    private val scope = "KALITEXT000000000001"

    private fun profile() = ConventionLegalProfileV2(
        companyId = "c1",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = "CADRE",
        classification = classification,
        contractType = "CDI",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun rule(
        approved: Boolean = true,
        selector: ConventionClassificationV2 = classification
    ) = ConventionProtectionCategoryV2.Rule(
        idcc = "292",
        ruleId = "KALI-PROTECTION-CATEGORY-test",
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = selector,
        professionalStatus = "CADRE",
        aniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        source = "Légifrance KALI — KALIARTI000050828557",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1),
        conventionScopeKey = scope,
        approvalStatus = if (approved) {
            ConventionProtectionCategoryV2.ApprovalStatus.APEC_APPROVED
        } else {
            ConventionProtectionCategoryV2.ApprovalStatus.APEC_REQUIRED_UNVERIFIED
        },
        approvalEffectiveFrom = if (approved) LocalDate.of(2025, 1, 1) else null,
        approvalSource = if (approved) "https://commission-paritaire.apec.fr/test.pdf" else null,
        approvalScopeKey = if (approved) scope else null,
        approvalClassification = if (approved) selector else null,
        approvalAniCategory = if (approved) ProtectionCategoryV2.AniCategory.ARTICLE_2_1 else null
    )

    private fun coverage(
        state: ConventionMatterCoverageV2.State,
        authorities: Set<ConventionMatterCoverageV2.Authority>
    ): ConventionMatterCoverageV2.Snapshot {
        val record = ConventionMatterCoverageV2.Record(
            idcc = "292",
            matter = ConventionMatterCoverageV2.Matter.PROVIDENT_CATEGORY,
            effectiveFrom = LocalDate.of(2026, 9, 1),
            effectiveTo = LocalDate.of(2026, 9, 30),
            classification = classification,
            professionalStatus = "CADRE",
            state = state,
            source = "audit test",
            checkedAtMs = 1L,
            authorities = authorities
        )
        return ConventionMatterCoverageV2.Snapshot(
            state = state,
            record = record,
            reliable = state != ConventionMatterCoverageV2.State.INCOMPLETE,
            warnings = emptyList()
        )
    }

    @Test
    fun `règle KALI APEC approuvée devient catégorie générique fiable`() {
        val result = VerifiedProtectionCategoryProviderV2.resolve(profile(), date, listOf(rule()))

        assertTrue(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, result.category.aniCategory)
        assertTrue(result.category.confirmed)
    }

    @Test
    fun `preuve KALI sans APEC reste TO CONFIRM`() {
        val result = VerifiedProtectionCategoryProviderV2.resolve(profile(), date, listOf(rule(approved = false)))

        assertFalse(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.category.aniCategory)
    }

    @Test
    fun `absence de règle n est fiable que si KALI et APEC confirment ensemble`() {
        val result = VerifiedProtectionCategoryProviderV2.resolve(
            profile(),
            date,
            emptyList(),
            coverage(
                ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE,
                setOf(ConventionMatterCoverageV2.Authority.KALI, ConventionMatterCoverageV2.Authority.APEC)
            )
        )

        assertTrue(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE, result.category.aniCategory)
    }

    @Test
    fun `couverture KALI seule ne suffit jamais à conclure aucune règle`() {
        val result = VerifiedProtectionCategoryProviderV2.resolve(
            profile(),
            date,
            emptyList(),
            coverage(
                ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE,
                setOf(ConventionMatterCoverageV2.Authority.KALI)
            )
        )

        assertFalse(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.category.aniCategory)
    }

    @Test
    fun `règle approuvée pour classification voisine ne déborde jamais`() {
        val result = VerifiedProtectionCategoryProviderV2.resolve(
            profile(),
            date,
            listOf(rule(selector = ConventionClassificationV2(coefficient = 920)))
        )

        assertFalse(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.category.aniCategory)
    }
}
