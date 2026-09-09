package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class VerifiedMealBasketPayrollSessionFactsV2Test {
    private val zone = ZoneId.of("Europe/Paris")

    private fun epoch(day: Int, hour: Int): Long =
        LocalDateTime.of(2026, 9, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun session(id: String, start: Int, end: Int) = WorkSessionV2(
        id = id,
        employerId = "employer",
        realArrivalMs = epoch(9, start),
        countedEntryMs = epoch(9, start),
        countedExitMs = epoch(9, end),
        realExitMs = epoch(9, end),
        status = SessionStatusV2.CLOSED
    )

    private fun postedRule() = ConventionMealBasketV2.Rule(
        idcc = "292",
        ruleId = "KALI-MEAL-V2C-KALITEXT000000000001-SHIFT",
        benefitId = "MEAL_SHIFT_1",
        effectiveFrom = java.time.LocalDate.of(2025, 1, 1),
        classification = ConventionClassificationV2(coefficient = 700),
        professionalStatus = "NON_CADRE",
        deliveryMode = ConventionMealBasketV2.DeliveryMode.CASH_ALLOWANCE,
        amountFormula = ConventionMealBasketV2.AmountFormula.FixedEuro(8.0),
        eligibilityAnyOf = listOf(
            ConventionMealBasketV2.EligibilityGroup(
                listOf(ConventionMealBasketV2.Condition.PostedShiftWorker)
            )
        ),
        countingUnit = ConventionMealBasketV2.CountingUnit.SHIFT,
        maxAwardsPerCalendarDay = 2,
        source = "Légifrance KALI",
        conventionScopeKey = "KALITEXT000000000001",
        evidenceArticleIds = setOf("KALIARTI000000000001"),
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = java.time.LocalDate.of(2025, 1, 1)
    )

    private fun arbitration(rule: ConventionMealBasketV2.Rule) = MealBasketLegalArbitrationBridgeV2.Result(
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
    fun `un fait confirme sur une session ne contamine pas la suivante`() {
        val first = session("first", 6, 10)
        val second = session("second", 14, 18)
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(first, second),
            year = 2026,
            monthZeroBased = 8,
            acceptedEmployerIds = setOf("employer"),
            arbitration = arbitration(postedRule()),
            facts = VerifiedMealBasketPayrollV2.FactDefaults(postedShiftWorker = false),
            zoneId = zone,
            factsBySessionId = mapOf(
                first.id to VerifiedMealBasketPayrollV2.FactDefaults(postedShiftWorker = true)
            )
        )

        assertTrue(result.reliable)
        assertEquals(1, result.count)
        assertEquals(8.0, result.totalAmount!!, 0.001)
    }

    @Test
    fun `faits differents de deux sessions sont evalues independamment`() {
        val first = session("first", 6, 10)
        val second = session("second", 14, 18)
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(first, second),
            year = 2026,
            monthZeroBased = 8,
            acceptedEmployerIds = setOf("employer"),
            arbitration = arbitration(postedRule()),
            zoneId = zone,
            factsBySessionId = mapOf(
                first.id to VerifiedMealBasketPayrollV2.FactDefaults(postedShiftWorker = true),
                second.id to VerifiedMealBasketPayrollV2.FactDefaults(postedShiftWorker = false)
            )
        )

        assertTrue(result.reliable)
        assertEquals(1, result.count)
        assertEquals(8.0, result.totalAmount!!, 0.001)
    }
}
