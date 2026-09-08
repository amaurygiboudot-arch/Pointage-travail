package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProvidentContributionV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V2ConventionProvidentContributionStoreTest {
    private fun rule(
        classification: ConventionClassificationV2 = ConventionClassificationV2(coefficient = 700),
        professionalStatus: String? = "NON_CADRE",
        aniCategories: Set<ProtectionCategoryV2.AniCategory> = setOf(ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2),
        conventionScopeKey: String? = "KALITEXT000000000001",
        extensionStatus: ConventionMinimumSalaryV2.ExtensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom: LocalDate? = LocalDate.of(2025, 1, 1),
        employeeRate: Double = 0.004
    ) = ConventionProvidentContributionV2.Rule(
        idcc = "292",
        ruleId = "KALIARTI000000000001",
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = classification,
        professionalStatus = professionalStatus,
        aniCategories = aniCategories,
        tiers = listOf(
            ConventionProvidentContributionV2.SeniorityTier(
                3,
                listOf(
                    ConventionProvidentContributionV2.Band(
                        label = "jusqu'à 4 PMSS",
                        upperCeilingMultiple = 4.0,
                        employeeRate = employeeRate,
                        employerRate = 0.004
                    )
                )
            )
        ),
        source = "Légifrance KALI — KALIARTI000000000001",
        conventionScopeKey = conventionScopeKey,
        extensionStatus = extensionStatus,
        extensionEffectiveFrom = extensionEffectiveFrom
    )

    @Test
    fun `preuve KALI avec parent exact peut entrer dans le store local`() {
        assertTrue(V2ConventionProvidentContributionStore.acceptsVerifiedRule(rule()))
    }

    @Test
    fun `preuve sans KALITEXT parent exact est refusée`() {
        assertFalse(V2ConventionProvidentContributionStore.acceptsVerifiedRule(rule(conventionScopeKey = null)))
    }

    @Test
    fun `portée libre inventée est refusée`() {
        assertFalse(
            V2ConventionProvidentContributionStore.acceptsVerifiedRule(
                rule(conventionScopeKey = "IDCC0292:ACCORD-PREVOYANCE")
            )
        )
    }

    @Test
    fun `règle structurellement invalide est refusée`() {
        assertFalse(V2ConventionProvidentContributionStore.acceptsVerifiedRule(rule(employeeRate = 1.5)))
    }

    @Test
    fun `règle conventionnelle générale reste persistable si le texte la formule ainsi`() {
        assertTrue(
            V2ConventionProvidentContributionStore.acceptsVerifiedRule(
                rule(
                    classification = ConventionClassificationV2(),
                    professionalStatus = null,
                    aniCategories = emptySet()
                )
            )
        )
    }

    @Test
    fun `preuve non étendue reste stockable sans devenir applicable à toutes les entreprises`() {
        assertTrue(
            V2ConventionProvidentContributionStore.acceptsVerifiedRule(
                rule(
                    extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED,
                    extensionEffectiveFrom = null
                )
            )
        )
    }

    @Test
    fun `extension inconnue reste stockable comme preuve mais pas comme applicabilité`() {
        assertTrue(
            V2ConventionProvidentContributionStore.acceptsVerifiedRule(
                rule(
                    extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN,
                    extensionEffectiveFrom = null
                )
            )
        )
    }

    @Test
    fun `TO_CONFIRM ne peut jamais devenir une catégorie de règle persistée`() {
        assertFalse(
            V2ConventionProvidentContributionStore.acceptsVerifiedRule(
                rule(aniCategories = setOf(ProtectionCategoryV2.AniCategory.TO_CONFIRM))
            )
        )
    }

    @Test
    fun `NO_CONVENTION_OVERRIDE ne peut jamais être fabriqué par le store`() {
        assertFalse(
            V2ConventionProvidentContributionStore.acceptsVerifiedRule(
                rule(aniCategories = setOf(ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE))
            )
        )
    }
}
