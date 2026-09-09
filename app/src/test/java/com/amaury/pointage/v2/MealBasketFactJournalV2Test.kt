package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MealBasketFactJournalV2Test {
    private val day = LocalDate.of(2026, 9, 9)

    private fun flag(
        id: String,
        scope: MealBasketFactJournalV2.Scope,
        key: MealBasketFactJournalV2.Key,
        value: Boolean,
        status: DecisionStatusV2 = DecisionStatusV2.CONFIRMED,
        dayEpochDay: Long? = null,
        sessionId: String? = null
    ) = MealBasketFactJournalV2.Entry(
        id = id,
        companyId = "company",
        scope = scope,
        key = key,
        value = MealBasketFactJournalV2.Value.Flag(value),
        source = MealBasketFactJournalV2.Source.USER_CONFIRMED,
        status = status,
        recordedAtMs = 1L,
        effectiveFromEpochDay = if (scope == MealBasketFactJournalV2.Scope.COMPANY) day.minusYears(1).toEpochDay() else null,
        dayEpochDay = dayEpochDay,
        sessionId = sessionId
    )

    @Test
    fun `un fait a confirmer ne nourrit jamais le salaire`() {
        val result = MealBasketFactJournalV2.resolve(
            entries = listOf(
                flag(
                    id = "pending",
                    scope = MealBasketFactJournalV2.Scope.COMPANY,
                    key = MealBasketFactJournalV2.Key.POSTED_SHIFT_WORKER,
                    value = true,
                    status = DecisionStatusV2.TO_CONFIRM
                )
            ),
            companyId = "company",
            day = day,
            sessionId = "session"
        )

        assertNull(result.facts.postedShiftWorker)
    }

    @Test
    fun `session prioritaire sur jour puis entreprise`() {
        val result = MealBasketFactJournalV2.resolve(
            entries = listOf(
                flag("company", MealBasketFactJournalV2.Scope.COMPANY, MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED, false),
                flag("day", MealBasketFactJournalV2.Scope.DAY, MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED, true, dayEpochDay = day.toEpochDay()),
                flag("session", MealBasketFactJournalV2.Scope.SESSION, MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED, false, sessionId = "session")
            ),
            companyId = "company",
            day = day,
            sessionId = "session"
        )

        assertEquals(false, result.facts.mealVoucherProvided)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `un autre jour ou une autre session ne contaminent pas le calcul`() {
        val result = MealBasketFactJournalV2.resolve(
            entries = listOf(
                flag("other-day", MealBasketFactJournalV2.Scope.DAY, MealBasketFactJournalV2.Key.EMPLOYER_MEAL_PROVIDED, true, dayEpochDay = day.minusDays(1).toEpochDay()),
                flag("other-session", MealBasketFactJournalV2.Scope.SESSION, MealBasketFactJournalV2.Key.WORKS_AWAY_FROM_USUAL_WORKPLACE, true, sessionId = "other")
            ),
            companyId = "company",
            day = day,
            sessionId = "session"
        )

        assertNull(result.facts.employerMealProvided)
        assertNull(result.facts.worksAwayFromUsualWorkplace)
    }

    @Test
    fun `deux faits confirmes contradictoires au meme scope restent inconnus`() {
        val result = MealBasketFactJournalV2.resolve(
            entries = listOf(
                flag("a", MealBasketFactJournalV2.Scope.SESSION, MealBasketFactJournalV2.Key.MUST_EAT_AT_WORKPLACE, true, sessionId = "session"),
                flag("b", MealBasketFactJournalV2.Scope.SESSION, MealBasketFactJournalV2.Key.MUST_EAT_AT_WORKPLACE, false, sessionId = "session")
            ),
            companyId = "company",
            day = day,
            sessionId = "session"
        )

        assertNull(result.facts.mustEatAtWorkplace)
        assertTrue(result.warnings.any { it.contains("contradictoires", ignoreCase = true) })
    }

    @Test
    fun `plage de nuit employeur conserve son type et sa portee`() {
        val entry = MealBasketFactJournalV2.Entry(
            id = "window",
            companyId = "company",
            scope = MealBasketFactJournalV2.Scope.COMPANY,
            key = MealBasketFactJournalV2.Key.EMPLOYER_NIGHT_WINDOW,
            value = MealBasketFactJournalV2.Value.NightWindow(ConventionMealBasketV2.DailyWindow(21 * 60, 6 * 60)),
            source = MealBasketFactJournalV2.Source.COMPANY_CONFIGURATION,
            status = DecisionStatusV2.CONFIRMED,
            recordedAtMs = 1L,
            effectiveFromEpochDay = day.minusYears(1).toEpochDay()
        )

        val result = MealBasketFactJournalV2.resolve(listOf(entry), "company", day, "session")
        assertEquals(21 * 60, result.facts.employerNightWindow?.startMinute)
        assertEquals(6 * 60, result.facts.employerNightWindow?.endMinute)
    }

    @Test
    fun `un type de valeur incompatible est invalide et ignore`() {
        val invalid = MealBasketFactJournalV2.Entry(
            id = "bad",
            companyId = "company",
            scope = MealBasketFactJournalV2.Scope.COMPANY,
            key = MealBasketFactJournalV2.Key.POSTED_SHIFT_WORKER,
            value = MealBasketFactJournalV2.Value.NightWindow(ConventionMealBasketV2.DailyWindow(21 * 60, 6 * 60)),
            source = MealBasketFactJournalV2.Source.IMPORT,
            status = DecisionStatusV2.CONFIRMED,
            recordedAtMs = 1L,
            effectiveFromEpochDay = day.minusYears(1).toEpochDay()
        )

        assertFalse(invalid.structurallyValid())
        val result = MealBasketFactJournalV2.resolve(listOf(invalid), "company", day, "session")
        assertNull(result.facts.postedShiftWorker)
        assertTrue(result.warnings.any { it.contains("invalide", ignoreCase = true) })
    }
}
