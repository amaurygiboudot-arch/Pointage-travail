package com.amaury.pointage.v2

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V2SegmentedWorkedGrossProductionBridgeTest {
    @Test
    fun coverageExpandsPeriodToWholeIsoWeeks() {
        val tuesday = LocalDate.of(2026, 9, 1).toEpochDay()
        val wednesday = LocalDate.of(2026, 9, 30).toEpochDay()

        val bounds = V2SegmentedWorkedGrossProductionBridge.coverageBounds(tuesday, wednesday)

        assertEquals(LocalDate.of(2026, 8, 31).toEpochDay(), bounds?.first)
        assertEquals(LocalDate.of(2026, 10, 4).toEpochDay(), bounds?.second)
    }

    @Test
    fun invertedPeriodIsRejected() {
        assertNull(V2SegmentedWorkedGrossProductionBridge.coverageBounds(10, 9))
    }
}
