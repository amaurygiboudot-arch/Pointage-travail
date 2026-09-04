package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartTimeComplementaryHoursV2Test {
    @Test
    fun twoHoursAbove28hArePaidAt10Percent() {
        val result = PartTimeComplementaryHoursV2.calculateWeek(
            contractualMinutes = 28 * 60,
            paidMinutes = 30 * 60,
            grossHourlyRate = 10.0
        )

        assertEquals(120, result.complementaryMinutes)
        assertEquals(22.0, result.grossToAdd, 0.001)
        assertEquals(120, result.tiers.single().minutes)
        assertEquals(1.10, result.tiers.single().multiplier, 0.001)
    }

    @Test
    fun fourHoursAbove28hUse10Then25Percent() {
        val result = PartTimeComplementaryHoursV2.calculateWeek(
            contractualMinutes = 28 * 60,
            paidMinutes = 32 * 60,
            grossHourlyRate = 10.0
        )

        assertEquals(240, result.complementaryMinutes)
        assertEquals(45.8, result.grossToAdd, 0.001)
        assertEquals(168, result.tiers[0].minutes)
        assertEquals(72, result.tiers[1].minutes)
        assertEquals(1.10, result.tiers[0].multiplier, 0.001)
        assertEquals(1.25, result.tiers[1].multiplier, 0.001)
        assertTrue(result.warnings.any { it.contains("1/10") })
    }

    @Test
    fun hoursBeyondOneThirdAreNeverDropped() {
        val result = PartTimeComplementaryHoursV2.calculateWeek(
            contractualMinutes = 15 * 60,
            paidMinutes = 22 * 60,
            grossHourlyRate = 12.0
        )

        assertTrue(result.tiers.any { it.label.contains("au-delà du tiers") })
        assertTrue(result.grossToAdd > 0.0)
        assertTrue(result.warnings.any { it.contains("supérieur au tiers") })
    }
}
