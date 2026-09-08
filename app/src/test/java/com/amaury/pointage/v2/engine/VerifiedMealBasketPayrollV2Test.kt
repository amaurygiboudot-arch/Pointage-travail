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

    private fun epoch(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun session(
        id: String,
        day: Int,
        startHour: Int,
        endHour: Int,
        startMinute: Int = 0,
        endMinute: Int = 0,
        pauses: List<PauseV2> = emptyList(),
        legacyFixedUnpaidPauseMs: Long = 0L
    ) = WorkSessionV2(
        id = id,
        employerId = "employer",
        realArrivalMs = epoch(day, startHour, startMinute),
        countedEntryMs = epoch(day, startHour, startMinute),
        countedExitMs = if (endHour >= startHour) epoch(day, endHour, endMinute) else epoch(day + 1, endHour, endMinute),
        realExitMs = if (endHour >= startHour) epoch(day, endHour, endMinute) else epoch(day + 1, endHour, endMinute),
        pauses = pauses,
        status = SessionStatusV2.CLOSED,
        legacyFixedUnpaidPauseMs = legacyFixedUnpaidPauseMs
    )

    private fun sessionOn(id: String, date: LocalDate, startHour: Int, endHour: Int) = WorkSessionV2(
        id = id,
        employerId = "employer",
        realArrivalMs = epoch(date, startHour),
        countedEntryMs = epoch(date, startHour),
        countedExitMs = epoch(date, endHour),
        realExitMs = epoch(date, endHour),
        status = SessionStatusV2.CLOSED
    )

    private fun branchRule(
        benefitId: String = "MEAL_DAY_1",
        amount: Double = 6.25,
        amountFormula: ConventionMealBasketV2.AmountFormula? = null,
        countingUnit: ConventionMealBasketV2.CountingUnit = ConventionMealBasketV2.CountingUnit.WORKED_DAY,
        eligibility: List<ConventionMealBasketV2.Condition> = listOf(ConventionMealBasketV2.Condition.WorkedDay)
    ) = ConventionMealBasketV2.Rule(
        idcc = "292",
        ruleId = "KALI-MEAL-V2C-KALITEXT000000000001-$benefitId",
        benefitId = benefitId,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = ConventionClassificationV2(coefficient = 700),
        professionalStatus = "NON_CADRE",
        deliveryMode = ConventionMealBasketV2.DeliveryMode.CASH_ALLOWANCE,
        amountFormula = amountFormula ?: ConventionMealBasketV2.AmountFormula.FixedEuro(amount),
        eligibilityAnyOf = listOf(ConventionMealBasketV2.EligibilityGroup(eligibility)),
        countingUnit = countingUnit,
        maxAwardsPerCalendarDay = 1,
        source = "Légifrance KALI",
        conventionScopeKey = "KALITEXT000000000001",
        evidenceArticleIds = setOf("KALIARTI000000000001"),
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    private fun arbitration(vararg rules: ConventionMealBasketV2.Rule) = MealBasketLegalArbitrationBridgeV2.Result(
        selected = rules.map { rule ->
            MealBasketLegalArbitrationBridgeV2.Selected(
                subject = MealBasketLegalArbitrationBridgeV2.subject(rule.benefitId),
                source = PayrollLegalArbitratorV2.Source.KALI,
                branchRule = rule
            )
        },
        reliable = true,
        warnings = emptyList()
    )

    @Test
    fun `deux sessions du meme jour ne doublent pas un panier par jour travaille`() {
        val rule = branchRule()
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(session("morning", 8, 6, 10), session("afternoon", 8, 14, 18)),
            year = 2026, monthZeroBased = 8, acceptedEmployerIds = setOf("employer"),
            arbitration = arbitration(rule), zoneId = zone
        )
        assertTrue(result.reliable)
        assertEquals(1, result.count)
        assertEquals(6.25, result.totalAmount!!, 0.001)
        assertEquals(6.25, result.unitAmount!!, 0.001)
    }

    @Test
    fun `montants jour et nuit differents ne produisent jamais un faux montant unitaire`() {
        val day = branchRule(benefitId = "MEAL_DAY_1", amount = 6.25)
        val night = branchRule(benefitId = "MEAL_NIGHT_1", amount = 10.0)
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(session("mixed", 8, 8, 16)), year = 2026, monthZeroBased = 8,
            acceptedEmployerIds = setOf("employer"), arbitration = arbitration(day, night), zoneId = zone
        )
        assertTrue(result.reliable)
        assertEquals(2, result.count)
        assertEquals(16.25, result.totalAmount!!, 0.001)
        assertNull(result.unitAmount)
    }

    @Test
    fun `pause retiree du travail effectif peut faire tomber sous seuil de nuit`() {
        val rule = branchRule(
            benefitId = "MEAL_NIGHT_1", amount = 10.0,
            countingUnit = ConventionMealBasketV2.CountingUnit.SHIFT,
            eligibility = listOf(
                ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInFixedWindow(
                    ConventionMealBasketV2.DailyWindow(22 * 60, 2 * 60), minimumMinutes = 4 * 60
                )
            )
        )
        val pause = PauseV2(
            startMs = epoch(8, 23, 30), endMs = epoch(9, 0, 0), paid = true,
            source = EventSourceV2.MANUAL, status = DecisionStatusV2.CONFIRMED
        )
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(session("night", 8, 22, 2, pauses = listOf(pause))),
            year = 2026, monthZeroBased = 8, acceptedEmployerIds = setOf("employer"),
            arbitration = arbitration(rule), zoneId = zone
        )
        assertTrue(result.reliable)
        assertEquals(0, result.count)
        assertEquals(0.0, result.totalAmount!!, 0.001)
    }

    @Test
    fun `pause fixe migree de position inconnue bloque un seuil potentiellement affecte`() {
        val rule = branchRule(
            benefitId = "MEAL_NIGHT_1", amount = 10.0,
            countingUnit = ConventionMealBasketV2.CountingUnit.SHIFT,
            eligibility = listOf(
                ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInFixedWindow(
                    ConventionMealBasketV2.DailyWindow(22 * 60, 2 * 60), minimumMinutes = 4 * 60
                )
            )
        )
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(session("legacy", 8, 22, 2, legacyFixedUnpaidPauseMs = 30 * 60_000L)),
            year = 2026, monthZeroBased = 8, acceptedEmployerIds = setOf("employer"),
            arbitration = arbitration(rule), zoneId = zone
        )
        assertFalse(result.reliable)
        assertNull(result.totalAmount)
    }

    @Test
    fun `pause inversee est une incoherence et ne disparait pas par clipping`() {
        val pause = PauseV2(
            startMs = epoch(8, 12), endMs = epoch(8, 11), paid = false,
            source = EventSourceV2.MANUAL, status = DecisionStatusV2.CONFIRMED
        )
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(session("reversed", 8, 8, 16, pauses = listOf(pause))),
            year = 2026, monthZeroBased = 8, acceptedEmployerIds = setOf("employer"),
            arbitration = arbitration(branchRule()), zoneId = zone
        )
        assertFalse(result.reliable)
        assertNull(result.totalAmount)
    }

    @Test
    fun `pause hors session est une incoherence et bloque le mois`() {
        val pause = PauseV2(
            startMs = epoch(8, 7), endMs = epoch(8, 7, 30), paid = false,
            source = EventSourceV2.MANUAL, status = DecisionStatusV2.CONFIRMED
        )
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(session("outside", 8, 8, 16, pauses = listOf(pause))),
            year = 2026, monthZeroBased = 8, acceptedEmployerIds = setOf("employer"),
            arbitration = arbitration(branchRule()), zoneId = zone
        )
        assertFalse(result.reliable)
        assertNull(result.totalAmount)
    }

    @Test
    fun `passage heure ete mesure la duree reelle et non trois heures murales`() {
        val date = LocalDate.of(2026, 3, 29)
        val rule = branchRule(
            benefitId = "MEAL_NIGHT_1", amount = 10.0,
            countingUnit = ConventionMealBasketV2.CountingUnit.SHIFT,
            eligibility = listOf(
                ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInFixedWindow(
                    ConventionMealBasketV2.DailyWindow(60, 4 * 60), minimumMinutes = 3 * 60
                )
            )
        )
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(sessionOn("dst", date, 1, 4)),
            year = 2026, monthZeroBased = 2, acceptedEmployerIds = setOf("employer"),
            arbitration = arbitration(rule), zoneId = zone
        )
        assertTrue(result.reliable)
        assertEquals(0, result.count)
        assertEquals(0.0, result.totalAmount!!, 0.001)
    }

    @Test
    fun `pause ouverte bloque le total au lieu de supposer sa duree`() {
        val rule = branchRule()
        val openPause = PauseV2(
            startMs = epoch(8, 10), endMs = null, paid = null,
            source = EventSourceV2.MANUAL, status = DecisionStatusV2.TO_CONFIRM
        )
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(session("open-pause", 8, 8, 16, pauses = listOf(openPause))),
            year = 2026, monthZeroBased = 8, acceptedEmployerIds = setOf("employer"),
            arbitration = arbitration(rule), zoneId = zone
        )
        assertFalse(result.reliable)
        assertNull(result.totalAmount)
        assertTrue(result.warnings.any { it.contains("pause", ignoreCase = true) })
    }

    @Test
    fun `fait travail poste inconnu bloque un panier qui en depend`() {
        val rule = branchRule(benefitId = "MEAL_SHIFT_1", eligibility = listOf(ConventionMealBasketV2.Condition.PostedShiftWorker))
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(session("shift", 8, 6, 14)), year = 2026, monthZeroBased = 8,
            acceptedEmployerIds = setOf("employer"), arbitration = arbitration(rule),
            facts = VerifiedMealBasketPayrollV2.FactDefaults(postedShiftWorker = null), zoneId = zone
        )
        assertFalse(result.reliable)
        assertNull(result.totalAmount)
    }

    @Test
    fun `montant externe jour ne peut jamais alimenter panier nuit`() {
        val day = branchRule(benefitId = "MEAL_DAY_1", amountFormula = ConventionMealBasketV2.AmountFormula.ExternalAgreementAmount)
        val night = branchRule(benefitId = "MEAL_NIGHT_1", amountFormula = ConventionMealBasketV2.AmountFormula.ExternalAgreementAmount)
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(session("day", 8, 8, 16)), year = 2026, monthZeroBased = 8,
            acceptedEmployerIds = setOf("employer"), arbitration = arbitration(day, night),
            amountContextsBySubject = mapOf("MEAL_DAY" to ConventionMealBasketEvaluatorV2.AmountContext(externalAgreementAmount = 7.0)),
            zoneId = zone
        )
        assertFalse(result.reliable)
        assertNull(result.totalAmount)
        assertTrue(result.warnings.any { it.contains("accord externe", ignoreCase = true) })
    }
}