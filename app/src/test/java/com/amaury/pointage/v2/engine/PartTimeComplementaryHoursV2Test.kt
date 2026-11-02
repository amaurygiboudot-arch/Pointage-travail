package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartTimeComplementaryHoursV2Test {
    @Test
    fun sourcedDatedScheduleReplacesFallbackOnlyWithCompleteMatchingProof() {
        val schedule = PartTimeComplementaryHoursV2.ConfirmedSchedule(
            "official-test-source", 1200, 100, 200,
            listOf(OvertimeTierV2(1200, 1260, 1.20), OvertimeTierV2(1260, 1320, 1.30))
        )
        val result = PartTimeComplementaryHoursV2.calculateWeek(1200, 1320, 10.0,
            confirmedSchedule = schedule, referenceEpochDay = 150)
        assertEquals(25.0, result.grossToAdd, 0.001)
        assertTrue(result.confirmedScheduleUsed)
        val rejected = listOf(schedule.copy(sourceId = " "), schedule.copy(contractualMinutes = 1100),
            schedule.copy(effectiveToEpochDay = 149), schedule.copy(tiers = listOf(OvertimeTierV2(1210, 1320, 1.2))),
            schedule.copy(tiers = listOf(OvertimeTierV2(1200, 1300, 1.2), OvertimeTierV2(1250, 1320, 1.3))))
        for (candidate in rejected) {
            val fallback = PartTimeComplementaryHoursV2.calculateWeek(1200, 1320, 10.0,
                confirmedSchedule = candidate, referenceEpochDay = 150)
            assertTrue(!fallback.confirmedScheduleUsed)
            assertEquals(22.0, fallback.grossToAdd, 0.001)
        }
        assertTrue(!PartTimeComplementaryHoursV2.calculateWeek(1200, 1320, 10.0,
            confirmedSchedule = schedule).confirmedScheduleUsed)
    }

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

    @Test(expected = IllegalArgumentException::class)
    fun negativePaidMinutesAreRejectedInsteadOfClampedToZero() {
        PartTimeComplementaryHoursV2.calculateWeek(
            contractualMinutes = 28 * 60,
            paidMinutes = -1,
            grossHourlyRate = 10.0
        )
    }
}
