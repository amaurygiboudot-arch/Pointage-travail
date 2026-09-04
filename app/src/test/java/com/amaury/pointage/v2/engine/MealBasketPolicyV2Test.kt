package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MealBasketPolicyV2Test {
    private val zone = ZoneId.systemDefault()

    @Test
    fun `un seul panier par jour de poste matin meme avec plusieurs sessions`() {
        val sessions = listOf(
            session("a", at(4, 5, 8), at(4, 5, 15), "company"),
            session("b", at(4, 6, 30), at(4, 6, 30), "company"),
            session("c", at(5, 5, 0), at(5, 5, 0), "company"),
            session("d", at(6, 8, 0), at(6, 8, 0), "company")
        )

        val result = MealBasketPolicyV2.calculate(
            sessions = sessions,
            year = 2026,
            monthZeroBased = 8,
            acceptedEmployerIds = setOf("company"),
            amountPerBasket = 5.38,
            zoneId = zone
        )

        assertEquals(2, result.count)
        assertEquals(5.38, result.amountPerBasket!!, 0.0001)
        assertEquals(10.76, result.totalAmount!!, 0.0001)
    }

    @Test
    fun `ancienne entree quinze cinq est reparee avant de determiner le panier`() {
        val result = MealBasketPolicyV2.calculate(
            sessions = listOf(session("old", at(4, 5, 8), at(4, 5, 15), "company")),
            year = 2026,
            monthZeroBased = 8,
            acceptedEmployerIds = setOf("company"),
            amountPerBasket = 5.38,
            zoneId = zone
        )

        assertEquals(1, result.count)
        assertEquals(5.38, result.totalAmount!!, 0.0001)
    }

    @Test
    fun `autre entreprise et session ouverte ne donnent pas de panier`() {
        val closedOther = session("other", at(4, 5, 0), at(4, 5, 0), "other")
        val open = WorkSessionV2(
            id = "open",
            employerId = "company",
            realArrivalMs = at(4, 5, 0),
            countedEntryMs = at(4, 5, 0),
            countedExitMs = null,
            realExitMs = null,
            status = SessionStatusV2.OPEN
        )

        val result = MealBasketPolicyV2.calculate(
            listOf(closedOther, open), 2026, 8, setOf("company"), 5.38, zone
        )

        assertEquals(0, result.count)
        assertEquals(0.0, result.totalAmount!!, 0.0001)
    }

    @Test
    fun `nombre de paniers reste connu quand le montant manque`() {
        val result = MealBasketPolicyV2.calculate(
            listOf(session("a", at(4, 5, 0), at(4, 5, 0), "company")),
            2026, 8, setOf("company"), null, zone
        )

        assertEquals(1, result.count)
        assertNull(result.amountPerBasket)
        assertNull(result.totalAmount)
        assertTrue(result.warnings.any { it.contains("montant unitaire") })
    }

    private fun session(id: String, realEntry: Long, countedEntry: Long, employer: String) = WorkSessionV2(
        id = id,
        employerId = employer,
        realArrivalMs = realEntry,
        countedEntryMs = countedEntry,
        countedExitMs = realEntry + 8L * 60L * 60L * 1000L,
        realExitMs = realEntry + 8L * 60L * 60L * 1000L,
        status = SessionStatusV2.CLOSED
    )

    private fun at(day: Int, hour: Int, minute: Int): Long =
        LocalDate.of(2026, 9, day)
            .atTime(hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
