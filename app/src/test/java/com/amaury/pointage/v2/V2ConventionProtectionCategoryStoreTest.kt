package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V2ConventionProtectionCategoryStoreTest {
    private fun rule(
        category: ProtectionCategoryV2.AniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        extensionStatus: ConventionMinimumSalaryV2.ExtensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionDate: LocalDate? = LocalDate.of(2025, 1, 1),
        classification: ConventionClassificationV2 = ConventionClassificationV2(coefficient = 910),
        approvalStatus: ConventionProtectionCategoryV2.ApprovalStatus = ConventionProtectionCategoryV2.ApprovalStatus.APEC_APPROVED,
        approvalDate: LocalDate? = LocalDate.of(2024, 10, 9),
        approvalSource: String? = "Commission paritaire APEC — agrément test"
    ) = ConventionProtectionCategoryV2.Rule(
        idcc = "292",
        ruleId = "KALI-PROTECTION-CATEGORY-test",
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = classification,
        professionalStatus = if (category == ProtectionCategoryV2.AniCategory.ARTICLE_2_1) "CADRE" else "NON_CADRE",
        aniCategory = category,
        source = "Légifrance KALI — KALIARTI000000000001",
        extensionStatus = extensionStatus,
        extensionEffectiveFrom = extensionDate,
        approvalStatus = approvalStatus,
        approvalEffectiveFrom = approvalDate,
        approvalSource = approvalSource
    )

    @Test
    fun `preuve KALI plus APEC structuree peut entrer dans le store local`() {
        assertTrue(V2ConventionProtectionCategoryStore.acceptsVerifiedRule(rule()))
    }

    @Test
    fun `preuve KALI sans agrément APEC peut etre stockee comme preuve non applicable`() {
        assertTrue(
            V2ConventionProtectionCategoryStore.acceptsVerifiedRule(
                rule(
                    approvalStatus = ConventionProtectionCategoryV2.ApprovalStatus.APEC_REQUIRED_UNVERIFIED,
                    approvalDate = null,
                    approvalSource = null
                )
            )
        )
    }

    @Test
    fun `preuve officielle non etendue peut etre stockee sans devenir applicable`() {
        assertTrue(
            V2ConventionProtectionCategoryStore.acceptsVerifiedRule(
                rule(
                    extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED,
                    extensionDate = null
                )
            )
        )
    }

    @Test
    fun `extension inconnue peut rester une preuve locale mais le resolveur la bloquera`() {
        assertTrue(
            V2ConventionProtectionCategoryStore.acceptsVerifiedRule(
                rule(
                    extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN,
                    extensionDate = null
                )
            )
        )
    }

    @Test
    fun `agrément APEC annoncé sans date ni source est structurellement refuse`() {
        assertFalse(
            V2ConventionProtectionCategoryStore.acceptsVerifiedRule(
                rule(
                    approvalStatus = ConventionProtectionCategoryV2.ApprovalStatus.APEC_APPROVED,
                    approvalDate = null,
                    approvalSource = null
                )
            )
        )
    }

    @Test
    fun `TO_CONFIRM ne peut jamais etre persiste comme preuve`() {
        assertFalse(
            V2ConventionProtectionCategoryStore.acceptsVerifiedRule(
                rule(category = ProtectionCategoryV2.AniCategory.TO_CONFIRM)
            )
        )
    }

    @Test
    fun `absence conventionnelle ne peut jamais etre fabriquee par le store`() {
        assertFalse(
            V2ConventionProtectionCategoryStore.acceptsVerifiedRule(
                rule(category = ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE)
            )
        )
    }

    @Test
    fun `classification vide est refusee`() {
        assertFalse(
            V2ConventionProtectionCategoryStore.acceptsVerifiedRule(
                rule(classification = ConventionClassificationV2())
            )
        )
    }
}
