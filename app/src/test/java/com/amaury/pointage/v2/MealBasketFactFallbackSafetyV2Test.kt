package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.VerifiedMealBasketPayrollV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MealBasketFactFallbackSafetyV2Test {
    private val day = LocalDate.of(2026, 9, 9)

    private fun entry(
        id: String,
        scope: MealBasketFactJournalV2.Scope,
        value: Boolean,
        status: DecisionStatusV2,
        dayEpochDay: Long? = null,
        sessionId: String? = null
    ) = MealBasketFactJournalV2.Entry(
        id = id,
        companyId = "company",
        scope = scope,
        key = MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED,
        value = MealBasketFactJournalV2.Value.Flag(value),
        source = MealBasketFactJournalV2.Source.USER_CONFIRMED,
        status = status,
        recordedAtMs = 1L,
        effectiveFromEpochDay = if (scope == MealBasketFactJournalV2.Scope.COMPANY) day.minusYears(1).toEpochDay() else null,
        dayEpochDay = dayEpochDay,
        sessionId = sessionId
    )

    @Test
    fun `fait jour a confirmer masque le fait entreprise confirme`() {
        val resolution = MealBasketFactJournalV2.resolve(
            entries = listOf(
                entry("company", MealBasketFactJournalV2.Scope.COMPANY, false, DecisionStatusV2.CONFIRMED),
                entry("day", MealBasketFactJournalV2.Scope.DAY, true, DecisionStatusV2.TO_CONFIRM, dayEpochDay = day.toEpochDay())
            ),
            companyId = "company",
            day = day,
            sessionId = "session"
        )

        assertNull(resolution.facts.mealVoucherProvided)
        assertTrue(MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED in resolution.blockedKeys)
        assertTrue(resolution.warnings.any { it.contains("fallback moins précis interdit", ignoreCase = true) })
    }

    @Test
    fun `fallback explicite ne traverse pas une cle bloquee`() {
        val resolution = MealBasketFactJournalV2.resolve(
            entries = listOf(
                entry("session", MealBasketFactJournalV2.Scope.SESSION, true, DecisionStatusV2.TO_CONFIRM, sessionId = "session")
            ),
            companyId = "company",
            day = day,
            sessionId = "session"
        )
        val merged = MealBasketFactJournalV2.overlay(
            fallback = VerifiedMealBasketPayrollV2.FactDefaults(mealVoucherProvided = false),
            resolution = resolution
        )

        assertNull(merged.mealVoucherProvided)
    }

    @Test
    fun `inconnu de session masque entreprise seulement pour cette session`() {
        val company = entry(
            "company-confirmed",
            MealBasketFactJournalV2.Scope.COMPANY,
            true,
            DecisionStatusV2.CONFIRMED
        )
        val sessionUnknown = MealBasketFactJournalV2.Entry(
            id = "session-unknown",
            companyId = "company",
            scope = MealBasketFactJournalV2.Scope.SESSION,
            key = MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED,
            value = MealBasketFactJournalV2.Value.Unknown,
            source = MealBasketFactJournalV2.Source.USER_CONFIRMED,
            status = DecisionStatusV2.TO_CONFIRM,
            recordedAtMs = 2L,
            sessionId = "session-a"
        )

        val first = MealBasketFactJournalV2.resolve(
            entries = listOf(company, sessionUnknown),
            companyId = "company",
            day = day,
            sessionId = "session-a"
        )
        val second = MealBasketFactJournalV2.resolve(
            entries = listOf(company, sessionUnknown),
            companyId = "company",
            day = day,
            sessionId = "session-b"
        )

        assertNull(first.facts.mealVoucherProvided)
        assertTrue(MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED in first.blockedKeys)
        assertEquals(true, second.facts.mealVoucherProvided)
    }

    @Test
    fun `absence de fait local autorise encore le fallback explicite`() {
        val resolution = MealBasketFactJournalV2.resolve(
            entries = emptyList(),
            companyId = "company",
            day = day,
            sessionId = "session"
        )
        val merged = MealBasketFactJournalV2.overlay(
            fallback = VerifiedMealBasketPayrollV2.FactDefaults(mealVoucherProvided = false),
            resolution = resolution
        )

        assertTrue(merged.mealVoucherProvided == false)
    }
}
