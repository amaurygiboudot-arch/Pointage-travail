package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.DecisionStatusV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MealBasketFactCompanyPeriodV2Test {
    private val day = LocalDate.of(2026, 9, 9)

    private fun companyFact(
        from: LocalDate?,
        to: LocalDate? = null,
        source: MealBasketFactJournalV2.Source = MealBasketFactJournalV2.Source.COMPANY_CONFIGURATION
    ) = MealBasketFactJournalV2.Entry(
        id = "canteen",
        companyId = "company",
        scope = MealBasketFactJournalV2.Scope.COMPANY,
        key = MealBasketFactJournalV2.Key.COMPANY_CANTEEN_AVAILABLE,
        value = MealBasketFactJournalV2.Value.Flag(true),
        source = source,
        status = DecisionStatusV2.CONFIRMED,
        recordedAtMs = 1L,
        effectiveFromEpochDay = from?.toEpochDay(),
        effectiveToEpochDay = to?.toEpochDay()
    )

    @Test
    fun `fait entreprise futur ne s applique pas au mois courant`() {
        val result = MealBasketFactJournalV2.resolve(
            listOf(companyFact(day.plusDays(1))), "company", day, "session"
        )

        assertNull(result.facts.companyCanteenAvailable)
        assertTrue(result.blockedKeys.isEmpty())
    }

    @Test
    fun `fait entreprise expire ne s applique pas au mois courant`() {
        val result = MealBasketFactJournalV2.resolve(
            listOf(companyFact(day.minusYears(1), day.minusDays(1))), "company", day, "session"
        )

        assertNull(result.facts.companyCanteenAvailable)
        assertTrue(result.blockedKeys.isEmpty())
    }

    @Test
    fun `fait entreprise actif est utilisable`() {
        val result = MealBasketFactJournalV2.resolve(
            listOf(companyFact(day.minusDays(1))), "company", day, "session"
        )

        assertTrue(result.facts.companyCanteenAvailable == true)
    }

    @Test
    fun `fait entreprise sans debut prouve est invalide et bloquant`() {
        val fact = companyFact(null)
        assertFalse(fact.structurallyValid())

        val result = MealBasketFactJournalV2.resolve(listOf(fact), "company", day, "session")
        assertNull(result.facts.companyCanteenAvailable)
        assertTrue(MealBasketFactJournalV2.Key.COMPANY_CANTEEN_AVAILABLE in result.blockedKeys)
    }

    @Test
    fun `evenement de session ne peut pas etre enregistre comme fait entreprise`() {
        val fact = companyFact(day.minusDays(1), source = MealBasketFactJournalV2.Source.SESSION_EVENT)
        assertFalse(fact.structurallyValid())
    }
}
