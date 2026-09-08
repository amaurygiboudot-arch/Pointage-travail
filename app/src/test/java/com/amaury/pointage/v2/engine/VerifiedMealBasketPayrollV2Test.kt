package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class VerifiedMealBasketPayrollV2Test {
    private val zone = ZoneId.of("Europe/Paris")

    private fun epoch(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 9, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun session(
        id: String,
        day: Int,
        startHour: Int,
        endHour: Int,
        startMinute: Int = 0,
        endMinute: Int = 0,
        pauses: List<PauseV2> = emptyList()
    ) = WorkSessionV2(
        id = id,
        employerId = "employer",
        realArrivalMs = epoch(day, startHour, startMinute),
        countedEntryMs = epoch(day, startHour, startMinute),
        countedExitMs = if (endHour >= startHour) epoch(day, endHour, endMinute) else epoch(day + 1, endHour, endMinute),
        realExitMs = if (endHour >= startHour) epoch(day, endHour, endMinute) else epoch(day + 1, endHour, endMinute),
        pauses = pauses,
        status = SessionStatusV2.CLOSED
    )

    private fun branchRule(
        benefitId: String = "MEAL_DAY_1",
        amount: Double = 6.25,
        countingUnit: ConventionMealBasketV2.CountingUnit = ConventionMealBasketV2.CountingUnit.WORKED_DAY,
        eligibility: List<ConventionMealBasketV2.Condition> = listOf(ConventionMealBasketV2.Condition.WorkedDay)
    ) = ConventionMealBasketV2.Rule(
        idcc = "292",
        ruleId = "KALI-MEAL-KALITEXT000000000001-$benefitId",
        benefitId = benefitId,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = ConventionClassificationV2(coefficient = 700),
        professionalStatus = "NON_CADRE",
        deliveryMode = ConventionMealBasketV2.DeliveryMode.CASH_ALLOWANCE,
        amountFormula = ConventionMealBasketV2.AmountFormula.FixedEuro(amount),
        eligibilityAnyOf = listOf(ConventionMealBasketV2.EligibilityGroup(eligibility)),
        countingUnit = countingUnit,
        maxAwardsPerCalendarDay = 1,
        source = "Légifrance KALI",
        conventionScopeKey = "KALITEXT000000000001",
        evidenceArticleIds = setOf("KALIARTI000000000001"),
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
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
    fun `deux sessions du meme jour ne doublent pas un panier par jour travaille`() {
        val rule = branchRule()
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(
                session("morning", 8, 6, 10),
                session("afternoon", 8, 14, 18)
            ),
            year = 2026,
            monthZeroBased = 8,
            acceptedEmployerIds = setOf("employer"),
            arbitration = arbitration(rule),
            zoneId = zone
        )

        assertTrue(result.reliable)
        assertEquals(1, result.count)
        assertEquals(6.25, result.totalAmount!!, 0.001)
    }

    @Test
    fun `pause retiree du travail effectif peut faire tomber sous seuil de nuit`() {
        val rule = branchRule(
            benefitId = "MEAL_NIGHT_1",
            amount = 10.0,
            countingUnit = ConventionMealBasketV2.CountingUnit.SHIFT,
            eligibility = listOf(
                ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInFixedWindow(
                    ConventionMealBasketV2.DailyWindow(22 * 60, 2 * 60),
                    minimumMinutes = 4 * 60
                )
            )
        )
        val pause = PauseV2(
            startMs = epoch(8, 23, 30),
            endMs = epoch(9, 0, 0),
            paid = true,
            source = EventSourceV2.MANUAL,
            status = DecisionStatusV2.CONFIRMED
        )
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(session("night", 8, 22, 2, pauses = listOf(pause))),
            year = 2026,
            monthZeroBased = 8,
            acceptedEmployerIds = setOf("employer"),
            arbitration = arbitration(rule),
            zoneId = zone
        )

        assertTrue(result.reliable)
        assertEquals(0, result.count)
        assertEquals(0.0, result.totalAmount!!, 0.001)
    }

    @Test
    fun `pause ouverte bloque le total au lieu de supposer sa duree`() {
        val rule = branchRule()
        val openPause = PauseV2(
            startMs = epoch(8, 10),
            endMs = null,
            paid = null,
            source = EventSourceV2.MANUAL,
            status = DecisionStatusV2.TO_CONFIRM
        )
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(session("open-pause", 8, 8, 16, pauses = listOf(openPause))),
            year = 2026,
            monthZeroBased = 8,
            acceptedEmployerIds = setOf("employer"),
            arbitration = arbitration(rule),
            zoneId = zone
        )

        assertFalse(result.reliable)
        assertNull(result.totalAmount)
        assertTrue(result.warnings.any { it.contains("pauses incomplets", ignoreCase = true) || it.contains("pauses", ignoreCase = true) })
    }

    @Test
    fun `fait travail poste inconnu bloque un panier qui en depend`() {
        val rule = branchRule(
            benefitId = "MEAL_SHIFT_1",
            eligibility = listOf(ConventionMealBasketV2.Condition.PostedShiftWorker)
        )
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(session("shift", 8, 6, 14)),
            year = 2026,
            monthZeroBased = 8,
            acceptedEmployerIds = setOf("employer"),
            arbitration = arbitration(rule),
            facts = VerifiedMealBasketPayrollV2.FactDefaults(postedShiftWorker = null),
            zoneId = zone
        )

        assertFalse(result.reliable)
        assertNull(result.totalAmount)
    }
}
