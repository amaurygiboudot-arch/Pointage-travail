package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ApecProtectionCategoryApprovalV2Test {
    private val scope = "IDCC0493:CCN-2013:ACCORD-2024-06-28"
    private val classification = ConventionClassificationV2(level = "VI", echelon = "B")

    private fun kaliRule(
        idcc: String = "493",
        scopeKey: String? = scope,
        selector: ConventionClassificationV2 = classification,
        category: ProtectionCategoryV2.AniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_2,
        status: String = "NON_CADRE"
    ) = ConventionProtectionCategoryV2.Rule(
        idcc = idcc,
        ruleId = "KALI-PROTECTION-CATEGORY-test",
        effectiveFrom = LocalDate.of(2024, 7, 1),
        classification = selector,
        professionalStatus = status,
        aniCategory = category,
        source = "Légifrance KALI — test",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2024, 12, 1),
        conventionScopeKey = scopeKey
    )

    private fun decision(
        idcc: String = "493",
        scopeKey: String = scope,
        selector: ConventionClassificationV2 = classification,
        category: ProtectionCategoryV2.AniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_2,
        status: String = "NON_CADRE",
        applicableFrom: LocalDate? = LocalDate.of(2024, 11, 19)
    ) = ApecProtectionCategoryApprovalV2.Decision(
        idcc = idcc,
        decisionId = "APEC-2024-11-19-IDCC493",
        decisionDate = LocalDate.of(2024, 11, 19),
        applicableFrom = applicableFrom,
        scopeKey = scopeKey,
        classification = selector,
        professionalStatus = status,
        aniCategory = category,
        source = "Commission paritaire rattachée à l'APEC — agrément du 19 novembre 2024"
    )

    @Test
    fun `même périmètre classification statut et catégorie permettent le rapprochement`() {
        val result = ApecProtectionCategoryApprovalV2.approve(kaliRule(), decision())

        assertTrue(result.reliable)
        assertNotNull(result.rule)
        assertEquals(ConventionProtectionCategoryV2.ApprovalStatus.APEC_APPROVED, result.rule!!.approvalStatus)
        assertEquals(scope, result.rule!!.approvalScopeKey)
        assertEquals(classification.normalized(), result.rule!!.approvalClassification)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_2, result.rule!!.approvalAniCategory)
    }

    @Test
    fun `même IDCC mais avenant régional différent reste bloqué`() {
        val result = ApecProtectionCategoryApprovalV2.approve(
            kaliRule(),
            decision(scopeKey = "IDCC0493:CHAMPAGNE:ACCORD-2024-12-04")
        )

        assertFalse(result.reliable)
        assertNull(result.rule)
        assertTrue(result.warnings.any { it.contains("autre accord") })
    }

    @Test
    fun `IDCC différent reste bloqué`() {
        val result = ApecProtectionCategoryApprovalV2.approve(kaliRule(), decision(idcc = "292"))

        assertFalse(result.reliable)
        assertNull(result.rule)
    }

    @Test
    fun `classification voisine agréée ne valide jamais le salarié`() {
        val result = ApecProtectionCategoryApprovalV2.approve(
            kaliRule(),
            decision(selector = ConventionClassificationV2(level = "VI", echelon = "A"))
        )

        assertFalse(result.reliable)
        assertNull(result.rule)
    }

    @Test
    fun `catégorie APEC différente reste bloquée`() {
        val result = ApecProtectionCategoryApprovalV2.approve(
            kaliRule(),
            decision(category = ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE)
        )

        assertFalse(result.reliable)
        assertNull(result.rule)
    }

    @Test
    fun `statut APEC différent reste bloqué`() {
        val result = ApecProtectionCategoryApprovalV2.approve(
            kaliRule(),
            decision(status = "CADRE", category = ProtectionCategoryV2.AniCategory.ARTICLE_2_1)
        )

        assertFalse(result.reliable)
        assertNull(result.rule)
    }

    @Test
    fun `date de décision APEC ne devient jamais automatiquement date d applicabilité`() {
        val result = ApecProtectionCategoryApprovalV2.approve(
            kaliRule(),
            decision(applicableFrom = null)
        )

        assertFalse(result.reliable)
        assertNull(result.rule)
        assertTrue(result.warnings.any { it.contains("date d'applicabilité") })
    }

    @Test
    fun `règle KALI sans périmètre exact ne peut pas recevoir un agrément`() {
        val result = ApecProtectionCategoryApprovalV2.approve(
            kaliRule(scopeKey = null),
            decision()
        )

        assertFalse(result.reliable)
        assertNull(result.rule)
        assertTrue(result.warnings.any { it.contains("périmètre exact") })
    }
}
