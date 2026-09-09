package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V2MealBasketFactStoreCodecTest {
    @Test
    fun `encodage et decodage conservent provenance scope et valeurs`() {
        val entries = listOf(
            MealBasketFactJournalV2.Entry(
                id = "company-posted",
                companyId = "company",
                scope = MealBasketFactJournalV2.Scope.COMPANY,
                key = MealBasketFactJournalV2.Key.POSTED_SHIFT_WORKER,
                value = MealBasketFactJournalV2.Value.Flag(true),
                source = MealBasketFactJournalV2.Source.COMPANY_CONFIGURATION,
                status = DecisionStatusV2.CONFIRMED,
                recordedAtMs = 10L
            ),
            MealBasketFactJournalV2.Entry(
                id = "session-voucher",
                companyId = "company",
                scope = MealBasketFactJournalV2.Scope.SESSION,
                key = MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED,
                value = MealBasketFactJournalV2.Value.Flag(false),
                source = MealBasketFactJournalV2.Source.USER_CONFIRMED,
                status = DecisionStatusV2.CONFIRMED,
                recordedAtMs = 20L,
                sessionId = "session-1"
            ),
            MealBasketFactJournalV2.Entry(
                id = "night-window",
                companyId = "company",
                scope = MealBasketFactJournalV2.Scope.COMPANY,
                key = MealBasketFactJournalV2.Key.EMPLOYER_NIGHT_WINDOW,
                value = MealBasketFactJournalV2.Value.NightWindow(
                    ConventionMealBasketV2.DailyWindow(21 * 60, 6 * 60)
                ),
                source = MealBasketFactJournalV2.Source.COMPANY_CONFIGURATION,
                status = DecisionStatusV2.CONFIRMED,
                recordedAtMs = 30L
            )
        )

        val decoded = V2MealBasketFactStore.decode(V2MealBasketFactStore.encode(entries).toString())

        assertEquals(0, decoded.malformedCount)
        assertEquals(entries, decoded.entries)
    }

    @Test
    fun `entree illisible est signalee au lieu d etre transformee`() {
        val decoded = V2MealBasketFactStore.decode("[{\"id\":\"broken\"}]")

        assertEquals(1, decoded.malformedCount)
        assertTrue(decoded.entries.isEmpty())
    }

    @Test
    fun `json entierement invalide est signale`() {
        val decoded = V2MealBasketFactStore.decode("not-json")

        assertEquals(1, decoded.malformedCount)
        assertTrue(decoded.entries.isEmpty())
    }
}
