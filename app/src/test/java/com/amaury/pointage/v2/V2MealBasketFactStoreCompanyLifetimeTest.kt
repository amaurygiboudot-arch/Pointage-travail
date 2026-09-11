package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.DecisionStatusV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V2MealBasketFactStoreCompanyLifetimeTest {
    private val day = LocalDate.of(2026, 9, 11).toEpochDay()

    private fun entry(
        id: String,
        companyId: String,
        scope: MealBasketFactJournalV2.Scope
    ) = MealBasketFactJournalV2.Entry(
        id = id,
        companyId = companyId,
        scope = scope,
        key = MealBasketFactJournalV2.Key.POSTED_SHIFT_WORKER,
        value = MealBasketFactJournalV2.Value.Flag(true),
        source = MealBasketFactJournalV2.Source.USER_CONFIRMED,
        status = DecisionStatusV2.CONFIRMED,
        recordedAtMs = 1L,
        effectiveFromEpochDay = day.takeIf { scope == MealBasketFactJournalV2.Scope.COMPANY },
        dayEpochDay = day.takeIf { scope == MealBasketFactJournalV2.Scope.DAY },
        sessionId = "session-1".takeIf { scope == MealBasketFactJournalV2.Scope.SESSION }
    )

    @Test
    fun `suppression d un fait entreprise conserve l entreprise comme garde`() {
        val existing = listOf(entry("company-fact", "company-a", MealBasketFactJournalV2.Scope.COMPANY))

        val ids = V2MealBasketFactStore.companyIdsForMutation(
            existing = existing,
            removeEntryIds = setOf("company-fact"),
            replacements = emptyList()
        )

        assertEquals(setOf("company-a"), ids)
    }

    @Test
    fun `ajout d un fait entreprise exige l entreprise cible`() {
        val replacement = entry("company-fact", "company-a", MealBasketFactJournalV2.Scope.COMPANY)

        val ids = V2MealBasketFactStore.companyIdsForMutation(
            existing = emptyList(),
            removeEntryIds = emptySet(),
            replacements = listOf(replacement)
        )

        assertEquals(setOf("company-a"), ids)
    }

    @Test
    fun `faits jour et session ne sont pas traites comme configuration entreprise`() {
        val replacements = listOf(
            entry("day-fact", "company-a", MealBasketFactJournalV2.Scope.DAY),
            entry("session-fact", "company-a", MealBasketFactJournalV2.Scope.SESSION)
        )

        val ids = V2MealBasketFactStore.companyIdsForMutation(
            existing = emptyList(),
            removeEntryIds = emptySet(),
            replacements = replacements
        )

        assertTrue(ids.isEmpty())
    }

    @Test
    fun `mutation de faits entreprise appartenant a deux entreprises reste ambigue`() {
        val existing = listOf(
            entry("fact-a", "company-a", MealBasketFactJournalV2.Scope.COMPANY),
            entry("fact-b", "company-b", MealBasketFactJournalV2.Scope.COMPANY)
        )

        val ids = V2MealBasketFactStore.companyIdsForMutation(
            existing = existing,
            removeEntryIds = setOf("fact-a", "fact-b"),
            replacements = emptyList()
        )

        assertEquals(setOf("company-a", "company-b"), ids)
    }
}
