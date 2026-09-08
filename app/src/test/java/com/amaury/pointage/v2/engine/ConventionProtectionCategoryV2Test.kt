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

    private fun approval(
        date: LocalDate = LocalDate.of(2024, 10, 9),
        authority: ConventionProtectionCategoryV2.ApprovalAuthority = ConventionProtectionCategoryV2.ApprovalAuthority.APEC
    ) = ConventionProtectionCategoryV2.ApprovalEvidence(
        authority = authority,
        approvedOn = date,
        source = "$authority — décision test"
    )

    private fun rule(
        id: String = "r1",
        category: ProtectionCategoryV2.AniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        selector: ConventionClassificationV2 = classification,
        status: String? = "CADRE",
        effectiveFrom: LocalDate = LocalDate.of(2025, 1, 1),
        extension: ConventionMinimumSalaryV2.ExtensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionDate: LocalDate? = LocalDate.of(2024, 12, 26),
        approvalRequired: Boolean = true,
        approvalEvidence: ConventionProtectionCategoryV2.ApprovalEvidence? = approval()
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
        approvalRequired = approvalRequired,
        approvalEvidence = approvalEvidence
    )

    @Test
    fun `regle exacte etendue et agreee confirme la categorie`() {
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
        assertTrue(result.category.source.orEmpty().contains("APEC"))
    }

    @Test
    fun `agrement manquant bloque meme une regle KALI etendue`() {
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            rules = listOf(rule(approvalEvidence = null))
        )

        assertFalse(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.category.aniCategory)
        assertTrue(result.warnings.any { it.contains("agrément", ignoreCase = true) })
    }

    @Test
    fun `agrement futur bloque la date anterieure`() {
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = LocalDate.of(2025, 1, 15),
            classification = classification,
            professionalStatus = "CADRE",
            rules = listOf(rule(approvalEvidence = approval(LocalDate.of(2025, 2, 1))))
        )

        assertFalse(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.category.aniCategory)
    }

    @Test
    fun `ancien agrement AGIRC peut etre conserve comme preuve historique`() {
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            rules = listOf(
                rule(
                    approvalEvidence = approval(
                        LocalDate.of(2019, 1, 1),
                        ConventionProtectionCategoryV2.ApprovalAuthority.AGIRC
                    )
                )
            )
        )
        assertTrue(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, result.category.aniCategory)
    }

    @Test
    fun `classification voisine ne recoit jamais la regle`() {
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
    fun `texte non etendu bloque le classement automatique`() {
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
    fun `extension future bloque la periode anterieure`() {
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = LocalDate.of(2025, 1, 15),
            classification = classification,
            professionalStatus = "CADRE",
            rules = listOf(rule(extensionDate = LocalDate.of(2025, 2, 1)))
        )

        assertFalse(result.reliable)
    }

    @Test
    fun `deux categories contradictoires bloquent toute selection`() {
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
    fun `absence de regle n est fiable que si couverture exhaustive confirme aucune regle`() {
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
    }

    @Test
    fun `extension eligible reste hors beneficiaires ANI meme agreee`() {
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = date,
            classification = ConventionClassificationV2(coefficient = 810),
            professionalStatus = "NON_CADRE",
            rules = listOf(
                rule(
                    selector = ConventionClassificationV2(coefficient = 810),
                    status = "NON_CADRE",
                    category = ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE
                )
            )
        )

        assertTrue(result.reliable)
        assertFalse(result.category.aniBeneficiaryConfirmed)
        assertTrue(result.warnings.isNotEmpty())
    }
}
