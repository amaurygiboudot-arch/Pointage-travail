package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.MealBasketLegalArbitrationBridgeV2
import com.amaury.pointage.v2.engine.PayrollLegalArbitratorV2
import com.amaury.pointage.v2.engine.VerifiedMealBasketPayrollV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MealBasketFactAcquisitionPlannerV2Test {
    private fun branchRule(
        conditions: List<ConventionMealBasketV2.Condition>,
        blockers: Set<ConventionMealBasketV2.Blocker> = emptySet(),
        delivery: ConventionMealBasketV2.DeliveryMode = ConventionMealBasketV2.DeliveryMode.CASH_ALLOWANCE,
        benefitId: String = "MEAL_NIGHT_1"
    ) = ConventionMealBasketV2.Rule(
        idcc = "292",
        ruleId = "KALI-MEAL-V2C-KALITEXT000000000111-KALIARTI000000000111-1",
        benefitId = benefitId,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = ConventionClassificationV2(coefficient = 700),
        professionalStatus = "NON_CADRE",
        deliveryMode = delivery,
        amountFormula = ConventionMealBasketV2.AmountFormula.FixedEuro(8.0),
        eligibilityAnyOf = listOf(ConventionMealBasketV2.EligibilityGroup(conditions)),
        blockers = blockers,
        source = "Légifrance KALI",
        conventionScopeKey = "KALITEXT000000000111",
        evidenceArticleIds = setOf("KALIARTI000000000111"),
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    private fun arbitration(rule: ConventionMealBasketV2.Rule) =
        MealBasketLegalArbitrationBridgeV2.Result(
            selected = listOf(
                MealBasketLegalArbitrationBridgeV2.Selected(
                    subject = MealBasketLegalArbitrationBridgeV2.subject(rule.benefitId),
                    source = PayrollLegalArbitratorV2.Source.KALI,
                    branchRule = rule
                )
            ),
            reliable = true,
            warnings = emptyList()
        )

    @Test
    fun `conditions calculables depuis la session ne creent aucune question`() {
        val rule = branchRule(
            listOf(
                ConventionMealBasketV2.Condition.WorkedDay,
                ConventionMealBasketV2.Condition.ShiftEnclosesMidnight,
                ConventionMealBasketV2.Condition.ShiftStartsAtMidnight,
                ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInFixedWindow(
                    ConventionMealBasketV2.DailyWindow(21 * 60, 6 * 60), 240
                ),
                ConventionMealBasketV2.Condition.ShiftStartsOrEndsInWindow(
                    ConventionMealBasketV2.DailyWindow(21 * 60, 23 * 60)
                )
            )
        )

        val plan = MealBasketFactAcquisitionPlannerV2.plan(arbitration(rule))

        assertTrue(plan.reliable)
        assertTrue(plan.requirements.isEmpty())
    }

    @Test
    fun `travail poste et plage employeur sont des faits entreprise`() {
        val rule = branchRule(
            listOf(
                ConventionMealBasketV2.Condition.PostedShiftWorker,
                ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInEmployerWindow(
                    allowedEnvelope = ConventionMealBasketV2.DailyWindow(21 * 60, 6 * 60),
                    requiredWindowMinutes = 8 * 60,
                    minimumEffectiveMinutes = 4 * 60
                )
            )
        )

        val plan = MealBasketFactAcquisitionPlannerV2.plan(arbitration(rule))
        val pairs = plan.requirements.map { it.key to it.recommendedScope }.toSet()

        assertEquals(
            setOf(
                MealBasketFactJournalV2.Key.POSTED_SHIFT_WORKER to MealBasketFactJournalV2.Scope.COMPANY,
                MealBasketFactJournalV2.Key.EMPLOYER_NIGHT_WINDOW to MealBasketFactJournalV2.Scope.COMPANY
            ),
            pairs
        )
    }

    @Test
    fun `retour domicile chantier et repas sur place restent lies a la session`() {
        val rule = branchRule(
            listOf(
                ConventionMealBasketV2.Condition.UnableToReturnHomeForMeal,
                ConventionMealBasketV2.Condition.WorksAwayFromUsualWorkplace,
                ConventionMealBasketV2.Condition.MustEatAtWorkplace
            ),
            benefitId = "MEAL_AWAY_1"
        )

        val plan = MealBasketFactAcquisitionPlannerV2.plan(arbitration(rule))

        assertEquals(3, plan.requirements.size)
        assertTrue(plan.requirements.all { it.recommendedScope == MealBasketFactJournalV2.Scope.SESSION })
    }

    @Test
    fun `non cumuls demandent uniquement les avantages reellement cites`() {
        val rule = branchRule(
            conditions = listOf(ConventionMealBasketV2.Condition.WorkedDay),
            blockers = setOf(
                ConventionMealBasketV2.Blocker.COMPANY_CANTEEN,
                ConventionMealBasketV2.Blocker.MEAL_VOUCHER,
                ConventionMealBasketV2.Blocker.OTHER_SAME_NATURE_MEAL_BENEFIT
            )
        )

        val plan = MealBasketFactAcquisitionPlannerV2.plan(arbitration(rule))
        val pairs = plan.requirements.map { it.key to it.recommendedScope }.toSet()

        assertEquals(
            setOf(
                MealBasketFactJournalV2.Key.COMPANY_CANTEEN_AVAILABLE to MealBasketFactJournalV2.Scope.SESSION,
                MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED to MealBasketFactJournalV2.Scope.DAY,
                MealBasketFactJournalV2.Key.OTHER_SAME_NATURE_MEAL_BENEFIT to MealBasketFactJournalV2.Scope.DAY
            ),
            pairs
        )
    }

    @Test
    fun `repas employeur alternatif est demande une seule fois meme avec blocker`() {
        val rule = branchRule(
            conditions = listOf(ConventionMealBasketV2.Condition.WorkedDay),
            blockers = setOf(ConventionMealBasketV2.Blocker.EMPLOYER_PROVIDED_MEAL),
            delivery = ConventionMealBasketV2.DeliveryMode.EMPLOYER_MEAL_OR_CASH_IF_NOT_PROVIDED
        )

        val plan = MealBasketFactAcquisitionPlannerV2.plan(arbitration(rule))
        val employerMeal = plan.requirements.filter {
            it.key == MealBasketFactJournalV2.Key.EMPLOYER_MEAL_PROVIDED
        }

        assertEquals(1, employerMeal.size)
        assertEquals(MealBasketFactJournalV2.Scope.SESSION, employerMeal.single().recommendedScope)
    }

    @Test
    fun `arbitrage juridique incertain ne declenche aucune question factuelle`() {
        val plan = MealBasketFactAcquisitionPlannerV2.plan(
            MealBasketLegalArbitrationBridgeV2.Result(
                selected = emptyList(),
                reliable = false,
                warnings = listOf("KALI à confirmer")
            )
        )

        assertFalse(plan.reliable)
        assertTrue(plan.requirements.isEmpty())
        assertTrue(plan.warnings.any { it.contains("aucune question", ignoreCase = true) })
    }

    @Test
    fun `missing retire un fait deja confirme mais conserve une cle bloquee`() {
        val plan = MealBasketFactAcquisitionPlannerV2.Plan(
            requirements = listOf(
                MealBasketFactAcquisitionPlannerV2.Requirement(
                    key = MealBasketFactJournalV2.Key.POSTED_SHIFT_WORKER,
                    recommendedScope = MealBasketFactJournalV2.Scope.COMPANY,
                    subjects = setOf("MEAL_SHIFT"),
                    reason = "test"
                ),
                MealBasketFactAcquisitionPlannerV2.Requirement(
                    key = MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED,
                    recommendedScope = MealBasketFactJournalV2.Scope.DAY,
                    subjects = setOf("MEAL_DAY"),
                    reason = "test"
                )
            ),
            reliable = true,
            warnings = emptyList()
        )
        val resolution = MealBasketFactJournalV2.Resolution(
            facts = VerifiedMealBasketPayrollV2.FactDefaults(
                postedShiftWorker = true,
                mealVoucherProvided = false
            ),
            warnings = emptyList(),
            blockedKeys = setOf(MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED)
        )

        val missing = MealBasketFactAcquisitionPlannerV2.missing(plan, resolution)

        assertEquals(listOf(MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED), missing.map { it.key })
    }
}
