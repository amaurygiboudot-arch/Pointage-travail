package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ConventionMealBasketEvaluatorV2Test {
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 9, day, hour, minute)

    private fun facts(
        start: LocalDateTime,
        end: LocalDateTime,
        posted: Boolean? = null,
        canReturnHome: Boolean? = null,
        away: Boolean? = null,
        mustEatAtWork: Boolean? = null,
        canteen: Boolean? = false,
        employerMeal: Boolean? = false,
        voucher: Boolean? = false,
        otherBenefit: Boolean? = false,
        employerNightWindow: ConventionMealBasketV2.DailyWindow? = null
    ) = ConventionMealBasketEvaluatorV2.WorkFacts(
        shiftStart = start,
        shiftEnd = end,
        effectiveWork = listOf(ConventionMealBasketEvaluatorV2.WorkInterval(start, end)),
        postedShiftWorker = posted,
        canReturnHomeForMeal = canReturnHome,
        worksAwayFromUsualWorkplace = away,
        mustEatAtWorkplace = mustEatAtWork,
        companyCanteenAvailable = canteen,
        employerMealProvided = employerMeal,
        mealVoucherProvided = voucher,
        otherSameNatureMealBenefit = otherBenefit,
        employerNightWindow = employerNightWindow
    )

    private fun rule(
        benefitId: String,
        amount: ConventionMealBasketV2.AmountFormula,
        groups: List<ConventionMealBasketV2.EligibilityGroup>,
        blockers: Set<ConventionMealBasketV2.Blocker> = emptySet(),
        delivery: ConventionMealBasketV2.DeliveryMode = ConventionMealBasketV2.DeliveryMode.CASH_ALLOWANCE
    ) = ConventionMealBasketV2.Rule(
        idcc = "292",
        ruleId = "KALI-MEAL-KALITEXT000000000001-$benefitId",
        benefitId = benefitId,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = ConventionClassificationV2(coefficient = 700),
        professionalStatus = "NON_CADRE",
        deliveryMode = delivery,
        amountFormula = amount,
        eligibilityAnyOf = groups,
        blockers = blockers,
        source = "Légifrance KALI test",
        conventionScopeKey = "KALITEXT000000000001",
        evidenceArticleIds = setOf("KALIARTI000000000001"),
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    @Test
    fun `quatre heures dans la plage de nuit employeur ouvrent le panier MG`() {
        val night = rule(
            benefitId = "NIGHT_4H",
            amount = ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple(2.65),
            groups = listOf(
                ConventionMealBasketV2.EligibilityGroup(
                    listOf(
                        ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInEmployerWindow(
                            allowedEnvelope = ConventionMealBasketV2.DailyWindow(21 * 60, 6 * 60),
                            requiredWindowMinutes = 8 * 60,
                            minimumEffectiveMinutes = 4 * 60
                        )
                    )
                )
            )
        )
        val result = ConventionMealBasketEvaluatorV2.evaluate(
            night,
            facts(
                at(8, 22), at(9, 2),
                employerNightWindow = ConventionMealBasketV2.DailyWindow(21 * 60, 5 * 60)
            ),
            ConventionMealBasketEvaluatorV2.AmountContext(minimumGuaranteed = 4.0)
        )

        assertTrue(result.reliable)
        assertTrue(result.eligible == true)
        assertEquals(10.60, result.cashAmount!!, 0.001)
    }

    @Test
    fun `plage de nuit choisie par employeur inconnue bloque sans deviner`() {
        val night = rule(
            "NIGHT_EMPLOYER_WINDOW",
            ConventionMealBasketV2.AmountFormula.FixedEuro(10.0),
            listOf(ConventionMealBasketV2.EligibilityGroup(listOf(
                ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInEmployerWindow(
                    ConventionMealBasketV2.DailyWindow(21 * 60, 6 * 60), 8 * 60, 4 * 60
                )
            )))
        )

        val result = ConventionMealBasketEvaluatorV2.evaluate(night, facts(at(8, 22), at(9, 2)))
        assertFalse(result.reliable)
        assertNull(result.cashAmount)
    }

    @Test
    fun `poste jour finissant exactement à quatorze heures satisfait la plage douze quatorze`() {
        val day = rule(
            "DAY_POSTED",
            ConventionMealBasketV2.AmountFormula.ExternalAgreementAmount,
            listOf(ConventionMealBasketV2.EligibilityGroup(listOf(
                ConventionMealBasketV2.Condition.PostedShiftWorker,
                ConventionMealBasketV2.Condition.ShiftStartsOrEndsInWindow(
                    ConventionMealBasketV2.DailyWindow(12 * 60, 14 * 60)
                )
            )))
        )
        val result = ConventionMealBasketEvaluatorV2.evaluate(
            day,
            facts(at(8, 6), at(8, 14), posted = true),
            ConventionMealBasketEvaluatorV2.AmountContext(externalAgreementAmount = 6.25)
        )

        assertTrue(result.reliable)
        assertEquals(6.25, result.cashAmount!!, 0.001)
    }

    @Test
    fun `poste encadrant minuit ou partant de minuit sont deux alternatives distinctes`() {
        val night = rule(
            "MIDNIGHT",
            ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple(1.5),
            listOf(
                ConventionMealBasketV2.EligibilityGroup(listOf(
                    ConventionMealBasketV2.Condition.PostedShiftWorker,
                    ConventionMealBasketV2.Condition.ShiftEnclosesMidnight
                )),
                ConventionMealBasketV2.EligibilityGroup(listOf(
                    ConventionMealBasketV2.Condition.PostedShiftWorker,
                    ConventionMealBasketV2.Condition.ShiftStartsAtMidnight
                ))
            )
        )

        val enclosing = ConventionMealBasketEvaluatorV2.evaluate(
            night,
            facts(at(8, 20), at(9, 4), posted = true),
            ConventionMealBasketEvaluatorV2.AmountContext(minimumGuaranteed = 4.0)
        )
        val starting = ConventionMealBasketEvaluatorV2.evaluate(
            night,
            facts(at(9, 0), at(9, 8), posted = true),
            ConventionMealBasketEvaluatorV2.AmountContext(minimumGuaranteed = 4.0)
        )

        assertEquals(6.0, enclosing.cashAmount!!, 0.001)
        assertEquals(6.0, starting.cashAmount!!, 0.001)
    }

    @Test
    fun `impossibilité de rentrer déjeuner ouvre indemnité hors domicile`() {
        val travelMeal = rule(
            "AWAY_HOME",
            ConventionMealBasketV2.AmountFormula.FixedEuro(12.25),
            listOf(ConventionMealBasketV2.EligibilityGroup(listOf(
                ConventionMealBasketV2.Condition.WorkedDay,
                ConventionMealBasketV2.Condition.UnableToReturnHomeForMeal
            )))
        )

        val result = ConventionMealBasketEvaluatorV2.evaluate(
            travelMeal,
            facts(at(8, 8), at(8, 16), canReturnHome = false)
        )
        assertTrue(result.reliable)
        assertEquals(12.25, result.cashAmount!!, 0.001)
    }

    @Test
    fun `possibilité de rentrer déjeuner inconnue bloque le droit hors domicile`() {
        val travelMeal = rule(
            "AWAY_HOME_UNKNOWN",
            ConventionMealBasketV2.AmountFormula.FixedEuro(12.25),
            listOf(ConventionMealBasketV2.EligibilityGroup(listOf(
                ConventionMealBasketV2.Condition.WorkedDay,
                ConventionMealBasketV2.Condition.UnableToReturnHomeForMeal
            )))
        )
        val result = ConventionMealBasketEvaluatorV2.evaluate(
            travelMeal,
            facts(at(8, 8), at(8, 16), canReturnHome = null)
        )

        assertFalse(result.reliable)
        assertNull(result.cashAmount)
    }

    @Test
    fun `cantine ou titre restaurant non cumulable bloque le panier`() {
        val day = rule(
            "NON_CUMUL",
            ConventionMealBasketV2.AmountFormula.FixedEuro(7.0),
            listOf(ConventionMealBasketV2.EligibilityGroup(listOf(ConventionMealBasketV2.Condition.WorkedDay))),
            blockers = setOf(
                ConventionMealBasketV2.Blocker.COMPANY_CANTEEN,
                ConventionMealBasketV2.Blocker.MEAL_VOUCHER
            )
        )

        val result = ConventionMealBasketEvaluatorV2.evaluate(
            day,
            facts(at(8, 8), at(8, 16), canteen = true, voucher = false)
        )
        assertTrue(result.reliable)
        assertTrue(result.eligible == false)
        assertEquals(0.0, result.cashAmount!!, 0.001)
    }

    @Test
    fun `minimum garanti manquant confirme le droit mais jamais le montant`() {
        val night = rule(
            "MG_UNKNOWN",
            ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple(1.5),
            listOf(ConventionMealBasketV2.EligibilityGroup(listOf(ConventionMealBasketV2.Condition.WorkedDay)))
        )
        val result = ConventionMealBasketEvaluatorV2.evaluate(night, facts(at(8, 22), at(9, 4)))

        assertTrue(result.eligibilityConfirmed)
        assertTrue(result.eligible == true)
        assertFalse(result.reliable)
        assertNull(result.cashAmount)
    }

    @Test
    fun `repas fourni remplace indemnité seulement quand le texte le prévoit`() {
        val alternative = rule(
            "MEAL_OR_CASH",
            ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple(2.65),
            listOf(ConventionMealBasketV2.EligibilityGroup(listOf(ConventionMealBasketV2.Condition.WorkedDay))),
            delivery = ConventionMealBasketV2.DeliveryMode.EMPLOYER_MEAL_OR_CASH_IF_NOT_PROVIDED
        )
        val result = ConventionMealBasketEvaluatorV2.evaluate(
            alternative,
            facts(at(8, 22), at(9, 4), employerMeal = true)
        )

        assertTrue(result.reliable)
        assertTrue(result.eligible == true)
        assertEquals(0.0, result.cashAmount!!, 0.001)
    }
}
