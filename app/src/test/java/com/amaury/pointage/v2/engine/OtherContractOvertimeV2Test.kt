package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherContractOvertimeV2Test {
    @Test
    fun `sans palier toutes les minutes au dessus du seuil restent payees provisoirement`() {
        val result = OtherContractOvertimeV2.calculate(
            regularWeeklyLimit = 35 * 60,
            paidWeeks = listOf(40 * 60),
            grossHourlyRate = 10.0,
            overtimeTiers = emptyList()
        )

        assertEquals(5 * 60, result.overtimeMinutes)
        assertEquals(55.0, result.overtimeGross, 0.0001)
        assertTrue(result.provisionalRateUsed)
        assertEquals(result.overtimeMinutes, result.tiers.sumOf { it.minutes })
        assertTrue(result.warnings.any { it.contains("+10 %") })
    }

    @Test
    fun `barème complet 25 puis 50 ne declenche aucun taux provisoire`() {
        val result = OtherContractOvertimeV2.calculate(
            regularWeeklyLimit = 35 * 60,
            paidWeeks = listOf(45 * 60),
            grossHourlyRate = 10.0,
            overtimeTiers = listOf(
                OvertimeTierV2(35 * 60, 43 * 60, 1.25),
                OvertimeTierV2(43 * 60, null, 1.50)
            )
        )

        assertEquals(10 * 60, result.overtimeMinutes)
        assertEquals(130.0, result.overtimeGross, 0.0001)
        assertFalse(result.provisionalRateUsed)
        assertTrue(result.warnings.isEmpty())
        assertEquals(result.overtimeMinutes, result.tiers.sumOf { it.minutes })
    }

    @Test
    fun `un trou avant un palier confirme est comble sans perdre de minutes`() {
        val result = OtherContractOvertimeV2.calculate(
            regularWeeklyLimit = 35 * 60,
            paidWeeks = listOf(41 * 60),
            grossHourlyRate = 10.0,
            overtimeTiers = listOf(
                OvertimeTierV2(39 * 60, null, 1.25)
            )
        )

        assertEquals(6 * 60, result.overtimeMinutes)
        assertEquals(69.0, result.overtimeGross, 0.0001)
        assertTrue(result.provisionalRateUsed)
        assertEquals(4 * 60, result.tiers.first { it.provisional }.minutes)
        assertEquals(2 * 60, result.tiers.first { !it.provisional }.minutes)
        assertEquals(result.overtimeMinutes, result.tiers.sumOf { it.minutes })
    }

    @Test
    fun `plusieurs semaines sont additionnees sans doublon`() {
        val result = OtherContractOvertimeV2.calculate(
            regularWeeklyLimit = 35 * 60,
            paidWeeks = listOf(37 * 60, 36 * 60, 35 * 60, 20 * 60),
            grossHourlyRate = 12.0,
            overtimeTiers = emptyList()
        )

        assertEquals(3 * 60, result.overtimeMinutes)
        assertEquals(39.6, result.overtimeGross, 0.0001)
        assertEquals(result.overtimeMinutes, result.tiers.sumOf { it.minutes })
    }

    @Test
    fun `aucune heure sup ne produit ni montant ni incertitude`() {
        val result = OtherContractOvertimeV2.calculate(
            regularWeeklyLimit = 35 * 60,
            paidWeeks = listOf(35 * 60, 30 * 60),
            grossHourlyRate = 10.0,
            overtimeTiers = emptyList()
        )

        assertEquals(0, result.overtimeMinutes)
        assertEquals(0.0, result.overtimeGross, 0.0001)
        assertFalse(result.provisionalRateUsed)
        assertTrue(result.tiers.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }
}
